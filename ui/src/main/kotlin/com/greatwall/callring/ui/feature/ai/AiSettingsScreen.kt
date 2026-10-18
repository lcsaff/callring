package com.greatwall.callring.ui.feature.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.recording.recordingMessage
import com.greatwall.callring.ui.state.*

@Composable fun AiSettingsScreen(state: LoadState<AiSettingsView>, draft: AiSettingsView?, dirty: Boolean, mutation: MutationState, onBack: () -> Unit, onEdit: (AiSettingsView) -> Unit, onSave: () -> Unit, onCredential: (AiSlot) -> Unit, onCopy: (AiSlot,AiSlot) -> Unit, onTest: (AiSlot) -> Unit) {
    var discard by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<AiSlot?>(null) }
    val back = { if(dirty) discard = true else onBack() }
    BackHandler(onBack = back)
    ScreenFrame("AI 设置", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (mutation.error ?: state.error)?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
            mutation.message?.let { Text(it) }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(dirty && state.data?.revision != draft?.revision) Text("保存值已变化；保留了本页草稿。保存时会检查冲突。")
            draft?.let { value ->
                Text("沟通复用内容分析连接。发送沟通时会提供应用完整业务文字，可能产生服务费用。密钥只在本地编辑，AI 只能看到是否已配置。")
                SafeConnectionForm("内容分析 / 沟通", value.analysis, true, { onEdit(value.copy(analysis = it)) }, { onCredential(AiSlot.ANALYSIS) })
                Row { TextButton(onClick = { onCopy(AiSlot.TRANSCRIPTION,AiSlot.ANALYSIS) }, enabled = !mutation.busy) { Text("从转写配置复制") }; TextButton(onClick = { test = AiSlot.ANALYSIS }) { Text("测试分析连接") } }
                SafeConnectionForm("录音转写 · OpenAI 兼容", value.transcription, false, { onEdit(value.copy(transcription = it)) }, { onCredential(AiSlot.TRANSCRIPTION) })
                Row { TextButton(onClick = { onCopy(AiSlot.ANALYSIS,AiSlot.TRANSCRIPTION) }, enabled = !mutation.busy) { Text("从分析配置复制") }; TextButton(onClick = { test = AiSlot.TRANSCRIPTION }) { Text("测试转写连接") } }
                Text("复制会在本地预览后保存，并在本地复制密钥；复制至转写时保持 OpenAI 协议。界面测试使用当前草稿和本地保存的密钥，不发送通话内容；AI 工具只测试已保存连接。")
                Text("客户端上下文预算", style = MaterialTheme.typography.titleMedium)
                Text("默认 49152，可配置 8192–1048576；输出预留 1024–16384 且小于上下文。此为保守客户端限制，不保证模型支持。完整数据、对话和全部工具均计入，超限会停止。")
                var context by remember(value.budget.context) { mutableStateOf(value.budget.context.toString()) }
                var output by remember(value.budget.output) { mutableStateOf(value.budget.output.toString()) }
                OutlinedTextField(context, { context = it; it.toLongOrNull()?.let { count -> onEdit(value.copy(budget = value.budget.copy(context = count))) } }, label = { Text("上下文预算") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(output, { output = it; it.toLongOrNull()?.let { count -> onEdit(value.copy(budget = value.budget.copy(output = count))) } }, label = { Text("输出预留") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = onSave, enabled = dirty && !mutation.busy && context.toLongOrNull() != null && output.toLongOrNull() != null, modifier = Modifier.fillMaxWidth()) { Text("保存 AI 设置") }
            }
        }
    }
    if(discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, text = { Text("本页草稿尚未保存。") }, confirmButton = { TextButton(onClick = onBack) { Text("放弃并返回") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    test?.let { slot -> AlertDialog(onDismissRequest = { test = null }, title = { Text("发送连接测试？") }, text = { Text(if(slot == AiSlot.TRANSCRIPTION) "将发送固定测试音频到当前草稿中的转写地址，可能产生费用；不会发送通话录音。" else "将发送固定测试文字到当前草稿中的分析地址，可能产生费用；不会发送业务数据。") }, confirmButton = { TextButton(onClick = { test = null; onTest(slot) }) { Text("发送测试") } }, dismissButton = { TextButton(onClick = { test = null }) { Text("取消") } }) }
}

@Composable private fun SafeConnectionForm(title: String, value: AiConnectionView, protocols: Boolean, edit: (AiConnectionView) -> Unit, credential: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if(protocols) Row { AiProtocol.entries.forEach { protocol -> FilterChip(value.protocol == protocol, { edit(value.copy(protocol = protocol)) }, label = { Text(protocol.name) }, modifier = Modifier.padding(end = 8.dp)) } }
    OutlinedTextField(value.url, { edit(value.copy(url = it)) }, label = { Text("API 基础地址（含 /v1 或自定义前缀）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.model, { edit(value.copy(model = it)) }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(if(value.keyConfigured) "密钥已配置" else "尚未配置密钥")
    OutlinedButton(onClick = credential) { Text("本地编辑密钥") }
}
fun aiErrorText(error: ApiError): String = when(error.message) {
    "ai.context_budget_exceeded" -> "完整上下文超出客户端预算，已停止后续请求和操作。请调整 AI 设置预算或新建对话；若全集本身过大，首版暂不能处理。"
    "ai.provider_context_rejected" -> "服务端拒绝了完整上下文，已停止后续操作。请核对模型容量与设置。"
    "ai.snapshot_domain_failed" -> "无法完整读取业务数据，未使用残缺上下文。${error.fieldErrors["domains"]?.let { "失败范围：$it" }.orEmpty()}"
    "ai.snapshot_changed", "business.changed", "settings.conflict", "recording.changed", "draft.field_changed" -> "数据或设置已变化；保留了草稿和已提交结果，请核对最新值后重新编辑或发送。"
    "ai.connection_changed" -> "分析连接已变化，本轮已停止；下一次发送会使用新连接，已保存的修改保留。"
    "ai.cancelled", "action.cancelled" -> "操作已取消；此前已提交的修改仍保留。"
    "action.denied" -> "系统授权或本地确认被拒绝，依赖操作未执行。"
    "action.stale", "action.expired" -> "系统请求已失效或过期，旧结果不会执行，请重新发起。"
    "action.unavailable" -> "系统没有提供该操作入口。"
    "ai.invalid_tool_response", "ai.protocol_unsupported" -> "服务回复不支持完整工具调用，本批操作未执行。请检查协议与模型。"
    "ai.authentication_failed" -> "服务认证失败，请在本地检查密钥及地址。"
    "ai.rate_limited" -> "服务限流，请稍后手动重试。"
    "ai.unavailable" -> "当前环境未提供 AI 服务。"
    "ai.busy" -> "正在处理上一条消息。"
    "ai.turn_limit" -> "已达到本轮操作限制；已提交结果保留，请核对后继续。"
    else -> if(error.code == ErrorCode.PERMISSION_REQUIRED) "所需系统授权尚未获得，配置未保存。" else recordingMessage(error.message)
}
