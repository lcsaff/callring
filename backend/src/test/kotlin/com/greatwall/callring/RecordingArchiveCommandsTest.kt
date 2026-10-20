package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.recording.CallArchive
import org.junit.Assert.*
import org.junit.Test

class RecordingArchiveCommandsTest {
    private class Data : RecordingArchiveState, RecordingArchiveMutations {
        var value: CallArchive? = null
        var deleted = false
        var gets = 0
        var writes = 0
        var fail = false
        var reject = false
        var afterWrite: () -> Unit = {}
        override fun get(callId: Long) = value.also { gets++ }
        override fun generation(callId: Long) = value?.generation
        override fun isDeleted(callId: Long) = deleted
        override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
            if (fail) error("storage failed")
            if (reject || this.value?.generation != expectedGeneration) return false
            this.value = value; deleted = false; writes++; afterWrite(); return true
        }
        override fun delete(callId: Long): CallArchive? {
            if (fail) error("storage failed")
            return value.also { value = null; deleted = true; writes++ }
        }
    }

    @Test fun completedPersistencePublishesAndRecordsEvenIfCommandBecomesInactive() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val commands = RecordingArchiveCommands(data, data, coordinator)
        var active = true
        val command = CommandContext("persisted", coordinator.capture(), 0) { active }
        data.afterWrite = { active = false }
        assertTrue(commands.compareAndSet(-1, null, CallArchive(-1, "31234", 1, 1), command, false))
        assertEquals(1L, coordinator.marker())
        assertEquals(coordinator.capture(), coordinator.committed("persisted"))
    }

    @Test fun generationChangesDoNotDecodeTheTargetPayloadAgain() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val commands = RecordingArchiveCommands(data, data, coordinator)
        val initial = CallArchive(-1, "31234", 1, 10, transcript = "large".repeat(10000))
        assertTrue(commands.compareAndSet(-1, null, initial, null, false))
        assertTrue(commands.compareAndSet(-1, 10, initial.copy(generation = 11), null, false))
        assertEquals("Generation already proves a real change", 0, data.gets)
        assertEquals(2L, coordinator.marker())
    }

    @Test fun realDeltaPublishesOneEntityAndNoopStillRecordsCommand() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val commands = RecordingArchiveCommands(data, data, coordinator)
        val value = CallArchive(-1, "31234", 1, 10)
        assertTrue(commands.compareAndSet(-1, null, value, null, false))
        assertEquals(1L, coordinator.marker())
        assertEquals(1L, coordinator.capture().values.getValue("recording"))
        assertEquals(0L, coordinator.capture().values.getValue("core"))
        assertEquals(setOf(DataDomain.RECORDING_ARCHIVES), coordinator.changes.value.domains)
        assertEquals(setOf("-1"), coordinator.invalidations.snapshots.value.domains.getValue(DataDomain.RECORDING_ARCHIVES).entityRevisions.keys)
        assertEquals(1L, coordinator.lastChange(setOf("recording", "archive:-1")))
        val command = CommandContext("noop", coordinator.capture(), 0) { true }
        assertTrue(commands.compareAndSet(-1, 10, value, command, false))
        assertEquals(1L, coordinator.marker())
        assertEquals(1, data.writes)
        assertEquals(coordinator.capture(), coordinator.committed("noop"))
        val changedSameGeneration = value.copy(error = "actual change")
        assertTrue(commands.compareAndSet(-1, 10, changedSameGeneration, null, false))
        assertEquals(2L, coordinator.marker())
        commands.delete(-1, null, false)
        assertEquals(3L, coordinator.marker())
        commands.delete(-1, null, false)
        assertEquals(3L, coordinator.marker())
    }

    @Test fun rejectedFailedAndStaleWritesDoNotPublishOrRecord() {
        val data = Data()
        val coordinator = BusinessRevisionCoordinator()
        val commands = RecordingArchiveCommands(data, data, coordinator)
        val value = CallArchive(-1, "31234", 1, 10)
        val command = CommandContext("failed", coordinator.capture(), 0) { true }
        data.reject = true
        assertFalse(commands.compareAndSet(-1, null, value, command, false))
        data.reject = false; data.fail = true
        assertTrue(runCatching { commands.compareAndSet(-1, null, value, command, false) }.isFailure)
        assertTrue(runCatching { commands.delete(-1, command, false) }.isFailure)
        assertEquals(0L, coordinator.marker())
        assertNull(coordinator.committed("failed"))
        data.fail = false
        coordinator.changed("core")
        assertTrue(runCatching { commands.compareAndSet(-1, null, value, command, false) }.isFailure)
        assertEquals(0, data.writes)
        assertEquals(1L, coordinator.marker())
    }
}
