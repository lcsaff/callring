package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class CalendarViewModel(private val api: CallringUiApi) : SnapshotViewModel<CalendarInfo>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getCalendar, entities = mapOf(DataDomain.WORK_CALENDAR to setOf("calendar"))
) {
    fun synchronize(year: Int) = write({
        when (val result = api.settings.synchronizeCalendar(year)) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> ApiResult.Failure(result.error.copy(message =
                "节假日同步未完成，现有日历未改变，请稍后重试"))
        }
    }, "节假日已同步")
}
