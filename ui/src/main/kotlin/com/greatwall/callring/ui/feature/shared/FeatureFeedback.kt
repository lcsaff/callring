package com.greatwall.callring.ui.feature.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*

@Composable fun <T> LoadFeedback(state: LoadState<T>, onRetry: () -> Unit) {
    if (state.loading && state.data == null) LoadingContent()
    state.error?.let { ErrorContent(it.message, onRetry) }
}

@Composable fun ApiErrorNotice(error: ApiError, onCapability: ((Capability) -> Unit)? = null) {
    Text(error.message, color = MaterialTheme.colorScheme.error)
    if (onCapability != null) error.requiredCapabilities.forEach { capability ->
        TextButton(onClick = { onCapability(capability) }) { Text("设置${UiLabels.capability(capability)}") }
    }
}

@Composable fun MutationFeedback(
    state: MutationState, onCapability: ((Capability) -> Unit)? = null,
    successMessage: String = "已保存", showSuccess: Boolean = true,
) {
    state.error?.let { ApiErrorNotice(it, onCapability) }
    MutationSuccessEffect(state.completed, state.busy, state.message?.takeUnless { it == "操作成功" } ?: successMessage, showSuccess)
}

@Composable fun PageControls(pageIndex: Int, pageSize: Int, totalItems: Long, loading: Boolean, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onPage(pageIndex - 1) }, enabled = !loading && pageIndex > 0) { Text("上一页") }
        Text("第 ${pageIndex + 1} 页 · 共 $totalItems 条", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onPage(pageIndex + 1) }, enabled = !loading && (pageIndex.toLong() + 1) * pageSize < totalItems) { Text("下一页") }
    }
}
