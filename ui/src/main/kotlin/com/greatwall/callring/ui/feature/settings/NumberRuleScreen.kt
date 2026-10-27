package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    state: LoadState<NumberRuleSettings>, draft: List<NumberRuleDraft>?, mutation: MutationState,
    errors: Map<String, String>, conflict: Boolean, trial: String, trialCheck: NumberCheck?,
    onBack: () -> Unit, onRetry: () -> Unit, onPrefix: (Long, String) -> Unit, onLength: (Long, String) -> Unit,
    onTrial: (String) -> Unit, onCheck: () -> Unit, onSave: () -> Unit, onReload: () -> Unit,
    onAdd: () -> Unit, onRemove: (Long) -> Unit,
) {
    val dirty = draft != null && state.data?.let { draft.map { row -> row.prefix to row.length } != it.rules.map { row -> row.prefix to row.length.toString() } } == true
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
                if (draft.isEmpty()) Text("不会匹配任何号码")
                draft.forEachIndexed { index, row ->
                    key(row.id) {
                        val prefixKey = "rules[$index].prefix"
                        val lengthKey = "rules[$index].length"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(row.prefix, { onPrefix(row.id, it) }, Modifier.fillMaxWidth().testTag("rule.${row.id}.prefix"), singleLine = true,
                                    label = { Text("号码前缀") }, enabled = !mutation.busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = errors[prefixKey] != null || mutation.error?.fieldErrors?.containsKey(prefixKey) == true,
                                    supportingText = { Text(errors[prefixKey] ?: if (row.prefix.isEmpty()) "不限前缀" else "可输入多位数字，例如 010 或 138") })
                                FieldError(mutation, prefixKey)
                            }
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(row.length, { onLength(row.id, it) }, Modifier.fillMaxWidth().testTag("rule.${row.id}.length"), singleLine = true,
                                    label = { Text("号码长度") }, enabled = !mutation.busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = errors[lengthKey] != null || mutation.error?.fieldErrors?.containsKey(lengthKey) == true,
                                    supportingText = { Text(errors[lengthKey] ?: "1–30 位") })
                                FieldError(mutation, lengthKey)
                            }
                            IconButton({ onRemove(row.id) }, enabled = !mutation.busy) {
                                Icon(Icons.Default.Delete, contentDescription = "删除规则 ${index + 1}")
                            }
                        }
                    }
                }
                IconButton(onAdd, enabled = !mutation.busy) { Icon(Icons.Default.Add, contentDescription = "添加规则") }
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
