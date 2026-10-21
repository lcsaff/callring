package com.greatwall.callring.ui.app.bridge

import com.greatwall.callring.ui.app.CallringActivity

import android.app.*
import android.content.Context

class UiNotifications(private val context: Context) {
    fun home(requestCode: Int): PendingIntent = PendingIntent.getActivity(context, requestCode,
        CallringActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun playback(preview: Boolean, stop: PendingIntent): Notification {
        channel("callring.playback", "铃声播放", NotificationManager.IMPORTANCE_LOW)
        return Notification.Builder(context, "callring.playback")
            .setSmallIcon(context.applicationInfo.icon).setContentTitle(if (preview) "试听铃声" else "来电铃声")
            .setContentText("点击停止播放").setContentIntent(home(201)).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    fun overlay(): Notification {
        channel("callring.overlay", "来电悬浮窗", NotificationManager.IMPORTANCE_LOW)
        return Notification.Builder(context, "callring.overlay")
            .setSmallIcon(context.applicationInfo.icon).setContentTitle("来电卡片正在显示")
            .setContentIntent(home(202)).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET).build()
    }

    private fun channel(id: String, title: String, importance: Int) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, title, importance).apply { setShowBadge(false) })
    }
}
