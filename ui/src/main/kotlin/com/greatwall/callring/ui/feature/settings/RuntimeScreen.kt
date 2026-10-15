package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.settings.components.SettingsCard
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable
fun RuntimeScreen(
    state: LoadState<RuntimeUiData>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onCapability: (Capability) -> Unit,
    onSynchronize: () -> Unit, onAppSettings: () -> Unit,
) {
    ScreenFrame("运行状态与权限", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading || (!state.loaded && state.error == null)) LoadingContent()
            state.error?.let { ApiErrorNotice(it, onCapability); TextButton(onRetry) { Text("重试") } }
            MutationFeedback(mutation, onCapability)
            state.data?.let { data ->
                val status = data.status
                SettingsCard("应用能力") {
                    Capability.entries.forEach { capability ->
                        val access = status.capabilities[capability] ?: AccessState.UNKNOWN
                        SettingRow(UiLabels.capability(capability), "${UiLabels.access(access)} · ${capabilityPurpose(capability)}", trailing = {
                            if (access != AccessState.GRANTED && access != AccessState.NOT_REQUIRED && access != AccessState.UNAVAILABLE) {
                                TextButton({ onCapability(capability) }, enabled = !mutation.busy) { Text("前往设置") }
                            }
                        })
                    }
                    Text("若系统不再弹出授权提示，请在应用设置中调整权限。")
                    OutlinedButton(onAppSettings, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("打开应用设置")
                    }
                }
                SettingsCard("当前运行状态") {
                    SettingRow("声音状态", UiLabels.audibility(status.audibility))
                    SettingRow("通话状态", UiLabels.phoneActivity(status.phoneActivity))
                    SettingRow("播放状态", UiLabels.playback(status.playback))
                    SettingRow("固定桌面组件", if (status.pinWidgetSupported) "支持" else "不支持")
                    Text("检查时间：${UiFormat.time(status.checkedAtEpochMillis, ZoneId.systemDefault())}")
                    OutlinedButton(onRetry, enabled = !state.loading && !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("刷新状态") }
                }
                SettingsCard("通话记录同步") {
                    Text("请求同步系统通话记录。同步结果以运行状态和统计数据为准。")
                    BrandButton(onSynchronize, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("同步通话记录") }
                }
                SettingsCard("诊断记录") {
                    data.diagnosticsError?.let {
                        ApiErrorNotice(it, onCapability)
                        TextButton(onRetry, enabled = !state.loading) { Text("重新加载诊断") }
                    }
                    if (data.diagnostics.isEmpty() && data.diagnosticsError == null) EmptyContent("暂无诊断记录")
                    data.diagnostics.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.occurredAtEpochMillis?.let { UiFormat.time(it, ZoneId.systemDefault()) }
                                ?: entry.legacyTimeText ?: "时间未知", style = MaterialTheme.typography.labelMedium)
                            Text(entry.message)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun capabilityPurpose(capability: Capability): String = when (capability) {
    Capability.PHONE_STATE -> "在接听或挂断后停止铃声"
    Capability.CALL_LOG -> "补全通话结果与时长"
    Capability.CONTACTS -> "识别已保存联系人的来电"
    Capability.NOTIFICATIONS -> "显示铃声服务运行通知"
    Capability.OVERLAY -> "在其他应用上显示来电卡片"
    Capability.BATTERY_OPTIMIZATION_EXEMPTION -> "减少后台来电处理受到的限制"
    Capability.CALL_SCREENING_ROLE -> "接收系统交付的来电筛选事件"
}
