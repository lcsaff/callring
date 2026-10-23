package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.rememberFormBack
import com.greatwall.callring.ui.state.*

@Composable
fun NumberRuleScreen(
    state: LoadState<NumberRuleSettings>, draft: NumberRuleDraft?, mutation: MutationState,
    errors: Map<String, String>, conflict: Boolean, trial: String, trialCheck: NumberCheck?,
    onBack: () -> Unit, onRetry: () -> Unit, onPrefix: (String) -> Unit, onLength: (String) -> Unit,
    onTrial: (String) -> Unit, onCheck: () -> Unit, onSave: () -> Unit, onReload: () -> Unit,
) {
    val dirty = draft != null && state.data?.let { draft != NumberRuleDraft(it.prefix, it.length.toString()) } == true
    val canSave = state.loaded && state.error == null && errors.isEmpty() && draft != null
    val back = rememberFormBack(onBack, dirty, mutation, onSave, canSave)
    SettingsLayout("号码规则", state, mutation, back, onRetry) {
        if (draft != null) {
            SettingsCard("来电号码规则") {
                Text("已有记录不重算；后续同步可能补入历史通话")
                if (conflict || mutation.error?.code == ErrorCode.CONFLICT) {
                    Text("规则已被其他操作更新，当前输入已保留", color = MaterialTheme.colorScheme.error)
                    TextButton(onReload, enabled = !mutation.busy) { Text("重新加载规则") }
                }
                OutlinedTextField(draft.prefix, onPrefix, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("号码前缀") }, enabled = !mutation.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errors["prefix"] != null, supportingText = { Text(errors["prefix"] ?: if (draft.prefix.isEmpty()) "不限前缀" else "只允许数字 0–9") })
                FieldError(mutation, "prefix")
                OutlinedTextField(draft.length, onLength, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("号码长度") }, enabled = !mutation.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errors["length"] != null, supportingText = { Text(errors["length"] ?: "1–30 位") })
                FieldError(mutation, "length")
                Button(onSave, enabled = canSave && !mutation.busy) { Text(if (mutation.busy) "正在保存…" else "保存规则") }
            }
            SettingsCard("试用已保存规则") {
                OutlinedTextField(trial, onTrial, Modifier.fillMaxWidth(), singleLine = true, label = { Text("试用号码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                TextButton(onCheck, enabled = state.loaded) { Text("检查号码") }
                if (trial.isNotBlank() && trialCheck != null) {
                    Text(if (!trialCheck.validProfileNumber) "请输入有效号码" else if (trialCheck.matchesRule) "符合规则号码" else "不符合规则号码")
                }
            }
        }
    }
}
