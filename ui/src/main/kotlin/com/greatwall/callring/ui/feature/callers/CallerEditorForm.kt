package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.state.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CallerEditorForm(
    state: LoadState<CallerEditorData>, draft: CallerDraft?, mutation: MutationState, numberEditable: Boolean,
    onRetry: () -> Unit, onChange: (CallerDraft) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
    showSuccess: Boolean = true,
    onDeleteRequest: (() -> Unit)? = null,
) {
    val submit = rememberSubmit(onSave)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadFeedback(state, onRetry)
        if (draft != null) {
            val errors = mutation.error?.fieldErrors.orEmpty()
            val enabled = !mutation.busy
            EditorField("号码", draft.number, errors["number"], enabled && numberEditable,
                keyboardType = KeyboardType.Phone) { onChange(draft.copy(number = it)) }
            EditorField("姓名", draft.name, errors["name"], enabled) { onChange(draft.copy(name = it)) }
            EditorField("部门", draft.department, errors["department"], enabled, done = true,
                onDone = submit) { onChange(draft.copy(department = it)) }
            var menu by remember { mutableStateOf(false) }
            val options = state.data?.selectablePositions.orEmpty()
            val selected = options.firstOrNull { it.id == draft.positionId }
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { if (enabled) menu = it }) {
                OutlinedTextField(
                    value = selected?.let { it.name + if (it.active) "" else "（已停用）" }
                        ?: if (draft.positionId == null) "未设置" else "当前职位（不可选）",
                    onValueChange = {}, readOnly = true, enabled = enabled,
                    label = { Text("职位") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("未设置") }, onClick = { menu = false; onChange(draft.copy(positionId = null)) })
                    options.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name + if (option.active) "" else "（已停用）") },
                            enabled = option.active || option.id == draft.positionId,
                            onClick = { menu = false; onChange(draft.copy(positionId = option.id)) })
                    }
                }
            }
            errors["positionId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SwitchSettingRow("白名单", draft.whitelisted, { onChange(draft.copy(whitelisted = it)) },
                "来电绕过应用免打扰", enabled = enabled)
            MutationFeedback(mutation, successMessage = "档案已保存", showSuccess = showSuccess)
            PrimaryAction(if (mutation.busy) "保存中…" else "保存档案", submit, enabled && draft.number.isNotBlank())

            if (onDeleteRequest != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onDeleteRequest, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除该号码及其全部数据") }
            }        }
    }
}

@Composable private fun EditorField(
    title: String, value: String, error: String?, enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text, done: Boolean = false, onDone: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(title) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (done) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { if (enabled) onDone() }),
        enabled = enabled, isError = error != null, supportingText = error?.let { { Text(it) } })
}
