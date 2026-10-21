package com.greatwall.callring.ui.app.bridge

import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.ThemeDefinition
import org.junit.Assert.*
import org.junit.Test

class AppearanceThemeMapperTest {
    @Test fun modeAndFallbackMatrixPreservesEveryStoredPreference() {
        for (mode in ColorMode.entries) for (systemDark in listOf(false, true)) {
            val expectedDark = when (mode) { ColorMode.FOLLOW_SYSTEM -> systemDark; ColorMode.LIGHT -> false; ColorMode.DARK -> true }
            val inputs = listOf(DefaultAppearance.copy(colorMode = mode),
                AppearanceSettings(ThemeSelection.PRESET, "future", 0x123456, mode)) +
                listOf(null, -1, 0x1000000, 0, 0xFFFFFF).map { AppearanceSettings(ThemeSelection.CUSTOM, "future", it, mode) }
            for (stored in inputs) {
                val before = stored.copy()
                val spec = stored.toThemeSpec(systemDark)
                assertEquals(expectedDark, spec.dark)
                val validCustom = stored.selection == ThemeSelection.CUSTOM && stored.customSeedRgb?.let { it in 0..0xFFFFFF } == true
                assertEquals(if (validCustom) stored.customSeedRgb else 0xE60012, spec.definition.seedRgb)
                assertEquals(before, stored)
                assertNotEquals(spec.definition.light.surface, spec.definition.dark.surface)
            }
        }
    }
    @Test fun defaultsAndImmutableExtendableRegistryKeepPresetOrderAndSeeds() {
        assertEquals(AppearanceSettings(ThemeSelection.PRESET, "unicom_red", null, ColorMode.FOLLOW_SYSTEM), DefaultAppearance)
        assertEquals(listOf("unicom_red", "sky_blue", "teal_green"), ThemeRegistry.Default.presets.map { it.id })
        assertEquals(listOf(0xE60012, 0x1976D2, 0x008577), ThemeRegistry.Default.presets.map { it.seedRgb })
        val added = ThemeRegistry.Default.presets.first().copy(id = "new", name = "New")
        val definitions = (ThemeRegistry.Default.presets + added).toMutableList()
        val registry = ThemeRegistry(definitions)
        definitions.clear()
        assertEquals(added, DefaultAppearance.copy(presetId = "new").toThemeSpec(false, registry).definition)
        assertEquals(4, registry.presets.size)
        assertThrows(UnsupportedOperationException::class.java) { (registry.presets as MutableList<ThemeDefinition>).clear() }
        assertThrows(IllegalArgumentException::class.java) { ThemeRegistry(listOf(added)) }
        assertThrows(IllegalArgumentException::class.java) { ThemeRegistry(ThemeRegistry.Default.presets + ThemeRegistry.Default.presets.first()) }
    }

    @Test fun everySharedPresetSelectsTheDisplayedThemeForEveryColorMode() {
        for (preset in ThemePresets.builtIn) for (mode in ColorMode.entries) {
            val stored = AppearanceSettings(ThemeSelection.PRESET, preset.id, 0x123456, mode)
            val theme = stored.toThemeSpec(false).definition
            assertEquals(preset.name, theme.name)
            assertEquals(preset.id, theme.id)
            assertEquals(preset.seedRgb, theme.seedRgb)
        }
    }
}
