package com.greatwall.callring.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread binding only. The service remains the sole controller/resource owner. */
internal object LockScreenOverlayHost {
    private val mutable = MutableStateFlow<OverlayController?>(null)
    val controller = mutable.asStateFlow()
    private var foreground: Any? = null
    val resumed get() = foreground != null
    fun resumed(activity: Any) { foreground = activity }
    fun paused(activity: Any) { if (foreground === activity) foreground = null }
    fun show(owner: OverlayController) { mutable.value = owner }
    fun hide(owner: OverlayController) {
        if (mutable.value === owner) { mutable.value = null; foreground = null }
    }
}
