package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.greatwall.callring.application.api.CalendarInfo
import com.greatwall.callring.ui.designsystem.components.BrandButton
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import java.time.LocalDate

@Composable
fun CalendarScreen(
    state: LoadState<CalendarInfo>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onSynchronize: () -> Unit,
    year: Int = LocalDate.now().year,
) {
    SettingsLayout("节假日同步", state, mutation, onBack, onRetry) { data ->
        SettingsCard("全年节假日") {
            Text("自动同步全年放假和补班安排，所有工作日使用相同的上下班与午休时间。")
            Text("已同步的数据保存在本机，断网后仍可使用。")
            if (data.years.none { it.year == year }) Text("$year 年节假日数据尚未同步")
            BrandButton(onSynchronize, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (mutation.busy) "正在同步…" else "同步全年节假日")
            }
        }
        data.years.sortedByDescending { it.year }.forEach { entry ->
            SettingsCard("${entry.year} 年") {
                Text("数据来源：${entry.source}")
                Text(if (entry.source.contains("holiday-cn")) "同步日期：${entry.published}" else "发布日期：${entry.published}")
                Text("数据版本：${entry.version}")
            }
        }
    }
}
