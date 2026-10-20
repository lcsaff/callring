package com.greatwall.callring

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolOperationCoverageTest {
    @Test fun all81DefinitionsHaveSourceOrWriteScopeAndReceiptPolicy() {
        val definitions=ToolRegistry().definitions
        assertEquals("Every discoverable operation requires a production definition",81,definitions.size)
        assertTrue(definitions.all{it.sharedApi.isNotBlank()})
        assertEquals("aiSettings.getRecordingView + aiSettings.getAiView", definitions.single{it.id==OperationId.RECORDINGS_GET_SETTINGS}.sharedApi)
        assertEquals(ReceiptPolicy.QUEUED,definitions.single{it.id==OperationId.RUNTIME_REQUEST_CALL_LOG_SYNC}.receiptPolicy)
        assertEquals(ReceiptPolicy.PERSISTED_READBACK,definitions.single{it.id==OperationId.SETTINGS_SAVE_APPEARANCE}.receiptPolicy)
    }
    @Test fun patchUsesActualProfileStorageAndPreservesPositionAndWhitelist() = runBlocking {
        val fixture = ConversationFixtures()
        val position = (fixture.backend.settings.savePosition(SavePosition(name = "Position", level = 1, active = true)) as ApiResult.Success).value
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234", "old", "department", position.id, true))
        val foreground = ForegroundActionService(checkCommand = { _, _ -> true }).also { it.attachHost() }
        val executor = ToolExecutor({ fixture.backend }, fixture.coordinator, foreground, ForegroundResourceRegistry(), queries=unusedToolQueries(), documentWriteVerified={false})
        val scope = ToolExecutionContext("s", "t", 1, CommandContext("op", fixture.coordinator.capture(), 1) { true })
        val receipt = executor.execute(ConversationToolCall("call", "callers_saveProfile", aiObject("number" to aiString("31234"), "name" to aiString("new"))), scope)
        assertEquals(ToolStatus.COMMITTED, receipt.status)
        assertEquals("new", fixture.state.profiles.getValue("31234").name)
        assertEquals(position.id, fixture.state.profiles.getValue("31234").positionId)
        assertTrue(fixture.state.profiles.getValue("31234").whitelisted)
    }
    @Test fun originalInUseValidationIsPreservedAndStaleCommandsCannotSave() = runBlocking {
        val fixture = ConversationFixtures()
        val foreground = ForegroundActionService(checkCommand = { _, _ -> true }).also { it.attachHost() }
        val executor = ToolExecutor({ fixture.backend }, fixture.coordinator, foreground, ForegroundResourceRegistry(), queries=unusedToolQueries(), documentWriteVerified={false})
        val old = fixture.coordinator.capture()
        fixture.backend.settings.setQuietMode(QuietMode.NON_WORK)
        val receipt = executor.execute(ConversationToolCall("call", "settings_setQuietEnabled", aiObject("enabled" to aiBool(false))), ToolExecutionContext("s", "t", 1, CommandContext("op", old, 1) { true }))
        assertEquals(ToolStatus.FAILED, receipt.status)
        assertEquals(com.greatwall.callring.domain.model.QuietMode.NON_WORK, fixture.state.quiet.mode)
    }
}
