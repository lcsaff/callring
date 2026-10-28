package com.greatwall.callring.data.backup

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistent rollback journal for closed resources. The owner must drain writers before calling
 * replace and must call recover before creating ANY storage owners after process startup.
 * Directory fsync is supplied by Android's Os.fsync; test callbacks cannot prove power-loss safety.
 * Journal originals remain immutable until cleanup, so interrupted recovery is itself repeatable.
 */
class RestoreJournal(
    private val work: File,
    private val live: File,
    private val checkpoint: () -> Unit = {},
    private val syncDirectory: (File) -> Unit = ::syncJvmDirectory,
) {
    private data class Entry(val name: String, val existed: Boolean)

    fun replace(stage: File, names: List<String>, beforeCommit: () -> Unit = {}) {
        validateTargets(names)
        check(!work.exists()) { "backup.recovery_required" }
        ensure(work.mkdirs())
        val entries = names.map { Entry(it, File(live, it).exists()) }
        // Everything needed by either generation is durable before the first live mutation.
        for (entry in entries) {
            val original = File(live, entry.name)
            if (entry.existed) copyTree(original, File(work, "old/${entry.name}"))
            val candidate = File(stage, entry.name)
            if (candidate.exists()) copyTree(candidate, File(work, "new/${entry.name}"))
        }
        writeState("PREPARED", entries)
        checkpoint()
        writeState("SWITCHING", entries)
        checkpoint()
        for (entry in entries) {
            install(File(work, "new/${entry.name}"), File(live, entry.name))
            checkpoint()
        }
        beforeCommit()
        try { writeState("COMMITTED", entries) }
        catch (failure: Exception) {
            // A failed commit-marker fsync must not leave a terminal marker that would make
            // same-process recovery mistake an unacknowledged switch for a committed restore.
            writeState("SWITCHING", entries)
            throw failure
        }
        try { checkpoint(); cleanup() }
        catch (_: Exception) { /* COMMITTED is durable; cleanup is safe to retry at startup. */ }
    }

    fun recover() {
        if (!work.exists()) return
        val state = File(work, "state")
        if (!state.exists()) { cleanup(); return } // preparation had not mutated live resources
        val (phase, entries) = readState(state)
        if (phase != "COMMITTED" && phase != "ROLLED_BACK") {
            for (entry in entries) {
                val original = File(work, "old/${entry.name}")
                if (entry.existed) ensure(original.exists())
                install(if (entry.existed) original else File(work, "absent"), File(live, entry.name))
                checkpoint()
            }
            writeState("ROLLED_BACK", entries)
            checkpoint()
        }
        cleanup()
    }

    private fun writeState(phase: String, entries: List<Entry>) {
        val pending = File(work, "state.tmp")
        FileOutputStream(pending).use { stream ->
            val out = DataOutputStream(stream)
            out.writeUTF("CALLRING_RESTORE_1")
            out.writeUTF(phase)
            out.writeInt(entries.size)
            entries.forEach { out.writeUTF(it.name); out.writeBoolean(it.existed) }
            out.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), File(work, "state").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(work)
        syncDirectory(requireNotNull(work.parentFile))
    }

    private fun readState(state: File): Pair<String, List<Entry>> = try {
        DataInputStream(state.inputStream()).use { input ->
            ensure(input.readUTF() == "CALLRING_RESTORE_1")
            val phase = input.readUTF()
            ensure(phase in setOf("PREPARED", "SWITCHING", "COMMITTED", "ROLLED_BACK"))
            val count = input.readInt()
            ensure(count in 1..64)
            val entries = List(count) { Entry(input.readUTF(), input.readBoolean()) }
            validateTargets(entries.map { it.name })
            ensure(input.read() == -1)
            phase to entries
        }
    } catch (_: Exception) { throw BackupFormatException() }

    private fun validateTargets(names: List<String>) {
        ensure(names.isNotEmpty() && names.size <= 64 && names.distinct().size == names.size)
        names.forEach(EncryptedBackupFormat::validateName)
        ensure(names.none { a -> names.any { b -> a != b && b.startsWith("$a/") } })
        val root = live.canonicalPath + File.separator
        ensure(names.all { File(live, it).canonicalPath.startsWith(root) })
        ensure(names.none { val target = File(live, it).canonicalPath; work.canonicalPath == target || work.canonicalPath.startsWith(target + File.separator) })
    }

    private fun install(source: File, target: File) {
        // Copy first, so a failed copy cannot delete a live resource. An interrupted delete/move
        // leaves a durable journal and is repaired before any normal owner can open the directory.
        val scratch = File(target.parentFile, ".restore-${target.name}")
        remove(scratch)
        if (source.exists()) copyTree(source, scratch)
        ensure(requireNotNull(target.parentFile).isDirectory || requireNotNull(target.parentFile).mkdirs())
        remove(target)
        if (scratch.exists()) {
            Files.move(scratch.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        syncDirectory(requireNotNull(target.parentFile))
    }

    private fun copyTree(source: File, target: File) {
        ensure(!Files.isSymbolicLink(source.toPath()))
        if (source.isDirectory) {
            ensure(target.isDirectory || target.mkdirs())
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) } ?: throw BackupFormatException()
            syncDirectory(target)
        } else {
            ensure(source.isFile)
            ensure(requireNotNull(target.parentFile).isDirectory || requireNotNull(target.parentFile).mkdirs())
            FileOutputStream(target).use { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) }; out.fd.sync() }
            syncDirectory(requireNotNull(target.parentFile))
        }
    }

    private fun cleanup() {
        fun delete(file: File) {
            if (file.isDirectory) file.listFiles()?.forEach(::delete)
            if (file.exists()) ensure(file.delete())
            checkpoint()
        }
        delete(work)
        syncDirectory(requireNotNull(work.parentFile))
    }
    private fun remove(file: File) { if (file.exists()) ensure(file.deleteRecursively()) }
    private fun ensure(condition: Boolean) { if (!condition) throw BackupFormatException() }

    companion object {
        private fun syncJvmDirectory(directory: File) {
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
