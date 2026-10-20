package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.model.*
import org.json.JSONObject

/** All capabilities share the same connection and transaction. Nothing live leaves the callback. */
internal class SqliteCoreUnitOfWork(private val db: SQLiteDatabase, private val sql: SqlAccess) :
    CoreUnitOfWork {
    private val callingCounts =
        listOf(
                "statisticsContains",
                "statisticsStart",
                "hasRecentPending",
                "callingWorkHistory",
                "screeningCall",
                "callingCall",
                "providerCalls",
                "correlationCalls",
                "ensureCallingProfile",
                "insertCallingCall",
                "updateCallingCalls",
                "decisionInputs",
            )
            .associateWith { java.util.concurrent.atomic.AtomicLong() }

    fun callingAccessCounts(): Map<String, Long> = callingCounts.mapValues { it.value.get() }

    private fun <T> callingAccess(label: String, block: () -> T): T {
        callingCounts.getValue(label).incrementAndGet()
        return block()
    }

    val quietReads = java.util.concurrent.atomic.AtomicLong()
    val quietWrites = java.util.concurrent.atomic.AtomicLong()

    override fun <T> readQuiet(block: (QuietReader) -> T): T = readSession { block(it) }

    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = writeSession { block(it) }

    override fun <T> readSession(block: (CoreReadSession) -> T): T = session(false, block)

    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = session(true, block)

    @Synchronized
    private fun <T> session(write: Boolean, block: (CoreWriteSession) -> T): T {
        if (write) db.beginTransaction() else db.beginTransactionNonExclusive()
        val session = Session(write)
        try {
            return block(session).also { db.setTransactionSuccessful() }
        } finally {
            session.active = false
            db.endTransaction()
        }
    }

    private inner class Session(private val writable: Boolean) : CoreWriteSession {
        var active = true
        private var loadedQuiet: QuietConfiguration? = null
        private var storedLastMode = QuietMode.NON_WORK

        private fun checkActive(write: Boolean = false) {
            check(active) { "storage.session.closed" }
            check(!write || writable) { "storage.session.read_only" }
        }

        override fun quiet(): QuietConfiguration {
            checkActive()
            quietReads.incrementAndGet()
            val settings =
                sql.query(
                        QueryTag.SCOPED_SETTING,
                        "settings",
                        arrayOf("key", "value"),
                        "key IN (?,?,?,?)",
                        arrayOf("quiet_mode", "all_numbers", "quiet_windows", "last_quiet_mode"),
                        null,
                        null,
                        null,
                    )
                    .use { cursor ->
                        buildMap {
                            while (cursor.moveToNext()) put(
                                cursor.getString(0),
                                cursor.getString(1),
                            )
                        }
                    }
            val mode = QuietMode.valueOf(settings["quiet_mode"] ?: "OFF")
            val quiet =
                QuietSettings(
                    mode,
                    settings["all_numbers"] == "1",
                    WorkConfigCodec.weekly(JSONObject(settings["quiet_windows"] ?: "{}")),
                )
            storedLastMode =
                runCatching { QuietMode.valueOf(settings["last_quiet_mode"] ?: "NON_WORK") }
                    .getOrDefault(QuietMode.NON_WORK)
                    .takeUnless { it == QuietMode.OFF } ?: QuietMode.NON_WORK
            val last =
                if (settings["last_quiet_mode"] == null && mode != QuietMode.OFF) mode
                else storedLastMode
            return QuietConfiguration(quiet, last).also { loadedQuiet = it }
        }

        override fun saveQuiet(value: QuietConfiguration): Boolean {
            checkActive(true)
            quietWrites.incrementAndGet()
            val before = loadedQuiet ?: quiet()
            val changed = linkedMapOf<String, String>()
            if (before.settings.mode != value.settings.mode)
                changed["quiet_mode"] = value.settings.mode.name
            if (storedLastMode != value.lastMode) changed["last_quiet_mode"] = value.lastMode.name
            if (before.settings.allNumbers != value.settings.allNumbers)
                changed["all_numbers"] = if (value.settings.allNumbers) "1" else "0"
            val windows = WorkConfigCodec.weeklyJson(value.settings.weeklyWindows).toString()
            if (WorkConfigCodec.weeklyJson(before.settings.weeklyWindows).toString() != windows)
                changed["quiet_windows"] = windows
            changed.forEach { (key, value) ->
                val updated =
                    sql.update("settings", values("value" to value), "key=?", arrayOf(key))
                if (updated == 0) sql.insert("settings", values("key" to key, "value" to value))
            }
            loadedQuiet = value
            storedLastMode = value.lastMode
            return changed.isNotEmpty()
        }

        private val calling =
            com.greatwall.callring.data.calling.SqliteCallStore(sql, ::checkActive)

        override fun statisticsContains(at: Long) =
            callingAccess("statisticsContains") { calling.statisticsContains(at) }

        override fun statisticsStart() =
            callingAccess("statisticsStart") { calling.statisticsStart() }

        override fun hasRecentPending(start: Long, end: Long) =
            callingAccess("hasRecentPending") { calling.hasRecentPending(start, end) }

        override fun callingWorkHistory() =
            callingAccess("callingWorkHistory") { calling.callingWorkHistory() }

        override fun screeningCall(key: String) =
            callingAccess("screeningCall") { calling.screeningCall(key) }

        override fun callingCall(id: Long) =
            callingAccess("callingCall") { calling.callingCall(id) }

        override fun providerCalls(logIds: Set<String>) =
            callingAccess("providerCalls") { calling.providerCalls(logIds) }

        override fun correlationCalls(number: String) =
            callingAccess("correlationCalls") { calling.correlationCalls(number) }

        override fun ensureCallingProfile(number: String) =
            callingAccess("ensureCallingProfile") { calling.ensureCallingProfile(number) }

        override fun insertCallingCall(row: com.greatwall.callring.domain.RawCallRecord) =
            callingAccess("insertCallingCall") { calling.insertCallingCall(row) }

        override fun updateCallingCalls(
            changes:
                List<
                    Pair<
                        com.greatwall.callring.domain.RawCallRecord,
                        com.greatwall.callring.domain.RawCallRecord,
                    >,
                >,
        ) = callingAccess("updateCallingCalls") { calling.updateCallingCalls(changes) }

        override fun decisionInputs(): DecisionInputs {
            checkActive()
            callingCounts.getValue("decisionInputs").incrementAndGet()
            val work = callingWorkHistory()
            val whitelist =
                sql.query(
                        QueryTag.SCOPED_ROWS,
                        "callers",
                        arrayOf("number"),
                        "whitelist=1",
                        null,
                        null,
                        null,
                        "number",
                    )
                    .use { c ->
                        buildSet { while (c.moveToNext()) add(c.getString(0)) }
                    }
            val intervals =
                sql.query(
                        QueryTag.SCOPED_ROWS,
                        "statistics_intervals",
                        arrayOf("start", "end"),
                        null,
                        null,
                        null,
                        null,
                        "id",
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) add(
                                com.greatwall.callring.domain.StatisticsInterval(
                                    c.getLong(0),
                                    if (c.isNull(1)) null else c.getLong(1),
                                ),
                            )
                        }
                    }
            return DecisionInputs(
                work.schedules,
                work.calendarYears,
                work.holidays,
                work.makeupDates,
                work.overrides,
                work.leaves,
                work.leaveVersion,
                whitelist,
                quiet().settings,
                intervals,
                overlay(),
            )
        }

        private val callers =
            com.greatwall.callring.data.callers.SqliteCallerStore(sql, ::checkActive)

        override fun callerPositions(ids: Set<Long>) = callers.callerPositions(ids)

        override fun caller(number: String) = callers.caller(number)

        override fun callerCount() = callers.callerCount()

        override fun callerPage(offset: Long, limit: Int) = callers.callerPage(offset, limit)

        override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit) =
            callers.visitCallerSearch(accept)

        override fun callers(numbers: List<String>) = callers.callers(numbers)

        override fun putCaller(
            value: com.greatwall.callring.domain.ProfileRecord,
            insert: Boolean,
        ) = callers.putCaller(value, insert)

        override fun removeCaller(number: String) = callers.removeCaller(number)

        override fun matchingCallNumbers(number: String) = callers.matchingCallNumbers(number)

        override fun visitCallerCallIdentities(
            numbers: List<String>,
            accept: (Long, String) -> Unit,
        ) = callers.visitCallerCallIdentities(numbers, accept)

        override fun callCount(numbers: List<String>) = callers.callCount(numbers)

        override fun removeCallerCalls(numbers: List<String>, expectedCount: Long) =
            callers.removeCallerCalls(numbers, expectedCount)

        override fun callerCall(id: Long) = callers.callerCall(id)

        override fun latestCallerCall() = callers.latestCallerCall()

        override fun visitVisibleCallerCalls(number: String, accept: (CallerOverviewFact) -> Unit) =
            callers.visitVisibleCallerCalls(number, accept)

        private val history =
            com.greatwall.callring.data.settings.SqliteHistorySettings(sql, ::checkActive)

        override fun calendarOverview() = history.calendarOverview()

        override fun calendarYear(year: Int) = history.calendarYear(year)

        override fun replaceCalendar(value: com.greatwall.callring.domain.CalendarImport) =
            history.replaceCalendar(value)

        override fun dateOverride(date: java.time.LocalDate) = history.dateOverride(date)

        override fun putDateOverride(value: DateOverride) = history.putDateOverride(value)

        override fun removeDateOverride(date: java.time.LocalDate) =
            history.removeDateOverride(date)

        override fun leaves() = history.leaves()

        override fun leave(id: Long) = history.leave(id)

        override fun putLeave(value: com.greatwall.callring.domain.LeaveRecord, insert: Boolean) =
            history.putLeave(value, insert)

        override fun removeLeave(id: Long) = history.removeLeave(id)

        override fun incrementLeaveVersion() = history.incrementLeaveVersion()

        override fun appendAudit(value: com.greatwall.callring.domain.AuditRecord) =
            history.appendAudit(value)

        override fun workHistory() = history.workHistory()

        override fun classificationBatch(afterId: Long, limit: Int) =
            history.classificationBatch(afterId, limit)

        override fun updateClassification(
            before: HistoricalClassification,
            current: ClassificationFact,
        ) = history.updateClassification(before, current)

        private val work =
            com.greatwall.callring.data.settings.SqliteWorkSchedule(sql, ::checkActive)

        override fun latestWorkSchedule() = work.latestWorkSchedule()

        override fun appendWorkSchedule(value: WorkScheduleSettings) =
            work.appendWorkSchedule(value)

        private val settings =
            com.greatwall.callring.data.settings.SqliteSimpleSettings(sql, ::checkActive)

        override fun score() = settings.score()

        override fun saveScore(value: ScoreSettings) = settings.saveScore(value)

        override fun overlay() = settings.overlay()

        override fun saveOverlay(value: Boolean) = settings.saveOverlay(value)

        override fun statisticsHead() = settings.statisticsHead()

        override fun openStatistics(at: Long) = settings.openStatistics(at)

        override fun closeStatistics(id: Long, at: Long) = settings.closeStatistics(id, at)

        override fun positions() = settings.positions()

        override fun position(id: Long) = settings.position(id)

        override fun myPositionId() = settings.myPositionId()

        override fun positionNameExists(name: String, except: Long?) =
            settings.positionNameExists(name, except)

        override fun positionReferenced(id: Long) = settings.positionReferenced(id)

        override fun positionNumbers(id: Long) = settings.positionNumbers(id)

        override fun putPosition(value: PositionRecord, insert: Boolean) =
            settings.putPosition(value, insert)

        override fun deletePosition(id: Long) = settings.deletePosition(id)

        override fun saveMyPosition(id: Long?) = settings.saveMyPosition(id)

        override fun settingsSummary() = settings.settingsSummary()
    }
}
