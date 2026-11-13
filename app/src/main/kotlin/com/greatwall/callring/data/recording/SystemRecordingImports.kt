package com.greatwall.callring.data.recording

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.contracts.recording.RecordingFile
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.recording.ParsedSystemRecording
import com.greatwall.callring.domain.recording.SystemRecordingAssociation

/** Private inventory contains URIs; only source bindings are portable backup data. */
internal class SystemRecordingImports(private val db: SQLiteDatabase) {
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE system_recording_sources(source_key TEXT PRIMARY KEY,archive_id INTEGER NOT NULL,number TEXT NOT NULL,started INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 0,direction TEXT NOT NULL DEFAULT 'INCOMING' CHECK(direction IN('INCOMING','OUTGOING','UNKNOWN')))")
            db.execSQL("CREATE INDEX system_sources_time ON system_recording_sources(started,number)")
            db.execSQL("CREATE TABLE system_recording_scan(id INTEGER PRIMARY KEY CHECK(id=1),generation INTEGER NOT NULL,signature TEXT NOT NULL,started INTEGER NOT NULL,days INTEGER NOT NULL,zone TEXT NOT NULL,complete INTEGER NOT NULL,next_at INTEGER,failures INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE system_recording_inventory(source_key TEXT PRIMARY KEY,scan_id INTEGER NOT NULL,signature TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,modified INTEGER NOT NULL,size INTEGER,known INTEGER NOT NULL,number TEXT NOT NULL,started INTEGER NOT NULL,stable_since INTEGER NOT NULL,observed INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX system_inventory_time ON system_recording_inventory(signature,started,number)")
            db.execSQL("CREATE INDEX system_inventory_scan ON system_recording_inventory(scan_id,source_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS recording_events_time ON recording_events(started,number)")
        }
        fun clearTransient(db: SQLiteDatabase) {
            db.execSQL("DELETE FROM system_recording_inventory")
            db.execSQL("DELETE FROM system_recording_scan")
        }
    }
    fun begin(signature: String, at: Long, zone: String): SystemRecordingScan = transaction {
        data class Previous(val generation: Long,val signature: String,val at: Long,val zone: String,val complete: Boolean)
        val old = db.rawQuery("SELECT generation,signature,started,zone,complete FROM system_recording_scan WHERE id=1", null).use { c ->
            if (!c.moveToFirst()) null else Previous(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3),c.getInt(4)==1)
        }
        val generation = (old?.generation ?: 0) + 1
        val resume = old?.takeIf { it.signature == signature && !it.complete }
        val round = SystemRecordingScan(generation,resume?.at ?: at,resume?.zone ?: zone)
        // The retired days column is retained for database compatibility and no longer interpreted.
        db.execSQL("INSERT OR REPLACE INTO system_recording_scan(id,generation,signature,started,days,zone,complete,next_at,failures) VALUES(1,?,?,?,?,?,0,NULL,0)", arrayOf<Any>(round.id, signature, round.at, 0, round.zone))
        // A different configuration cannot inherit accepted candidates or stability observations.
        db.delete("system_recording_inventory", "signature<>?", arrayOf(signature))
        round
    }
    fun observe(id: Long, entry: ScannedSystemRecording, parsed: ParsedSystemRecording, at: Long) = transaction {
        val signature = db.rawQuery("SELECT signature FROM system_recording_scan WHERE id=1 AND generation=? AND complete=0", arrayOf(id.toString())).use { check(it.moveToFirst()) { "recording.scan_changed" }; it.getString(0) }
        val file = entry.file
        val since = db.rawQuery("SELECT stable_since,observed,modified,size,known,number,started,signature FROM system_recording_inventory WHERE source_key=?", arrayOf(entry.sourceKey)).use { c ->
            if (c.moveToFirst() && c.getLong(1) <= at && c.getLong(2) == file.modifiedAt && !c.isNull(3) && c.getLong(3) == file.size &&
                c.getInt(4) == 1 && file.modifiedKnown && c.getString(5) == normalized(parsed.number) && c.getLong(6) == parsed.startedAtEpochMillis && c.getString(7) == signature) c.getLong(0) else at
        }
        db.execSQL("INSERT OR REPLACE INTO system_recording_inventory VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(entry.sourceKey, id, signature, file.uri, file.name,
            file.modifiedAt, file.size, if (file.modifiedKnown) 1 else 0, normalized(parsed.number), parsed.startedAtEpochMillis, since, at))
    }
    fun finish(id: Long) = transaction {
        check(db.compileStatement("UPDATE system_recording_scan SET complete=1 WHERE id=1 AND generation=?").use { it.bindLong(1, id); it.executeUpdateDelete() } == 1)
        db.delete("system_recording_inventory","scan_id<>?",arrayOf(id.toString()))
        Unit
    }
    fun candidates(id: Long, after: String?, limit: Int): List<SystemRecordingCandidate> {
        require(limit in 1..64)
        val args = mutableListOf(id.toString())
        if (after != null) args += after
        return db.rawQuery("SELECT source_key,uri,name,modified,size,known,number,started,stable_since,observed FROM system_recording_inventory WHERE scan_id=? AND EXISTS(SELECT 1 FROM system_recording_scan WHERE generation=scan_id AND complete=1)" +
            (if (after == null) "" else " AND source_key>?") + " ORDER BY source_key LIMIT $limit", args.toTypedArray()).use { c -> buildList {
                while (c.moveToNext()) add(SystemRecordingCandidate(ScannedSystemRecording(c.getString(0), RecordingFile(c.getString(1), c.getString(2), c.getLong(3), if(c.isNull(4)) null else c.getLong(4), c.getInt(5) == 1)),
                    ParsedSystemRecording(c.getString(6), c.getLong(7)), c.getLong(8), c.getLong(9)))
            } }
    }
    fun conflicting(id: Long, key: String, parsed: ParsedSystemRecording, target: RecordingCall): Boolean {
        // Keep the durable choice, including retries after a failed copy. A newly
        // discovered alternate must not replace an already-bound recording.
        val bound = db.rawQuery("SELECT source_key FROM system_recording_sources WHERE archive_id=? LIMIT 1", arrayOf(target.id.toString())).use { if(it.moveToFirst()) it.getString(0) else null }
        if (bound != null) return bound != key
        val range = SystemRecordingAssociation.recordingStarts(target.startedAt)
        // Multiple recordings can belong to one call. Select only a uniquely closest
        // start; an equally close file remains ambiguous. Include unstable competitors
        // so a shorter/farther file cannot win before the preferred file settles.
        return db.rawQuery("SELECT 1 FROM system_recording_inventory WHERE signature=(SELECT signature FROM system_recording_scan WHERE generation=?) AND source_key<>? AND number=? AND started BETWEEN ? AND ? AND known=1 AND size>0 AND ABS(started-?)<=CAST(? AS INTEGER) LIMIT 1",
            arrayOf(id.toString(),key,normalized(parsed.number),range.first.toString(),range.last.toString(),target.startedAt.toString(),kotlin.math.abs(parsed.startedAtEpochMillis-target.startedAt).toString())).use { it.moveToFirst() }
    }
    fun binding(key: String): RecordingCall? = db.rawQuery("SELECT archive_id,number,started,direction FROM system_recording_sources WHERE source_key=?", arrayOf(key)).use { if(it.moveToFirst()) RecordingCall(it.getLong(0),it.getString(1),it.getLong(2),null,com.greatwall.callring.domain.model.CallDirection.valueOf(it.getString(3))) else null }
    fun completed(key: String): Boolean = db.rawQuery("SELECT completed FROM system_recording_sources WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun conflictingLink(key: String, source: RecordingCall, target: RecordingCall): Boolean {
        val range = SystemRecordingAssociation.recordingStarts(target.startedAt)
        return db.rawQuery("SELECT 1 FROM system_recording_sources WHERE source_key<>? AND number=? AND started BETWEEN ? AND ? AND ABS(started-?)<=CAST(? AS INTEGER) LIMIT 1",
            arrayOf(key,normalized(source.number),range.first.toString(),range.last.toString(),target.startedAt.toString(),kotlin.math.abs(source.startedAt-target.startedAt).toString())).use { it.moveToFirst() }
    }
    fun releaseUnimported(key: String, callId: Long): Boolean =
        db.delete("system_recording_sources", "source_key=? AND archive_id=? AND completed=0", arrayOf(key,callId.toString())) > 0
    fun complete(key: String) { db.execSQL("UPDATE system_recording_sources SET completed=1 WHERE source_key=?", arrayOf(key)) }
    fun bind(key: String, call: RecordingCall) {
        check(!db.rawQuery("SELECT 1 FROM system_recording_sources WHERE archive_id=? AND source_key<>? LIMIT 1",arrayOf(call.id.toString(),key)).use { it.moveToFirst() }) { "recording.source_ambiguous" }
        db.execSQL("INSERT INTO system_recording_sources(source_key,archive_id,number,started,direction) VALUES(?,?,?,?,?)", arrayOf<Any>(key, call.id, normalized(call.number), call.startedAt, call.direction.name))
    }
    fun matching(parsed: ParsedSystemRecording): List<RecordingCall> {
        val close = matching(parsed, parsed.startedAtEpochMillis - 3000, parsed.startedAtEpochMillis + 3000)
        val range = SystemRecordingAssociation.callStarts(parsed.startedAtEpochMillis)
        return close.ifEmpty { matching(parsed,range.first,range.last) }
    }
    private fun matching(parsed: ParsedSystemRecording, start: Long, end: Long): List<RecordingCall> {
        val from = start.toString(); val to = end.toString()
        val result = linkedMapOf<Long, RecordingCall>()
        db.rawQuery("SELECT id,number,started,direction FROM recording_events WHERE started BETWEEN ? AND ? ORDER BY id", arrayOf(from,to)).use { c ->
            while(c.moveToNext()) if(normalized(c.getString(1)) == normalized(parsed.number)) {
                result[-c.getLong(0)] = RecordingCall(-c.getLong(0),c.getString(1),c.getLong(2),null,com.greatwall.callring.domain.model.CallDirection.valueOf(c.getString(3)))
                if(result.size >= 2) return result.values.toList()
            }
        }
        db.rawQuery("SELECT p.call_id,p.number,p.started,COALESCE((SELECT s.direction FROM system_recording_sources s WHERE s.archive_id=p.call_id LIMIT 1),'INCOMING') FROM archive_projection p JOIN archives a ON a.call_id=p.call_id AND a.generation=p.generation WHERE p.started BETWEEN ? AND ? ORDER BY p.call_id", arrayOf(from,to)).use { c ->
            while(c.moveToNext()) if(normalized(c.getString(1)) == normalized(parsed.number)) {
                result[c.getLong(0)] = RecordingCall(c.getLong(0),c.getString(1),c.getLong(2),null,com.greatwall.callring.domain.model.CallDirection.valueOf(c.getString(3)))
                if(result.size >= 2) break
            }
        }
        return result.values.toList()
    }
    fun retry(at: Long?): Long? {
        db.execSQL("UPDATE system_recording_scan SET next_at=? WHERE id=1", arrayOf(at))
        return at
    }
    fun sourcePage(after: String?, limit: Int): List<Pair<String,RecordingCall>> {
        require(limit in 1..64)
        return db.rawQuery("SELECT source_key,archive_id,number,started,direction FROM system_recording_sources" + (if(after == null) "" else " WHERE source_key>?") + " ORDER BY source_key LIMIT $limit", after?.let { arrayOf(it) }).use { c -> buildList {
            while(c.moveToNext()) add(c.getString(0) to RecordingCall(c.getLong(1),c.getString(2),c.getLong(3),null,com.greatwall.callring.domain.model.CallDirection.valueOf(c.getString(4))))
        } }
    }
    private fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() } }
    private fun normalized(number: String) = number.filter(Char::isDigit)
}
