package com.greatwall.callring.ui.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.feature.recording.recordingMessage
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

/**
 * AI settings are applied field by field. The page has three groups and no page-level save action;
 * key editing and connection tests keep their one-shot confirmations.
 */
@Composable
fun AiSettingsScreen(
    state: LoadState<AiSettingsUi>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit,
    onAnalysisChange: (AiConnectionView) -> Unit, onAnalysisProtocol: (AiProtocol) -> Unit,
    onTranscriptionChange: (AiConnectionView) -> Unit,
    onThinkingMode: (ThinkingMode) -> Unit, onBudget: (ConversationBudget) -> Unit,
    onAutomaticAnalysis: (Boolean) -> Unit,
    onCredential: (AiSlot) -> Unit, onCopy: (AiSlot, AiSlot) -> Unit, onTest: (AiSlot) -> Unit,
) {
    var test by remember { mutableStateOf<AiSlot?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    ScreenFrame("AI 设置", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (mutation.error ?: state.error)?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
            mutation.message?.let { Text(it) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.data == null) {
                if (!state.loading && state.error == null) TextButton(onClick = onRetry) { Text("加载设置") }
            }
            state.data?.let { data ->
                AiGroup("内容分析与沟通") {
                    Text("沟通使用内容分析连接，按需发送本次任务所需的文字。密钥仅在本地编辑，可能产生服务费用。")
                    ConnectionFields(data.ai.analysis, true, onAnalysisProtocol, onAnalysisChange) {
                        onCredential(AiSlot.ANALYSIS)
                    }
                    ThinkingModeRow(data.ai.analysis, onThinkingMode)
                    Row {
                        TextButton(onClick = { onCopy(AiSlot.TRANSCRIPTION, AiSlot.ANALYSIS) }, enabled = !mutation.busy) {
                            Text("从转写配置复制")
                        }
                        TextButton(onClick = { test = AiSlot.ANALYSIS }) { Text("测试分析连接") }
                    }
                }
                AiGroup("录音转写") {
                    ConnectionFields(data.ai.transcription, false, {}, onTranscriptionChange) {
                        onCredential(AiSlot.TRANSCRIPTION)
                    }
                    Row {
                        TextButton(onClick = { onCopy(AiSlot.ANALYSIS, AiSlot.TRANSCRIPTION) }, enabled = !mutation.busy) {
                            Text("从分析配置复制")
                        }
                        TextButton(onClick = { test = AiSlot.TRANSCRIPTION }) { Text("测试转写连接") }
                    }
                    Text("复制会在本地预览并保存，并在本地复制密钥；复制至转写时保持 OpenAI 协议。")
                    SwitchSettingRow("录音就绪后自动分析", data.recording.automaticAnalysis, onAutomaticAnalysis,
                        "自动分析会将录音发送到转写服务，将转写文字发送到分析服务，可能按服务商价格收费。")
                }
                AdvancedSettings(data.ai.budget, mutation, advanced, { advanced = it }, onBudget)
            }
        }
    }
    test?.let { slot -> AlertDialog(onDismissRequest = { test = null }, title = { Text("发送连接测试？") },
        text = { Text(if (slot == AiSlot.TRANSCRIPTION) "将发送固定测试音频到当前保存的转写地址，可能产生费用；不会发送通话录音。"
            else "将发送固定测试文字到当前保存的分析地址，可能产生费用；不会发送业务数据。") },
        confirmButton = { TextButton(onClick = { test = null; onTest(slot) }) { Text("发送测试") } },
        dismissButton = { TextButton(onClick = { test = null }) { Text("取消") } }) }
}

@Composable
private fun AiGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
        HorizontalDivider()
    }
}

@Composable
private fun ConnectionFields(
    value: AiConnectionView, showProtocols: Boolean,
    onProtocol: (AiProtocol) -> Unit, onEdit: (AiConnectionView) -> Unit, onCredential: () -> Unit,
) {
    // Local copy so typing the address and then the model in one debounce window never loses the first edit.
    var local by remember(value) { mutableStateOf(value) }
    if (showProtocols) {
        Row {
            AiProtocol.entries.forEach { protocol ->
                FilterChip(local.protocol == protocol, { local = local.copy(protocol = protocol); onProtocol(protocol) },
                    label = { Text(protocol.name) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
    OutlinedTextField(local.url, { local = local.copy(url = it); onEdit(local) },
        label = { Text("API 基础地址（含 /v1 或自定义前缀）") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(local.model, { local = local.copy(model = it); onEdit(local) }, label = { Text("模型名称") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(if (local.keyConfigured) "密钥已配置" else "尚未配置密钥")
    OutlinedButton(onClick = onCredential) { Text("本地编辑密钥") }
}

@Composable
private fun ThinkingModeRow(connection: AiConnectionView, onChange: (ThinkingMode) -> Unit) {
    val supported = connection.thinkingSupport == ThinkingSupport.AVAILABLE ||
        connection.thinkingSupport == ThinkingSupport.CUSTOM_GATEWAY
    SwitchSettingRow(
        "思考模式", connection.thinkingMode == ThinkingMode.ENABLED,
        { checked -> onChange(if (checked) ThinkingMode.ENABLED else ThinkingMode.DISABLED) },
        summary = thinkingReason(connection), enabled = supported,
    )
}

internal fun thinkingReason(connection: AiConnectionView): String = when (connection.thinkingSupport) {
    ThinkingSupport.AVAILABLE -> "当前模型支持切换思考模式。"
    ThinkingSupport.CUSTOM_GATEWAY -> "自定义网关可能不执行思考参数。"
    ThinkingSupport.UNSUPPORTED -> thinkingNoticeText(connection.thinkingNotice, "当前模型不支持切换思考模式。")
    ThinkingSupport.UNRECOGNIZED -> thinkingNoticeText(connection.thinkingNotice, "当前模型未识别，不能切换思考模式。")
}

private fun thinkingNoticeText(notice: String, fallback: String): String = when (notice) {
    "", "thinking.available" -> fallback
    "thinking.unsupported.protocol" -> "当前协议不支持切换思考模式。"
    "thinking.model_not_verified" -> "当前模型不支持切换思考模式。"
    "thinking.unrecognized" -> "当前模型未识别，不能切换思考模式。"
    else -> if (notice.startsWith("thinking.")) fallback else notice
}

@Composable
private fun AdvancedSettings(
    budget: ConversationBudget, mutation: MutationState,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onBudget: (ConversationBudget) -> Unit,
) {
    var contextRaw by rememberSaveable(budget.context) { mutableStateOf(budget.context.toString()) }
    var outputRaw by rememberSaveable(budget.output) { mutableStateOf(budget.output.toString()) }
    val contextValue = contextRaw.toLongOrNull()
    val outputValue = outputRaw.toLongOrNull()
    val contextValid = contextValue != null && contextValue in 8192L..1048576L
    val outputValid = outputValue != null && outputValue in 1024L..16384L
    val relationValid = contextValue != null && outputValue != null && outputValue < contextValue
    val invalidCount = listOf(contextValid, outputValid, relationValid).count { !it }
    fun submitIfValid(nextContext: String = contextRaw, nextOutput: String = outputRaw) {
        val context = nextContext.toLongOrNull()
        val output = nextOutput.toLongOrNull()
        if (context != null && context in 8192L..1048576L &&
            output != null && output in 1024L..16384L && output < context) {
            onBudget(ConversationBudget(context, output))
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 8.dp)
            .testTag("ai-advanced-toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("高级设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (invalidCount > 0) Text("$invalidCount 项配置需要修正", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Text("预算只限制客户端发送的上下文；非法值不会提交。")
            OutlinedTextField(contextRaw, { contextRaw = it; submitIfValid(nextContext = it) },
                label = { Text("上下文预算") },
                singleLine = true, isError = !contextValid, modifier = Modifier.fillMaxWidth().testTag("ai-budget-context"))
            Text("8192–1048576", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(outputRaw, { outputRaw = it; submitIfValid(nextOutput = it) }, label = { Text("输出预留") },
                singleLine = true, isError = !outputValid, modifier = Modifier.fillMaxWidth().testTag("ai-budget-output"))
            Text("1024–16384，且小于上下文预算", style = MaterialTheme.typography.bodySmall)
            if (contextValid && outputValid && !relationValid) Text("输出预留必须小于上下文预算",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            mutation.error?.fieldErrors.orEmpty().filterKeys { it == "budget" || it.startsWith("budget.") }
                .values.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

fun aiErrorText(error: ApiError): String = when (error.message) {
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
    "update.unavailable" -> "当前环境未提供应用更新服务。"
    else -> if (error.code == ErrorCode.PERMISSION_REQUIRED) "所需系统授权尚未获得，配置未保存。" else recordingMessage(error.message)
}
