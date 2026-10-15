package com.greatwall.callring.ui.designsystem

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.greatwall.callring.application.api.AppearanceSettings
import com.greatwall.callring.application.api.ColorMode
import com.greatwall.callring.application.api.ThemeSelection
import java.util.Collections

val DefaultAppearance = AppearanceSettings(
    selection = ThemeSelection.PRESET,
    presetId = "unicom_red",
    customSeedRgb = null,
    colorMode = ColorMode.FOLLOW_SYSTEM,
)

@Immutable
data class ThemeDefinition(
    val id: String,
    val name: String,
    val seedRgb: Int,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    init {
        require(id.isNotBlank()) { "主题标识不能为空" }
        require(seedRgb in 0x000000..0xFFFFFF) { "主色必须为六位 RGB 颜色" }
    }

    companion object {
        /** Material Color Utilities supplies every role; no wallpaper colors enter this palette. */
        @SuppressLint("RestrictedApi") // Published utilities in the pinned Material 1.13.0 artifact.
        fun fromSeed(id: String, name: String, seedRgb: Int): ThemeDefinition {
            require(seedRgb in 0x000000..0xFFFFFF) { "主色必须为六位 RGB 颜色" }
            val seed = Hct.fromInt(seedRgb or 0xFF000000.toInt())
            return ThemeDefinition(
                id, name, seedRgb,
                SchemeTonalSpot(seed, false, 0.0).toComposeColors(),
                SchemeTonalSpot(seed, true, 0.0).toComposeColors(),
            )
        }
    }
}

/** A registry is an immutable snapshot; extending it only requires additional definitions. */
@Immutable
class ThemeRegistry(definitions: List<ThemeDefinition>) {
    val presets: List<ThemeDefinition> = Collections.unmodifiableList(ArrayList(definitions))
    private val byId = presets.associateBy { it.id }

    init {
        require(byId.size == presets.size) { "主题标识不能重复" }
        require("unicom_red" in byId) { "必须包含默认联通红主题" }
    }

    /** Display fallback never alters the appearance preferences supplied by the application API. */
    fun resolve(settings: AppearanceSettings): ThemeDefinition = when (settings.selection) {
        ThemeSelection.PRESET -> byId[settings.presetId] ?: byId.getValue("unicom_red")
        ThemeSelection.CUSTOM -> settings.customSeedRgb?.takeIf { it in 0x000000..0xFFFFFF }?.let {
            ThemeDefinition.fromSeed("custom", "自定义", it)
        } ?: byId.getValue("unicom_red")
    }

    companion object {
        val Default: ThemeRegistry by lazy {
            ThemeRegistry(listOf(
                ThemeDefinition.fromSeed("unicom_red", "联通红", 0xE60012),
                ThemeDefinition.fromSeed("sky_blue", "天空蓝", 0x1976D2),
                ThemeDefinition.fromSeed("teal_green", "青绿色", 0x008577),
            ))
        }
        val presets: List<ThemeDefinition> get() = Default.presets
        fun resolve(settings: AppearanceSettings): ThemeDefinition = Default.resolve(settings)
    }
}

fun AppearanceSettings.usesDarkColors(systemDark: Boolean): Boolean = when (colorMode) {
    ColorMode.FOLLOW_SYSTEM -> systemDark
    ColorMode.LIGHT -> false
    ColorMode.DARK -> true
}

val LocalCallringDarkMode = staticCompositionLocalOf { false }

object CallringSpacing {
    val screen = 20.dp
    val section = 24.dp
    val item = 12.dp
    val compact = 8.dp
}

private val CallringTypography = Typography()
private val CallringShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CallringTheme(
    appearance: AppearanceSettings = DefaultAppearance,
    systemDark: Boolean = isSystemInDarkTheme(),
    registry: ThemeRegistry = ThemeRegistry.Default,
    content: @Composable () -> Unit,
) {
    val definition = remember(registry, appearance.selection, appearance.presetId, appearance.customSeedRgb) {
        registry.resolve(appearance)
    }
    val dark = appearance.usesDarkColors(systemDark)
    CompositionLocalProvider(LocalCallringDarkMode provides dark) {
        MaterialTheme(
            colorScheme = if (dark) definition.dark else definition.light,
            typography = CallringTypography,
            shapes = CallringShapes,
            content = content,
        )
    }
}

@SuppressLint("RestrictedApi")
private fun DynamicScheme.toComposeColors(): ColorScheme = lightColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(surfaceTint),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
)
