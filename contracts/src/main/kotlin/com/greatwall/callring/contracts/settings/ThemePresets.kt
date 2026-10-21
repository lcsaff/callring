package com.greatwall.callring.contracts.settings

import java.util.Collections

/** Shared metadata for theme selection; palette construction belongs to the UI. */
data class ThemePreset(val id: String, val name: String, val seedRgb: Int)

object ThemePresets {
    val builtIn: List<ThemePreset> = Collections.unmodifiableList(listOf(
        ThemePreset("unicom_red", "联通红", 0xE60012),
        ThemePreset("sky_blue", "天空蓝", 0x1976D2),
        ThemePreset("teal_green", "青绿色", 0x008577),
    ))
}
