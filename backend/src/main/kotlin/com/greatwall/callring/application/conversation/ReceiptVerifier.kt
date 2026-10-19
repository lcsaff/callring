package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure
import kotlinx.coroutines.CancellationException

/** A committed command is never replayed merely because its readback failed. */
class ReceiptVerifier(private val q:QueryService) {
    suspend fun verify(index:Int,call:ConversationToolCall,receipt:ToolReceipt,before:AiJson?=null):ToolReceipt {
        if(receipt.status in setOf(ToolStatus.QUEUED,ToolStatus.LAUNCHED))return receipt.copy(verification=VerificationStatus.PENDING)
        if(receipt.status!=ToolStatus.COMMITTED)return receipt
        val saved=q.snapshotObservations()
        val evidence=mutableListOf<AiJson>()
        suspend fun get(d:AiDomain,key:String="current",p:String="default"):AiJson.Obj {
            val v=q.get(d,key,p).requiredValue();evidence+=aiObject("value" to v.value,"stamp" to v.stamp.json());return v.value
        }
        suspend fun page(d:AiDomain,p:String="default",f:QueryFilter=QueryFilter()):List<AiJson.Obj> {
            val v=q.query(QuerySpec(d,p,f,limit=200)).requiredValue();if(!v.complete)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.verification_incomplete"));evidence+=v.json();return v.items
        }
        suspend fun absent(d:AiDomain,key:String,p:String="default"):Boolean=when(val v=q.get(d,key,p)) {
            is ApiResult.Success->{evidence+=aiObject("value" to v.value.value,"stamp" to v.value.stamp.json());false}
            is ApiResult.Failure->if(v.error.code==ErrorCode.NOT_FOUND){evidence+=aiObject("domain" to aiString(d.name),"key" to aiString(key),"absent" to aiBool(true));true}else throw BackendFailure(v.error)
        }
        val a=call.arguments
        fun compare(actual:AiJson.Obj,expected:AiJson.Obj=a,aliases:Map<String,String> = emptyMap()):Boolean = expected.fields.filterKeys{it !in setOf("taskId","dependsOn")}.all{(key,value)->sameValue(actual[aliases[key]?:key],value,key)}
        fun receiptObject()=receipt.value as? AiJson.Obj?:aiObject()
        try {
            val matches=when(index) {
                7->{val v=get(AiDomain.D01,a.s("number"));sameValue(v["stored"],aiBool(true))&&compare(v,aliases=mapOf("positionId" to "position_id"))}
                8->compare(get(AiDomain.D01,a.s("number")),aiObject("number" to a["number"],"whitelisted" to a["enabled"]))
                12->sameValue(get(AiDomain.D10,"configuration")["statisticsEnabled"],a["enabled"])
                13->sameValue(get(AiDomain.D10,"configuration")["overlay"],a["enabled"])
                15->compare(get(AiDomain.D10,"configuration"),aliases=mapOf("mode" to "quiet_mode","allNumbers" to "all_numbers","weeklyWindows" to "quiet_windows"))
                16->sameValue(get(AiDomain.D10,"configuration")["quiet_mode"],a["mode"])
                17->sameValue(get(AiDomain.D10,"configuration")["quiet_mode"],receiptObject()["mode"])
                19->{val id=a.optL("id")?:(receiptObject()["id"] as? AiJson.Num)?.value?.toLong();id!=null&&compare(get(AiDomain.D02,id.toString()))}
                20->absent(AiDomain.D02,a.l("id").toString())
                21->sameValue(get(AiDomain.D10,"configuration")["myPositionId"],a["id"])
                23,24->{val current=page(AiDomain.D06,"current").singleOrNull()?.get("snapshot") as? AiJson.Obj;current!=null&&compare(current,if(index==24)AiJson.Obj(receiptObject().fields.filterKeys{it in setOf("zoneId","weekly","makeup")})else a,mapOf("zoneId" to "zone"))}
                28->{val v=get(AiDomain.D08,a.s("date"));val windows=if(a.s("kind")=="REST")AiJson.Arr(emptyList())else a["windows"];sameValue(v["windows"],windows)}
                29->absent(AiDomain.D08,a.s("date"))
                31->{val expected=receiptObject();val id=(expected["id"] as? AiJson.Num)?.value?.toLong();id!=null&&compare(get(AiDomain.D09,id.toString()),AiJson.Obj(expected.fields.filterKeys{it in setOf("id","startInclusiveEpochMillis","endExclusiveEpochMillis","note")}),mapOf("startInclusiveEpochMillis" to "start","endExclusiveEpochMillis" to "end"))}
                32->absent(AiDomain.D09,a.l("id").toString())
                34,35->compare(get(AiDomain.D10,"configuration"),if(index==35)receiptObject()else a,mapOf("offHoursWeight" to "off_weight","repeatWeight" to "repeat_weight","repeatMinutes" to "repeat_minutes","observationDays" to "score_days"))
                37->compare(get(AiDomain.D11))
                39->{val v=get(AiDomain.D12)["settings"] as? AiJson.Obj;v!=null&&sameValue(v["enabled"],a["enabled"])}
                40,41->{val v=get(AiDomain.D12)["settings"] as? AiJson.Obj;v!=null&&sameValue(v["source"],aiString(if(index==40)"IMPORTED"else"BUILT_IN"))}
                48->compare(get(AiDomain.D18,"binding:${a.l("widgetId")}","bindings"))
                49->absent(AiDomain.D18,"binding:${a.l("widgetId")}","bindings")
                52->{val r=a.optObj("recording");val ai=a.optObj("ai");(r==null||compare(get(AiDomain.D13),r))&&(ai==null||compareAi(get(AiDomain.D14),ai))}
                55->{val v=get(AiDomain.D16,a.l("callId").toString());sameValue(v["audioPresent"],aiBool(true))&&v["generation"]==receiptObject()["generation"]}
                57->absent(AiDomain.D16,a.l("callId").toString())
                60->sameValue(get(AiDomain.D13)["folderConfigured"],aiBool(true))
                63->{val archive=get(AiDomain.D16,a.l("callId").toString());val analysis=archive["analysis"] as? AiJson.Obj;val n=archive.s("number");val profile=get(AiDomain.D01,n);analysis!=null&&compare(profile,aiObject("name" to analysis["name"],"department" to analysis["organization"]))}
                65->compareAi(get(AiDomain.D14),a)
                67->{val runtime=get(AiDomain.D19)["runtime"] as? AiJson.Obj;val capabilities=runtime?.get("capabilities") as? AiJson.Obj;capabilities?.get(a.s("capability")) in setOf(aiString("GRANTED"),aiString("NOT_REQUIRED"))}
                66,72->{val value=get(AiDomain.D14);val expected=receiptObject();expected.fields.isNotEmpty()&&compare(value,expected)}
                else->false
            }
            val proof=AiJson.Arr(evidence)
            return if(matches&&(receipt.commitRevision!=null||receipt.noOp))receipt.copy(verification=VerificationStatus.VERIFIED,noOp=receipt.noOp||(before!=null&&before==proof),verificationEvidence=proof)
                else receipt.copy(verification=VerificationStatus.COMMITTED_UNVERIFIED,error=receipt.error?:ApiError(ErrorCode.IO_FAILURE,"ai.readback_mismatch"),verificationEvidence=proof)
        } catch(e:CancellationException){throw e}
        catch(_:Exception){return receipt.copy(verification=VerificationStatus.COMMITTED_UNVERIFIED,error=receipt.error?:ApiError(ErrorCode.IO_FAILURE,"ai.readback_failed"),verificationEvidence=AiJson.Arr(evidence))}
        finally {q.restoreObservations(saved)}
    }
    private fun compareAi(actual:AiJson.Obj,patch:AiJson.Obj):Boolean=patch.fields.filterKeys{it !in setOf("taskId","dependsOn")}.all{(key,value)->
        when(key){
            "context","output"->sameValue((actual["budget"] as? AiJson.Obj)?.get(key)?:AiJson.Null,value)
            "analysis","transcription"->{val view=actual[key] as? AiJson.Obj;view!=null&&(value as AiJson.Obj).fields.all{(f,v)->sameValue(view[f],v,f)}}
            else->sameValue(actual[key],value,key)
        }
    }
}
internal fun sameValue(actual:AiJson,expected:AiJson,key:String=""):Boolean {
    if(expected is AiJson.Bool)return when(actual){is AiJson.Bool->actual.value==expected.value;is AiJson.Num->actual.value in setOf("0","1")&&(actual.value=="1")==expected.value;is AiJson.Str->actual.value in setOf("0","1","true","false")&&(actual.value in setOf("1","true"))==expected.value;else->false}
    if(expected is AiJson.Num)return (actual as? AiJson.Num)?.value?.toBigDecimalOrNull()==expected.value.toBigDecimalOrNull()||(actual as? AiJson.Str)?.value?.toBigDecimalOrNull()==expected.value.toBigDecimalOrNull()
    if(expected is AiJson.Str)return actual is AiJson.Str&&actual.value==if(key in setOf("name","department"))expected.value.trim()else expected.value
    if(expected is AiJson.Obj){if(actual is AiJson.Arr&&expected.fields.keys==setOf("startMinute","endMinute"))return actual.values==listOf(expected["startMinute"],expected["endMinute"]);return actual is AiJson.Obj&&actual.fields.keys==expected.fields.keys&&expected.fields.all{(k,v)->sameValue(actual[k],v,k)}}
    if(expected is AiJson.Arr)return actual is AiJson.Arr&&actual.values.size==expected.values.size&&expected.values.indices.all{sameValue(actual.values[it],expected.values[it],key)}
    return actual==expected
}
