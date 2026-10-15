package com.greatwall.callring.ui.app

import android.Manifest
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.greatwall.callring.application.api.Capability
import com.greatwall.callring.application.api.CallDetail
import com.greatwall.callring.ui.widget.CallringWidgetReceiver
import com.greatwall.callring.ui.overlay.CallringOverlayService
import kotlinx.coroutines.launch

class CallringActivity : ComponentActivity() {
    private var destination by mutableStateOf<UiDestination>(UiDestination.Home)
    private var requestSequence by mutableLongStateOf(0)
    private var refreshSignal by mutableLongStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSignal++ }
    private val systemScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshSignal++ }
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshSignal++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestSequence = savedInstanceState?.getLong(STATE_REQUEST_SEQUENCE) ?: 0
        destination = readDestination(intent)
        val api = (application as UiApiOwner).uiApi
        setContent { CallringApp(api, destination, requestSequence, refreshSignal, ::requestCapability, ::previewCall, ::pinWidget, ::openApplicationSettings) }
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

    private fun requestCapability(capability: Capability) {
        try {
            when (capability) {
                Capability.PHONE_STATE -> permission.launch(Manifest.permission.READ_PHONE_STATE)
                Capability.CALL_LOG -> permission.launch(Manifest.permission.READ_CALL_LOG)
                Capability.CONTACTS -> permission.launch(Manifest.permission.READ_CONTACTS)
                Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else systemScreen.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                Capability.OVERLAY -> systemScreen.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Capability.BATTERY_OPTIMIZATION_EXEMPTION -> systemScreen.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Capability.CALL_SCREENING_ROLE -> {
                    val roles = getSystemService(RoleManager::class.java)
                    if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) systemScreen.launch(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    else Toast.makeText(this, "系统不支持来电筛选角色", Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "系统没有提供该设置入口", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "系统限制了该操作，请在系统设置中调整", Toast.LENGTH_LONG).show()
        }
    }

    private fun openApplicationSettings() {
        try {
            systemScreen.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "系统没有提供该设置入口", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "系统限制了该操作，请在系统设置中调整", Toast.LENGTH_LONG).show()
        }
    }

    private fun previewCall(call: CallDetail) {
        if (!Settings.canDrawOverlays(this)) { requestCapability(Capability.OVERLAY); return }
        try { startForegroundService(CallringOverlayService.previewIntent(this, call)) }
        catch (_: RuntimeException) { Toast.makeText(this, "系统未允许显示来电卡片，请检查运行条件", Toast.LENGTH_LONG).show() }
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        try {
            if (!manager.isRequestPinAppWidgetSupported || !manager.requestPinAppWidget(ComponentName(this, CallringWidgetReceiver::class.java), null, null))
                Toast.makeText(this, "请在桌面长按并选择添加小组件", Toast.LENGTH_LONG).show()
        } catch (_: RuntimeException) { Toast.makeText(this, "请在桌面长按并选择添加小组件", Toast.LENGTH_LONG).show() }
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
                UiDestination.Settings -> "settings"
                is UiDestination.Report -> "report"
                is UiDestination.EditCaller -> "edit"
                UiDestination.Lookup -> "lookup"
                is UiDestination.Setting -> "setting"
                UiDestination.Home -> "home"
            }
            putExtra(EXTRA_TARGET, target)
            when (destination) {
                is UiDestination.Report -> putExtra(EXTRA_NUMBER, destination.number)
                is UiDestination.EditCaller -> putExtra(EXTRA_NUMBER, destination.number)
                is UiDestination.Setting -> putExtra(EXTRA_PAGE, destination.page.name)
                else -> Unit
            }
        }
    }
}
