package com.greatwall.callring.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    private val visualThemes = listOf(0xE60012, 0x1976D2, 0x008577).map { ThemeDefinition.fromSeed("seed-$it", "Seed", it) }
    @Test fun customExtremeSeedsProduceReadableCompleteLightAndDarkSchemes() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF)) {
            val theme = ThemeDefinition.fromSeed("custom", "自定义", rgb)
            assertEquals(rgb, theme.seedRgb)
            assertReadable(theme.light)
            assertReadable(theme.dark)
            assertNotEquals(theme.light.surface, theme.dark.surface)
        }
    }

    @Test fun brandFillKeepsTheSelectedRgbInBothModesIncludingCustomExtremes() {
        val themes = visualThemes + listOf(0x000000, 0xFFFFFF, 0x777777, 0x00FF00, 0x0000FF).map {
            ThemeDefinition.fromSeed("custom", "自定义", it)
        }
        for (theme in themes) for (scheme in listOf(theme.light, theme.dark)) {
            assertEquals("${theme.id} brand fill changed", Color(theme.seedRgb or 0xFF000000.toInt()), scheme.primaryFixed)
            assertTrue(contrast(scheme.onPrimaryFixed, scheme.primaryFixed) >= 4.5)
        }
    }

    @Test fun allBackgroundsAndSurfacesStayNeutralRegardlessOfTheme() {
        val themes = visualThemes + ThemeDefinition.fromSeed("custom", "自定义", 0xFF00FF)
        for (theme in themes) for (scheme in listOf(theme.light, theme.dark)) {
            for (surface in surfaces(scheme) + listOf(scheme.background, scheme.surfaceVariant, scheme.primaryContainer)) {
                assertEquals("${theme.id} surface is tinted", surface.red, surface.green, 0.0001f)
                assertEquals("${theme.id} surface is tinted", surface.green, surface.blue, 0.0001f)
            }
        }
    }

    @Test fun accentTextStaysReadableOnAllNeutralSurfaceLevels() {
        val themes = visualThemes + listOf(0x000000, 0xFFFFFF, 0xFFFF00, 0x00FF00, 0x777777).map {
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
