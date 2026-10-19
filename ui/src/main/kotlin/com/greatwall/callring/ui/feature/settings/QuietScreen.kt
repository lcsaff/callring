package com.greatwall.callring.ui.feature.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*

@Composable
fun QuietScreen(
    state: LoadState<QuietSettings>, draft: QuietSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (QuietSettings) -> Unit,
    // The route still forwards a save callback from the previous page model; immediate editing never renders one.
    @Suppress("UNUSED_PARAMETER") onSave: () -> Unit,
) {
    SettingsLayout("免打扰", state, mutation, onBack, onRetry) { saved ->
        // Custom mode without any window is a local editing state: it is shown with an error but never committed.
        var local by remember(saved) { mutableStateOf<QuietSettings?>(null) }
        val value = local ?: draft ?: saved
        fun apply(next: QuietSettings) {
            local = next
            if (next.mode != QuietMode.CUSTOM || next.weeklyWindows.values.any { it.isNotEmpty() }) onChange(next)
        }
        val hasCustomWindow = value.weeklyWindows.values.any { it.isNotEmpty() }
        SettingsCard("免打扰模式") {
            QuietMode.entries.forEach { mode ->
                FilterChip(value.mode == mode, { apply(value.copy(mode = mode)) },
                    label = { Text(UiLabels.quiet(mode)) })
            }
            FieldError(mutation, "mode")
            SwitchSettingRow("应用于所有号码", value.allNumbers,
                { apply(value.copy(allNumbers = it)) }, summary = "关闭时按来电规则处理")
            FieldError(mutation, "allNumbers")
        }
        if (value.mode == QuietMode.CUSTOM) {
            SettingsCard("每周自定义时段") {
                Text("无时段表示当天不静音。结束时间早于开始时间时，时段跨到次日。")
                weekLabels.forEachIndexed { index, label ->
                    val day = index + 1
                    WindowsField(label, value.weeklyWindows[day].orEmpty(), true,
                        { apply(value.copy(weeklyWindows = value.weeklyWindows + (day to it))) },
                        {}, mutation, "weeklyWindows.$day", "weeklyWindows[$day]")
                }
                InputError(if (!hasCustomWindow) "自定义免打扰至少需要一个时段" else null)
                FieldError(mutation, "weeklyWindows", includeDescendants = false)
            }
        }
    }
}
