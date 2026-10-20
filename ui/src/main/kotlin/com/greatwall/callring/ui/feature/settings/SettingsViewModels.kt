package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class SettingsSummaryViewModel(private val api: CallringUiApi) : SnapshotViewModel<SettingsSummary>(
    api.changes, setOf(DataDomain.STATISTICS, DataDomain.OVERLAY_SETTINGS, DataDomain.QUIET_HOURS,
        DataDomain.POSITIONS, DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS, DataDomain.APPEARANCE), api.settings::getSummary, refreshFromChanges = true
) {
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun overlay(enabled: Boolean) = write({ api.settings.setOverlayEnabled(enabled) })
}

class QuietViewModel(api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle, testScope: kotlinx.coroutines.CoroutineScope? = null) : ImmediateEditor<QuietSettings>(
    api.changes, setOf(DataDomain.QUIET_HOURS), api.settings::getQuietHours, api.edits::quiet, testScope
)

class WorkScheduleViewModel(private val api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle) : ImmediateEditor<WorkScheduleSettings>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getWorkSchedule,
    { base, value -> api.edits.work(base, normalizeWorkSchedule(value)) }
) {
    fun reset() = submitReset { api.settings.resetWorkSchedule() }
}

class ScoreSettingsViewModel(private val api: CallringUiApi, @Suppress("UNUSED_PARAMETER") state: SavedStateHandle) : ImmediateEditor<ScoreSettings>(
    api.changes, setOf(DataDomain.SCORE_SETTINGS), api.settings::getScoreSettings, api.edits::score,
    editDebounceMillis = 500L
) {
    fun reset() = submitReset { api.settings.resetScoreSettings() }
}

class PositionsViewModel(private val api: CallringUiApi) : SnapshotViewModel<PositionSettings>(
    api.changes, setOf(DataDomain.POSITIONS), api.settings::getPositions, refreshFromChanges = true
) {
    fun save(command: SavePosition) = write({ api.settings.savePosition(command) }, "职位已保存")
    fun saveDraft(command: SavePosition, base: SavePosition?) = write({ api.edits.position(base, command) }, "职位已保存")
    fun delete(id: Long) = write({ api.settings.deletePosition(id) }, "职位已删除")
    fun mine(id: Long?) = write({ api.settings.setMyPosition(id) }, "我的职位已更新")
}

class CalendarViewModel(private val api: CallringUiApi) : SnapshotViewModel<CalendarInfo>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getCalendar
) {
    fun synchronize(year: Int) = write({
        when (val result = api.settings.synchronizeCalendar(year)) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> ApiResult.Failure(result.error.copy(message =
                "节假日同步未完成，现有日历未改变，请稍后重试"))
        }
    }, "节假日已同步")
}

class LeavesViewModel(private val api: CallringUiApi) : SnapshotViewModel<List<LeaveEntry>>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getLeaves
) {
    fun save(command: SaveLeave) = write({ api.settings.saveLeave(command) }, "请假记录已保存")
    fun saveDraft(command: SaveLeave, base: SaveLeave?) = write({ api.edits.leave(base, command) }, "请假记录已保存")
    fun delete(id: Long) = write({ api.settings.deleteLeave(id) }, "请假记录已删除")
}
