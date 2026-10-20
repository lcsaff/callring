package com.greatwall.callring.platform.ai

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.recording.*
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Leaf observations contain counts only, never SQL, paths, numbers or transcript text. */
class RecordingReadCounters {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    internal fun add(key: String, value: Long = 1) { counts.computeIfAbsent(key) { AtomicLong() }.addAndGet(value) }
    internal fun maximum(key: String, value: Long) { counts.computeIfAbsent(key) { AtomicLong() }.updateAndGet { maxOf(it, value) } }
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}

/** Metadata never creates a row in the D16 full-text projection or its progress tables. */
internal class RecordingMetadataReads(private val db: SQLiteDatabase, private val counters: RecordingReadCounters,
    private val cancelled: () -> Boolean) {
    private fun active() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("recording.cancelled")
    }
    private fun <T> read(block: () -> T): T {
        active()
        db.beginTransactionNonExclusive()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private val pending = setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING, ArchiveStatus.FAILED)
    private val joined = "archive_projection p JOIN archives a ON a.call_id=p.call_id AND a.generation=p.generation"

    /** UTF-8 is decoded across byte chunks; SQLite never returns a whole payload to this reader. */
    private inner class PayloadInput(private val id: Long, private val generation: Long) : InputStream() {
        private var offset = 0L
        private var bytes = ByteArray(0)
        private var at = 0
        override fun read(): Int {
            if (at == bytes.size) {
                active()
                bytes = db.rawQuery("SELECT substr(CAST(payload AS BLOB),?,4096) FROM archives WHERE call_id=? AND generation=?",
                    arrayOf((offset + 1).toString(), id.toString(), generation.toString())).use { c ->
                    check(c.moveToFirst()) { "recording.changed" }
                    c.getBlob(0)
                }
                counters.add("sourceChunkQueries")
                counters.add("sourceBytes", bytes.size.toLong())
                counters.maximum("maxSourceChunkBytes", bytes.size.toLong())
                if (bytes.isEmpty()) return -1
                offset += bytes.size; at = 0
            }
            return bytes[at++].toInt() and 255
        }
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first < 0) return -1
            buffer[off] = first.toByte()
            val count = minOf(len - 1, bytes.size - at)
            bytes.copyInto(buffer, off + 1, at, at + count); at += count
            return count + 1
        }
    }
    private data class Minimal(val metadata: RecordingArchiveMetadata, val audio: String?)
    private fun payload(id: Long, generation: Long): Minimal {
        counters.add("payloadMetadataRows")
        var encodedId: Long? = null; var encodedGeneration: Long? = null
        var number: String? = null; var started: Long? = null; var status: ArchiveStatus? = null
        var source: RecordingSource? = null; var duration: Long? = null; var next: Long? = null; var audio: String? = null
        JsonReader(InputStreamReader(PayloadInput(id, generation), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                active()
                when (reader.nextName()) {
                    "id" -> encodedId = reader.nextLong()
                    "generation" -> encodedGeneration = reader.nextLong()
                    "number" -> { number = reader.nextString(); counters.add("metadataCharacters", number!!.length.toLong()) }
                    "started" -> started = reader.nextLong()
                    "status" -> status = ArchiveStatus.valueOf(reader.nextString())
                    "source" -> source = RecordingSource.valueOf(reader.nextString())
                    "duration" -> if (reader.peek() == JsonToken.NULL) { reader.nextNull(); duration = null } else duration = reader.nextLong()
                    "next" -> next = reader.nextLong()
                    "audio" -> if (reader.peek() == JsonToken.NULL) { reader.nextNull(); audio = null } else {
                        audio = reader.nextString(); counters.add("metadataCharacters", audio!!.length.toLong())
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            check(reader.peek() == JsonToken.END_DOCUMENT) { "recording.invalid_archive" }
        }
        return Minimal(RecordingArchiveMetadata(requireNotNull(encodedId), requireNotNull(encodedGeneration), requireNotNull(number), requireNotNull(started), requireNotNull(status), requireNotNull(source), duration, audio != null, requireNotNull(next)), audio)
    }
    private fun warm(text: String): RecordingArchiveMetadata {
        counters.add("projectionMetadataBytes", text.toByteArray(Charsets.UTF_8).size.toLong())
        val value = org.json.JSONObject(text)
        return RecordingArchiveMetadata(value.getLong("callId"), value.getLong("generation"), value.getString("number"),
            value.getLong("startedAtEpochMillis"), ArchiveStatus.valueOf(value.getString("status")), RecordingSource.valueOf(value.getString("source")),
            if (value.isNull("durationMillis")) null else value.getLong("durationMillis"), value.getBoolean("audioPresent"), value.getLong("nextAttemptAt"))
    }
    private fun cold(after: Long? = null, accept: (Minimal) -> Boolean) {
        // Missing historical projection has no searchable metadata: enumerate IDs only, then stream each source.
        db.rawQuery("SELECT a.call_id,a.generation FROM archives a LEFT JOIN archive_projection p ON a.call_id=p.call_id AND a.generation=p.generation WHERE p.call_id IS NULL" +
            (if (after == null) "" else " AND a.call_id>?") + " ORDER BY a.call_id", after?.let { arrayOf(it.toString()) }).use { c ->
            while (c.moveToNext()) { active(); counters.add("coldIdentityRows"); if (!accept(payload(c.getLong(0), c.getLong(1)))) break }
        }
    }
    fun metadata(id: Long): RecordingArchiveMetadata? = read {
        db.rawQuery("SELECT a.generation,p.metadata FROM archives a LEFT JOIN archive_projection p ON a.call_id=p.call_id AND a.generation=p.generation WHERE a.call_id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) null else if (!c.isNull(1)) {
                counters.add("warmMetadataRows"); warm(c.getString(1))
            } else payload(id, c.getLong(0)).metadata
        }
    }
    fun earliestPendingAt(): Long? = read {
        var result: Long? = null
        fun accept(row: RecordingArchiveMetadata) {
            if (row.status in pending && row.nextAttemptAt != Long.MAX_VALUE) result = result?.let { minOf(it, row.nextAttemptAt) } ?: row.nextAttemptAt
        }
        db.rawQuery("SELECT p.metadata FROM $joined WHERE p.status IN ('QUEUED','TRANSCRIBING','ANALYZING','FAILED')", null).use { c ->
            while (c.moveToNext()) { active(); counters.add("warmMetadataRows"); accept(warm(c.getString(0))) }
        }
        cold { accept(it.metadata); true }; result
    }
    fun dueCandidates(at: Long, after: Long?, limit: Int): List<RecordingCandidate> = read {
        require(limit in 1..256)
        val result = java.util.TreeMap<Long, RecordingCandidate>()
        fun accept(row: RecordingArchiveMetadata): Boolean {
            if (row.status in pending && row.nextAttemptAt <= at) {
                result[row.callId] = RecordingCandidate(row.callId, row.generation)
                if (result.size > limit) result.pollLastEntry()
                return true
            }
            return false
        }
        db.rawQuery("SELECT p.metadata FROM $joined WHERE p.status IN ('QUEUED','TRANSCRIBING','ANALYZING','FAILED')" +
            (if (after == null) "" else " AND p.call_id>?") + " ORDER BY p.call_id", after?.let { arrayOf(it.toString()) }).use { c ->
            var due = 0
            while (due < limit && c.moveToNext()) {
                active(); counters.add("warmMetadataRows")
                if (accept(warm(c.getString(0)))) due++
            }
        }
        // Neither partition may suppress the other: its prefix can precede the collected IDs.
        var coldDue = 0
        cold(after) { if (accept(it.metadata)) coldDue++; coldDue < limit }
        result.values.toList()
    }
    fun recentMetadata(since: Long): List<RecordingArchiveMetadata> = read {
        buildList {
            db.rawQuery("SELECT p.metadata FROM $joined WHERE p.started>=? ORDER BY p.call_id", arrayOf(since.toString())).use { c ->
                while (c.moveToNext()) { active(); counters.add("warmMetadataRows"); add(warm(c.getString(0))) }
            }
            cold { if (it.metadata.startedAtEpochMillis >= since) add(it.metadata); true }
        }.sortedBy { it.callId }
    }
    fun numberMetadata(number: String): List<NumberRecordingMetadata> = read {
        buildList {
            fun accept(row: Minimal) {
                if (sameRecordingNumber(row.metadata.number, number)) add(NumberRecordingMetadata(row.metadata.callId, row.metadata.generation,
                    row.metadata.status, row.audio?.let { runCatching { java.io.File(it).length() }.getOrDefault(0L) } ?: 0L, row.metadata.startedAtEpochMillis))
            }
            // Raw numbers must be compared using Char.isDigit, including arbitrary punctuation and Unicode digits.
            db.rawQuery("SELECT p.call_id,p.generation,p.number FROM $joined ORDER BY p.call_id", null).use { c ->
                while (c.moveToNext()) {
                    active(); counters.add("warmNumberRows")
                    if (sameRecordingNumber(c.getString(2), number)) { counters.add("targetMetadataRows"); accept(payload(c.getLong(0), c.getLong(1))) }
                }
            }
            cold { accept(it); true }
        }.sortedWith(compareByDescending<NumberRecordingMetadata> { it.startedAtEpochMillis }.thenBy { it.callId })
    }
}
