package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.state.*

@Composable fun CallerEditorForm(
    state: LoadState<CallerEditorData>, draft: CallerDraft?, mutation: MutationState, numberEditable: Boolean,
    onRetry: () -> Unit, onChange: (CallerDraft) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadFeedback(state, onRetry)
        if (draft != null) {
            val errors = mutation.error?.fieldErrors.orEmpty()
            val enabled = !mutation.busy
            EditorField("号码", draft.number, errors["number"], enabled && numberEditable) { onChange(draft.copy(number = it)) }
            EditorField("姓名", draft.name, errors["name"], enabled) { onChange(draft.copy(name = it)) }
            EditorField("部门", draft.department, errors["department"], enabled) { onChange(draft.copy(department = it)) }
            var menu by remember { mutableStateOf(false) }
            val options = state.data?.selectablePositions.orEmpty()
            val selected = options.firstOrNull { it.id == draft.positionId }
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("职位：${selected?.let { it.name + if (it.active) "" else "（已停用）" } ?: if (draft.positionId == null) "未设置" else "当前职位（不可选）"}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("未设置") }, onClick = { menu = false; onChange(draft.copy(positionId = null)) })
                    options.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name + if (option.active) "" else "（已停用）") },
                            enabled = option.active || option.id == draft.positionId,
                            onClick = { menu = false; onChange(draft.copy(positionId = option.id)) })
                    }
                }
            }
            errors["positionId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SettingRow("白名单", "来电绕过应用免打扰", trailing = {
                Switch(draft.whitelisted, { onChange(draft.copy(whitelisted = it)) }, Modifier.semantics { contentDescription = "白名单" }, enabled = enabled)
            })
            MutationFeedback(mutation)
            PrimaryAction("保存档案", onSave, enabled)
        }
    }
}

@Composable private fun EditorField(title: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(title) }, singleLine = true,
        enabled = enabled, isError = error != null, supportingText = { if (error != null) Text(error) })
}
