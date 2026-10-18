package com.greatwall.callring.platform

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
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
    @Test fun bindingsAppearanceAndDiagnosticsPublishRealCommits() {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BusinessRevisionCoordinator()
        val appearance = context.getSharedPreferences("publication_appearance", 0)
        val widgets = context.getSharedPreferences("publication_widgets", 0)
        val initial = AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
        val preferences = AndroidPreferences(appearance, widgets, initial, coordinator)
        preferences.saveBinding(WidgetBinding(7, "31234"))
        assertEquals(1L, coordinator.capture().values["preferences"])
        assertEquals(listOf(WidgetBinding(7, "31234")), preferences.listBindings())
        preferences.saveAppearance(initial.copy(colorMode = ColorMode.DARK))
        assertEquals(2L, coordinator.capture().values["preferences"])
        AndroidDiagnosticsStore(context.getSharedPreferences("publication_diag", 0), Clock.systemUTC(), coordinator).use { it.add(DiagnosticEvent.AUDIO_PLAYING) }
        assertEquals(1L, coordinator.capture().values["diagnostics"])
    }
}
