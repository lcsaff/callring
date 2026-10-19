package com.greatwall.callring.platform.ai

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*

internal class RecordingScopedReadAdapter(private val db:SQLiteDatabase,private val versionProvider:()->Long):ScopedReadPort {
    override val domains=setOf(AiDomain.D15,AiDomain.D16)
    override fun version(domain:AiDomain)=versionProvider()
    private val index=ArchiveProjectionIndex(db)
    private fun <T> read(block:()->T):T{db.beginTransactionNonExclusive();try{return block().also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
    private fun row(c:Cursor):AiJson.Obj=AiJson.Obj((0 until c.columnCount).associate{i->c.getColumnName(i) to when(c.getType(i)){Cursor.FIELD_TYPE_NULL->AiJson.Null;Cursor.FIELD_TYPE_INTEGER->aiNumber(c.getLong(i));else->aiString(c.getString(i))}})
    /** Only called for an explicit D16 query, never during graph construction or ordinary chat. */
    private fun advance():Boolean {
        val pending=db.rawQuery("SELECT a.call_id,a.generation FROM archives a LEFT JOIN archive_projection p ON a.call_id=p.call_id AND a.generation=p.generation WHERE p.call_id IS NULL ORDER BY a.call_id LIMIT 16",null).use{c->buildList{while(c.moveToNext())add(c.getLong(0) to c.getLong(1))}}
        var budget=1024*1024
        for((id,generation) in pending){if(budget<4)break;val step=index.advance(id,generation,budget);budget-=step.sourceBytes;if(!step.complete)break}
        return DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archives a LEFT JOIN archive_projection p ON a.call_id=p.call_id AND a.generation=p.generation WHERE p.call_id IS NULL",null)==0L
    }
    private fun filter(f:QueryFilter,archive:Boolean):Pair<String,List<String>> {
        val clauses=mutableListOf<String>();val args=mutableListOf<String>()
        fun add(column:String,value:Any?,op:String="="){if(value!=null){clauses+="$column $op ?";args+=value.toString()}}
        add("number",f.number);add(if(archive)"call_id" else "callId",f.id);add("started",f.start,">=");add("started",f.end,"<")
        if(f.status!=null){require(archive);add("status",f.status)}
        f.text?.let{add("number",it,"LIKE")}
        require(f.year==null&&f.kind==null&&f.date==null&&f.zone==null)
        return (if(clauses.isEmpty())"1"else clauses.joinToString(" AND ")) to args
    }
    private fun archiveRow(id:Long,generation:Long,metadata:String):SourceRow {
        val fields=db.rawQuery("SELECT field,total FROM archive_text_fields WHERE call_id=? AND generation=? ORDER BY field",arrayOf(id.toString(),generation.toString())).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getLong(1))}}
        return SourceRow(id.toString(),parseConversationJson(metadata) as AiJson.Obj,fields)
    }
    private fun eventSql(projection:String)=when(projection){
        "default","events"->"SELECT id entity_key,id eventId,-id callId,session_id,number,started,account FROM recording_events"
        "links"->"SELECT statistics_id entity_key,statistics_id,session_id FROM recording_statistics_links"
        "deleted"->"SELECT call_id entity_key,call_id callId FROM deleted_archives"
        else->throw IllegalArgumentException("ai.query_invalid")
    }
    override fun query(spec:QuerySpec,after:String?):SourcePage {
        if(spec.domain==AiDomain.D16){
            require(spec.projection in setOf("default","metadata"))
            if(!advance())return SourcePage(emptyList(),null,"index:",false,QueryStatus.PARTIAL,"INDEX_INCOMPLETE: progress persisted; continue nextCursor")
            return read{
                val (where,args)=filter(spec.filter,true);val key=after?.takeUnless{it.startsWith("index:")}
                val total=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archive_projection WHERE $where",args.toTypedArray())
                val rows=db.rawQuery("SELECT call_id,generation,metadata FROM archive_projection WHERE ($where)"+(if(key==null)""else" AND call_id>?")+" ORDER BY call_id LIMIT ?",(args+listOfNotNull(key)+(spec.limit+1).toString()).toTypedArray()).use{c->buildList{while(c.moveToNext())add(archiveRow(c.getLong(0),c.getLong(1),c.getString(2)))}}
                val page=rows.take(spec.limit);SourcePage(page,total,if(rows.size>spec.limit)page.last().key else null)
            }
        }
        require(spec.domain==AiDomain.D15)
        return read{
            val sql=eventSql(spec.projection);val(where,args)=if(spec.projection in setOf("default","events"))filter(spec.filter,false)else{require(spec.filter==QueryFilter());"1" to emptyList()}
            val total=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM ($sql) WHERE $where",args.toTypedArray())
            val rows=db.rawQuery("SELECT * FROM ($sql) WHERE ($where)"+(if(after==null)""else" AND entity_key>?")+" ORDER BY entity_key LIMIT ?",(args+listOfNotNull(after)+(spec.limit+1).toString()).toTypedArray()).use{c->buildList{while(c.moveToNext())add(SourceRow(c.getLong(0).toString(),row(c)))}}
            val page=rows.take(spec.limit);SourcePage(page,total,if(rows.size>spec.limit)page.last().key else null)
        }
    }
    override fun get(domain:AiDomain,key:String,projection:String):SourceRow? {
        val id=key.toLong()
        if(domain==AiDomain.D16){
            val generation=db.rawQuery("SELECT generation FROM archives WHERE call_id=?",arrayOf(key)).use{c->if(c.moveToFirst())c.getLong(0)else return null}
            if(!index.advance(id,generation,1024*1024).complete)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.index_incomplete",retryable=true))
            return read{db.rawQuery("SELECT generation,metadata FROM archive_projection WHERE call_id=?",arrayOf(key)).use{c->if(c.moveToFirst())archiveRow(id,c.getLong(0),c.getString(1))else null}}
        }
        return read{db.rawQuery("SELECT * FROM (${eventSql(projection)}) WHERE entity_key=?",arrayOf(key)).use{c->if(c.moveToFirst())SourceRow(key,row(c))else null}}
    }
    override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int):SourceText=read {
        require(domain==AiDomain.D16&&start>=0)
        val generation=DatabaseUtils.longForQuery(db,"SELECT generation FROM archives WHERE call_id=?",arrayOf(key))
        val total=db.rawQuery("SELECT total FROM archive_text_fields WHERE call_id=? AND generation=? AND field=?",arrayOf(key,generation.toString(),field)).use{c->if(c.moveToFirst())c.getLong(0)else throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"ai.text_not_found"))}
        require(start<=total);val end=minOf(total,start+maxChars);val text=StringBuilder()
        db.rawQuery("SELECT start,end,text FROM archive_text_chunks WHERE call_id=? AND generation=? AND field=? AND end>? AND start<? ORDER BY start",arrayOf(key,generation.toString(),field,start.toString(),end.toString())).use{c->while(c.moveToNext()){
            val value=c.getString(2);val beginPoint=maxOf(start,c.getLong(0))-c.getLong(0);val endPoint=minOf(end,c.getLong(1))-c.getLong(0)
            text.append(value.substring(value.offsetByCodePoints(0,beginPoint.toInt()),value.offsetByCodePoints(0,endPoint.toInt())))
        }}
        require(text.codePointCount(0,text.length).toLong()==end-start)
        SourceText(text.toString(),start,end,total)
    }
    override fun aggregate(spec:AggregateSpec):AiJson {
        require(spec.metric=="count"&&spec.groupBy==null)
        if(spec.domain==AiDomain.D16){
            if(spec.filter!=QueryFilter()&&!advance())throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.aggregate_incomplete"))
            return read{if(spec.filter==QueryFilter())aiObject("count" to aiNumber(DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archives",null)))else{val(where,args)=filter(spec.filter,true);aiObject("count" to aiNumber(DatabaseUtils.longForQuery(db,"SELECT count(*) FROM archive_projection WHERE $where",args.toTypedArray())))}}
        }
        return query(QuerySpec(AiDomain.D15,filter=spec.filter,limit=1),null).let{aiObject("count" to aiNumber(it.matchedTotal!!))}
    }
}
