package com.greatwall.callring.ui.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.ui.designsystem.components.*

internal fun backupError(key: String): String = when (key) {
    "backup.password_required" -> "请填写备份口令。口令不会保存在应用中。"
    "backup.password_too_short" -> "备份口令至少需要 8 个字符，建议使用较长且不易猜测的口令。"
    "backup.phone_state_required" -> "请先在“运行状态与权限”授予电话状态权限，以确认操作时没有通话。"
    "backup.insufficient_space" -> "可用空间不足，请先释放空间后重试。应用不会自动删除已有数据。"
    "backup.busy", "backup.call_active" -> "当前有通话、录音或备份操作，请结束后重试。"
    "backup.changed", "backup.owner_changed", "backup.preview_expired" -> "数据或备份预览已变化，请重新选择文件并校验。"
    else -> "备份操作未完成。请检查口令、文件完整性、可用空间及文档访问权限后重试。"
}

@Composable
fun BackupScreen(state: BackupUiState, progress: BackupProgress, password: String, includeAudio: Boolean,
    onBack: () -> Unit, onPassword: (String) -> Unit, onAudio: (Boolean) -> Unit,
    onExport: () -> Unit, onPreview: () -> Unit, onRestore: () -> Unit, onCancel: () -> Unit) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    val restoring = state.busy && progress.phase == BackupPhase.RESTORING
    ScreenFrame("备份与恢复", if (restoring) ({}) else onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("备份包含档案、配置与历史、统计事实、录音转写与分析。可选择包含应用自有录音；自定义铃声一并备份。")
            Text("不包含 API 密钥、系统权限、录音目录授权、桌面组件和聊天。请妥善保存口令；忘记后无法解密。")
            OutlinedTextField(password, onPassword, enabled = !state.busy, label = { Text("备份口令（至少 8 个字符）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchSettingRow("包含自有录音音频", includeAudio, onAudio, "关闭时仍备份转写和分析；未备份音频恢复后需重新导入。", enabled = !state.busy)
            BrandButton(onExport, enabled = !state.busy && password.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("选择位置并导出备份") }
            OutlinedButton(onPreview, enabled = !state.busy && password.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("选择备份并校验") }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when (progress.phase) {
                    BackupPhase.RESTORING -> "正在恢复，完成后重新打开主界面…"
                    BackupPhase.VALIDATING -> "正在解密并校验备份…"
                    BackupPhase.PREPARING -> "正在准备一致的数据副本…"
                    else -> "正在准备并导出加密备份…"
                })
                if (!restoring) TextButton(onCancel) { Text("取消当前操作") }
            }
            state.error?.let { Text(backupError(it.message), color = MaterialTheme.colorScheme.error) }
            state.exported?.let { summary ->
                Text("备份已导出", style = MaterialTheme.typography.titleMedium)
                BackupCounts(summary)
            }
            state.preview?.let { preview ->
                Text("已校验的恢复预览", style = MaterialTheme.typography.titleMedium)
                BackupCounts(preview.summary)
                Text("恢复会替换当前应用数据，完成后重新打开主界面。自动分析将关闭，需要重新配置密钥、目录权限和桌面组件。", color = MaterialTheme.colorScheme.error)
                Text("建议先导出当前数据。恢复不修改系统原录音或系统通话记录。")
                BrandButton({ confirmation = preview.token }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("恢复此备份") }
            }
            if (state.restored) Text("恢复已提交，正在重新打开主界面。")
        }
    }
    val token = confirmation
    if (token != null && token == state.preview?.token) ConfirmationDialog("替换当前应用数据？",
        "将使用已校验备份替换当前档案、配置、历史及录音归档。完成后重新打开主界面；密钥、权限、目录授权和桌面组件需重新配置。此操作不能撤销。",
        "确认恢复", { confirmation = null; onRestore() }, { confirmation = null }, busy = state.busy)
}

@Composable private fun BackupCounts(summary: BackupSummary) {
    Text("档案 ${summary.callers} · 通话 ${summary.calls} · 录音归档 ${summary.archives}")
    Text("音频 ${summary.audioFiles} 个 · ${summary.audioBytes} 字节")
    Text(if (summary.includesAudio) "包含自有录音音频" else "未包含自有录音音频")
    Text(if (summary.includesCustomRingtone) "包含自定义铃声" else "无自定义铃声")
}
