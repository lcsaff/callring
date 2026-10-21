package com.greatwall.callring.ui.designsystem

import android.annotation.SuppressLint
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeMonochrome

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
        /** Fixed fills preserve the selected RGB; text accents adapt independently on neutral surfaces. */
        @SuppressLint("RestrictedApi") // Published utilities in the pinned Material 1.13.0 artifact.
        fun fromSeed(id: String, name: String, seedRgb: Int): ThemeDefinition {
            require(seedRgb in 0x000000..0xFFFFFF) { "主色必须为六位 RGB 颜色" }
            val seed = Hct.fromInt(seedRgb or 0xFF000000.toInt())
            return ThemeDefinition(
                id, name, seedRgb,
                brandScheme(seed, false),
                brandScheme(seed, true),
            )
        }
    }
}

@SuppressLint("RestrictedApi")
private fun brandScheme(seed: Hct, dark: Boolean): ColorScheme {
    val neutral = SchemeMonochrome(Hct.fromInt(0xFF000000.toInt()), dark, 0.0).toComposeColors()
    val fill = Color(seed.toInt())
    val onFill = if (colorContrast(Color.Black, fill) >= colorContrast(Color.White, fill)) Color.Black else Color.White
    val surfaces = listOf(neutral.surface, neutral.surfaceContainerLowest, neutral.surfaceContainerLow,
        neutral.surfaceContainer, neutral.surfaceContainerHigh, neutral.surfaceContainerHighest,
        neutral.surfaceBright, neutral.surfaceDim)
    val accent = readableAccent(seed, surfaces, dark)
    val onAccent = if (colorContrast(Color.Black, accent) >= colorContrast(Color.White, accent)) Color.Black else Color.White
    return neutral.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = neutral.surfaceContainerHigh, onPrimaryContainer = neutral.onSurface,
        secondary = accent, onSecondary = onAccent,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = neutral.surfaceContainerHigh, onTertiaryContainer = neutral.onSurface,
        inversePrimary = readableAccent(seed, listOf(neutral.inverseSurface), !dark),
        primaryFixed = fill, primaryFixedDim = fill, onPrimaryFixed = onFill, onPrimaryFixedVariant = onFill,
        secondaryFixed = fill, secondaryFixedDim = fill, onSecondaryFixed = onFill, onSecondaryFixedVariant = onFill,
        tertiaryFixed = fill, tertiaryFixedDim = fill, onTertiaryFixed = onFill, onTertiaryFixedVariant = onFill,
    )
}

@SuppressLint("RestrictedApi")
private fun readableAccent(seed: Hct, surfaces: List<Color>, dark: Boolean): Color {
    fun readable(color: Color) = surfaces.all { colorContrast(color, it) >= 4.5 }
    val original = Color(seed.toInt())
    if (readable(original)) return original
    // Move only toward the needed luminance; do not replace the brand with a pastel palette.
    val tones = if (dark) kotlin.math.ceil(seed.tone).toInt()..100
        else kotlin.math.floor(seed.tone).toInt() downTo 0
    for (tone in tones) {
        val candidate = Color(Hct.from(seed.hue, seed.chroma, tone.toDouble()).toInt())
        if (readable(candidate)) return candidate
    }
    return if (dark) Color.White else Color.Black
}

internal fun colorContrast(a: Color, b: Color): Double =
    (maxOf(a.luminance(), b.luminance()) + 0.05) / (minOf(a.luminance(), b.luminance()) + 0.05)

@Immutable
data class ThemeSpec(val definition: ThemeDefinition, val dark: Boolean)

val LocalCallringDarkMode = staticCompositionLocalOf { false }

object CallringSpacing {
    val screen = 16.dp
    val section = 20.dp
    val item = 8.dp
    val compact = 6.dp
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
fun CallringTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val definition = spec.definition
    val dark = spec.dark
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
