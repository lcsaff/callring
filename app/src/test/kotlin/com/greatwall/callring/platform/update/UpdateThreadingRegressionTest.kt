package com.greatwall.callring.platform.update

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.common.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UpdateThreadingRegressionTest {
    @Test fun verificationLeavesCallingThreadButInstallerReturnsToIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val apk = byteArrayOf(4, 5, 6)
        val directory = File(context.cacheDir, "update-thread-test").apply { mkdirs() }
        val file = File(directory, "callring-0.4.0.apk").apply { writeBytes(apk) }
        val latest = AppReleaseInfo("0.4.0", 4, 0, "", file.name, apk.size.toLong(),
            sha256Hex(apk), "https://example.test/${file.name}")
        val state = PersistedUpdateState(phase = UpdatePhase.VERIFIED, latest = latest, fileName = file.name, verified = true)
        val caller = Thread.currentThread()
        var inspectionThread: Thread? = null
        var launchThread: Thread? = null
        val installer = object : UpdateInstaller {
            override fun canInstall() = true
            override fun openInstallPermission() = error("Permission already granted")
            override fun launch(apk: File): Boolean { launchThread = Thread.currentThread(); return true }
        }
        val api = AndroidAppUpdate(context,
            build = { UpdateBuildInfo(AppVersionInfo("0.3.0", 3), CALLRING_PACKAGE, true, 33) },
            directory = { directory }, persistence = MemoryUpdatePersistence(encodeUpdateState(state)),
            apkInspector = ApkInspector {
                inspectionThread = Thread.currentThread()
                ApkInspection(CALLRING_PACKAGE, 4, 29, "cert")
            }, installedCertificate = InstalledCertificateReader { "cert" }, installer = installer, io = Dispatchers.IO)
        try {
            val result = api.install()
            assertTrue(result is ApiResult.Success)
            assertNotNull(inspectionThread)
            assertNotSame("Blocking verification must leave the UI caller", caller, inspectionThread)
            assertSame("Installer UI must run back on the caller", caller, launchThread)
        } finally {
            api.close()
            file.delete()
            directory.delete()
        }
    }
}
