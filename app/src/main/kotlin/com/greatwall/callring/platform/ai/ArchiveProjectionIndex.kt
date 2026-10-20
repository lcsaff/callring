package com.greatwall.callring.platform.ai

import com.greatwall.callring.data.codec.parseAiJson

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.safeValue
import java.util.concurrent.CancellationException

/** Derived index only. Legacy payload remains authoritative and is never rewritten by a query. */
internal class ArchiveProjectionIndex(private val db:SQLiteDatabase,private val onSourceRead:(Int)->Unit={}) {
    companion object {
        fun createProgress(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS archive_parse_progress(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,source_offset INTEGER NOT NULL,state TEXT NOT NULL)")}
        fun create(db:SQLiteDatabase){
            createProgress(db)
            db.execSQL("CREATE TABLE archive_projection(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,number TEXT,started INTEGER,status TEXT,metadata TEXT NOT NULL)")
            db.execSQL("CREATE TABLE archive_text_fields(call_id INTEGER,generation INTEGER,field TEXT,total INTEGER NOT NULL,PRIMARY KEY(call_id,generation,field))")
            db.execSQL("CREATE TABLE archive_text_chunks(call_id INTEGER,generation INTEGER,field TEXT,start INTEGER,end INTEGER,text TEXT NOT NULL,PRIMARY KEY(call_id,generation,field,start))")
            db.execSQL("CREATE TABLE archive_index_progress(call_id INTEGER PRIMARY KEY,generation INTEGER,raw_offset INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE archive_raw_chunks(call_id INTEGER,generation INTEGER,start INTEGER,text TEXT NOT NULL,PRIMARY KEY(call_id,generation,start))")
        }
    }
    private fun checkActive(){if(Thread.currentThread().isInterrupted)throw CancellationException("ai.cancelled")}
    private fun <T> transaction(block:()->T):T {db.beginTransaction();try{return block().also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
    fun remove(id:Long){listOf("archive_projection","archive_text_fields","archive_text_chunks","archive_index_progress","archive_raw_chunks","archive_parse_progress").forEach{db.delete(it,"call_id=?",arrayOf(id.toString()))}}
    private fun text(id:Long,generation:Long,field:String,value:String){
        val total=value.codePointCount(0,value.length)
        db.execSQL("INSERT OR REPLACE INTO archive_text_fields VALUES(?,?,?,?)",arrayOf<Any>(id,generation,field,total))
        var offset=0;var point=0
        while(offset<value.length){checkActive();val end=value.offsetByCodePoints(offset,minOf(4096,total-point));val chunk=value.substring(offset,end);val next=point+chunk.codePointCount(0,chunk.length)
            db.execSQL("INSERT OR REPLACE INTO archive_text_chunks VALUES(?,?,?,?,?,?)",arrayOf<Any>(id,generation,field,point,next,chunk));offset=end;point=next}
    }
    private fun project(id:Long,generation:Long,path:String,value:AiJson):AiJson=when(value){
        is AiJson.Str -> if(path=="transcript"||path.startsWith("analysis.")||value.value.codePointCount(0,value.value.length)>4096){text(id,generation,path,value.value);AiJson.Null}else value
        is AiJson.Obj -> AiJson.Obj(value.fields.mapValues{(key,child)->project(id,generation,if(path.isEmpty())key else "$path.$key",child)})
        is AiJson.Arr -> AiJson.Arr(value.values.mapIndexed{i,child->project(id,generation,"$path[$i]",child)})
        else -> value
    }
    private fun saveMetadata(id:Long,generation:Long,metadata:AiJson.Obj){
        require(metadata["callId"].long()==id&&metadata["generation"].long()==generation)
        db.execSQL("INSERT OR REPLACE INTO archive_projection VALUES(?,?,?,?,?,?)",arrayOf<Any>(id,generation,metadata["number"].string(),metadata["startedAtEpochMillis"].long(),metadata["status"].string(),metadata.encode()))
    }
    /** Caller holds the existing archive business transaction. */
    fun write(archive:CallArchive){remove(archive.callId);saveMetadata(archive.callId,archive.generation,project(archive.callId,archive.generation,"",safeValue(archive)) as AiJson.Obj)}
    fun indexed(id:Long,generation:Long)=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archive_projection WHERE call_id=? AND generation=?",arrayOf(id.toString(),generation.toString()))==1L
    data class Advance(val complete:Boolean,val sourceBytes:Int,val transactions:Int=0)
    fun advance(id:Long,generation:Long,budget:Int):Advance {
        if(budget<4)return Advance(false,0)
        checkActive()
        if(indexed(id,generation))return Advance(true,0)
        val saved=db.rawQuery("SELECT generation,state FROM archive_parse_progress WHERE call_id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst()&&c.getLong(0)==generation)parseAiJson(c.getString(1)) as AiJson.Obj else null}
        fun current(){val now=db.rawQuery("SELECT generation FROM archives WHERE call_id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())c.getLong(0)else null};if(now!=generation)throw BackendFailure(ApiError(ErrorCode.CONFLICT,"recording.changed"))}
        if(saved==null)transaction {
            current()
            listOf("archive_projection","archive_text_fields","archive_text_chunks","archive_parse_progress").forEach{db.delete(it,"call_id=?",arrayOf(id.toString()))}
        }
        val parser=ArchiveStreamParser(db,id,generation,saved)
        val total=db.rawQuery("SELECT length(CAST(payload AS BLOB)) FROM archives WHERE call_id=? AND generation=?",arrayOf(id.toString(),generation.toString())).use{c->if(c.moveToFirst())c.getLong(0)else throw BackendFailure(ApiError(ErrorCode.CONFLICT,"recording.changed"))}
        var bytes=0;var transactions=if(saved==null)1 else 0
        while(parser.sourceOffset<total&&budget-bytes>=4){
            checkActive();val limit=minOf(4096,budget-bytes)
            val raw=db.rawQuery("SELECT substr(CAST(payload AS BLOB),?,?) FROM archives WHERE call_id=? AND generation=?",arrayOf((parser.sourceOffset+1).toString(),limit.toString(),id.toString(),generation.toString())).use{c->if(c.moveToFirst())c.getBlob(0)else throw BackendFailure(ApiError(ErrorCode.CONFLICT,"recording.changed"))}
            if(raw.isEmpty())throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE,"ai.archive_source_shape"))
            bytes+=raw.size;onSourceRead(raw.size);checkActive()
            val input=java.nio.ByteBuffer.wrap(raw);val chars=java.nio.CharBuffer.allocate(raw.size)
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(input,chars,parser.sourceOffset+raw.size==total)
            require(!decoded.isError&&input.position()>0){"ai.archive_source_shape"};chars.flip()
            transaction {
                parser.consume(chars.toString(),input.position())
                current()
                db.execSQL("INSERT OR REPLACE INTO archive_parse_progress VALUES(?,?,?,?)",arrayOf<Any>(id,generation,parser.sourceOffset,parser.checkpoint()))
            };transactions++
        }
        if(parser.sourceOffset<total)return Advance(false,bytes,transactions)
        checkActive()
        // Discard obsolete v3 staging in bounded batches. No payload is decoded again.
        val pending=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archive_raw_chunks WHERE call_id=?",arrayOf(id.toString()))
        if(pending>0){transaction{db.execSQL("DELETE FROM archive_raw_chunks WHERE rowid IN (SELECT rowid FROM archive_raw_chunks WHERE call_id=? LIMIT 64)",arrayOf(id))};transactions++;if(pending>64)return Advance(false,bytes,transactions)}
        transaction {
            checkActive();current();saveMetadata(id,generation,parser.finish())
            db.delete("archive_parse_progress","call_id=?",arrayOf(id.toString()));db.delete("archive_index_progress","call_id=?",arrayOf(id.toString()))
        };transactions++
        return Advance(true,bytes,transactions)
    }
}
