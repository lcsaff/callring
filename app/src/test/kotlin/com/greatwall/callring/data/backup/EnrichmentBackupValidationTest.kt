package com.greatwall.callring.data.backup

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.recording.RecordingJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EnrichmentBackupValidationTest {
    @Test fun validIneligibleBaselineDoesNotBlockArchiveRestore() {
        val json = RecordingJson.archive(CallArchive(-1, "１２３", 1, enrichmentBaseline = CallerIdentityBaseline("", emptyMap(), eligible = false)))
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,generation INTEGER,payload TEXT)")
            db.execSQL("INSERT INTO archives VALUES(-1,0,?)", arrayOf(json.toString()))
            assertTrue(ArchiveBackupFields.fields(db, -1) {}.containsKey("id"))
        }
    }

    @Test fun malformedEvidenceIsRejectedBeforeRestore() {
        val json = RecordingJson.archive(CallArchive(-1, "31234", 1, analysis = CallAnalysis()))
        json.getJSONObject("analysis").put("fieldEvidence", JSONObject().put("region", 42))
        assertRejected(json)
    }
    @Test fun malformedDurableBaselineIsRejectedBeforeRestore() {
        val json = RecordingJson.archive(CallArchive(-1, "31234", 1))
        json.put("enrichmentBaseline", JSONObject().put("epoch", "epoch").put("revisions", JSONObject().put("region", -1)))
        assertRejected(json)
    }
    private fun assertRejected(json: JSONObject) {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,generation INTEGER,payload TEXT)")
            db.execSQL("INSERT INTO archives VALUES(-1,0,?)", arrayOf(json.toString()))
            assertThrows(BackupFormatException::class.java) { ArchiveBackupFields.fields(db, -1) {} }
        }
    }
}
