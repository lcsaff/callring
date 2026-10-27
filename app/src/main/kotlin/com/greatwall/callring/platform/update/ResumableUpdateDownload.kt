package com.greatwall.callring.platform.update

import com.greatwall.callring.contracts.runtime.AppReleaseInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpdateDownloadFailure(val reason: String, val detail: String, val discardPartial: Boolean = false) : Exception(detail)

/** Byte ranges are bound to the persisted release digest; every completed file is hashed in full. */
internal suspend fun downloadUpdatePart(
    latest: AppReleaseInfo, part: File, userAgent: String, readTimeoutMs: Int,
    openConnection: (URL) -> HttpURLConnection,
    connectionChanged: (HttpURLConnection?) -> Unit,
    progress: (Long) -> Unit,
    commitFile: () -> Unit,
) {
    var offset = part.length()
    var retryFromZero = false
    while (offset < latest.sizeBytes) {
        currentCoroutineContext().ensureActive()
        val http = openConnection(URL(latest.downloadUrl))
        connectionChanged(http)
        try {
            http.instanceFollowRedirects = true
            http.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            http.readTimeout = readTimeoutMs
            http.requestMethod = "GET"
            http.setRequestProperty("User-Agent", userAgent)
            http.setRequestProperty("Accept", "application/vnd.android.package-archive")
            http.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
            val code = http.responseCode
            if (code == 416 && offset > 0 && !retryFromZero) {
                currentCoroutineContext().ensureActive()
                FileOutputStream(part, false).close()
                offset = 0
                retryFromZero = true
                progress(0)
                continue
            }
            if (code != 200 && code != 206) throw IOException("http_$code")
            if (http.getHeaderField("Content-Encoding")?.let { it != "identity" } == true)
                throw UpdateDownloadFailure(UpdateReasons.DOWNLOAD_FAILED, "unexpected_encoding")
            val end = if (code == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(http.getHeaderField("Content-Range").orEmpty())
                val values = range?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                val start = values?.get(0)
                val last = values?.get(1)
                val total = values?.get(2)
                if (start != offset || last == null || last < offset || total != latest.sizeBytes || last >= total)
                    throw UpdateDownloadFailure(UpdateReasons.DOWNLOAD_FAILED, "invalid_content_range")
                last
            } else {
                offset = 0 // A server may ignore Range. Replace the partial file instead of appending.
                latest.sizeBytes - 1
            }
            val declared = http.contentLengthLong
            val expected = end - offset + 1
            if (declared > 0 && declared != expected)
                throw UpdateDownloadFailure(UpdateReasons.DOWNLOAD_FAILED, "invalid_content_length")
            var received = 0L
            var published = offset
            currentCoroutineContext().ensureActive()
            FileOutputStream(part, code == 206 && offset > 0).use { output ->
                progress(offset)
                http.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (received + count > expected)
                            throw UpdateDownloadFailure(UpdateReasons.DOWNLOAD_FAILED, "size_overflow", discardPartial = true)
                        output.write(buffer, 0, count)
                        received += count
                        if (offset + received - published >= 256 * 1024) {
                            published = offset + received
                            progress(published)
                        }
                    }
                }
            }
            offset += received
            progress(offset)
            if (received != expected) throw IOException("incomplete_response")
        } finally {
            runCatching { http.disconnect() }
            connectionChanged(null)
        }
    }
    currentCoroutineContext().ensureActive()
    if (part.length() != latest.sizeBytes || !sha256File(part).equals(latest.sha256, ignoreCase = true))
        throw UpdateDownloadFailure(UpdateReasons.VERIFY_FAILED, "sha256_mismatch", discardPartial = true)
    currentCoroutineContext().ensureActive()
    commitFile()
}
