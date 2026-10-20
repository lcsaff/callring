package com.greatwall.callring.application

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createSettingsApi(context: BackendContext): SettingsApi =
    with(context) {
        object : SettingsApi {
            override suspend fun getSummary() = summaryService.get()

            override suspend fun setStatisticsEnabled(enabled: Boolean) = statisticsService.set(enabled)

            override suspend fun setOverlayEnabled(enabled: Boolean) = overlayService.set(enabled)

            override suspend fun getQuietHours(): ApiResult<QuietSettings> = quietService.get()

            override suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings> =
                quietService.save(settings)

            override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> = quietService.mode(mode)

            override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> = quietService.enabled(enabled)

            override suspend fun getPositions() = positionService.get()
            override suspend fun savePosition(command: SavePosition) = positionService.save(command)
            override suspend fun deletePosition(id: Long) = positionService.delete(id)
            override suspend fun setMyPosition(id: Long?) = positionService.mine(id)

            override suspend fun getWorkSchedule() = workScheduleService.get()
            override suspend fun saveWorkSchedule(command: SaveWorkSchedule) = workScheduleService.save(command)
            override suspend fun resetWorkSchedule() = workScheduleService.reset()
            override suspend fun getCalendar() = calendarService.get()
            override suspend fun synchronizeCalendar(year: Int) = calendarService.synchronize(year)
            override suspend fun importCalendar(source: DocumentRef) = calendarService.import(source)
            override suspend fun saveDateOverride(command: DateOverride) = calendarService.saveOverride(command)
            override suspend fun deleteDateOverride(date: LocalDate) = calendarService.deleteOverride(date)
            override suspend fun getLeaves() = leaveService.get()
            override suspend fun saveLeave(command: SaveLeave) = leaveService.save(command)
            override suspend fun deleteLeave(id: Long) = leaveService.delete(id)
            override suspend fun getScoreSettings() = scoreService.get()
            override suspend fun saveScoreSettings(settings: ScoreSettings) = scoreService.save(settings)
            override suspend fun resetScoreSettings() = scoreService.reset()

            override suspend fun getAppearance() = appearanceService.get()
            override suspend fun saveAppearance(settings: AppearanceSettings) = appearanceService.save(settings)
        }
    }
