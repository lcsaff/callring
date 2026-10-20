package com.greatwall.callring.data

import com.greatwall.callring.domain.model.WorkScheduleSettings
import com.greatwall.callring.domain.model.CalendarYear
import com.greatwall.callring.domain.model.DateOverride
import com.greatwall.callring.domain.model.DateOverrideKind
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.LeaveRecord
import java.time.LocalDate
import org.json.JSONArray

internal class ScheduleStore(private val db: SQLiteDatabase, private val sql: SqlAccess = SqlAccess(db)) {
    private val schedules = TableSnapshot(db, "config_versions", "id", sql)
    private val years = TableSnapshot(db, "calendar_years", "year", sql)
    private val days = TableSnapshot(db, "calendar_days", "date", sql)
    private val overrides = TableSnapshot(db, "overrides", "date", sql)
    private val leaves = TableSnapshot(db, "leaves", "id", sql)
    private var initialSchedules: Map<Long, WorkScheduleSettings> = emptyMap()
    private var initialOverrides: Map<LocalDate, DateOverride> = emptyMap()

    fun load(state: BackendState) {
        sql.query(QueryTag.LOAD_ROWS, "config_versions", null, null, null, null, null, "effective ASC,id ASC").use {
            cursor ->
            while (cursor.moveToNext()) {
                state.schedules.add(
                    WorkConfigCodec.decode(
                        cursor.text("snapshot"),
                        cursor.long("id"),
                        cursor.long("effective"),
                    )
                )
            }
        }
        sql.query(QueryTag.LOAD_ROWS, "calendar_years", null, null, null, null, null, "year").use { cursor ->
            while (cursor.moveToNext()) {
                val row =
                    CalendarYear(
                        cursor.long("year").toInt(),
                        cursor.text("source"),
                        cursor.text("published"),
                        cursor.text("version"),
                    )
                state.calendarYears[row.year] = row
            }
        }
        sql.query(QueryTag.LOAD_ROWS, "calendar_days", null, null, null, null, null, "date").use { cursor ->
            while (cursor.moveToNext()) {
                val date = LocalDate.parse(cursor.text("date"))
                if (cursor.flag("working")) state.makeupDates.add(date)
                else state.holidays[date] = cursor.text("name")
            }
        }
        sql.query(QueryTag.LOAD_ROWS, "overrides", null, null, null, null, null, "date").use { cursor ->
            while (cursor.moveToNext()) {
                val date = LocalDate.parse(cursor.text("date"))
                val windows = WorkConfigCodec.windows(JSONArray(cursor.text("windows")))
                state.overrides[date] =
                    DateOverride(
                        date,
                        if (windows.isEmpty()) DateOverrideKind.REST else DateOverrideKind.WORK,
                        windows,
                    )
            }
        }
        sql.query(QueryTag.LOAD_ROWS, "leaves", null, null, null, null, null, "start DESC,id DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val row =
                    LeaveRecord(
                        cursor.long("id"),
                        cursor.long("start"),
                        cursor.long("end"),
                        cursor.text("note"),
                    )
                state.leaves[row.id] = row
            }
        }
        state.nextConfigVersion = sql.nextId("config_versions")
        state.nextLeaveId = sql.nextId("leaves")
        initialSchedules = state.schedules.associateBy { it.configVersion }
        initialOverrides = state.overrides.toMap()
    }

    fun save(state: BackendState) {
        state.schedules.forEach { row ->
            if (initialSchedules[row.configVersion] != row)
                schedules.save(
                    row.configVersion,
                    values(
                        "id" to row.configVersion,
                        "effective" to row.effectiveFromEpochMillis,
                        "snapshot" to WorkConfigCodec.encode(row),
                    ),
                )
        }
        val allDates = state.holidays.keys + state.makeupDates
        days.deleteMissing(allDates)
        state.calendarYears.values.forEach {
            years.save(
                it.year,
                values(
                    "year" to it.year,
                    "source" to it.source,
                    "published" to it.published,
                    "version" to it.version,
                ),
            )
        }
        state.holidays.forEach { (date, name) ->
            days.save(
                date,
                values(
                    "date" to date.toString(),
                    "year" to date.year,
                    "working" to 0,
                    "name" to name,
                ),
            )
        }
        state.makeupDates.forEach { date ->
            days.save(
                date,
                values(
                    "date" to date.toString(),
                    "year" to date.year,
                    "working" to 1,
                    "name" to
                        (days
                            .previous(date)
                            ?.takeIf { it.getAsInteger("working") == 1 }
                            ?.getAsString("name") ?: LegacyValueCodec.MAKEUP_DAY_NAME),
                ),
            )
        }
        years.deleteMissing(state.calendarYears.keys)
        state.overrides.forEach { (date, row) ->
            if (initialOverrides[date] != row)
                overrides.save(
                    date,
                    values(
                        "date" to date.toString(),
                        "windows" to WorkConfigCodec.windowsJson(row.windows).toString(),
                    ),
                )
        }
        overrides.deleteMissing(state.overrides.keys)
        state.leaves.values.forEach {
            leaves.save(
                it.id,
                values(
                    "id" to it.id,
                    "start" to it.startMillis,
                    "end" to it.endMillis,
                    "note" to it.note,
                ),
            )
        }
        leaves.deleteMissing(state.leaves.keys)
        state.pendingAudits.forEach { audit ->
            check(
                db.insertOrThrow(
                    "calendar_audit",
                    null,
                    values(
                        "kind" to audit.kind,
                        "target" to audit.target,
                        "raw" to audit.raw,
                        "created" to audit.createdMillis,
                    ),
                ) != -1L
            ) {
                "Audit insert was ignored"
            }
        }
    }
}
