@file:Suppress("DEPRECATION")
package com.greatwall.callring.platform.update

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.app.CallringActivity
import com.greatwall.callring.ui.app.UiDestination
import com.greatwall.callring.ui.feature.settings.SettingsPage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Explicitly selected, update-only acceptance tests; never downloads or installs an APK. */
class AppUpdateDeviceTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private class MemoryState : UpdatePersistence {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private fun ApiResult<UpdateStatus>.status(): UpdateStatus = when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> error("${error.code}: ${error.message}")
    }

    fun testDebugGuardDoesNotRequestNetwork() = runBlocking {
        assertFalse(defaultUpdateBuildInfo(context).updatable)
        AndroidAppUpdate(context, persistence = MemoryState(), openConnection = { error("Debug must not request network") }).use {
            val status = it.check().status()
            assertEquals(UpdatePhase.UNSUPPORTED_DEVICE, status.phase)
            assertEquals(UpdateReasons.UNSUPPORTED_BUILD, status.failureReason)
        }
    }

    fun testManifestFailureRecoversOnTheSameRelease() = runBlocking {
        val info = defaultUpdateBuildInfo(context).copy(updatable = true)
        val versionCode = info.current.versionCode + 1
        val manifest = JSONObject().put("schemaVersion", 1).put("applicationId", CALLRING_PACKAGE)
            .put("versionCode", versionCode).put("versionName", "99.0.0").put("minSdk", 29)
            .put("assetName", "callring-99.0.0.apk").put("sizeBytes", 1024).put("sha256", "0".repeat(64)).toString()
        val release = """{"tag_name":"v99.0.0","assets":[
            {"name":"update.json","size":512,"browser_download_url":"https://example.test/update.json"},
            {"name":"callring-99.0.0.apk","size":1024,"browser_download_url":"https://example.test/callring-99.0.0.apk"}]}"""
        var manifestRequests = 0
        val transport: (URL) -> HttpURLConnection = { url ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                private val responseStatus by lazy { when {
                    url.toString() == RELEASES_API_URL -> if (getRequestProperty("If-None-Match") != null) 304 else 200
                    url.path.endsWith("update.json") -> if (++manifestRequests == 1) 503 else 200
                    else -> error("APK must not be requested")
                } }
                override fun getResponseCode(): Int = responseStatus
                override fun getHeaderField(name: String): String? = if (name == "ETag") "device-etag" else null
                override fun getInputStream() = ByteArrayInputStream(
                    (if (url.toString() == RELEASES_API_URL) release else manifest).toByteArray())
            }
        }
        AndroidAppUpdate(context, build = { info }, persistence = MemoryState(), openConnection = transport).use {
            assertEquals(UpdatePhase.CHECK_FAILED, it.check().status().phase)
            val recovered = it.check().status()
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, recovered.phase)
            assertEquals(versionCode, recovered.latest?.versionCode)
            assertEquals(UpdatePhase.UPDATE_AVAILABLE, it.check().status().phase)
            assertEquals(2, manifestRequests)
        }
    }

    fun testRealGitHubCheckTwice() = runBlocking {
        // Exercise the production client on the device, with eligibility overridden only here.
        // Keep an Activity resumed: some devices block DNS for background app UIDs.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start --display 0 -n com.greatwall.callring/com.greatwall.callring.ui.app.CallringActivity --es callring.ui.target setting --es callring.ui.page UPDATE"
        )).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        val info = defaultUpdateBuildInfo(context).copy(updatable = true)
        AndroidAppUpdate(context, build = { info }, persistence = MemoryState()).use { api ->
            repeat(2) { index ->
                val status = api.check().status()
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "Live check ${index + 1}: ${status.phase}, detail=${status.failureDetail}, latest=${status.latest?.versionName}(${status.latest?.versionCode})\n")
                })
                assertTrue("Live check failed: ${status.phase}/${status.failureDetail}",
                    status.phase == UpdatePhase.UP_TO_DATE || status.phase == UpdatePhase.UPDATE_AVAILABLE)
            }
        }
    }

    fun testUpdatePageRecheckActions() {
        val storage = SharedPreferencesUpdatePersistence.forContext(context)
        val original = storage.read()
        var activity: android.app.Activity? = null
        try {
            for (phase in listOf(UpdatePhase.UP_TO_DATE, UpdatePhase.NO_INSTALLABLE)) {
                storage.write(encodeUpdateState(PersistedUpdateState(phase = phase)))
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "am start --display 0 -n com.greatwall.callring/com.greatwall.callring.ui.app.CallringActivity --es callring.ui.target setting --es callring.ui.page UPDATE"
                )).use { it.readBytes() }
                activity = instrumentation.startActivitySync(CallringActivity.intent(context, UiDestination.Setting(SettingsPage.UPDATE)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                instrumentation.runOnMainSync { activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                instrumentation.waitForIdleSync()
                val action = awaitText("重新检查")
                saveScreenshot("${phase.name.lowercase()}-recheck.png")
                var target: AccessibilityNodeInfo? = action
                while (target != null && !target.isClickable) target = target.parent
                assertNotNull("Clickable recheck action", target)
                assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitText("当前是调试或内部测试构建，不支持应用内更新；请安装正式构建。")
                assertEquals(UpdatePhase.UNSUPPORTED_DEVICE, decodeUpdateState(storage.read())?.phase)
                saveScreenshot("debug-guard.png")
                instrumentation.runOnMainSync { activity?.finish() }
                instrumentation.waitForIdleSync()
                activity = null
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            storage.write(original)
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            findText(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(150)
        }
        error("UI text not found: $text")
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun saveScreenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "update-device-proof").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
