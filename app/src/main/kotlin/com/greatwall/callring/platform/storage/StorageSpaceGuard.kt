package com.greatwall.callring.platform.storage

import java.io.File
import java.io.IOException

object StorageSpaceGuard {
    fun requireAvailable(freeBytes: Long?, requiredBytes: Long) {
        require(requiredBytes >= 0)
        if (freeBytes == null) throw IOException("storage.space_unknown")
        if (freeBytes < requiredBytes) throw IOException("storage.insufficient_space")
    }
    fun requireWritable(directory: File) = requireAvailable(directory.usableSpace, 16L * 1024 * 1024)
}
