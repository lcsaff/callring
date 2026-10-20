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

            override suspend fun getWorkSchedule(): ApiResult<WorkScheduleSettings> = io {
                read { scheduleCopy(currentSchedule(it)).toContract() }
            }

            override suspend fun saveWorkSchedule(
                command: SaveWorkSchedule
            ): ApiResult<WorkScheduleSettings> = write {
                validateZone(command.zoneId)
                validateWeekly(command.weekly, "weekly")
                validateWindows(command.makeup, "makeup")
                mutate { s ->
                    val effective =
                        maxOf(
                            clock.millis(),
                            s.schedules.maxOfOrNull { it.effectiveFromEpochMillis + 1 } ?: 0,
                        )
                    val version = s.nextConfigVersion++
                    val c =
                        scheduleCopy(
                            WorkScheduleSettings(
                                command.zoneId,
                                command.weekly,
                                command.makeup,
                                version,
                                effective,
                            )
                        )
                    s.schedules += c.toDomain()
                    c
                }
            }

            override suspend fun resetWorkSchedule(): ApiResult<WorkScheduleSettings> {
                val defaults = defaultWorkSchedule().toContract()
                return saveWorkSchedule(
                    SaveWorkSchedule(defaults.zoneId, defaults.weekly, defaults.makeup)
                )
            }

            override suspend fun getCalendar(): ApiResult<CalendarInfo> = io {
                read { calendar(it) }
            }

            override suspend fun synchronizeCalendar(year: Int): ApiResult<CalendarInfo> = write {
                requireField(year in 2008..9998, "year", "calendar.year.invalid")
                // Network completes before the store transaction or coordinator lock is acquired.
                val imported = calendarSource.fetch(year)
                requireField(imported.metadata.year == year, "year", "calendar.year.mismatch")
                requireField(imported.holidays.isNotEmpty(), "holidays", "calendar.incomplete")
                val previous = read { it.calendarYears[year] }
                // The feed omits a publication timestamp. Preserve the first fetched date when
                // its source/hash is identical, avoiding needless history reclassification.
                val metadata = if (previous != null && previous.source == imported.metadata.source &&
                    previous.version == imported.metadata.version) previous else imported.metadata
                replaceCalendar(imported.copy(metadata = metadata))
            }

            override suspend fun importCalendar(source: DocumentRef): ApiResult<CalendarInfo> =
                write { replaceCalendar(documents.readCalendar(source)) }

            override suspend fun saveDateOverride(command: DateOverride): ApiResult<CalendarInfo> =
                write {
                    requireIssues(com.greatwall.callring.domain.model.SettingsRules.overrideWindows(command.toDomain()))
                    val copy = command.copy(windows = frozenList(command.windows)).toDomain()
                    mutate { s ->
                        if (s.overrides[copy.date] != copy) {
                            s.overrides[copy.date] = copy
                            s.pendingAudits +=
                                AuditRecord(
                                    "override",
                                    copy.date.toString(),
                                    copy.windows.joinToString(",", "[", "]") {
                                        "[${it.startMinute},${it.endMinute}]"
                                    },
                                    clock.millis(),
                                )
                            WorkClassifier.reclassify(s)
                        }
                        calendar(s)
                    }
                }

            override suspend fun deleteDateOverride(date: LocalDate): ApiResult<CalendarInfo> =
                write {
                    mutate { s ->
                        if (s.overrides.remove(date) != null) {
                            s.pendingAudits +=
                                AuditRecord("override-delete", date.toString(), "", clock.millis())
                            WorkClassifier.reclassify(s)
                        }
                        calendar(s)
                    }
                }

            override suspend fun getLeaves(): ApiResult<List<LeaveEntry>> = io {
                read { s ->
                    frozenList(
                        s.leaves.values
                            .sortedWith(
                                compareByDescending<LeaveRecord> { it.startMillis }
                                    .thenByDescending { it.id }
                            )
                            .map { leaveEntry(it) }
                    )
                }
            }

            override suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry> = write {
                val bounds =
                    when (val period = command.period) {
                        is LeavePeriod.Timed ->
                            Pair(period.startInclusiveEpochMillis, period.endExclusiveEpochMillis)
                        is LeavePeriod.AllDay -> {
                            validateZone(period.zoneId, "period.zoneId")
                            requireIssues(com.greatwall.callring.domain.model.SettingsRules.dateOrder(period.startDate, period.endDateInclusive, "period.endDateInclusive"))
                            val zone = ZoneId.of(period.zoneId)
                            Pair(
                                period.startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                                period.endDateInclusive
                                    .plusDays(1)
                                    .atStartOfDay(zone)
                                    .toInstant()
                                    .toEpochMilli(),
                            )
                        }
                    }
                requireIssues(com.greatwall.callring.domain.model.SettingsRules.leaveBounds(bounds.first, bounds.second))
                mutate { s ->
                    if (command.id != null && command.id !in s.leaves)
                        fail(ErrorCode.NOT_FOUND, "leave.not_found")
                    val id = command.id ?: s.nextLeaveId++
                    val record = LeaveRecord(id, bounds.first, bounds.second, command.note.trim())
                    if (s.leaves[id] != record) {
                        s.leaves[id] = record
                        s.leaveVersion++
                        s.pendingAudits +=
                            AuditRecord(
                                "leave",
                                id.toString(),
                                "${record.startMillis}:${record.endMillis}:${record.note}",
                                clock.millis(),
                            )
                        WorkClassifier.reclassify(s)
                    }
                    leaveEntry(record)
                }
            }

            override suspend fun deleteLeave(id: Long): ApiResult<Unit> = write {
                mutate { s ->
                    if (s.leaves.remove(id) == null) fail(ErrorCode.NOT_FOUND, "leave.not_found")
                    s.leaveVersion++
                    s.pendingAudits +=
                        AuditRecord("leave-delete", id.toString(), "", clock.millis())
                    WorkClassifier.reclassify(s)
                }
            }

            override suspend fun getScoreSettings() = scoreService.get()
            override suspend fun saveScoreSettings(settings: ScoreSettings) = scoreService.save(settings)
            override suspend fun resetScoreSettings() = scoreService.reset()

            override suspend fun getAppearance() = appearanceService.get()
            override suspend fun saveAppearance(settings: AppearanceSettings) = appearanceService.save(settings)
        }
    }

private fun BackendContext.replaceCalendar(imported: CalendarImport): CalendarInfo {
    val m = imported.metadata
    requireField(m.year in 1..9999, "year", "calendar.year.invalid")
    requireField(m.source.isNotBlank(), "source", "calendar.source.required")
    requireField(m.version.isNotBlank(), "version", "calendar.version.required")
    requireField(
        imported.holidays.keys.all { it.year == m.year } &&
            imported.makeupDates.all { it.year == m.year },
        "year",
        "calendar.date.outside_year",
    )
    requireField(
        imported.holidays.values.none { it.isBlank() },
        "holidays",
        "holiday.name.required",
    )
    requireField(
        imported.makeupDates.none { it in imported.holidays },
        "workdays",
        "calendar.conflict",
    )
    try {
        LocalDate.parse(m.published)
    } catch (_: Exception) {
        fail(ErrorCode.VALIDATION, "calendar.published.invalid", "published")
    }
    return mutate { s ->
        if (
            s.calendarYears[m.year] != m ||
                s.holidays.filterKeys { it.year == m.year } != imported.holidays ||
                s.makeupDates.filter { it.year == m.year }.toSet() !=
                    imported.makeupDates
        ) {
            s.calendarYears[m.year] = m
            s.holidays.keys.removeAll { it.year == m.year }
            s.holidays.putAll(imported.holidays)
            s.makeupDates.removeAll { it.year == m.year }
            s.makeupDates.addAll(imported.makeupDates)
            s.pendingAudits +=
                AuditRecord(
                    "calendar",
                    m.year.toString(),
                    imported.rawJson.orEmpty(),
                    clock.millis(),
                )
            WorkClassifier.reclassify(s)
        }
        calendar(s)
    }
}
