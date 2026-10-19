package com.greatwall.callring.platform.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

internal data class FakeResponse(
    val code: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val streamFactory: ((FakeHttpConnection) -> InputStream)? = null,
    val contentLength: Long = -1,
)

internal class FakeHttpConnection(
    url: URL,
    private val handler: (FakeHttpConnection) -> FakeResponse,
) : HttpURLConnection(url) {
    private val requestHeaders = linkedMapOf<String, String>()
    private val response by lazy { handler(this) }
    @Volatile var disconnected = false
        private set

    fun requestHeader(name: String): String? = requestHeaders[name]
    fun requestHeaders(): Map<String, String> = requestHeaders.toMap()

    override fun setRequestProperty(key: String, value: String) {
        requestHeaders[key] = value
        super.setRequestProperty(key, value)
    }

    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode(): Int = response.code
    override fun getInputStream(): InputStream =
        response.streamFactory?.invoke(this) ?: ByteArrayInputStream(response.body)
    override fun getHeaderField(name: String): String? = response.headers[name] ?: super.getHeaderField(name)
    override fun getContentLengthLong(): Long =
        response.contentLength.takeIf { it >= 0 } ?: response.body.size.toLong()
}

internal class FakeTransport(private val handler: (FakeHttpConnection) -> FakeResponse) : (URL) -> HttpURLConnection {
    val requests = CopyOnWriteArrayList<FakeHttpConnection>()
    override fun invoke(url: URL): HttpURLConnection = FakeHttpConnection(url, handler).also { requests += it }
}

internal class MemoryUpdatePersistence(initial: String? = null) : UpdatePersistence {
    @Volatile private var value: String? = initial
    @Synchronized override fun read(): String? = value
    @Synchronized override fun write(value: String?) { this.value = value }
}

internal class FakeInstaller(
    var canInstall: Boolean = true,
    var launchResult: Boolean = true,
) : UpdateInstaller {
    var permissionOpened = 0
    var launches = 0
    override fun canInstall(): Boolean = canInstall
    override fun openInstallPermission() { permissionOpened++ }
    override fun launch(apk: File): Boolean { launches++; return launchResult }
}

private fun assetJson(name: String, url: String, size: Long) =
    JSONObject().put("name", name).put("size", size).put("browser_download_url", url)

internal fun releaseJson(
    versionName: String = "0.4.0",
    tagName: String = "v$versionName",
    draft: Boolean = false,
    prerelease: Boolean = false,
    body: String = "更新说明",
    publishedAt: String = "2026-10-19T03:49:30Z",
    apkSize: Long = 4096,
    includeManifest: Boolean = true,
    includeApk: Boolean = true,
): String {
    val assets = JSONArray()
    if (includeManifest) assets.put(assetJson(UPDATE_MANIFEST_ASSET, "https://example.test/update.json", 512))
    if (includeApk) assets.put(assetJson("callring-$versionName.apk", "https://example.test/callring-$versionName.apk", apkSize))
    return JSONObject()
        .put("tag_name", tagName)
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("published_at", publishedAt)
        .put("body", body)
        .put("assets", assets)
        .toString()
}

internal fun manifestJson(
    apk: ByteArray,
    versionName: String = "0.4.0",
    versionCode: Long = 4,
    applicationId: String = CALLRING_PACKAGE,
    minSdk: Int = 29,
    assetName: String = "callring-$versionName.apk",
    sizeOverride: Long? = null,
    shaOverride: String? = null,
): String = JSONObject()
    .put("schemaVersion", 1)
    .put("applicationId", applicationId)
    .put("versionCode", versionCode)
    .put("versionName", versionName)
    .put("minSdk", minSdk)
    .put("assetName", assetName)
    .put("sizeBytes", sizeOverride ?: apk.size.toLong())
    .put("sha256", shaOverride ?: sha256Hex(apk))
    .toString()

/** 标准成功路由：releases/latest -> release，update.json -> manifest，apk -> bytes。 */
internal fun successfulUpdateTransport(
    release: String,
    manifest: String,
    apk: ByteArray,
    etag: String = "etag-1",
): FakeTransport = FakeTransport { connection ->
    when {
        connection.url.toString() == RELEASES_API_URL -> FakeResponse(
            200, release.toByteArray(), mapOf("ETag" to etag),
        )
        connection.url.toString().endsWith(UPDATE_MANIFEST_ASSET) -> FakeResponse(200, manifest.toByteArray())
        connection.url.toString().endsWith(".apk") -> FakeResponse(200, apk, contentLength = apk.size.toLong())
        else -> FakeResponse(404)
    }
}
