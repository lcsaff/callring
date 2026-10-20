package com.greatwall.callring.platform.ai

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.ports.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingMetadataReadsTest {
    private val testCoordinator = com.greatwall.callring.application.BusinessRevisionCoordinator()
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
    @Test fun unindexedOneThousandUnrelatedArchives() = cold(1_000)
    @Test fun unindexedTenThousandUnrelatedArchives() = cold(10_000)
    private fun cold(size: Int) {
        val namespace = "metadata-cold-$size"
        val path = context.getDatabasePath(namespace + "_recording.db").apply { parentFile!!.mkdirs() }
        var sourceSize = 0L
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE archives(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE deleted_archives(call_id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE recording_events(id INTEGER PRIMARY KEY,session_id TEXT,number TEXT,started INTEGER,account TEXT)")
            db.execSQL("CREATE TABLE recording_statistics_links(statistics_id INTEGER PRIMARY KEY,session_id TEXT)")
            db.version = 2
            db.beginTransaction()
            try {
                for (id in 0L..size.toLong()) {
                    val row = if (id == 0L) CallArchive(-7, "31234", 100, generation = 9, status = ArchiveStatus.QUEUED, nextAttemptAt = 77)
                        else CallArchive(id, "39999", 1, generation = 3, status = ArchiveStatus.COMPLETE)
                    val payload = RecordingJson.archive(row).apply { remove("manual") }.toString()
                    sourceSize += payload.toByteArray(Charsets.UTF_8).size
                    db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(row.callId, row.generation, payload))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { store ->
            assertEquals(77L, store.earliestPendingAt { false })
            assertEquals(listOf(RecordingCandidate(-7, 9)), store.dueCandidates(100, null, 64) { false })
            assertEquals(listOf(-7L), store.recentMetadata(100) { false }.map { it.callId })
            assertEquals(listOf(-7L), store.numberMetadata("31234") { false }.map { it.callId })
            val counts = store.readCounters.snapshot()
            assertEquals((size + 1) * 4L, counts["coldIdentityRows"])
            assertEquals(sourceSize * 4, counts["sourceBytes"])
            assertEquals(0L, counts["warmNumberRows"] ?: 0)
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0)
            File("build/qa/layer-boundaries/t10b1-metadata-cold-$size.json").apply { parentFile!!.mkdirs(); writeText(JSONObject(counts).toString(2)) }
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) { store.numberMetadata("31234") { ++checks > 25 } }
            assertTrue(store.readCounters.snapshot().getValue("sourceBytes") - counts.getValue("sourceBytes") < sourceSize)
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                for (table in listOf("archive_projection", "archive_parse_progress", "archive_text_fields", "archive_text_chunks"))
                    assertEquals(0L, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null))
                assertEquals(size + 1L, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archives", null))
            }
        }
    }
    @Test fun warmOneThousandUnrelatedArchives() = warm(1_000)
    @Test fun warmTenThousandUnrelatedArchives() = warm(10_000)
    private fun warm(size: Int) {
        val namespace = "metadata-warm-$size"
        val audio = File(context.cacheDir, "metadata-audio-$size").apply { writeBytes(ByteArray(73)) }
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { store ->
            store.scopedReads() // create schema; fixtures are outside measured operations
            SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    val index = ArchiveProjectionIndex(db)
                    for (id in 1L..size.toLong()) {
                        val row = CallArchive(id, "39999", 1, generation = 2, status = ArchiveStatus.COMPLETE)
                        db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(id, 2, RecordingJson.archive(row).toString()))
                        index.write(row)
                    }
                    val target = CallArchive(-7, "3\u200B1[2]3/4", 100, generation = 8, status = ArchiveStatus.QUEUED,
                        audioPath = audio.absolutePath, transcript = "😀\\\"".repeat(100_000), nextAttemptAt = 91)
                    db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(-7, 8, RecordingJson.archive(target).toString()))
                    index.write(target)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val marker = testCoordinator.marker()
            assertEquals(91L, store.earliestPendingAt { false })
            assertEquals(listOf(RecordingCandidate(-7, 8)), store.dueCandidates(100, null, 64) { false })
            assertEquals(listOf(-7L), store.recentMetadata(100) { false }.map { it.callId })
            assertEquals(8L, store.metadata(-7) { false }!!.generation)
            val readsBeforeNumber = store.readCounters.snapshot()
            assertEquals(0L, readsBeforeNumber["sourceBytes"] ?: 0)
            assertEquals(listOf(NumberRecordingMetadata(-7, 8, ArchiveStatus.QUEUED, 73, 100)), store.numberMetadata("31234") { false })
            val counts = store.readCounters.snapshot()
            assertEquals(size + 1L, counts["warmNumberRows"])
            assertEquals(1L, counts["payloadMetadataRows"])
            assertEquals(1L, counts["targetMetadataRows"])
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0)
            assertTrue((counts["metadataCharacters"] ?: 0) < 512)
            assertTrue((counts["maxSourceChunkBytes"] ?: 0) <= 4096)
            assertEquals(marker, testCoordinator.marker())
            File("build/qa/layer-boundaries/t10b1-metadata-warm-$size.json").apply { parentFile!!.mkdirs(); writeText(JSONObject(counts).toString(2)) }
        }
    }
    @Test fun legacyMetadataStreamsLargeTextWithoutPretendingD16IsIndexed() {
        val namespace = "metadata-cold"
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { it.scopedReads() }
        val path = context.getDatabasePath(namespace + "_recording.db")
        val transcript = "😀\\\"\n".repeat(250_000) + "LEGACY-尾"
        val payload = RecordingJson.archive(CallArchive(42, "١\u200B٢/٣", 200, generation = 7,
            status = ArchiveStatus.FAILED, transcript = transcript, nextAttemptAt = 123)).apply { remove("manual") }.toString()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO archives VALUES(42,7,?)", arrayOf(payload))
            val stale = CallArchive(42, "wrong", 0, generation = 6, status = ArchiveStatus.COMPLETE)
            ArchiveProjectionIndex(db).write(stale)
        }
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { store ->
            assertEquals(123L, store.earliestPendingAt { false })
            assertEquals(listOf(RecordingCandidate(42, 7)), store.dueCandidates(123, null, 1) { false })
            assertEquals(1, store.numberMetadata("١٢٣") { false }.size)
            assertTrue(store.numberMetadata("123") { false }.isEmpty()) // Char.isDigit preserves digit code points
            assertEquals("١\u200B٢/٣", store.recentMetadata(100) { false }.single().number)
            assertEquals(7L, store.generation(42))
            assertFalse(store.isDeleted(42))
            val counts = store.readCounters.snapshot()
            assertTrue(counts.getValue("sourceBytes") > payload.toByteArray().size)
            assertTrue(counts.getValue("metadataCharacters") < 512)
            assertEquals(4096L, counts["maxSourceChunkBytes"])
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0)
            File("build/qa/layer-boundaries/t10b1-metadata-cold.json").apply { parentFile!!.mkdirs(); writeText(JSONObject(counts).toString(2)) }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertFalse(ArchiveProjectionIndex(db).indexed(42, 7))
                assertEquals(0L, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM archive_parse_progress", null))
                assertEquals(payload, DatabaseUtils.stringForQuery(db, "SELECT payload FROM archives WHERE call_id=42", null))
            }
            var page = store.scopedReads().query(QuerySpec(AiDomain.D16, filter = QueryFilter(number = "١\u200B٢/٣")), null)
            var loops = 0
            while (page.status == QueryStatus.PARTIAL && loops++ < 10) page = store.scopedReads().query(QuerySpec(AiDomain.D16, filter = QueryFilter(number = "١\u200B٢/٣")), page.nextKey)
            assertEquals(1, page.rows.size)
            val total = transcript.codePointCount(0, transcript.length).toLong()
            assertEquals(total, page.rows.single().textFields.getValue("transcript"))
            assertTrue(store.scopedReads().text(AiDomain.D16, "42", "transcript", total - 8, 8).text.endsWith("LEGACY-尾"))
            assertTrue(store.scopedReads().query(QuerySpec(AiDomain.D16, filter = QueryFilter(number = "١٢٣")), null).rows.isEmpty())
        }
    }
    @Test fun nullableDuplicateMetadataUsesLastNullValueLikeOriginalCodec() {
        val namespace = "metadata-duplicate-null"
        val audio = File(context.cacheDir, "metadata-null.wav").apply { writeBytes(ByteArray(12)) }
        val base = RecordingJson.archive(CallArchive(42, "31234", 1, generation = 3, audioPath = audio.absolutePath, durationMillis = 123)).toString()
        val payload = base.dropLast(1) + ",\"audio\":null,\"duration\":null}"
        val original = RecordingJson.archive(JSONObject(payload))
        assertNull(original.audioPath)
        assertNull(original.durationMillis)
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { store ->
            store.scopedReads()
            SQLiteDatabase.openDatabase(context.getDatabasePath(namespace + "_recording.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO archives VALUES(42,3,?)", arrayOf(payload))
            }
            val metadata = store.metadata(42) { false }!!
            assertFalse(metadata.hasAudio)
            assertNull(metadata.durationMillis)
            assertEquals(0L, store.numberMetadata("31234") { false }.single().audioBytes)
        }
    }
    @Test fun cancellationAndInvalidRequiredMetadataCannotBecomeFalseEmptyResults() {
        val namespace = "metadata-cancel"
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { testCoordinator.capture().values.getValue("recording") }).use { store ->
            store.scopedReads()
            val path = context.getDatabasePath(namespace + "_recording.db")
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val payload = RecordingJson.archive(CallArchive(-1, "31234", 1, generation = 3)).apply { remove("status") }.toString()
                db.execSQL("INSERT INTO archives VALUES(-1,3,?)", arrayOf(payload))
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) { store.numberMetadata("31234") { true } }
            assertEquals(0L, store.readCounters.snapshot()["sourceBytes"] ?: 0)
            assertThrows(IllegalArgumentException::class.java) { store.earliestPendingAt { false } }
            assertThrows(IllegalArgumentException::class.java) { store.numberMetadata("31234") { false } }
        }
    }
}
