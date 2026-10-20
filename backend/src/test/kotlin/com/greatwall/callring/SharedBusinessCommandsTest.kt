package com.greatwall.callring

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedBusinessCommandsTest {
    @Test fun adoptionPreservesPositionWhitelistAndEmptyFieldsAndNeverAdoptsInference() = runBlocking {
        val fixture = ConversationFixtures()
        val position = fixture.backend.settings.savePosition(SavePosition(name = "Role", level = 1, active = true)).requiredValue()
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234","old name","old unit",position.id,true))
        val command = CommandContext("op",fixture.coordinator.capture(),0) { true }
        val api = object : CallringUiApi by fixture.backend.commandApi(command) {
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> = ApiResult.Success(CallArchive(callId,"31234",1,generation = 7,analysis = CallAnalysis(organization = "new unit",position = "CEO",inferredIdentity = "not a self-report")))
            }
        }
        val service = com.greatwall.callring.application.recording.IdentityAdoptionService(api.recordings::getArchive, { api.callers.getEditor(it) }, { base, value, cmd -> fixture.backend.commandApi(cmd).edits.profile(base, value) }, fixture.coordinator, active = { true })
        assertTrue(service.adoptIdentity(1,8) is ApiResult.Failure)
        assertEquals("old unit",fixture.state.profiles.getValue("31234").department)
        service.adoptIdentity(1,7).requiredValue()
        val saved = fixture.state.profiles.getValue("31234")
        assertEquals("old name",saved.name); assertEquals("new unit",saved.department)
        assertEquals(position.id,saved.positionId); assertTrue(saved.whitelisted)
    }
    @Test fun archiveReplacementAfterReadRejectsProfileCommit() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234", "original", "unit", null)).requiredValue()
        val api = object : CallringUiApi by fixture.backend {
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> = ApiResult.Success(
                    CallArchive(callId, "31234", 1, generation = 7, analysis = CallAnalysis(name = "stale")))
            }
            override val callers = object : CallersApi by fixture.backend.callers {
                override suspend fun getEditor(number: String?): ApiResult<CallerEditorData> {
                    val editor = fixture.backend.callers.getEditor(number)
                    fixture.coordinator.publishResource("recording", setOf("recording"), com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_ARCHIVES)))
                    return editor
                }
            }
        }
        assertTrue(com.greatwall.callring.application.recording.IdentityAdoptionService(api.recordings::getArchive, { api.callers.getEditor(it) }, { base, value, cmd -> fixture.backend.commandApi(cmd).edits.profile(base, value) }, fixture.coordinator, active = { true }).adoptIdentity(1, 7) is ApiResult.Failure)
        assertEquals("original", fixture.state.profiles.getValue("31234").name)
    }
    @Test fun canonicalArchiveAdoptionIgnoresUnrelatedArchiveAndDirtyMergesOtherFields() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "old", "unit", null)).requiredValue()
        val service = com.greatwall.callring.application.recording.IdentityAdoptionService(
            { ApiResult.Success(CallArchive(-1, "31234", 1, generation = 7, analysis = CallAnalysis(name = "self"))) },
            { number ->
                val editor = f.backend.callers.getEditor(number)
                f.coordinator.publishResource("recording", setOf("archive:-99"), com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_ARCHIVES)))
                f.backend.callers.setWhitelisted(number, true).requiredValue()
                editor
            },
            { base, value, cmd -> f.backend.commandApi(cmd).edits.profile(base, value) }, f.coordinator, active = { true })
        service.adoptIdentity(-1, 7).requiredValue()
        assertEquals("self", f.state.profiles.getValue("31234").name)
        assertTrue(f.state.profiles.getValue("31234").whitelisted)
    }
    @Test fun formattedArchiveProtectsNormalizedProfileBeforeEditorRead() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "original", "unit", null)).requiredValue()
        var editorReads = 0
        val service = com.greatwall.callring.application.recording.IdentityAdoptionService(
            {
                f.backend.callers.saveProfile(SaveCallerProfile("31234", "foreign", "unit", null)).requiredValue()
                ApiResult.Success(CallArchive(-1, "31-234", 1, generation = 7, analysis = CallAnalysis(name = "stale")))
            },
            { number -> editorReads++; f.backend.callers.getEditor(number) },
            { base, value, cmd -> f.backend.commandApi(cmd).edits.profile(base, value) }, f.coordinator, active = { true })
        assertTrue(service.adoptIdentity(-1, 7) is ApiResult.Failure)
        assertEquals(0, editorReads)
        assertEquals("foreign", f.state.profiles.getValue("31234").name)
    }
    @Test fun wrongGenerationNeverReadsOrWritesProfile() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        var reads = 0; var writes = 0
        val service = com.greatwall.callring.application.recording.IdentityAdoptionService(
            { ApiResult.Success(CallArchive(-1, "31234", 1, generation = 7, analysis = CallAnalysis(name = "self"))) },
            { reads++; error("generation must be checked first") },
            { _, _, _ -> writes++; error("generation must be checked first") }, coordinator, active = { true })
        assertEquals("recording.changed", (service.adoptIdentity(-1, 8) as ApiResult.Failure).error.message)
        assertEquals(0, reads); assertEquals(0, writes)
    }
    @Test fun closingOwnerAfterEditorReadRejectsFinalProfileCommit() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
        var open = true
        val service = com.greatwall.callring.application.recording.IdentityAdoptionService(
            { ApiResult.Success(CallArchive(-1, "31234", 1, generation = 7, analysis = CallAnalysis(name = "stale"))) },
            { number -> f.backend.callers.getEditor(number).also { open = false } },
            { base, value, cmd -> f.backend.commandApi(cmd).edits.profile(base, value) }, f.coordinator, active = { open })
        assertEquals("ai.cancelled", (service.adoptIdentity(-1, 7) as ApiResult.Failure).error.message)
        assertEquals("keep", f.state.profiles.getValue("31234").name)
    }
}
