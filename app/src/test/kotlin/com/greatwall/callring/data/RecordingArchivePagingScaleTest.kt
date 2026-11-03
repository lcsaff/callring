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
class RecordingArchivePagingScaleTest {
    private val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }

    @Test fun thousandAndTenThousandArchivesUseIndexSeeksAndBoundedMetadataPages() {
        for (size in listOf(1000, 10000)) {
            val context = RuntimeEnvironment.getApplication()
            val namespace = "paging-scale-$size"
            AndroidRecordingStore(context, namespace, cipher, recordingVersion = { 0 }).use { store ->
                repeat(size) { i ->
                    val entry = CallArchive(-(i + 1L), "31234", (i / 3).toLong(), generation = 1,
                        transcript = if (i % 1000 == 0) "长转写".repeat(80000) else "short transcript",
                        analysis = CallAnalysis(summary = "summary $i"))
                    assertTrue(store.compareAndSet(entry.callId, null, entry))
                }
                context.openOrCreateDatabase(namespace + "_recording.db", 0, null).use { db ->
                    for ((where, args) in listOf("p.started=? AND p.call_id>?" to arrayOf("100", "-301"), "p.started<?" to arrayOf("100"))) {
                        val plan = db.rawQuery("EXPLAIN QUERY PLAN SELECT p.metadata FROM archive_projection p JOIN archives a ON a.call_id=p.call_id AND a.generation=p.generation WHERE $where ORDER BY p.started DESC,p.call_id ASC LIMIT 51", args).use { c ->
                            buildList { while (c.moveToNext()) add(c.getString(c.columnCount - 1)) }.joinToString("\n")
                        }
                        assertTrue(plan, Regex("SEARCH[^\\n]*archive_projection[^\\n]*archive_list_order").containsMatchIn(plan))
                    }
                }
                var cursor: ArchiveListCursor? = null
                var total = 0
                var pages = 0
                var maxNanos = 0L
                var sampledHeap = 0L
                val started = System.nanoTime()
                do {
                    val before = store.readCounters.snapshot()["listMetadataRows"] ?: 0L
                    val at = System.nanoTime()
                    val page = store.queryArchives(ArchiveListQuery(cursor)) { false }
                    maxNanos = maxOf(maxNanos, System.nanoTime() - at)
                    sampledHeap = maxOf(sampledHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    assertFalse(page.preparing)
                    assertTrue((store.readCounters.snapshot()["listMetadataRows"] ?: 0L) - before <= 51L)
                    total += page.items.size
                    cursor = page.nextCursor
                    assertEquals(cursor == null, page.complete)
                    pages++
                } while (cursor != null)
                assertEquals(size, total)
                assertEquals((size + 49) / 50, pages)
                assertEquals(0L, store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0L)
                assertEquals(0L, store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0L)
                println("ARCHIVE_LIST_BENCHMARK rows=$size pages=$pages elapsedMs=${(System.nanoTime()-started)/1000000} maxPageMs=${maxNanos/1000000} sampledJvmHeapBytes=$sampledHeap fullDecodes=0")
                context.openOrCreateDatabase(namespace + "_recording.db", 0, null).use { db ->
                    listOf("archive_projection", "archive_text_fields", "archive_text_chunks", "archive_parse_progress").forEach { db.execSQL("DELETE FROM $it") }
                    db.execSQL("UPDATE archive_list_state SET ready=0")
                }
                val coldStarted = System.nanoTime()
                var preparationCalls = 0
                var coldHeap = 0L
                var prepared: ArchiveListPage
                do {
                    val before = store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0L
                    prepared = store.queryArchives(ArchiveListQuery()) { false }
                    coldHeap = maxOf(coldHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    assertTrue((store.readCounters.snapshot()["listIndexSourceBytes"] ?: 0L) - before <= 65536L)
                    if (prepared.preparing) { assertFalse(prepared.complete); assertTrue(prepared.items.isEmpty()) }
                    assertTrue(++preparationCalls <= size * 2)
                } while (prepared.preparing)
                assertEquals(50, prepared.items.size)
                assertEquals(0L, store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0L)
                println("ARCHIVE_LIST_COLD_BENCHMARK rows=$size requests=$preparationCalls elapsedMs=${(System.nanoTime()-coldStarted)/1000000} sampledJvmHeapBytes=$coldHeap fullDecodes=0")
            }
        }
    }
}
