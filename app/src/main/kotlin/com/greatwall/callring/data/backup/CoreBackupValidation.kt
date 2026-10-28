package com.greatwall.callring.data.backup

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.data.codec.NumberRuleCodec
import com.greatwall.callring.data.codec.WorkConfigCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Decode only persisted encodings that normal readers consume. Audit/raw legacy facts remain
 * opaque; unknown setting keys and the shipped legacy enum fallbacks are deliberately preserved. */
internal object CoreBackupValidation {
    fun validate(file: File, check: () -> Unit) {
        try { validateRows(file, check) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { throw BackupFormatException() }
    }
    private fun validateRows(file: File, check: () -> Unit) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,effective,snapshot FROM config_versions ORDER BY id", null).use { rows ->
                while (rows.moveToNext()) { check(); WorkConfigCodec.decode(rows.getString(2), rows.getLong(0), rows.getLong(1)) }
            }
            db.rawQuery("SELECT key,value FROM settings WHERE key IN ('number_rule','quiet_windows','quiet_mode','off_weight','repeat_weight','repeat_minutes','score_days','my_position','leave_revision')", null).use { rows ->
                while (rows.moveToNext()) {
                    check()
                    val value = if (rows.isNull(1)) null else rows.getString(1)
                    when (rows.getString(0)) {
                        "number_rule" -> NumberRuleCodec.decode(value)
                        "quiet_windows" -> WorkConfigCodec.weekly(JSONObject(value ?: "{}"))
                        "quiet_mode" -> com.greatwall.callring.domain.model.QuietMode.valueOf(requireNotNull(value))
                        "off_weight", "repeat_weight", "repeat_minutes", "score_days" -> value?.toInt()
                        "my_position" -> value?.toLong()
                        "leave_revision" -> requireNotNull(value).toLong()
                    }
                }
            }
            db.rawQuery("SELECT date,windows FROM overrides", null).use { rows ->
                while (rows.moveToNext()) { check(); LocalDate.parse(rows.getString(0)); WorkConfigCodec.windows(JSONArray(rows.getString(1))) }
            }
            db.rawQuery("SELECT date FROM calendar_days", null).use { rows -> while (rows.moveToNext()) { check(); LocalDate.parse(rows.getString(0)) } }
        }
    }
}
