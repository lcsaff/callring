package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.*

@Composable
fun RingtoneScreen(
    state: LoadState<RingtoneSettings>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onEnabled: (Boolean) -> Unit,
    onImport: () -> Unit, onSelect: (BuiltInRingtone) -> Unit, onPreview: () -> Unit, onStop: () -> Unit,
) {
    SettingsLayout("铃声", state, mutation, onBack, onRetry) { data ->
        SettingsCard("来电铃声") {
            SwitchSettingRow("启用自定义铃声", data.enabled, onEnabled, "按来电规则播放所选铃声", enabled = !mutation.busy)
            FieldError(mutation, "enabled")
            Text(if (data.source == AudioSource.BUILT_IN) data.builtInRingtone.label() else data.displayName.ifBlank { "未命名铃声" }, style = MaterialTheme.typography.titleMedium)
            Text("来源：" + when (data.source) { AudioSource.BUILT_IN -> "内置"; AudioSource.IMPORTED -> "导入音频" })
            BrandButton(onImport, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("导入音频") }
            FieldError(mutation, "source", "uri")
        }
        SettingsCard("内置铃声") {
            Column(Modifier.selectableGroup()) {
                BuiltInRingtone.entries.forEach { ringtone ->
                    val selected = data.source == AudioSource.BUILT_IN && data.builtInRingtone == ringtone
                    Row(Modifier.fillMaxWidth().selectable(selected, enabled = !mutation.busy,
                        role = Role.RadioButton, onClick = { onSelect(ringtone) }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = selected, onClick = null, enabled = !mutation.busy)
                        Text(ringtone.label())
                    }
                }
            }
        }
        SettingsCard("试听") {
            Text("持续循环播放，离开此页面或应用进入后台时自动停止。")
            BrandButton(onPreview, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("试听铃声") }
            OutlinedButton(onStop, modifier = Modifier.fillMaxWidth()) { Text("停止播放") }
        }
    }
}

private fun BuiltInRingtone.label(): String = when (this) {
    BuiltInRingtone.MELODY -> "原始旋律"
    BuiltInRingtone.CHIME -> "清脆铃音"
    BuiltInRingtone.GENTLE -> "柔和音符"
    BuiltInRingtone.CLASSIC -> "经典电话"
    BuiltInRingtone.DIGITAL -> "电子提示"
}
