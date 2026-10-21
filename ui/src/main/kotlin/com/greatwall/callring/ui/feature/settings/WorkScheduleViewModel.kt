package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class WorkScheduleViewModel(private val api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle) : ImmediateEditor<WorkScheduleSettings>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getWorkSchedule,
    { base, value -> api.edits.work(base, normalizeWorkSchedule(value)) }, entities = mapOf(DataDomain.WORK_CALENDAR to setOf("schedule"))
) {
    fun reset() = submitReset { api.settings.resetWorkSchedule() }
}
