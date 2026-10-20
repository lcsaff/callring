package com.greatwall.callring.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.callers.CallerEditorForm
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.*

data class OverlayContentState(
    val session: OverlaySessionState,
    val overview: LoadState<CallerOverview>,
    val editor: LoadState<CallerEditorData> = LoadState(),
    val draft: CallerDraft? = null,
    val mutation: MutationState = MutationState(),
    val editorMutation: MutationState = MutationState(),
    val savedRevision: Long = 0L,
)

@Composable fun OverlayScreen(
    state: OverlayContentState, onDrag: (Offset) -> Unit, onClose: () -> Unit, onCollapse: () -> Unit,
    onEdit: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onChange: (CallerDraft) -> Unit,
    onWhitelist: (Boolean) -> Unit, onReport: () -> Unit, onRetry: () -> Unit, onRetryEditor: () -> Unit,
    maxHeight: Dp, modifier: Modifier = Modifier
) {
    val session = state.session
    val overview = state.overview.data
    var pendingDismiss by rememberSaveable { mutableStateOf<String?>(null) }
    val dirty = session.editing && state.draft != null && state.editor.data?.draft != state.draft
    val commitBusy = state.editorMutation.busy || state.mutation.busy
    fun dismiss(kind: String) {
        if (!commitBusy) {
            if (dirty) pendingDismiss = kind else if (kind == "close") onClose() else onCancel()
        }
    }
    val dragStep = with(LocalDensity.current) { 24.dp.toPx() }
    FeedbackPanel {
    MutationSuccessEffect(state.savedRevision, false, "档案已保存")
    Surface(modifier.fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 12.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp, 48.dp).pointerInput(onDrag) {
                    detectDragGestures { change, amount -> change.consume(); onDrag(amount) }
                }.semantics {
                    contentDescription = "拖动来电卡片"
                    customActions = listOf(
                        CustomAccessibilityAction("向上移动") { onDrag(Offset(0f, -dragStep)); true },
                        CustomAccessibilityAction("向下移动") { onDrag(Offset(0f, dragStep)); true },
                        CustomAccessibilityAction("向左移动") { onDrag(Offset(-dragStep, 0f)); true },
                        CustomAccessibilityAction("向右移动") { onDrag(Offset(dragStep, 0f)); true })
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(20.dp, 4.dp).background(MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small))
                }
                Text(when { session.testPreview -> "悬浮窗测试"; session.preview -> "最近来电档案"; else -> "来电档案" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!session.editing) TextButton(onClick = onCollapse) { Text(if (session.collapsed) "展开" else "收起") }
                TextButton(onClick = { dismiss("close") }, enabled = !commitBusy) { Text("关闭") }
            }
            if (session.hasQueuedCall) Text("有新的来电，完成编辑后显示", style = MaterialTheme.typography.bodySmall)
            if (session.callEnded && session.editing) Text("通话已结束，保存或取消后关闭卡片", style = MaterialTheme.typography.bodySmall)
            if (session.testPreview && !session.collapsed) {
                Text("仅检查悬浮窗显示，不会拨打电话、播放铃声或保存示例档案。", style = MaterialTheme.typography.bodyMedium)
                Text("可拖动卡片、收起或关闭。", style = MaterialTheme.typography.bodySmall)
            } else if (session.editing) {
                CallerEditorForm(state.editor, state.draft, state.editorMutation, false, onRetryEditor, onChange, onSave,
                    Modifier.weight(1f, fill = false), showSuccess = false)
                TextButton(onClick = { dismiss("cancel") }, enabled = !state.editorMutation.busy) { Text("取消编辑") }
            } else if (session.collapsed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session.testPreview) "悬浮窗显示正常" else overview?.profile?.name?.ifBlank { session.request.number } ?: session.request.number,
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (overview != null && !session.testPreview) ScoreBadge(overview.rollingScore.points)
                }
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadFeedback(state.overview, onRetry)
                    if (overview == null) Text(session.request.number, style = MaterialTheme.typography.titleLarge)
                    else {
                        CallerCard(overview.profile)
                        ScoreBadge(overview.rollingScore.points)
                        Text(UiFormat.scoreReason(overview.rollingScore), style = MaterialTheme.typography.bodySmall)
                        Text("累计来电 ${overview.lifetimeCallCount} 次", style = MaterialTheme.typography.bodySmall)
                        Text(UiFormat.workReason(overview.work.classification.reason))
                    }
                    Text(UiFormat.decision(session.request.decision), color = MaterialTheme.colorScheme.primary)
                    MutationFeedback(state.mutation, successMessage = "白名单已更新")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onEdit) { Text("编辑") }
                    if (overview != null) TextButton(onClick = { onWhitelist(!overview.profile.whitelisted) }, enabled = !state.mutation.busy) {
                        Text(if (overview.profile.whitelisted) "移出白名单" else "加入白名单")
                    }
                    TextButton(onClick = onReport) { Text("报表") }
                }
            }
        }
    }
    }
    pendingDismiss?.let { kind ->
        OverlayConfirmationDialog("放弃未保存的修改？", "本次档案修改不会保存。", "放弃修改",
            onConfirm = { pendingDismiss = null; if (kind == "close") onClose() else onCancel() },
            onDismiss = { pendingDismiss = null }, busy = commitBusy)
    }
}
