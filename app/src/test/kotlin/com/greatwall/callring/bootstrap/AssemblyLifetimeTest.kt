package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.RecordingSettingsPatch
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.platform.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AssemblyLifetimeTest {
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
