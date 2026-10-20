package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.runtime.*
import android.Manifest
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.greatwall.callring.contracts.runtime.Capability
import com.greatwall.callring.contracts.callers.CallDetail
import com.greatwall.callring.ui.widget.CallringWidgetReceiver
import com.greatwall.callring.ui.overlay.CallringOverlayService
import kotlinx.coroutines.launch

class CallringActivity : ComponentActivity() {
    private lateinit var actionHost: ForegroundActionHost
    private var notice by mutableStateOf<UiNotice?>(null)
    private var noticeSequence = 0L
    private fun notifyUser(text: String) { notice = UiNotice(++noticeSequence, text) }
    private var destination by mutableStateOf<UiDestination>(UiDestination.Home)
    private var requestSequence by mutableLongStateOf(0)
    private var refreshSignal by mutableLongStateOf(0)
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshSignal++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestSequence = savedInstanceState?.getLong(STATE_REQUEST_SEQUENCE) ?: 0
        destination = readDestination(intent)
        val api = (application as UiApiOwner).uiApi
        actionHost = ForegroundActionHost(this,api,::notifyUser)
        setContent { CallringApp(api, destination, requestSequence, refreshSignal, ::requestCapability, ::previewCall, ::pinWidget, ::openApplicationSettings, notice, { if (notice?.id == it) notice = null }, onTestOverlay = ::testOverlay); ForegroundActionDialogs(api) }
    }

    override fun onResume() {
        super.onResume()
        refreshSignal++
        // A grant made in system settings must restore the observer as well as refresh its status.
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { (application as UiApiOwner).uiApi.runtime.requestCallLogSync() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination = readDestination(intent)
        requestSequence++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_REQUEST_SEQUENCE, requestSequence)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeChanges, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(timeChanges, filter)
    }

    override fun onStop() {
        unregisterReceiver(timeChanges)
        super.onStop()
    }

    private fun readDestination(intent: Intent): UiDestination {
        val destination = UiDestination.fromExternal(intent.getStringExtra(EXTRA_TARGET), intent.getStringExtra(EXTRA_NUMBER), intent.getStringExtra(EXTRA_PAGE))
        val api = (application as UiApiOwner).uiApi
        return when (destination) {
            is UiDestination.Report -> api.checkNumber(destination.number).let { check -> if (check.validProfileNumber) UiDestination.Report(check.normalized) else UiDestination.Lookup }
            is UiDestination.EditCaller -> destination.number?.let { raw -> api.checkNumber(raw).let { check -> if (check.validProfileNumber) UiDestination.EditCaller(check.normalized) else UiDestination.EditCaller() } } ?: destination
            else -> destination
        }
    }

    private fun requestCapability(capability: Capability) = actionHost.requestCapability(capability)
    private fun openApplicationSettings() = actionHost.openApplicationSettings()
    private fun previewCall(call: CallDetail) {
        when(actionHost.previewCall(call)) {
            com.greatwall.callring.contracts.ai.ForegroundResultStatus.DENIED -> requestCapability(Capability.OVERLAY)
            com.greatwall.callring.contracts.ai.ForegroundResultStatus.UNAVAILABLE -> notifyUser("系统未允许显示来电卡片")
            else -> Unit
        }
    }
    private fun testOverlay() {
        when(actionHost.testOverlay()) {
            com.greatwall.callring.contracts.ai.ForegroundResultStatus.DENIED -> { notifyUser("开启悬浮窗权限后，请再次测试"); requestCapability(Capability.OVERLAY) }
            com.greatwall.callring.contracts.ai.ForegroundResultStatus.UNAVAILABLE -> notifyUser("系统未允许显示测试卡片")
            else -> Unit
        }
    }
    private fun pinWidget() {
        if(actionHost.pinWidget() == com.greatwall.callring.contracts.ai.ForegroundResultStatus.LAUNCHED) notifyUser("已发起桌面添加，请以系统桌面为准")
        else notifyUser("请在桌面长按并选择添加小组件")
    }
    companion object {
        private const val EXTRA_TARGET = "callring.ui.target"
        private const val EXTRA_NUMBER = "callring.ui.number"
        private const val EXTRA_PAGE = "callring.ui.page"
        private const val STATE_REQUEST_SEQUENCE = "callring.ui.requestSequence"
        fun intent(context: Context, destination: UiDestination = UiDestination.Home): Intent = Intent(context, CallringActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val target = when (destination) {
                UiDestination.Callers -> "callers"
                UiDestination.RecordingArchives -> "recording-archives"
                UiDestination.Settings -> "settings"
                UiDestination.Conversation -> "conversation"
                is UiDestination.Report -> "report"
                is UiDestination.Archive -> "report"
                is UiDestination.EditCaller -> "edit"
                UiDestination.Lookup -> "lookup"
                UiDestination.Leaves -> "leaves"
                UiDestination.Positions -> "positions"
                is UiDestination.Setting -> "setting"
                UiDestination.Home -> "home"
            }
            putExtra(EXTRA_TARGET, target)
            when (destination) {
                is UiDestination.Report -> putExtra(EXTRA_NUMBER, destination.number)
                is UiDestination.Archive -> putExtra(EXTRA_NUMBER, destination.number)
                is UiDestination.EditCaller -> putExtra(EXTRA_NUMBER, destination.number)
                is UiDestination.Setting -> putExtra(EXTRA_PAGE, destination.page.name)
                else -> Unit
            }
        }
    }
}
