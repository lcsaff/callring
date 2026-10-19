package com.greatwall.callring.platform.update

import com.greatwall.callring.application.api.ApiResult
import com.greatwall.callring.application.api.AppReleaseInfo
import com.greatwall.callring.application.api.AppVersionInfo
import com.greatwall.callring.application.api.UpdatePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidAppUpdateTest {
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val release = releaseJson(apkSize = apk.size.toLong())
    private val manifest = manifestJson(apk)
    private lateinit var dir: File
    private val persistence = MemoryUpdatePersistence()
    private val current = AppVersionInfo("0.3.0", 3)
    private val matchingInspection = ApkInspection(CALLRING_PACKAGE, 4, 29, "cert-current")

    @Before fun setUp() {
        dir = File(RuntimeEnvironment.getApplication().cacheDir, "update-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newApi(
        transport: (java.net.URL) -> java.net.HttpURLConnection,
        updatable: Boolean = true,
        inspector: ApkInspector = ApkInspector { matchingInspection },
        certificate: InstalledCertificateReader = InstalledCertificateReader { "cert-current" },
        installer: FakeInstaller = FakeInstaller(),
    ) = AndroidAppUpdate(
        context = RuntimeEnvironment.getApplication(),
        build = { UpdateBuildInfo(current, CALLRING_PACKAGE, updatable, 33) },
        directory = { dir },
        persistence = persistence,
        openConnection = transport,
        apkInspector = inspector,
        installedCertificate = certificate,
        installer = installer,
        clock = { 1_000L },
        io = Dispatchers.IO,
    )

    private fun <T> ApiResult<T>.ok(): T = (this as ApiResult.Success).value

    private fun seedVerifiedFile() {
        dir.mkdirs()
        val file = File(dir, "callring-0.4.0.apk").apply { writeBytes(apk) }
        val latest = AppReleaseInfo(
            versionName = "0.4.0", versionCode = 4, publishedAtEpochMillis = 0, notes = "",
            assetName = "callring-0.4.0.apk", sizeBytes = apk.size.toLong(),
            sha256 = sha256Hex(apk), downloadUrl = "https://example.test/callring-0.4.0.apk",
        )
        persistence.write(encodeUpdateState(PersistedUpdateState(
            phase = UpdatePhase.VERIFIED,
            checkedAtEpochMillis = 1,
            latest = latest,
            downloadedBytes = apk.size.toLong(),
            totalBytes = apk.size.toLong(),
            fileName = file.name,
            verified = true,
        )))
    }

    private suspend fun awaitPhase(api: AndroidAppUpdate, phase: UpdatePhase, timeoutMs: Long = 10_000): com.greatwall.callring.application.api.UpdateStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = api.status().ok()
            if (status.phase == phase) return status
            delay(10)
        }
        error("phase $phase not reached, last=${api.status().ok()}")
    }

    @Test fun debugOrQaBuildDoesNotUpdateAndNeverTouchesNetwork() = runBlocking {
        val transport = FakeTransport { error("no network expected") }
        val status = newApi(transport, updatable = false).check().ok()
        assertEquals(UpdatePhase.UNSUPPORTED_DEVICE, status.phase)
        assertEquals(UpdateReasons.UNSUPPORTED_BUILD, status.failureReason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun secondCheckSendsEtagAndUses304Cache() = runBlocking {
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL ->
                    if (connection.requestHeader("If-None-Match") == "etag-1") FakeResponse(304)
                    else FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
                connection.url.toString().endsWith(UPDATE_MANIFEST_ASSET) -> FakeResponse(200, manifest.toByteArray())
                else -> FakeResponse(404)
            }
        }
        val api = newApi(transport)
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
        assertEquals(3, transport.requests.size)
        assertEquals("etag-1", transport.requests[2].requestHeader("If-None-Match"))
    }

    @Test fun downloadVerifiesAndKeepsAReusableFile() = runBlocking {
        val transport = successfulUpdateTransport(release, manifest, apk)
        val installer = FakeInstaller()
        val api = newApi(transport, installer = installer)
        api.check().ok()
        api.download().ok()
        val status = awaitPhase(api, UpdatePhase.VERIFIED)
        val file = File(dir, "callring-0.4.0.apk")
        assertTrue(file.isFile)
        assertEquals(apk.size.toLong(), file.length())
        assertEquals(apk.size.toLong(), status.downloadedBytes)
        assertEquals(apk.size.toLong(), status.totalBytes)
        assertTrue(transport.requests.any { it.url.toString().endsWith(".apk") })
        assertEquals(0, installer.launches)
    }

    @Test fun shaMismatchDeletesTheDownloadedFile() = runBlocking {
        val transport = successfulUpdateTransport(release, manifestJson(apk, shaOverride = "0".repeat(64)), apk)
        val api = newApi(transport)
        api.check().ok()
        api.download().ok()
        val status = awaitPhase(api, UpdatePhase.VERIFY_FAILED)
        assertEquals(UpdateReasons.VERIFY_FAILED, status.failureReason)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadHttpFailureMapsToDownloadFailedAndCleansUp() = runBlocking {
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL -> FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
                connection.url.toString().endsWith(UPDATE_MANIFEST_ASSET) -> FakeResponse(200, manifest.toByteArray())
                else -> FakeResponse(500)
            }
        }
        val api = newApi(transport)
        api.check().ok()
        api.download().ok()
        val status = awaitPhase(api, UpdatePhase.VERIFY_FAILED)
        assertEquals(UpdateReasons.DOWNLOAD_FAILED, status.failureReason)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelStopsTheRunningDownloadAndDeletesThePartialFile() = runBlocking {
        val slow = object : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < apk.size) apk[index++].toInt() and 0xff else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index >= apk.size) return -1
                Thread.sleep(15)
                val count = minOf(length, apk.size - index)
                System.arraycopy(apk, index, buffer, offset, count)
                index += count
                return count
            }
        }
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL -> FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
                connection.url.toString().endsWith(UPDATE_MANIFEST_ASSET) -> FakeResponse(200, manifest.toByteArray())
                else -> FakeResponse(200, streamFactory = { slow }, contentLength = apk.size.toLong())
            }
        }
        val api = newApi(transport)
        api.check().ok()
        api.download().ok()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && api.status().ok().downloadedBytes <= 0) delay(10)
        assertTrue(api.status().ok().downloadedBytes > 0)
        val cancelled = api.cancel().ok()
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, cancelled.phase)
        assertEquals(0, cancelled.downloadedBytes)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun restartRecognizesTheVerifiedFileWithoutDownloadingAgain() = runBlocking {
        val transport = successfulUpdateTransport(release, manifest, apk)
        val first = newApi(transport)
        first.check().ok()
        first.download().ok()
        awaitPhase(first, UpdatePhase.VERIFIED)

        val offline = FakeTransport { error("no network expected") }
        val restarted = newApi(offline)
        val status = restarted.status().ok()
        assertEquals(UpdatePhase.VERIFIED, status.phase)
        assertTrue(offline.requests.isEmpty())
        assertTrue(File(dir, "callring-0.4.0.apk").isFile)
    }

    @Test fun signatureMismatchRefusesInstallWithoutLaunchingTheInstaller() = runBlocking {
        seedVerifiedFile()
        val installer = FakeInstaller()
        val api = newApi(
            FakeTransport { error("no network expected") },
            inspector = ApkInspector { matchingInspection.copy(certificateSha256 = "cert-other") },
            installer = installer,
        )
        val status = api.install().ok()
        assertEquals(UpdatePhase.VERIFY_FAILED, status.phase)
        assertEquals(0, installer.launches)
        assertFalse(File(dir, "callring-0.4.0.apk").exists())
    }

    @Test fun installWithoutPermissionRequestsPermissionInsteadOfLaunching() = runBlocking {
        seedVerifiedFile()
        val installer = FakeInstaller(canInstall = false)
        val api = newApi(FakeTransport { error("no network expected") }, installer = installer)
        val status = api.install().ok()
        assertEquals(UpdatePhase.INSTALL_PERMISSION_REQUIRED, status.phase)
        assertEquals(UpdateReasons.PERMISSION_REQUIRED, status.failureReason)
        assertEquals(1, installer.permissionOpened)
        assertEquals(0, installer.launches)
    }

    @Test fun launcherFailureIsReportedWithoutInventingAVerifiedState() = runBlocking {
        seedVerifiedFile()
        val installer = FakeInstaller(launchResult = false)
        val api = newApi(FakeTransport { error("no network expected") }, installer = installer)
        val result = api.install()
        assertTrue(result is ApiResult.Failure)
        assertEquals("update.install_failed", (result as ApiResult.Failure).error.message)
        assertEquals(1, installer.launches)
        assertEquals(UpdatePhase.VERIFIED, api.status().ok().phase)
    }
}
