package com.greatwall.callring.ui.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.state.LoadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    state: LoadState<HomeData>, onRetry: () -> Unit, onLeaves: () -> Unit,
    quietCard: @Composable () -> Unit = {},
) {
    ScreenFrame("首页") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadFeedback(state, onRetry)
            state.data?.let { data ->
                val work = data.currentWork
                val date = Instant.ofEpochMilli(work.evaluatedAtEpochMillis).atZone(ZoneId.of(work.classification.zoneId))
                Card(Modifier.fillMaxWidth().testTag("home-status"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)),
                            style = MaterialTheme.typography.bodyLarge)
                        Text(when (work.dayKind) {
                            WorkDayKind.WORKDAY -> "工作日"
                            WorkDayKind.REST_DAY -> "休息日"
                            WorkDayKind.HOLIDAY -> "法定节假日"
                        }, style = MaterialTheme.typography.headlineMedium)
                        work.holidayName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.titleLarge)
                        }
                        HorizontalDivider()
                        Text(when {
                            work.classification.working -> "当前工作中"
                            work.classification.reason == WorkReason.PERSONAL_LEAVE -> "请假中 · 当前不工作"
                            else -> "当前不工作"
                        }, style = MaterialTheme.typography.titleMedium)
                        if (!work.classification.calendarVerified) Text("本年度节假日数据待更新",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                quietCard()
                Card(onClick = onLeaves, modifier = Modifier.fillMaxWidth().testTag("home-leaves"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("请假管理", style = MaterialTheme.typography.titleMedium)
                        Text("添加请假，查看和管理请假记录", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
