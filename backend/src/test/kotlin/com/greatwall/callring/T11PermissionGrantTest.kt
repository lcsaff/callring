package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class T11PermissionGrantTest {
    @Test fun fullVectorCommandStillRequiresTheExactRuntimeProofMarker() = runBlocking {
        val f = ConversationFixtures()
        f.backend.runtime.getStatus()
        val observedAt = f.coordinator.marker()
        f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
        f.backend.runtime.getStatus().requiredValue()
        val validator = ForegroundCommandValidator(f.coordinator)
        val command = com.greatwall.callring.contracts.command.CommandContext("full-proof", f.coordinator.capture(), 1) { true }
        assertTrue(runCatching { validator.recordCapability(command, Capability.CALL_LOG, observedAt) }.isFailure)
        assertNull(f.coordinator.committed(command.operationId))
    }
    @Test fun olderGrantedProofCannotOwnANewerDeniedObservation() = runBlocking {
        val f = ConversationFixtures(); f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
        f.backend.runtime.getStatus().requiredValue()
        val foreground = ForegroundActionService(checkCommand = ForegroundCommandValidator(f.coordinator)::check)
        val host = foreground.attachHost(); var injected = false
        val api = object : com.greatwall.callring.contracts.CallringUiApi by f.backend {
            override fun commandApi(context: com.greatwall.callring.contracts.command.CommandContext): com.greatwall.callring.contracts.CallringUiApi {
                val bound = f.backend.commandApi(context)
                return object : com.greatwall.callring.contracts.CallringUiApi by bound {
                    override val runtime = object : RuntimeApi by bound.runtime {
                        override suspend fun getStatus(): com.greatwall.callring.contracts.common.ApiResult<RuntimeStatus> {
                            val proof = bound.runtime.getStatus()
                            if (!injected) {
                                injected = true
                                f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
                                f.backend.runtime.getStatus().requiredValue()
                            }
                            return proof
                        }
                    }
                }
            }
        }
        val command = com.greatwall.callring.contracts.command.CommandContext("old-proof", f.coordinator.capture(), 1,
            expectedScopes = mapOf("runtime:capability:CALL_LOG" to 0L), mayCommit = { foreground.hasHost }) { true }
        val executor = ToolExecutor({ api }, f.coordinator, foreground, ForegroundResourceRegistry())
        val result = async { executor.execute(ConversationToolCall("action", "system_requestCapability", aiObject("capability" to aiString("CALL_LOG"))),
            ToolExecutionContext("s", "t", 1, command)) }
        val ticket = withTimeout(5000) { foreground.pending.first { it != null }!! }
        assertTrue(foreground.beginLaunch(ticket.requestId, host))
        f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.GRANTED); f.backend.runtime.getStatus().requiredValue()
        assertTrue(foreground.deliver(ticket.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        assertEquals(ToolStatus.DENIED, result.await().status)
        assertTrue(injected); assertNull(f.coordinator.committed(command.operationId))
    }
    @Test fun settingsReturnsLaunchedWithoutOwningRuntimeChangesAndFalseCapabilityReceiptIsRejected() = runBlocking {
        for (settings in listOf(true, false)) {
            val f = ConversationFixtures(); f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
            f.backend.runtime.getStatus().requiredValue()
            val validator = ForegroundCommandValidator(f.coordinator)
            val foreground = ForegroundActionService(checkCommand = validator::check)
            val host = foreground.attachHost()
            val key = if (settings) "runtime:status" else "runtime:capability:CALL_LOG"
            val command = com.greatwall.callring.contracts.command.CommandContext("op", f.coordinator.capture(), 1,
                expectedScopes = mapOf(key to f.coordinator.marker()), mayCommit = { foreground.hasHost }) { true }
            val executor = ToolExecutor({ f.backend }, f.coordinator, foreground, ForegroundResourceRegistry())
            val call = ConversationToolCall("action", if (settings) "system_openApplicationSettings" else "system_requestCapability",
                if (settings) aiObject() else aiObject("capability" to aiString("CALL_LOG")))
            val receipt = async { executor.execute(call, ToolExecutionContext("session", "turn", 1, command)) }
            val ticket = withTimeout(5000) { foreground.pending.first { it != null }!! }
            assertTrue(foreground.beginLaunch(ticket.requestId, host))
            if (settings) { f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.GRANTED); f.backend.runtime.getStatus().requiredValue() }
            assertTrue(foreground.deliver(ticket.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
            val result = receipt.await()
            assertEquals(result.json().encode(), if (settings) ToolStatus.LAUNCHED else ToolStatus.DENIED, result.status)
            assertNull(f.coordinator.committed(command.operationId))
        }
    }
    @Test fun targetRebaseCannotAdvanceOtherScopesOrBypassTheFinalForegroundGate() = runBlocking {
        val f = ConversationFixtures(); f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
        f.backend.runtime.getStatus().requiredValue()
        val validator = ForegroundCommandValidator(f.coordinator)
        val foreground = ForegroundActionService(checkCommand = validator::check); val host = foreground.attachHost()
        val command = com.greatwall.callring.contracts.command.CommandContext("foreign", f.coordinator.capture(), 1,
            expectedScopes = mapOf("runtime:capability:CALL_LOG" to 0L, "profile:A" to 0L)) { true }
        val result = async { foreground.request("s", "t", "call", 1, ForegroundActionKind.CAPABILITY,
            aiObject("capability" to aiString("CALL_LOG")), commandContext = command) { true } }
        val ticket = withTimeout(5000) { foreground.pending.first { it != null }!! }
        assertTrue(foreground.beginLaunch(ticket.requestId, host))
        f.coordinator.publishCore(setOf("profile:A"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.GRANTED); f.backend.runtime.getStatus().requiredValue()
        assertFalse(foreground.deliver(ticket.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        assertEquals(ForegroundResultStatus.STALE, result.await().status)
        assertTrue(runCatching { validator.recordCapability(command, Capability.CALL_LOG, f.coordinator.marker()) }.isFailure)
        val validScopes = command.copy(expectedScopes = f.coordinator.expect(setOf("runtime:capability:CALL_LOG")), mayCommit = { false })
        assertTrue(runCatching { validator.recordCapability(validScopes, Capability.CALL_LOG, f.coordinator.marker()) }.isFailure)
        assertNull(f.coordinator.committed(command.operationId))
    }
    @Test fun actualGrantSurvivesTrackerPublicationAndFollowingCallLogCommandUsesOwnTargetObservation() = runBlocking {
        val f = ConversationFixtures()
        f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
        val source = object : ScopedReadPort {
            override val domains = setOf(AiDomain.D19)
            override fun version(domain: AiDomain) = f.coordinator.lastChange(setOf("runtime:status"))
            override fun get(domain: AiDomain, key: String, projection: String) = SourceRow(key,
                aiObject("runtime" to safeValue(runBlocking { f.backend.runtime.getStatus().requiredValue() })))
            override fun query(spec: QuerySpec, after: String?) = SourcePage(listOf(get(spec.domain, "current", spec.projection)), 1)
        }
        val queries = QueryService(listOf(source), revision = f.coordinator::marker)
        val conversation = ConversationService({ f.backend }, queries, { RecordingSettings() }, f.coordinator, { error("no provider") })
        try {
            val foreground = conversation.foreground
            val host = foreground.attachHost()
            val executor = ToolExecutor({ f.backend }, f.coordinator, foreground, ForegroundResourceRegistry(), queries = queries)
            val engine = ToolBatchEngine({ f.backend }, executor, queries, f.coordinator, "session", "turn", 1, { true })
            queries.get(AiDomain.D19, "current").requiredValue()
            val plan = engine.execute(listOf(ConversationToolCall("plan", "task_prepare", aiObject("kind" to aiString("OPERATIONS"), "expectedCount" to aiNumber(2))))).single()
            val task = (plan.value as AiJson.Obj)["taskId"].string()
            val call = ConversationToolCall("grant", "system_requestCapability", aiObject("capability" to aiString("CALL_LOG"), "taskId" to aiString(task)))
            val result = async { engine.execute(listOf(call, ConversationToolCall("sync", "runtime_requestCallLogSync", aiObject("taskId" to aiString(task), "dependsOn" to AiJson.Arr(listOf(aiString("grant"))))))) }
            val ticket = withTimeout(5000) { foreground.pending.first { it != null }!! }
            assertTrue(foreground.beginLaunch(ticket.requestId, host))
            f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.GRANTED)
            f.backend.runtime.getStatus().requiredValue()
            assertTrue("the requested platform transition must not stale its launched ticket", foreground.deliver(ticket.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
            val receipts = result.await()
            assertEquals(VerificationStatus.VERIFIED, receipts[0].verification)
            val sync = receipts[1]
            assertEquals(sync.json().encode(), ToolStatus.QUEUED, sync.status)
            val grantMarker = f.coordinator.marker()
            f.capabilities = f.capabilities + (Capability.CALL_LOG to AccessState.DENIED)
            f.backend.runtime.getStatus().requiredValue()
            val foreign = com.greatwall.callring.contracts.command.CommandContext("foreign", f.coordinator.capture(), 1,
                expectedScopes = mapOf("runtime:capability:CALL_LOG" to grantMarker)) { true }
            val stale = f.backend.commandApi(foreign).runtime.requestCallLogSync()
            assertEquals(com.greatwall.callring.contracts.common.ErrorCode.CONFLICT, (stale as com.greatwall.callring.contracts.common.ApiResult.Failure).error.code)
        } finally { conversation.close() }
    }
}
