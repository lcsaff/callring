package com.greatwall.callring.ui.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.greatwall.callring.application.api.IncomingCallUiRequest
import com.greatwall.callring.application.api.IncomingCallUiSink

/** No API reads or window work on the business caller's thread. */
class AndroidIncomingCallUiSink(context: Context) : IncomingCallUiSink {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    override fun show(request: IncomingCallUiRequest) {
        main.post {
            try { application.startForegroundService(CallringOverlayService.intent(application, request)) }
            catch (_: RuntimeException) { Log.w("CallringOverlay", "System rejected overlay service start") }
        }
    }
}
