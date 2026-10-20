package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.StatisticsInterval
import com.greatwall.callring.domain.model.*
import org.json.JSONObject

/** All capabilities share the same connection and transaction. Nothing live leaves the callback. */
internal class SqliteCoreUnitOfWork(private val db: SQLiteDatabase, private val sql: SqlAccess) : CoreUnitOfWork {
    val quietReads = java.util.concurrent.atomic.AtomicLong()
    val quietWrites = java.util.concurrent.atomic.AtomicLong()
    override fun <T> readQuiet(block: (QuietReader) -> T): T = readSession { block(it) }
    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = writeSession { block(it) }
    override fun <T> readSession(block: (CoreReadSession) -> T): T = session(false, block)
    override fun <T> writeSession(block: (CoreWriteSession) -> T): T = session(true, block)
    @Synchronized private fun <T> session(write: Boolean, block: (CoreWriteSession) -> T): T {
        if (write) db.beginTransaction() else db.beginTransactionNonExclusive()
        val session = Session(write)
        try { return block(session).also { db.setTransactionSuccessful() } }
        finally { session.active = false; db.endTransaction() }
    }
    private inner class Session(private val writable: Boolean) : CoreWriteSession {
        var active = true
        private var loadedQuiet: QuietConfiguration? = null
        private var storedLastMode = QuietMode.NON_WORK
        private fun checkActive(write: Boolean = false) { check(active) { "storage.session.closed" }; check(!write || writable) { "storage.session.read_only" } }
        override fun quiet(): QuietConfiguration {
            checkActive(); quietReads.incrementAndGet()
            val settings = sql.query(QueryTag.SCOPED_SETTING, "settings", arrayOf("key", "value"),
                "key IN (?,?,?,?)", arrayOf("quiet_mode", "all_numbers", "quiet_windows", "last_quiet_mode"), null, null, null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            val mode = QuietMode.valueOf(settings["quiet_mode"] ?: "OFF")
            val quiet = QuietSettings(mode, settings["all_numbers"] == "1", WorkConfigCodec.weekly(JSONObject(settings["quiet_windows"] ?: "{}")))
            storedLastMode = runCatching { QuietMode.valueOf(settings["last_quiet_mode"] ?: "NON_WORK") }.getOrDefault(QuietMode.NON_WORK).takeUnless { it == QuietMode.OFF } ?: QuietMode.NON_WORK
            val last = if (settings["last_quiet_mode"] == null && mode != QuietMode.OFF) mode else storedLastMode
            return QuietConfiguration(quiet, last).also { loadedQuiet = it }
        }
        override fun saveQuiet(value: QuietConfiguration): Boolean {
            checkActive(true); quietWrites.incrementAndGet()
            val before = loadedQuiet ?: quiet()
            val changed = linkedMapOf<String, String>()
            if (before.settings.mode != value.settings.mode) changed["quiet_mode"] = value.settings.mode.name
            if (storedLastMode != value.lastMode) changed["last_quiet_mode"] = value.lastMode.name
            if (before.settings.allNumbers != value.settings.allNumbers) changed["all_numbers"] = if (value.settings.allNumbers) "1" else "0"
            val windows = WorkConfigCodec.weeklyJson(value.settings.weeklyWindows).toString()
            if (WorkConfigCodec.weeklyJson(before.settings.weeklyWindows).toString() != windows) changed["quiet_windows"] = windows
            changed.forEach { (key, value) ->
                val updated = sql.update("settings", values("value" to value), "key=?", arrayOf(key))
                if (updated == 0) sql.insert("settings", values("key" to key, "value" to value))
            }
            loadedQuiet = value
            storedLastMode = value.lastMode
            return changed.isNotEmpty()
        }
        override fun statistics(): List<StatisticsInterval> {
            checkActive()
            return sql.query(QueryTag.SCOPED_ROWS, "statistics_intervals", arrayOf("start", "end"), null, null, null, null, "id").use { c ->
                buildList { while (c.moveToNext()) add(StatisticsInterval(c.getLong(0), if (c.isNull(1)) null else c.getLong(1))) }
            }
        }
        override fun appendStatistics(value: StatisticsInterval) {
            checkActive(true)
            db.insertOrThrow("statistics_intervals", null, values("start" to value.startMillis, "end" to value.endMillis))
        }
    }
}
