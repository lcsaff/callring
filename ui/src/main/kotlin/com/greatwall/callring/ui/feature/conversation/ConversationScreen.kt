package com.greatwall.callring.ui.feature.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
    fun continueTask(taskId:String,disclosed:AiConnectionView?) { disclosed?:return;scope.launch { when(val result=api.conversation.continueTask(state.value.sessionId,taskId,disclosed)){is ApiResult.Success->localError.value=null;is ApiResult.Failure->localError.value=result.error} } }
    fun stop() { scope.launch { api.conversation.stop(state.value.sessionId) } }
    fun newSession() { scope.launch { api.conversation.newSession(state.value.sessionId) } }
}
@Composable fun ConversationScreen(state:ConversationState,input:String,target:String,localError:ApiError?,onInput:(String)->Unit,onSend:()->Unit,onStop:()->Unit,onNew:()->Unit,onContinue:(String)->Unit={}) {
    var confirmNew by remember{mutableStateOf(false)}
    ScreenFrame("AI 沟通",actions={IconButton(onClick={confirmNew=true}){Icon(Icons.Filled.Add,contentDescription="新对话")}}) {
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("服务：${target.ifBlank{"请先配置 AI 设置"}}",style=MaterialTheme.typography.bodySmall)
            if(state.busy&&state.target.isNotBlank())Text("本轮已发送至：${state.target}",style=MaterialTheme.typography.bodySmall)
            Text(phaseLabel(state.phase),style=MaterialTheme.typography.labelLarge)
            (state.error?:localError)?.let{Text(aiErrorText(it),color=MaterialTheme.colorScheme.error)}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                itemsIndexed(state.messages){index,message->
                    if(message.text.isNotEmpty()&&message.toolCalls.isEmpty()&&message.role in setOf("user","assistant")) {
                        if(message.role=="user")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            Surface(Modifier.widthIn(max=560.dp).testTag("message-user-$index"),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onPrimaryContainer){Text(message.text,Modifier.padding(horizontal=14.dp,vertical=10.dp))}
                        }else Column(Modifier.fillMaxWidth().testTag("message-assistant-$index")){Markdown(message.text)}
                    }
                    message.receipts.distinctBy{it.operationId}.filter{it.status!=ToolStatus.READ||it.error!=null}.forEach{ReceiptCard(it)}
                }
                val paired=state.messages.flatMap{it.receipts}.map{it.operationId}.toSet()
                state.receipts.distinctBy{it.operationId}.filter{it.operationId !in paired&&(it.status!=ToolStatus.READ||it.error!=null)}.forEach{receipt->item{ReceiptCard(receipt)}}
            }
            if(state.busy)Row(verticalAlignment=Alignment.CenterVertically){LinearProgressIndicator(Modifier.weight(1f));TextButton(onClick=onStop){Text("停止")}}
            if(!state.busy)state.continuableTasks.firstOrNull()?.let{task->OutlinedButton(onClick={onContinue(task)},enabled=target.isNotBlank()){Text("继续未完成任务")}}
            val canSend=!state.busy&&input.isNotBlank()&&target.isNotBlank()
            Surface(Modifier.fillMaxWidth().testTag("conversation-input"),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxWidth().heightIn(min=112.dp)) {
                    BasicTextField(value=input,onValueChange=onInput,enabled=!state.busy,maxLines=4,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={if(canSend)onSend()}),modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=220.dp).padding(start=16.dp,end=16.dp,top=14.dp,bottom=52.dp),decorationBox={inner->if(input.isEmpty())Text("说说你想了解或处理什么",color=MaterialTheme.colorScheme.onSurfaceVariant);inner()})
                    IconButton(onClick=onSend,enabled=canSend,modifier=Modifier.align(Alignment.BottomEnd).padding(4.dp).size(48.dp).semantics{contentDescription="发送消息"}){Icon(Icons.AutoMirrored.Filled.Send,null,Modifier.size(18.dp).testTag("send-glyph"))}
                }
            }
        }
    }
    if(confirmNew)AlertDialog(onDismissRequest={confirmNew=false},title={Text("开始新对话？")},text={Text("将停止当前请求并清空本进程对话。已提交的修改仍保留；退出应用后不会自动恢复任务。")},confirmButton={TextButton(onClick={confirmNew=false;onNew()}){Text("开始新对话")}},dismissButton={TextButton(onClick={confirmNew=false}){Text("取消")}})
}
@Composable private fun ReceiptCard(receipt:ToolReceipt) {
    val label=when {
        receipt.verification==VerificationStatus.VERIFIED->if(receipt.noOp)"已是目标值"else"已核验"
        receipt.verification==VerificationStatus.COMMITTED_UNVERIFIED->"已提交，尚未核验；不会重复执行"
        receipt.status==ToolStatus.COMMITTED->"已提交，尚未核验"
        else->when(receipt.status){ToolStatus.READ->"已读取";ToolStatus.QUEUED->"已排队，尚未完成";ToolStatus.LAUNCHED->"已发起，等待实际结果";ToolStatus.CANCELLED->"已取消";ToolStatus.DENIED->"已拒绝";ToolStatus.FAILED->"失败";ToolStatus.UNKNOWN->"结果未知，不会自动重放";ToolStatus.SKIPPED->"未执行";else->"待核验"}
    }
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("操作结果：$label")
        receipt.error?.let{Text(aiErrorText(it),color=MaterialTheme.colorScheme.error)}
        val value=receipt.value as? AiJson.Obj
        listOf("number" to "号码","name" to "姓名","department" to "部门").forEach{(key,title)->(value?.get(key) as? AiJson.Str)?.value?.takeIf{it.isNotBlank()}?.let{Text("$title：$it",style=MaterialTheme.typography.bodyMedium)}}
    }}
}
internal fun phaseLabel(phase:ConversationPhase)=when(phase) {
    ConversationPhase.IDLE->"准备就绪"
    ConversationPhase.PREPARING,ConversationPhase.CAPTURING->"准备会话"
    ConversationPhase.DISCOVERING->"查找所需功能"
    ConversationPhase.READING->"读取所需数据"
    ConversationPhase.REPAIRING->"修正未执行步骤"
    ConversationPhase.BUDGETING->"检查本轮容量"
    ConversationPhase.REQUESTING->"等待 AI 回复"
    ConversationPhase.VALIDATING->"验证回复"
    ConversationPhase.EXECUTING->"执行并核验操作"
    ConversationPhase.WAITING_FOREGROUND->"等待系统界面或本地确认"
    ConversationPhase.COMPLETED->"本轮已完成"
    ConversationPhase.PARTIAL->"部分完成，请核对结果"
    ConversationPhase.STOPPING->"正在停止，保留已提交操作"
    ConversationPhase.STOPPED->"已停止"
    ConversationPhase.FAILED->"本轮未完成，请查看结果"
}