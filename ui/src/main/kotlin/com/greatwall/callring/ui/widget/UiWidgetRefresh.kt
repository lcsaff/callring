package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.greatwall.callring.contracts.common.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import androidx.glance.appwidget.GlanceAppWidgetManager

internal data class WidgetRefreshRequest(val revision: Long, val error: ApiError? = null)

/** Signals invalidate presentation; persisted control state always comes from the API. */
internal object WidgetRefreshSignals {
    val requests = MutableStateFlow<Map<Int, WidgetRefreshRequest>>(emptyMap())
    fun signal(widgetIds: Iterable<Int>, error: ApiError? = null) {
        requests.update { previous ->
            previous.toMutableMap().apply {
                widgetIds.forEach { id -> put(id, WidgetRefreshRequest((previous[id]?.revision ?: 0) + 1, error)) }
            }
        }
    }
    fun present(widgetIds: Iterable<Int>, error: ApiError?) {
        requests.update { previous -> previous.toMutableMap().apply {
            widgetIds.forEach { id -> put(id, (previous[id] ?: WidgetRefreshRequest(0)).copy(error = error)) }
        } }
    }
    fun forget(widgetId: Int) { requests.update { it - widgetId } }
}

/** Application change-feed and time-change bridges may call this from their owned coroutine. */
object UiWidgetRefresh {
    suspend fun refresh(context: Context, widgetIds: Set<Int>? = null) = update(context, widgetIds, true, null)
    suspend fun present(context: Context, widgetIds: Set<Int>, error: ApiError?) = update(context, widgetIds, false, error)
    private suspend fun update(context: Context, widgetIds: Set<Int>?, reload: Boolean, error: ApiError?) {
        val application = context.applicationContext
        val ownedIds = AppWidgetManager.getInstance(application)
            .getAppWidgetIds(ComponentName(application, CallringWidgetReceiver::class.java)).toSet()
        val ids = if (widgetIds == null) ownedIds else ownedIds.intersect(widgetIds)
        if (reload) WidgetRefreshSignals.signal(ids) else WidgetRefreshSignals.present(ids, error)
        val manager = GlanceAppWidgetManager(application)
        ids.forEach { id -> CallringWidget().update(application, manager.getGlanceIdBy(id)) }
    }
}
