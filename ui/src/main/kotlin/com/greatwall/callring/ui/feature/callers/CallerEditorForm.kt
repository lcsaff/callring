package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.settings.PositionOption
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.state.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CallerEditorForm(
    state: LoadState<CallerEditorData>, draft: CallerDraft?, mutation: MutationState, numberEditable: Boolean,
    onRetry: () -> Unit, onChange: (CallerDraft) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
    showSuccess: Boolean = true,
    onDeleteRequest: (() -> Unit)? = null,
    field: CallerProfileField? = null,
    canSave: Boolean = true,
    onReload: () -> Unit = {},
) {
    val submit = rememberSubmit(onSave)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadFeedback(state, onRetry)
        if (draft != null) {
            val errors = mutation.error?.fieldErrors.orEmpty().mapValues { (_, message) ->
                when(message) {
                    "caller.field.too_long" -> "最多输入 500 个字符"
                    "draft.field_changed" -> "此信息已更新，请重新加载后保存"
                    else -> message
                }
            }
            val reloadingConflict = field != null && state.loading && mutation.error?.code == ErrorCode.CONFLICT
            val enabled = !mutation.busy && !reloadingConflict
            val positions = state.data?.selectablePositions.orEmpty()
            if (field == null) {
                EditorField("号码", draft.number, errors["number"], enabled && numberEditable,
                    keyboardType = KeyboardType.Phone) { onChange(draft.copy(number = it)) }
                EditorField("姓名", draft.name, errors["name"], enabled) { onChange(draft.copy(name = it)) }
                EditorField("部门", draft.department, errors["department"], enabled, done = true,
                    onDone = submit) { onChange(draft.copy(department = it)) }
                EditorField("自述职位", draft.selfReportedPosition, errors["selfReportedPosition"], enabled) {
                    onChange(draft.copy(selfReportedPosition = it))
                }
                EditorField("所属地区", draft.region, errors["region"], enabled) { onChange(draft.copy(region = it)) }
                Text("有明确通话依据时自动补全空缺；有异议的信息允许后续通话纠正。", style = MaterialTheme.typography.bodySmall)
                CallerProfileField.entries.filter { it.hasMetadata }.forEach {
                    IdentityMetadataControls(it, draft, enabled, onChange)
                }
                PositionField("职位", positions, draft.positionId, errors["positionId"], enabled) {
                    onChange(draft.copy(positionId = it))
                }
                WhitelistField(draft.whitelisted, enabled) { onChange(draft.copy(whitelisted = it)) }
            } else {
                SingleProfileField(field, draft, positions, errors[field.key], enabled, submit, onChange)
                if (field.hasMetadata) IdentityMetadataControls(field, draft, enabled, onChange)
            }
            MutationFeedback(mutation, successMessage = "档案已保存", showSuccess = showSuccess)
            if (field != null && mutation.error?.code == ErrorCode.CONFLICT) {
                TextButton(onClick = onReload, enabled = enabled && !state.loading) { Text("重新加载并放弃输入") }
            }
            PrimaryAction(if (mutation.busy) "保存中…" else "保存档案", submit,
                enabled && canSave && draft.number.isNotBlank())

            if (onDeleteRequest != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onDeleteRequest, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除号码档案及通话记录") }
            }
        }
    }
}

@Composable private fun SingleProfileField(
    field: CallerProfileField, draft: CallerDraft, positions: List<PositionOption>, error: String?,
    enabled: Boolean, submit: () -> Unit, onChange: (CallerDraft) -> Unit,
) {
    when (field) {
        CallerProfileField.NAME -> EditorField(field.label, draft.name, error, enabled, done = true, onDone = submit) {
            onChange(draft.copy(name = it))
        }
        CallerProfileField.DEPARTMENT -> EditorField(field.label, draft.department, error, enabled, done = true, onDone = submit) {
            onChange(draft.copy(department = it))
        }
        CallerProfileField.SELF_REPORTED_POSITION ->
            EditorField(field.label, draft.selfReportedPosition, error, enabled, done = true, onDone = submit) {
                onChange(draft.copy(selfReportedPosition = it))
            }
        CallerProfileField.REGION -> EditorField(field.label, draft.region, error, enabled, done = true, onDone = submit) {
            onChange(draft.copy(region = it))
        }
        CallerProfileField.POSITION -> PositionField(field.label, positions, draft.positionId, error, enabled, showLevel = true) {
            onChange(draft.copy(positionId = it))
        }
        CallerProfileField.WHITELIST -> WhitelistField(draft.whitelisted, enabled) { onChange(draft.copy(whitelisted = it)) }
    }
}

@Composable private fun IdentityMetadataControls(
    field: CallerProfileField, draft: CallerDraft, enabled: Boolean, onChange: (CallerDraft) -> Unit,
) {
    val metadata = draft.identityMetadata[field.key] ?: CallerFieldMetadata()
    SwitchSettingRow("${field.label} 有异议", metadata.disputed, { checked ->
        onChange(draft.copy(identityMetadata = draft.identityMetadata + (field.key to metadata.copy(disputed = checked))))
    }, enabled = enabled)
    if (metadata.evidence.isNotBlank()) {
        var expanded by remember(field, metadata.revision) { mutableStateOf(false) }
        TextButton({ expanded = !expanded }) { Text(if (expanded) "收起来源" else "${field.label} 的来源") }
        if (expanded) Text("通话归档 ${metadata.archiveId}：${metadata.evidence}", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PositionField(
    label: String, options: List<PositionOption>, selectedId: Long?, error: String?, enabled: Boolean,
    showLevel: Boolean = false, onChange: (Long?) -> Unit,
) {
    fun description(option: PositionOption): String {
        val level = if (showLevel) " · ${option.level} 级" else ""
        val status = if (option.active) "" else "（已停用）"
        return "${option.name}$level$status"
    }
    var menu by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { if (enabled) menu = it }) {
        OutlinedTextField(
            value = selected?.let(::description) ?: if (selectedId == null) "未设置" else "当前职位（不可选）",
            onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("未设置") }, onClick = { menu = false; onChange(null) })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(description(option)) }, enabled = option.active || option.id == selectedId,
                    onClick = { menu = false; onChange(option.id) })
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun WhitelistField(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SwitchSettingRow("白名单", checked, onChange, "来电绕过应用免打扰", enabled = enabled)
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
