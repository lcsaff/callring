package com.greatwall.callring.platform.runtime

import android.app.Notification
import android.app.PendingIntent

/** Implemented by the UI composition root. Backend services never reference an Activity class. */
interface UiNotificationEntryPoints {
    fun home(requestCode: Int): PendingIntent

    /** UI creates its localized notification and channel. The service supplies its stop command. */
    fun playback(preview: Boolean, stopAction: PendingIntent): Notification
}
