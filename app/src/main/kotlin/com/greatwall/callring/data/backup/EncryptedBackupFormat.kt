package com.greatwall.callring.data.backup

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.CancellationException

/** Public failures deliberately contain no paths, source text, password or provider details. */
class BackupFormatException : IOException("backup.invalid_or_incomplete")

data class BackupLimits(
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 32L * 1024 * 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024 * 1024,
) {
    init { require(maxEntries in 1..100_000 && maxEntryBytes in 0..32L * 1024 * 1024 * 1024 && maxTotalBytes in 0..64L * 1024 * 1024 * 1024) }
}

/**
 * Version 1: fixed authenticated header, PBKDF2-HMAC-SHA256, AES-256-GCM framed stream.
 * Each <=64 KiB frame has its own nonce and authenticates the complete header and sequence.
 * A mandatory authenticated empty final frame prevents truncation. No compression is accepted.
 * File entries carry SHA-256 and exact lengths; the decoder only returns a completely verified
 * private staging directory. Neither side holds archive contents or entry collections in RAM.
 */
object EncryptedBackupFormat {
    private val magic = byteArrayOf(67, 82, 66, 65, 67, 75, 85, 80) // CRBACKUP
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val CHUNK = 64 * 1024
    private const val HEADER_SIZE = 8 + 4 + 4 + 16 + 8
    private val random = SecureRandom()

    fun write(source: File, output: OutputStream, password: CharArray, limits: BackupLimits = BackupLimits(), checkCancellation: () -> Unit = {}) {
        require(password.isNotEmpty()) { "backup.password_required" }
        checkCancellation()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(VERSION).putInt(ITERATIONS).put(salt).put(prefix).array()
        val key = derive(password, salt, ITERATIONS)
        try {
            output.write(header)
            val framed = FrameOutput(output, key, prefix, header, checkCancellation)
            val data = DataOutputStream(framed)
            var count = 0
            var total = 0L
            val root = source.canonicalFile
            ensure(root.isDirectory)
            root.walkTopDown().onEnter { dir -> ensure(!Files.isSymbolicLink(dir.toPath())); true }.filter { it.isFile }.forEach { file ->
                checkCancellation()
                ensure(!Files.isSymbolicLink(file.toPath()))
                val name = file.relativeTo(root).invariantSeparatorsPath
                validateName(name)
                ensure(++count <= limits.maxEntries)
                val length = file.length()
                ensure(length <= limits.maxEntryBytes && length <= limits.maxTotalBytes - total)
                total += length
                val digest = digest(file, checkCancellation)
                data.writeByte(1)
                data.writeUTF(name)
                data.writeLong(length)
                data.write(digest)
                file.inputStream().use { input -> copyExact(input, data, length, checkCancellation); ensure(input.read() == -1) }
            }
            data.writeByte(0)
            data.writeInt(count)
            data.writeLong(total)
            data.flush()
            framed.finish()
            output.flush()
        } finally { key.fill(0) }
    }

    fun read(input: InputStream, destination: File, password: CharArray, limits: BackupLimits = BackupLimits(), checkCancellation: () -> Unit = {}) {
        require(password.isNotEmpty()) { "backup.password_required" }
        ensure(destination.isDirectory && destination.listFiles()?.isEmpty() == true)
        try {
            checkCancellation()
            val raw = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE)
            raw.readFully(header)
            val parameters = ByteBuffer.wrap(header)
            val readMagic = ByteArray(8).also(parameters::get)
            ensure(readMagic.contentEquals(magic) && parameters.int == VERSION)
            val iterations = parameters.int
            // Bound untrusted cost before invoking JCA. v1 currently emits exactly 210,000.
            ensure(iterations in 100_000..600_000)
            val salt = ByteArray(16).also(parameters::get)
            val prefix = ByteArray(8).also(parameters::get)
            val key = derive(password, salt, iterations)
            try {
                val framed = FrameInput(raw, key, prefix, header, checkCancellation)
                val data = DataInputStream(framed)
                var count = 0
                var total = 0L
                // Duplicate protection is persisted as destination existence, bounded independently
                // from the payload; no unbounded in-memory file-name set.
                while (true) {
                    checkCancellation()
                    when (data.readUnsignedByte()) {
                        0 -> { ensure(data.readInt() == count && data.readLong() == total); break }
                        1 -> Unit
                        else -> throw BackupFormatException()
                    }
                    ensure(++count <= limits.maxEntries)
                    val name = data.readUTF()
                    validateName(name)
                    val length = data.readLong()
                    ensure(length >= 0 && length <= limits.maxEntryBytes && length <= limits.maxTotalBytes - total)
                    total += length
                    val expected = ByteArray(32).also(data::readFully)
                    val file = File(destination, name)
                    ensure(file.canonicalPath.startsWith(destination.canonicalPath + File.separator))
                    ensure(!file.exists())
                    val parent = requireNotNull(file.parentFile)
                    ensure(parent.isDirectory || parent.mkdirs())
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(CHUNK)
                        var remaining = length
                        while (remaining > 0) {
                            checkCancellation()
                            val n = data.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            ensure(n > 0)
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            remaining -= n
                        }
                        output.fd.sync()
                    }
                    ensure(MessageDigest.isEqual(expected, digest.digest()))
                }
                // Forces consumption/authentication of the final frame and rejects extra plaintext.
                ensure(data.read() == -1)
                ensure(input.read() == -1)
            } finally { key.fill(0) }
        } catch (cancelled: CancellationException) {
            destination.listFiles()?.forEach { it.deleteRecursively() }
            throw cancelled
        } catch (_: Exception) {
            destination.listFiles()?.forEach { it.deleteRecursively() }
            throw BackupFormatException()
        }
    }

    internal fun validateName(name: String) {
        ensure(name.isNotEmpty() && name.length <= 1024 && name.none { it == '\\' || it == ':' || it.code < 32 })
        ensure(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') })
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun crypt(mode: Int, key: ByteArray, prefix: ByteArray, header: ByteArray, sequence: Int, plainLength: Int, value: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(sequence).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        cipher.updateAAD(ByteBuffer.allocate(8).putInt(sequence).putInt(plainLength).array())
        return cipher.doFinal(value)
    }

    private class FrameOutput(private val output: OutputStream, private val key: ByteArray, private val prefix: ByteArray, private val header: ByteArray, private val check: () -> Unit) : OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var length = 0
        private var sequence = 0
        override fun write(value: Int) { if (length == CHUNK) frame(); buffer[length++] = value.toByte() }
        override fun write(bytes: ByteArray, offset: Int, size: Int) {
            var at = offset
            var left = size
            while (left > 0) { if (length == CHUNK) frame(); val n = minOf(left, CHUNK - length); bytes.copyInto(buffer, length, at, at + n); length += n; at += n; left -= n }
        }
        private fun frame() {
            check(); ensure(sequence < Int.MAX_VALUE)
            val encrypted = crypt(Cipher.ENCRYPT_MODE, key, prefix, header, sequence++, length, buffer.copyOf(length))
            DataOutputStream(output).apply { writeInt(length); write(encrypted) }
            length = 0
        }
        fun finish() { if (length > 0) frame(); frame() }
    }

    private class FrameInput(private val input: DataInputStream, private val key: ByteArray, private val prefix: ByteArray, private val header: ByteArray, private val check: () -> Unit) : InputStream() {
        private var buffer = byteArrayOf()
        private var position = 0
        private var sequence = 0
        private var finished = false
        private fun availableFrame(): Boolean {
            if (position < buffer.size) return true
            if (finished) return false
            check(); ensure(sequence < Int.MAX_VALUE)
            val size = input.readInt()
            ensure(size in 0..CHUNK)
            val encrypted = ByteArray(size + 16).also(input::readFully)
            buffer = crypt(Cipher.DECRYPT_MODE, key, prefix, header, sequence++, size, encrypted)
            position = 0
            if (size == 0) { finished = true; return false }
            return true
        }
        override fun read(): Int = if (availableFrame()) buffer[position++].toInt() and 255 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!availableFrame()) return -1
            val count = minOf(length, buffer.size - position)
            buffer.copyInto(bytes, offset, position, position + count)
            position += count
            return count
        }
    }

    private fun digest(file: File, check: () -> Unit): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val buffer = ByteArray(CHUNK); while (true) { check(); val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) } }
        digest.digest()
    }
    private fun copyExact(input: InputStream, output: OutputStream, length: Long, check: () -> Unit) {
        val buffer = ByteArray(CHUNK)
        var remaining = length
        while (remaining > 0) { check(); val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); ensure(n > 0); output.write(buffer, 0, n); remaining -= n }
    }
    private fun ensure(condition: Boolean) { if (!condition) throw BackupFormatException() }
}
