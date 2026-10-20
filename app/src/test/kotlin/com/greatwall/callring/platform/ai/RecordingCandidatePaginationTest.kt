package com.greatwall.callring.platform.ai

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.RecordingCandidate
import com.greatwall.callring.contracts.recording.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingCandidatePaginationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
    @Test fun mixedThousandDueRowsConsumeOnlyEachPartitionsCandidatePrefix() = allDue(1_000)
    @Test fun mixedTenThousandDueRowsConsumeOnlyEachPartitionsCandidatePrefix() = allDue(10_000)
    private fun allDue(size: Int) {
        val namespace = "pagination-$size"
        val rows = (-size / 2L..size / 2L).filter { it != 0L }.map { id ->
            CallArchive(id, "31234", 1, generation = 7, status = ArchiveStatus.QUEUED, transcript = if (id == -3L) "large-text".repeat(1000) else "")
        }
        fixture(namespace, rows).use { store ->
            var after: Long? = null
            val actual = mutableListOf<RecordingCandidate>()
            val pages = JSONArray()
            while (true) {
                val before = store.readCounters.snapshot()
                val page = store.dueCandidates(100, after, 64) { false }
                val counts = store.readCounters.snapshot()
                fun delta(key: String) = (counts[key] ?: 0) - (before[key] ?: 0)
                val warm = delta("warmMetadataRows"); val cold = delta("coldIdentityRows")
                pages.put(JSONObject().put("returned", page.size).put("warmRows", warm).put("coldRows", cold).put("sourceBytes", delta("sourceBytes")))
                File("build/qa/layer-boundaries/t10b1-pagination-$size.json").apply {
                    parentFile!!.mkdirs(); writeText(JSONObject().put("fixtureRows", size).put("pages", pages).put("totals", JSONObject(counts)).toString(2))
                }
                assertTrue("warm/cold prefixes must stop separately: $warm/$cold", warm <= 64 && cold <= 64)
                if (page.isEmpty()) break
                actual += page; after = page.last().callId
            }
            assertEquals(rows.map { RecordingCandidate(it.callId, 7) }, actual)
            val counts = store.readCounters.snapshot()
            assertTrue((counts["warmMetadataRows"] ?: 0) + (counts["coldIdentityRows"] ?: 0) <= size * 2L)
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0)
            assertUnchangedIndex(namespace, rows)
        }
    }
    @Test fun futureRowsStillRequireExtraReadsAndCancellationDoesNotPublishOrIndex() {
        val namespace = "pagination-future"
        val rows = (-90L..90L).filter { it != 0L }.map { id ->
            CallArchive(id, "31234", 1, generation = 7, status = ArchiveStatus.QUEUED,
                nextAttemptAt = if (id % 3L == 0L) 200 else 0)
        }
        fixture(namespace, rows).use { store ->
            val marker = store.coordinator.marker()
            val actual = mutableListOf<RecordingCandidate>(); var after: Long? = null
            while (true) {
                val page = store.dueCandidates(100, after, 7) { false }
                if (page.isEmpty()) break
                actual += page; after = page.last().callId
            }
            assertEquals(rows.filter { it.nextAttemptAt <= 100 }.map { RecordingCandidate(it.callId, 7) }, actual)
            val before = store.readCounters.snapshot()
            assertTrue((before["warmMetadataRows"] ?: 0) + (before["coldIdentityRows"] ?: 0) > actual.size)
            File("build/qa/layer-boundaries/t10b1-pagination-future.json").apply {
                parentFile!!.mkdirs(); writeText(JSONObject().put("returned", actual.size).put("totals", JSONObject(before)).toString(2))
            }
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                store.dueCandidates(100, null, 64) { ++checks > 10 }
            }
            assertTrue((store.readCounters.snapshot()["warmMetadataRows"] ?: 0) > (before["warmMetadataRows"] ?: 0))
            // Remaining warm rows finish before cancellation reaches the cold source stream.
            checks = 0
            val sourceBefore = store.readCounters.snapshot()["sourceBytes"] ?: 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                store.dueCandidates(100, 86, 64) { ++checks > 8 }
            }
            assertTrue((store.readCounters.snapshot()["sourceBytes"] ?: 0) > sourceBefore)
            assertEquals(marker, store.coordinator.marker())
            assertUnchangedIndex(namespace, rows)
        }
    }
    private fun fixture(namespace: String, rows: List<CallArchive>): AndroidRecordingStore {
        AndroidRecordingStore(context, namespace, cipher).use { it.scopedReads() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                val index = ArchiveProjectionIndex(db)
                for (row in rows) {
                    db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(row.callId, row.generation, RecordingJson.archive(row).toString()))
                    if (row.callId % 2L == 0L) index.write(row)
                    else if (row.callId % 5L == 0L) index.write(row.copy(generation = 6, status = ArchiveStatus.COMPLETE))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return AndroidRecordingStore(context, namespace, cipher)
    }
    private fun assertUnchangedIndex(namespace: String, rows: List<CallArchive>) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(rows.count { it.callId % 2L == 0L || it.callId % 5L == 0L }.toLong(), DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archive_projection", null))
            assertEquals(0L, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archive_parse_progress", null))
            assertEquals(rows.count { it.callId % 2L == 0L }.toLong(), DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archive_projection WHERE generation=7", null))
        }
    }
}
