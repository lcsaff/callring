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
    private val commandCommits = linkedMapOf<String, RevisionVector>()
    val changes = MutableStateFlow(DataChange(0, DataDomain.values().toSet(), null, null))
    fun capture(): RevisionVector = synchronized(fence) { RevisionVector(incarnation, versions.toMap()) }
    fun check(context: CommandContext?) {
        if (context == null) return
        if (!context.active()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "ai.cancelled"))
        if (!context.mayCommit()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "action.foreground_required"))
        if (context.expectedVersions != capture()) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    }
    fun <T> guard(context: CommandContext? = null, block: () -> T): T = synchronized(fence) { check(context); block() }
    fun changed(domain: String) = synchronized(fence) {
        versions[domain] = Math.addExact(versions.getValue(domain), 1)
        revision = Math.addExact(revision, 1)
        changes.value = DataChange(revision, DataDomain.values().toSet(), null, null)
    }
    fun record(context: CommandContext?) { if(context != null) synchronized(fence) { commandCommits[context.operationId] = capture() } }
    fun committed(operationId: String): RevisionVector? = synchronized(fence) { commandCommits[operationId] }
    fun runtimeChanged() = synchronized(fence) { revision = Math.addExact(revision,1); changes.value = DataChange(revision,DataDomain.values().toSet(),null,null) }
    fun <T> commit(domain: String, context: CommandContext? = null, block: () -> T): T = guard(context) { block().also { changed(domain); record(context) } }
}
