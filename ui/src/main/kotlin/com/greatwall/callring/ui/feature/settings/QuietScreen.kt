package com.greatwall.callring.ui.feature.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.designsystem.components.rememberFormBack
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*

@Composable
fun QuietScreen(
    state: LoadState<QuietSettings>, draft: QuietSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (QuietSettings) -> Unit, onSave: () -> Unit,
) {
    val valid = remember { mutableStateMapOf<Int, Boolean>() }
    val invalidWindows = (draft ?: state.data)?.mode == QuietMode.CUSTOM && valid.values.any { !it }
    val current = draft ?: state.data
    val canSave = current != null && (current.mode != QuietMode.CUSTOM ||
        (current.weeklyWindows.values.any { it.isNotEmpty() } && valid.values.all { it }))
    val requestBack = rememberFormBack(onBack, (draft != null && draft != state.data) || invalidWindows,
        mutation, onSave, canSave)
    SettingsLayout("免打扰", state, mutation, requestBack, onRetry) { saved ->
        val value = draft ?: saved
        val hasCustomWindow = (1..7).any { value.weeklyWindows[it].orEmpty().isNotEmpty() }
        SettingsCard("免打扰模式") {
            QuietMode.entries.forEach { mode ->
                FilterChip(value.mode == mode, { onChange(value.copy(mode = mode)) }, enabled = !mutation.busy,
                    label = { Text(UiLabels.quiet(mode)) })
            }
            FieldError(mutation, "mode")
            SwitchSettingRow("应用于所有号码", value.allNumbers,
                { onChange(value.copy(allNumbers = it)) }, summary = "关闭时按来电规则处理", enabled = !mutation.busy)
            FieldError(mutation, "allNumbers")
        }
        if (value.mode == QuietMode.CUSTOM) {
            SettingsCard("每周自定义时段") {
                Text("无时段表示当天不静音。结束时间早于开始时间时，时段跨到次日。")
                weekLabels.forEachIndexed { index, label ->
                    val day = index + 1
                    WindowsField(label, value.weeklyWindows[day].orEmpty(), !mutation.busy,
                        { onChange(value.copy(weeklyWindows = value.weeklyWindows + (day to it))) },
                        { valid[day] = it }, mutation, "weeklyWindows.$day", "weeklyWindows[$day]")
                }
                InputError(if (!hasCustomWindow) "自定义免打扰至少需要一个时段" else null)
                FieldError(mutation, "weeklyWindows", includeDescendants = false)
            }
        }
        SaveAction(mutation, value.mode != QuietMode.CUSTOM || (hasCustomWindow && valid.values.all { it }), onSave)
    }
}
