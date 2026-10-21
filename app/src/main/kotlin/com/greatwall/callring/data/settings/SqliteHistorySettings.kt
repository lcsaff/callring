package com.greatwall.callring.data.settings

import com.greatwall.callring.data.codec.LegacyValueCodec
import com.greatwall.callring.data.codec.WorkConfigCodec
import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.args
import com.greatwall.callring.data.database.values
import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.CalendarImport
import com.greatwall.callring.domain.model.LeaveRecord

import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.data.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import java.time.LocalDate
import org.json.JSONArray

/** SQL mapping only. Domain classification runs in the application with the full work history. */
internal class SqliteHistorySettings(private val sql: SqlAccess, private val checkActive: (Boolean) -> Unit) : CalendarWriter, LeaveWriter {
    private fun insert(table: String, fields: android.content.ContentValues): Long = sql.insertReturningId(table, fields).also {
        check(it != -1L) { "storage.insert.ignored" }
    }
    private fun update(table: String, fields: android.content.ContentValues, where: String, args: Array<String>) {
        check(sql.update(table, fields, where, args) == 1) { "storage.update.ignored" }
    }
    private fun query(table: String, columns: Array<String>, where: String? = null, args: Array<String>? = null, order: String? = null) =
        sql.query(QueryTag.WORK_ROWS, table, columns, where, args, null, null, order)
    private fun years(): List<CalendarYear> = query("calendar_years", arrayOf("year", "source", "published", "version"), order = "year").use { c ->
        buildList { while(c.moveToNext()) add(CalendarYear(c.getInt(0),c.getString(1),c.getString(2),c.getString(3))) }
    }
    private fun overrides(): List<DateOverride> = query("overrides", arrayOf("date", "windows"), order = "date").use { c ->
        buildList { while(c.moveToNext()) { val windows = WorkConfigCodec.windows(JSONArray(c.getString(1))); add(DateOverride(LocalDate.parse(c.getString(0)), if(windows.isEmpty()) DateOverrideKind.REST else DateOverrideKind.WORK, windows)) } }
    }
    override fun calendarOverview(): CalendarOverview { checkActive(false); return CalendarOverview(years(), overrides()) }
    override fun calendarYear(year: Int): CalendarImport? {
        checkActive(false)
        val metadata = query("calendar_years", arrayOf("year","source","published","version"), "year=?", arrayOf(year.toString())).use { c ->
            if(c.moveToFirst()) CalendarYear(c.getInt(0),c.getString(1),c.getString(2),c.getString(3)) else null
        } ?: return null
        val holidays = linkedMapOf<LocalDate,String>(); val makeup = linkedSetOf<LocalDate>()
        query("calendar_days", arrayOf("date","working","name"), "year=?", arrayOf(year.toString())).use { c -> while(c.moveToNext()) {
            val date=LocalDate.parse(c.getString(0)); if(c.getInt(1)==1) makeup += date else holidays[date]=c.getString(2)
        } }
        return CalendarImport(metadata,holidays,makeup)
    }
    override fun replaceCalendar(value: CalendarImport) {
        checkActive(true)
        val year=value.metadata.year
        val old=calendarYear(year)
        val m=value.metadata
        if(old?.metadata != m) {
            val fields=values("source" to m.source,"published" to m.published,"version" to m.version)
            if(old==null) { fields.put("year",year); insert("calendar_years",fields) }
            else update("calendar_years",fields,"year=?",arrayOf(year.toString()))
        }
        val oldDates=old?.let { it.holidays.keys + it.makeupDates }.orEmpty()
        val dates=value.holidays.keys + value.makeupDates
        (oldDates - dates).forEach { check(sql.delete("calendar_days","date=?",arrayOf(it.toString()))==1) }
        dates.forEach { date ->
            val working=date in value.makeupDates
            val same=if(working) date in old?.makeupDates.orEmpty() else old?.holidays?.get(date)==value.holidays[date]
            if(!same) {
                val fields=values("year" to year,"working" to working,"name" to (if(working) LegacyValueCodec.MAKEUP_DAY_NAME else value.holidays.getValue(date)))
                if(date in oldDates) update("calendar_days",fields,"date=?",arrayOf(date.toString()))
                else { fields.put("date",date.toString()); insert("calendar_days",fields) }
            }
        }
    }
    override fun dateOverride(date: LocalDate): DateOverride? {
        checkActive(false)
        return query("overrides",arrayOf("windows"),"date=?",arrayOf(date.toString())).use { c -> if(c.moveToFirst()) {
            val windows=WorkConfigCodec.windows(JSONArray(c.getString(0)))
            DateOverride(date,if(windows.isEmpty()) DateOverrideKind.REST else DateOverrideKind.WORK,windows)
        } else null }
    }
    override fun putDateOverride(value: DateOverride) {
        checkActive(true)
        val fields=values("windows" to WorkConfigCodec.windowsJson(value.windows).toString())
        if(sql.update("overrides",fields,"date=?",arrayOf(value.date.toString()))==0) {
            fields.put("date",value.date.toString()); insert("overrides",fields)
        }
    }
    override fun removeDateOverride(date: LocalDate) { checkActive(true); check(sql.delete("overrides","date=?",arrayOf(date.toString()))==1) }
    override fun leaves(): List<LeaveRecord> {
        checkActive(false)
        return query("leaves",arrayOf("id","start","end","note"),order="start DESC,id DESC").use { c -> buildList {
            while(c.moveToNext()) add(LeaveRecord(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3)))
        } }
    }
    override fun leave(id: Long): LeaveRecord? {
        checkActive(false)
        return query("leaves",arrayOf("id","start","end","note"),"id=?",arrayOf(id.toString())).use { c ->
            if(c.moveToFirst()) LeaveRecord(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3)) else null
        }
    }
    override fun putLeave(value: LeaveRecord, insert: Boolean): LeaveRecord {
        checkActive(true)
        val fields=values("start" to value.startMillis,"end" to value.endMillis,"note" to value.note)
        return if(insert) value.copy(id=insert("leaves",fields)) else {
            check(sql.update("leaves",fields,"id=?",arrayOf(value.id.toString()))==1); value
        }
    }
    override fun removeLeave(id: Long) { checkActive(true); check(sql.delete("leaves","id=?",arrayOf(id.toString()))==1) }
    override fun incrementLeaveVersion() {
        checkActive(true)
        val next=leaveVersion()+1
        val fields=values("value" to next.toString())
        if(sql.update("settings",fields,"key=?",arrayOf("leave_revision"))==0) insert("settings",values("key" to "leave_revision","value" to next.toString()))
    }
    private fun leaveVersion() = query("settings",arrayOf("value"),"key=?",arrayOf("leave_revision")).use { if(it.moveToFirst()) it.getString(0).toLong() else 0 }
    override fun appendAudit(value: AuditRecord) {
        checkActive(true); insert("calendar_audit",values("kind" to value.kind,"target" to value.target,"raw" to value.raw,"created" to value.createdMillis))
    }
    override fun workHistory(): WorkHistory {
        checkActive(false)
        val schedules=query("config_versions",arrayOf("id","effective","snapshot"),order="effective,id").use { c -> buildList {
            while(c.moveToNext()) add(WorkConfigCodec.decode(c.getString(2),c.getLong(0),c.getLong(1)))
        } }
        val holidays=linkedMapOf<LocalDate,String>(); val makeup=linkedSetOf<LocalDate>()
        query("calendar_days",arrayOf("date","working","name")).use { c -> while(c.moveToNext()) {
            val date=LocalDate.parse(c.getString(0)); if(c.getInt(1)==1) makeup += date else holidays[date]=c.getString(2)
        } }
        return WorkHistory(schedules,years().associateBy { it.year },holidays,makeup,overrides().associateBy { it.date },leaves().associateBy { it.id },leaveVersion())
    }
    override fun classificationBatch(afterId: Long, limit: Int): List<HistoricalClassification> {
        checkActive(false); require(limit in 1..1024)
        return sql.rawQuery(QueryTag.WORK_ROWS,setOf(SqlTable.CALLS),
            "SELECT id,number,time,working,reason,verified,zone,config_version,calendar_version,leave_version FROM calls WHERE id>? ORDER BY id LIMIT ?",arrayOf(afterId.toString(),limit.toString())).use { c -> buildList {
            while(c.moveToNext()) add(HistoricalClassification(c.getLong(0),c.getString(1),c.getLong(2),ClassificationFact(c.getInt(3)==1,LegacyValueCodec.reason(c.getString(4)),LegacyValueCodec.holidayName(c.getString(4)),c.getInt(5)==1,c.getString(6),c.getLong(7),LegacyValueCodec.calendarVersion(c.getString(8)),c.getLong(9))))
        } }
    }
    override fun updateClassification(before: HistoricalClassification, current: ClassificationFact) {
        checkActive(true)
        val old=before.current
        val fields=android.content.ContentValues()
        if(old.working!=current.working) fields.put("working",if(current.working) 1 else 0)
        if(old.reason!=current.reason || old.holidayName!=current.holidayName) fields.put("reason",LegacyValueCodec.encodeReason(current))
        if(old.calendarVerified!=current.calendarVerified) fields.put("verified",if(current.calendarVerified) 1 else 0)
        if(old.zoneId!=current.zoneId) fields.put("zone",current.zoneId)
        if(old.configVersion!=current.configVersion) fields.put("config_version",current.configVersion)
        if(old.calendarVersion!=current.calendarVersion) fields.put("calendar_version",LegacyValueCodec.encodeCalendarVersion(current.calendarVersion))
        if(old.leaveVersion!=current.leaveVersion) fields.put("leave_version",current.leaveVersion)
        if(fields.size()>0) check(sql.update("calls",fields,"id=?",arrayOf(before.id.toString()))==1)
    }
}
