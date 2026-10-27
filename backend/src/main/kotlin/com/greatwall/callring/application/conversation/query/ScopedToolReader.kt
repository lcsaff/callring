package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.conversation.execution.numberRuleSettings
import com.greatwall.callring.application.conversation.execution.exactInt
import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.execution.l
import com.greatwall.callring.application.conversation.execution.obj
import com.greatwall.callring.application.conversation.execution.optL
import com.greatwall.callring.application.conversation.execution.optObj
import com.greatwall.callring.application.conversation.execution.optS
import com.greatwall.callring.application.conversation.execution.s

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.mapping.toSnapshot
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure
import java.time.*

/** Tool reads always use authoritative source projections. */
internal data class SourceToolResult(val value: AiJson, val evidence: AiJson = AiJson.Null)
class ScopedToolReader(private val q:QueryService,private val numberMatcher:com.greatwall.callring.domain.calling.NumberMatcher,private val catalog:OperationCatalog = OperationCatalog()) {
    internal suspend fun readWithEvidence(operation:OperationId,a:AiJson.Obj):SourceToolResult {
        if(operation !in setOf(OperationId.ROOT_CHECK_NUMBER,OperationId.SETTINGS_GET_NUMBER_RULE))return SourceToolResult(read(operation,a))
        val observed=q.get(AiDomain.D10,"configuration").requiredValue()
        val rule=observed.value.obj("numberRule")
        val settings=rule.numberRuleSettings()
        val value=if(operation==OperationId.ROOT_CHECK_NUMBER) safeValue(com.greatwall.callring.application.mapping.checkNumber(a.s("raw"),settings.toSnapshot(),numberMatcher)) else safeValue(settings)
        return SourceToolResult(value,AiJson.Arr(listOf(aiObject("value" to observed.value,"stamp" to observed.stamp.json()))))
    }
    suspend fun read(operation:OperationId,a:AiJson.Obj):AiJson {
        require(catalog[operation].readSource==ReadStrategy.SOURCE)
        if(operation in setOf(OperationId.ROOT_CHECK_NUMBER,OperationId.SETTINGS_GET_NUMBER_RULE))return readWithEvidence(operation,a).value
        val domains=when(operation) {
            OperationId.HOME_LOAD -> setOf(AiDomain.D10,AiDomain.D06,AiDomain.D19,AiDomain.D05)
            OperationId.CALLERS_GET_EDITOR -> setOf(AiDomain.D01,AiDomain.D02)
            OperationId.CALLERS_GET_OVERVIEW -> setOf(AiDomain.D01,AiDomain.D05,AiDomain.D06,AiDomain.D04)
            OperationId.REPORTS_LOAD -> setOf(AiDomain.D06,AiDomain.D05,AiDomain.D04)
            OperationId.RECORDINGS_GET_SETTINGS -> setOf(AiDomain.D13,AiDomain.D14)
            else -> emptySet()
        }
        return if(domains.isEmpty())readSources(operation,a)else q.coherent(domains){readSources(operation,a)}
    }
    private suspend fun readSources(operation:OperationId,a:AiJson.Obj):AiJson {
        fun limit():Int {
            val page=a.optObj("page")
            if((page?.optL("pageIndex")?:0L)!=0L)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.source_cursor_required"))
            return (a.optL("limit")?:page?.optL("pageSize")?:50).exactInt()
        }
        suspend fun page(d:AiDomain,p:String="default",f:QueryFilter=QueryFilter(),n:Int=limit())=q.query(QuerySpec(d,p,f,a.optS("cursor"),n)).requiredValue().json()
        suspend fun get(d:AiDomain,key:String="current",p:String="default")=q.get(d,key,p).requiredValue().let{aiObject("value" to it.value,"stamp" to it.stamp.json())}
        suspend fun aggregate(d:AiDomain,metric:String="count",f:QueryFilter=QueryFilter(),group:String?=null)=q.aggregate(AggregateSpec(d,metric,f,group)).requiredValue().let{aiObject("value" to it.value,"stamp" to it.stamp.json(),"complete" to aiBool(it.complete))}
        return when(operation) {
            OperationId.HOME_LOAD ->aiObject("configuration" to get(AiDomain.D10,"configuration"),"work" to page(AiDomain.D06,"work",n=1),"runtime" to get(AiDomain.D19),"statistics" to aggregate(AiDomain.D05))
            OperationId.CALLERS_SEARCH ->page(AiDomain.D01,f=QueryFilter(text=a.optS("text")?.trim()?.takeIf{it.isNotEmpty()}))
            OperationId.CALLERS_GET_EDITOR ->aiObject("profiles" to page(AiDomain.D01,f=QueryFilter(number=a.optS("number")),n=if(a.optS("number")==null)1 else 50),"positions" to page(AiDomain.D02),"myPosition" to page(AiDomain.D02,"current",n=1))
            OperationId.CALLERS_GET_OVERVIEW ->{val f=QueryFilter(number=a.s("number"));aiObject("profiles" to page(AiDomain.D01,f=f),"totals" to aggregate(AiDomain.D05,"reportTotals",f),"work" to page(AiDomain.D06,"work",n=1),"latestCall" to page(AiDomain.D04,f=f,n=1))}
            OperationId.CALLERS_GET_LATEST_CALL ->page(AiDomain.D04,n=1)
            OperationId.REPORTS_LOAD ->{
                val range=a.obj("range")
                val zone=range.optS("zoneId")?:q.query(QuerySpec(AiDomain.D06,"current",limit=1)).requiredValue().items.firstOrNull()?.let{(it["snapshot"] as? AiJson.Obj)?.optS("zone")}?:"UTC"
                val z=ZoneId.of(zone)
                val f=QueryFilter(number=a.s("number"),start=LocalDate.parse(range.s("startDate")).atStartOfDay(z).toInstant().toEpochMilli(),end=LocalDate.parse(range.s("endDateInclusive")).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(),zone=zone)
                val kind=a.optS("filter")?.takeUnless{it=="ALL"}
                aiObject("totals" to aggregate(AiDomain.D05,"reportTotals",f),"records" to page(AiDomain.D04,f=f.copy(kind=kind)))
            }
            OperationId.SETTINGS_GET_SUMMARY, OperationId.SETTINGS_GET_QUIET_HOURS, OperationId.SETTINGS_GET_SCORE_SETTINGS ->get(AiDomain.D10,"configuration")
            OperationId.SETTINGS_GET_POSITIONS ->page(AiDomain.D02)
            OperationId.SETTINGS_GET_WORK_SCHEDULE ->page(AiDomain.D06,"current")
            OperationId.SETTINGS_GET_CALENDAR ->page(AiDomain.D07)
            OperationId.SETTINGS_GET_LEAVES ->page(AiDomain.D09)
            OperationId.SETTINGS_GET_APPEARANCE ->get(AiDomain.D11)
            OperationId.RINGTONE_GET_SETTINGS ->get(AiDomain.D12)
            OperationId.RUNTIME_GET_STATUS ->get(AiDomain.D19)
            OperationId.RUNTIME_GET_DIAGNOSTICS ->page(AiDomain.D20)
            OperationId.WIDGETS_GET_BINDING ->get(AiDomain.D18,"binding:${a.l("widgetId")}","bindings")
            OperationId.RECORDINGS_LIST_ARCHIVES ->page(AiDomain.D16)
            OperationId.RECORDINGS_GET_SETTINGS ->aiObject("recording" to get(AiDomain.D13),"ai" to get(AiDomain.D14))
            OperationId.RECORDINGS_GET_ARCHIVE ->get(AiDomain.D16,a.l("callId").toString())
            OperationId.RECORDINGS_SYSTEM_RECORDINGS ->page(AiDomain.D17)
            OperationId.WIDGETS_LIST_BINDINGS ->page(AiDomain.D18)
            OperationId.AI_GET_SETTINGS ->get(AiDomain.D14)
            OperationId.DATA_QUERY ->q.query(QuerySpec(AiDomain.valueOf(a.s("domain")),a.optS("projection")?:"default",a.optObj("filter")?.queryFilter()?:QueryFilter(),a.optS("cursor"),(a.optL("limit")?:50).exactInt(),(a.optL("maxBytes")?:32768).exactInt())).requiredValue().json()
            OperationId.DATA_GET ->get(AiDomain.valueOf(a.s("domain")),a.s("key"),a.optS("projection")?:"default")
            OperationId.DATA_AGGREGATE ->aggregate(AiDomain.valueOf(a.s("domain")),a.optS("metric")?:"count",a.optObj("filter")?.queryFilter()?:QueryFilter(),a.optS("groupBy"))
            OperationId.DATA_READ_TEXT ->q.readText(a.s("reference"),a.optS("cursor"),(a.optL("maxChars")?:4096).exactInt()).requiredValue().json()
            OperationId.CALLERS_GET_CALL_DETAIL ->get(AiDomain.D03,a.l("callId").toString())
            else->throw BackendFailure(ApiError(ErrorCode.INTERNAL,"ai.read_contract_missing"))
        }
    }
}
internal fun AiJson.Obj.queryFilter()=QueryFilter(optS("number"),optL("id"),optS("text"),optL("start"),optL("end"),optL("year")?.exactInt(),optS("status"),optS("kind"),optS("date"),optS("zone"))
