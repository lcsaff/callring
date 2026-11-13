package com.greatwall.callring.data.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Operates exclusively on private, closed candidates. Originals remain journal rollback inputs. */
internal object NumberProfileResetStaging {
    fun transform(core: File, recording: File, keep: Boolean, check: () -> Unit) {
        SQLiteDatabase.openDatabase(recording.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS core",arrayOf(core.path))
            db.beginTransaction()
            try {
                db.execSQL("CREATE TEMP TABLE reset_identity(old_id INTEGER PRIMARY KEY,event_id INTEGER NOT NULL)")
                // A positive tombstone without metadata remains opaque. Its old ID is protected
                // by the preserved core high-water mark rather than a fabricated caller identity.
                val high = DatabaseUtils.longForQuery(db,"SELECT MAX(value) FROM (SELECT COALESCE(MAX(id),0) value FROM core.calls UNION ALL SELECT COALESCE(MAX(call_id),0) FROM archives UNION ALL SELECT COALESCE(MAX(call_id),0) FROM deleted_archives UNION ALL SELECT COALESCE(MAX(archive_id),0) FROM system_recording_sources UNION ALL SELECT COALESCE(MAX(seq),0) FROM core.sqlite_sequence WHERE name='calls')",null)
                db.execSQL("INSERT OR IGNORE INTO core.sqlite_sequence(name,seq) SELECT 'calls',? WHERE NOT EXISTS(SELECT 1 FROM core.sqlite_sequence WHERE name='calls')",arrayOf(high))
                db.execSQL("UPDATE core.sqlite_sequence SET seq=MAX(seq,?) WHERE name='calls'",arrayOf(high))
                // Discarded content never acquires a reset tombstone. Existing explicit deletions
                // retain just enough identity/source provenance to remain explicit deletions.
                if (!keep) {
                    db.execSQL("DELETE FROM system_recording_sources WHERE archive_id NOT IN(SELECT call_id FROM deleted_archives)")
                    db.execSQL("DELETE FROM archives")
                    db.execSQL("DELETE FROM recording_retained_origins WHERE event_id NOT IN(SELECT -call_id FROM deleted_archives WHERE call_id<0)")
                    db.execSQL("DELETE FROM recording_retained_events WHERE event_id NOT IN(SELECT -call_id FROM deleted_archives WHERE call_id<0)")
                    db.execSQL("DELETE FROM recording_events WHERE -id NOT IN(SELECT call_id FROM deleted_archives)")
                }
                var after = 0L
                while (true) {
                    check()
                    val ids = db.rawQuery("SELECT id FROM (SELECT call_id id FROM archives WHERE call_id>0 UNION SELECT call_id FROM deleted_archives WHERE call_id>0 UNION SELECT archive_id FROM system_recording_sources WHERE archive_id>0) WHERE id>? ORDER BY id LIMIT 32",arrayOf(after.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
                    if(ids.isEmpty()) break
                    for(id in ids) {
                        check(); after=id
                        val fields = if(db.rawQuery("SELECT 1 FROM archives WHERE call_id=?",arrayOf(id.toString())).use { it.moveToFirst() }) ArchiveBackupFields.fields(db,id,check) else null
                        data class Identity(val number:String,val started:Long,val account:String?,val direction:String)
                        val identity = fields?.let { Identity(requireNotNull(ArchiveBackupFields.string(it["number"])),it.getValue("started").scalar!!.toLong(),null,if(it["direction"]?.present == true) requireNotNull(ArchiveBackupFields.string(it["direction"])) else "INCOMING") }
                            ?: db.rawQuery("SELECT number,time,account,direction FROM core.calls WHERE id=?",arrayOf(id.toString())).use { c -> if(c.moveToFirst()) Identity(c.getString(0),c.getLong(1),if(c.isNull(2))null else c.getString(2),c.getString(3)) else null }
                            ?: db.rawQuery("SELECT number,started,NULL,direction FROM system_recording_sources WHERE archive_id=? LIMIT 1",arrayOf(id.toString())).use { c -> if(c.moveToFirst()) Identity(c.getString(0),c.getLong(1),null,c.getString(3)) else null }
                            ?: continue
                        db.execSQL("INSERT INTO recording_events(session_id,number,started,account,direction) VALUES(?,?,?,?,?)",arrayOf<Any?>("reset:${UUID.randomUUID()}",identity.number,identity.started,identity.account,identity.direction))
                        val event = DatabaseUtils.longForQuery(db,"SELECT last_insert_rowid()",null)
                        db.execSQL("INSERT INTO reset_identity VALUES(?,?)",arrayOf(id,event))
                        if(fields!=null) {
                            ArchiveBackupFields.replace(db,id,fields,mapOf("id" to (-event).toString()))
                            db.execSQL("UPDATE archives SET call_id=? WHERE call_id=?",arrayOf(-event,id))
                        }
                        db.execSQL("UPDATE deleted_archives SET call_id=? WHERE call_id=?",arrayOf(-event,id))
                        db.execSQL("UPDATE system_recording_sources SET archive_id=?,direction=? WHERE archive_id=?",arrayOf<Any>(-event,identity.direction,id))
                    }
                }
                // Every preserved negative event gets a marker, including source-less manual
                // archives and tombstones. Old core links provide exact provider provenance.
                db.rawQuery("SELECT id,number,started FROM recording_events WHERE -id IN(SELECT call_id FROM archives UNION SELECT call_id FROM deleted_archives)",null).use { c -> while(c.moveToNext()) {
                    check();db.execSQL("INSERT OR IGNORE INTO recording_retained_events VALUES(?,?,?)",arrayOf<Any>(c.getLong(0),c.getString(1).filter(Char::isDigit),c.getLong(2)))
                } }
                db.execSQL("INSERT OR REPLACE INTO recording_retained_origins SELECT c.log_id,m.event_id,c.number,c.time,c.direction FROM core.calls c JOIN reset_identity m ON m.old_id=COALESCE(c.linked_screen_id,c.id) WHERE c.log_id IS NOT NULL")
                db.execSQL("INSERT OR REPLACE INTO recording_retained_origins SELECT c.log_id,e.id,c.number,c.time,c.direction FROM core.calls c JOIN recording_statistics_links l ON l.statistics_id=COALESCE(c.linked_screen_id,c.id) JOIN recording_events e ON e.session_id=l.session_id JOIN recording_retained_events r ON r.event_id=e.id WHERE c.log_id IS NOT NULL")
                db.execSQL("DELETE FROM recording_statistics_links")
                db.execSQL("DELETE FROM system_recording_inventory"); db.execSQL("DELETE FROM system_recording_scan")
                var cursor:Long?=null
                while(true) {
                    check()
                    val ids=db.rawQuery("SELECT call_id FROM archives"+(if(cursor==null)"" else " WHERE call_id>?")+" ORDER BY call_id LIMIT 32",cursor?.let { arrayOf(it.toString()) }).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
                    if(ids.isEmpty())break
                    for(id in ids) {
                        check();cursor=id
                        val fields=ArchiveBackupFields.fields(db,id,check)
                        val status=ArchiveBackupFields.string(fields["status"])
                        val updates=linkedMapOf("resetPaused" to "true","next" to Long.MAX_VALUE.toString(),"generation" to Math.addExact(fields.getValue("generation").scalar!!.toLong(),1).toString())
                        if(status in setOf("QUEUED","TRANSCRIBING","ANALYZING","FAILED","WAITING_RECORDING")) {
                            updates["status"]=JSONObject.quote("FAILED");updates["error"]=JSONObject.quote("recording.reset_retry_required")
                        } else if(status in setOf("ENRICHING","ENRICHMENT_FAILED")) {
                            updates["status"]=JSONObject.quote("ENRICHMENT_FAILED");updates["error"]=JSONObject.quote("recording.reset_retry_required")
                        }
                        ArchiveBackupFields.replace(db,id,fields,updates)
                        db.execSQL("UPDATE archives SET generation=? WHERE call_id=?",arrayOf(updates.getValue("generation").toLong(),id))
                    }
                }
                listOf("archive_projection","archive_text_fields","archive_text_chunks","archive_index_progress","archive_raw_chunks","archive_parse_progress").forEach { db.execSQL("DELETE FROM $it") }
                db.execSQL("UPDATE archive_list_state SET ready=0")
                db.execSQL("UPDATE core.calls SET linked_screen_id=NULL WHERE linked_screen_id IS NOT NULL")
                db.execSQL("DELETE FROM core.calls");db.execSQL("DELETE FROM core.callers")
                db.execSQL("DELETE FROM core.settings WHERE key GLOB 'caller_deleted_at:*' OR key GLOB 'caller_confirmation:*'")
                check();db.setTransactionSuccessful()
            } finally { db.endTransaction();db.execSQL("DETACH DATABASE core") }
        }
    }
}
