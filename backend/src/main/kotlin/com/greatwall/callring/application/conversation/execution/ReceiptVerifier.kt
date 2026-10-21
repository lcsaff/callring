package com.greatwall.callring.application.conversation.execution

import com.greatwall.callring.application.conversation.query.AdoptionExpectation
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.conversation.query.ScopedTextFingerprint
import com.greatwall.callring.application.conversation.query.json

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure
import kotlinx.coroutines.CancellationException

/** A committed command is never replayed merely because its readback failed. */
class ReceiptVerifier(private val q:QueryService,private val catalog:OperationCatalog = OperationCatalog()) {
    suspend fun prepareAdoption(call:ConversationToolCall):AdoptionExpectation {
        val saved=q.snapshotObservations()
        try{
            val archive=q.get(AiDomain.D16,call.arguments.l("callId").toString()).requiredValue().value
            val analysis=archive["analysis"] as? AiJson.Obj?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"recording.analysis_missing"))
            val number=archive.s("number")
            val profile=when(val value=q.get(AiDomain.D01,number)){is ApiResult.Success->value.value.value;is ApiResult.Failure->if(value.error.code==ErrorCode.NOT_FOUND)null else throw BackendFailure(value.error)}
            suspend fun expected(field:String,target:String):ScopedTextFingerprint {val value=ScopedTextFingerprint.read(q,archive,"analysis.$field",analysis[field]);return if(value.blank)ScopedTextFingerprint.read(q,profile,target,profile?.get(target)?:AiJson.Null)else value}
            return AdoptionExpectation(number,archive["generation"],expected("name","name"),expected("organization","department"))
        }finally{q.restoreObservations(saved)}
    }
    suspend fun verify(operation:OperationId,call:ConversationToolCall,receipt:ToolReceipt,before:AiJson?=null,adoption:AdoptionExpectation?=null):ToolReceipt {
        require(catalog[operation].verification==VerificationStrategy.DIRECT)
        if(receipt.status in setOf(ToolStatus.QUEUED,ToolStatus.LAUNCHED))return receipt.copy(verification=VerificationStatus.PENDING)
        if(receipt.status!=ToolStatus.COMMITTED)return receipt
        val saved=q.snapshotObservations()
        val foregroundProof=(receipt.verificationEvidence as? AiJson.Arr)?.values.orEmpty().filter{it is AiJson.Obj&&it["foregroundAction"] is AiJson.Str}
        val documentProof=(receipt.verificationEvidence as? AiJson.Arr)?.values.orEmpty().filter{it is AiJson.Obj&&it["documentReadback"]==aiBool(true)}
        val evidence=(foregroundProof+documentProof).toMutableList()
        suspend fun get(d:AiDomain,key:String="current",p:String="default"):AiJson.Obj {
            val v=q.get(d,key,p).requiredValue();evidence+=aiObject("value" to v.value,"stamp" to v.stamp.json());return v.value
        }
        suspend fun page(d:AiDomain,p:String="default",f:QueryFilter=QueryFilter()):List<AiJson.Obj> {
            val v=q.query(QuerySpec(d,p,f,limit=200)).requiredValue();if(!v.complete || v.status !in setOf(QueryStatus.COMPLETE, QueryStatus.EMPTY))throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.verification_incomplete"));evidence+=v.json();return v.items
        }
        suspend fun absent(d:AiDomain,key:String,p:String="default"):Boolean=when(val v=q.get(d,key,p)) {
            is ApiResult.Success->{evidence+=aiObject("value" to v.value.value,"stamp" to v.value.stamp.json());false}
            is ApiResult.Failure->if(v.error.code==ErrorCode.NOT_FOUND){evidence+=aiObject("domain" to aiString(d.name),"key" to aiString(key),"absent" to aiBool(true));true}else throw BackendFailure(v.error)
        }
        val a=call.arguments
        fun compare(actual:AiJson.Obj,expected:AiJson.Obj=a,aliases:Map<String,String> = emptyMap()):Boolean = expected.fields.filterKeys{it !in setOf("taskId","dependsOn")}.all{(key,value)->sameValue(actual[aliases[key]?:key],value,key)}
        fun receiptObject()=receipt.value as? AiJson.Obj?:aiObject()
        try {
            val readbackDomains=when(operation){OperationId.RECORDINGS_ADOPT_IDENTITY->setOf(AiDomain.D01,AiDomain.D16);OperationId.CALLERS_DELETE_NUMBER->setOf(AiDomain.D01,AiDomain.D03,AiDomain.D16,AiDomain.D18);OperationId.RECORDINGS_SAVE_SETTINGS->setOf(AiDomain.D13,AiDomain.D14);else->emptySet()}
            val matches=q.coherent(readbackDomains){when(operation) {
                OperationId.CALLERS_SAVE_PROFILE ->{val v=get(AiDomain.D01,a.s("number"));sameValue(v["stored"],aiBool(true))&&compare(v,aliases=mapOf("positionId" to "position_id"))}
                OperationId.CALLERS_SET_WHITELISTED ->compare(get(AiDomain.D01,a.s("number")),aiObject("number" to a["number"],"whitelisted" to a["enabled"]))
                OperationId.REPORTS_EXPORT_CSV ->documentProof.isNotEmpty()
                OperationId.SETTINGS_SET_STATISTICS_ENABLED ->sameValue(get(AiDomain.D10,"configuration")["statisticsEnabled"],a["enabled"])
                OperationId.SETTINGS_SET_OVERLAY_ENABLED ->sameValue(get(AiDomain.D10,"configuration")["overlay"],a["enabled"])
                OperationId.SETTINGS_SAVE_QUIET_HOURS ->compare(get(AiDomain.D10,"configuration"),aliases=mapOf("mode" to "quiet_mode","allNumbers" to "all_numbers","weeklyWindows" to "quiet_windows"))
                OperationId.SETTINGS_SET_QUIET_MODE ->sameValue(get(AiDomain.D10,"configuration")["quiet_mode"],a["mode"])
                OperationId.SETTINGS_SET_QUIET_ENABLED ->sameValue(get(AiDomain.D10,"configuration")["quiet_mode"],receiptObject()["mode"])
                OperationId.SETTINGS_SAVE_POSITION ->{val id=a.optL("id")?:(receiptObject()["id"] as? AiJson.Num)?.value?.toLong();id!=null&&compare(get(AiDomain.D02,id.toString()))}
                OperationId.SETTINGS_DELETE_POSITION ->absent(AiDomain.D02,a.l("id").toString())
                OperationId.SETTINGS_SET_MY_POSITION ->sameValue(get(AiDomain.D10,"configuration")["myPositionId"],a["id"])
                OperationId.SETTINGS_SAVE_WORK_SCHEDULE, OperationId.SETTINGS_RESET_WORK_SCHEDULE ->{val current=page(AiDomain.D06,"current").singleOrNull()?.get("snapshot") as? AiJson.Obj;current!=null&&compare(current,if(operation==OperationId.SETTINGS_RESET_WORK_SCHEDULE)AiJson.Obj(receiptObject().fields.filterKeys{it in setOf("zoneId","weekly","makeup")})else a,mapOf("zoneId" to "zone"))}
                OperationId.SETTINGS_SYNCHRONIZE_CALENDAR ->{val years=(receiptObject()["years"] as? AiJson.Arr)?.values.orEmpty();val expected=years.filterIsInstance<AiJson.Obj>().singleOrNull{it["year"]==a["year"]};expected!=null&&compare(get(AiDomain.D07,"year:${a.l("year")}"),expected)}
                OperationId.SETTINGS_IMPORT_CALENDAR ->{val expected=(receiptObject()["years"] as? AiJson.Arr)?.values?.filterIsInstance<AiJson.Obj>();val actual=page(AiDomain.D07,"years");expected!=null&&expected.isNotEmpty()&&actual.size==expected.size&&expected.all{year->actual.singleOrNull{it["year"]==year["year"]}?.let{compare(it,year)}==true}}
                OperationId.SETTINGS_SAVE_DATE_OVERRIDE ->{val v=get(AiDomain.D08,a.s("date"));val windows=if(a.s("kind")=="REST")AiJson.Arr(emptyList())else a["windows"];sameValue(v["windows"],windows)}
                OperationId.SETTINGS_DELETE_DATE_OVERRIDE ->absent(AiDomain.D08,a.s("date"))
                OperationId.SETTINGS_SAVE_LEAVE ->{val expected=receiptObject();val id=(expected["id"] as? AiJson.Num)?.value?.toLong();id!=null&&compare(get(AiDomain.D09,id.toString()),AiJson.Obj(expected.fields.filterKeys{it in setOf("id","startInclusiveEpochMillis","endExclusiveEpochMillis","note")}),mapOf("startInclusiveEpochMillis" to "start","endExclusiveEpochMillis" to "end"))}
                OperationId.SETTINGS_DELETE_LEAVE ->absent(AiDomain.D09,a.l("id").toString())
                OperationId.SETTINGS_SAVE_SCORE_SETTINGS, OperationId.SETTINGS_RESET_SCORE_SETTINGS ->compare(get(AiDomain.D10,"configuration"),if(operation==OperationId.SETTINGS_RESET_SCORE_SETTINGS)receiptObject()else a,mapOf("offHoursWeight" to "off_weight","repeatWeight" to "repeat_weight","repeatMinutes" to "repeat_minutes","observationDays" to "score_days"))
                OperationId.SETTINGS_SAVE_APPEARANCE ->compare(get(AiDomain.D11))
                OperationId.RINGTONE_SET_ENABLED ->{val v=get(AiDomain.D12)["settings"] as? AiJson.Obj;v!=null&&sameValue(v["enabled"],a["enabled"])}
                OperationId.RINGTONE_IMPORT_AUDIO, OperationId.RINGTONE_RESTORE_BUILT_IN ->{val v=get(AiDomain.D12)["settings"] as? AiJson.Obj;v!=null&&sameValue(v["source"],aiString(if(operation==OperationId.RINGTONE_IMPORT_AUDIO)"IMPORTED"else"BUILT_IN"))&&(receiptObject().fields.isEmpty()||compare(v,receiptObject()))}
                OperationId.WIDGETS_SAVE_BINDING ->compare(get(AiDomain.D18,"binding:${a.l("widgetId")}","bindings"))
                OperationId.WIDGETS_REMOVE_BINDING ->absent(AiDomain.D18,"binding:${a.l("widgetId")}","bindings")
                OperationId.RECORDINGS_SAVE_SETTINGS ->{val r=a.optObj("recording");val ai=a.optObj("ai");(r==null||compare(get(AiDomain.D13),r))&&(ai==null||compareAi(get(AiDomain.D14),ai))}
                OperationId.RECORDINGS_IMPORT_RECORDING ->{val v=get(AiDomain.D16,a.l("callId").toString());sameValue(v["audioPresent"],aiBool(true))&&v["generation"]==receiptObject()["generation"]}
                OperationId.RECORDINGS_DELETE ->absent(AiDomain.D16,a.l("callId").toString())
                OperationId.RECORDINGS_PLAY ->{val v=get(AiDomain.D19)["recordingPlayback"] as? AiJson.Obj;v!=null&&v["state"]==aiString("PLAYING")&&sameValue(v["callId"],a["callId"])}
                OperationId.RECORDINGS_STOP ->{val v=get(AiDomain.D19)["recordingPlayback"] as? AiJson.Obj;v!=null&&v["mode"]==aiString("NONE")&&v["state"]==aiString("IDLE")&&v["callId"]==AiJson.Null}
                OperationId.RECORDINGS_SET_SYSTEM_FOLDER ->sameValue(get(AiDomain.D13)["folderConfigured"],aiBool(true))
                OperationId.RECORDINGS_ADOPT_IDENTITY ->{
                    val archive=get(AiDomain.D16,a.l("callId").toString());val analysis=archive["analysis"] as? AiJson.Obj
                    val n=archive.s("number");val profile=get(AiDomain.D01,n)
                    suspend fun matches(field:String,target:String,expected:ScopedTextFingerprint?):Boolean {
                        val wanted=expected?:analysis?.let{ScopedTextFingerprint.read(q,archive,"analysis.$field",it[field])}?:return false
                        if(expected==null&&wanted.blank)return false
                        return wanted.matches(ScopedTextFingerprint.read(q,profile,target,profile[target]))
                    }
                    analysis!=null&&(adoption==null||(adoption.number==n&&adoption.generation==archive["generation"]))&&matches("name","name",adoption?.name)&&matches("organization","department",adoption?.department)
                }
                OperationId.AI_PATCH_SETTINGS ->compareAi(get(AiDomain.D14),a)
                OperationId.SYSTEM_REQUEST_CAPABILITY ->{val runtime=get(AiDomain.D19)["runtime"] as? AiJson.Obj;val capabilities=runtime?.get("capabilities") as? AiJson.Obj;capabilities?.get(a.s("capability")) in setOf(aiString("GRANTED"),aiString("NOT_REQUIRED"))}
                OperationId.AI_EDIT_CREDENTIAL, OperationId.AI_COPY_CONNECTION ->{val value=get(AiDomain.D14);val expected=receiptObject();expected.fields.isNotEmpty()&&compare(value,expected)}
                OperationId.CALLERS_DELETE_NUMBER -> {
                    val filter = QueryFilter(number = a.s("number"))
                    val profileGone = absent(AiDomain.D01, a.s("number"))
                    val callsGone = page(AiDomain.D03, f = filter).isEmpty()
                    val archivesGone = page(AiDomain.D16, f = filter).isEmpty()
                    val bindingsGone = page(AiDomain.D18, "bindings", filter).isEmpty()
                    receipt.error == null && profileGone && callsGone && archivesGone && bindingsGone
                }
                else->false
            }
            }
            val proof=AiJson.Arr(evidence)
            val actualExternalReceipt=(operation==OperationId.REPORTS_EXPORT_CSV&&documentProof.isNotEmpty())||(operation==OperationId.SYSTEM_REQUEST_CAPABILITY&&foregroundProof.any{it is AiJson.Obj&&it["foregroundAction"]==aiString("CAPABILITY")&&it["result"]==aiString("VERIFIED")})
            if(!matches&&receipt.noOp)return receipt.copy(status=ToolStatus.FAILED,verification=VerificationStatus.FAILED,error=ApiError(ErrorCode.CONFLICT,"business.changed"),verificationEvidence=proof)
            return if(matches&&(receipt.commitRevision!=null||receipt.noOp||actualExternalReceipt))receipt.copy(verification=VerificationStatus.VERIFIED,noOp=receipt.noOp||(before!=null&&before==proof),verificationEvidence=proof)
                else receipt.copy(verification=VerificationStatus.COMMITTED_UNVERIFIED,error=receipt.error?:ApiError(ErrorCode.IO_FAILURE,"ai.readback_mismatch"),verificationEvidence=proof)
        } catch(e:CancellationException){throw e}
        catch(_:Exception){return receipt.copy(status=if(receipt.noOp)ToolStatus.FAILED else receipt.status,verification=if(receipt.noOp)VerificationStatus.FAILED else VerificationStatus.COMMITTED_UNVERIFIED,error=receipt.error?:ApiError(ErrorCode.IO_FAILURE,"ai.readback_failed"),verificationEvidence=AiJson.Arr(evidence))}
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
    if(expected is AiJson.Obj){if(actual is AiJson.Arr&&expected.fields.keys==setOf("startMinute","endMinute"))return actual.values==listOf(expected["startMinute"],expected["endMinute"]);if(actual is AiJson.Obj&&key in setOf("weekly","weeklyWindows","quiet_windows"))return (1..7).all{day->sameValue(actual.fields[day.toString()]?:AiJson.Arr(emptyList()),expected.fields[day.toString()]?:AiJson.Arr(emptyList()))};return actual is AiJson.Obj&&actual.fields.keys==expected.fields.keys&&expected.fields.all{(k,v)->sameValue(actual[k],v,k)}}
    if(expected is AiJson.Arr)return actual is AiJson.Arr&&actual.values.size==expected.values.size&&expected.values.indices.all{sameValue(actual.values[it],expected.values[it],key)}
    return actual==expected
}
