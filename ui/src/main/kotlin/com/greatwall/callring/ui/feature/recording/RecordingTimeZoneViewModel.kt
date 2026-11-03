package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.change.ChangeFeed
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.settings.SettingsApi
import com.greatwall.callring.ui.state.SnapshotViewModel
import kotlinx.coroutines.CoroutineScope
import java.time.ZoneId

/** Observes only the schedule field projected from the existing settings summary. */
class RecordingTimeZoneViewModel(settings: SettingsApi, changes: ChangeFeed, testScope: CoroutineScope? = null) :
    SnapshotViewModel<ZoneId>(changes, setOf(DataDomain.WORK_CALENDAR), {
        when (val result = settings.getSummary()) {
            is ApiResult.Success -> ApiResult.Success(ZoneId.of(result.value.workZoneId))
            is ApiResult.Failure -> result
        }
    }, testScope, entities = mapOf(DataDomain.WORK_CALENDAR to setOf("schedule")))
