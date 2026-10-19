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
    var advanced by remember { mutableStateOf(false) }
    val back = { if(dirty) discard = true else onBack() }
    BackHandler(onBack = back)
    ScreenFrame("AI 设置", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (mutation.error ?: state.error)?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
            mutation.message?.let { Text(it) }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(dirty && state.data?.revision != draft?.revision) Text("保存值已变化；保留了本页草稿。保存时会检查冲突。")
            draft?.let { value ->
                Text("沟通使用内容分析连接，按需发送本次任务所需的文字。密钥仅在本地编辑，可能产生服务费用。")
                SafeConnectionForm("内容分析 / 沟通", value.analysis, true, { onEdit(value.copy(analysis = it)) }, { onCredential(AiSlot.ANALYSIS) })
                val resolved=runCatching{resolveConversationProfile(AiConnection(value.analysis.protocol,value.analysis.url,model=value.analysis.model),value.compatibility)}
                Text("草稿解析方式：${resolved.getOrNull()?.name?:"配置需修正"}")
                state.data?.let{Text("已保存解析方式：${it.analysis.resolvedProfile}",style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={advanced=!advanced}){Text(if(advanced)"收起高级兼容"else"高级兼容")}
                if(advanced) {
                    Text("自动根据官方地址和模型选择；代理地址需要明确选择 Qwen FAST。")
                    ConversationCompatibility.entries.forEach{compatibility->FilterChip(value.compatibility==compatibility,{onEdit(value.copy(compatibility=compatibility))},label={Text(when(compatibility){ConversationCompatibility.AUTO->"自动（AUTO）";ConversationCompatibility.STANDARD->"标准（STANDARD）";ConversationCompatibility.QWEN_FAST->"Qwen FAST"})})}
                    if(resolved.isFailure)Text("当前模型或协议不支持所选兼容方式。",color=MaterialTheme.colorScheme.error)
                }
                Row { TextButton(onClick = { onCopy(AiSlot.TRANSCRIPTION,AiSlot.ANALYSIS) }, enabled = !mutation.busy) { Text("从转写配置复制") }; TextButton(onClick = { test = AiSlot.ANALYSIS }) { Text("测试分析连接") } }
                SafeConnectionForm("录音转写 · OpenAI 兼容", value.transcription, false, { onEdit(value.copy(transcription = it)) }, { onCredential(AiSlot.TRANSCRIPTION) })
                Row { TextButton(onClick = { onCopy(AiSlot.ANALYSIS,AiSlot.TRANSCRIPTION) }, enabled = !mutation.busy) { Text("从分析配置复制") }; TextButton(onClick = { test = AiSlot.TRANSCRIPTION }) { Text("测试转写连接") } }
                Text("复制会在本地预览后保存，并在本地复制密钥；复制至转写时保持 OpenAI 协议。界面测试使用当前草稿和本地保存的密钥，不发送通话内容；AI 工具只测试已保存连接。")
                Text("客户端上下文预算", style = MaterialTheme.typography.titleMedium)
                Text("默认 49152，可配置 8192–1048576；输出预留 1024–16384 且小于上下文。本次文字、按需数据和已启用工具计入预算；容量不足时会保留已提交结果并停止。")
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
    "ai.context_budget_exceeded" -> "本轮所需内容超出客户端预算，已停止后续操作。已提交结果保留；请缩小查询范围或新建对话。"
    "ai.provider_context_rejected" -> "服务端拒绝了本次上下文，已停止后续操作。已提交结果保留，请核对模型容量。"
    "ai.snapshot_domain_failed" -> "无法完整读取业务数据，未使用残缺上下文。${error.fieldErrors["domains"]?.let { "失败范围：$it" }.orEmpty()}"
    "ai.snapshot_changed", "business.changed", "settings.conflict", "recording.changed", "draft.field_changed" -> "数据或设置已变化；保留了草稿和已提交结果，请核对最新值后重新编辑或发送。"
    "ai.connection_changed" -> "分析连接已变化，本轮已停止；下一次发送会使用新连接，已保存的修改保留。"
    "ai.cancelled", "action.cancelled" -> "操作已取消；此前已提交的修改仍保留。"
    "action.denied" -> "系统授权或本地确认被拒绝，依赖操作未执行。"
    "action.stale", "action.expired" -> "系统请求已失效或过期，旧结果不会执行，请重新发起。"
    "action.unavailable" -> "系统没有提供该操作入口。"
    "action.target_not_found" -> "所选目标已不存在，操作未执行。请重新读取后选择。"
    "ai.invalid_tool_response", "ai.protocol_unsupported" -> "服务回复不支持完整工具调用，本批操作未执行。请检查协议与模型。"
    "ai.invalid_tool_arguments", "ai.argument_json", "ai.unknown_tool", "ai.tool_not_enabled" -> "本次步骤的参数或功能不可用，尚未执行；会在本轮限制内尝试修正。"
    "ai.readback_failed", "ai.readback_mismatch" -> "操作已提交，尚未通过读回核验；不会重复执行。"
    "ai.task_incomplete" -> "计划尚未全部核验，请查看已完成与未执行步骤。"
    "ai.task_reference_expired" -> "该任务引用已失效，请重新说明所需的未完成工作。"
    "ai.unknown_commit_requires_review" -> "存在提交结果未知的步骤，请先核对目标数据；不会重复执行。"
    "ai.timeout" -> "等待服务回复超时；已提交结果保留，未自动重试。"
    "ai.transport_failed" -> "未能完成服务连接；已提交结果保留，未自动重试。"
    "ai.http_rejected", "ai.service_failed" -> "服务未接受本次请求；已提交结果保留。"
    "ai.output_truncated", "ai.response_too_large" -> "服务回复不完整或超过容量，本批操作未执行。"
    "ai.profile_invalid" -> "QWEN_FAST 仅适用于 OPENAI 的 qwen3.8-flash。更换模型请将高级兼容改为 AUTO 或 STANDARD。"
    "ai.provider_mode_mismatch" -> "当前会话不支持该服务的思考轨迹回传。请使用非思考配置；QWEN_FAST 只支持 qwen3.8-flash。"
    "ai.authentication_failed" -> "服务认证失败，请在本地检查密钥及地址。"
    "ai.rate_limited" -> "服务限流，请稍后手动重试。"
    "ai.unavailable" -> "当前环境未提供 AI 服务。"
    "ai.busy" -> "正在处理上一条消息。"
    "ai.turn_limit" -> "已达到本轮操作限制；已提交结果保留，请核对后继续。"
    else -> if(error.code == ErrorCode.PERMISSION_REQUIRED) "所需系统授权尚未获得，配置未保存。" else recordingMessage(error.message)
}
