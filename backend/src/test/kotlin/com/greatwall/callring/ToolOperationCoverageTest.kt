package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolOperationCoverageTest {
    @Test fun all72DefinitionsHaveSourceOrWriteScopeAndReceiptPolicy() {
        val registry=ToolRegistry();val contracts=registry.operationContracts()
        assertEquals(registry.definitions.map{it.index},contracts.map{it.index})
        assertEquals(72,contracts.size)
        assertTrue(contracts.all{it.sharedApi.isNotBlank()})
        assertEquals(ReceiptPolicy.QUEUED,contracts.single{it.index==46}.receiptPolicy)
        assertEquals(ReceiptPolicy.PERSISTED_READBACK,contracts.single{it.index==37}.receiptPolicy)
    }
    @Test fun patchUsesActualProfileStorageAndPreservesPositionAndWhitelist() = runBlocking {
        val fixture = ConversationFixtures()
        val position = (fixture.backend.settings.savePosition(SavePosition(name = "Position", level = 1, active = true)) as ApiResult.Success).value
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234", "old", "department", position.id, true))
        val foreground = ForegroundActionService().also { it.attachHost() }
        val executor = ToolExecutor({ fixture.backend }, fixture.coordinator, foreground, ForegroundResourceRegistry())
        val scope = ToolExecutionContext("s", "t", 1, CommandContext("op", fixture.coordinator.capture(), 1) { true })
        val receipt = executor.execute(ConversationToolCall("call", "callers_saveProfile", aiObject("number" to aiString("31234"), "name" to aiString("new"))), scope)
        assertEquals(ToolStatus.COMMITTED, receipt.status)
        assertEquals("new", fixture.state.profiles.getValue("31234").name)
        assertEquals(position.id, fixture.state.profiles.getValue("31234").positionId)
        assertTrue(fixture.state.profiles.getValue("31234").whitelisted)
    }
    @Test fun originalInUseValidationIsPreservedAndStaleCommandsCannotSave() = runBlocking {
        val fixture = ConversationFixtures()
        val foreground = ForegroundActionService().also { it.attachHost() }
        val executor = ToolExecutor({ fixture.backend }, fixture.coordinator, foreground, ForegroundResourceRegistry())
        val old = fixture.coordinator.capture()
        fixture.backend.settings.setQuietMode(QuietMode.NON_WORK)
        val receipt = executor.execute(ConversationToolCall("call", "settings_setQuietEnabled", aiObject("enabled" to aiBool(false))), ToolExecutionContext("s", "t", 1, CommandContext("op", old, 1) { true }))
        assertEquals(ToolStatus.FAILED, receipt.status)
        assertEquals(QuietMode.NON_WORK, fixture.state.quiet.mode)
    }
}
