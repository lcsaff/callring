package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.state.*

@Composable fun RecordingSettingsScreen(state: LoadState<RecordingSettingsView>, mutation: MutationState,
    onBack: () -> Unit, onRefresh: () -> Unit, onEnabled: (Boolean) -> Unit, onCaptureMode: (CaptureMode) -> Unit,
    onFolder: () -> Unit, onMicrophone: () -> Unit) {
    ScreenFrame("录音设置", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordingFeedback(state.error, mutation)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.data == null && !state.loading) TextButton(onClick = onRefresh) { Text("加载设置") }
            state.data?.let { value ->
                SwitchSettingRow("启用录音归档", value.recordingEnabled, onEnabled, summary = "录音与来电统计独立开关")
                Text("系统录音须由设备提供或手动导入，不能保证自动取得完整双向通话录音。")
                Text("录音来源", style = MaterialTheme.typography.titleMedium)
                Row { RadioButton(value.captureMode == CaptureMode.SYSTEM_ONLY, { onCaptureMode(CaptureMode.SYSTEM_ONLY) }); Text("仅系统录音 / 导入文件") }
                Row { RadioButton(value.captureMode == CaptureMode.MICROPHONE_EXPERIMENTAL, onMicrophone); Text("实验性麦克风（有限、可能只有单方）") }
                Text("麦克风权限不代表设备能录制对方声音；受系统限制，可能无声或中断。")
                OutlinedButton(onClick = onFolder, enabled = !mutation.busy) { Text("授权系统录音目录") }
                Text(if (!value.folderConfigured) "尚未授权目录" else "已授权系统录音目录")
                Text("自动分析与转写连接请前往 AI 设置。")
            }
        }
    }
}
