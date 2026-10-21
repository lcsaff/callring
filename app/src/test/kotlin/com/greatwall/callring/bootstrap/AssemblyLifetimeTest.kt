package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.RecordingSettingsPatch
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.contracts.runtime.AccessState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AssemblyLifetimeTest {
    @Test fun platformResumeDoesNotInitializeUnrelatedRecordingResources() = runBlocking {
        val graph = graph("t11-platform-resume")
        try {
            val field = BackendGraph::class.java.getDeclaredField("recordingDelegate").apply { isAccessible = true }
            val recording = field.get(graph) as Lazy<*>
            graph.reobservePlatform()
            assertFalse("home/platform observation must not read recording configuration", recording.isInitialized())
            val marker = graph.coordinator.marker()
            graph.reobservePlatform()
            assertEquals(marker, graph.coordinator.marker())
            assertFalse(recording.isInitialized())
        } finally { graph.close() }
    }
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumedFolderProviderRunsOffTheCallingUiDispatcherAndDoesNotBlockIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val probeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val probes = java.util.concurrent.atomic.AtomicInteger()
        var block = false
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri) = "vnd.android.document/directory"
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                probes.incrementAndGet()
                if (block) {
                    probeThread.set(Thread.currentThread()); entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return android.database.MatrixCursor(projection!!).apply { addRow(arrayOf("Recordings")) }
            }
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "t11-resume-folder"; exported = true })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("t11-resume-folder", provider)
        val graph = graph("t11-provider-dispatch")
        val ui = kotlinx.coroutines.newSingleThreadContext("t11-ui")
        var job: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val recording = graph.recordings
            recording.javaClass.getDeclaredField("sources\$delegate").apply { isAccessible = true }
                .set(recording, lazy { com.greatwall.callring.platform.recording.SystemRecordingSource(context.contentResolver) { true } })
            val cipher = object : com.greatwall.callring.data.recording.RecordingCipher {
                override fun encrypt(value: String) = value
                override fun decrypt(value: String) = value
            }
            recording.javaClass.getDeclaredField("storeDelegate").apply { isAccessible = true }.set(recording,
                lazy { com.greatwall.callring.data.recording.AndroidRecordingStore(context, graph.configuration.databaseName, cipher,
                    recordingVersion = { graph.coordinator.capture().values.getValue("recording") }) })
            recording.store.persistSettings(com.greatwall.callring.application.recording.model.RecordingSettings(systemFolder = "content://t11-resume-folder/tree/root"))
            assertEquals(AccessState.GRANTED, graph.api.aiSettings.getRecordingView().requiredValue().folderAccess)
            assertEquals(1, probes.get())
            val baseline = graph.coordinator.marker()
            block = true
            val caller = kotlinx.coroutines.withContext(ui) { Thread.currentThread() }
            job = kotlinx.coroutines.CoroutineScope(ui).async { graph.reobservePlatform() }
            assertTrue("provider probe was not reached", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotEquals("blocking provider I/O must leave the resume/UI dispatcher", caller, probeThread.get())
            kotlinx.coroutines.withTimeout(1000) { kotlinx.coroutines.withContext(ui) { assertEquals(caller, Thread.currentThread()) } }
            release.countDown(); job.await()
            assertEquals("one cached-folder provider reobservation", 2, probes.get())
            assertEquals("unchanged provider facts publish nothing", baseline, graph.coordinator.marker())
        } finally {
            release.countDown(); job?.join(); ui.close(); graph.close()
        }
    }

    @Test fun retainedPublicPreferenceWritersCannotMutateANewGraphAfterClose() = runBlocking {
        val old = graph("retained-public")
        val api = old.api
        val appearance = api.settings.getAppearance().requiredValue()
        api.widgets.saveBinding(7, "31234").requiredValue()
        old.close()
        val marker = old.coordinator.marker()
        val replacement = graph("retained-public")
        try {
            val changes = listOf(
                api.settings.saveAppearance(appearance.copy(colorMode = ColorMode.DARK)),
                api.edits.appearance(appearance, appearance.copy(colorMode = ColorMode.DARK)),
                api.widgets.saveBinding(7, "35678"),
                api.widgets.removeBinding(7),
            )
            changes.forEach { assertTrue("closed public writer must reject: $it", it is ApiResult.Failure) }
            assertEquals(marker, old.coordinator.marker())
            assertEquals(appearance, replacement.api.settings.getAppearance().requiredValue())
            assertEquals("31234", replacement.api.widgets.getBinding(7).requiredValue()!!.number)
        } finally { replacement.close() }
    }

    @Test fun retainedBoundAndNonSqlWritersKeepTheSameClosedOwner() = runBlocking {
        val old = graph("retained-nonsql")
        val api = old.api
        val appearance = api.settings.getAppearance().requiredValue()
        val settings = api.aiSettings
        val recording = settings.getRecordingView().requiredValue()
        val ai = settings.getAiView().requiredValue()
        val ringtone = api.ringtone
        val runtime = api.runtime
        val bound = api.commandApi(CommandContext("retained", old.coordinator.capture(), 0, active = { true },
            expectedScopes = old.coordinator.expect(setOf("preferences", "ringtone", "recordingConfig", "aiConfig"))))
        old.close()
        val marker = old.coordinator.marker()
        val results = listOf(
            bound.settings.saveAppearance(appearance.copy(colorMode = ColorMode.DARK)),
            bound.widgets.saveBinding(9, "31234"),
            settings.patchRecording(recording, RecordingSettingsPatch(recordingEnabled = PatchField.Set(true))),
            settings.copyConnection(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION, ai.revision),
            ringtone.setEnabled(false), ringtone.restoreBuiltIn(), ringtone.startPreview(),
            runtime.requestCallLogSync(),
        )
        results.forEach { assertTrue("closed owner must reject: $it", it is ApiResult.Failure) }
        assertEquals(marker, old.coordinator.marker())
    }

    @Test fun constructionAndClosingUnusedAssembliesNeverOpenPersistence() {
        val graph = graph("assembly-unopened")
        assertEquals(0L, graph.businessReadCounters().getValue("coreOpens"))
        assertEquals(0L, graph.businessReadCounters().getValue("sourceQueries"))
        graph.close()
        val context = RuntimeEnvironment.getApplication()
        assertFalse(context.getDatabasePath("assembly-unopened.db").exists())
        assertFalse(context.getDatabasePath("assembly-unopened.db_recording.db").exists())
    }

    @Test fun graphCloseDrainsQueuedIncomingStatisticsLinkBeforeClosingStorage() = runBlocking {
        val old = graph("graph-drain")
        old.api.settings.setStatisticsEnabled(true).requiredValue()
        old.backend.refreshDecisionSnapshot()
        val recording = old.recordings.store
        val incoming = old.incomingCalls
        val at = old.configuration.clock.millis() + 1
        val call = old.recordings.registrations.registerIncoming("queued-drain", "31234", at, null, null)
        var statisticsId = 0L
        var closer: Thread? = null
        try {
            synchronized(recording) {
                val session = incoming.inspect("queued-drain", "31234", at, null)
                statisticsId = session.record.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(statisticsId > 0)
                closer = Thread { old.close() }.apply { isDaemon = true; start() }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                var publiclyClosed = false
                while (System.nanoTime() < deadline && !publiclyClosed) {
                    publiclyClosed = try { old.api; false } catch (_: IllegalStateException) { true }
                    Thread.yield()
                }
                assertTrue("close must mark the public owner before drain", publiclyClosed)
                assertTrue("close must await the queued recording link", closer!!.isAlive)
            }
        } finally {
            closer?.join(5000)
            if (closer == null) old.close()
        }
        assertFalse("graph drain did not finish", closer!!.isAlive)
        val replacement = graph("graph-drain")
        try {
            assertEquals(call.id, replacement.recordings.store.linkedRecording(statisticsId)!!.id)
            assertNotNull(replacement.recordings.store.get(call.id))
            assertEquals("31234", replacement.api.callers.getCallDetail(statisticsId).requiredValue().number)
        } finally { replacement.close() }
    }

    @Test fun publicSqlWritersRejectWhileColdIncomingObservationDrains() = runBlocking {
        val old = graph("public-sql-drain")
        val api = old.api
        api.callers.saveProfile(SaveCallerProfile("31234", "before", "unit", null)).requiredValue()
        val quiet = api.settings.getQuietHours().requiredValue()
        val bound = api.commandApi(CommandContext("sql-drain", old.coordinator.capture(), 0, active = { true },
            expectedScopes = old.coordinator.expect(setOf("profile:31234", "quiet"))))
        old.backend.refreshDecisionSnapshot()
        val incoming = old.incomingCalls
        // Control scheduling of the real existing executor, without a production callback/facade.
        val observations = incoming.javaClass.getDeclaredField("observations").let {
            it.isAccessible = true
            it.get(incoming) as java.util.concurrent.ExecutorService
        }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        observations.submit { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        incoming.inspect("cold-drain", "31234", old.configuration.clock.millis(), null)
        val closer = Thread { old.close() }.apply { isDaemon = true; start() }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var publiclyClosed = false
            while (System.nanoTime() < deadline && !publiclyClosed) {
                publiclyClosed = try { old.api; false } catch (_: IllegalStateException) { true }
                Thread.yield()
            }
            assertTrue(publiclyClosed)
            assertTrue(closer.isAlive)
            assertTrue(api.callers.saveProfile(SaveCallerProfile("31234", "after", "unit", null)) is ApiResult.Failure)
            assertTrue(bound.settings.saveQuietHours(quiet.copy(allNumbers = !quiet.allNumbers)) is ApiResult.Failure)
        } finally { release.countDown(); closer.join(5000) }
        assertFalse(closer.isAlive)
        val replacement = graph("public-sql-drain")
        try {
            assertEquals("before", replacement.api.callers.getEditor("31234").requiredValue().draft.name)
            assertEquals(quiet, replacement.api.settings.getQuietHours().requiredValue())
            assertFalse(replacement.diagnostics.read().any { it.message.contains("call.record_failed") })
        } finally { replacement.close() }
    }

    private fun graph(name: String): BackendGraph {
        val context = RuntimeEnvironment.getApplication()
        return BackendGraph(context, BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = "$name.db", ringPreferencesName = "${name}_ring",
            widgetPreferencesName = "${name}_widgets", appearancePreferencesName = "${name}_appearance"),
            object : UiNotificationEntryPoints {
                override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
                override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
            }, object : IncomingCallUiSink { override fun show(request: IncomingCallUiRequest) = Unit })
    }
}
