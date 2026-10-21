package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.ToolExecutionContext
import com.greatwall.callring.application.conversation.execution.ToolExecutor
import com.greatwall.callring.application.conversation.execution.s
import com.greatwall.callring.application.conversation.foreground.ForegroundActionService
import com.greatwall.callring.application.conversation.foreground.ForegroundCommandValidator
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.testing.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConfirmationTargetTest {
    @Test fun fiveConfirmationsUseRealTargetsAndRejectCancelChangedOrMissingTargets() = runBlocking {
        for(operation in listOf(OperationId.SETTINGS_DELETE_POSITION,OperationId.SETTINGS_DELETE_LEAVE,OperationId.RECORDINGS_TEST_CONNECTION,OperationId.RECORDINGS_DELETE,OperationId.RECORDINGS_ADOPT_IDENTITY)) {
            val f = ConversationFixtures()
            f.backend.settings.savePosition(SavePosition(name = "actual-role",level = 2,active = true))
            f.backend.settings.saveLeave(SaveLeave(period = LeavePeriod.Timed(10,20),note = "actual-note"))
            f.backend.callers.saveProfile(SaveCallerProfile("31234","old","old-org",null,false))
            var effects = 0
            val archive = CallArchive(1,"31234",123,generation = 7,analysis = CallAnalysis(name = "self",organization = "unit"))
            val recording = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getArchive(callId: Long) = ApiResult.Success(archive.takeIf { callId == 1L })
                override suspend fun delete(callId: Long): ApiResult<Unit> { effects++; return ApiResult.Success(Unit) }
            }
            val safe = AiConnectionView(AiProtocol.OPENAI,"https://actual.example/v1","actual-model",true)
            val settings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getAiView() = ApiResult.Success(AiSettingsView(safe,safe,ConversationBudget(),0))
                override suspend fun testSlot(slot: AiSlot): ApiResult<Unit> { effects++; return ApiResult.Success(Unit) }
            }
            fun root(context: CommandContext? = null): CallringUiApi = object : CallringUiApi by (context?.let(f.backend::commandApi) ?: f.backend) {
                override val recordings = recording
                override val aiSettings = settings
                override fun commandApi(context: CommandContext) = root(context)
                override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long) = com.greatwall.callring.application.recording.IdentityAdoptionService(recordings::getArchive, { callers.getEditor(it) }, { base, value, cmd -> f.backend.commandApi(cmd).edits.profile(base, value) }, f.coordinator, context, active = { true }).adoptIdentity(callId,expectedGeneration)
            }
            val foreground = ForegroundActionService(checkCommand = ForegroundCommandValidator(f.coordinator)::check)
            val host = foreground.attachHost()
            val executor = ToolExecutor({ root() },f.coordinator,foreground,ForegroundResourceRegistry(), queries=unusedToolQueries(), documentWriteVerified={false})
            val definition = executor.registry.definitions.single { it.id == operation }
            fun call(id: Long = 1) = ConversationToolCall("call",definition.name,when(operation) {
                OperationId.SETTINGS_DELETE_POSITION,OperationId.SETTINGS_DELETE_LEAVE -> aiObject("id" to aiNumber(id))
                OperationId.RECORDINGS_TEST_CONNECTION -> aiObject("slot" to aiString(if(id == 1L) "ANALYSIS" else "INVALID"))
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
                    is ConfirmationTarget.Recording -> { assertEquals("31234",target.number); assertEquals(123L,target.startedAt); if(operation == OperationId.RECORDINGS_ADOPT_IDENTITY) assertEquals("self",target.adoption!!.name) }
                    is ConfirmationTarget.Connection -> { assertEquals(AiSlot.ANALYSIS,target.slot); assertEquals("actual-model",target.connection.model) }
                    is ConfirmationTarget.NumberDeletion -> fail("Number deletion is not part of the legacy five confirmations")
                    null -> fail("Missing authoritative target for $operation")
                }
                assertTrue(foreground.beginLaunch(ticket.requestId,host))
                if(changed) f.backend.settings.saveScoreSettings(ScoreSettings(7,2,10,30))
                foreground.deliver(ticket.requestId,ForegroundHostResult(if(changed) ForegroundResultStatus.VERIFIED else ForegroundResultStatus.CANCELLED))
                val receipt = withTimeout(3000) { result.await() }
                assertEquals(if(changed) ToolStatus.FAILED else ToolStatus.CANCELLED,receipt.status)
                assertEquals(0,effects)
                if(!changed) assertEquals(before,f.coordinator.capture())
                assertEquals("old",f.core.people.getValue("31234").name)
            }
        }
    }
}
