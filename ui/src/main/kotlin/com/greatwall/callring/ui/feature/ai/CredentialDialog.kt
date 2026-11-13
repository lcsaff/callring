package com.greatwall.callring.ui.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import kotlinx.coroutines.launch

/** Plaintext exists only in this local, unsaved composition. */
data class CredentialDraft(val slot: AiSlot, val base: AiSettingsView, val connection: ConnectionPatch)

@Composable fun CredentialDialog(api: AiSettingsApi, slot: AiSlot, initial: CredentialDraft? = null, onFinished: (Boolean) -> Unit) {
    var base by remember { mutableStateOf<AiSettingsView?>(null) }
    var recording by remember { mutableStateOf<RecordingSettingsView?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf(0) }
    var key by remember { mutableStateOf("") }
    var clearConnection by remember { mutableStateOf(false) }
    var disableAutomatic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) {
        if(initial != null) base = initial.base
        else when(val result = api.getAiView()) { is ApiResult.Success -> base = result.value; is ApiResult.Failure -> error = result.error }
        when(val result = api.getRecordingView()) { is ApiResult.Success -> recording = result.value; is ApiResult.Failure -> error = result.error }
    }
    fun cancel() { if(!busy) { key = ""; onFinished(false) } }
    AlertDialog(onDismissRequest = ::cancel, title = { Text("本地编辑分析密钥") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("密钥不会加入对话、工具结果或保存的界面状态。留空保留与清除是不同操作。")
            base?.let { Text(if(it.analysis.keyConfigured) "当前已配置密钥" else "当前未配置密钥") }
            listOf("保留现有密钥", "替换密钥", "清除密钥").forEachIndexed { index, label -> Row(Modifier.selectable(choice == index, role = Role.RadioButton, onClick = { choice = index })) { RadioButton(choice == index, null); Text(label) } }
            if(choice == 1) OutlinedTextField(key, { key = it }, label = { Text("新密钥") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("credential-key"))
            if(choice == 2) {
                Row { Checkbox(clearConnection, { clearConnection = it }); Text("同时清空此连接的地址和模型") }
                Row { Checkbox(disableAutomatic, { disableAutomatic = it }); Text("关闭自动分析") }
                Text("清除后仍须通过完整配置校验；启用自动分析时必须保留有效的分析连接。")
            }
            error?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && base != null && recording != null && (choice != 1 || key.isNotBlank()), onClick = {
        val aiBase = base ?: return@TextButton; val recBase = recording ?: return@TextButton
        val secret = when(choice) { 1 -> LocalSecretChange.Replace(key); 2 -> LocalSecretChange.Clear; else -> LocalSecretChange.Keep }
        val connection = if(choice == 2 && clearConnection) ConnectionPatch(url = PatchField.Set(""), model = PatchField.Set("")) else initial?.connection ?: ConnectionPatch()
        val patch = AiSettingsPatch(analysis = connection)
        busy = true
        scope.launch {
            try {
                when(val result = api.patchCombined(recBase, RecordingSettingsPatch(automaticAnalysis = if(choice == 2 && disableAutomatic) PatchField.Set(false) else PatchField.Keep), aiBase, patch,
                    secret)) {
                    is ApiResult.Success -> { key = ""; onFinished(true) }
                    is ApiResult.Failure -> error = result.error
                }
            } finally { busy = false }
        }
    }) { Text("保存密钥") } }, dismissButton = { TextButton(onClick = ::cancel, enabled = !busy) { Text("取消") } })
}
