package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure

/** Read before preparing a command; preserve observations actually disclosed to the model. */
class OperationScopePlanner(private val queries:QueryService,private val coordinator:BusinessRevisionCoordinator,private val catalog:OperationCatalog = OperationCatalog()) {
    companion object {
        val writeOperations=setOf(OperationId.CALLERS_SAVE_PROFILE,OperationId.CALLERS_SET_WHITELISTED,OperationId.REPORTS_EXPORT_CSV,OperationId.SETTINGS_SET_STATISTICS_ENABLED,OperationId.SETTINGS_SET_OVERLAY_ENABLED,OperationId.SETTINGS_SAVE_QUIET_HOURS,OperationId.SETTINGS_SET_QUIET_MODE,OperationId.SETTINGS_SET_QUIET_ENABLED,OperationId.SETTINGS_SAVE_POSITION,OperationId.SETTINGS_DELETE_POSITION,OperationId.SETTINGS_SET_MY_POSITION,OperationId.SETTINGS_SAVE_WORK_SCHEDULE,OperationId.SETTINGS_RESET_WORK_SCHEDULE,OperationId.SETTINGS_SYNCHRONIZE_CALENDAR,OperationId.SETTINGS_IMPORT_CALENDAR,OperationId.SETTINGS_SAVE_DATE_OVERRIDE,OperationId.SETTINGS_DELETE_DATE_OVERRIDE,OperationId.SETTINGS_SAVE_LEAVE,OperationId.SETTINGS_DELETE_LEAVE,OperationId.SETTINGS_SAVE_SCORE_SETTINGS,OperationId.SETTINGS_RESET_SCORE_SETTINGS,OperationId.SETTINGS_SAVE_APPEARANCE,OperationId.RINGTONE_SET_ENABLED,OperationId.RINGTONE_IMPORT_AUDIO,OperationId.RINGTONE_RESTORE_BUILT_IN,OperationId.RINGTONE_START_PREVIEW,OperationId.RINGTONE_STOP_PLAYBACK,OperationId.RUNTIME_REQUEST_CALL_LOG_SYNC,OperationId.WIDGETS_SAVE_BINDING,OperationId.WIDGETS_REMOVE_BINDING,OperationId.RECORDINGS_SAVE_SETTINGS,OperationId.RECORDINGS_TEST_CONNECTION,OperationId.RECORDINGS_IMPORT_RECORDING,OperationId.RECORDINGS_RETRY,OperationId.RECORDINGS_DELETE,OperationId.RECORDINGS_PLAY,OperationId.RECORDINGS_STOP,OperationId.RECORDINGS_SET_SYSTEM_FOLDER,OperationId.RECORDINGS_ADOPT_IDENTITY,OperationId.AI_PATCH_SETTINGS,OperationId.AI_EDIT_CREDENTIAL,OperationId.SYSTEM_REQUEST_CAPABILITY,OperationId.SYSTEM_OPEN_APPLICATION_SETTINGS,OperationId.SYSTEM_PIN_WIDGET,OperationId.SYSTEM_TEST_OVERLAY,OperationId.SYSTEM_PREVIEW_CALL,OperationId.AI_COPY_CONNECTION,OperationId.CALLERS_DELETE_NUMBER)
    }
    private val own=mutableMapOf<String,Long>()
    fun resetForContinuation(){own.clear()}
    fun committed(operationId:String,keys:Set<String>){coordinator.committedMarker(operationId)?.let{at->keys.forEach{own[it]=at}}}
    fun afterOwnCommits(expected:Map<String,Long>)=expected.mapValues{(key,at)->maxOf(at,own[key]?:Long.MIN_VALUE)}
    suspend fun prepare(call:ConversationToolCall,operation:OperationId):Map<String,Long> {
        require(catalog[operation].scope==ScopeStrategy.DIRECT && operation in writeOperations)
        val a=call.arguments;val scopes=linkedMapOf<String,Long>()
        val disclosed=queries.snapshotObservations()
        suspend fun observe(domain:AiDomain,key:String?,keys:Set<String>,projection:String="default",filter:QueryFilter=QueryFilter()) {
            require(keys.isNotEmpty())
            val normalized=if(domain==AiDomain.D01)key?:"*" else key?.toLongOrNull()?.toString()?:key?:"*"
            val old=disclosed[domain to normalized]
            val at=if(key==null)queries.query(QuerySpec(domain,projection,filter,limit=1)).requiredValue().stamp.scopeRevision else {
                when(val result=queries.get(domain,key,projection)) {
                    is ApiResult.Success->result.value.stamp.scopeRevision
                    is ApiResult.Failure->{
                        if(result.error.code!=ErrorCode.NOT_FOUND)throw BackendFailure(result.error)
                        queries.query(QuerySpec(domain,projection,filter,limit=1)).requiredValue().stamp.scopeRevision
                    }
                }
            }
            keys.forEach{k->val baseline=maxOf(old?:own[k]?:at,own[k]?:Long.MIN_VALUE);scopes[k]=minOf(scopes[k]?:baseline,baseline)}
        }
        fun fields(prefix:String,names:Set<String>)=names.filter{a.has(it)}.mapTo(linkedSetOf()){prefix+it}
        suspend fun config(keys:Set<String>)=observe(AiDomain.D10,"configuration",keys)
        suspend fun aiKeys(value:AiJson.Obj):Set<String> = buildSet {
            listOf("analysis" to "ANALYSIS","transcription" to "TRANSCRIPTION").forEach{(field,slot)->value.optObj(field)?.fields?.keys?.forEach{add("ai:$slot:$it")}}
            if(value.has("context")||value.has("output"))add("ai:budget")
            if(value.has("compatibility"))add("ai:compatibility")
            if(isEmpty())add("aiConfig")
        }
        try {
            when(operation) {
                OperationId.CALLERS_SAVE_PROFILE, OperationId.CALLERS_SET_WHITELISTED ->{
                    val n=a.s("number")
                    val keys=if(operation==OperationId.CALLERS_SET_WHITELISTED)setOf("profile:$n:whitelisted") else fields("profile:$n:",setOf("name","department","positionId","whitelisted"))+"profile:$n:exists"
                    observe(AiDomain.D01,n,keys,filter=QueryFilter(number=n))
                    a.optL("positionId")?.let{id->observe(AiDomain.D02,id.toString(),setOf("position:$id:valid"),filter=QueryFilter(id=id))}
                }
                OperationId.REPORTS_EXPORT_CSV ->{val n=a.s("number");observe(AiDomain.D04,null,setOf("calls:$n"),filter=QueryFilter(number=n));config(setOf("score","statistics"))}
                OperationId.CALLERS_DELETE_NUMBER ->{
                    val n=a.s("number")
                    observe(AiDomain.D01,n,setOf("profile:$n", "profile:$n:exists"),filter=QueryFilter(number=n))
                    observe(AiDomain.D04,null,setOf("calls:$n"),filter=QueryFilter(number=n))
                    // Collection guards also cover records added or rebound after confirmation.
                    // Deletion intentionally rejects intervening archive/preference writes.
                    observe(AiDomain.D16,null,setOf("recording"),filter=QueryFilter(number=n))
                    observe(AiDomain.D18,null,setOf("preferences"),"bindings",QueryFilter(number=n))
                }
                OperationId.SETTINGS_SET_STATISTICS_ENABLED ->config(setOf("statistics"))
                OperationId.SETTINGS_SET_OVERLAY_ENABLED ->config(setOf("overlay"))
                OperationId.SETTINGS_SAVE_QUIET_HOURS, OperationId.SETTINGS_SET_QUIET_MODE, OperationId.SETTINGS_SET_QUIET_ENABLED ->config(setOf("quiet"))
                OperationId.SETTINGS_SAVE_POSITION, OperationId.SETTINGS_DELETE_POSITION ->{val id=a.optL("id");observe(AiDomain.D02,id?.toString(),if(id==null)setOf("positions")else setOf("position:$id","positionRefs:$id","myPosition"),filter=QueryFilter(id=id))}
                OperationId.SETTINGS_SET_MY_POSITION ->{config(setOf("myPosition"));a.optL("id")?.let{id->observe(AiDomain.D02,id.toString(),setOf("position:$id:valid"),filter=QueryFilter(id=id))}}
                OperationId.SETTINGS_SAVE_WORK_SCHEDULE, OperationId.SETTINGS_RESET_WORK_SCHEDULE ->observe(AiDomain.D06,null,setOf("schedule"),"current")
                OperationId.SETTINGS_SYNCHRONIZE_CALENDAR, OperationId.SETTINGS_IMPORT_CALENDAR ->observe(AiDomain.D07,null,setOf("calendar"))
                OperationId.SETTINGS_SAVE_DATE_OVERRIDE, OperationId.SETTINGS_DELETE_DATE_OVERRIDE ->{val date=a.s("date");observe(AiDomain.D08,date,setOf("override:$date"),filter=QueryFilter(date=date))}
                OperationId.SETTINGS_SAVE_LEAVE, OperationId.SETTINGS_DELETE_LEAVE ->{val id=a.optL("id");observe(AiDomain.D09,id?.toString(),if(id==null)setOf("leaves")else setOf("leave:$id"),filter=QueryFilter(id=id))}
                OperationId.SETTINGS_SAVE_SCORE_SETTINGS, OperationId.SETTINGS_RESET_SCORE_SETTINGS ->config(setOf("score"))
                OperationId.SETTINGS_SAVE_APPEARANCE ->{var keys:Set<String> = fields("appearance:",setOf("selection","presetId","customSeedRgb","colorMode"));if(keys.any{it!="appearance:colorMode"})keys=keys+setOf("appearance:selection","appearance:presetId","appearance:customSeedRgb");observe(AiDomain.D11,"current",keys.ifEmpty{setOf("appearance:colorMode")} )}
                OperationId.RINGTONE_SET_ENABLED, OperationId.RINGTONE_IMPORT_AUDIO, OperationId.RINGTONE_RESTORE_BUILT_IN, OperationId.RINGTONE_START_PREVIEW, OperationId.RINGTONE_STOP_PLAYBACK->observe(AiDomain.D12,"current",setOf("ringtone"))
                OperationId.RUNTIME_REQUEST_CALL_LOG_SYNC ->observe(AiDomain.D19,"current",setOf("runtime:capability:CALL_LOG"))
                OperationId.SYSTEM_REQUEST_CAPABILITY ->observe(AiDomain.D19,"current",setOf("runtime:capability:"+a.s("capability")))
                OperationId.SYSTEM_OPEN_APPLICATION_SETTINGS ->observe(AiDomain.D19,"current",setOf("runtime:status"))
                OperationId.SYSTEM_PIN_WIDGET ->observe(AiDomain.D19,"current",setOf("runtime:pinWidget"))
                OperationId.SYSTEM_TEST_OVERLAY ->observe(AiDomain.D19,"current",setOf("runtime:capability:OVERLAY"))
                OperationId.WIDGETS_SAVE_BINDING, OperationId.WIDGETS_REMOVE_BINDING ->{val id=a.l("widgetId");observe(AiDomain.D18,"binding:$id",setOf("widget:$id"),"bindings",QueryFilter(id=id))}
                OperationId.RECORDINGS_SAVE_SETTINGS ->{val r=a.optObj("recording");if(r!=null)observe(AiDomain.D13,"current",r.fields.keys.mapTo(linkedSetOf()){ "recordingConfig:$it" }.ifEmpty{setOf("recordingConfig")});a.optObj("ai")?.let{observe(AiDomain.D14,"current",aiKeys(it))};if(scopes.isEmpty())observe(AiDomain.D13,"current",setOf("recordingConfig"))}
                OperationId.RECORDINGS_TEST_CONNECTION, OperationId.AI_EDIT_CREDENTIAL ->{val slot=a.s("slot");observe(AiDomain.D14,"current",setOf("ai:$slot:protocol","ai:$slot:url","ai:$slot:model","ai:$slot:key"))}
                OperationId.RECORDINGS_IMPORT_RECORDING, OperationId.RECORDINGS_RETRY, OperationId.RECORDINGS_DELETE, OperationId.RECORDINGS_PLAY, OperationId.RECORDINGS_ADOPT_IDENTITY, OperationId.SYSTEM_PREVIEW_CALL ->{
                    val id=a.l("callId")
                    if(operation==OperationId.RECORDINGS_ADOPT_IDENTITY){
                        val archive=queries.get(AiDomain.D16,id.toString()).requiredValue().value;val native=archive.l("callId");val n=archive.s("number")
                        observe(AiDomain.D16,native.toString(),setOf("archive:$native"),filter=QueryFilter(id=native))
                        if(native!=id)observe(AiDomain.D16,id.toString(),setOf("recordingLink:$id"),filter=QueryFilter(id=native))
                        observe(AiDomain.D01,n,setOf("profile:$n:exists","profile:$n:name","profile:$n:department"),filter=QueryFilter(number=n))
                    }else observe(AiDomain.D16,id.toString(),setOf("archive:$id"),filter=QueryFilter(id=id))
                }
                OperationId.RECORDINGS_STOP ->observe(AiDomain.D19,"current",setOf("runtime:recordingPlayback"))
                OperationId.RECORDINGS_SET_SYSTEM_FOLDER ->observe(AiDomain.D13,"current",setOf("recordingConfig:systemFolder"))
                OperationId.AI_PATCH_SETTINGS ->observe(AiDomain.D14,"current",aiKeys(a))
                OperationId.AI_COPY_CONNECTION ->{val from=a.s("from");val to=a.s("to");val keys=listOf(from,to).flatMap{slot->listOf("protocol","url","model","key").map{"ai:$slot:$it"}}.toSet()+if(to=="ANALYSIS")setOf("ai:compatibility")else emptySet();observe(AiDomain.D14,"current",keys)}
                else -> error("ai.scope_contract_missing")
            }
            check(scopes.isNotEmpty())
            return scopes
        } finally {queries.restoreObservations(disclosed)}
    }
}
