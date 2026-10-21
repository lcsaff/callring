package com.greatwall.callring.ui.app.bridge

import androidx.compose.runtime.Immutable
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.ThemeDefinition
import com.greatwall.callring.ui.designsystem.ThemeSpec
import java.util.Collections

val DefaultAppearance = AppearanceSettings(
    selection = ThemeSelection.PRESET,
    presetId = "unicom_red",
    customSeedRgb = null,
    colorMode = ColorMode.FOLLOW_SYSTEM,
)

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
    internal fun resolve(settings: AppearanceSettings): ThemeDefinition = when (settings.selection) {
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
    }
}

fun AppearanceSettings.toThemeSpec(systemDark: Boolean, registry: ThemeRegistry = ThemeRegistry.Default): ThemeSpec =
    ThemeSpec(registry.resolve(this), usesDarkColors(systemDark))

private fun AppearanceSettings.usesDarkColors(systemDark: Boolean): Boolean = when (colorMode) {
        ColorMode.FOLLOW_SYSTEM -> systemDark
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

/** Cache palette construction across feedback and color-mode-only recompositions. */
@androidx.compose.runtime.Composable
fun AppearanceSettings.rememberAppearanceThemeSpec(
    systemDark: Boolean, registry: ThemeRegistry = ThemeRegistry.Default,
): ThemeSpec {
    val definition = androidx.compose.runtime.remember(registry, selection, presetId, customSeedRgb) {
        toThemeSpec(systemDark, registry).definition
    }
    return ThemeSpec(definition, usesDarkColors(systemDark))
}
