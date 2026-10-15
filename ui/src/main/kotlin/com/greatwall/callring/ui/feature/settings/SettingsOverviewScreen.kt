package com.greatwall.callring.ui.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.greatwall.callring.application.api.SettingsSummary
import com.greatwall.callring.ui.designsystem.components.SettingRow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

enum class SettingsPage { QUIET, POSITIONS, WORK, CALENDAR, LEAVES, SCORE, RINGTONE, RUNTIME, APPEARANCE }

@Composable
fun SettingsOverviewScreen(
    state: LoadState<SettingsSummary>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onStatistics: (Boolean) -> Unit,
    onOverlay: (Boolean) -> Unit, onNavigate: (SettingsPage) -> Unit,
) {
    SettingsLayout("设置", state, mutation, onBack, onRetry) { data ->
        SettingsCard("来电管理") {
            SettingRow("来电统计", "记录并统计来电情况", trailing = {
                SettingsSwitch("来电统计", data.statisticsEnabled, onStatistics, enabled = !mutation.busy)
            })
            FieldError(mutation, "statisticsEnabled")
            SettingRow("来电悬浮提示", "来电时显示号码与处理信息", trailing = {
                SettingsSwitch("来电悬浮提示", data.overlayEnabled, onOverlay, enabled = !mutation.busy)
            })
            FieldError(mutation, "overlayEnabled")
            SettingRow("免打扰", UiLabels.quiet(data.quietMode), { onNavigate(SettingsPage.QUIET) })
            SettingRow("职位管理", data.myPosition?.let { it.name + if (it.active) "" else "（已停用）" } ?: "尚未设置我的职位", { onNavigate(SettingsPage.POSITIONS) })
        }
        SettingsCard("工作与统计") {
            SettingRow("工作时间", data.workZoneId, { onNavigate(SettingsPage.WORK) })
            SettingRow("节假日与日期覆盖", "导入日历，管理指定日期", { onNavigate(SettingsPage.CALENDAR) })
            SettingRow("请假管理", "全天或指定时间请假", { onNavigate(SettingsPage.LEAVES) })
            SettingRow("评分设置", "非工作来电权重 ${data.score.offHoursWeight} · 重复来电权重 ${data.score.repeatWeight}", { onNavigate(SettingsPage.SCORE) })
        }
        SettingsCard("应用") {
            SettingRow("铃声", "导入、试听与恢复内置铃声", { onNavigate(SettingsPage.RINGTONE) })
            SettingRow("运行状态与权限", "能力授权、播放状态与诊断", { onNavigate(SettingsPage.RUNTIME) })
            SettingRow("外观", "主题色与深浅模式", { onNavigate(SettingsPage.APPEARANCE) })
        }
    }
}
