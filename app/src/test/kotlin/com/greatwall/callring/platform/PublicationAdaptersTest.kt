package com.greatwall.callring.platform

import com.greatwall.callring.data.preferences.AndroidPreferences
import com.greatwall.callring.platform.audio.PlaybackStateTracker
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import com.greatwall.callring.platform.diagnostics.DiagnosticEvent

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import java.time.Clock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PublicationAdaptersTest {
    @Test fun realPlaybackTrackerAndFollowingPlatformObservationPublishOnce() {
        val coordinator = BusinessRevisionCoordinator()
        val observations = com.greatwall.callring.application.runtime.RuntimeObservationTracker(coordinator)
        val tracker = PlaybackStateTracker { coordinator.publishRuntime(setOf("runtime:ringtonePlayback")) }
        val status = com.greatwall.callring.contracts.runtime.RuntimeStatus(emptyMap(),
            com.greatwall.callring.contracts.runtime.Audibility.AUDIBLE, com.greatwall.callring.contracts.runtime.PhoneActivity.IDLE,
            tracker.snapshot(), true, 0)
        observations.observed(status)
        tracker.mark(com.greatwall.callring.contracts.runtime.PlaybackMode.PREVIEW, com.greatwall.callring.contracts.runtime.PlaybackState.PLAYING)
        observations.observed(status.copy(playback = tracker.snapshot(), checkedAtEpochMillis = 1000))
        assertEquals(1L, coordinator.marker())
        tracker.mark(com.greatwall.callring.contracts.runtime.PlaybackMode.PREVIEW, com.greatwall.callring.contracts.runtime.PlaybackState.PLAYING)
        assertEquals(1L, coordinator.marker())
    }
    @Test fun diagnosticsSaturationAndFailedPersistenceDoNotPublish() {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BusinessRevisionCoordinator()
        val preferences = context.getSharedPreferences("t11_diag_saturation", 0)
        val clock = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
        AndroidDiagnosticsStore(preferences, clock, coordinator).use { store ->
            repeat(18) { store.add(DiagnosticEvent.AUDIO_PLAYING) }
            val before = coordinator.marker()
            store.add(DiagnosticEvent.AUDIO_PLAYING)
            assertEquals("identical persisted history is a no-op", before, coordinator.marker())
            val snapshot = coordinator.invalidations.snapshots.value
            assertEquals(setOf(com.greatwall.callring.contracts.change.DataDomain.RUNTIME), snapshot.domains.keys)
            assertEquals(setOf("diagnostics"), snapshot.domains.getValue(com.greatwall.callring.contracts.change.DataDomain.RUNTIME).entityRevisions.keys)
        }
        val failed = object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit() = false
                }
            }
        }
        val before = coordinator.marker()
        AndroidDiagnosticsStore(failed, clock, coordinator).use { store ->
            assertTrue(runCatching { store.add(DiagnosticEvent.AUDIO_STOPPED) }.isFailure)
        }
        assertEquals(before, coordinator.marker())
    }
    @Test fun bindingsAppearanceAndDiagnosticsPublishRealCommits() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BusinessRevisionCoordinator()
        val appearance = context.getSharedPreferences("publication_appearance", 0)
        val widgets = context.getSharedPreferences("publication_widgets", 0)
        val initial = AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
        val preferences = AndroidPreferences(appearance, widgets, initial)
        val service = com.greatwall.callring.application.widget.WidgetBindingsService(preferences,
            { WidgetInstallState(emptyList(), com.greatwall.callring.contracts.runtime.AccessState.GRANTED) }, coordinator, kotlinx.coroutines.Dispatchers.Unconfined, operationActive = { true })
        assertTrue(service.saveBinding(7, "31234") is com.greatwall.callring.contracts.common.ApiResult.Success)
        assertEquals(1L, coordinator.marker())
        service.saveBinding(7, "31234")
        service.removeBinding(8)
        assertEquals(1L, coordinator.marker())
        assertEquals(setOf("7"), coordinator.invalidations.snapshots.value.domains.getValue(com.greatwall.callring.contracts.change.DataDomain.WIDGET_BINDINGS).entityRevisions.keys)
        assertEquals(1L, coordinator.capture().values["preferences"])
        assertEquals(listOf(WidgetBinding(7, "31234")), preferences.listBindings())
        preferences.saveAppearance(initial.copy(colorMode = ColorMode.DARK))
        assertEquals("appearance persistence publishes only through its business service", 1L, coordinator.capture().values["preferences"])
        assertEquals(ColorMode.DARK, preferences.getAppearance().colorMode)
        AndroidDiagnosticsStore(context.getSharedPreferences("publication_diag", 0), Clock.systemUTC(), coordinator).use { it.add(DiagnosticEvent.AUDIO_PLAYING) }
        assertEquals(1L, coordinator.capture().values["diagnostics"])
    }
}
