package com.greatwall.callring.data.backup

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException

class EncryptedBackupFormatTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password get() = "correct horse battery staple".toCharArray()

    @Test fun streamsFilesAcrossChunkBoundariesAndPreservesNamesAndEmptyFiles() {
        val input = temporary.newFolder("source")
        File(input, "core.db").writeBytes(ByteArray(196613) { (it % 251).toByte() })
        File(input, "empty").writeBytes(byteArrayOf())
        val archive = ByteArrayOutputStream()
        EncryptedBackupFormat.write(input, archive, password)
        val output = temporary.newFolder("output")
        EncryptedBackupFormat.read(ByteArrayInputStream(archive.toByteArray()), output, password)
        assertArrayEquals(File(input, "core.db").readBytes(), File(output, "core.db").readBytes())
        assertEquals(0L, File(output, "empty").length())
    }

    @Test fun wrongPasswordAndTamperingNeverLeaveValidatedOutput() {
        val input = temporary.newFolder("source")
        File(input, "core.db").writeText("private caller data")
        val bytes = ByteArrayOutputStream().also { EncryptedBackupFormat.write(input, it, password) }.toByteArray()
        for ((index, damaged) in listOf(bytes, bytes.copyOf().apply { this[lastIndex - 7] = (this[lastIndex - 7].toInt() xor 1).toByte() }).withIndex()) {
            val output = temporary.newFolder("wrong$index")
            assertThrows(BackupFormatException::class.java) {
                EncryptedBackupFormat.read(ByteArrayInputStream(damaged), output, if (index == 0) "incorrect".toCharArray() else password)
            }
            assertTrue(output.listFiles()!!.isEmpty())
        }
    }

    @Test fun truncationAndTrailingBytesAreRejected() {
        val input = temporary.newFolder("source")
        File(input, "core.db").writeText("data")
        val bytes = ByteArrayOutputStream().also { EncryptedBackupFormat.write(input, it, password) }.toByteArray()
        listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(7)).forEachIndexed { i, invalid ->
            val output = temporary.newFolder("truncated$i")
            assertThrows(BackupFormatException::class.java) { EncryptedBackupFormat.read(ByteArrayInputStream(invalid), output, password) }
            assertTrue(output.listFiles()!!.isEmpty())
        }
    }

    @Test fun boundedLimitsRejectOversizedEntriesBeforeCopyingAndCancellationPropagates() {
        val input = temporary.newFolder("source")
        File(input, "core.db").writeText("payload")
        assertThrows(BackupFormatException::class.java) {
            EncryptedBackupFormat.write(input, ByteArrayOutputStream(), password, BackupLimits(maxEntryBytes = 3))
        }
        assertThrows(CancellationException::class.java) {
            EncryptedBackupFormat.write(input, ByteArrayOutputStream(), password, checkCancellation = { throw CancellationException() })
        }
    }

    @Test fun kdfParametersAreBoundedBeforeDerivation() {
        val input = temporary.newFolder("source")
        val bytes = ByteArrayOutputStream().also { EncryptedBackupFormat.write(input, it, password) }.toByteArray()
        // Magic (8), format (4), then PBKDF2 iteration count (4).
        for (i in 12..15) bytes[i] = 0x7f
        assertThrows(BackupFormatException::class.java) {
            EncryptedBackupFormat.read(ByteArrayInputStream(bytes), temporary.newFolder("output"), password)
        }
    }
    @Test fun authenticatedTraversalDuplicatesAndChecksumMismatchAreRejected() {
        val cases = listOf(listOf("../escape" to "data"), listOf("/absolute" to "data"), listOf("a\\b" to "data"), listOf("same" to "one", "same" to "two"))
        cases.forEachIndexed { index, entries ->
            val output = temporary.newFolder("malicious$index")
            assertThrows(BackupFormatException::class.java) { EncryptedBackupFormat.read(ByteArrayInputStream(authenticated(entries)), output, password) }
            assertTrue(output.listFiles()!!.isEmpty())
        }
        val output = temporary.newFolder("bad-checksum")
        assertThrows(BackupFormatException::class.java) { EncryptedBackupFormat.read(ByteArrayInputStream(authenticated(listOf("core.db" to "data"), corruptDigest = true)), output, password) }
        assertTrue(output.listFiles()!!.isEmpty())
    }

    @Test fun reorderedAuthenticatedFramesAndReadLimitsAreRejected() {
        val source = temporary.newFolder("frame-source")
        java.io.File(source, "core.db").writeBytes(ByteArray(196613) { (it % 251).toByte() })
        val archive = ByteArrayOutputStream().also { EncryptedBackupFormat.write(source, it, password) }.toByteArray()
        val header = 40
        fun frameEnd(start: Int) = start + 4 + java.nio.ByteBuffer.wrap(archive, start, 4).int + 16
        val firstEnd = frameEnd(header)
        val secondEnd = frameEnd(firstEnd)
        val reordered = archive.copyOfRange(0, header) + archive.copyOfRange(firstEnd, secondEnd) + archive.copyOfRange(header, firstEnd) + archive.copyOfRange(secondEnd, archive.size)
        assertThrows(BackupFormatException::class.java) { EncryptedBackupFormat.read(ByteArrayInputStream(reordered), temporary.newFolder("reordered"), password) }
        assertThrows(BackupFormatException::class.java) { EncryptedBackupFormat.read(ByteArrayInputStream(archive), temporary.newFolder("read-limit"), password, BackupLimits(maxEntryBytes = 3)) }
        var checks = 0
        val cancelled = temporary.newFolder("read-cancelled")
        assertThrows(CancellationException::class.java) {
            EncryptedBackupFormat.read(ByteArrayInputStream(archive), cancelled, password, checkCancellation = { if (++checks == 8) throw CancellationException() })
        }
        assertTrue(cancelled.listFiles()!!.isEmpty())
    }

    /** Adversarial writer: independent framing, bypassing production path validation. */
    private fun authenticated(entries: List<Pair<String, String>>, corruptDigest: Boolean = false): ByteArray {
        val plain = ByteArrayOutputStream()
        java.io.DataOutputStream(plain).apply {
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray()
                writeByte(1); writeUTF(name); writeLong(bytes.size.toLong())
                write(if (corruptDigest) ByteArray(32) else java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); write(bytes)
            }
            writeByte(0); writeInt(entries.size); writeLong(entries.sumOf { it.second.toByteArray().size.toLong() })
        }
        val salt = ByteArray(16) { it.toByte() }
        val noncePrefix = ByteArray(8) { (it + 16).toByte() }
        val header = java.nio.ByteBuffer.allocate(40).put("CRBACKUP".toByteArray()).putInt(1).putInt(210_000).put(salt).put(noncePrefix).array()
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, 210_000, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val result = ByteArrayOutputStream(); result.write(header)
        java.io.DataOutputStream(result).apply {
            for ((sequence, frame) in listOf(plain.toByteArray(), byteArrayOf()).withIndex()) {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, java.nio.ByteBuffer.allocate(12).put(noncePrefix).putInt(sequence).array()))
                cipher.updateAAD(header); cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putInt(sequence).putInt(frame.size).array())
                writeInt(frame.size); write(cipher.doFinal(frame))
            }
        }
        key.fill(0)
        return result.toByteArray()
    }
}
