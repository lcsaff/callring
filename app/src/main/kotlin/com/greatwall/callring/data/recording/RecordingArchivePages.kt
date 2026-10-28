package com.greatwall.callring.data.recording

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.recording.*
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CancellationException

/** List queries touch bounded metadata; legacy indexing advances under an explicit byte budget. */
internal class RecordingArchivePages(
    private val db: SQLiteDatabase,
    private val incarnation: String,
    private val counters: RecordingReadCounters,
    private val cancelled: () -> Boolean,
) {
    companion object {
        fun create(db: SQLiteDatabase, initiallyReady: Boolean = false) {
            db.execSQL("CREATE INDEX IF NOT EXISTS archive_list_order ON archive_projection(started DESC,call_id ASC)")
            db.execSQL("CREATE TABLE IF NOT EXISTS archive_list_state(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,ready INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO archive_list_state VALUES(1,0,${if (initiallyReady) 1 else 0})")
            if (!initiallyReady) db.execSQL("UPDATE archive_list_state SET ready=0 WHERE id=1")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS archive_list_insert AFTER INSERT ON archives BEGIN UPDATE archive_list_state SET revision=revision+1 WHERE id=1; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS archive_list_delete AFTER DELETE ON archives BEGIN UPDATE archive_list_state SET revision=revision+1 WHERE id=1; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS archive_list_update AFTER UPDATE ON archives WHEN OLD.generation<>NEW.generation OR OLD.payload<>NEW.payload BEGIN UPDATE archive_list_state SET revision=revision+1 WHERE id=1; END")
        }
    }

    private fun active() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("recording.cancelled")
    }

    fun query(query: ArchiveListQuery): ArchiveListPage {
        require(query.limit in 1..100)
        active()
        val revision = DatabaseUtils.longForQuery(db, "SELECT revision FROM archive_list_state WHERE id=1", null)
        val cursor = query.cursor
        if (cursor != null && (cursor.incarnation != incarnation || cursor.revision != revision || cursor.limit != query.limit))
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.page_changed", retryable = true))

        if (DatabaseUtils.longForQuery(db, "SELECT ready FROM archive_list_state WHERE id=1", null) == 0L) {
            val missing = "FROM archives a LEFT JOIN archive_projection p ON a.call_id=p.call_id AND a.generation=p.generation WHERE p.call_id IS NULL"
            val pending = db.rawQuery("SELECT a.call_id,a.generation $missing ORDER BY a.call_id LIMIT 16", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
            }
            var budget = 64 * 1024
            for ((id, generation) in pending) {
                active()
                if (budget < 4) break
                val result = ArchiveProjectionIndex(db, cancelled) { counters.add("listIndexSourceBytes", it.toLong()) }.advance(id, generation, budget)
                budget -= result.sourceBytes
            }
            val remaining = DatabaseUtils.longForQuery(db, "SELECT count(*) $missing", null)
            if (remaining > 0) return ArchiveListPage(emptyList(), null, complete = false, preparing = true, remaining = remaining)
            active()
            db.execSQL("UPDATE archive_list_state SET ready=1 WHERE id=1")
        }

        db.beginTransactionNonExclusive()
        try {
            active()
            val current = DatabaseUtils.longForQuery(db, "SELECT revision FROM archive_list_state WHERE id=1", null)
            if (current != revision) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.page_changed", retryable = true))
            // Two index seeks avoid SQLite scanning the skipped prefix for an OR predicate.
            // The second seek fills only the remaining slots, so total candidates <= limit+1.
            fun select(condition: String, args: Array<String>?, limit: Int): List<ArchiveListItem> = db.rawQuery("SELECT p.metadata,(SELECT substr(t.text,1,160) FROM archive_text_chunks t WHERE t.call_id=p.call_id AND t.generation=p.generation AND t.field='analysis.summary' AND t.start=0) " +
                "FROM archive_projection p JOIN archives a ON a.call_id=p.call_id AND a.generation=p.generation" + condition +
                " ORDER BY p.started DESC,p.call_id ASC LIMIT $limit", args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        active()
                        counters.add("listMetadataRows")
                        val value = JSONObject(c.getString(0))
                        add(ArchiveListItem(value.getLong("callId"), value.getLong("generation"), value.getString("number"),
                            value.getLong("startedAtEpochMillis"), ArchiveStatus.valueOf(value.getString("status")),
                            RecordingSource.valueOf(value.getString("source")),
                            if (value.isNull("durationMillis")) null else value.getLong("durationMillis"), value.getBoolean("audioPresent"),
                            if (c.isNull(1)) "" else c.getString(1)))
                    }
                }
            }
            val rows = if (cursor == null) select("", null, query.limit + 1) else {
                val equal = select(" WHERE p.started=? AND p.call_id>?", arrayOf(cursor.startedAtEpochMillis.toString(), cursor.callId.toString()), query.limit + 1)
                if (equal.size == query.limit + 1) equal else equal + select(" WHERE p.started<?", arrayOf(cursor.startedAtEpochMillis.toString()), query.limit + 1 - equal.size)
            }
            val items = rows.take(query.limit)
            val next = if (rows.size > query.limit) items.last().let {
                ArchiveListCursor(incarnation, revision, it.startedAtEpochMillis, it.callId, query.limit)
            } else null
            active()
            db.setTransactionSuccessful()
            return ArchiveListPage(Collections.unmodifiableList(items), next, complete = next == null)
        } finally { db.endTransaction() }
    }
}
