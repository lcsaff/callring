package com.greatwall.callring.platform.audio

import android.content.Context
import android.content.Intent
import android.os.ResultReceiver
import com.greatwall.callring.RingPlaybackService

/** The ready callback and token retain the service's prepare-before-silence handshake. */
class AndroidPlaybackCommands(private val context: Context) {
    fun prepare(token: String, ready: ResultReceiver) {
        context.startForegroundService(
            Intent(context, RingPlaybackService::class.java)
                .setAction("prepare")
                .putExtra("token", token)
                .putExtra("ready", ready)
        )
    }

    fun cancel(token: String) {
        context.startService(
            Intent(context, RingPlaybackService::class.java)
                .setAction("cancel")
                .putExtra("token", token)
        )
    }

    fun commitPrepared(token: String): Boolean = RingPlaybackService.commitPrepared(token)
}
