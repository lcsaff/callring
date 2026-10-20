package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.RecordingCall
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.recording.CallArchive
import org.junit.Assert.*
import org.junit.Test

class RecordingRegistrationServiceTest {
    private class Data : RecordingRegistrationStore {
        var call: RecordingCall? = null
        var archive: CallArchive? = null
        var link: Long? = null
        var writes = 0
        var fail = false
        override fun insertIncoming(sessionId: String, number: String, at: Long, account: String?, initial: (RecordingCall) -> CallArchive): RecordingRegistration {
            if (fail) error("storage failed")
            val previous = call
            val next = previous ?: RecordingCall(-1, number, at, null)
            val initialArchive = if (archive == null) initial(next) else null
            call = next
            if (initialArchive != null) archive = initialArchive
            writes++
            return RecordingRegistration(next, previous == null, initialArchive != null)
        }
        override fun sessionRecording(sessionId: String) = call
        override fun insertStatisticsLink(statisticsId: Long, sessionId: String): Boolean {
            if (fail) error("storage failed")
            if (link != null) return false
            link = statisticsId; writes++; return true
        }
    }

    @Test fun oneNarrowResourceSupportsRegistrationLinksNoopsAndCommandContinuation() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val service = RecordingRegistrationService(data, coordinator) { 10 }
        val command = CommandContext("continued", coordinator.capture(), 0, continueOwnCommits = true) { true }
        service.linkStatistics("absent", 42, command)
        assertEquals(0L, coordinator.marker())
        val call = service.registerIncoming("one", "31234", 1, null, command)
        assertEquals(10000L, data.archive!!.generation)
        assertEquals(1L, coordinator.marker())
        assertEquals(1L, coordinator.lastChange(setOf("recordingEvents")))
        assertEquals(1L, coordinator.lastChange(setOf("archive:${call.id}")))
        service.registerIncoming("one", "31234", 1, null, command)
        assertEquals(1L, coordinator.marker())
        service.linkStatistics("one", 42, command)
        assertEquals(2L, coordinator.marker())
        assertEquals(2L, coordinator.lastChange(setOf("recordingLinks")))
        assertEquals(2L, coordinator.lastChange(setOf("recordingLink:42")))
        service.linkStatistics("one", 42, command)
        assertEquals(2L, coordinator.marker())
        assertEquals(coordinator.capture(), coordinator.committed("continued"))
        assertEquals(0L, coordinator.capture().values.getValue("core"))
    }

    @Test fun failedInvalidCancelledAndUnauthorizedCommandsNeverPublish() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val service = RecordingRegistrationService(data, coordinator)
        val command = CommandContext("failed", coordinator.capture(), 0) { true }
        data.fail = true
        assertTrue(runCatching { service.registerIncoming("one", "31234", 1, null, command) }.isFailure)
        data.fail = false
        assertTrue(runCatching { service.registerIncoming("", "31234", 1, null, command) }.isFailure)
        assertTrue(runCatching { service.registerIncoming("one", "31234", 1, null, command.copy(active = { false })) }.isFailure)
        assertTrue(runCatching { service.registerIncoming("one", "31234", 1, null, command.copy(mayCommit = { false })) }.isFailure)
        assertEquals(0, data.writes)
        assertEquals(0L, coordinator.marker())
        assertNull(coordinator.committed("failed"))
    }
}
