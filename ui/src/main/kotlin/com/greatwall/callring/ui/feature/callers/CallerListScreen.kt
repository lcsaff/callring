package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.state.LoadState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager

@Composable fun CallerListScreen(
    state: LoadState<Page<CallerProfile>>, query: CallerSearch,
    onSearch: (String) -> Unit, onPage: (Int) -> Unit, onRetry: () -> Unit,
    onAdd: () -> Unit, onCaller: (String) -> Unit, onLookup: () -> Unit, onPositions: () -> Unit = {},
    onRecordings: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    ScreenFrame("号码档案", actions = {
        TextButton(onClick = onPositions) { Text("职位管理") }
        TextButton(onClick = onAdd) { Text("新增") }
    }) {
        OutlinedTextField(query.text, onSearch, Modifier.fillMaxWidth(), label = { Text("搜索姓名、部门或号码") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        TextButton(onClick = onLookup) { Text("按号码查看报表") }
        onRecordings?.let { open -> TextButton(onClick = open) { Text("通话录音") } }
        LoadFeedback(state, onRetry)
        val page = state.data
        if (page != null) {
            if (page.items.isEmpty() && state.loaded && !state.loading) EmptyContent("暂无匹配档案")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(page.items, key = { it.number }) { profile -> CallerCard(profile, onClick = { onCaller(profile.number) }) }
            }
            PageControls(page.pageIndex, page.pageSize, page.totalItems, state.loading, onPage)
        }
    }
}
