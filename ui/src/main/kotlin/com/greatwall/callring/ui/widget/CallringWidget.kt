package com.greatwall.callring.ui.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.app.CallringActivity
import com.greatwall.callring.ui.app.UiApiOwner
import com.greatwall.callring.ui.app.UiDestination
import com.greatwall.callring.ui.designsystem.ScorePalette
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import com.greatwall.callring.ui.designsystem.usesDarkColors
import com.greatwall.callring.ui.state.affects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

class CallringWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 170.dp), DpSize(280.dp, 210.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val api = (context.applicationContext as UiApiOwner).uiApi
        val model = WidgetModel(api)
        val initial = model.load(widgetId)
        provideContent {
            var snapshot by remember { mutableStateOf(initial) }
            var requestError by remember { mutableStateOf<ApiError?>(null) }
            // Both subscriptions live with Glance's worker session, including while no Activity exists.
            LaunchedEffect(widgetId) {
                merge(
                    WidgetRefreshSignals.requests.map { it[widgetId] }.distinctUntilChanged()
                        .map { WidgetLoadRequest(clearError = true, error = it?.error) },
                    api.changes.observe().filter { it.affects(widgetDomains, snapshot.binding?.number, widgetId) }
                        .map { WidgetLoadRequest() },
                ).collectLatest { request ->
                    if (request.clearError) requestError = request.error
                    val loaded = model.load(widgetId)
                    currentCoroutineContext().ensureActive()
                    snapshot = loaded
                }
            }
            WidgetContent(context, widgetId, snapshot, requestError)
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        try {
            // Glance's broadcast completion owns this suspend callback. Leave time for its cleanup.
            withTimeout(7_000) { WidgetModel((context.applicationContext as UiApiOwner).uiApi).remove(widgetId) }
        } finally {
            WidgetRefreshSignals.forget(widgetId)
        }
    }
}

private data class WidgetLoadRequest(val clearError: Boolean = false, val error: ApiError? = null)

private val widgetDomains = setOf(DataDomain.WIDGET_BINDINGS, DataDomain.CALLERS, DataDomain.POSITIONS,
    DataDomain.CALLS, DataDomain.REPORTS, DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS,
    DataDomain.SCORE_SETTINGS, DataDomain.STATISTICS, DataDomain.APPEARANCE)

@Composable
private fun WidgetContent(context: Context, widgetId: Int, snapshot: WidgetSnapshot, actionError: ApiError?) {
    val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dark = snapshot.appearance.usesDarkColors(systemDark)
    val theme = ThemeRegistry.resolve(snapshot.appearance)
    val colors = if (dark) theme.dark else theme.light
    val foreground = ColorProvider(colors.onSurface)
    val muted = ColorProvider(colors.onSurfaceVariant)
    val large = LocalSize.current.width >= 280.dp && LocalSize.current.height >= 210.dp
    val overview = snapshot.overview
    Column(GlanceModifier.fillMaxSize().background(ColorProvider(colors.surfaceContainer)).cornerRadius(24.dp).padding(12.dp)) {
        if (overview == null) {
            Text("来电评分", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(snapshot.error?.message ?: "选择一个号码开始使用", style = TextStyle(color = muted, fontSize = 13.sp), maxLines = 2,
                modifier = GlanceModifier.padding(vertical = 8.dp))
            WidgetLink("选择号码", actionStartActivity(WidgetConfigurationActivity.intent(context, widgetId)), colors.primary)
            WidgetLink("重试", actionRunCallback<WidgetRetryAction>(), colors.primary)
        } else {
            Text(overview.profile.name.ifBlank { overview.profile.number }, maxLines = 1,
                style = TextStyle(color = foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            if (overview.profile.name.isNotBlank()) Text(overview.profile.number, maxLines = 1, style = TextStyle(color = muted, fontSize = 12.sp))
            if (large) Text(listOf(overview.profile.department, overview.profile.position?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "部门、职位未填写" },
                maxLines = 1, style = TextStyle(color = muted, fontSize = 13.sp), modifier = GlanceModifier.padding(top = 4.dp))
            val score = ScorePalette.colors(overview.rollingScore.points, dark)
            Text("不受欢迎程度评分 ${overview.rollingScore.points?.toString() ?: "暂无"}",
                style = TextStyle(color = ColorProvider(score.foreground), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.padding(top = 8.dp, bottom = 4.dp))
            val error = actionError ?: snapshot.error
            if (error != null) Text(error.message, maxLines = 1, style = TextStyle(color = ColorProvider(colors.error), fontSize = 11.sp))
            Row(GlanceModifier.fillMaxWidth()) {
                WidgetLink("查看报表", actionStartActivity(CallringActivity.intent(context, UiDestination.Report(overview.profile.number))), colors.primary,
                    GlanceModifier.defaultWeight())
                val enabled = !overview.profile.whitelisted
                WidgetLink(if (enabled) "加入白名单" else "移出白名单", actionRunCallback<WidgetWhitelistAction>(actionParametersOf(
                    WidgetActionKeys.number to snapshot.binding!!.number, WidgetActionKeys.enabled to enabled)), colors.primary, GlanceModifier.defaultWeight())
            }
            if (large) Row {
                WidgetLink("更换号码", actionStartActivity(WidgetConfigurationActivity.intent(context, widgetId)), colors.primary)
                WidgetLink("刷新", actionRunCallback<WidgetRetryAction>(), colors.primary)
            }
        }
    }
}

@Composable
private fun WidgetLink(label: String, action: androidx.glance.action.Action, color: androidx.compose.ui.graphics.Color, modifier: GlanceModifier = GlanceModifier) {
    Text(label, style = TextStyle(color = ColorProvider(color), fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = modifier.clickable(action).padding(vertical = 10.dp, horizontal = 4.dp), maxLines = 1)
}

private object WidgetActionKeys {
    val number = ActionParameters.Key<String>("number")
    val enabled = ActionParameters.Key<Boolean>("enabled")
}

class WidgetRetryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        UiWidgetRefresh.refresh(context, setOf(id))
    }
}

class WidgetWhitelistAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val number = parameters[WidgetActionKeys.number] ?: return
        val enabled = parameters[WidgetActionKeys.enabled] ?: return
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val error = try {
            val result = WidgetModel((context.applicationContext as UiApiOwner).uiApi).setWhitelisted(id, number, enabled)
            (result as? ApiResult.Failure)?.error
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiError(ErrorCode.INTERNAL, "白名单操作未完成，请重试") }
        UiWidgetRefresh.refresh(context, setOf(id))
        if (error != null) WidgetRefreshSignals.signal(listOf(id), error)
    }
}
