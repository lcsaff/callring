package com.greatwall.callring

import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.CalendarImport
import com.greatwall.callring.domain.model.LeaveRecord
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.StatisticsInterval

import com.greatwall.callring.domain.model.defaultWorkSchedule
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*

/** Deterministic in-memory implementation of the same narrow session ports. */
internal class MemoryCoreUnitOfWork : CoreUnitOfWork {
    val decision = DecisionFixture()
    val people = linkedMapOf<String, ProfileRecord>()
    val roles = linkedMapOf<Long, PositionRecord>()
    val calls = linkedMapOf<Long, RawCallRecord>()
    val audits = mutableListOf<AuditRecord>()
    var selectedRole: Long? = null
    var lastMode = QuietMode.NON_WORK
    var scoreSettings = ScoreSettings(5, 2, 10, 30)
    var nextCall = 1L
    var nextRole = 1L
    var nextLeave = 1L
    var nextSchedule = 1L
    var failCommit = false
    private val session =
        object : CoreWriteSession {
            override fun visitReportCalls(
                number: String,
                start: Long,
                end: Long,
                ascending: Boolean,
                accept: (ReportCallFact) -> Unit,
            ) {
                val order = compareBy<RawCallRecord> { it.time }.thenBy { it.id }
                calls.values
                    .filter {
                        !it.hidden && it.number == number && it.time >= start && it.time < end
                    }
                    .sortedWith(if (ascending) order else order.reversed())
                    .forEach {
                        accept(ReportCallFact(it, it.linkedScreenId?.let(calls::get)))
                    }
            }

            override fun statisticsEnabled() =
                decision.intervals.any { it.endMillis == null }

            override fun homeQuietMode() = decision.quiet.mode

            override fun latestVisibleReportCall(): ReportCallFact? =
                calls.values
                    .filter { !it.hidden }
                    .maxWithOrNull(compareBy<RawCallRecord> { it.time }.thenBy { it.id })
                    ?.let {
                        ReportCallFact(it, it.linkedScreenId?.let(calls::get))
                    }

            override fun decisionInputs() = decision.decisionInputs().copy(whitelistedNumbers = people.filterValues { it.whitelisted }.keys)

            override fun statisticsContains(at: Long) =
                decision.intervals.any {
                    at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                }

            override fun statisticsStart() =
                decision.intervals.minOfOrNull { it.startMillis } ?: 0L

            override fun hasRecentPending(start: Long, end: Long) =
                calls.values.any {
                    !it.hidden &&
                        it.counted &&
                        it.logId == null &&
                        it.time >= start &&
                        it.time < end
                }

            override fun callingWorkHistory() = workHistory()

            override fun screeningCall(key: String) =
                calls.values.singleOrNull { it.screeningKey == key }

            override fun callingCall(id: Long) = calls[id]

            override fun visibleRecordingLinks(screenId: Long) = calls.values.filter { !it.hidden && it.linkedScreenId == screenId }.take(2)

            override fun providerCalls(logIds: Set<String>) =
                calls.values.filter { it.logId in logIds }

            override fun correlationCalls(number: String) =
                calls.values
                    .filter { it.number == number }
                    .sortedWith(compareBy<RawCallRecord> { it.time }.thenBy { it.id })

            override fun ensureCallingProfile(number: String): Boolean {
                if (people.containsKey(number)) return false
                people[number] = ProfileRecord(number)
                return true
            }

            override fun insertCallingCall(row: RawCallRecord): RawCallRecord =
                row.copy(id = nextCall++).also { calls[it.id] = it }

            override fun updateCallingCalls(changes: List<Pair<RawCallRecord, RawCallRecord>>) {
                changes.forEach { (_, after) -> calls[after.id] = after }
            }

            override fun caller(number: String) = people[number]

            override fun callerCount() = people.size.toLong()

            override fun callerPage(offset: Long, limit: Int) =
                people.values
                    .sortedBy { it.number }
                    .drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .take(limit)

            override fun callers(numbers: List<String>) = numbers.mapNotNull { people[it] }

            override fun callerPositions(ids: Set<Long>) = ids.mapNotNull { roles[it] }

            override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit) {
                people.values
                    .sortedBy { it.number }
                    .forEach { accept(CallerSearchRow(it.number, it.name, it.department)) }
            }

            override fun putCaller(
                value: com.greatwall.callring.domain.model.ProfileRecord,
                insert: Boolean,
            ) {
                people[value.number] = value
            }

            override fun removeCaller(number: String) {
                people.remove(number)
            }

            override fun matchingCallNumbers(number: String) =
                calls.values
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
                calls.values
                    .filter { it.number in numbers }
                    .forEach { accept(it.id, it.number) }
            }

            override fun callCount(numbers: List<String>) =
                calls.values.count { it.number in numbers }.toLong()

            override fun removeCallerCalls(numbers: List<String>, expectedCount: Long) {
                calls.entries.removeAll { it.value.number in numbers }
            }

            override fun callerCall(id: Long) = calls[id]

            override fun latestCallerCall() =
                calls.values
                    .filter { !it.hidden }
                    .maxWithOrNull(
                        compareBy<com.greatwall.callring.domain.model.RawCallRecord> { it.time }
                            .thenBy { it.id },
                    )

            override fun visitVisibleCallerCalls(
                number: String,
                accept: (CallerOverviewFact) -> Unit,
            ) {
                calls.values
                    .filter { !it.hidden && it.number == number }
                    .sortedWith(
                        compareBy<com.greatwall.callring.domain.model.RawCallRecord> { it.time }
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
                    decision.calendarYears.values.sortedBy { it.year },
                    decision.overrides.values.sortedBy { it.date },
                )

            override fun calendarYear(year: Int) =
                decision.calendarYears[year]?.let {
                    com.greatwall.callring.domain.model.CalendarImport(
                        it,
                        decision.holidays.filterKeys { it.year == year },
                        decision.makeupDates.filterTo(linkedSetOf()) { it.year == year },
                    )
                }

            override fun replaceCalendar(value: com.greatwall.callring.domain.model.CalendarImport) {
                val year = value.metadata.year
                decision.calendarYears[year] = value.metadata
                decision.holidays.keys.removeAll { it.year == year }
                decision.holidays.putAll(value.holidays)
                decision.makeupDates.removeAll { it.year == year }
                decision.makeupDates.addAll(value.makeupDates)
            }

            override fun dateOverride(date: java.time.LocalDate) = decision.overrides[date]

            override fun putDateOverride(value: com.greatwall.callring.domain.model.DateOverride) {
                decision.overrides[value.date] = value
            }

            override fun removeDateOverride(date: java.time.LocalDate) {
                decision.overrides.remove(date)
            }

            override fun leaves() =
                decision.leaves.values.sortedWith(
                    compareByDescending<com.greatwall.callring.domain.model.LeaveRecord> {
                            it.startMillis
                        }
                        .thenByDescending { it.id },
                )

            override fun leave(id: Long) = decision.leaves[id]

            override fun putLeave(
                value: com.greatwall.callring.domain.model.LeaveRecord,
                insert: Boolean,
            ): com.greatwall.callring.domain.model.LeaveRecord {
                val row = if (insert) value.copy(id = nextLeave++) else value
                decision.leaves[row.id] = row
                return row
            }

            override fun removeLeave(id: Long) {
                decision.leaves.remove(id)
            }

            override fun incrementLeaveVersion() {
                decision.leaveVersion++
            }

            override fun appendAudit(value: com.greatwall.callring.domain.model.AuditRecord) {
                audits += value
            }

            override fun workHistory() =
                WorkHistory(
                    decision.schedules.toList(),
                    decision.calendarYears.toMap(),
                    decision.holidays.toMap(),
                    decision.makeupDates.toSet(),
                    decision.overrides.toMap(),
                    decision.leaves.toMap(),
                    decision.leaveVersion,
                )

            override fun classificationBatch(afterId: Long, limit: Int) =
                calls.values
                    .filter { it.id > afterId }
                    .sortedBy { it.id }
                    .take(limit)
                    .map { HistoricalClassification(it.id, it.number, it.time, it.classification) }

            override fun updateClassification(
                before: HistoricalClassification,
                current: com.greatwall.callring.domain.model.ClassificationFact,
            ) {
                calls[before.id] =
                    calls.getValue(before.id).copy(classification = current)
            }

            override fun latestWorkSchedule() =
                decision.schedules.maxWithOrNull(
                    compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                            it.effectiveFromEpochMillis
                        }
                        .thenBy { it.configVersion },
                )

            override fun appendWorkSchedule(
                value: com.greatwall.callring.domain.model.WorkScheduleSettings,
            ): com.greatwall.callring.domain.model.WorkScheduleSettings {
                val saved = value.copy(configVersion = nextSchedule++)
                decision.schedules += saved
                return saved
            }

            override fun score() = scoreSettings

            override fun saveScore(
                value: com.greatwall.callring.domain.model.ScoreSettings,
            ): Boolean {
                val changed = scoreSettings != value
                scoreSettings = value
                return changed
            }

            override fun overlay() = decision.overlay

            override fun saveOverlay(value: Boolean): Boolean {
                val changed = decision.overlay != value
                decision.overlay = value
                return changed
            }

            override fun statisticsHead() =
                StatisticsHead(
                    decision.intervals
                        .withIndex()
                        .lastOrNull { it.value.endMillis == null }
                        ?.let {
                            OpenStatistics(it.index.toLong(), it.value)
                        },
                    decision.intervals.mapNotNull { it.endMillis }.maxOrNull(),
                )

            override fun openStatistics(at: Long) {
                decision.intervals +=
                    com.greatwall.callring.domain.model.StatisticsInterval(at, null)
            }

            override fun closeStatistics(id: Long, at: Long) {
                decision.intervals[id.toInt()] =
                    decision.intervals[id.toInt()].copy(endMillis = at)
            }

            override fun positions() = roles.values.toList()

            override fun position(id: Long) = roles[id]

            override fun myPositionId() = selectedRole

            override fun positionNameExists(name: String, except: Long?) =
                roles.values.any { it.name == name && it.id != except }

            override fun positionReferenced(id: Long) =
                people.values.any { it.positionId == id }

            override fun positionNumbers(id: Long) =
                people.values
                    .filter { it.positionId == id }
                    .mapTo(linkedSetOf()) { it.number }

            override fun putPosition(
                value: com.greatwall.callring.domain.model.PositionRecord,
                insert: Boolean,
            ): com.greatwall.callring.domain.model.PositionRecord {
                val saved = if (insert) value.copy(id = nextRole++) else value
                roles[saved.id] = saved
                return saved
            }

            override fun deletePosition(id: Long) {
                roles.remove(id)
            }

            override fun saveMyPosition(id: Long?) {
                selectedRole = id
            }

            override fun settingsSummary() =
                CoreSettingsSummary(
                    decision.intervals.any { it.endMillis == null },
                    decision.overlay,
                    decision.quiet.mode,
                    roles[selectedRole],
                    decision.schedules
                        .maxWithOrNull(
                            compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                                    it.effectiveFromEpochMillis
                                }
                                .thenBy { it.configVersion },
                        )
                        ?.zoneId ?: com.greatwall.callring.domain.model.defaultWorkSchedule().zoneId,
                    scoreSettings,
                )

            override fun quiet() = QuietConfiguration(decision.quiet, lastMode)

            override fun saveQuiet(value: QuietConfiguration): Boolean {
                val changed = quiet() != value
                decision.quiet = value.settings
                lastMode = value.lastMode
                return changed
            }
        }

    override fun <T> readSession(block: (CoreReadSession) -> T): T = synchronized(this) { block(session) }
    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = synchronized(this) {
        val undo = mutableListOf<() -> Unit>()
        fun <K, V> remember(map: MutableMap<K, V>) {
            val saved = map.toMap(); undo += { map.clear(); map.putAll(saved) }
        }
        fun <T> remember(list: MutableList<T>) {
            val saved = list.toList(); undo += { list.clear(); list.addAll(saved) }
        }
        remember(people); remember(roles); remember(calls); remember(audits)
        remember(decision.schedules); remember(decision.calendarYears); remember(decision.holidays)
        remember(decision.overrides); remember(decision.leaves); remember(decision.intervals)
        val makeup = decision.makeupDates.toSet()
        val quiet = decision.quiet; val overlay = decision.overlay; val leaveVersion = decision.leaveVersion
        val selected = selectedRole; val mode = lastMode; val score = scoreSettings
        val callId = nextCall; val roleId = nextRole; val leaveId = nextLeave; val scheduleId = nextSchedule
        try {
            val result = block(session)
            if (failCommit) throw IllegalStateException("test.commit_failure")
            result
        } catch (failure: Throwable) {
            undo.forEach { it() }
            decision.makeupDates.clear(); decision.makeupDates.addAll(makeup)
            decision.quiet = quiet; decision.overlay = overlay; decision.leaveVersion = leaveVersion
            selectedRole = selected; lastMode = mode; scoreSettings = score
            nextCall = callId; nextRole = roleId; nextLeave = leaveId; nextSchedule = scheduleId
            throw failure
        }
    }

    override fun <T> readQuiet(block: (QuietReader) -> T): T = readSession { block(it) }
    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = writeSession { block(it) }
}
