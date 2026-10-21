package com.greatwall.callring

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.res.Configuration
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.ui.app.bridge.UiNotifications
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.overlay.*
import com.greatwall.callring.ui.widget.observeWidgetUpdates
import com.greatwall.callring.ui.widget.UiWidgetRefresh
import kotlinx.coroutines.*

/** The single production composition root. */
open class CallringApplication : Application(), BackendProvider, UiOverlayOwner {
    override val overlaySessions = OverlaySessions()
    override val backendGraph by lazy {
        val notifications = UiNotifications(this)
        BackendGraph(this, BackendConfiguration(DefaultAppearance), object : UiNotificationEntryPoints {
            override fun home(requestCode: Int): PendingIntent = notifications.home(requestCode)
            override fun playback(preview: Boolean, stopAction: PendingIntent): Notification = notifications.playback(preview, stopAction)
        }, AndroidIncomingCallUiSink(this))
    }
    override val uiApi get() = backendGraph.api
    override suspend fun reobservePlatform() = backendGraph.reobservePlatform()
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        lifetime.observeWidgetUpdates(this, uiApi)
        try { RecordingJobService.schedule(this) }
        catch (_: Exception) { android.util.Log.w("CallringRecording", "Recording jobs deferred") }
        try { CalendarSyncJobService.schedule(this) }
        catch (_: Exception) { android.util.Log.w("CallringCalendar", "Calendar refresh deferred") }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lifetime.launch {
            try { UiWidgetRefresh.refresh(this@CallringApplication) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("CallringWidget", "Configuration refresh deferred") }
        }
    }
}
