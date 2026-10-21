package com.greatwall.callring.data

import com.greatwall.callring.data.calling.CallingRowCodec
import com.greatwall.callring.data.codec.WorkConfigCodec
import com.greatwall.callring.data.database.SqlAccess
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.SqliteCoreUnitOfWork
import com.greatwall.callring.data.database.args
import com.greatwall.callring.data.database.long
import com.greatwall.callring.data.database.text
import com.greatwall.callring.data.database.values
import com.greatwall.callring.data.query.SqliteScopedReadAdapter
import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.LeaveRecord
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.StatisticsInterval

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.conversation.query.sourceVersion

/** White-box fixture SQL on the resource's existing connection; never a production capability. */
internal fun SqliteCoreStorageResource.fixtureSql(block: (SQLiteDatabase) -> Unit) {
    synchronized(this) {
        val db = SqliteCoreStorageResource::class.java.getDeclaredField("db").let {
            it.isAccessible = true
            it.get(this) as SQLiteDatabase
        }
        db.beginTransaction()
        db.execSQL("PRAGMA defer_foreign_keys=ON")
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
internal fun SQLiteDatabase.scalar(sql: String, parameters: Array<String>? = null): Long =
    rawQuery(sql, parameters).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0 }
internal fun SQLiteDatabase.seedCaller(row: ProfileRecord) {
    val fields = values("name" to row.name, "department" to row.department,
        "position_id" to row.positionId, "whitelist" to row.whitelisted)
    if(update("callers", fields, "number=?", args(row.number)) == 0)
        check(insertOrThrow("callers", null, fields.apply { put("number", row.number) }) != -1L)
}
internal fun SQLiteDatabase.seedPosition(row: PositionRecord) {
    check(insertOrThrow("positions", null, values("id" to row.id, "name" to row.name,
        "level" to row.level, "active" to row.active)) != -1L)
}
internal fun SQLiteDatabase.seedCall(row: RawCallRecord) {
    val old = callFact(row.id)
    val fields = CallingRowCodec.encode(row, old)
    if (old == null) check(insertOrThrow("calls", null, fields.apply { put("id", row.id) }) != -1L)
    else if (fields.size() > 0) check(update("calls", fields, "id=?", args(row.id)) == 1)
}
internal fun SQLiteDatabase.callFact(id: Long): RawCallRecord? =
    rawQuery("SELECT * FROM calls WHERE id=?", args(id)).use { if(it.moveToFirst()) CallingRowCodec.decode(it) else null }
internal fun SQLiteDatabase.seedSchedule(row: WorkScheduleSettings) {
    val fields = values("effective" to row.effectiveFromEpochMillis, "snapshot" to WorkConfigCodec.encode(row))
    if(update("config_versions", fields, "id=?", args(row.configVersion)) == 0)
        check(insertOrThrow("config_versions", null, fields.apply { put("id", row.configVersion) }) != -1L)
}
internal fun SQLiteDatabase.scheduleFacts(): List<WorkScheduleSettings> =
    rawQuery("SELECT * FROM config_versions ORDER BY effective,id", null).use { c -> buildList {
        while(c.moveToNext()) add(WorkConfigCodec.decode(c.text("snapshot"), c.long("id"), c.long("effective")))
    } }
internal fun SQLiteDatabase.seedInterval(row: StatisticsInterval) {
    check(insertOrThrow("statistics_intervals", null, values("start" to row.startMillis, "end" to row.endMillis)) != -1L)
}
internal fun SQLiteDatabase.seedAudit(row: AuditRecord) {
    check(insertOrThrow("calendar_audit", null, values("kind" to row.kind, "target" to row.target,
        "raw" to row.raw, "created" to row.createdMillis)) != -1L)
}
internal fun SQLiteDatabase.seedLeave(row: LeaveRecord) {
    check(insertOrThrow("leaves",null,values("id" to row.id,"start" to row.startMillis,"end" to row.endMillis,"note" to row.note)) != -1L)
}
internal fun SQLiteDatabase.seedSequence(table: String, next: Long) {
    check(table in setOf("positions","calls","leaves","config_versions"))
    val sequence=next-1
    if(update("sqlite_sequence",values("seq" to sequence),"name=?",args(table))==0)
        check(insertOrThrow("sqlite_sequence",null,values("name" to table,"seq" to sequence)) != -1L)
}
internal fun SQLiteDatabase.fixtureWork(): WorkHistory =
    SqliteCoreUnitOfWork(this, SqlAccess(this)).readSession { it.workHistory() }

internal fun SqliteCoreStorageResource.fixtureScopedReads(coordinator: com.greatwall.callring.application.coordination.BusinessRevisionCoordinator): com.greatwall.callring.application.ports.query.ScopedReadPort {
    val db = SqliteCoreStorageResource::class.java.getDeclaredField("db").let { it.isAccessible = true; it.get(this) as SQLiteDatabase }
    return com.greatwall.callring.application.conversation.query.CoreScopedReadSource(SqliteScopedReadAdapter(db, { domain ->
        coordinator.sourceVersion(domain)
    }, sql = SqliteCoreStorageResource::class.java.getDeclaredField("sql").let { it.isAccessible = true; it.get(this) as SqlAccess }))
}

internal fun SQLiteDatabase.fixtureNextId(table: String): Long {
    check(table in setOf("calls", "positions", "leaves", "config_versions"))
    return maxOf(scalar("SELECT COALESCE(MAX(id),0) FROM $table"),
        scalar("SELECT seq FROM sqlite_sequence WHERE name=?", args(table))) + 1
}
