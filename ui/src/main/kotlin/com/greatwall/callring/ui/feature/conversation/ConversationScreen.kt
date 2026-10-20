package com.greatwall.callring.ui.feature.conversation

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackHost
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.ai.aiErrorText
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.mikepenz.markdown.m3.Markdown

@Composable fun ConversationScreen(state:ConversationState,input:String,target:String,localError:ApiError?,onInput:(String)->Unit,onSend:()->Unit,onStop:()->Unit,onNew:()->Unit,onContinue:(String)->Unit={},onOpenSettings:()->Unit={},connected:Boolean=true) {
    var confirmNew by remember{mutableStateOf(false)}
    val listState=rememberLazyListState()
    var readingHistory by remember(state.sessionId){mutableStateOf(false)}
    val paired=state.messages.flatMap{it.receipts}.map{it.operationId}.toSet()
    val additional=state.receipts.distinctBy{it.operationId}.filter{it.operationId !in paired&&(it.status!=ToolStatus.READ||it.error!=null)}
    val pendingAssistant = state.busy
    val latestIndex=state.messages.size+(if(additional.isNotEmpty())1 else 0)+(if(pendingAssistant)1 else 0)
    val finalIndex=state.messages.lastIndex.takeIf{it>=0&&state.messages[it].role=="assistant"&&state.messages[it].toolCalls.isEmpty()}
    val historyScroll=remember(state.sessionId) {object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {if(source==NestedScrollSource.UserInput&&available.y>0)readingHistory=true;return Offset.Zero}
    }}
    LaunchedEffect(listState,state.sessionId){snapshotFlow{!listState.canScrollForward&&!listState.isScrollInProgress}.distinctUntilChanged().filter{it}.collect{readingHistory=false}}
    // Markdown finishes parsing after first composition; follow subsequent measured size changes too.
    LaunchedEffect(listState,state.sessionId){
        snapshotFlow{val info=listState.layoutInfo;(if(readingHistory)-1 else info.totalItemsCount) to (listState.isScrollInProgress to info.visibleItemsInfo.map{it.index to (it.offset to it.size)})}
            .collect{(count,_)->if(count>0&&!readingHistory&&!listState.isScrollInProgress&&listState.canScrollForward)listState.scrollToItem(count-1)}
    }
    LaunchedEffect(state.sessionId,state.messages,state.receipts,state.phase,readingHistory){if(!readingHistory){withFrameNanos{};listState.scrollToItem(latestIndex)}}
    fun send(){readingHistory=false;onSend()}
    val ready = connected
    ScreenFrame("AI 沟通", compactTitle = true, actions = {
        IconButton(onClick={confirmNew=true}){Icon(Icons.Filled.Add,contentDescription="新对话")}
        Box {
            IconButton(onClick=onOpenSettings,modifier=Modifier.semantics{contentDescription="AI 设置"}){Icon(Icons.Filled.Settings,contentDescription=null)}
            // 连接校验通过即绿点、失败即红点，直到下次校验状态改变。
            Box(Modifier.align(Alignment.TopEnd).padding(top=10.dp,end=10.dp).size(9.dp).clip(CircleShape).background(if(ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                .testTag("ai-ready-dot").semantics{contentDescription=if(ready)"已连接" else "未连接"})
        }
    }) {
        // 反馈宿主由 ScreenFrame 提供（外层窗口可能没有）；在内容作用域内读取才能落到当前窗口的 Snackbar。
        val copy=rememberMessageCopier()
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            (state.error?:localError)?.let{Text(aiErrorText(it),color=MaterialTheme.colorScheme.error)}
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation-messages").nestedScroll(historyScroll),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                state.messages.forEachIndexed{index,message->
                    if(index==finalIndex&&additional.isNotEmpty())item(key="current-results"){ReceiptGroup(additional){readingHistory=true}}
                    item(key="message-$index") {
                    val body=when(message.presentationKey){"conversation.continue"->"继续未完成任务";"conversation.partial"->"任务尚未完成。请查看已核验、待核验及失败步骤；不会重复执行已提交操作。";else->message.text}
                    if(body.isNotEmpty()&&message.toolCalls.isEmpty()&&message.role in setOf("user","assistant")) {
                        if(message.role=="user")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            Surface(Modifier.widthIn(max=560.dp).testTag("message-user-$index").copyableMessage(body,copy),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onPrimaryContainer){Text(body,Modifier.padding(horizontal=14.dp,vertical=10.dp))}
                        }else Column(Modifier.fillMaxWidth().testTag("message-assistant-$index").copyableMessage(body,copy)){Markdown(body)}
                    }
                    ReceiptGroup(message.receipts.distinctBy{it.operationId}.filter{it.status!=ToolStatus.READ||it.error!=null}){readingHistory=true}
                    }
                }
                if(finalIndex==null&&additional.isNotEmpty())item(key="current-results"){ReceiptGroup(additional){readingHistory=true}}
                // 等待回复期间把状态放在 AI 回复的位置；真实回复到达后被消息替换。
                if(pendingAssistant)item(key="assistant-pending"){PendingAssistant(phaseLabel(state.phase))}
                item(key="latest"){Spacer(Modifier.height(1.dp))}
            }
            if(readingHistory&&listState.canScrollForward)IconButton(onClick={readingHistory=false},modifier=Modifier.align(Alignment.CenterHorizontally).testTag("jump-to-latest").semantics{contentDescription="回到最新"}){Icon(Icons.Filled.KeyboardArrowDown,null,Modifier.size(24.dp))}
            if(!state.busy)state.continuableTasks.firstOrNull()?.let{task->OutlinedButton(onClick={onContinue(task)},enabled=target.isNotBlank()){Text("继续未完成任务")}}
            val canSend=!state.busy&&input.isNotBlank()&&target.isNotBlank()
            Surface(Modifier.fillMaxWidth().testTag("conversation-input"),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxWidth().heightIn(min=112.dp)) {
                    BasicTextField(value=input,onValueChange=onInput,enabled=!state.busy,maxLines=4,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={if(canSend)send()}),modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=220.dp).padding(start=16.dp,end=16.dp,top=14.dp,bottom=52.dp),decorationBox={inner->if(input.isEmpty())Text("说说你想了解或处理什么",color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.55f));inner()})
                    // 发送与停止合并成右下角同一个按钮。
                    IconButton(
                        onClick = if(state.busy) onStop else ::send,
                        enabled = state.busy || canSend,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(48.dp)
                            .testTag("conversation-action")
                            .semantics{contentDescription=if(state.busy)"停止输出" else "发送消息"},
                    ) {
                        if(state.busy) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface).testTag("stop-glyph"))
                        else Icon(Icons.AutoMirrored.Filled.Send,null,Modifier.size(18.dp).testTag("send-glyph"))
                    }
                }
            }
        }
    }
    if(confirmNew)AlertDialog(onDismissRequest={confirmNew=false},title={Text("开始新对话？")},text={Text("将停止当前请求并清空本进程对话。已提交的修改仍保留；退出应用后不会自动恢复任务。")},confirmButton={TextButton(onClick={confirmNew=false;onNew()}){Text("开始新对话")}},dismissButton={TextButton(onClick={confirmNew=false}){Text("取消")}})
}
/** 长按气泡复制正文：写入剪贴板成功后震动并提示"已复制"；写入失败不提示。 */
@Composable private fun rememberMessageCopier(): (String)->Unit {
    val clipboard=LocalClipboard.current
    val haptics=LocalHapticFeedback.current
    val host=LocalFeedbackHost.current
    val scope=rememberCoroutineScope()
    return remember(clipboard,haptics,host,scope) { { text->
        scope.launch {
            val copied=runCatching{clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("消息",text)))}
            if(copied.isSuccess){haptics.performHapticFeedback(HapticFeedbackType.LongPress);host?.showSnackbar("已复制",withDismissAction=true)}
        }
    } }
}
/** 单击不改变气泡行为；长按复制正文，并把同一动作与正文合并成同一无障碍节点。 */
private fun Modifier.copyableMessage(text:String,copy:(String)->Unit)=this
    .pointerInput(text,copy){detectLongPressWithoutStealing{copy(text)}}
    .semantics(mergeDescendants=true){onLongClick(label="复制"){copy(text);true}}
/**
 * 只观察手势、不在长按成立前消费：链接和滚动等子级手势仍由子级处理；
 * 长按成立后消费抬手事件，避免同一次手势又把 Markdown 链接点开。
 */
private suspend fun PointerInputScope.detectLongPressWithoutStealing(onLongPress:()->Unit) {
    awaitEachGesture {
        val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
        val releasedEarly=withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var movedAway=false
            while(!movedAway) {
                val change=awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull{it.id==down.id}
                movedAway=change==null||!change.pressed||(change.position-down.position).getDistance()>viewConfiguration.touchSlop
            }
            false
        }
        if(releasedEarly!=null)return@awaitEachGesture
        onLongPress()
        while(true) {
            val change=awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull{it.id==down.id}?:break
            if(!change.pressed){change.consume();break}
        }
    }
}
@Composable private fun PendingAssistant(label:String) {
    Row(Modifier.fillMaxWidth().testTag("assistant-pending"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
        Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ReceiptGroup(receipts:List<ToolReceipt>,onExpand:()->Unit={}) {
    val verified=receipts.filter{it.verification==VerificationStatus.VERIFIED&&it.error==null}
    var expanded by remember(verified.map{it.operationId}){mutableStateOf(false)}
    if(verified.isNotEmpty()){
        TextButton(onClick={if(!expanded)onExpand();expanded=!expanded},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.heightIn(min=32.dp)){Text(if(expanded)"收起已核验操作"else"查看 ${verified.size} 项已核验操作",style=MaterialTheme.typography.labelLarge)}
        if(expanded)verified.forEach{ReceiptCard(it)}
    }
    receipts.filter{it !in verified}.forEach{ReceiptCard(it)}
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
