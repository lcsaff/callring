package com.greatwall.callring.application.api

enum class AiDomain { D01,D02,D03,D04,D05,D06,D07,D08,D09,D10,D11,D12,D13,D14,D15,D16,D17,D18,D19,D20,D21 }
enum class QueryStatus { COMPLETE, EMPTY, PARTIAL, NOT_CONFIGURED, UNAVAILABLE, DENIED }
data class QueryFilter(val number:String?=null,val id:Long?=null,val text:String?=null,val start:Long?=null,val end:Long?=null,val year:Int?=null,val status:String?=null,val kind:String?=null,val date:String?=null,val zone:String?=null)
data class QuerySpec(val domain:AiDomain,val projection:String="default",val filter:QueryFilter=QueryFilter(),val cursor:String?=null,val limit:Int=50,val maxBytes:Int=32768)
data class AggregateSpec(val domain:AiDomain,val metric:String="count",val filter:QueryFilter=QueryFilter(),val groupBy:String?=null)
data class SourceStamp(val domain:AiDomain,val sourceVersion:Long,val observedAt:Long,val sourceScope:String,val consistency:String)
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
    fun reset()=Unit
}

fun SourceStamp.json()=aiObject("domain" to aiString(domain.name),"sourceVersion" to aiNumber(sourceVersion),"observedAt" to aiNumber(observedAt),"sourceScope" to aiString(sourceScope),"consistency" to aiString(consistency))
fun ScopedPage.json()=aiObject("schemaVersion" to aiNumber(2),"items" to AiJson.Arr(items),"stamp" to stamp.json(),"matchedTotal" to (matchedTotal?.let(::aiNumber)?:AiJson.Null),"returnedCount" to aiNumber(items.size.toLong()),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete),"status" to aiString(status.name),"missingReason" to (missingReason?.let(::aiString)?:AiJson.Null))
fun TextChunk.json()=aiObject("reference" to aiString(reference),"stamp" to stamp.json(),"codePointStart" to aiNumber(codePointStart),"codePointEnd" to aiNumber(codePointEnd),"totalCodePoints" to aiNumber(totalCodePoints),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete),"text" to aiString(text))
