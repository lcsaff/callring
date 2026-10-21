package com.greatwall.callring.bootstrap

import android.content.Context
import com.greatwall.callring.contracts.runtime.AccessState
import com.greatwall.callring.contracts.widget.WidgetInstallState
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import com.greatwall.callring.contracts.callers.IncomingCallUiSink

/** Owns concrete UI entry points and widget component lookup. */
internal class UiBridgeAssembly(
    private val application: Context,
    val notifications: UiNotificationEntryPoints,
    val incomingCallUi: IncomingCallUiSink,
) {
    fun installedWidgets() = WidgetInstallState(
        android.appwidget.AppWidgetManager.getInstance(application).getAppWidgetIds(
            android.content.ComponentName(application, com.greatwall.callring.ui.widget.CallringWidgetReceiver::class.java),
        ).toList(), AccessState.GRANTED,
    )
}
