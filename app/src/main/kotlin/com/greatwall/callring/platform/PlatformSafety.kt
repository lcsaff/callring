package com.greatwall.callring.platform

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class DocumentLimitExceeded : IOException("document.too_large")

internal fun validateDocumentUri(value: String) {
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("document.invalid_uri")
        }
    require(
        uri.scheme == "content" &&
            !uri.isOpaque &&
            !uri.rawAuthority.isNullOrEmpty() &&
            uri.fragment == null &&
            uri.userInfo == null
    ) {
        "document.invalid_uri"
    }
}

internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    checkCancellation: () -> Unit = {},
): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        checkCancellation()
        if (count < 0) return total
        if (count == 0) continue
        if (total > limit - count) throw DocumentLimitExceeded()
        output.write(buffer, 0, count)
        total += count
    }
}

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
    fun publish(expected: Long, block: () -> Unit): Boolean {
        return publish(expected, {}, block)
    }

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

/**
 * Both files must be in the same directory/filesystem. A failed metadata write restores the old
 * file.
 */
internal fun replaceAudioAtomically(
    candidate: File,
    selected: File,
    checkCancellation: () -> Unit = {},
    commitMetadata: () -> Unit,
) {
    require(candidate.parentFile?.canonicalFile == selected.parentFile?.canonicalFile) {
        "audio.invalid_directory"
    }
    val backup = File.createTempFile("audio-backup-", ".tmp", selected.parentFile)
    val hadPrevious = selected.exists()
    try {
        if (hadPrevious)
            Files.copy(selected.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // Backup copying belongs to preparation. The atomic rename is the publication boundary.
        checkCancellation()
        Files.move(
            candidate.toPath(),
            selected.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        try {
            commitMetadata()
        } catch (failure: Throwable) {
            try {
                if (hadPrevious)
                    Files.move(
                        backup.toPath(),
                        selected.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                else Files.deleteIfExists(selected.toPath())
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    } finally {
        backup.delete()
    }
}
