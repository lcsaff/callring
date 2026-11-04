package com.greatwall.callring

import com.greatwall.callring.application.ports.recording.NumberRecordingMetadata

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue

import com.greatwall.callring.application.*
import com.greatwall.callring.application.callers.*
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NumberDeletionServiceTest {
    private val recordings = object : NumberDeletionRecordingReads {
        override suspend fun archives(number: String) = ApiResult.Success(listOf(
            NumberRecordingMetadata(-1,2,ArchiveStatus.TRANSCRIBING,73,8)))
    }
    @Test fun busyRecordingsAreInformationalAndNeverDeletedOrBlockNumberDeletion() = runBlocking {
        val f=ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234","delete","unit",null)).requiredValue()
        val service=NumberDeletionService(f.backend.callers::numberDataImpact,
            { n,count,cmd -> f.backend.commandApi(cmd).callers.deleteNumberCore(n,count) },recordings,
            { ApiResult.Success(emptyList<WidgetBinding>()) },{ _,_ -> error("no bindings") },f.coordinator,active={true})
        val impact=service.impact("31234").requiredValue()
        assertEquals(1,impact.archiveCount); assertEquals(73L,impact.audioBytes); assertFalse(impact.busy)
        // An old archive count is not an authorization or conflict condition for retained data.
        service.delete(DeleteNumberCommand("31234",0,999,0)).requiredValue()
        assertFalse(f.core.people.containsKey("31234"))
    }
    @Test fun recordingChangesDoNotInvalidateNumberDeletionButProfileChangesDo() = runBlocking {
        for (recordingOnly in listOf(true,false)) {
            val f=ConversationFixtures()
            f.backend.callers.saveProfile(SaveCallerProfile("31234","keep","unit",null)).requiredValue()
            var reads=0
            val service=NumberDeletionService(f.backend.callers::numberDataImpact,
                { n,count,cmd -> f.backend.commandApi(cmd).callers.deleteNumberCore(n,count) },recordings,
                { if (++reads==1) {
                    if(recordingOnly) f.coordinator.publishResource("recording",setOf("archive:-1"),ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
                    else f.backend.callers.saveProfile(SaveCallerProfile("31234","edited elsewhere","unit",null)).requiredValue()
                  }; ApiResult.Success(emptyList<WidgetBinding>()) },{ _,_ -> error("no bindings") },f.coordinator,active={true})
            val result=service.delete(DeleteNumberCommand("31234",0,1,0))
            if(recordingOnly) { assertTrue(result is ApiResult.Success); assertFalse(f.core.people.containsKey("31234")) }
            else { assertTrue(result is ApiResult.Failure); assertEquals("edited elsewhere",f.core.people.getValue("31234").name) }
        }
    }
    @Test fun callAndBindingCountsRemainProtectedAndArchiveOnlyIsNotNumberDeletionTarget() = runBlocking {
        var writes=0
        val coordinator=BusinessRevisionCoordinator()
        val service=NumberDeletionService({ApiResult.Success(NumberCoreImpact(it,true,2))},
            {_,_,_ -> writes++; ApiResult.Success(Unit)},recordings,
            {ApiResult.Success(listOf(WidgetBinding(1,it)))},{_,_->writes++;ApiResult.Success(Unit)},coordinator,active={true})
        for (expected in listOf(DeleteNumberCommand("31234",1,1,1),DeleteNumberCommand("31234",2,1,0)))
            assertEquals("number.delete_conflict",(service.delete(expected) as ApiResult.Failure).error.message)
        assertEquals(0,writes)
        val missing=NumberDeletionService({ApiResult.Success(NumberCoreImpact(it,false,0))},{_,_,_->error("no core")},recordings,
            {ApiResult.Success(emptyList<WidgetBinding>())},{_,_->error("no bindings")},coordinator,active={true})
        assertEquals("number.not_found",(missing.delete(DeleteNumberCommand("31234",0,1,0)) as ApiResult.Failure).error.message)
    }
}
