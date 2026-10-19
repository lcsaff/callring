package com.greatwall.callring.ui.feature.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.ai.aiErrorText
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.mikepenz.markdown.m3.Markdown

class ConversationViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    val state = api.conversation.state
    val input = MutableStateFlow("")
    val localError = MutableStateFlow<ApiError?>(null)
    val destination = MutableStateFlow<AiConnectionView?>(null)
    init { scope.launch { api.changes.observe().collect { when(val result = api.aiSettings.getAiView()) {
        is ApiResult.Success -> destination.value = result.value.analysis
        is ApiResult.Failure -> { destination.value = null; localError.value = result.error }
    } } } }
    fun send(disclosed: AiConnectionView?) { val text = input.value; disclosed ?: return; scope.launch { when(val result = api.conversation.sendTo(state.value.sessionId,text,disclosed)) { is ApiResult.Success -> { input.value = ""; localError.value = null }; is ApiResult.Failure -> localError.value = result.error } } }
    fun stop() { scope.launch { api.conversation.stop(state.value.sessionId) } }
    fun newSession() { scope.launch { api.conversation.newSession(state.value.sessionId) } }
}
@Composable fun ConversationScreen(state: ConversationState, input: String, target: String, localError: ApiError?, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onNew: () -> Unit) {
    var confirmNew by remember { mutableStateOf(false) }
    ScreenFrame("AI 沟通", actions = { TextButton(onClick = { confirmNew = true }) { Text("新对话") } }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发送会将应用完整业务文字、配置和运行状态交给已配置的分析服务；沟通不发送音频，密钥仅提供配置状态。可能产生服务费用。")
            Text("服务：${target.ifBlank { "请先配置 AI 设置" }}")
            if(state.busy && state.target.isNotBlank()) Text("本轮已发送至：${state.target}")
            Text(phaseLabel(state.phase), style = MaterialTheme.typography.labelLarge)
            (state.error ?: localError)?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.messages) { _, message ->
                    if(message.text.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(if(message.role == "user") "你" else if(message.toolCalls.isNotEmpty()) "AI 操作建议（未经验证，以操作回执为准）" else "AI", style = MaterialTheme.typography.labelMedium); if(message.role == "assistant") Markdown(message.text) else Text(message.text) } }
                    message.receipts.forEach { ReceiptCard(it) }
                }
                val pairedIds = state.messages.flatMap { it.receipts }.map { it.operationId }.toSet()
                state.receipts.filterNot { it.operationId in pairedIds }.forEach { item { ReceiptCard(it) } }
            }
            if(state.busy) Row { LinearProgressIndicator(Modifier.weight(1f)); TextButton(onClick = onStop) { Text("停止") } }
            OutlinedTextField(input, onInput, enabled = !state.busy, label = { Text("说说你想了解或处理什么") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            Button(onClick = onSend, enabled = !state.busy && input.isNotBlank() && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("发送") }
        }
    }
    if(confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("开始新对话？") }, text = { Text("将停止当前请求并清空本进程对话。已提交 ${state.messages.flatMap { it.receipts }.plus(state.receipts).distinctBy { it.operationId }.count { it.status == ToolStatus.COMMITTED }} 项操作会保留，不会撤销。进程退出后也不会保留聊天。") }, confirmButton = { TextButton(onClick = { confirmNew = false; onNew() }) { Text("开始新对话") } }, dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("取消") } })
}
@Composable private fun ReceiptCard(receipt: ToolReceipt) {
    var details by remember(receipt.operationId) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("操作结果：${when(receipt.status) { ToolStatus.READ -> "已读取"; ToolStatus.COMMITTED -> "已保存 / 已执行"; ToolStatus.QUEUED -> "已排队，尚未完成"; ToolStatus.LAUNCHED -> "已发起，后续状态以系统为准"; ToolStatus.CANCELLED -> "已取消"; ToolStatus.DENIED -> "已拒绝"; ToolStatus.FAILED -> "失败"; ToolStatus.UNKNOWN -> "结果未知，不会自动重放"; ToolStatus.SKIPPED -> "未执行" }}")
        receipt.error?.let { Text(aiErrorText(it), color = MaterialTheme.colorScheme.error) }
        if(receipt.value != AiJson.Null) { TextButton(onClick = { details = !details }) { Text(if(details) "收起结果" else "查看结果") }; if(details) Text(receipt.value.encode()) }
    } }
}
private fun phaseLabel(phase: ConversationPhase): String = when(phase) {
    ConversationPhase.PREPARING -> "准备会话"; ConversationPhase.DISCOVERING -> "查找所需功能"; ConversationPhase.READING -> "读取所需数据"; ConversationPhase.REPAIRING -> "修正未执行步骤"; ConversationPhase.PARTIAL -> "部分完成，请核对结果"; ConversationPhase.IDLE -> "准备就绪"; ConversationPhase.CAPTURING -> "完整读取数据中"; ConversationPhase.BUDGETING -> "检查完整上下文容量"; ConversationPhase.REQUESTING -> "等待 AI 回复"; ConversationPhase.VALIDATING -> "验证完整回复"; ConversationPhase.EXECUTING -> "执行并同步业务操作"; ConversationPhase.WAITING_FOREGROUND -> "等待前台系统界面或本地确认"; ConversationPhase.COMPLETED -> "本轮已结束，请核对实际操作结果"; ConversationPhase.STOPPING -> "正在停止，保留已提交操作"; ConversationPhase.STOPPED -> "已停止"; ConversationPhase.FAILED -> "本轮未完成，请查看错误和已提交结果"
}
