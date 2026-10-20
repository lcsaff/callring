package com.greatwall.callring

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopedRevisionTest {
    @Test fun preparedProfilePatchMergesUntouchedFieldsAtActualCommit()=runBlocking {
        val f=ConversationFixtures();val base=CallerDraft("31234","old","old-unit",null,false)
        f.backend.callers.saveProfile(SaveCallerProfile(base.number,base.name,base.department,null,false))
        val command=CommandContext("patch",f.coordinator.capture(),1,expectedScopes=f.coordinator.expect(setOf("profile:31234:name"))){true}
        f.backend.callers.saveProfile(SaveCallerProfile(base.number,base.name,"concurrent-unit",null,false))
        val result=f.backend.commandApi(command).callers.saveProfile(SaveCallerProfile(base.number,"new",base.department,null,false,baseline=base,dirtyFields=setOf("name"))).requiredValue()
        assertEquals("new",result.name);assertEquals("concurrent-unit",result.department)
    }
    @Test fun unrelatedCallsAndAppearanceFieldsDoNotRejectColorModeButSameFieldDoes()=runBlocking {
        val f=ConversationFixtures();val before=f.appearance
        val command=CommandContext("color",f.coordinator.capture(),1,expectedScopes=f.coordinator.expect(setOf("appearance:colorMode"))){true}
        f.backend.settings.setStatisticsEnabled(true);f.backend.recordIncoming("31234",f.now,CallDecision.NORMAL_RINGING)
        f.backend.settings.saveAppearance(before.copy(presetId="other"))
        assertTrue(f.backend.commandApi(command).edits.appearance(before,before.copy(colorMode=ColorMode.DARK)) is ApiResult.Success)
        assertEquals("other",f.appearance.presetId);assertEquals(ColorMode.DARK,f.appearance.colorMode)
        val old=f.appearance
        val stale=CommandContext("stale",f.coordinator.capture(),1,expectedScopes=f.coordinator.expect(setOf("appearance:colorMode"))){true}
        f.backend.settings.saveAppearance(old.copy(colorMode=ColorMode.LIGHT))
        assertTrue(f.backend.commandApi(stale).edits.appearance(old,old.copy(colorMode=ColorMode.FOLLOW_SYSTEM)) is ApiResult.Failure)
    }
    @Test fun anotherProfileDoesNotConflictButReferencedPositionDoes()=runBlocking {
        val f=ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234","old","",null,false))
        val command=CommandContext("profile",f.coordinator.capture(),1,expectedScopes=f.coordinator.expect(setOf("profile:31234:name"))){true}
        f.backend.callers.saveProfile(SaveCallerProfile("35678","unrelated","",null,false))
        assertTrue(f.backend.commandApi(command).callers.saveProfile(SaveCallerProfile("31234","new","",null,false)) is ApiResult.Success)
        val stale=CommandContext("position",f.coordinator.capture(),1,expectedScopes=f.coordinator.expect(setOf("position:1:valid"))){true}
        f.backend.settings.savePosition(SavePosition(null,"合成职位",1,true))
        assertTrue(runCatching{f.coordinator.guard(stale){}}.isFailure)
    }
}
