package com.greatwall.callring.ui.feature.recording

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.greatwall.callring.ui.state.LoadState
import java.time.ZoneId

@Composable internal fun RecordingTimeZoneFeedback(zone: LoadState<ZoneId>, onRetry: () -> Unit) {
    if (zone.error != null) {
        Text("显示时区加载失败，请重试", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("重试显示时区") }
    } else if (zone.data == null) {
        Text("正在加载显示时区…")
    }
}
