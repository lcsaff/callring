package com.greatwall.callring.platform

import com.greatwall.callring.application.BusinessRevisionCoordinator
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
    @Test fun bindingsAppearanceAndDiagnosticsPublishRealCommits() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BusinessRevisionCoordinator()
        val appearance = context.getSharedPreferences("publication_appearance", 0)
        val widgets = context.getSharedPreferences("publication_widgets", 0)
        val initial = AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
        val preferences = AndroidPreferences(appearance, widgets, initial)
        val service = com.greatwall.callring.application.widget.WidgetBindingsService(preferences,
            { WidgetInstallState(emptyList(), com.greatwall.callring.contracts.runtime.AccessState.GRANTED) }, coordinator, kotlinx.coroutines.Dispatchers.Unconfined)
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
