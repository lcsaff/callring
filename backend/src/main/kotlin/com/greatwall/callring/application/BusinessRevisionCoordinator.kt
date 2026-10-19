package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/** Fence only publication, never preparation, network, or consumers of the change feed. */
class BusinessRevisionCoordinator {
    val fence = Any()
    private val incarnation = UUID.randomUUID().toString()
    private val versions = linkedMapOf("core" to 0L, "recording" to 0L, "recordingConfig" to 0L, "aiConfig" to 0L, "preferences" to 0L, "ringtone" to 0L, "diagnostics" to 0L)
    private var revision = 0L
    private val scopeChanges=linkedMapOf<String,Long>()
    private val domainMarkers=versions.keys.associateWith{linkedMapOf(0L to 0L)}
    fun marker():Long=synchronized(fence){revision}
    fun domainMarker(domain:String,version:Long):Long?=synchronized(fence){domainMarkers[domain]?.get(version)}
    fun unchanged(keys:Set<String>,at:Long)=synchronized(fence){keys.isNotEmpty()&&at<=revision&&keys.none{(scopeChanges[it]?:0L)>at}}
    fun expect(keys:Set<String>,observedAt:Long=marker()):Map<String,Long> = synchronized(fence){require(keys.isNotEmpty()&&observedAt<=revision);keys.associateWith{observedAt}}
    fun publishScopes(keys:Set<String>)=synchronized(fence){
        if(keys.isNotEmpty()){revision=Math.addExact(revision,1);keys.forEach{scopeChanges[it]=revision};changes.value=DataChange(revision,DataDomain.values().toSet(),null,null)}
    }
    private val commandCommits = linkedMapOf<String, RevisionVector>()
    val changes = MutableStateFlow(DataChange(0, DataDomain.values().toSet(), null, null))
    fun capture(): RevisionVector = synchronized(fence) { RevisionVector(incarnation, versions.toMap()) }
    fun check(context: CommandContext?) { synchronized(fence){
        if (context == null) return
        if (!context.active()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "ai.cancelled"))
        if (!context.mayCommit()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "action.foreground_required"))
        val scopes=context.expectedScopes
        val changed=if(scopes==null) context.expectedVersions!=capture() else scopes.isEmpty()||context.expectedVersions.incarnation!=incarnation||scopes.any{(key,at)->at>revision||(scopeChanges[key]?:0L)>at}
        if (changed) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    } }
    fun <T> guard(context: CommandContext? = null, block: () -> T): T = synchronized(fence) { check(context); block() }
    fun changed(domain: String) = synchronized(fence) {
        versions[domain] = Math.addExact(versions.getValue(domain), 1)
        revision = Math.addExact(revision, 1)
        scopeChanges[domain]=revision
        domainMarkers.getValue(domain).let{history->history[versions.getValue(domain)]=revision;if(history.size>1024)history.remove(history.keys.first())}
        changes.value = DataChange(revision, DataDomain.values().toSet(), null, null)
    }
    fun record(context: CommandContext?) { if(context != null) synchronized(fence) { commandCommits[context.operationId] = capture() } }
    fun committed(operationId: String): RevisionVector? = synchronized(fence) { commandCommits[operationId] }
    fun runtimeChanged() = synchronized(fence) { revision = Math.addExact(revision,1); changes.value = DataChange(revision,DataDomain.values().toSet(),null,null) }
    fun <T> commit(domain: String, context: CommandContext? = null, block: () -> T): T = guard(context) { block().also { changed(domain); record(context) } }
}
