package com.greatwall.callring.ui.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.app.bridge.UiApiOwner
import com.greatwall.callring.ui.app.bridge.toThemeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout

class CallringWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 170.dp), DpSize(280.dp, 210.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val api = (context.applicationContext as UiApiOwner).uiApi
        val model = WidgetModel(api)
        val initialRequest = WidgetRefreshSignals.requests.value[widgetId]
        val initial = model.load()
        provideContent {
            var snapshot by remember { mutableStateOf(initial) }
            var requestError by remember { mutableStateOf(initialRequest?.error) }
            LaunchedEffect(widgetId) {
                widgetSessionUpdates(widgetId, initialRequest, model).collect { update ->
                    requestError = update.error
                    update.snapshot?.let { snapshot = it }
                }
            }
            WidgetContent(context, snapshot, requestError)
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        // Global quiet and leave settings belong to the app, never to a widget instance.
        WidgetRefreshSignals.forget(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}


@Composable
private fun WidgetContent(context: Context, snapshot: WidgetSnapshot, actionError: ApiError?) {
    val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val spec = snapshot.appearance.toThemeSpec(systemDark)
    val colors = if (spec.dark) spec.definition.dark else spec.definition.light
    val foreground = ColorProvider(colors.onSurface)
    val muted = ColorProvider(colors.onSurfaceVariant)
    val large = LocalSize.current.width >= 280.dp && LocalSize.current.height >= 210.dp
    Column(GlanceModifier.fillMaxSize().background(ColorProvider(colors.surfaceContainer)).cornerRadius(24.dp).padding(12.dp)) {
        Text("来电管理", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Switch(checked = snapshot.quiet?.mode?.let { it != QuietMode.OFF } ?: false,
            onCheckedChange = if (snapshot.quiet != null) actionRunCallback<WidgetQuietAction>() else null,
            text = "免打扰", style = TextStyle(color = foreground, fontSize = 14.sp),
            modifier = GlanceModifier.fillMaxWidth().height(48.dp))
        val granted = snapshot.leaveGranted == true
        val leaveModifier = GlanceModifier.fillMaxWidth().height(48.dp)
            .background(ColorProvider(if (granted) colors.surfaceContainer else colors.primaryContainer)).cornerRadius(12.dp)
        // Keep the idempotent action active: a launcher may still render yesterday's acknowledgement.
        Box(if (snapshot.leaveGranted != null) leaveModifier.clickable(actionRunCallback<WidgetTodayLeaveAction>()) else leaveModifier,
            contentAlignment = Alignment.Center) {
            Text(if (granted) "今日已请假" else "今日请假", style = TextStyle(
                color = if (granted) muted else ColorProvider(colors.onPrimaryContainer), fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        val error = actionError ?: snapshot.error
        if (error != null) Text("操作未完成，点此刷新", maxLines = 1,
            style = TextStyle(color = ColorProvider(colors.error), fontSize = 11.sp),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionRunCallback<WidgetRetryAction>()).padding(top = 4.dp))
        else if (large) Text("一键申请今天全天请假", maxLines = 1, style = TextStyle(color = muted, fontSize = 12.sp),
            modifier = GlanceModifier.padding(top = 8.dp))
    }
}

class WidgetRetryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UiWidgetRefresh.refresh(context)
    }
}

class WidgetQuietAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val enabled = parameters[ToggleableStateKey] ?: return
        runWidgetAction(context, glanceId) { model -> model.setQuietEnabled(enabled) }
    }
}

class WidgetTodayLeaveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runWidgetAction(context, glanceId) { it.requestTodayLeave() }
    }
}

private suspend fun runWidgetAction(context: Context, glanceId: GlanceId, action: suspend (WidgetModel) -> ApiResult<*>) {
    val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val error = try {
        val result = withTimeout(7_000) { action(WidgetModel((context.applicationContext as UiApiOwner).uiApi)) }
        (result as? ApiResult.Failure)?.error
    } catch (_: TimeoutCancellationException) { ApiError(ErrorCode.INTERNAL, "操作超时，请刷新后重试", retryable = true) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiError(ErrorCode.INTERNAL, "操作未完成，请重试", retryable = true) }
    UiWidgetRefresh.present(context, setOf(id), error)
}
