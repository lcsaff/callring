package com.greatwall.callring

import com.greatwall.callring.application.ports.BackendStore
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.*

/** Test-only adaptation of deterministic memory fixtures. Production has no legacy fallback. */
internal class MemoryQuietTransactions(private val store: BackendStore) : CoreUnitOfWork {
    private fun reader(state: BackendState) =
        object : CoreWriteSession {
            override fun visitReportCalls(
                number: String,
                start: Long,
                end: Long,
                ascending: Boolean,
                accept: (ReportCallFact) -> Unit,
            ) {
                val order = compareBy<RawCallRecord> { it.time }.thenBy { it.id }
                state.calls.values
                    .filter {
                        !it.hidden && it.number == number && it.time >= start && it.time < end
                    }
                    .sortedWith(if (ascending) order else order.reversed())
                    .forEach {
                        accept(ReportCallFact(it, it.linkedScreenId?.let(state.calls::get)))
                    }
            }

            override fun statisticsEnabled() =
                state.statisticsIntervals.any { it.endMillis == null }

            override fun homeQuietMode() = state.quiet.mode

            override fun latestVisibleReportCall(): ReportCallFact? =
                state.calls.values
                    .filter { !it.hidden }
                    .maxWithOrNull(compareBy<RawCallRecord> { it.time }.thenBy { it.id })
                    ?.let {
                        ReportCallFact(it, it.linkedScreenId?.let(state.calls::get))
                    }

            override fun decisionInputs() = state.decisionInputs()

            override fun statisticsContains(at: Long) =
                state.statisticsIntervals.any {
                    at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                }

            override fun statisticsStart() =
                state.statisticsIntervals.minOfOrNull { it.startMillis } ?: 0L

            override fun hasRecentPending(start: Long, end: Long) =
                state.calls.values.any {
                    !it.hidden &&
                        it.counted &&
                        it.logId == null &&
                        it.time >= start &&
                        it.time < end
                }

            override fun callingWorkHistory() = workHistory()

            override fun screeningCall(key: String) =
                state.calls.values.singleOrNull { it.screeningKey == key }

            override fun callingCall(id: Long) = state.calls[id]

            override fun visibleRecordingLinks(screenId: Long) = state.calls.values.filter { !it.hidden && it.linkedScreenId == screenId }.take(2)

            override fun providerCalls(logIds: Set<String>) =
                state.calls.values.filter { it.logId in logIds }

            override fun correlationCalls(number: String) =
                state.calls.values
                    .filter { it.number == number }
                    .sortedWith(compareBy<RawCallRecord> { it.time }.thenBy { it.id })

            override fun ensureCallingProfile(number: String): Boolean {
                if (state.profiles.containsKey(number)) return false
                state.profiles[number] = ProfileRecord(number)
                return true
            }

            override fun insertCallingCall(row: RawCallRecord): RawCallRecord =
                row.copy(id = state.nextCallId++).also { state.calls[it.id] = it }

            override fun updateCallingCalls(changes: List<Pair<RawCallRecord, RawCallRecord>>) {
                changes.forEach { (_, after) -> state.calls[after.id] = after }
            }

            override fun caller(number: String) = state.profiles[number]

            override fun callerCount() = state.profiles.size.toLong()

            override fun callerPage(offset: Long, limit: Int) =
                state.profiles.values
                    .sortedBy { it.number }
                    .drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .take(limit)

            override fun callers(numbers: List<String>) = numbers.mapNotNull { state.profiles[it] }

            override fun callerPositions(ids: Set<Long>) = ids.mapNotNull { state.positions[it] }

            override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit) {
                state.profiles.values
                    .sortedBy { it.number }
                    .forEach { accept(CallerSearchRow(it.number, it.name, it.department)) }
            }

            override fun putCaller(
                value: com.greatwall.callring.domain.ProfileRecord,
                insert: Boolean,
            ) {
                state.profiles[value.number] = value
            }

            override fun removeCaller(number: String) {
                state.profiles.remove(number)
            }

            override fun matchingCallNumbers(number: String) =
                state.calls.values
                    .map { it.number }
                    .distinct()
                    .filter {
                        com.greatwall.callring.domain.model.NumberIdentity.parse(it).normalized ==
                            number
                    }

            override fun visitCallerCallIdentities(
                numbers: List<String>,
                accept: (Long, String) -> Unit,
            ) {
                state.calls.values
                    .filter { it.number in numbers }
                    .forEach { accept(it.id, it.number) }
            }

            override fun callCount(numbers: List<String>) =
                state.calls.values.count { it.number in numbers }.toLong()

            override fun removeCallerCalls(numbers: List<String>, expectedCount: Long) {
                state.calls.entries.removeAll { it.value.number in numbers }
            }

            override fun callerCall(id: Long) = state.calls[id]

            override fun latestCallerCall() =
                state.calls.values
                    .filter { !it.hidden }
                    .maxWithOrNull(
                        compareBy<com.greatwall.callring.domain.RawCallRecord> { it.time }
                            .thenBy { it.id },
                    )

            override fun visitVisibleCallerCalls(
                number: String,
                accept: (CallerOverviewFact) -> Unit,
            ) {
                state.calls.values
                    .filter { !it.hidden && it.number == number }
                    .sortedWith(
                        compareBy<com.greatwall.callring.domain.RawCallRecord> { it.time }
                            .thenBy { it.id },
                    )
                    .forEach {
                        accept(
                            CallerOverviewFact(
                                it.id,
                                it.time,
                                it.duration,
                                it.result,
                                it.counted,
                                it.classification.working,
                                it.classification.calendarVerified,
                            ),
                        )
                    }
            }

            override fun calendarOverview() =
                CalendarOverview(
                    state.calendarYears.values.sortedBy { it.year },
                    state.overrides.values.sortedBy { it.date },
                )

            override fun calendarYear(year: Int) =
                state.calendarYears[year]?.let {
                    com.greatwall.callring.domain.CalendarImport(
                        it,
                        state.holidays.filterKeys { it.year == year },
                        state.makeupDates.filterTo(linkedSetOf()) { it.year == year },
                    )
                }

            override fun replaceCalendar(value: com.greatwall.callring.domain.CalendarImport) {
                val year = value.metadata.year
                state.calendarYears[year] = value.metadata
                state.holidays.keys.removeAll { it.year == year }
                state.holidays.putAll(value.holidays)
                state.makeupDates.removeAll { it.year == year }
                state.makeupDates.addAll(value.makeupDates)
            }

            override fun dateOverride(date: java.time.LocalDate) = state.overrides[date]

            override fun putDateOverride(value: com.greatwall.callring.domain.model.DateOverride) {
                state.overrides[value.date] = value
            }

            override fun removeDateOverride(date: java.time.LocalDate) {
                state.overrides.remove(date)
            }

            override fun leaves() =
                state.leaves.values.sortedWith(
                    compareByDescending<com.greatwall.callring.domain.LeaveRecord> {
                            it.startMillis
                        }
                        .thenByDescending { it.id },
                )

            override fun leave(id: Long) = state.leaves[id]

            override fun putLeave(
                value: com.greatwall.callring.domain.LeaveRecord,
                insert: Boolean,
            ): com.greatwall.callring.domain.LeaveRecord {
                val row = if (insert) value.copy(id = state.nextLeaveId++) else value
                state.leaves[row.id] = row
                return row
            }

            override fun removeLeave(id: Long) {
                state.leaves.remove(id)
            }

            override fun incrementLeaveVersion() {
                state.leaveVersion++
            }

            override fun appendAudit(value: com.greatwall.callring.domain.AuditRecord) {
                state.pendingAudits += value
            }

            override fun workHistory() =
                WorkHistory(
                    state.schedules.toList(),
                    state.calendarYears.toMap(),
                    state.holidays.toMap(),
                    state.makeupDates.toSet(),
                    state.overrides.toMap(),
                    state.leaves.toMap(),
                    state.leaveVersion,
                )

            override fun classificationBatch(afterId: Long, limit: Int) =
                state.calls.values
                    .filter { it.id > afterId }
                    .sortedBy { it.id }
                    .take(limit)
                    .map { HistoricalClassification(it.id, it.number, it.time, it.classification) }

            override fun updateClassification(
                before: HistoricalClassification,
                current: com.greatwall.callring.domain.model.ClassificationFact,
            ) {
                state.calls[before.id] =
                    state.calls.getValue(before.id).copy(classification = current)
            }

            override fun latestWorkSchedule() =
                state.schedules.maxWithOrNull(
                    compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                            it.effectiveFromEpochMillis
                        }
                        .thenBy { it.configVersion },
                )

            override fun appendWorkSchedule(
                value: com.greatwall.callring.domain.model.WorkScheduleSettings,
            ): com.greatwall.callring.domain.model.WorkScheduleSettings {
                val saved = value.copy(configVersion = state.nextConfigVersion++)
                state.schedules += saved
                return saved
            }

            override fun score() = state.score

            override fun saveScore(
                value: com.greatwall.callring.domain.model.ScoreSettings,
            ): Boolean {
                val changed = state.score != value
                state.score = value
                return changed
            }

            override fun overlay() = state.overlayEnabled

            override fun saveOverlay(value: Boolean): Boolean {
                val changed = state.overlayEnabled != value
                state.overlayEnabled = value
                return changed
            }

            override fun statisticsHead() =
                StatisticsHead(
                    state.statisticsIntervals
                        .withIndex()
                        .lastOrNull { it.value.endMillis == null }
                        ?.let {
                            OpenStatistics(it.index.toLong(), it.value)
                        },
                    state.statisticsIntervals.mapNotNull { it.endMillis }.maxOrNull(),
                )

            override fun openStatistics(at: Long) {
                state.statisticsIntervals +=
                    com.greatwall.callring.domain.StatisticsInterval(at, null)
            }

            override fun closeStatistics(id: Long, at: Long) {
                state.statisticsIntervals[id.toInt()] =
                    state.statisticsIntervals[id.toInt()].copy(endMillis = at)
            }

            override fun positions() = state.positions.values.toList()

            override fun position(id: Long) = state.positions[id]

            override fun myPositionId() = state.myPositionId

            override fun positionNameExists(name: String, except: Long?) =
                state.positions.values.any { it.name == name && it.id != except }

            override fun positionReferenced(id: Long) =
                state.profiles.values.any { it.positionId == id }

            override fun positionNumbers(id: Long) =
                state.profiles.values
                    .filter { it.positionId == id }
                    .mapTo(linkedSetOf()) { it.number }

            override fun putPosition(
                value: com.greatwall.callring.domain.PositionRecord,
                insert: Boolean,
            ): com.greatwall.callring.domain.PositionRecord {
                val saved = if (insert) value.copy(id = state.nextPositionId++) else value
                state.positions[saved.id] = saved
                return saved
            }

            override fun deletePosition(id: Long) {
                state.positions.remove(id)
            }

            override fun saveMyPosition(id: Long?) {
                state.myPositionId = id
            }

            override fun settingsSummary() =
                CoreSettingsSummary(
                    state.statisticsIntervals.any { it.endMillis == null },
                    state.overlayEnabled,
                    state.quiet.mode,
                    state.positions[state.myPositionId],
                    state.schedules
                        .maxWithOrNull(
                            compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                                    it.effectiveFromEpochMillis
                                }
                                .thenBy { it.configVersion },
                        )
                        ?.zoneId ?: com.greatwall.callring.domain.defaultWorkSchedule().zoneId,
                    state.score,
                )

            override fun quiet() = QuietConfiguration(state.quiet, state.lastQuietMode)

            override fun saveQuiet(value: QuietConfiguration): Boolean {
                val changed = quiet() != value
                state.quiet = value.settings
                state.lastQuietMode = value.lastMode
                return changed
            }
        }

    override fun <T> readSession(block: (CoreReadSession) -> T): T = store.read {
        block(reader(it))
    }

    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = store.transaction {
        block(reader(it))
    }

    override fun <T> readQuiet(block: (QuietReader) -> T): T = store.read { block(reader(it)) }

    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = store.transaction {
        block(reader(it))
    }
}
