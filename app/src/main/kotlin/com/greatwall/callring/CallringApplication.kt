package com.greatwall.callring

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.ui.app.bridge.UiNotifications
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.overlay.*
import com.greatwall.callring.ui.widget.observeWidgetUpdates
import com.greatwall.callring.ui.widget.UiWidgetRefresh
import kotlinx.coroutines.*

/** The single production composition root. Each replacement permanently closes the previous owner. */
open class CallringApplication : Application(), BackendProvider, UiOverlayOwner {
    override val overlaySessions = OverlaySessions()
    private val graphLock = Any()
    @Volatile private var restoring = false
    @Volatile private var graphInstance: BackendGraph? = null
    protected open fun backendConfiguration() = BackendConfiguration(DefaultAppearance)
    protected open fun createGraph(configuration: BackendConfiguration, owner: BackupGraphOwner): BackendGraph {
        val notifications = UiNotifications(this)
        return BackendGraph(owner, this, configuration, object : UiNotificationEntryPoints {
            override fun home(requestCode: Int): PendingIntent = notifications.home(requestCode)
            override fun playback(preview: Boolean, stopAction: PendingIntent): Notification = notifications.playback(preview, stopAction)
        }, AndroidIncomingCallUiSink(this))
    }
    override val backendGraph: BackendGraph get() {
        // Telecom callers never wait behind restore IO; system ringing remains available.
        check(!restoring) { "backup.restoring" }
        return synchronized(graphLock) {
            check(!restoring) { "backup.restoring" }
            graphInstance ?: createGraph(backendConfiguration(), BackupGraphOwner(::replaceGraph)).also { graphInstance = it }
        }
    }
    private val apiAvailability = kotlinx.coroutines.flow.MutableStateFlow<com.greatwall.callring.contracts.CallringUiApi?>(null)
    override val availableUiApi: kotlinx.coroutines.flow.StateFlow<com.greatwall.callring.contracts.CallringUiApi?> get() = apiAvailability
    override val uiApi get() = backendGraph.api
    override suspend fun reobservePlatform() {
        val graph = graphInstance ?: return
        if (restoring) return
        try { graph.reobservePlatform() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (closed: IllegalStateException) { if (graph.acceptsOperations()) throw closed }
    }
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var widgetJob: Job? = null
    override fun onCreate() {
        super.onCreate()
        // Recovery is mandatory and precedes widget observation, jobs and normal graph creation.
        RestoreCoordinator.recover(this, backendConfiguration().databaseName)
        apiAvailability.value = uiApi
        widgetJob = lifetime.observeWidgetUpdates(this, uiApi)
        try { RecordingJobService.schedule(this) }
        catch (_: Exception) { android.util.Log.w("CallringRecording", "Recording jobs deferred") }
        try { CalendarSyncJobService.schedule(this) }
        catch (_: Exception) { android.util.Log.w("CallringCalendar", "Calendar refresh deferred") }
    }
    private fun replaceGraph(expected: BackendGraph, mutation: () -> Unit): Boolean {
        synchronized(graphLock) {
            check(!restoring && graphInstance === expected) { "backup.owner_changed" }
            restoring = true
            apiAvailability.value = null
        }
        widgetJob?.cancel()
        var failure: Exception? = null
        try {
            expected.close() // seal, cancel, drain accepted accesses, then close persistence
            mutation()
        } catch (caught: Exception) {
            failure = caught
            // Failure to recover leaves the owner sealed; no mixed resources can be opened.
            RestoreCoordinator.recover(this, backendConfiguration().databaseName)
        }
        val replacement = createGraph(backendConfiguration(), BackupGraphOwner(::replaceGraph))
        synchronized(graphLock) { graphInstance = replacement; restoring = false; apiAvailability.value = replacement.api }
        lifetime.launch {
            widgetJob = lifetime.observeWidgetUpdates(this@CallringApplication, replacement.api)
            if (failure != null) android.widget.Toast.makeText(this@CallringApplication, "恢复未完成，请重新检查当前数据", android.widget.Toast.LENGTH_LONG).show()
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException("backup.restart_unavailable")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
        }
        failure?.let { throw it }
        return true
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
