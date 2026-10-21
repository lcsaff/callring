package com.greatwall.callring.application.coordination

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.application.ports.BackendFailure
import java.util.UUID
import com.greatwall.callring.application.coordination.*

/** Fence only publication, never preparation, network, or consumers of the change feed. */
class BusinessRevisionCoordinator {
    val fence = Any()
    private val incarnation = UUID.randomUUID().toString()
    val invalidations = InvalidationTracker(incarnation)
    fun publishCore(keys: Set<String>, impact: ChangeImpact) = synchronized(fence) {
        publishResource("core", keys, impact)
    }
    fun publishResource(domain: String, keys: Set<String>, impact: ChangeImpact) =
        publishResources(setOf(domain), keys, impact)

    /** One logical commit may change multiple persisted resources. */
    fun publishResources(resources: Set<String>, keys: Set<String>, impact: ChangeImpact) = synchronized(fence) {
        if (keys.isEmpty()) return@synchronized
        require(resources.isNotEmpty() && resources.all { it in versions })
        revision = Math.addExact(revision, 1)
        resources.forEach { domain ->
            versions[domain] = Math.addExact(versions.getValue(domain), 1)
            domainMarkers.getValue(domain).let { history ->
                history[versions.getValue(domain)] = revision
                if (history.size > 1024) history.remove(history.keys.first())
            }
        }
        (keys + resources).forEach { scopeChanges[it] = revision }
        invalidations.publish(revision, impact)
    }
    private val versions = linkedMapOf("core" to 0L, "recording" to 0L, "recordingConfig" to 0L, "aiConfig" to 0L, "preferences" to 0L, "ringtone" to 0L, "diagnostics" to 0L)
    private var revision = 0L
    private val scopeChanges=linkedMapOf<String,Long>()
    private val domainMarkers=versions.keys.associateWith{linkedMapOf(0L to 0L)}
    fun lastChange(keys:Set<String>):Long=synchronized(fence){keys.maxOfOrNull{scopeChanges[it]?:0L}?:0L}
    fun marker():Long=synchronized(fence){revision}
    fun domainMarker(domain:String,version:Long):Long?=synchronized(fence){domainMarkers[domain]?.get(version)}
    fun unchanged(keys:Set<String>,at:Long)=synchronized(fence){keys.isNotEmpty()&&at<=revision&&keys.none{(scopeChanges[it]?:0L)>at}}
    fun expect(keys:Set<String>,observedAt:Long=marker()):Map<String,Long> = synchronized(fence){require(keys.isNotEmpty()&&observedAt<=revision);keys.associateWith{observedAt}}
    private val commandCommits = linkedMapOf<String, RevisionVector>()
    private val commandMarkers = linkedMapOf<String,Long>()
    fun capture(): RevisionVector = synchronized(fence) { RevisionVector(incarnation, versions.toMap()) }
    fun check(context: CommandContext?) { synchronized(fence){
        if (context == null) return
        if (!context.active()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "ai.cancelled"))
        if (!context.mayCommit()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "action.foreground_required"))
        val scopes=context.expectedScopes
        // record() runs inside the same fence as the guarded write. Its marker is a
        // safe continuation baseline, never a snapshot captured after yielding to others.
        val ownMarker = if (context.continueOwnCommits) commandMarkers[context.operationId] else null
        val ownVersions = if (context.continueOwnCommits) commandCommits[context.operationId] else null
        val changed = if (scopes == null) {
            (ownVersions ?: context.expectedVersions) != capture()
        } else {
            scopes.isEmpty() || context.expectedVersions.incarnation != incarnation ||
                scopes.any { (key, at) -> at > revision || (scopeChanges[key] ?: 0L) > maxOf(at, ownMarker ?: at) }
        }
        if (changed) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    } }
    fun <T> guard(context: CommandContext? = null, block: () -> T): T = synchronized(fence) { check(context); block() }
    fun record(context: CommandContext?) { if(context != null) synchronized(fence) { commandCommits[context.operationId] = capture();commandMarkers[context.operationId]=revision } }
    fun committedMarker(operationId:String):Long?=synchronized(fence){commandMarkers[operationId]}
    fun committed(operationId: String): RevisionVector? = synchronized(fence) { commandCommits[operationId] }
    fun publishRuntime(keys:Set<String>)=synchronized(fence) {
        if(keys.isEmpty())return@synchronized
        revision=Math.addExact(revision,1)
        keys.forEach{scopeChanges[it]=revision}
        val impact=ChangeImpact(mapOf(DataDomain.RUNTIME to keys.mapTo(linkedSetOf()) { it.removePrefix("runtime:") }))
        invalidations.publish(revision,impact)
    }
}
