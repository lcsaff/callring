package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class SettingsSummaryViewModel(private val api: CallringUiApi) : SnapshotViewModel<SettingsSummary>(
    api.changes, setOf(DataDomain.STATISTICS, DataDomain.OVERLAY_SETTINGS, DataDomain.QUIET_HOURS,
        DataDomain.POSITIONS, DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS, DataDomain.APPEARANCE), api.settings::getSummary, entities = mapOf(DataDomain.WORK_CALENDAR to setOf("schedule"))
) {
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun overlay(enabled: Boolean) = write({ api.settings.setOverlayEnabled(enabled) })
}
