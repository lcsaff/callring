package com.greatwall.callring.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Glance owns the PendingResult, timeout and work scheduling for system broadcasts. */
class CallringWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CallringWidget()
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefreshSignals.signal(appWidgetIds.asIterable())
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
