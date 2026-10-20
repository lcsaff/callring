package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.state.LoadState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager

@Composable fun CallerListScreen(
    state: LoadState<Page<CallerProfile>>, query: CallerSearch, check: NumberCheck?,
    onSearch: (String) -> Unit, onPage: (Int) -> Unit, onRetry: () -> Unit,
    onAdd: () -> Unit, onCaller: (String) -> Unit, onReport: (String) -> Unit,
    onPositions: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    ScreenFrame("号码档案", compactTitle = true, actions = {
        IconButton(onClick = onPositions) {
            Icon(Icons.Filled.List, contentDescription = "职位管理")
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "新增档案")
        }
    }) {
        OutlinedTextField(query.text, onSearch, Modifier.fillMaxWidth(), label = { Text("搜索姓名、部门或号码") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        val page = state.data
        val normalized = check?.takeIf { it.validProfileNumber }?.normalized
        // Any exact saved profile keeps this entry hidden; substring matches must not suppress it.
        val showReportAction = normalized != null && query.text.isNotBlank() && state.loaded && !state.loading &&
            page?.items?.none { it.number == normalized } == true
        if (showReportAction && normalized != null) {
            TextButton(onClick = { onReport(normalized) }) { Text("查看该号码的报表") }
            Text(
                "规范号码 $normalized · ${if (check.matchesRule) "符合规则号码" else "不符合规则号码"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LoadFeedback(state, onRetry)
        if (page != null) {
            if (page.items.isEmpty() && state.loaded && !state.loading) EmptyContent("暂无匹配档案")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(page.items, key = { it.number }) { profile -> CallerCard(profile, onClick = { onCaller(profile.number) }) }
            }
            PageControls(page.pageIndex, page.pageSize, page.totalItems, state.loading, onPage)
        }
    }
}
