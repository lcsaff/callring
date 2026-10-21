package com.greatwall.callring.platform.document

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
