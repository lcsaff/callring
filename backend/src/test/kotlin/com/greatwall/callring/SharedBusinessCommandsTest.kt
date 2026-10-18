package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
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
            override val recordings = object : RecordingApi by UnavailableRecordingApi {
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> = ApiResult.Success(CallArchive(callId,"31234",1,generation = 7,analysis = CallAnalysis(organization = "new unit",position = "CEO",inferredIdentity = "not a self-report")))
            }
        }
        val service = SharedBusinessCommands(api)
        assertTrue(service.adoptIdentity(1,8) is ApiResult.Failure)
        assertEquals("old unit",fixture.state.profiles.getValue("31234").department)
        service.adoptIdentity(1,7).requiredValue()
        val saved = fixture.state.profiles.getValue("31234")
        assertEquals("old name",saved.name); assertEquals("new unit",saved.department)
        assertEquals(position.id,saved.positionId); assertTrue(saved.whitelisted)
    }
}
