package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.components.SettingRow
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.designsystem.components.ConfirmAction
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.*

@Composable
fun RingtoneScreen(
    state: LoadState<RingtoneSettings>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onEnabled: (Boolean) -> Unit,
    onImport: () -> Unit, onRestore: () -> Unit, onPreview: () -> Unit, onStop: () -> Unit,
) {
    SettingsLayout("铃声", state, mutation, onBack, onRetry) { data ->
        SettingsCard("来电铃声") {
            SwitchSettingRow("启用自定义铃声", data.enabled, onEnabled, "按来电规则播放所选铃声", enabled = !mutation.busy)
            FieldError(mutation, "enabled")
            Text(data.displayName.ifBlank { "未命名铃声" }, style = MaterialTheme.typography.titleMedium)
            Text("来源：" + when (data.source) { AudioSource.BUILT_IN -> "内置"; AudioSource.IMPORTED -> "导入音频" })
            Text("试听时长：${data.previewSeconds} 秒")
            BrandButton(onImport, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("导入音频") }
            FieldError(mutation, "source", "uri")
            ConfirmAction("恢复内置铃声", "恢复内置铃声？", "当前选择将替换为内置铃声。", onRestore,
                enabled = !mutation.busy && data.source != AudioSource.BUILT_IN, modifier = Modifier.fillMaxWidth())
        }
        SettingsCard("试听") {
            BrandButton(onPreview, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("试听铃声") }
            OutlinedButton(onStop, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("停止播放") }
        }
    }
}
