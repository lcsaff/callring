package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConfirmationTargetTest {
    @Test fun fiveConfirmationsUseRealTargetsAndRejectCancelChangedOrMissingTargets() = runBlocking {
        for(index in listOf(20,32,53,57,63)) {
            val f = ConversationFixtures()
            f.backend.settings.savePosition(SavePosition(name = "actual-role",level = 2,active = true))
            f.backend.settings.saveLeave(SaveLeave(period = LeavePeriod.Timed(10,20),note = "actual-note"))
            f.backend.callers.saveProfile(SaveCallerProfile("31234","old","old-org",null,false))
            var effects = 0
            val archive = CallArchive(1,"31234",123,generation = 7,analysis = CallAnalysis(name = "self",organization = "unit"))
            val recording = object : RecordingApi by UnavailableRecordingApi {
                override suspend fun getArchive(callId: Long) = ApiResult.Success(archive.takeIf { callId == 1L })
                override suspend fun delete(callId: Long): ApiResult<Unit> { effects++; return ApiResult.Success(Unit) }
            }
            val safe = AiConnectionView(AiProtocol.OPENAI,"https://actual.example/v1","actual-model",true)
            val settings = object : AiSettingsApi by UnavailableAiSettingsApi {
                override suspend fun getAiView() = ApiResult.Success(AiSettingsView(safe,safe,ConversationBudget(),0))
                override suspend fun testSlot(slot: AiSlot): ApiResult<Unit> { effects++; return ApiResult.Success(Unit) }
            }
            fun root(context: CommandContext? = null): CallringUiApi = object : CallringUiApi by (context?.let(f.backend::commandApi) ?: f.backend) {
                override val recordings = recording
                override val aiSettings = settings
                override fun commandApi(context: CommandContext) = root(context)
                override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long) = SharedBusinessCommands(this).adoptIdentity(callId,expectedGeneration)
            }
            val foreground = ForegroundActionService(checkCommand = { command -> command == null || runCatching { f.coordinator.guard(command) {} }.isSuccess })
            val host = foreground.attachHost()
            val executor = ToolExecutor({ root() },f.coordinator,foreground,ForegroundResourceRegistry())
            val definition = executor.registry.definitions.single { it.index == index }
            fun call(id: Long = 1) = ConversationToolCall("call",definition.name,when(index) {
                20,32 -> aiObject("id" to aiNumber(id))
                53 -> aiObject("slot" to aiString(if(id == 1L) "ANALYSIS" else "INVALID"))
                else -> aiObject("callId" to aiNumber(id))
            })
            fun scope() = ToolExecutionContext("s","t",1,CommandContext(java.util.UUID.randomUUID().toString(),f.coordinator.capture(),1) { true })
            assertEquals(ToolStatus.FAILED,withTimeout(3000) { executor.execute(call(999),scope()) }.status)
            assertNull(foreground.pending.value)
            for(changed in listOf(false,true)) {
                val before = f.coordinator.capture()
                val result = async { executor.execute(call(),scope()) }
                val ticket = withTimeout(3000) { foreground.pending.first { it != null } }!!
                when(val target = ticket.confirmation) {
                    is ConfirmationTarget.Position -> { assertEquals(1L,target.id); assertEquals("actual-role",target.name) }
                    is ConfirmationTarget.Leave -> { assertEquals("actual-note",target.note); assertEquals(10L,target.start) }
                    is ConfirmationTarget.Recording -> { assertEquals("31234",target.number); assertEquals(123L,target.startedAt); if(index == 63) assertEquals("self",target.adoption!!.name) }
                    is ConfirmationTarget.Connection -> { assertEquals(AiSlot.ANALYSIS,target.slot); assertEquals("actual-model",target.connection.model) }
                    null -> fail("Missing authoritative target for $index")
                }
                assertTrue(foreground.beginLaunch(ticket.requestId,host))
                if(changed) f.backend.settings.saveScoreSettings(ScoreSettings(7,2,10,30))
                foreground.deliver(ticket.requestId,ForegroundHostResult(if(changed) ForegroundResultStatus.VERIFIED else ForegroundResultStatus.CANCELLED))
                val receipt = withTimeout(3000) { result.await() }
                assertEquals(if(changed) ToolStatus.FAILED else ToolStatus.CANCELLED,receipt.status)
                assertEquals(0,effects)
                if(!changed) assertEquals(before,f.coordinator.capture())
                assertEquals("old",f.state.profiles.getValue("31234").name)
            }
        }
    }
}
