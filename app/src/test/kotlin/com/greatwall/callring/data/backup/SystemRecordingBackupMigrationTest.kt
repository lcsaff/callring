package com.greatwall.callring.data.backup

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.data.recording.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingBackupMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun legacySchemas() {
        val installed = current("current")
        for (version in 1..5) {
            val source = if (version == 1) File(temporary.root,"legacy-1.db").also { file ->
                SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                    db.execSQL("CREATE TABLE archives (call_id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, payload TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE deleted_archives (call_id INTEGER PRIMARY KEY)")
                    db.execSQL("INSERT INTO archives VALUES(42,3,'retained-payload')")
                    db.execSQL("INSERT INTO deleted_archives VALUES(43)")
                    db.version = 1
                }
            } else current("legacy-$version")
            if (version > 1) SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO archives VALUES(42,3,'retained-payload')")
                db.execSQL("INSERT INTO deleted_archives VALUES(43)")
                db.execSQL("DROP TABLE system_recording_sources")
                db.execSQL("DROP TABLE system_recording_inventory")
                db.execSQL("DROP TABLE system_recording_scan")
                db.execSQL("DROP INDEX recording_events_time")
                if (version < 5) {
                    listOf("archive_list_insert", "archive_list_delete", "archive_list_update").forEach { db.execSQL("DROP TRIGGER $it") }
                    db.execSQL("DROP INDEX archive_list_order"); db.execSQL("DROP TABLE archive_list_state")
                }
                if (version < 4) db.execSQL("DROP TABLE archive_parse_progress")
                if (version < 3) listOf("archive_projection", "archive_text_fields", "archive_text_chunks", "archive_index_progress", "archive_raw_chunks").forEach { db.execSQL("DROP TABLE $it") }
                if (version < 2) { db.execSQL("DROP TABLE recording_events"); db.execSQL("DROP TABLE recording_statistics_links") }
                else {
                    db.execSQL("INSERT INTO recording_events(id,session_id,number,started) VALUES(71,'old','12345',1)")
                    db.execSQL("DELETE FROM recording_events")
                }
                db.version = version
            }
            val original = source.readBytes()
            val candidate = File(temporary.root, "candidate-$version.db")
            SqliteBackupCopy.copy(source, candidate) {}
            SqliteBackupCopy.upgradeRecordingCandidate(candidate)
            SqliteBackupCopy.validate(candidate, installed)
            assertArrayEquals(original, source.readBytes())
            SQLiteDatabase.openDatabase(candidate.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(6, db.version)
                db.rawQuery("SELECT generation,payload FROM archives WHERE call_id=42", null).use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals("retained-payload", it.getString(1)) }
                assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM deleted_archives WHERE call_id=43", null))
                db.execSQL("INSERT INTO recording_events(session_id,number,started) VALUES('new','12345',2)")
                assertEquals(if(version < 2) 1L else 72L, android.database.DatabaseUtils.longForQuery(db,"SELECT id FROM recording_events WHERE session_id='new'",null))
            }
        }
    }
    private fun current(name: String): File {
        val namespace = "backup-migration-$name"
        AndroidRecordingStore(context, namespace, object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }, recordingVersion = { 0 }).use { it.captureRecordingText() }
        val target = File(temporary.root, "$name.db")
        SqliteBackupCopy.copy(context.getDatabasePath(namespace + "_recording.db"), target) {}
        context.deleteDatabase(namespace + "_recording.db")
        return target
    }
}
