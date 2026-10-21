package com.greatwall.callring.data.query

import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.application.ports.query.SourceText

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.*
import java.util.TreeMap

/** For preferences/platform APIs which intrinsically expose a small source snapshot or row stream. */
class ValueScopedReadSource(override val domains:Set<AiDomain>,private val versionProvider:(AiDomain)->Long,private val consistencyProvider:(AiDomain)->String,private val rows:(QuerySpec)->Sequence<SourceRow>):ScopedReadPort {
    override fun version(domain:AiDomain)=versionProvider(domain)
    override fun consistency(domain:AiDomain)=consistencyProvider(domain)
    private fun matches(row:SourceRow,f:QueryFilter):Boolean {
        require(f.year==null&&f.date==null&&f.zone==null&&f.start==null&&f.end==null)
        fun value(name:String)= (row.value[name] as? AiJson.Str)?.value
        fun number(name:String)= (row.value[name] as? AiJson.Num)?.value?.toLongOrNull()
        return (f.number==null||f.number==value("number"))&&(f.id==null||f.id==(number("id")?:number("widgetId")))&&(f.status==null||f.status==value("status"))&&(f.kind==null||f.kind==value("kind"))&&(f.text==null||row.value.encode().contains(f.text!!))
    }
    override fun query(spec:QuerySpec,after:String?):SourcePage {
        val selected=TreeMap<String,SourceRow>();var total=0L
        rows(spec).forEach{row->
            if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("ai.cancelled")
            if(matches(row,spec.filter)){total++;if(after==null||row.key>after){selected[row.key]=row;if(selected.size>spec.limit+1)selected.pollLastEntry()}}
        }
        val page=selected.values.take(spec.limit);return SourcePage(page,total,if(selected.size>spec.limit)page.last().key else null)
    }
    override fun get(domain:AiDomain,key:String,projection:String)=rows(QuerySpec(domain,projection)).firstOrNull{it.key==key}
    override fun aggregate(spec:AggregateSpec):AiJson {require(spec.metric=="count"&&spec.groupBy==null);return aiObject("count" to aiNumber(rows(QuerySpec(spec.domain,filter=spec.filter)).count{matches(it,spec.filter)}.toLong()))}
    override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int):SourceText {
        val row=get(domain,key,"default")?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"ai.not_found"))
        val value=row.value[field].string();return sliceSourceText(value,start,maxChars)
    }
}
fun sliceSourceText(value:String,start:Long,maxChars:Int):SourceText {
    val total=value.codePointCount(0,value.length).toLong();require(start in 0..total)
    val end=minOf(total,start+maxChars);return SourceText(value.substring(value.offsetByCodePoints(0,start.toInt()),value.offsetByCodePoints(0,end.toInt())),start,end,total)
}
