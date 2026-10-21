@file:Suppress("DEPRECATION")
package com.greatwall.callring.platform.update

import android.os.Bundle
import android.test.InstrumentationTestCase
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** Explicit opt-in: real GitHub network and APK verification, isolated files, never installs. */
class AppUpdateLiveDownloadDeviceTest : InstrumentationTestCase() {
    fun testRealPublishedApkDownloadAndVerification() = runBlocking {
        val context = instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.greatwall.callring.ui.app.CallringActivity"
        )).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        val info = defaultUpdateBuildInfo(context).copy(
            current = AppVersionInfo("0.0.0", 0), packageName = CALLRING_PACKAGE, updatable = true)
        val directory = File(context.getExternalFilesDir(null), "live-update-acceptance")
        assertFalse("Do not overwrite an earlier acceptance artifact", directory.exists())
        assertTrue(directory.mkdirs())
        val memory = object : UpdatePersistence {
            private var value: String? = null
            override fun read() = value
            override fun write(value: String?) { this.value = value }
        }
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            AndroidAppUpdate(context, build = { info }, directory = { directory }, persistence = memory, io = dispatcher).use { api ->
                try {
                    val checked = (api.check() as ApiResult.Success).value
                    assertEquals("Live GitHub check: ${checked.failureDetail}", UpdatePhase.UPDATE_AVAILABLE, checked.phase)
                    val latest = checkNotNull(checked.latest)
                    assertTrue(latest.downloadUrl.startsWith("https://github.com/"))
                    assertEquals(UpdatePhase.DOWNLOADING, (api.download() as ApiResult.Success).value.phase)
                    val completed = withTimeout(1_800_000) {
                        var status = (api.status() as ApiResult.Success).value
                        while (status.phase == UpdatePhase.DOWNLOADING || status.phase == UpdatePhase.VERIFYING) {
                            delay(250)
                            status = (api.status() as ApiResult.Success).value
                        }
                        status
                    }
                    assertEquals("Live APK verification: ${completed.failureReason}/${completed.failureDetail}", UpdatePhase.VERIFIED, completed.phase)
                    val apk = directory.listFiles()!!.single { it.extension == "apk" }
                    assertEquals(latest.sizeBytes, apk.length())
                    assertEquals(latest.sha256.lowercase(), sha256File(apk).lowercase())
                    val inspected = checkNotNull(AndroidApkInspector(context.packageManager).inspect(apk))
                    assertEquals(CALLRING_PACKAGE, inspected.packageName)
                    assertEquals(latest.versionCode, inspected.versionCode)
                    assertEquals(AndroidInstalledCertificate(context.packageManager, context.packageName).sha256(), inspected.certificateSha256)
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("stream", "LIVE_DOWNLOAD_VERIFIED version=${latest.versionName}/${latest.versionCode} bytes=${apk.length()} sha256=${sha256File(apk)} certificate=${inspected.certificateSha256}\n")
                    })
                } finally {
                    withContext(NonCancellable) {
                        assertTrue("Download cancellation request must succeed", api.cancel() is ApiResult.Success)
                    }
                }
            }
        } finally {
            dispatcher.close()
            check(executor.awaitTermination(45, TimeUnit.SECONDS)) { "Download workers still running; retain files for diagnosis" }
            check(directory.deleteRecursively()) { "Live download acceptance directory cleanup failed" }
            assertFalse(directory.exists())
        }
    }
}
