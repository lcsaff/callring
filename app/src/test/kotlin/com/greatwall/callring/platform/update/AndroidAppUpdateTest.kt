package com.greatwall.callring.platform.update

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.AppReleaseInfo
import com.greatwall.callring.contracts.runtime.AppVersionInfo
import com.greatwall.callring.contracts.runtime.UpdatePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
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
        io = io,
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

    private suspend fun awaitPhase(api: AndroidAppUpdate, phase: UpdatePhase, timeoutMs: Long = 10_000): com.greatwall.callring.contracts.runtime.UpdateStatus {
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

    @Test fun lateStartupCheckCannotOverwriteNewerManualCheck() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL && calls.incrementAndGet() == 1 -> {
                    entered.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                    FakeResponse(503)
                }
                connection.url.toString() == RELEASES_API_URL -> FakeResponse(200, release.toByteArray())
                else -> FakeResponse(200, manifest.toByteArray())
            }
        }
        newApi(transport).use { api ->
            val startup = async(Dispatchers.Default) { api.check().ok() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
            } finally { finish.countDown() }
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, startup.await().phase)
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.status().ok().phase)
        }
    }

    @Test fun lateCheckCannotDiscardVerifiedDownload() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val transport = FakeTransport {
            entered.countDown()
            check(finish.await(10, TimeUnit.SECONDS))
            FakeResponse(503)
        }
        newApi(transport).use { api ->
            val startup = async(Dispatchers.Default) { api.check().ok() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                seedVerifiedFile()
            } finally { finish.countDown() }
            assertEquals(UpdatePhase.VERIFIED, startup.await().phase)
            assertEquals(UpdatePhase.VERIFIED, api.status().ok().phase)
            assertTrue(File(dir, "callring-0.4.0.apk").isFile)
        }
    }

    @Test fun checkWithoutCachedMetadataDoesNotSendEtag() = runBlocking {
        for (phase in listOf(UpdatePhase.UP_TO_DATE, UpdatePhase.NO_INSTALLABLE, UpdatePhase.CHECK_FAILED)) {
            persistence.write(encodeUpdateState(PersistedUpdateState(etag = "etag-1", phase = phase)))
            val transport = FakeTransport { connection ->
                when {
                    connection.url.toString() == RELEASES_API_URL ->
                        if (connection.requestHeader("If-None-Match") != null) FakeResponse(304)
                        else FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
                    else -> FakeResponse(200, manifest.toByteArray())
                }
            }
            newApi(transport).use { api ->
                assertEquals("Recovery from $phase", UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
                assertNull(transport.requests.first().requestHeader("If-None-Match"))
            }
        }
    }

    @Test fun transientManifestFailureCanBeRetriedForTheSameRelease() = runBlocking {
        var manifestRequests = 0
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL ->
                    if (connection.requestHeader("If-None-Match") != null) FakeResponse(304)
                    else FakeResponse(200, release.toByteArray(), mapOf("ETag" to "etag-1"))
                ++manifestRequests == 1 -> FakeResponse(503)
                else -> FakeResponse(200, manifest.toByteArray())
            }
        }
        newApi(transport).use { api ->
            assertEquals(UpdatePhase.CHECK_FAILED, api.check().ok().phase)
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
            assertEquals(2, manifestRequests)
        }
    }

    @Test fun responseWithoutEtagDoesNotReuseAnOlderReleaseEtag() = runBlocking {
        persistence.write(encodeUpdateState(PersistedUpdateState(etag = "old-etag")))
        val transport = FakeTransport { connection ->
            if (connection.url.toString() == RELEASES_API_URL) FakeResponse(200, release.toByteArray())
            else FakeResponse(200, manifest.toByteArray())
        }
        newApi(transport).use { api ->
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, api.check().ok().phase)
            assertNull(decodeUpdateState(persistence.read())?.etag)
        }
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

    private fun seedPartial(bytes: ByteArray, phase: UpdatePhase = UpdatePhase.DOWNLOADING) {
        seedVerifiedFile()
        File(dir, "callring-0.4.0.apk").delete()
        File(dir, "callring-0.4.0.apk.part").writeBytes(bytes)
        val state = decodeUpdateState(persistence.read())!!
        persistence.write(encodeUpdateState(state.copy(phase = phase, verified = false, downloadedBytes = bytes.size.toLong())))
    }

    @Test fun networkInterruptionRetainsActualBytesAndRetryUsesRange() = runBlocking {
        var attempts = 0
        val cut = 90_000
        val transport = FakeTransport { connection ->
            when {
                connection.url.toString() == RELEASES_API_URL -> FakeResponse(200, release.toByteArray())
                connection.url.toString().endsWith(UPDATE_MANIFEST_ASSET) -> FakeResponse(200, manifest.toByteArray())
                ++attempts == 1 -> FakeResponse(200, streamFactory = {
                    object : InputStream() {
                        var offset = 0
                        override fun read(): Int = error("buffered reads required")
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                            if (offset >= cut) throw java.io.IOException("connection lost")
                            val n = minOf(len, cut - offset)
                            apk.copyInto(buffer, off, offset, offset + n); offset += n
                            return n
                        }
                    }
                })
                else -> {
                    assertEquals("bytes=$cut-", connection.requestHeader("Range"))
                    FakeResponse(206, apk.copyOfRange(cut, apk.size), mapOf("Content-Range" to "bytes $cut-${apk.lastIndex}/${apk.size}"))
                }
            }
        }
        newApi(transport).use { api ->
            api.check().ok(); api.download().ok()
            val interrupted = awaitPhase(api, UpdatePhase.VERIFY_FAILED)
            assertEquals(cut.toLong(), interrupted.downloadedBytes)
            assertEquals(cut.toLong(), File(dir, "callring-0.4.0.apk.part").length())
            api.download().ok()
            awaitPhase(api, UpdatePhase.VERIFIED)
            assertArrayEquals(apk, File(dir, "callring-0.4.0.apk").readBytes())
        }
    }

    @Test fun restartRecoversPartialBytesAndServerIgnoringRangeRestartsCleanly() = runBlocking {
        seedPartial(apk.copyOfRange(0, 12345))
        val transport = FakeTransport { connection ->
            assertEquals("bytes=12345-", connection.requestHeader("Range"))
            assertEquals("identity", connection.requestHeader("Accept-Encoding"))
            FakeResponse(200, apk)
        }
        newApi(transport).use { api ->
            val recovered = api.status().ok()
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, recovered.phase)
            assertEquals(12345L, recovered.downloadedBytes)
            assertTrue(transport.requests.isEmpty())
            api.download().ok(); awaitPhase(api, UpdatePhase.VERIFIED)
            assertArrayEquals(apk, File(dir, "callring-0.4.0.apk").readBytes())
        }
    }

    @Test fun malformedRangeNeverAppendsBytesToPartialFile() = runBlocking {
        seedPartial(apk.copyOfRange(0, 100))
        newApi(FakeTransport { FakeResponse(206, apk.copyOfRange(100, apk.size),
            mapOf("Content-Range" to "bytes 99-${apk.lastIndex}/${apk.size}")) }).use { api ->
            api.download().ok(); awaitPhase(api, UpdatePhase.VERIFY_FAILED)
            assertArrayEquals(apk.copyOfRange(0, 100), File(dir, "callring-0.4.0.apk.part").readBytes())
            assertFalse(File(dir, "callring-0.4.0.apk").exists())
        }
    }

    @Test fun rangeNotSatisfiableRetriesOnceFromZero() = runBlocking {
        seedPartial(apk.copyOfRange(0, 100))
        var calls = 0
        newApi(FakeTransport { connection ->
            if (++calls == 1) FakeResponse(416) else {
                assertNull(connection.requestHeader("Range"))
                FakeResponse(200, apk)
            }
        }).use { api ->
            api.download().ok(); awaitPhase(api, UpdatePhase.VERIFIED)
            assertEquals(2, calls)
        }
    }

    @Test fun completedPartialIsVerifiedWithoutNetworkAndCorruptPrefixIsRejected() = runBlocking {
        seedPartial(apk.copyOf().also { it[0] = (it[0] + 1).toByte() })
        newApi(FakeTransport { error("complete partial needs no network") }).use { api ->
            api.download().ok()
            val failed = awaitPhase(api, UpdatePhase.VERIFY_FAILED)
            assertEquals(UpdateReasons.VERIFY_FAILED, failed.failureReason)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun checkingSameReleasePreservesPartialButChangedDigestDiscardsIt() = runBlocking {
        seedPartial(apk.copyOfRange(0, 12345), UpdatePhase.VERIFY_FAILED)
        var changed = false
        val transport = FakeTransport { connection ->
            if (connection.url.toString() == RELEASES_API_URL) FakeResponse(200, release.toByteArray())
            else FakeResponse(200, manifestJson(apk, shaOverride = if (changed) "0".repeat(64) else sha256Hex(apk)).toByteArray())
        }
        newApi(transport).use { api ->
            assertEquals(12345L, api.check().ok().downloadedBytes)
            assertEquals(12345L, File(dir, "callring-0.4.0.apk.part").length())
            changed = true
            assertEquals(0L, api.check().ok().downloadedBytes)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun failedMetadataCheckKeepsResumableReleaseAndBytes() = runBlocking {
        seedPartial(apk.copyOfRange(0, 12345), UpdatePhase.VERIFY_FAILED)
        newApi(FakeTransport { FakeResponse(503) }).use { api ->
            val failed = api.check().ok()
            assertEquals(UpdatePhase.VERIFY_FAILED, failed.phase)
            assertEquals(12345L, failed.downloadedBytes)
            assertNotNull(failed.latest)
            assertTrue(File(dir, "callring-0.4.0.apk.part").isFile)
        }
    }

    @Test fun closePreservesPartialAndPreventsLatePublication() = runBlocking {
        seedPartial(apk.copyOfRange(0, 100))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val transport = FakeTransport {
            FakeResponse(206, headers = mapOf("Content-Range" to "bytes 100-${apk.lastIndex}/${apk.size}"), streamFactory = {
                object : InputStream() {
                    override fun read(): Int = error("buffered reads required")
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        check(finish.await(10, TimeUnit.SECONDS))
                        apk.copyInto(buffer, off, 100, 101)
                        return 1
                    }
                    override fun close() { drained.countDown() }
                }
            })
        }
        val api = newApi(transport)
        try {
            api.download().ok()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val before = persistence.read()
            api.close()
            finish.countDown()
            assertTrue(drained.await(10, TimeUnit.SECONDS))
            assertEquals(before, persistence.read())
            assertEquals(100L, File(dir, "callring-0.4.0.apk.part").length())
            assertTrue(api.status() is ApiResult.Failure)
            newApi(FakeTransport { FakeResponse(200, apk) }).use { restarted ->
                restarted.download().ok(); awaitPhase(restarted, UpdatePhase.VERIFIED)
            }
            Unit
        } finally { finish.countDown(); api.close() }
    }

    @Test fun restartDuringVerificationRecoversCompleteFileForLocalVerification() = runBlocking {
        for (phase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
            seedVerifiedFile()
            val previous = decodeUpdateState(persistence.read())!!
            persistence.write(encodeUpdateState(previous.copy(phase = phase, verified = false)))
            val transport = FakeTransport { error("complete file must not be downloaded twice") }
            newApi(transport).use { api ->
                assertEquals(apk.size.toLong(), api.status().ok().downloadedBytes)
                api.download().ok(); awaitPhase(api, UpdatePhase.VERIFIED)
                assertTrue(transport.requests.isEmpty())
            }
        }
    }

    @Test fun closedOwnerCannotDeleteAFileVerifiedByItsReplacement() = runBlocking {
        seedPartial(apk.copyOfRange(0, 100))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val old = newApi(FakeTransport {
                entered.countDown()
                check(finish.await(10, TimeUnit.SECONDS))
                throw java.io.IOException("late network failure")
            }, io = io)
            try {
                old.download().ok()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                old.close()
                newApi(FakeTransport { FakeResponse(200, apk) }).use { replacement ->
                    replacement.download().ok(); awaitPhase(replacement, UpdatePhase.VERIFIED)
                    finish.countDown()
                    withContext(io) { /* drain the old worker before checking the replacement's file */ }
                    assertEquals(UpdatePhase.VERIFIED, replacement.status().ok().phase)
                    assertArrayEquals(apk, File(dir, "callring-0.4.0.apk").readBytes())
                }
            } finally { finish.countDown(); old.close() }
        }
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
        assertEquals(0L, status.downloadedBytes)
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
