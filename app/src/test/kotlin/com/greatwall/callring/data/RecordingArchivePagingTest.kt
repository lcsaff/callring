package com.greatwall.callring.data

import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import com.greatwall.callring.contracts.recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingArchivePagingTest {
    @Test fun openingListDoesNotDecodeFullTranscripts() {
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "paging-red",
            object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value },
            recordingVersion = { 0 })
        store.use {
            repeat(5) { index ->
                val archive = CallArchive(-(index + 1L), "31234", index.toLong(), generation = 1,
                    transcript = "长转写".repeat(10000), analysis = CallAnalysis(summary = "摘要"))
                assertTrue(store.compareAndSet(archive.callId, null, archive))
            }
            val page = store.queryArchives(ArchiveListQuery()) { false }
            assertEquals(5, page.items.size)
            assertTrue(page.complete)
            assertTrue(page.items.all { it.summary == "摘要" })
            assertEquals(0L, store.readCounters.snapshot()["listArchiveDecodes"] ?: 0L)
            assertEquals(0L, store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0L)
        }
    }

    @Test fun keysetOrderUsesTimeThenSignedIdentityAndRejectsChangedOrForeignCursor() {
        val context = RuntimeEnvironment.getApplication()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(context, "paging-order", cipher, recordingVersion = { 0 })
        store.use {
            listOf(-3L to 100L, 4L to 100L, -1L to 101L, 2L to 99L).forEach { (id, at) ->
                store.compareAndSet(id, null, CallArchive(id, "31234", at, generation = 1,
                    analysis = CallAnalysis(summary = "😀".repeat(200))))
            }
            val first = store.queryArchives(ArchiveListQuery(limit = 2)) { false }
            assertEquals(listOf(-1L, -3L), first.items.map { it.callId })
            assertEquals(160, first.items.first().summary.codePointCount(0, first.items.first().summary.length))
            assertFalse(first.complete)
            val before = store.readCounters.snapshot()["listMetadataRows"] ?: 0
            val second = store.queryArchives(ArchiveListQuery(first.nextCursor, 2)) { false }
            assertEquals(listOf(4L, 2L), second.items.map { it.callId })
            assertTrue(second.complete)
            assertEquals(2L, (store.readCounters.snapshot()["listMetadataRows"] ?: 0) - before)
            assertThrows(IllegalArgumentException::class.java) { store.queryArchives(ArchiveListQuery(limit = 101)) { false } }
            store.compareAndSet(5L, null, CallArchive(5L, "31234", 102L, generation = 1))
            assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) {
                store.queryArchives(ArchiveListQuery(first.nextCursor, 2)) { false }
            }
            val ownerCursor = store.queryArchives(ArchiveListQuery(limit = 2)) { false }.nextCursor
            AndroidRecordingStore(context, "paging-order", cipher, recordingVersion = { 0 }).use { other ->
                assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) {
                    other.queryArchives(ArchiveListQuery(ownerCursor, 2)) { false }
                }
            }
        }
    }

    @Test fun legacyIndexPreparationIsBoundedResumableAndDoesNotRewritePayload() {
        val context = RuntimeEnvironment.getApplication()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val namespace = "paging-legacy"
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0 }).use { store ->
            store.compareAndSet(-1, null, CallArchive(-1, "31234", 100, generation = 1,
                transcript = "转写😀".repeat(25000), analysis = CallAnalysis(summary = "旧归档摘要")))
        }
        var original = ""
        context.openOrCreateDatabase(namespace + "_recording.db", 0, null).use { db ->
            original = db.rawQuery("SELECT payload FROM archives", null).use { it.moveToFirst(); it.getString(0) }
            db.execSQL("DELETE FROM archive_projection")
            db.execSQL("DELETE FROM archive_text_chunks")
            db.execSQL("DELETE FROM archive_text_fields")
            db.version = 4
        }
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0 }).use { store ->
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                store.queryArchives(ArchiveListQuery()) { (store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0) >= 4096 }
            }
            assertEquals(4096L, store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0L)
            val before = store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0L
            val first = store.queryArchives(ArchiveListQuery()) { false }
            assertTrue(first.preparing)
            assertFalse(first.complete)
            assertTrue(first.items.isEmpty())
            assertTrue((store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0) - before <= 65536)
            assertThrows(java.util.concurrent.CancellationException::class.java) { store.queryArchives(ArchiveListQuery()) { true } }
        }
        AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0 }).use { reopened ->
            var page: ArchiveListPage
            var calls = 0
            do {
                val before = reopened.readCounters.snapshot()["listIndexSourceBytes"] ?: 0
                page = reopened.queryArchives(ArchiveListQuery()) { false }
                assertTrue((reopened.readCounters.snapshot()["listIndexSourceBytes"] ?: 0) - before <= 65536)
                assertTrue(++calls < 30)
            } while (page.preparing)
            assertEquals("旧归档摘要", page.items.single().summary)
            assertTrue(page.complete)
            assertEquals(0L, reopened.readCounters.snapshot()["fullArchiveDecodes"] ?: 0)
        }
        context.openOrCreateDatabase(namespace + "_recording.db", 0, null).use { db ->
            val unchanged = db.rawQuery("SELECT payload FROM archives", null).use { it.moveToFirst(); it.getString(0) }
            assertEquals(original, unchanged)
        }
    }
}
