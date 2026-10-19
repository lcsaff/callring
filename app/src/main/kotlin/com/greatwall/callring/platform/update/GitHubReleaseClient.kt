package com.greatwall.callring.platform.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal sealed class LatestFetch {
    data class Fetched(val release: GitHubRelease, val etag: String?) : LatestFetch()
    data class NotModified(val etag: String?) : LatestFetch()
    data class Failed(val detail: String) : LatestFetch()
}

internal sealed class ManifestFetch {
    data class Fetched(val manifest: UpdateManifest) : ManifestFetch()
    data class Failed(val detail: String) : ManifestFetch()
}

/** GitHub 检查与元数据读取；只有带自定义 UA 的 HTTPS 请求，凭据从不进入请求。 */
internal class GitHubReleaseClient(
    private val openConnection: (URL) -> HttpURLConnection,
    private val userAgent: String,
    private val connectTimeoutMs: Int = NETWORK_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = NETWORK_READ_TIMEOUT_MS,
) {
    fun fetchLatest(ifNoneMatch: String?): LatestFetch = try {
        val http = openConnection(URL(RELEASES_API_URL))
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = connectTimeoutMs
            http.readTimeout = readTimeoutMs
            http.requestMethod = "GET"
            http.setRequestProperty("User-Agent", userAgent)
            http.setRequestProperty("Accept", "application/vnd.github+json")
            if (ifNoneMatch != null) http.setRequestProperty("If-None-Match", ifNoneMatch)
            val status = http.responseCode
            when {
                status == 304 -> LatestFetch.NotModified(http.getHeaderField("ETag") ?: ifNoneMatch)
                status == 200 -> {
                    val bytes = readBounded(http, MAX_RELEASE_BYTES)
                    LatestFetch.Fetched(parseLatestRelease(String(bytes, Charsets.UTF_8)), http.getHeaderField("ETag"))
                }
                status == 403 && http.getHeaderField("X-RateLimit-Remaining") == "0" -> LatestFetch.Failed("rate_limited")
                status == 403 -> LatestFetch.Failed("forbidden")
                status == 429 -> LatestFetch.Failed("rate_limited")
                status == 404 -> LatestFetch.Failed("not_found")
                else -> LatestFetch.Failed("http_$status")
            }
        } finally {
            runCatching { http.disconnect() }
        }
    } catch (_: SocketTimeoutException) {
        LatestFetch.Failed("timeout")
    } catch (_: IOException) {
        LatestFetch.Failed("network")
    } catch (_: UpdateFormatException) {
        LatestFetch.Failed("release_json")
    }

    fun fetchManifest(release: GitHubRelease): ManifestFetch {
        val asset = release.assets.firstOrNull { it.name == UPDATE_MANIFEST_ASSET }
            ?: return ManifestFetch.Failed("manifest_missing")
        return try {
            val http = openConnection(URL(asset.downloadUrl))
            try {
                http.instanceFollowRedirects = true
                http.connectTimeout = connectTimeoutMs
                http.readTimeout = readTimeoutMs
                http.requestMethod = "GET"
                http.setRequestProperty("User-Agent", userAgent)
                http.setRequestProperty("Accept", "application/json")
                if (http.responseCode !in 200..299) return ManifestFetch.Failed("manifest_http_${http.responseCode}")
                val bytes = readBounded(http, MAX_MANIFEST_BYTES)
                ManifestFetch.Fetched(parseUpdateManifest(String(bytes, Charsets.UTF_8)))
            } finally {
                runCatching { http.disconnect() }
            }
        } catch (_: SocketTimeoutException) {
            ManifestFetch.Failed("manifest_timeout")
        } catch (_: IOException) {
            ManifestFetch.Failed("manifest_network")
        } catch (_: UpdateFormatException) {
            ManifestFetch.Failed("manifest_invalid")
        }
    }

    private fun readBounded(http: HttpURLConnection, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        http.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("body_too_large")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
