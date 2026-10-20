package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure
import java.time.*

/** Legacy names remain callable, but read from source projections rather than UI list loaders. */
class ScopedToolReader(private val q:QueryService) {
    companion object {val legacyReads=setOf(2,3,4,5,6,9,11,14,18,22,25,30,33,36,38,44,45,47,50,51,54,61,62,64)}
    suspend fun read(index:Int,a:AiJson.Obj):AiJson {
        val domains=when(index) {
            2 -> setOf(AiDomain.D10,AiDomain.D06,AiDomain.D19,AiDomain.D05)
            4 -> setOf(AiDomain.D01,AiDomain.D02)
            5 -> setOf(AiDomain.D01,AiDomain.D05,AiDomain.D06,AiDomain.D04)
            9 -> setOf(AiDomain.D06,AiDomain.D05,AiDomain.D04)
            51 -> setOf(AiDomain.D13,AiDomain.D14)
            else -> emptySet()
        }
        return if(domains.isEmpty())readSources(index,a)else q.coherent(domains){readSources(index,a)}
    }
    private suspend fun readSources(index:Int,a:AiJson.Obj):AiJson {
        fun limit():Int {
            val page=a.optObj("page")
            if((page?.optL("pageIndex")?:0L)!=0L)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.source_cursor_required"))
            return (a.optL("limit")?:page?.optL("pageSize")?:50).exactInt()
        }
        suspend fun page(d:AiDomain,p:String="default",f:QueryFilter=QueryFilter(),n:Int=limit())=q.query(QuerySpec(d,p,f,a.optS("cursor"),n)).requiredValue().json()
        suspend fun get(d:AiDomain,key:String="current",p:String="default")=q.get(d,key,p).requiredValue().let{aiObject("value" to it.value,"stamp" to it.stamp.json())}
        suspend fun aggregate(d:AiDomain,metric:String="count",f:QueryFilter=QueryFilter(),group:String?=null)=q.aggregate(AggregateSpec(d,metric,f,group)).requiredValue().let{aiObject("value" to it.value,"stamp" to it.stamp.json(),"complete" to aiBool(it.complete))}
        return when(index) {
            2->aiObject("configuration" to get(AiDomain.D10,"configuration"),"work" to page(AiDomain.D06,"work",n=1),"runtime" to get(AiDomain.D19),"statistics" to aggregate(AiDomain.D05))
            3->page(AiDomain.D01,f=QueryFilter(text=a.optS("text")?.trim()?.takeIf{it.isNotEmpty()}))
            4->aiObject("profiles" to page(AiDomain.D01,f=QueryFilter(number=a.optS("number")),n=if(a.optS("number")==null)1 else 50),"positions" to page(AiDomain.D02),"myPosition" to page(AiDomain.D02,"current",n=1))
            5->{val f=QueryFilter(number=a.s("number"));aiObject("profiles" to page(AiDomain.D01,f=f),"totals" to aggregate(AiDomain.D05,"reportTotals",f),"work" to page(AiDomain.D06,"work",n=1),"latestCall" to page(AiDomain.D04,f=f,n=1))}
            6->page(AiDomain.D04,n=1)
            9->{
                val range=a.obj("range")
                val zone=range.optS("zoneId")?:q.query(QuerySpec(AiDomain.D06,"current",limit=1)).requiredValue().items.firstOrNull()?.let{(it["snapshot"] as? AiJson.Obj)?.optS("zone")}?:"UTC"
                val z=ZoneId.of(zone)
                val f=QueryFilter(number=a.s("number"),start=LocalDate.parse(range.s("startDate")).atStartOfDay(z).toInstant().toEpochMilli(),end=LocalDate.parse(range.s("endDateInclusive")).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(),zone=zone)
                val kind=a.optS("filter")?.takeUnless{it=="ALL"}
                aiObject("totals" to aggregate(AiDomain.D05,"reportTotals",f),"records" to page(AiDomain.D04,f=f.copy(kind=kind)))
            }
            11,14,33->get(AiDomain.D10,"configuration")
            18->page(AiDomain.D02)
            22->page(AiDomain.D06,"current")
            25->page(AiDomain.D07)
            30->page(AiDomain.D09)
            36->get(AiDomain.D11)
            38->get(AiDomain.D12)
            44->get(AiDomain.D19)
            45->page(AiDomain.D20)
            47->get(AiDomain.D18,"binding:${a.l("widgetId")}","bindings")
            50->page(AiDomain.D16)
            51->aiObject("recording" to get(AiDomain.D13),"ai" to get(AiDomain.D14))
            54->get(AiDomain.D16,a.l("callId").toString())
            61->page(AiDomain.D17)
            62->page(AiDomain.D18)
            64->get(AiDomain.D14)
            74->q.query(QuerySpec(AiDomain.valueOf(a.s("domain")),a.optS("projection")?:"default",a.optObj("filter")?.queryFilter()?:QueryFilter(),a.optS("cursor"),(a.optL("limit")?:50).exactInt(),(a.optL("maxBytes")?:32768).exactInt())).requiredValue().json()
            75->get(AiDomain.valueOf(a.s("domain")),a.s("key"),a.optS("projection")?:"default")
            76->aggregate(AiDomain.valueOf(a.s("domain")),a.optS("metric")?:"count",a.optObj("filter")?.queryFilter()?:QueryFilter(),a.optS("groupBy"))
            77->q.readText(a.s("reference"),a.optS("cursor"),(a.optL("maxChars")?:4096).exactInt()).requiredValue().json()
            80->get(AiDomain.D03,a.l("callId").toString())
            else->throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.read_contract_missing"))
        }
    }
}
internal fun AiJson.Obj.queryFilter()=QueryFilter(optS("number"),optL("id"),optS("text"),optL("start"),optL("end"),optL("year")?.exactInt(),optS("status"),optS("kind"),optS("date"),optS("zone"))
