package com.greatwall.callring.ui.feature.settings

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.greatwall.callring.contracts.settings.SettingsSummary
import com.greatwall.callring.ui.designsystem.components.SettingRow
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

enum class SettingsPage { QUIET, POSITIONS, MY_POSITION, WORK, CALENDAR, LEAVES, SCORE, RINGTONE, RUNTIME, APPEARANCE, RECORDING, AI, UPDATE }

/** QA builds must not offer the in-app update entry (their applicationId cannot match a release). */
internal fun updateEntryVisible(packageName: String): Boolean = !packageName.endsWith(".backendqa")

/** The installed version always comes from the system package manager, never from the backend summary. */
internal fun installedVersionName(context: Context): String? = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()

@Composable
fun SettingsOverviewScreen(
    state: LoadState<SettingsSummary>, mutation: MutationState,
    onBack: (() -> Unit)?, onRetry: () -> Unit, onStatistics: (Boolean) -> Unit,
    onOverlay: (Boolean) -> Unit, onNavigate: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) { installedVersionName(context) }
    val showUpdate = remember(context) { updateEntryVisible(context.packageName) }
    SettingsLayout("设置", state, mutation, onBack, onRetry, compactTitle = true) { data ->
        SettingsCard("个人信息") {
            SettingRow("我的职位", data.myPosition?.let { it.name + if (it.active) "" else "（已停用）" } ?: "尚未设置",
                { onNavigate(SettingsPage.MY_POSITION) })
        }
        SettingsCard("来电处理") {
            SwitchSettingRow("来电统计", data.statisticsEnabled, onStatistics, "记录并统计来电情况", enabled = !mutation.busy)
            FieldError(mutation, "statisticsEnabled")
            SwitchSettingRow("来电悬浮提示", data.overlayEnabled, onOverlay, "来电时显示号码与处理信息", enabled = !mutation.busy)
            FieldError(mutation, "overlayEnabled")
            SettingRow("铃声", "导入、试听与恢复内置铃声", { onNavigate(SettingsPage.RINGTONE) })
        }
        SettingsCard("工作与评分") {
            SettingRow("工作时间", "统一上下班、午休时间", { onNavigate(SettingsPage.WORK) })
            SettingRow("节假日同步", "自动获取全年放假与补班安排", { onNavigate(SettingsPage.CALENDAR) })
            SettingRow("评分设置", "非工作来电权重 ${data.score.offHoursWeight} · 重复来电权重 ${data.score.repeatWeight}", { onNavigate(SettingsPage.SCORE) })
        }
        SettingsCard("录音") {
            SettingRow("录音设置", "录音来源与目录授权", { onNavigate(SettingsPage.RECORDING) })
        }
        SettingsCard("AI") {
            SettingRow("AI 设置", "分析、转写连接、思考模式与上下文预算", { onNavigate(SettingsPage.AI) })
        }
        SettingsCard("显示") {
            SettingRow("外观", "主题色与深浅模式", { onNavigate(SettingsPage.APPEARANCE) })
        }
        SettingsCard("系统与支持") {
            SettingRow("运行状态与权限", "权限授权与运行诊断", { onNavigate(SettingsPage.RUNTIME) })
            if (showUpdate) {
                SettingRow("检查更新", versionName?.let { "当前版本 $it" }, { onNavigate(SettingsPage.UPDATE) })
            }
        }
    }
}
