package com.greatwall.callring.contracts.ai

import com.greatwall.callring.contracts.common.*

enum class AiDomain { D01,D02,D03,D04,D05,D06,D07,D08,D09,D10,D11,D12,D13,D14,D15,D16,D17,D18,D19,D20,D21 }

enum class QueryStatus { COMPLETE, EMPTY, PARTIAL, NOT_CONFIGURED, UNAVAILABLE, DENIED }

data class QueryFilter(val number:String?=null,val id:Long?=null,val text:String?=null,val start:Long?=null,val end:Long?=null,val year:Int?=null,val status:String?=null,val kind:String?=null,val date:String?=null,val zone:String?=null)

data class QuerySpec(val domain:AiDomain,val projection:String="default",val filter:QueryFilter=QueryFilter(),val cursor:String?=null,val limit:Int=50,val maxBytes:Int=32768)

data class AggregateSpec(val domain:AiDomain,val metric:String="count",val filter:QueryFilter=QueryFilter(),val groupBy:String?=null)

data class SourceStamp(val domain:AiDomain,val sourceVersion:Long,val observedAt:Long,val sourceScope:String,val consistency:String,val scopeRevision:Long=0,val projection:String="default",val filter:QueryFilter=QueryFilter())

data class ScopedPage(val items:List<AiJson.Obj>,val stamp:SourceStamp,val matchedTotal:Long?,val nextCursor:String?,val complete:Boolean,val status:QueryStatus,val missingReason:String?=null) {
    val schemaVersion=2
    val returnedCount get()=items.size
}

data class ScopedValue(val value:AiJson.Obj,val stamp:SourceStamp)

data class ScopedAggregate(val value:AiJson,val stamp:SourceStamp,val complete:Boolean=true)

data class TextChunk(val reference:String,val stamp:SourceStamp,val codePointStart:Long,val codePointEnd:Long,val totalCodePoints:Long,val nextCursor:String?,val complete:Boolean,val text:String)

interface AiQueryApi {
    suspend fun query(spec:QuerySpec):ApiResult<ScopedPage>
    suspend fun get(domain:AiDomain,key:String,projection:String="default"):ApiResult<ScopedValue>
    suspend fun aggregate(spec:AggregateSpec):ApiResult<ScopedAggregate>
    suspend fun readText(reference:String,cursor:String?=null,maxChars:Int=4096):ApiResult<TextChunk>
    fun reset()
}
