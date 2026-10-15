package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.EmptyContent
import com.greatwall.callring.ui.designsystem.components.SettingRow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.state.*

@Composable
fun PositionsScreen(
    state: LoadState<PositionSettings>, mutation: MutationState, onBack: () -> Unit, onRetry: () -> Unit,
    onSave: (SavePosition) -> Unit, onDelete: (Long) -> Unit, onMine: (Long?) -> Unit,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("1") }
    var active by rememberSaveable { mutableStateOf(true) }
    var openedAt by rememberSaveable { mutableStateOf(mutation.completed) }
    var lastDeletedId by rememberSaveable { mutableStateOf<Long?>(null) }
    fun edit(option: PositionOption?) {
        editingId = option?.id; name = option?.name.orEmpty(); level = (option?.level ?: 1).toString()
        active = option?.active ?: true; openedAt = mutation.completed; editorOpen = true
    }
    LaunchedEffect(mutation.completed) {
        if (mutation.completed < openedAt) openedAt = mutation.completed
        else if (mutation.completed > openedAt) editorOpen = false
    }
    SettingsLayout("职位管理", state, mutation, onBack, onRetry) { data ->
        SettingsCard("我的职位") {
            val mine = data.options.firstOrNull { it.id == data.myPositionId }
            Text(mine?.let { it.name + if (it.active) "" else "（已停用）" } ?: "尚未设置")
            if (data.myPositionId != null) OutlinedButton({ onMine(null) }, enabled = !mutation.busy) { Text("清除我的职位") }
            FieldError(mutation, "myPositionId")
        }
        if (mutation.error?.code == ErrorCode.IN_USE) {
            SettingsCard("职位仍被使用") {
                Text("请先调整引用此职位的联系人，或编辑职位并将其停用。")
                val referenced = data.options.firstOrNull { it.id == lastDeletedId }
                if (referenced != null) TextButton({ edit(referenced) }, enabled = !mutation.busy) { Text("编辑被引用职位") }
                TextButton(onRetry, enabled = !mutation.busy) { Text("刷新职位状态") }
            }
        }
        BrandButton({ edit(null) }, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("添加职位") }
        if (data.options.isEmpty()) EmptyContent("暂无职位，请添加职位后选择我的职位")
        data.options.forEach { option ->
            SettingsCard {
                Text(option.name, style = MaterialTheme.typography.titleMedium)
                Text("级别 ${option.level} · " + if (option.active) "已启用" else "已停用")
                if (option.id == data.myPositionId) Text("我的职位", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ edit(option) }, enabled = !mutation.busy) { Text("编辑") }
                    TextButton({ lastDeletedId = option.id; onDelete(option.id) }, enabled = !mutation.busy) { Text("删除") }
                }
                if (option.id != data.myPositionId && option.active) OutlinedButton({ onMine(option.id) }, enabled = !mutation.busy) { Text("设为我的职位") }
            }
        }
    }
    if (editorOpen) {
        val parsedLevel = level.toIntOrNull()
        AlertDialog(onDismissRequest = { if (!mutation.busy) editorOpen = false },
            title = { Text(if (editingId == null) "添加职位" else "编辑职位") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MutationFeedback(mutation)
                    OutlinedTextField(name, { name = it }, label = { Text("职位名称") }, singleLine = true,
                        enabled = !mutation.busy, isError = name.isBlank(), modifier = Modifier.fillMaxWidth())
                    InputError(if (name.isBlank()) "请输入职位名称" else null)
                    FieldError(mutation, "name")
                    OutlinedTextField(level, { level = it }, label = { Text("职位级别") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !mutation.busy,
                        isError = parsedLevel == null || parsedLevel <= 0, modifier = Modifier.fillMaxWidth())
                    InputError(if (parsedLevel == null || parsedLevel <= 0) "级别必须为正整数" else null)
                    FieldError(mutation, "level")
                    SettingRow("启用职位", trailing = { SettingsSwitch("启用职位", active, { active = it }, enabled = !mutation.busy) })
                    FieldError(mutation, "active", "id")
                }
            },
            confirmButton = {
                TextButton({ onSave(SavePosition(editingId, name.trim(), parsedLevel!!, active)) },
                    enabled = !mutation.busy && name.isNotBlank() && parsedLevel != null && parsedLevel > 0) { Text("保存") }
            }, dismissButton = { TextButton({ editorOpen = false }, enabled = !mutation.busy) { Text("取消") } })
    }
}
