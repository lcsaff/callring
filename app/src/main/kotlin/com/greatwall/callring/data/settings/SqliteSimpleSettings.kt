package com.greatwall.callring.data.settings

import com.greatwall.callring.data.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.StatisticsInterval
import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.model.*

/** One adapter per leased session; shares its SQL connection and lifetime checks. */
internal class SqliteSimpleSettings(private val sql: SqlAccess, private val checkLease: (Boolean) -> Unit) :
    ScoreWriter, OverlayWriter, StatisticsWriter, PositionWriter, SummaryReader {
    private fun checkActive(write: Boolean = false) = checkLease(write)
    private fun settings(vararg keys: String): Map<String, String?> {
        checkActive()
        return sql.query(QueryTag.SCOPED_SETTING, "settings", arrayOf("key", "value"),
            "key IN (${keys.joinToString(",") { "?" }})", keys.toList().toTypedArray(), null, null, null).use { c ->
            buildMap { while(c.moveToNext()) put(c.getString(0), if(c.isNull(1)) null else c.getString(1)) }
        }
    }
    private fun putSetting(key: String, value: String?) {
        checkActive(true)
        if (sql.update("settings", values("value" to value), "key=?", arrayOf(key)) == 0)
            sql.insert("settings", values("key" to key, "value" to value))
    }
    private var loadedScore: ScoreSettings? = null
    override fun score(): ScoreSettings {
        val v = settings("off_weight", "repeat_weight", "repeat_minutes", "score_days")
        return ScoreSettings((v["off_weight"] ?: "5").toInt(), (v["repeat_weight"] ?: "2").toInt(),
            (v["repeat_minutes"] ?: "10").toInt(), (v["score_days"] ?: "30").toInt()).also { loadedScore = it }
    }
    override fun saveScore(value: ScoreSettings): Boolean {
        checkActive(true)
        val old = loadedScore ?: score()
        if(old.offHoursWeight != value.offHoursWeight) putSetting("off_weight", value.offHoursWeight.toString())
        if(old.repeatWeight != value.repeatWeight) putSetting("repeat_weight", value.repeatWeight.toString())
        if(old.repeatMinutes != value.repeatMinutes) putSetting("repeat_minutes", value.repeatMinutes.toString())
        if(old.observationDays != value.observationDays) putSetting("score_days", value.observationDays.toString())
        loadedScore = value
        return old != value
    }
    override fun overlay() = settings("overlay")["overlay"] == "1"
    override fun saveOverlay(value: Boolean): Boolean {
        checkActive(true)
        putSetting("overlay", if(value) "1" else "0")
        return true
    }
    override fun statisticsHead(): StatisticsHead {
        checkActive()
        val open = sql.rawQuery(QueryTag.SCOPED_ROWS, setOf(SqlTable.STATISTICS_INTERVALS),
            "SELECT id,start FROM statistics_intervals WHERE end IS NULL LIMIT 1", null).use { c ->
            if(c.moveToFirst()) OpenStatistics(c.getLong(0), StatisticsInterval(c.getLong(1), null)) else null
        }
        val end = sql.rawQuery(QueryTag.AGGREGATE, setOf(SqlTable.STATISTICS_INTERVALS),
            "SELECT MAX(end) FROM statistics_intervals WHERE end IS NOT NULL", null).use { c ->
            if(c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        return StatisticsHead(open, end)
    }
    override fun openStatistics(at: Long) {
        checkActive(true)
        sql.insert("statistics_intervals", values("start" to at, "end" to null))
    }
    override fun closeStatistics(id: Long, at: Long) {
        checkActive(true)
        check(sql.update("statistics_intervals", values("end" to at), "id=? AND end IS NULL", arrayOf(id.toString())) == 1)
    }
    private fun positionRow(c: android.database.Cursor) = PositionRecord(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) != 0)
    override fun positions(): List<PositionRecord> {
        checkActive()
        return sql.query(QueryTag.SCOPED_ROWS, "positions", arrayOf("id", "name", "level", "active"), null, null, null, null, null).use { c ->
            buildList { while(c.moveToNext()) add(positionRow(c)) }
        }
    }
    override fun position(id: Long): PositionRecord? {
        checkActive()
        return sql.query(QueryTag.SCOPED_ROWS, "positions", arrayOf("id", "name", "level", "active"), "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if(c.moveToFirst()) positionRow(c) else null
        }
    }
    override fun myPositionId(): Long? = settings("my_position")["my_position"]?.toLong()
    override fun positionNameExists(name: String, except: Long?): Boolean {
        checkActive()
        val predicate = if (except == null) "name=?" else "name=? AND id<>?"
        val args = if (except == null) arrayOf(name) else arrayOf(name, except.toString())
        return sql.scalar(QueryTag.AGGREGATE, setOf(SqlTable.POSITIONS),
            "SELECT EXISTS(SELECT 1 FROM positions WHERE $predicate)", args) != 0L
    }
    override fun positionReferenced(id: Long): Boolean {
        checkActive()
        return sql.scalar(QueryTag.AGGREGATE, setOf(SqlTable.CALLERS), "SELECT EXISTS(SELECT 1 FROM callers WHERE position_id=?)", arrayOf(id.toString())) != 0L
    }
    override fun positionNumbers(id: Long): Set<String> {
        checkActive()
        return sql.query(QueryTag.SCOPED_ROWS, "callers", arrayOf("number"), "position_id=?", arrayOf(id.toString()), null, null, null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }
    }
    override fun putPosition(value: PositionRecord, insert: Boolean): PositionRecord {
        checkActive(true)
        val fields = values("name" to value.name, "level" to value.level, "active" to if(value.active) 1 else 0)
        return if(insert) value.copy(id = sql.insertReturningId("positions", fields)) else {
            check(sql.update("positions", fields, "id=?", arrayOf(value.id.toString())) == 1)
            value
        }
    }
    override fun deletePosition(id: Long) {
        checkActive(true)
        check(sql.delete("positions", "id=?", arrayOf(id.toString())) == 1)
    }
    override fun saveMyPosition(id: Long?) {
        checkActive(true)
        val fields = values("value" to id?.toString(), "position_id" to id)
        if(sql.update("settings", fields, "key=?", arrayOf("my_position")) == 0)
            sql.insert("settings", fields.apply { put("key", "my_position") })
    }
    override fun settingsSummary(): CoreSettingsSummary {
        val v = settings("quiet_mode", "overlay", "my_position", "off_weight", "repeat_weight", "repeat_minutes", "score_days")
        val enabled = sql.scalar(QueryTag.AGGREGATE, setOf(SqlTable.STATISTICS_INTERVALS),
            "SELECT EXISTS(SELECT 1 FROM statistics_intervals WHERE end IS NULL)") != 0L
        val zone = sql.rawQuery(QueryTag.SCOPED_ROWS, setOf(SqlTable.CONFIG_VERSIONS),
            "SELECT snapshot,id,effective FROM config_versions ORDER BY effective DESC,id DESC LIMIT 1", null).use { c ->
            if(c.moveToFirst()) WorkConfigCodec.decode(c.getString(0), c.getLong(1), c.getLong(2)).zoneId
            else com.greatwall.callring.domain.defaultWorkSchedule().zoneId
        }
        return CoreSettingsSummary(enabled, v["overlay"] == "1", QuietMode.valueOf(v["quiet_mode"] ?: "OFF"),
            v["my_position"]?.toLong()?.let(::position), zone,
            ScoreSettings((v["off_weight"] ?: "5").toInt(), (v["repeat_weight"] ?: "2").toInt(),
                (v["repeat_minutes"] ?: "10").toInt(), (v["score_days"] ?: "30").toInt()))
    }
}
