package com.greatwall.callring.ui.overlay

import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.app.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class CallringOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: OverlayController
    private lateinit var window: OverlayWindow
    private var started = false
    private var lastStartId = 0
    private var windowOwner = 0L
    private val visibility = OverlayVisibility()
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.refreshRuntime()
            reconcileWindow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val owner = application as UiOverlayOwner
        controller = OverlayController(owner.uiApi, owner.overlaySessions, scope)
        windowOwner = owner.overlaySessions.attachOwner()
        window = OverlayWindow(this, controller) { number ->
            try { startActivity(CallringActivity.intent(this, UiDestination.Report(number))) }
            catch (_: RuntimeException) { Log.w("CallringOverlay", "System rejected report launch") }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(changes, filter)
        scope.launch { controller.sessions.state.collect { if (started) reconcileWindow() } }
        scope.launch { while (isActive) { delay(500); if (started) reconcileWindow() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(OVERLAY_NOTIFICATION, UiNotifications(this).overlay())
        started = true
        lastStartId = startId
        val request = intent?.let(::readRequest)
        if (request != null) {
            if (intent.getBooleanExtra(PREVIEW, false)) controller.sessions.preview(request, intent.getBooleanExtra(TEST_PREVIEW, false))
            else controller.show(request)
        }
        reconcileWindow()
        return START_NOT_STICKY
    }

    private fun reconcileWindow() {
        val session = controller.sessions.state.value
        if (session == null) {
            window.hide()
            if (stopSelfResult(lastStartId)) {
                started = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return
        }
        if (!Settings.canDrawOverlays(this)) { controller.dismiss(); return }
        val visible = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val model = controller.models.value?.presentation
        visibility.session(session.request.sessionId)
        val status = model?.runtime?.value?.data
        val access = status?.capabilities?.get(Capability.PHONE_STATE)
        val reveal = visibility.visible(visible, model?.runtimeRead?.value ?: 0,
            status?.phoneActivity ?: PhoneActivity.UNKNOWN, session.editing)
        try {
            if ((session.preview && visible) || (reveal && access == AccessState.GRANTED)) window.show(session.editing) else window.hide()
        }
        catch (_: RuntimeException) {
            Log.w("CallringOverlay", "System rejected overlay window")
            controller.dismiss()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(changes)
        controller.close()
        controller.sessions.dismissOwned(windowOwner)
        window.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val OVERLAY_NOTIFICATION = 203
        private const val SESSION = "session"
        private const val NUMBER = "number"
        private const val START = "start"
        private const val DECISION = "decision"
        private const val PREVIEW = "preview"
        private const val TEST_PREVIEW = "testPreview"
        fun testPreviewIntent(context: Context) = intent(context, IncomingCallUiRequest(
            "overlay-test-${java.util.UUID.randomUUID()}", "100", System.currentTimeMillis(), CallDecision.NORMAL_RINGING))
            .putExtra(PREVIEW, true).putExtra(TEST_PREVIEW, true)
        fun previewIntent(context: Context, call: CallDetail) = intent(context, IncomingCallUiRequest(
            "preview-${java.util.UUID.randomUUID()}", call.number, call.startedAtEpochMillis, call.decision)).putExtra(PREVIEW, true)
        fun intent(context: Context, request: IncomingCallUiRequest) = Intent(context, CallringOverlayService::class.java)
            .putExtra(SESSION, request.sessionId).putExtra(NUMBER, request.number)
            .putExtra(START, request.startedAtEpochMillis).putExtra(DECISION, request.decision.name)

        private fun readRequest(intent: Intent): IncomingCallUiRequest? {
            val session = intent.getStringExtra(SESSION)?.takeIf { it.isNotBlank() } ?: return null
            val number = intent.getStringExtra(NUMBER) ?: return null
            val decision = CallDecision.entries.firstOrNull { it.name == intent.getStringExtra(DECISION) } ?: return null
            if (!intent.hasExtra(START)) return null
            return IncomingCallUiRequest(session, number, intent.getLongExtra(START, 0), decision)
        }
    }
}
