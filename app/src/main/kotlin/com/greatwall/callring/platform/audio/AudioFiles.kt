package com.greatwall.callring.platform.audio

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption


/**
 * Merely assembling the application graph must not call Context.getFilesDir on the caller's thread.
 */
internal class AudioFiles(directoryProvider: () -> File) {
    val directory: File by lazy(directoryProvider)
    val selected: File
        get() = File(directory, "selected_audio")
}

/** Copy/decoding happens outside the lock; selection publication and invalidation share it. */
internal class AudioGeneration {
    private var generation = 0L

    @Synchronized fun begin(): Long = ++generation

    @Synchronized
    fun publish(expected: Long, checkCancellation: () -> Unit, block: () -> Unit): Boolean {
        if (expected != generation) return false
        checkCancellation()
        block()
        return true
    }

    @Synchronized
    fun invalidate(block: () -> Unit) {
        generation++
        block()
    }
}

/** A rollback copy is prepared before the coordinator fence; generation rejects stale preparations. */
internal class PreparedAudioBackup(val selected: File, val file: File, val hadPrevious: Boolean) : java.io.Closeable {
    override fun close() { file.delete() }
}

internal fun prepareAudioBackup(selected: File): PreparedAudioBackup {
    val backup = File.createTempFile("audio-backup-", ".tmp", selected.parentFile)
    val hadPrevious = selected.exists()
    try {
        if (hadPrevious) Files.copy(selected.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return PreparedAudioBackup(selected, backup, hadPrevious)
    } catch (failure: Throwable) {
        backup.delete()
        throw failure
    }
}

/** Atomic rename and metadata commit; a failed metadata write restores the prepared previous file. */
internal fun replaceAudioAtomically(
    candidate: File,
    selected: File,
    backup: PreparedAudioBackup,
    checkCancellation: () -> Unit = {},
    commitMetadata: () -> Unit,
) {
    require(candidate.parentFile?.canonicalFile == selected.parentFile?.canonicalFile && backup.selected.canonicalFile == selected.canonicalFile) {
        "audio.invalid_directory"
    }
    try {
        checkCancellation()
        Files.move(candidate.toPath(), selected.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        try {
            commitMetadata()
        } catch (failure: Throwable) {
            try {
                if (backup.hadPrevious) Files.move(backup.file.toPath(), selected.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else Files.deleteIfExists(selected.toPath())
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    } finally {
        backup.close()
    }
}
