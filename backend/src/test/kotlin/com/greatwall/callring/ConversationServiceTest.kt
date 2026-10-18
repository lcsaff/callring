package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConversationServiceTest {
    @Test fun sendRejectsUndisclosedUrlModelOrProtocolBeforeAnyRequest() = runBlocking {
        val f = ConversationFixtures()
        var config = settings()
        val original = config.analysis
        val disclosed = AiConnectionView(original.protocol,original.url,original.model,true)
        val client = Client(ArrayDeque(listOf(ConversationReply.Final("done"))))
        val service = ConversationService({ f.backend },snapshots(f),{ config },f.coordinator,{ client })
        try {
            for(connection in listOf(original.copy(url = "https://new.example/v1"),original.copy(model = "new"),original.copy(protocol = AiProtocol.ANTHROPIC))) {
                config = config.copy(analysis = connection)
                assertTrue(service.sendTo(service.state.value.sessionId,"next",disclosed) is ApiResult.Failure)
                assertEquals(0,client.requests)
            }
            val actual = config.analysis
            assertTrue(service.sendTo(service.state.value.sessionId,"next",AiConnectionView(actual.protocol,actual.url,actual.model,true)) is ApiResult.Success)
            withTimeout(5000) { service.state.first { !it.busy } }
            assertEquals(1,client.requests)
            assertEquals(ConversationPhase.COMPLETED,service.state.value.phase)
        } finally { service.close() }
    }
    @Test fun analysisTargetChangeCancelsTurnAndClearsMatchingHistory() = runBlocking {
        val fixture = ConversationFixtures()
        var configuration = settings()
        val entered = CompletableDeferred<Unit>(); var cancelled = false
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = byteArrayOf()
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { entered.complete(Unit); awaitCancellation() }
            override fun cancel() { cancelled = true }
        }
        val service = ConversationService({ fixture.backend }, snapshots(fixture), { configuration }, fixture.coordinator, { client })
        service.send(service.state.value.sessionId,"old target")
        withTimeout(5000) { entered.await() }
        configuration = configuration.copy(analysis = configuration.analysis.copy(model = "changed"))
        fixture.coordinator.changed("aiConfig")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertTrue(cancelled)
        assertEquals("ai.connection_changed", service.state.value.error?.message)
        assertTrue(service.state.value.messages.isEmpty())
        service.close()
    }
    @Test fun incomingCallCanPublishWhileNetworkWaitsAndStaleReplyCannotWrite() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.settings.setStatisticsEnabled(true)
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<ConversationReply>()
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = byteArrayOf()
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { entered.complete(Unit); return reply.await() }
            override fun cancel() = Unit
        }
        val service = ConversationService({ fixture.backend }, snapshots(fixture), ::settings, fixture.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change quiet")
        withTimeout(5000) { entered.await() }
        val id = fixture.backend.recordIncoming("31234",fixture.now,CallDecision.NORMAL_RINGING)
        assertTrue(id > 0)
        reply.complete(ConversationReply.ToolBatch("",listOf(ConversationToolCall("one","settings_setQuietEnabled",aiObject("enabled" to aiBool(true))))))
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals("business.changed", service.state.value.error?.message)
        assertEquals(QuietMode.OFF,fixture.state.quiet.mode)
        assertEquals(id,fixture.state.calls.getValue(id).id)
        service.close()
    }
    @Test fun reusedCallIdWithDifferentArgumentsCannotExecuteAgain() = runBlocking {
        val fixture = ConversationFixtures()
        val first = ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true)))
        val client = Client(ArrayDeque(listOf(ConversationReply.ToolBatch("", listOf(first)), ConversationReply.ToolBatch("", listOf(first.copy(arguments = aiObject("enabled" to aiBool(false))))))))
        val service = ConversationService({ fixture.backend }, snapshots(fixture), ::settings, fixture.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.FAILED,service.state.value.phase)
        assertEquals(QuietMode.NON_WORK,fixture.state.quiet.mode)
        assertEquals(1L,fixture.coordinator.capture().values["core"])
        assertEquals(1,service.state.value.receipts.size)
        service.close()
    }
    @Test fun stopCancelsPendingNetworkAndBusySendNeverQueuesAnotherTurn() = runBlocking {
        val fixture = ConversationFixtures()
        val entered = CompletableDeferred<Unit>(); var cancelled = false; var requests = 0
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = byteArrayOf()
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++; entered.complete(Unit); awaitCancellation() }
            override fun cancel() { cancelled = true }
        }
        val service = ConversationService({ fixture.backend },snapshots(fixture),::settings,fixture.coordinator,{ client })
        val session = service.state.value.sessionId
        service.send(session,"first")
        withTimeout(5000) { entered.await() }
        assertTrue(service.send(session,"must not queue") is ApiResult.Failure)
        service.stop(session)
        assertTrue(cancelled); assertEquals(1,requests)
        assertEquals(ConversationPhase.STOPPED,service.state.value.phase)
        assertEquals(QuietMode.OFF,fixture.state.quiet.mode)
        service.newSession(session)
        assertNotEquals(session,service.state.value.sessionId)
        assertTrue(service.state.value.messages.isEmpty())
        service.close()
    }
    @Test fun budgetGrowthAfterCommitStopsRemainingToolsAndRetainsReceipt() = runBlocking {
        val fixture = ConversationFixtures(); var requests = 0
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = ByteArray(if(fixture.coordinator.capture().values.getValue("core") > 0) 50000 else 0)
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++; return ConversationReply.ToolBatch("",listOf(
                ConversationToolCall("one","settings_setQuietEnabled",aiObject("enabled" to aiBool(true))),
                ConversationToolCall("two","settings_setQuietEnabled",aiObject("enabled" to aiBool(false))))) }
            override fun cancel() = Unit
        }
        val service = ConversationService({ fixture.backend },snapshots(fixture),::settings,fixture.coordinator,{ client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.FAILED,service.state.value.phase)
        assertEquals("ai.context_budget_exceeded",service.state.value.error!!.message)
        assertEquals(QuietMode.NON_WORK,fixture.state.quiet.mode)
        assertEquals(1,requests)
        assertEquals(ToolStatus.COMMITTED,service.state.value.receipts.single().status)
        assertTrue(service.state.value.messages.flatMap { it.receipts }.any { it.toolCallId == "two" && it.status == ToolStatus.SKIPPED })
        service.close()
    }
    private class Client(val replies: ArrayDeque<ConversationReply>) : ConversationClient {
        var requests = 0
        override fun encode(connection: AiConnection, request: ConversationRequest) = request.snapshot.encode().toByteArray()
        override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++; return replies.removeFirst() }
        override fun cancel() = Unit
    }
    private fun snapshots(f: ConversationFixtures) = AiSnapshotService(f.coordinator, listOf({ at ->
        val core = f.backend.captureCore(at).requiredValue()
        (1..21).associate { i -> val id = "D%02d".format(i); id to SnapshotPart(core[id] ?: AiJson.Arr(emptyList())) }
    }), { listOf("secret") })
    private fun settings() = RecordingSettings(analysis = AiConnection(url = "https://example.com", apiKey = "secret", model = "fake"))
    @Test fun malformedWholeBatchNeverWritesAndBusyIsRejected() = runBlocking {
        val f = ConversationFixtures()
        val calls = listOf(ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true))), ConversationToolCall("two", "run_sql", aiObject()))
        val client = Client(ArrayDeque<ConversationReply>(listOf(ConversationReply.ToolBatch("", calls))))
        val service = ConversationService({ f.backend }, snapshots(f), ::settings, f.coordinator, { client })
        service.foreground.attachHost()
        val session = service.state.value.sessionId
        assertTrue(service.send(session, "enable quiet") is ApiResult.Success)
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.FAILED, service.state.value.phase)
        assertEquals(QuietMode.OFF, f.state.quiet.mode)
        assertEquals(1, client.requests)
        service.close()
    }
    @Test fun duplicateCallsReturnLedgerReceiptAndSuccessfulWriteRefreshesFullSnapshot() = runBlocking {
        val f = ConversationFixtures()
        val call = ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true)))
        val client = Client(ArrayDeque<ConversationReply>(listOf(ConversationReply.ToolBatch("", listOf(call)), ConversationReply.ToolBatch("", listOf(call)), ConversationReply.Final("done"))))
        val service = ConversationService({ f.backend }, snapshots(f), ::settings, f.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId, "enable quiet")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.COMPLETED, service.state.value.phase)
        assertEquals(QuietMode.NON_WORK, f.state.quiet.mode)
        assertEquals(1L, f.coordinator.capture().values["core"])
        assertEquals(3, client.requests)
        assertTrue(service.state.value.receipts.all { it.status == ToolStatus.COMMITTED })
        service.close()
    }
}
