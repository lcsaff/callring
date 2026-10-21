package com.greatwall.callring.application.ports.platform

import com.greatwall.callring.domain.model.CalendarImport

/** Downloads a validated annual calendar; never invoked from the call decision path. */
fun interface CalendarSource {
    fun fetch(year: Int): CalendarImport
}
