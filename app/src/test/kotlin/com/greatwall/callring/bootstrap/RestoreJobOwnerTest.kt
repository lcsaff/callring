package com.greatwall.callring.bootstrap

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.SyncJobService
import com.greatwall.callring.RecordingJobService
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.domain.model.SystemCallRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = RunOwnerApplication::class)
class RestoreJobOwnerTest {
    @Test fun lateImportCannotResolveReplacementGraph() {
        val app = RuntimeEnvironment.getApplication() as RunOwnerApplication
        val old = graph("old")
        val replacement = graph("new")
        app.current = old
        val service = Robolectric.buildService(SyncJobService::class.java).create().get()
        try {
            val factory = SyncJobService::class.java.getDeclaredMethod("controller", BackendGraph::class.java).apply { isAccessible = true }
            val controller = factory.invoke(service, old)
            val import = controller.javaClass.getDeclaredField("import").apply { isAccessible = true }.get(controller) as Function1<List<SystemCallRecord>, Unit>
            old.close(); app.current = replacement
            assertThrows(IllegalStateException::class.java) { import.invoke(emptyList()) }
            assertEquals(0L, replacement.businessReadCounters()["coreOpens"])
        } finally { service.onDestroy(); old.close(); replacement.close() }
    }
    @Test fun oldRecordingCancellationCannotInitializeNewWorkflow() {
        val app = RuntimeEnvironment.getApplication() as RunOwnerApplication
        val old = graph("record-old")
        val replacement = graph("record-new")
        app.current = old
        val service = Robolectric.buildService(RecordingJobService::class.java).create().get()
        try {
            val controller = RecordingJobService::class.java.getDeclaredMethod("controller", BackendGraph::class.java).apply { isAccessible = true }.invoke(service, old)
            val cancel = controller.javaClass.getDeclaredField("cancelProcessing").apply { isAccessible = true }.get(controller) as Function0<Unit>
            old.close(); app.current = replacement
            assertThrows(IllegalStateException::class.java) { cancel.invoke() }
            val lazy = BackendGraph::class.java.getDeclaredField("recordingDelegate").apply { isAccessible = true }.get(replacement) as Lazy<*>
            assertFalse(lazy.isInitialized())
        } finally { service.onDestroy(); old.close(); replacement.close() }
    }
    @Test fun graphCloseWaitsForRawBackupReadLeaseBeforeClosingPersistence() {
        val graph = graph("snapshot")
        val lease = graph.acquireBackupReadLease()
        val done = java.util.concurrent.CountDownLatch(1)
        val closer = Thread { graph.close(); done.countDown() }.apply { start() }
        try {
            assertFalse(done.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertThrows(IllegalStateException::class.java) { graph.acquireBackupReadLease() }
            lease.close()
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { lease.close(); closer.join(2000) }
    }    private fun graph(namespace: String): BackendGraph {
        val context = RuntimeEnvironment.getApplication()
        return BackendGraph(BackupGraphOwner { _, _ -> error("unexpected restore") }, context,
            BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM), databaseName = "$namespace.db"),
            object : UiNotificationEntryPoints {
                override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
                override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
            }, object : IncomingCallUiSink { override fun show(request: IncomingCallUiRequest) {} })
    }
}
class RunOwnerApplication : Application(), BackendProvider {
    lateinit var current: BackendGraph
    override val backendGraph get() = current
}
