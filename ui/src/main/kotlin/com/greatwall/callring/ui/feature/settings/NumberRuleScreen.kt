package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.greatwall.callring.ui.state.*

@Composable
fun NumberRuleScreen(
    state: LoadState<NumberRuleSettings>, draft: List<NumberRuleDraft>?, mutation: MutationState,
    errors: Map<String, String>, conflict: Boolean, trial: String, trialCheck: NumberCheck?,
    onBack: () -> Unit, onRetry: () -> Unit, onPrefix: (Long, String) -> Unit, onLength: (Long, String) -> Unit,
    onTrial: (String) -> Unit, onCheck: () -> Unit, onReload: () -> Unit,
    onAdd: () -> Unit, onRemove: (Long) -> Unit,
) {
    SettingsLayout("号码规则", state, mutation, onBack, onRetry) {
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CompactRuleField(row.prefix, { onPrefix(row.id, it) }, "号码前缀", "前缀（不限）",
                                errors[prefixKey] != null || mutation.error?.fieldErrors?.containsKey(prefixKey) == true,
                                Modifier.weight(1f).testTag("rule.${row.id}.prefix"))
                            CompactRuleField(row.length, { onLength(row.id, it) }, "号码长度", "长度（默认 5）",
                                errors[lengthKey] != null || mutation.error?.fieldErrors?.containsKey(lengthKey) == true,
                                Modifier.weight(1f).testTag("rule.${row.id}.length"))
                            IconButton({ onRemove(row.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除规则 ${index + 1}")
                            }
                        }
                        InputError(errors[prefixKey])
                        InputError(errors[lengthKey])
                        FieldError(mutation, prefixKey, lengthKey)
                    }
                }
                IconButton(onAdd) { Icon(Icons.Default.Add, contentDescription = "添加规则") }
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

/** Content grows with font scale; the normal row stays at a 48 dp touch target. */
@Composable
private fun CompactRuleField(
    value: String, onValueChange: (String) -> Unit, description: String, hint: String,
    error: Boolean, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onValueChange, modifier.semantics { contentDescription = description }
        .heightIn(min = 48.dp)
        .border(1.dp, if (error) colors.error else colors.outline, RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                inner()
            }
        })
}
