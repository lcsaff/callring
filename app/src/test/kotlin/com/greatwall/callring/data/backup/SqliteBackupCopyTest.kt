package com.greatwall.callring.data.backup

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SqliteBackupCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun actualNullableArchiveFieldsAreInsertedWithoutLoadingTranscript() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,payload TEXT)")
            val original = com.greatwall.callring.data.recording.RecordingJson.archive(com.greatwall.callring.contracts.recording.CallArchive(-1, "100", 1, transcript = "retained"))
            db.execSQL("INSERT INTO archives VALUES(-1,?)", arrayOf(original.toString()))
            val fields = ArchiveBackupFields.fields(db, -1) {}
            assertNull(ArchiveBackupFields.string(fields["audio"]))
            ArchiveBackupFields.replace(db, -1, fields, mapOf("audio" to "null", "error" to JSONObject.quote("recording.audio_not_restored")))
            db.rawQuery("SELECT payload FROM archives", null).use {
                assertTrue(it.moveToFirst())
                val restored = com.greatwall.callring.data.recording.RecordingJson.archive(JSONObject(it.getString(0)))
                assertEquals("retained", restored.transcript); assertNull(restored.audioPath); assertEquals("recording.audio_not_restored", restored.error)
            }
        }
    }    @Test fun malformedKnownArchiveShapesAreRejectedWhileLongStringsStayStreamed() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,payload TEXT)")
            val original = com.greatwall.callring.data.recording.RecordingJson.archive(com.greatwall.callring.contracts.recording.CallArchive(-1,"100",1, transcript = "😀".repeat(100_000)))
            db.execSQL("INSERT INTO archives VALUES(-1,?)", arrayOf(original.toString()))
            for (mutate in listOf<(JSONObject) -> Unit>({ it.put("transcript", JSONObject()) }, { it.put("analysis", JSONObject()) }, { it.put("attempts", 2147483648L) })) {
                val bad = JSONObject(original.toString()); mutate(bad)
                db.execSQL("UPDATE archives SET payload=? WHERE call_id=-1", arrayOf(bad.toString()))
                assertThrows(BackupFormatException::class.java) { ArchiveBackupFields.fields(db, -1) {} }
            }
        }
    }    @Test fun copiesHistoricalRowsAndAutoincrementSequenceWithoutPayloadDecoding() {
        val source = File(temporary.root, "source.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT,payload TEXT)")
            db.execSQL("INSERT INTO facts(id,payload) VALUES(71,?)", arrayOf("中😀".repeat(100_000)))
            db.execSQL("DELETE FROM facts")
            db.execSQL("INSERT INTO facts(payload) VALUES('retained')")
            db.version = 3
        }
        val target = File(temporary.root, "copy.db")
        SqliteBackupCopy.copy(source, target) {}
        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(3, db.version)
            db.execSQL("INSERT INTO facts(payload) VALUES('new')")
            db.rawQuery("SELECT id,payload FROM facts ORDER BY id", null).use {
                assertTrue(it.moveToFirst()); assertEquals(72L, it.getLong(0)); assertEquals("retained", it.getString(1))
                assertTrue(it.moveToNext()); assertEquals(73L, it.getLong(0))
            }
        }
    }
    @Test fun pagesLongArchiveTextAndRewritesOnlyTopLevelFieldsIncludingUnicodeOffsets() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,payload TEXT)")
            val transcript = "字😀\\\"audio\\\"".repeat(40_000)
            val payload = JSONObject().put("id", -8).put("number", "100").put("started", 1).put("generation", 3)
                .put("source", "IMPORTED").put("protocol", JSONObject.NULL).put("duration", 3).put("attempts", 1).put("analyzed", JSONObject.NULL).put("quality", "recording.unverified").put("analysisModel", "").put("transcriptionModel", "").put("status", "QUEUED").put("audio", "/private/😀.wav").put("manual", true).put("next", 3).put("error", JSONObject.NULL)
                .put("transcript", transcript).put("analysis", JSONObject().put("name", "").put("organization", "").put("position", "").put("purpose", "").put("evidence", "").put("identity", "").put("summary", "").put("actions", org.json.JSONArray()).put("audio", "nested untouched")).toString()
            db.execSQL("INSERT INTO archives VALUES(-8,?)", arrayOf(payload))
            val fields = ArchiveBackupFields.fields(db, -8) {}
            assertEquals("/private/😀.wav", ArchiveBackupFields.string(fields["audio"]))
            ArchiveBackupFields.replace(db, -8, fields, mapOf("audio" to "null", "manual" to "false", "status" to JSONObject.quote("FAILED")))
            val changed = db.rawQuery("SELECT payload FROM archives", null).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            assertTrue(changed.isNull("audio")); assertFalse(changed.getBoolean("manual")); assertEquals("FAILED", changed.getString("status"))
            assertEquals(transcript, changed.getString("transcript")); assertEquals("nested untouched", changed.getJSONObject("analysis").getString("audio"))
        }
    }
}
