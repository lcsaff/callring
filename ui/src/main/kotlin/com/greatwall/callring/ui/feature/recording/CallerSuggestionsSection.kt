package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

data class ArchiveSuggestionsState(
    val load: LoadState<CallerSuggestions?> = LoadState(),
    val mutation: MutationState = MutationState(),
    val conflictCount: Int = 0,
)

@Composable
internal fun CallerSuggestionsSection(
    state: ArchiveSuggestionsState,
    onRetry: () -> Unit,
    onConfirm: (CallerSuggestions, Set<CallerSuggestionField>) -> Unit,
    enabled: Boolean,
) {
    val selectable = enabled && !state.mutation.busy && !state.load.loading
    Text("核对写入档案的建议", style = MaterialTheme.typography.titleMedium)
    LoadFeedback(state.load, onRetry)
    RecordingFeedback(null, state.mutation)
    if (state.conflictCount > 0) Text("档案或分析结果已更新，请核对最新值后重新选择。")
    val suggestions = state.load.data
    if (suggestions == null || suggestions.fields.isEmpty()) {
        if (state.load.loaded && !state.load.loading) Text("暂无可确认的字段建议")
        return
    }
    Text("选择要写入的字段。未选择的字段保留当前值。")
    var selected by rememberSaveable(suggestions.token, state.conflictCount) { mutableStateOf(arrayListOf<String>()) }
    suggestions.fields.forEach { suggestion ->
        val field = suggestion.field
        val checked = field.name in selected
        Row(
            Modifier.fillMaxWidth().toggleable(checked, enabled = selectable, role = Role.Checkbox) {
                selected = ArrayList(if (it) selected + field.name else selected - field.name)
            }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = selectable)
            Column {
                Text(when (field) {
                    CallerSuggestionField.NAME -> "姓名"
                    CallerSuggestionField.DEPARTMENT -> "部门"
                    CallerSuggestionField.POSITION -> "自述职位"
                    CallerSuggestionField.REGION -> "所属地区"
                }, style = MaterialTheme.typography.titleSmall)
                Text("当前：${suggestion.currentValue.ifBlank { "空" }}")
                Text("建议：${suggestion.suggestedValue}")
                Text("原文：${suggestion.evidence}")
            }
        }
    }
    Button(
        onClick = { onConfirm(suggestions, selected.mapTo(mutableSetOf(), CallerSuggestionField::valueOf)) },
        enabled = selected.isNotEmpty() && selectable,
    ) { Text("确认写入档案") }
}
