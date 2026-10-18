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
