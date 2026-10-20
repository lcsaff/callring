package com.greatwall.callring.application

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
            override suspend fun getSummary(): ApiResult<SettingsSummary> = io {
                val appearance = preferences.getAppearance()
                read { s ->
                    SettingsSummary(
                        stats(s),
                        s.overlayEnabled,
                        s.quiet.mode,
                        s.positions[s.myPositionId]?.let { option(s, it) },
                        currentSchedule(s).zoneId,
                        s.score,
                        appearance,
                        meta(),
                    )
                }
            }

            override suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit> = write {
                val changed = mutate { s ->
                    if (stats(s) == enabled) false
                    else {
                        var at = clock.millis()
                        if (enabled) {
                            at =
                                maxOf(
                                    at,
                                    s.statisticsIntervals.mapNotNull { it.endMillis }.maxOrNull()
                                        ?: Long.MIN_VALUE,
                                )
                            s.statisticsIntervals += StatisticsInterval(at, null)
                        } else {
                            val index = s.statisticsIntervals.indexOfLast { it.endMillis == null }
                            val interval = s.statisticsIntervals[index]
                            s.statisticsIntervals[index] =
                                interval.copy(endMillis = maxOf(at, interval.startMillis))
                        }
                        true
                    }
                }
                if (enabled && changed)
                    try {
                        syncScheduler.requestSync(clock.millis())
                    } catch (_: Exception) {
                        /* scheduling failure cannot roll back an already committed statistics interval */
                    }
                Unit
            }

            override suspend fun setOverlayEnabled(enabled: Boolean): ApiResult<Unit> = write {
                if (enabled) capability(Capability.OVERLAY)
                mutate { it.overlayEnabled = enabled }
            }

            override suspend fun getQuietHours(): ApiResult<QuietSettings> = io {
                read { quietCopy(it.quiet) }
            }

            override suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings> =
                write {
                    validateQuiet(settings)
                    val copy = quietCopy(settings)
                    mutate {
                        updateQuiet(it, copy)
                    }
                }

            override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> = write {
                mutate { s ->
                    val q = s.quiet.copy(mode = mode)
                    updateQuiet(s, q)
                }
            }

            override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> = write {
                mutate { s ->
                    updateQuiet(s, s.quiet.copy(mode = if (enabled) resumedQuietMode(s) else QuietMode.OFF))
                }
            }

            override suspend fun getPositions(): ApiResult<PositionSettings> = io {
                read { PositionSettings(options(it), it.myPositionId) }
            }

            override suspend fun savePosition(command: SavePosition): ApiResult<PositionOption> =
                write {
                    val name = command.name.trim()
                    requireField(name.isNotEmpty(), "name", "name.required")
                    requireField(command.level > 0, "level", "level.invalid")
                    mutate { s ->
                        requireField(command.id == null || requireNotNull(command.id) > 0, "id", "id.invalid")
                        if (command.id != null && command.id !in s.positions)
                            fail(ErrorCode.NOT_FOUND, "position.not_found")
                        requireField(
                            s.positions.values.none { it.name == name && it.id != command.id },
                            "name",
                            "position.name.duplicate",
                        )
                        val id = command.id ?: s.nextPositionId++
                        val p = PositionRecord(id, name, command.level, command.active)
                        s.positions[id] = p
                        option(s, p)
                    }
                }

            override suspend fun deletePosition(id: Long): ApiResult<Unit> = write {
                mutate { s ->
                    if (id !in s.positions) fail(ErrorCode.NOT_FOUND, "position.not_found")
                    if (s.myPositionId == id || s.profiles.values.any { it.positionId == id })
                        fail(ErrorCode.IN_USE, "position.in_use")
                    s.positions.remove(id)
                    Unit
                }
            }

            override suspend fun setMyPosition(id: Long?): ApiResult<PositionSettings> = write {
                mutate { s ->
                    validatePosition(s, id, s.myPositionId)
                    s.myPositionId = id
                    PositionSettings(options(s), id)
                }
            }

            override suspend fun getWorkSchedule(): ApiResult<WorkScheduleSettings> = io {
                read { scheduleCopy(currentSchedule(it)) }
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
                    s.schedules += c
                    c
                }
            }

            override suspend fun resetWorkSchedule(): ApiResult<WorkScheduleSettings> {
                val defaults = defaultWorkSchedule()
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
                    validateWindows(command.windows, "windows")
                    requireField(
                        if (command.kind == DateOverrideKind.REST) command.windows.isEmpty()
                        else command.windows.isNotEmpty(),
                        "windows",
                        "override.windows.invalid",
                    )
                    val copy = command.copy(windows = frozenList(command.windows))
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
                            requireField(
                                !period.endDateInclusive.isBefore(period.startDate),
                                "period.endDateInclusive",
                                "range.order.invalid",
                            )
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
                requireField(bounds.second > bounds.first, "period", "leave.range.invalid")
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

            override suspend fun getScoreSettings(): ApiResult<ScoreSettings> = io {
                read { it.score }
            }

            override suspend fun saveScoreSettings(
                settings: ScoreSettings
            ): ApiResult<ScoreSettings> = write {
                requireField(settings.offHoursWeight >= 0, "offHoursWeight", "weight.invalid")
                requireField(settings.repeatWeight >= 0, "repeatWeight", "weight.invalid")
                requireField(settings.repeatMinutes > 0, "repeatMinutes", "repeat_minutes.invalid")
                requireField(
                    settings.observationDays > 0,
                    "observationDays",
                    "observation_days.invalid",
                )
                mutate {
                    it.score = settings
                    settings
                }
            }

            override suspend fun resetScoreSettings(): ApiResult<ScoreSettings> =
                saveScoreSettings(ScoreSettings(5, 2, 10, 30))

            override suspend fun getAppearance(): ApiResult<AppearanceSettings> = io {
                preferences.getAppearance()
            }

            override suspend fun saveAppearance(
                settings: AppearanceSettings
            ): ApiResult<AppearanceSettings> = write { saveAppearanceExact(settings, preserveNull = true) }
        }
    }

internal fun BackendContext.saveAppearanceExact(settings: AppearanceSettings, preserveNull: Boolean,baseline:AppearanceSettings?=null,dirtyFields:Set<String>?=null): AppearanceSettings {
    return synchronized(lock) {
        coordinator.check(commandContext)
        val oldAppearance=preferences.getAppearance()
        fun <T> merged(field:String,wanted:T,current:T,base:T):T {
            if(dirtyFields==null)return wanted
            if(field !in dirtyFields)return current
            requireField(base==current,field,"draft.field_changed")
            return wanted
        }
        requireField(dirtyFields==null||baseline!=null,"baseline","draft.baseline_required")
        val desired=if(baseline==null)settings else AppearanceSettings(merged("selection",settings.selection,oldAppearance.selection,baseline.selection),merged("presetId",settings.presetId,oldAppearance.presetId,baseline.presetId),merged("customSeedRgb",settings.customSeedRgb,oldAppearance.customSeedRgb,baseline.customSeedRgb),merged("colorMode",settings.colorMode,oldAppearance.colorMode,baseline.colorMode))
        val saved = if (preserveNull && desired.customSeedRgb == null) desired.copy(customSeedRgb = oldAppearance.customSeedRgb) else desired
        requireField(saved.presetId.isNotBlank(), "presetId", "preset.required")
        requireField(saved.customSeedRgb == null || saved.customSeedRgb in 0..0xffffff, "customSeedRgb", "rgb.invalid")
        requireField(saved.selection != ThemeSelection.CUSTOM || saved.customSeedRgb != null, "customSeedRgb", "rgb.required")
        preferences.saveAppearance(saved)
        coordinator.publishScopes(changedScopeKeys(appearanceScopeValues(oldAppearance),appearanceScopeValues(saved)))
        notifyChanged()
        coordinator.record(commandContext)
        saved
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
