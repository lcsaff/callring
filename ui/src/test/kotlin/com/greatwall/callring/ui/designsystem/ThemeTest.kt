package com.greatwall.callring.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.greatwall.callring.application.api.AppearanceSettings
import com.greatwall.callring.application.api.ColorMode
import com.greatwall.callring.application.api.ThemeSelection
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun defaultAppearanceUsesStableRedPresetAndFollowsSystem() {
        assertEquals(AppearanceSettings(ThemeSelection.PRESET, "unicom_red", null, ColorMode.FOLLOW_SYSTEM), DefaultAppearance)
        assertEquals(listOf("unicom_red", "sky_blue", "teal_green"), ThemeRegistry.presets.map { it.id })
    }

    @Test fun unknownStoredPresetFallsBackForDisplayWithoutChangingPreferences() {
        val stored = AppearanceSettings(ThemeSelection.PRESET, "future_theme", 0x123456, ColorMode.DARK)
        assertEquals("unicom_red", ThemeRegistry.resolve(stored).id)
        assertEquals("future_theme", stored.presetId)
        assertEquals(0x123456, stored.customSeedRgb)
        assertEquals(ColorMode.DARK, stored.colorMode)
    }

    @Test fun registryCanExtendPresetsWithoutChangingResolutionCode() {
        val added = ThemeRegistry.presets.first().copy(id = "new_preset", name = "新主题")
        val definitions = (ThemeRegistry.presets + added).toMutableList()
        val registry = ThemeRegistry(definitions)
        definitions.clear()
        assertEquals(added, registry.resolve(DefaultAppearance.copy(presetId = "new_preset")))
        assertEquals(4, registry.presets.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (registry.presets as MutableList<ThemeDefinition>).clear()
        }
    }

    @Test fun customExtremeSeedsProduceReadableCompleteLightAndDarkSchemes() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF)) {
            val settings = AppearanceSettings(ThemeSelection.CUSTOM, "future_theme", rgb, ColorMode.DARK)
            val theme = ThemeRegistry.resolve(settings)
            assertEquals(rgb, theme.seedRgb)
            assertReadable(theme.light)
            assertReadable(theme.dark)
            assertNotEquals(theme.light.surface, theme.dark.surface)
        }
    }

    @Test fun invalidStoredCustomSeedsFallBackForDisplayAndPreserveStoredPreferences() {
        for (rgb in listOf(null, -1, 0x1000000)) {
            val stored = AppearanceSettings(ThemeSelection.CUSTOM, "future_theme", rgb, ColorMode.DARK)
            assertEquals("unicom_red", ThemeRegistry.resolve(stored).id)
            assertEquals(rgb, stored.customSeedRgb)
            assertEquals(ThemeSelection.CUSTOM, stored.selection)
            assertEquals("future_theme", stored.presetId)
            assertEquals(ColorMode.DARK, stored.colorMode)
        }
    }

    @Test fun explicitDarkModeIsPreservedIndependentlyFromSystemAndTheme() {
        assertTrue(DefaultAppearance.copy(colorMode = ColorMode.DARK).usesDarkColors(false))
        assertFalse(DefaultAppearance.copy(colorMode = ColorMode.LIGHT).usesDarkColors(true))
        assertTrue(DefaultAppearance.usesDarkColors(true))
        assertFalse(DefaultAppearance.usesDarkColors(false))
    }

    @Test fun brandFillKeepsTheSelectedRgbInBothModesIncludingCustomExtremes() {
        val themes = ThemeRegistry.presets + listOf(0x000000, 0xFFFFFF, 0x777777, 0x00FF00, 0x0000FF).map {
            ThemeDefinition.fromSeed("custom", "自定义", it)
        }
        for (theme in themes) for (scheme in listOf(theme.light, theme.dark)) {
            assertEquals("${theme.id} brand fill changed", Color(theme.seedRgb or 0xFF000000.toInt()), scheme.primaryFixed)
            assertTrue(contrast(scheme.onPrimaryFixed, scheme.primaryFixed) >= 4.5)
        }
    }

    @Test fun allBackgroundsAndSurfacesStayNeutralRegardlessOfTheme() {
        val themes = ThemeRegistry.presets + ThemeDefinition.fromSeed("custom", "自定义", 0xFF00FF)
        for (theme in themes) for (scheme in listOf(theme.light, theme.dark)) {
            for (surface in surfaces(scheme) + listOf(scheme.background, scheme.surfaceVariant, scheme.primaryContainer)) {
                assertEquals("${theme.id} surface is tinted", surface.red, surface.green, 0.0001f)
                assertEquals("${theme.id} surface is tinted", surface.green, surface.blue, 0.0001f)
            }
        }
    }

    @Test fun accentTextStaysReadableOnAllNeutralSurfaceLevels() {
        val themes = ThemeRegistry.presets + listOf(0x000000, 0xFFFFFF, 0xFFFF00, 0x00FF00, 0x777777).map {
            ThemeDefinition.fromSeed("custom", "自定义", it)
        }
        for (theme in themes) for (scheme in listOf(theme.light, theme.dark)) {
            assertReadable(scheme)
            for (surface in surfaces(scheme)) {
                assertTrue("${theme.id} accent unreadable on $surface", contrast(scheme.primary, surface) >= 4.5)
            }
        }
    }

    private fun surfaces(scheme: ColorScheme) = listOf(scheme.surface, scheme.surfaceContainerLowest,
        scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest, scheme.surfaceBright, scheme.surfaceDim)

    @Test fun blackAndWhiteThemesKeepActiveAndInactiveTracksDistinct() {
        for (theme in listOf(ThemeDefinition.fromSeed("black", "黑色", 0), ThemeDefinition.fromSeed("white", "白色", 0xFFFFFF))) {
            for (scheme in listOf(theme.light, theme.dark)) {
                assertTrue("${theme.id} track has no active distinction", contrast(scheme.primary, scheme.secondaryContainer) >= 3.0)
            }
        }
    }

    @Test fun blackAndWhiteSelectionContainersRemainVisibleOnCards() {
        val whiteLight = ThemeDefinition.fromSeed("white", "白色", 0xFFFFFF).light
        val blackDark = ThemeDefinition.fromSeed("black", "黑色", 0).dark
        for (scheme in listOf(whiteLight, blackDark)) {
            assertTrue("Selection container disappears", contrast(scheme.secondaryContainer, scheme.surfaceContainerLow) >= 1.2)
            assertTrue(contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5)
        }
    }

    private fun assertReadable(scheme: ColorScheme) {
        for ((foreground, background) in listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onTertiary to scheme.tertiary,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceVariant,
            scheme.onError to scheme.error,
        )) assertTrue("Insufficient text contrast: $foreground on $background", contrast(foreground, background) >= 4.5)
    }

    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(a.luminance(), b.luminance())
        val low = minOf(a.luminance(), b.luminance())
        return (high + 0.05) / (low + 0.05)
    }
}
