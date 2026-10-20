package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure

/** Read before preparing a command; preserve observations actually disclosed to the model. */
class OperationScopePlanner(private val queries:QueryService,private val coordinator:BusinessRevisionCoordinator) {
    companion object {
        val writeOperations=setOf(7,8,10,12,13,15,16,17,19,20,21,23,24,26,27,28,29,31,32,34,35,37,39,40,41,42,43,46,48,49,52,53,55,56,57,58,59,60,63,65,66,67,68,69,70,71,72,81)
    }
    private val own=mutableMapOf<String,Long>()
    fun resetForContinuation(){own.clear()}
    fun committed(operationId:String,keys:Set<String>){coordinator.committedMarker(operationId)?.let{at->keys.forEach{own[it]=at}}}
    fun afterOwnCommits(expected:Map<String,Long>)=expected.mapValues{(key,at)->maxOf(at,own[key]?:Long.MIN_VALUE)}
    suspend fun prepare(call:ConversationToolCall,index:Int):Map<String,Long> {
        require(index in writeOperations)
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
            when(index) {
                7,8->{
                    val n=a.s("number")
                    val keys=if(index==8)setOf("profile:$n:whitelisted") else fields("profile:$n:",setOf("name","department","positionId","whitelisted"))+"profile:$n:exists"
                    observe(AiDomain.D01,n,keys,filter=QueryFilter(number=n))
                    a.optL("positionId")?.let{id->observe(AiDomain.D02,id.toString(),setOf("position:$id:valid"),filter=QueryFilter(id=id))}
                }
                10->{val n=a.s("number");observe(AiDomain.D04,null,setOf("calls:$n"),filter=QueryFilter(number=n));config(setOf("score","statistics"))}
                81->{
                    val n=a.s("number")
                    observe(AiDomain.D01,n,setOf("profile:$n", "profile:$n:exists"),filter=QueryFilter(number=n))
                    observe(AiDomain.D04,null,setOf("calls:$n"),filter=QueryFilter(number=n))
                    // Collection guards also cover records added or rebound after confirmation.
                    // Deletion intentionally rejects intervening archive/preference writes.
                    observe(AiDomain.D16,null,setOf("recording"),filter=QueryFilter(number=n))
                    observe(AiDomain.D18,null,setOf("preferences"),"bindings",QueryFilter(number=n))
                }
                12->config(setOf("statistics"))
                13->config(setOf("overlay"))
                15,16,17->config(setOf("quiet"))
                19,20->{val id=a.optL("id");observe(AiDomain.D02,id?.toString(),if(id==null)setOf("positions")else setOf("position:$id","positionRefs:$id","myPosition"),filter=QueryFilter(id=id))}
                21->{config(setOf("myPosition"));a.optL("id")?.let{id->observe(AiDomain.D02,id.toString(),setOf("position:$id:valid"),filter=QueryFilter(id=id))}}
                23,24->observe(AiDomain.D06,null,setOf("schedule"),"current")
                26,27->observe(AiDomain.D07,null,setOf("calendar"))
                28,29->{val date=a.s("date");observe(AiDomain.D08,date,setOf("override:$date"),filter=QueryFilter(date=date))}
                31,32->{val id=a.optL("id");observe(AiDomain.D09,id?.toString(),if(id==null)setOf("leaves")else setOf("leave:$id"),filter=QueryFilter(id=id))}
                34,35->config(setOf("score"))
                37->{var keys:Set<String> = fields("appearance:",setOf("selection","presetId","customSeedRgb","colorMode"));if(keys.any{it!="appearance:colorMode"})keys=keys+setOf("appearance:selection","appearance:presetId","appearance:customSeedRgb");observe(AiDomain.D11,"current",keys.ifEmpty{setOf("appearance:colorMode")} )}
                in 39..43->observe(AiDomain.D12,"current",setOf("ringtone"))
                46,67,68,69,70->observe(AiDomain.D19,"current",setOf("runtime:capabilities"))
                48,49->{val id=a.l("widgetId");observe(AiDomain.D18,"binding:$id",setOf("widget:$id"),"bindings",QueryFilter(id=id))}
                52->{val r=a.optObj("recording");if(r!=null)observe(AiDomain.D13,"current",r.fields.keys.mapTo(linkedSetOf()){ "recordingConfig:$it" }.ifEmpty{setOf("recordingConfig")});a.optObj("ai")?.let{observe(AiDomain.D14,"current",aiKeys(it))};if(scopes.isEmpty())observe(AiDomain.D13,"current",setOf("recordingConfig"))}
                53,66->{val slot=a.s("slot");observe(AiDomain.D14,"current",setOf("ai:$slot:protocol","ai:$slot:url","ai:$slot:model","ai:$slot:key"))}
                55,56,57,58,63,71->{
                    val id=a.l("callId")
                    if(index==63){
                        val archive=queries.get(AiDomain.D16,id.toString()).requiredValue().value;val native=archive.l("callId");val n=archive.s("number")
                        observe(AiDomain.D16,native.toString(),setOf("archive:$native"),filter=QueryFilter(id=native))
                        if(native!=id)observe(AiDomain.D16,id.toString(),setOf("recordingLink:$id"),filter=QueryFilter(id=native))
                        observe(AiDomain.D01,n,setOf("profile:$n:exists","profile:$n:name","profile:$n:department"),filter=QueryFilter(number=n))
                    }else observe(AiDomain.D16,id.toString(),setOf("archive:$id"),filter=QueryFilter(id=id))
                }
                59->observe(AiDomain.D19,"current",setOf("runtime:recordingPlayback"))
                60->observe(AiDomain.D13,"current",setOf("recordingConfig:systemFolder"))
                65->observe(AiDomain.D14,"current",aiKeys(a))
                72->{val from=a.s("from");val to=a.s("to");val keys=listOf(from,to).flatMap{slot->listOf("protocol","url","model","key").map{"ai:$slot:$it"}}.toSet()+if(to=="ANALYSIS")setOf("ai:compatibility")else emptySet();observe(AiDomain.D14,"current",keys)}
            }
            check(scopes.isNotEmpty())
            return scopes
        } finally {queries.restoreObservations(disclosed)}
    }
}
