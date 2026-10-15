package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*

class SettingsSummaryViewModel(private val api: CallringUiApi) : SnapshotViewModel<SettingsSummary>(
    api.changes, setOf(DataDomain.STATISTICS, DataDomain.OVERLAY_SETTINGS, DataDomain.QUIET_HOURS,
        DataDomain.POSITIONS, DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS, DataDomain.APPEARANCE), api.settings::getSummary
) {
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun overlay(enabled: Boolean) = write({ api.settings.setOverlayEnabled(enabled) })
}

class QuietViewModel(api: CallringUiApi, state: SavedStateHandle) : DraftEditor<QuietSettings>(
    api.changes, setOf(DataDomain.QUIET_HOURS), state, SettingsDraftCodecs.quiet,
    api.settings::getQuietHours, api.settings::saveQuietHours
)

class WorkScheduleViewModel(private val api: CallringUiApi, state: SavedStateHandle) : DraftEditor<WorkScheduleSettings>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), state, SettingsDraftCodecs.work,
    api.settings::getWorkSchedule, { value -> api.settings.saveWorkSchedule(SaveWorkSchedule(value.zoneId, value.weekly, value.makeup)) }
) {
    fun reset() = execute({ api.settings.resetWorkSchedule() }) { replaceDraft(it); refresh() }
}

class ScoreSettingsViewModel(private val api: CallringUiApi, state: SavedStateHandle) : DraftEditor<ScoreSettings>(
    api.changes, setOf(DataDomain.SCORE_SETTINGS), state, SettingsDraftCodecs.score,
    api.settings::getScoreSettings, api.settings::saveScoreSettings
) {
    fun reset() = execute({ api.settings.resetScoreSettings() }) { replaceDraft(it); refresh() }
}

class PositionsViewModel(private val api: CallringUiApi) : SnapshotViewModel<PositionSettings>(
    api.changes, setOf(DataDomain.POSITIONS, DataDomain.CALLERS), api.settings::getPositions
) {
    fun save(command: SavePosition) = write({ api.settings.savePosition(command) }, "职位已保存")
    fun delete(id: Long) = write({ api.settings.deletePosition(id) }, "职位已删除")
    fun mine(id: Long?) = write({ api.settings.setMyPosition(id) }, "我的职位已更新")
}

class CalendarViewModel(private val api: CallringUiApi) : SnapshotViewModel<CalendarInfo>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getCalendar
) {
    fun import(source: DocumentRef) = write({ api.settings.importCalendar(source) }, "日历已导入")
    fun save(command: DateOverride) = write({ api.settings.saveDateOverride(command) }, "日期安排已保存")
    fun delete(date: java.time.LocalDate) = write({ api.settings.deleteDateOverride(date) }, "日期安排已删除")
}

class LeavesViewModel(private val api: CallringUiApi) : SnapshotViewModel<List<LeaveEntry>>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getLeaves
) {
    fun save(command: SaveLeave) = write({ api.settings.saveLeave(command) }, "请假记录已保存")
    fun delete(id: Long) = write({ api.settings.deleteLeave(id) }, "请假记录已删除")
}
