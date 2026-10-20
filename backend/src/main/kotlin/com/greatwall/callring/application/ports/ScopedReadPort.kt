package com.greatwall.callring.application.ports

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*

data class SourceRow(val key:String,val value:AiJson.Obj,val textFields:Map<String,Long> = emptyMap(),val cursorKey:String=key)
data class SourcePage(val rows:List<SourceRow>,val matchedTotal:Long?,val nextKey:String?=null,val complete:Boolean=nextKey==null,val status:QueryStatus=if(nextKey==null) QueryStatus.COMPLETE else QueryStatus.PARTIAL,val reason:String?=null)
data class SourceText(val text:String,val start:Long,val end:Long,val total:Long)
interface ScopedReadPort {
    fun reset()=Unit
    val domains:Set<AiDomain>
    /** Called outside source transactions. Never acquire the coordinator fence from a SQL read. */
    fun version(domain:AiDomain):Long
    fun query(spec:QuerySpec,after:String?):SourcePage
    fun get(domain:AiDomain,key:String,projection:String):SourceRow? = throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.query_not_implemented"))
    fun aggregate(spec:AggregateSpec):AiJson = throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.aggregate_not_implemented"))
    fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int):SourceText = throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.text_not_implemented"))
    fun scope(domain:AiDomain)="all_stored"
    fun consistency(domain:AiDomain)="source_snapshot"
}
