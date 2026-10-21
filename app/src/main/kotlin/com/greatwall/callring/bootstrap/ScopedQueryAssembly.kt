package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.conversation.context.SecretRedactor
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.data.query.ValueScopedReadSource

import com.greatwall.callring.application.conversation.query.*

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.data.preferences.AndroidPreferences
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import com.greatwall.callring.platform.recording.SystemRecordingSource
import java.time.Clock
import kotlinx.coroutines.runBlocking

/** Register lazy source adapters. Constructing this registry performs no business source reads. */
fun scopedQueryApi(core:ScopedReadPort,recordings:()->RecordingAssembly,api:()->CallringUiApi,preferences:()->AndroidPreferences,diagnostics:()->AndroidDiagnosticsStore,directory:SystemRecordingSource,coordinator:BusinessRevisionCoordinator,resources:ForegroundResourceRegistry,playback:()->PlaybackStatus,installed:()->WidgetInstallState,jobs:()->AiJson,clock:Clock):QueryService {
    val recordingSource=object:ScopedReadPort {
        override val domains=setOf(AiDomain.D15,AiDomain.D16)
        override fun version(domain:AiDomain)=coordinator.sourceVersion(domain)
        private fun source()=recordings().store.scopedReads()
        override fun query(spec:QuerySpec,after:String?)=source().query(spec,after)
        override fun get(domain:AiDomain,key:String,projection:String)=source().get(domain,key,projection)
        override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int)=source().text(domain,key,field,start,maxChars)
        override fun aggregate(spec:AggregateSpec)=source().aggregate(spec)
    }
    val views=ValueScopedReadSource(setOf(AiDomain.D11,AiDomain.D12,AiDomain.D13,AiDomain.D14,AiDomain.D18,AiDomain.D19),{d->coordinator.sourceVersion(d)}){spec->
        if(spec.domain==AiDomain.D18){
            require(spec.projection in setOf("default","bindings","installed"))
            val bound=if(spec.projection=="installed")emptySequence()else preferences().scopedBindingRows()
            val installation=if(spec.projection=="bindings")emptySequence()else installed().let{state->
                state.ids.asSequence().map{id->SourceRow("installed:$id",aiObject("kind" to aiString("installed"),"widgetId" to aiNumber(id.toLong()),"access" to aiString(state.access.name)))}+
                    sequenceOf(SourceRow("installation-observation",aiObject("kind" to aiString("installation_observation"),"access" to aiString(state.access.name))))
            }
            bound+installation
        }else{
            require(spec.projection in setOf("default","current"))
            val value=when(spec.domain){
                AiDomain.D11->safeValue(preferences().getAppearance())
                AiDomain.D12->aiObject("settings" to runBlocking{safeValue(api().ringtone.getSettings().requiredValue())},"playback" to safeValue(playback()))
                AiDomain.D13->runBlocking{safeValue(recordings().aiSettings.getRecordingView().requiredValue())}
                AiDomain.D14->runBlocking{safeValue(recordings().aiSettings.getAiView().requiredValue())}
                AiDomain.D19->aiObject("runtime" to runBlocking{safeValue(api().runtime.getStatus().requiredValue())},"recordingPlayback" to recordings().service.playbackSnapshot(),"processing" to recordings().workflow.activitySnapshot(),"scheduledJobs" to jobs())
                else->error("ai.query_not_implemented")
            }
            sequenceOf(SourceRow("current",value as AiJson.Obj))
        }
    }
    val diagnosticSource=object:ScopedReadPort {
        override val domains=setOf(AiDomain.D20)
        override fun version(domain:AiDomain)=coordinator.sourceVersion(domain)
        override fun query(spec:QuerySpec,after:String?)=diagnostics().scopedReads().query(spec,after)
        override fun get(domain:AiDomain,key:String,projection:String)=diagnostics().scopedReads().get(domain,key,projection)
        override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int)=diagnostics().scopedReads().text(domain,key,field,start,maxChars)
        override fun aggregate(spec:AggregateSpec)=diagnostics().scopedReads().aggregate(spec)
    }
    val sources=listOf(core,recordingSource,views,diagnosticSource,directory.scopedReads({recordings().store.settings().systemFolder},{coordinator.sourceVersion(AiDomain.D17)},resources))
    require(sources.flatMap{it.domains}.toSet()==AiDomain.entries.toSet())
    return QueryService(sources,clock::millis,coordinator::marker){recordings().store.settings().let{SecretRedactor(listOf(it.analysis.apiKey,it.transcription.apiKey))}}
}
