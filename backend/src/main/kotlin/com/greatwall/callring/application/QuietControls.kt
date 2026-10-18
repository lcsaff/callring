package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.BackendState

/** All mode writes share one remembered setting; disabling never destroys the time windows. */
internal fun BackendContext.updateQuiet(state: BackendState, value: QuietSettings): QuietSettings {
    validateQuiet(value)
    val copy = quietCopy(value)
    if (state.quiet.mode != QuietMode.OFF) state.lastQuietMode = state.quiet.mode
    if (copy.mode != QuietMode.OFF) state.lastQuietMode = copy.mode
    state.quiet = copy
    return copy
}

internal fun resumedQuietMode(state: BackendState): QuietMode = when {
    state.quiet.mode != QuietMode.OFF -> state.quiet.mode
    state.lastQuietMode == QuietMode.CUSTOM && state.quiet.weeklyWindows.values.any { it.isNotEmpty() } -> QuietMode.CUSTOM
    else -> QuietMode.NON_WORK
}
