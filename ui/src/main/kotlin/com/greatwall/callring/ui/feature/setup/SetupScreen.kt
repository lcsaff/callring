package com.greatwall.callring.ui.feature.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.recording.CaptureMode
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.settings.components.SettingsCard
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.LoadState
import java.time.ZoneId

@Composable
fun SetupScreen(
    state: LoadState<SetupUiData>, onBack: () -> Unit, onRetry: () -> Unit,
    onCapability: (Capability) -> Unit, onAppSettings: () -> Unit,
    onRecordingSettings: () -> Unit, onRuntime: () -> Unit,
) {
    ScreenFrame("配置清单", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按需检查，随时可以跳过；仅点击相应操作才会请求权限或打开设置。")
            TextButton(onBack) { Text("暂时跳过") }
            LoadFeedback(state, onRetry)
            state.data?.let { data ->
                val status = data.status
                SettingsCard("核心来电条件") {
                    Text(when (status.coreReadiness) {
                        CoreReadiness.READY -> "核心条件已具备"
                        CoreReadiness.MISSING -> "核心来电条件尚未就绪"
                        CoreReadiness.UNAVAILABLE -> "系统不支持部分核心来电能力"
                        CoreReadiness.UNKNOWN -> "核心来电条件待确认"
                    })
                    SetupCapability(status, Capability.CALL_SCREENING_ROLE, "接收系统交付的来电筛选事件", onCapability)
                    SetupCapability(status, Capability.PHONE_STATE, "在接听或挂断后停止铃声", onCapability)
                    Text("以上是当前观察，不能保证真实来电一定正常。", style = MaterialTheme.typography.bodySmall)
                }
                SettingsCard("通知与识别覆盖") {
                    SetupCapability(status, Capability.NOTIFICATIONS, "按系统版本适用；显示服务通知", onCapability)
                    SetupCapability(status, Capability.CONTACTS, "识别已保存联系人", onCapability)
                    if (status.capabilities[Capability.CONTACTS] != AccessState.GRANTED) {
                        Text("未授予联系人权限时，已保存联系人的来电识别覆盖受限。")
                    }
                    SetupCapability(status, Capability.CALL_LOG, "补全通话结果与时长", onCapability)
                    Text("若不再弹出授权提示，请打开应用设置调整；无法仅凭当前授权状态判断是否永久拒绝。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onAppSettings, Modifier.fillMaxWidth()) { Text("打开应用设置") }
                }
                if (data.overlayEnabled == true || data.recording?.recordingEnabled == true ||
                    data.overlayError != null || data.recordingError != null) {
                    SettingsCard("已启用功能的条件") {
                        data.overlayError?.let { Text("悬浮窗配置待确认"); ApiErrorNotice(it, onCapability) }
                        data.recordingError?.let { Text("录音配置待确认"); ApiErrorNotice(it, onCapability) }
                        if (data.overlayEnabled == true) SetupCapability(status, Capability.OVERLAY, "显示来电卡片", onCapability)
                        data.recording?.takeIf { it.recordingEnabled }?.let { recording ->
                            if (recording.captureMode == CaptureMode.MICROPHONE_EXPERIMENTAL) {
                                SetupCapability(status, Capability.MICROPHONE, "实验性麦克风采集", onCapability)
                            }
                            Text("录音目录", style = MaterialTheme.typography.titleSmall)
                            Text(if (recording.folderConfigured) UiLabels.access(recording.folderAccess) else "尚未选择")
                            Text("系统录音发现需要重新确认目录授权；麦克风采集效果需单独验证。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onRecordingSettings) { Text("选择录音目录") }
                        }
                    }
                }
                SettingsCard("当前声音条件") {
                    Text("当前声音条件：${UiLabels.audibility(status.audibility)}")
                    Text("仅观察系统声音条件，不自动修改静音、振动、音量或系统勿扰。")
                }
                SettingsCard("后台可靠性建议") {
                    SetupCapability(status, Capability.BATTERY_OPTIMIZATION_EXEMPTION, "电池优化豁免建议", onCapability)
                    Text("电池豁免不能保证后台来电处理；厂商自启动、后台弹出和锁屏显示设置无法通过标准接口可靠探测，请按设备说明检查。")
                    TextButton(onRuntime) { Text("运行状态与悬浮窗测试") }
                    Text("悬浮窗测试只证明当前测试卡片可显示，不能证明真实来电正常。", style = MaterialTheme.typography.bodySmall)
                }
                Text("检查时间：${UiFormat.time(status.checkedAtEpochMillis, ZoneId.systemDefault())}；系统与功能配置分别采样。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onRetry, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
            }
        }
    }
}

@Composable
private fun SetupCapability(status: RuntimeStatus, capability: Capability, purpose: String, onCapability: (Capability) -> Unit) {
    val access = status.capabilities[capability] ?: AccessState.UNKNOWN
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${UiLabels.capability(capability)} · ${UiLabels.access(access)}", style = MaterialTheme.typography.titleSmall)
        Text(purpose, style = MaterialTheme.typography.bodySmall)
        if (access == AccessState.DENIED || access == AccessState.UNKNOWN) {
            TextButton({ onCapability(capability) }, Modifier.testTag("setup-action-${capability.name}")) { Text("前往设置") }
        }
    }
}
