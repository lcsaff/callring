package com.greatwall.callring.platform.update

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseClientTest {
    private val apk = ByteArray(4096) { (it % 251).toByte() }
    private val release = releaseJson(apkSize = apk.size.toLong())
    private val manifest = manifestJson(apk)

    private fun requestHeaders(transport: FakeTransport) = transport.requests.first().requestHeaders()

    @Test fun latestUsesUserAgentAndParsesReleaseAndEtag() {
        val transport = successfulUpdateTransport(release, manifest, apk)
        val result = GitHubReleaseClient(transport, "Callring/0.3.0").fetchLatest(null)
        val fetched = result as LatestFetch.Fetched
        assertEquals("etag-1", fetched.etag)
        assertEquals("v0.4.0", fetched.release.tagName)
        assertEquals("Callring/0.3.0", requestHeaders(transport)["User-Agent"])
        assertEquals("application/vnd.github+json", requestHeaders(transport)["Accept"])
        assertNull(requestHeaders(transport)["If-None-Match"])
    }

    @Test fun conditionalRequestSendsIfNoneMatchAndHandles304() {
        val transport = FakeTransport { connection ->
            if (connection.requestHeader("If-None-Match") == "etag-1") FakeResponse(304) else FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
        }
        val client = GitHubReleaseClient(transport, "Callring/0.3.0")
        client.fetchLatest(null)
        val result = client.fetchLatest("etag-1")
        assertTrue(result is LatestFetch.NotModified)
        assertEquals("etag-1", transport.requests[1].requestHeader("If-None-Match"))
    }

    @Test fun forbiddenRateLimitedNotFoundAndServerErrorsAreDistinct() {
        fun failure(status: Int, headers: Map<String, String> = emptyMap()): String =
            (GitHubReleaseClient(FakeTransport { FakeResponse(status, headers = headers) }, "Callring/0.3.0")
                .fetchLatest(null) as LatestFetch.Failed).detail
        assertEquals("forbidden", failure(403))
        assertEquals("rate_limited", failure(403, mapOf("X-RateLimit-Remaining" to "0")))
        assertEquals("rate_limited", failure(429))
        assertEquals("not_found", failure(404))
        assertEquals("http_500", failure(500))
    }

    @Test fun networkTimeoutAndMalformedJsonMapToCheckFailures() {
        val timeout = GitHubReleaseClient(FakeTransport { throw SocketTimeoutException() }, "Callring/0.3.0")
        assertEquals("timeout", (timeout.fetchLatest(null) as LatestFetch.Failed).detail)
        val network = GitHubReleaseClient(FakeTransport { throw IOException("offline") }, "Callring/0.3.0")
        assertEquals("network", (network.fetchLatest(null) as LatestFetch.Failed).detail)
        val malformed = GitHubReleaseClient(FakeTransport { FakeResponse(200, "{oops".toByteArray()) }, "Callring/0.3.0")
        assertEquals("release_json", (malformed.fetchLatest(null) as LatestFetch.Failed).detail)
    }

    @Test fun manifestIsReadFromUpdateJsonAssetAndParsed() {
        val transport = successfulUpdateTransport(release, manifest, apk)
        val fetched = GitHubReleaseClient(transport, "Callring/0.3.0").fetchLatest(null) as LatestFetch.Fetched
        val result = GitHubReleaseClient(transport, "Callring/0.3.0").fetchManifest(fetched.release)
        val parsed = result as ManifestFetch.Fetched
        assertEquals(4, parsed.manifest.versionCode)
        assertEquals(sha256Hex(apk), parsed.manifest.sha256)
        assertEquals("Callring/0.3.0", transport.requests.last().requestHeader("User-Agent"))
    }

    @Test fun missingManifestAssetAndInvalidManifestAreReported() {
        val withoutManifest = parseLatestRelease(releaseJson(includeManifest = false))
        val client = GitHubReleaseClient(FakeTransport { FakeResponse(404) }, "Callring/0.3.0")
        assertEquals("manifest_missing", (client.fetchManifest(withoutManifest) as ManifestFetch.Failed).detail)

        val fetched = parseLatestRelease(release)
        val invalid = GitHubReleaseClient(FakeTransport { FakeResponse(200, "{}".toByteArray()) }, "Callring/0.3.0")
        assertEquals("manifest_invalid", (invalid.fetchManifest(fetched) as ManifestFetch.Failed).detail)
    }
}
