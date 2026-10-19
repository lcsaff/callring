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
    @Test fun undisclosedResolvedProfileCannotReceiveHistory() = runBlocking {
        val f=ConversationFixtures()
        val connection=AiConnection(url="https://dashscope.aliyuncs.com/compatible-mode/v1",model="qwen3.8-flash",apiKey="secret")
        val client=Client(ArrayDeque(listOf(ConversationReply.Final("done"))))
        val service=ConversationService({f.backend},queries(f),{RecordingSettings(analysis=connection)},f.coordinator,{client})
        try {
            assertTrue(service.sendTo(service.state.value.sessionId,"next",AiConnectionView(connection.protocol,connection.url,connection.model,true,ProviderProfile.STANDARD)) is ApiResult.Failure)
            assertEquals(0,client.requests)
        } finally { service.close() }
    }
    @Test fun profileChangeCancelsAnActiveTurn() = runBlocking {
        val f=ConversationFixtures(); var config=settings().copy(analysis=AiConnection(url="https://dashscope.aliyuncs.com/compatible-mode/v1",model="qwen3.8-flash",apiKey="secret"))
        val entered=CompletableDeferred<Unit>(); var cancelled=false
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest)=byteArrayOf()
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply { entered.complete(Unit);awaitCancellation() }
            override fun cancel() {cancelled=true}
        }
        val service=ConversationService({f.backend},queries(f),{config},f.coordinator,{client})
        try {
            service.send(service.state.value.sessionId,"hello"); withTimeout(5000){entered.await()}
            config=config.copy(conversationCompatibility=ConversationCompatibility.STANDARD);f.coordinator.changed("aiConfig")
            withTimeout(1500){service.state.first{!it.busy}}
            assertTrue(cancelled);assertTrue(service.state.value.messages.isEmpty())
        } finally {service.close()}
    }
    @Test fun sendRejectsUndisclosedUrlModelOrProtocolBeforeAnyRequest() = runBlocking {
        val f = ConversationFixtures()
        var config = settings()
        val original = config.analysis
        val disclosed = AiConnectionView(original.protocol,original.url,original.model,true)
        val client = Client(ArrayDeque(listOf(ConversationReply.Final("done"))))
        val service = ConversationService({ f.backend },queries(f),{ config },f.coordinator,{ client })
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
        val service = ConversationService({ fixture.backend }, queries(fixture), { configuration }, fixture.coordinator, { client })
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
    @Test fun incomingCallCanPublishWhileNetworkWaitsWithoutRejectingUnrelatedQuietEdit() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.settings.setStatisticsEnabled(true)
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<ConversationReply>(); var requests=0
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = byteArrayOf()
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++;return when(requests){1->discover("settings_getQuietHours","settings_setQuietEnabled");2->ConversationReply.ToolBatch("",listOf(ConversationToolCall("read","settings_getQuietHours",aiObject())));3->{entered.complete(Unit);reply.await()};else->ConversationReply.Final("done")} }
            override fun cancel() = Unit
        }
        val service = ConversationService({ fixture.backend }, queries(fixture), ::settings, fixture.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change quiet")
        withTimeout(5000) { entered.await() }
        val id = fixture.backend.recordIncoming("31234",fixture.now,CallDecision.NORMAL_RINGING)
        assertTrue(id > 0)
        reply.complete(ConversationReply.ToolBatch("",listOf(ConversationToolCall("one","settings_setQuietEnabled",aiObject("enabled" to aiBool(true))))))
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.COMPLETED,service.state.value.phase)
        assertEquals(QuietMode.NON_WORK,fixture.state.quiet.mode)
        assertEquals(id,fixture.state.calls.getValue(id).id)
        service.close()
    }
    @Test fun reusedCallIdWithDifferentArgumentsCannotExecuteAgain() = runBlocking {
        val fixture = ConversationFixtures()
        val first = ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true)))
        val client = Client(ArrayDeque(listOf(discover("settings_setQuietEnabled"),ConversationReply.ToolBatch("", listOf(first)), ConversationReply.ToolBatch("", listOf(first.copy(arguments = aiObject("enabled" to aiBool(false))))))))
        val service = ConversationService({ fixture.backend }, queries(fixture), ::settings, fixture.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.PARTIAL,service.state.value.phase)
        assertEquals(QuietMode.NON_WORK,fixture.state.quiet.mode)
        assertEquals(1L,fixture.coordinator.capture().values["core"])
        assertEquals(1,service.state.value.receipts.count{it.status==ToolStatus.COMMITTED})
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
        val service = ConversationService({ fixture.backend },queries(fixture),::settings,fixture.coordinator,{ client })
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
    @Test fun budgetGrowthAfterCommitPreservesReceiptAndStopsBeforeAnotherRequest() = runBlocking {
        val fixture = ConversationFixtures(); var requests = 0
        val client = object : ConversationClient {
            override fun encode(connection: AiConnection, request: ConversationRequest) = ByteArray(if(fixture.coordinator.capture().values.getValue("core") > 0) 50000 else 0)
            override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++;return if(requests==1)discover("settings_setQuietEnabled")else ConversationReply.ToolBatch("",listOf(ConversationToolCall("one","settings_setQuietEnabled",aiObject("enabled" to aiBool(true))))) }
            override fun cancel() = Unit
        }
        val service = ConversationService({ fixture.backend },queries(fixture),::settings,fixture.coordinator,{ client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId,"change")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.PARTIAL,service.state.value.phase)
        assertEquals("ai.context_budget_exceeded",service.state.value.error!!.message)
        assertEquals(QuietMode.NON_WORK,fixture.state.quiet.mode)
        assertEquals(2,requests)
        assertEquals(1,service.state.value.receipts.count{it.status==ToolStatus.COMMITTED})

        service.close()
    }
    private fun discover(vararg names:String)=ConversationReply.ToolBatch("",listOf(ConversationToolCall("discover","discover_tools",aiObject("exactNames" to AiJson.Arr(names.map(::aiString))))))
    @Test fun explicitTaskReferenceContinuesOnlyRemainingWorkAndExpiresOnNewSession()=runBlocking {
        val f=ConversationFixtures();var n=0;var task=""
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest):ByteArray {request.messages.flatMap{it.receipts}.forEach{r->(r.value as? AiJson.Obj)?.optSForTest("taskId")?.let{task=it}};return byteArrayOf()}
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply {
                n++;return when(n){1,5->discover("task_prepare","settings_setQuietEnabled");2->ConversationReply.ToolBatch("",listOf(ConversationToolCall("plan","task_prepare",aiObject("kind" to aiString("OPERATIONS"),"expectedCount" to aiNumber(2)))));3,6->ConversationReply.ToolBatch("",listOf(ConversationToolCall("save-$n","settings_setQuietEnabled",aiObject("taskId" to aiString(task),"enabled" to aiBool(n==3)))));4->throw BackendFailure(ApiError(ErrorCode.IO_FAILURE,"ai.timeout"));else->ConversationReply.Final("done")}
            }
            override fun cancel()=Unit
        }
        val service=ConversationService({f.backend},queries(f),::settings,f.coordinator,{client});service.foreground.attachHost()
        try {
            val session=service.state.value.sessionId;service.send(session,"two operations");withTimeout(5000){service.state.first{!it.busy}}
            assertEquals(ConversationPhase.PARTIAL,service.state.value.phase);assertEquals(listOf(task),service.state.value.continuableTasks)
            assertTrue(service.continueTask(session,task,AiConnectionView(AiProtocol.OPENAI,"https://example.com","fake",true)) is ApiResult.Success)
            withTimeout(5000){service.state.first{!it.busy}}
            assertEquals(ConversationPhase.COMPLETED,service.state.value.phase);assertEquals(QuietMode.OFF,f.state.quiet.mode);assertEquals(2L,f.coordinator.capture().values["core"])
            service.newSession(session)
            assertTrue(service.continueTask(service.state.value.sessionId,task,AiConnectionView(AiProtocol.OPENAI,"https://example.com","fake",true)) is ApiResult.Failure)
        }finally{service.close()}
    }
    private fun AiJson.Obj.optSForTest(key:String)=(this[key] as? AiJson.Str)?.value
    @Test fun prematureFinalGetsOneLedgerBasedChanceToFillMissingStep()=runBlocking {
        val f=ConversationFixtures();var n=0;var task=""
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest):ByteArray {request.messages.flatMap{it.receipts}.forEach{r->(r.value as? AiJson.Obj)?.optSForTest("taskId")?.let{task=it}};return byteArrayOf()}
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply {n++;return when(n){1->discover("task_prepare","settings_setQuietEnabled");2->ConversationReply.ToolBatch("",listOf(ConversationToolCall("plan","task_prepare",aiObject("kind" to aiString("OPERATIONS"),"expectedCount" to aiNumber(2)))));3,5->ConversationReply.ToolBatch("",listOf(ConversationToolCall("save-$n","settings_setQuietEnabled",aiObject("taskId" to aiString(task),"enabled" to aiBool(n==3)))));else->ConversationReply.Final("done")}}
            override fun cancel()=Unit
        }
        val service=ConversationService({f.backend},queries(f),::settings,f.coordinator,{client});service.foreground.attachHost()
        try{service.send(service.state.value.sessionId,"two operations");withTimeout(5000){service.state.first{!it.busy}};assertEquals(ConversationPhase.COMPLETED,service.state.value.phase);assertEquals(6,n);assertEquals(2L,f.coordinator.capture().values["core"])}finally{service.close()}
    }
    @Test fun providerBatchThatCannotFitRequiredWireHistoryNeverStartsAWrite()=runBlocking {
        val f=ConversationFixtures();var n=0
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest)=ByteArray(request.messages.flatMap{it.toolCalls}.sumOf{it.arguments.encode().toByteArray(Charsets.UTF_8).size})
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply {n++;return if(n==1)discover("settings_saveAppearance")else ConversationReply.ToolBatch("",listOf(ConversationToolCall("large","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"),"presetId" to aiString("x".repeat(50000))))))}
            override fun cancel()=Unit
        }
        val original=f.appearance;val service=ConversationService({f.backend},queries(f),::settings,f.coordinator,{client});service.foreground.attachHost()
        try{service.send(service.state.value.sessionId,"change");withTimeout(5000){service.state.first{!it.busy}};assertEquals(original,f.appearance);assertEquals("ai.context_budget_exceeded",service.state.value.error?.message);assertTrue(service.state.value.receipts.none{it.status==ToolStatus.COMMITTED})}finally{service.close()}
    }
    @Test fun sameFieldChangedAfterModelReadCannotBeRetriedOrReportedComplete()=runBlocking {
        val f=ConversationFixtures();val entered=CompletableDeferred<Unit>();val next=CompletableDeferred<ConversationReply>();var n=0
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest)=byteArrayOf()
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply {n++;return when(n){1->discover("settings_getQuietHours","settings_setQuietEnabled");2->ConversationReply.ToolBatch("",listOf(ConversationToolCall("read","settings_getQuietHours",aiObject())));3->{entered.complete(Unit);next.await()};else->ConversationReply.Final("done")}}
            override fun cancel()=Unit
        }
        val service=ConversationService({f.backend},queries(f),::settings,f.coordinator,{client});service.foreground.attachHost()
        try {
            service.send(service.state.value.sessionId,"enable quiet");withTimeout(5000){entered.await()}
            f.backend.settings.setQuietMode(QuietMode.NON_WORK).requiredValue()
            next.complete(ConversationReply.ToolBatch("",listOf(ConversationToolCall("save","settings_setQuietEnabled",aiObject("enabled" to aiBool(false))))))
            withTimeout(5000){service.state.first{!it.busy}}
            assertEquals(QuietMode.NON_WORK,f.state.quiet.mode);assertEquals(ConversationPhase.FAILED,service.state.value.phase);assertEquals("business.changed",service.state.value.error?.message)
            assertTrue(service.state.value.receipts.none{it.status==ToolStatus.COMMITTED})
        }finally{service.close()}
    }
    private class Client(val replies: ArrayDeque<ConversationReply>) : ConversationClient {
        var requests = 0
        override fun encode(connection: AiConnection, request: ConversationRequest) = request.snapshot.encode().toByteArray()
        override suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply { requests++; return replies.removeFirst() }
        override fun cancel() = Unit
    }
    private fun queries(f:ConversationFixtures)=QueryService(listOf(object:ScopedReadPort {
        override val domains=AiDomain.entries.toSet()
        override fun version(domain:AiDomain)=f.coordinator.capture().values.getValue("core")
        override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,aiObject("quiet_mode" to aiString(f.state.quiet.mode.name),"colorMode" to aiString(f.appearance.colorMode.name)))
        override fun query(spec:QuerySpec,after:String?)=SourcePage(listOf(get(spec.domain,"current",spec.projection)),1)
    }),revision=f.coordinator::marker)
    private fun settings() = RecordingSettings(analysis = AiConnection(url = "https://example.com", apiKey = "secret", model = "fake"))
    @Test fun malformedWholeBatchNeverWritesAndBusyIsRejected() = runBlocking {
        val f = ConversationFixtures()
        val calls = listOf(ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true))), ConversationToolCall("two", "run_sql", aiObject()))
        val client = Client(ArrayDeque<ConversationReply>(listOf(discover("settings_setQuietEnabled"),ConversationReply.ToolBatch("", calls),ConversationReply.Final("done"))))
        val service = ConversationService({ f.backend }, queries(f), ::settings, f.coordinator, { client })
        service.foreground.attachHost()
        val session = service.state.value.sessionId
        assertTrue(service.send(session, "enable quiet") is ApiResult.Success)
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.FAILED, service.state.value.phase)
        assertEquals(QuietMode.OFF, f.state.quiet.mode)
        assertEquals(3, client.requests)
        service.close()
    }
    @Test fun duplicateCallsReturnVerifiedLedgerReceiptWithoutRecapturingBusinessSnapshot() = runBlocking {
        val f = ConversationFixtures()
        val call = ConversationToolCall("one", "settings_setQuietEnabled", aiObject("enabled" to aiBool(true)))
        val client = Client(ArrayDeque<ConversationReply>(listOf(discover("settings_setQuietEnabled"),ConversationReply.ToolBatch("", listOf(call)), ConversationReply.ToolBatch("", listOf(call)), ConversationReply.Final("done"))))
        val service = ConversationService({ f.backend }, queries(f), ::settings, f.coordinator, { client })
        service.foreground.attachHost()
        service.send(service.state.value.sessionId, "enable quiet")
        withTimeout(5000) { service.state.first { !it.busy } }
        assertEquals(ConversationPhase.COMPLETED, service.state.value.phase)
        assertEquals(QuietMode.NON_WORK, f.state.quiet.mode)
        assertEquals(1L, f.coordinator.capture().values["core"])
        assertEquals(4, client.requests)
        assertTrue(service.state.value.receipts.filter{it.toolCallId=="one"}.all{it.verification==VerificationStatus.VERIFIED})
        service.close()
    }
}
