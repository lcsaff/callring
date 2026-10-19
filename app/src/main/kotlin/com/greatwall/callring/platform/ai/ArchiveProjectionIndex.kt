package com.greatwall.callring.platform.ai

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.safeValue
import java.io.Reader
import java.util.concurrent.CancellationException

/** Derived index only. Legacy payload remains authoritative and is never rewritten by a query. */
internal class ArchiveProjectionIndex(private val db:SQLiteDatabase) {
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE archive_projection(call_id INTEGER PRIMARY KEY,generation INTEGER NOT NULL,number TEXT,started INTEGER,status TEXT,metadata TEXT NOT NULL)")
            db.execSQL("CREATE TABLE archive_text_fields(call_id INTEGER,generation INTEGER,field TEXT,total INTEGER NOT NULL,PRIMARY KEY(call_id,generation,field))")
            db.execSQL("CREATE TABLE archive_text_chunks(call_id INTEGER,generation INTEGER,field TEXT,start INTEGER,end INTEGER,text TEXT NOT NULL,PRIMARY KEY(call_id,generation,field,start))")
            db.execSQL("CREATE TABLE archive_index_progress(call_id INTEGER PRIMARY KEY,generation INTEGER,raw_offset INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE archive_raw_chunks(call_id INTEGER,generation INTEGER,start INTEGER,text TEXT NOT NULL,PRIMARY KEY(call_id,generation,start))")
        }
    }
    private fun checkActive(){if(Thread.currentThread().isInterrupted)throw CancellationException("ai.cancelled")}
    private fun <T> transaction(block:()->T):T {db.beginTransaction();try{return block().also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
    fun remove(id:Long){listOf("archive_projection","archive_text_fields","archive_text_chunks","archive_index_progress","archive_raw_chunks").forEach{db.delete(it,"call_id=?",arrayOf(id.toString()))}}
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
    data class Advance(val complete:Boolean,val sourceBytes:Int)
    fun advance(id:Long,generation:Long,budget:Int):Advance {
        if(indexed(id,generation))return Advance(true,0)
        var offset=db.rawQuery("SELECT generation,raw_offset FROM archive_index_progress WHERE call_id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst()&&c.getLong(0)==generation)c.getLong(1)else -1L}
        if(offset<0){transaction{remove(id);db.execSQL("INSERT INTO archive_index_progress VALUES(?,?,0)",arrayOf(id,generation))};offset=0}
        val total=DatabaseUtils.longForQuery(db,"SELECT length(payload) FROM archives WHERE call_id=? AND generation=?",arrayOf(id.toString(),generation.toString()))
        var bytes=0
        while(offset<total&&budget-bytes>=4){
            checkActive();val limit=minOf(16384,(budget-bytes)/4)
            val raw=DatabaseUtils.stringForQuery(db,"SELECT substr(payload,?,?) FROM archives WHERE call_id=? AND generation=?",arrayOf((offset+1).toString(),limit.toString(),id.toString(),generation.toString()))
            if(raw.isEmpty())throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE,"ai.archive_source_shape"))
            val next=offset+raw.codePointCount(0,raw.length)
            transaction{db.execSQL("INSERT OR REPLACE INTO archive_raw_chunks VALUES(?,?,?,?)",arrayOf<Any>(id,generation,offset,raw));db.execSQL("UPDATE archive_index_progress SET raw_offset=? WHERE call_id=? AND generation=?",arrayOf(next,id,generation))}
            bytes+=raw.toByteArray(Charsets.UTF_8).size;offset=next
        }
        if(offset<total)return Advance(false,bytes)
        // Parsing uses staged, bounded DB blocks. Source progress is persistent even if parsing is interrupted.
        transaction{
            listOf("archive_projection","archive_text_fields","archive_text_chunks").forEach{db.delete(it,"call_id=?",arrayOf(id.toString()))}
            JsonReader(StagedReader(db,id,generation)).use{reader->
                reader.isLenient=false
                val metadata=parse(reader,id,generation,"",false) as AiJson.Obj
                require(reader.peek()==JsonToken.END_DOCUMENT)
                saveMetadata(id,generation,metadata)
            }
            require(DatabaseUtils.longForQuery(db,"SELECT generation FROM archives WHERE call_id=?",arrayOf(id.toString()))==generation)
            db.delete("archive_raw_chunks","call_id=?",arrayOf(id.toString()));db.delete("archive_index_progress","call_id=?",arrayOf(id.toString()))
        }
        return Advance(true,bytes)
    }
    private val names=mapOf("id" to "callId","started" to "startedAtEpochMillis","quality" to "audioQuality","duration" to "durationMillis","next" to "nextAttemptAt","analyzed" to "analyzedAt","protocol" to "analysisProtocol","manual" to "manualAnalysis")
    private fun parse(r:JsonReader,id:Long,generation:Long,path:String,analysis:Boolean):AiJson {
        checkActive()
        return when(r.peek()){
            JsonToken.BEGIN_OBJECT->{r.beginObject();val fields=linkedMapOf<String,AiJson>();val seen=hashSetOf<String>()
                while(r.hasNext()){
                    val raw=r.nextName();require(seen.add(raw))
                    if(path.isEmpty()&&raw=="audio"){fields["audioPresent"]=aiBool(r.peek()!=JsonToken.NULL);r.skipValue();continue}
                    val name=if(path.isEmpty())names[raw]?:raw else if(analysis)when(raw){"identity"->"inferredIdentity";"actions"->"actionItems";else->raw}else raw
                    val next=if(path.isEmpty())name else "$path.$name"
                    fields[name]=parse(r,id,generation,next,next=="analysis")
                };r.endObject();AiJson.Obj(fields)}
            JsonToken.BEGIN_ARRAY->{r.beginArray();val values=mutableListOf<AiJson>();while(r.hasNext()){values+=parse(r,id,generation,"$path[${values.size}]",false)};r.endArray();AiJson.Arr(values)}
            JsonToken.STRING->{val value=r.nextString();project(id,generation,path,aiString(value))}
            JsonToken.NUMBER->AiJson.Num(r.nextString())
            JsonToken.BOOLEAN->aiBool(r.nextBoolean())
            JsonToken.NULL->{r.nextNull();AiJson.Null}
            else->throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE,"ai.archive_source_shape"))
        }
    }
    private class StagedReader(private val db:SQLiteDatabase,private val id:Long,private val generation:Long):Reader(){
        private var key=-1L;private var buffer="";private var offset=0;private var done=false
        override fun read(chars:CharArray,start:Int,length:Int):Int {
            if(length==0)return 0
            if(offset==buffer.length&&!done){db.rawQuery("SELECT start,text FROM archive_raw_chunks WHERE call_id=? AND generation=? AND start>? ORDER BY start LIMIT 1",arrayOf(id.toString(),generation.toString(),key.toString())).use{c->if(c.moveToFirst()){key=c.getLong(0);buffer=c.getString(1);offset=0}else done=true}}
            if(done)return -1
            val count=minOf(length,buffer.length-offset);buffer.toCharArray(chars,start,offset,offset+count);offset+=count;return count
        }
        override fun close()=Unit
    }
}
