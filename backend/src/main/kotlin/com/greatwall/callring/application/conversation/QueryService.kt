package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

class QueryService(sources:List<ScopedReadPort>,private val clock:()->Long=System::currentTimeMillis,private val revision:()->Long={0L},private val redactor:()->SecretRedactor={SecretRedactor(emptyList())}):AiQueryApi {
    private val sources=sources.flatMap{s->s.domains.map{it to s}}.also{require(it.map{it.first}.distinct().size==it.size)}.toMap()
    private data class Cursor(val identity:String,val domain:AiDomain,val version:Long,val after:String,val expires:Long)
    private data class Ref(val domain:AiDomain,val key:String,val field:String,val version:Long,val expires:Long)
    private val cursors=ConcurrentHashMap<String,Cursor>()
    private val refs=ConcurrentHashMap<String,Ref>()
    private val observations=ConcurrentHashMap<Pair<AiDomain,String>,Long>()
    private fun canonical(key:String)=key.toLongOrNull()?.toString()?:key
    fun observedRevision(domain:AiDomain,key:String)=observations[domain to canonical(key)]
    fun clearObservations(){observations.clear()}
    internal fun snapshotObservations()=observations.toMap()
    internal fun restoreObservations(values:Map<Pair<AiDomain,String>,Long>){observations.clear();observations.putAll(values)}
    private fun observe(domain:AiDomain,key:String,at:Long){if(observations.size>=4096&&!observations.containsKey(domain to canonical(key)))fail("capacity_exceeded",ErrorCode.SYSTEM_RESTRICTED);observations[domain to canonical(key)]=at}
    val sourceQueries=AtomicLong()
    val returnedRows=AtomicLong()
    override fun reset(){cursors.clear();refs.clear();observations.clear();sources.values.distinct().forEach{it.reset()}}
    private fun fail(code:String,error:ErrorCode=ErrorCode.VALIDATION):Nothing=throw BackendFailure(ApiError(error,"ai.$code"))
    private fun source(domain:AiDomain)=sources[domain]?:fail("query_not_implemented",ErrorCode.INTERNAL)
    private fun stamp(domain:AiDomain,version:Long,s:ScopedReadPort,at:Long)=SourceStamp(domain,version,clock(),s.scope(domain),s.consistency(domain),at)
    private fun token(cursor:Cursor):String { if(cursors.size>=512) fail("capacity_exceeded",ErrorCode.SYSTEM_RESTRICTED);return UUID.randomUUID().toString().also{cursors[it]=cursor} }
    private fun cursor(value:String,identity:String,domain:AiDomain,version:Long):String {
        val c=cursors[value]?:fail("cursor_stale",ErrorCode.CONFLICT)
        if(c.expires<clock()||c.domain!=domain||c.version!=version||c.identity!=identity) fail("cursor_stale",ErrorCode.CONFLICT)
        return c.after
    }
    private suspend fun <T> result(block:()->T):ApiResult<T> = withContext(Dispatchers.IO){
        try { runInterruptible { ApiResult.Success(block()) } }
        catch(e:CancellationException){throw e}
        catch(e:BackendFailure){ApiResult.Failure(e.error)}
        catch(_:IllegalArgumentException){ApiResult.Failure(ApiError(ErrorCode.VALIDATION,"ai.query_invalid"))}
        catch(_:Exception){ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE,"ai.query_source_failed"))}
    }
    private fun project(row:SourceRow,domain:AiDomain,version:Long):AiJson.Obj {
        val references=row.textFields.mapValues{(field,total)->
            if(refs.size>=512) fail("capacity_exceeded",ErrorCode.SYSTEM_RESTRICTED)
            val handle=UUID.randomUUID().toString();refs[handle]=Ref(domain,row.key,field,version,clock()+600000)
            aiObject("reference" to aiString(handle),"totalCodePoints" to aiNumber(total))
        }
        return redactor().redact(AiJson.Obj(row.value.fields+mapOf("entityKey" to aiString(row.key))+if(references.isEmpty()) emptyMap() else mapOf("textFields" to AiJson.Obj(references)))) as AiJson.Obj
    }
    override suspend fun query(spec:QuerySpec)=result {
        require(spec.limit in 1..200&&spec.maxBytes in 1..131072&&spec.projection.length<=64)
        require(spec.filter.text?.length?:0<=4096)
        val s=source(spec.domain);val before=s.version(spec.domain);val observed=revision()
        val identity=spec.copy(cursor=null,limit=50,maxBytes=32768).toString()
        val after=spec.cursor?.let{cursor(it,identity,spec.domain,before)}
        sourceQueries.incrementAndGet()
        val page=s.query(spec,after)
        if(s.version(spec.domain)!=before) fail("cursor_stale",ErrorCode.CONFLICT)
        val items=mutableListOf<AiJson.Obj>();var bytes=0;var next=page.nextKey
        for(row in page.rows){
            val item=project(row,spec.domain,before);val size=item.encode().toByteArray(Charsets.UTF_8).size
            if(bytes+size>spec.maxBytes){if(items.isEmpty()) fail("item_too_large",ErrorCode.SYSTEM_RESTRICTED);next=page.rows[items.size-1].cursorKey;break}
            items+=item;bytes+=size;observe(spec.domain,row.key,observed)
        }
        returnedRows.addAndGet(items.size.toLong())
        val complete=page.complete&&next==null
        if(spec.filter==QueryFilter())observe(spec.domain,"*",observed)
        if(complete&&page.matchedTotal==0L)spec.filter.number?.let{observe(spec.domain,it,observed)}
        ScopedPage(items,stamp(spec.domain,before,s,observed).copy(projection=spec.projection,filter=spec.filter),page.matchedTotal,next?.let{token(Cursor(identity,spec.domain,before,it,clock()+600000))},complete,
            if(page.status in setOf(QueryStatus.NOT_CONFIGURED,QueryStatus.DENIED,QueryStatus.UNAVAILABLE))page.status else if(complete&&items.isEmpty()&&page.status==QueryStatus.COMPLETE) QueryStatus.EMPTY else if(!complete) QueryStatus.PARTIAL else page.status,page.reason)
    }
    override suspend fun get(domain:AiDomain,key:String,projection:String)=result {
        require(key.isNotBlank()&&key.length<=1024)
        val s=source(domain);val before=s.version(domain);val observed=revision();sourceQueries.incrementAndGet()
        val row=s.get(domain,key,projection)?:fail("not_found",ErrorCode.NOT_FOUND)
        if(s.version(domain)!=before) fail("cursor_stale",ErrorCode.CONFLICT)
        val value=project(row,domain,before)
        if(value.encode().toByteArray(Charsets.UTF_8).size>131072)fail("item_too_large",ErrorCode.SYSTEM_RESTRICTED)
        observe(domain,key,observed)
        ScopedValue(value,stamp(domain,before,s,observed).copy(projection=projection))
    }
    override suspend fun aggregate(spec:AggregateSpec)=result {
        require(spec.metric in setOf("count","sumDuration","score","reportTotals"))
        require(spec.groupBy==null||spec.groupBy in setOf("number","status","direction","year"))
        val s=source(spec.domain);val before=s.version(spec.domain);val observed=revision();sourceQueries.incrementAndGet()
        val value=s.aggregate(spec)
        if(s.version(spec.domain)!=before) fail("aggregate_incomplete",ErrorCode.CONFLICT)
        if(value.encode().toByteArray(Charsets.UTF_8).size>131072)fail("aggregate_incomplete",ErrorCode.SYSTEM_RESTRICTED)
        observe(spec.domain,spec.filter.number?:"*",observed)
        ScopedAggregate(redactor().redact(value),stamp(spec.domain,before,s,observed).copy(filter=spec.filter))
    }
    override suspend fun readText(reference:String,cursor:String?,maxChars:Int)=result {
        require(maxChars in 1..16384)
        val r=refs[reference]?:fail("text_reference_stale",ErrorCode.NOT_FOUND)
        val s=source(r.domain);val v=s.version(r.domain);val observed=revision()
        if(v!=r.version||r.expires<clock()) fail("cursor_stale",ErrorCode.CONFLICT)
        val start=cursor?.let{cursor(it,reference,r.domain,v).toLong()}?:0L
        val sanitizer=redactor();val overlap=sanitizer.overlapCodePoints.toLong()
        val expandedStart=maxOf(0L,start-overlap);val requested=maxChars.toLong()+(start-expandedStart)+overlap
        if(requested>131072)fail("capacity_exceeded",ErrorCode.SYSTEM_RESTRICTED)
        sourceQueries.incrementAndGet();val chunk=s.text(r.domain,r.key,r.field,expandedStart,requested.toInt())
        if(s.version(r.domain)!=v) fail("cursor_stale",ErrorCode.CONFLICT)
        require(chunk.start==expandedStart&&chunk.end>=start&&chunk.end<=chunk.total&&chunk.text.codePointCount(0,chunk.text.length)<=requested)
        val end=minOf(start+maxChars,chunk.total);val complete=end==chunk.total
        TextChunk(reference,stamp(r.domain,v,s,observed),start,end,chunk.total,if(complete)null else token(Cursor(reference,r.domain,v,end.toString(),clock()+600000)),complete,sanitizer.textRange(chunk.text,(start-expandedStart).toInt(),(end-expandedStart).toInt()))
    }
}
