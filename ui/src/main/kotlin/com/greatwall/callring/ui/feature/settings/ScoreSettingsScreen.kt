package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.greatwall.callring.application.api.ScoreSettings
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.*

@Composable
fun ScoreSettingsScreen(
    state: LoadState<ScoreSettings>, draft: ScoreSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (ScoreSettings) -> Unit,
    onSave: () -> Unit, onReset: () -> Unit,
) {
    SettingsLayout("评分设置", state, mutation, onBack, onRetry) { saved ->
        val value = draft ?: saved
        val validity = remember { mutableStateMapOf<String, Boolean>() }
        SettingsCard("评分参数") {
            Text("权重可为零；重复来电窗口与观察天数必须大于零。")
            ScoreNumber("非工作来电权重", "offHoursWeight", value.offHoursWeight, 0, mutation,
                { onChange(value.copy(offHoursWeight = it)) }, { validity["offHoursWeight"] = it })
            ScoreNumber("重复来电权重", "repeatWeight", value.repeatWeight, 0, mutation,
                { onChange(value.copy(repeatWeight = it)) }, { validity["repeatWeight"] = it })
            ScoreNumber("重复来电窗口（分钟）", "repeatMinutes", value.repeatMinutes, 1, mutation,
                { onChange(value.copy(repeatMinutes = it)) }, { validity["repeatMinutes"] = it })
            ScoreNumber("观察天数", "observationDays", value.observationDays, 1, mutation,
                { onChange(value.copy(observationDays = it)) }, { validity["observationDays"] = it })
        }
        SaveAction(mutation, validity.values.all { it }, onSave)
        OutlinedButton(onReset, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("恢复默认评分设置") }
    }
}

@Composable
private fun ScoreNumber(
    label: String, key: String, value: Int, minimum: Int, mutation: MutationState,
    onChange: (Int) -> Unit, onValidity: (Boolean) -> Unit,
) {
    // A committed reset replaces invalid raw text even when the typed value is unchanged.
    var raw by rememberSaveable(value, mutation.completed) { mutableStateOf(value.toString()) }
    val valid = raw.toIntOrNull()?.let { it >= minimum } == true
    LaunchedEffect(raw) { onValidity(valid) }
    OutlinedTextField(raw, {
        raw = it
        val parsed = it.toIntOrNull()
        onValidity(parsed != null && parsed >= minimum)
        if (parsed != null && parsed >= minimum) onChange(parsed)
    }, enabled = !mutation.busy, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
        modifier = Modifier.fillMaxWidth().testTag("score-$key"))
    InputError(if (valid) null else if (minimum == 0) "请输入非负整数" else "请输入正整数")
    FieldError(mutation, key)
}
