package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.greatwall.callring.ui.designsystem.components.ConfirmAction
import com.greatwall.callring.ui.designsystem.components.formKeyboardActions
import com.greatwall.callring.contracts.settings.ScoreSettings
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.*

@Composable
fun ScoreSettingsScreen(
    state: LoadState<ScoreSettings>, draft: ScoreSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (ScoreSettings) -> Unit, onReset: () -> Unit,
) {
    SettingsLayout("评分设置", state, mutation, onBack, onRetry) { saved ->
        val value = draft ?: saved
        SettingsCard("评分参数") {
            Text("权重可为零；重复来电窗口与观察天数必须大于零。")
            // The page model debounces numeric patches; invalid intermediate text is never submitted.
            ScoreNumber("非工作来电权重", "offHoursWeight", value.offHoursWeight, 0, mutation.completed, mutation) {
                onChange(value.copy(offHoursWeight = it))
            }
            ScoreNumber("重复来电权重", "repeatWeight", value.repeatWeight, 0, mutation.completed, mutation) {
                onChange(value.copy(repeatWeight = it))
            }
            ScoreNumber("重复来电窗口（分钟）", "repeatMinutes", value.repeatMinutes, 1, mutation.completed, mutation) {
                onChange(value.copy(repeatMinutes = it))
            }
            ScoreNumber("观察天数", "observationDays", value.observationDays, 1, mutation.completed, mutation) {
                onChange(value.copy(observationDays = it))
            }
        }
        ConfirmAction("恢复默认评分设置", "恢复默认评分设置？", "当前评分参数将被默认值替换。", onReset,
            enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ScoreNumber(
    label: String, key: String, value: Int, minimum: Int, resetKey: Long, mutation: MutationState,
    onChange: (Int) -> Unit,
) {
    // A committed reset replaces invalid raw text even when the typed value is unchanged.
    var raw by rememberSaveable(resetKey) { mutableStateOf(value.toString()) }
    val parsed = raw.toIntOrNull()?.takeIf { it >= minimum }
    OutlinedTextField(raw, { next ->
        raw = next
        next.toIntOrNull()?.takeIf { it >= minimum }?.let(onChange)
    }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
            imeAction = if (key == "observationDays") ImeAction.Done else ImeAction.Next),
        keyboardActions = formKeyboardActions(), isError = parsed == null,
        modifier = Modifier.fillMaxWidth().testTag("score-$key"))
    InputError(if (parsed != null) null else if (minimum == 0) "请输入非负整数" else "请输入正整数")
    FieldError(mutation, key)
}
