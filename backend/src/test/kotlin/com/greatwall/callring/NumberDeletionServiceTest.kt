package com.greatwall.callring

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
    @Test fun serviceProtectsNewArchiveEvenWhenCallerSuppliedOnlyProfileScope() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
        val command = CommandContext("delete", f.coordinator.capture(), 0,
            expectedScopes = f.coordinator.expect(setOf("profile:31234"))) { true }
        var coreDeletes = 0
        val recordings = object : NumberDeletionRecordings {
            override suspend fun archives(number: String) = ApiResult.Success(listOf(NumberRecordingMetadata(-1, 2, ArchiveStatus.READY, 10, 1)))
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit> {
                f.coordinator.guard(command) {
                    f.coordinator.publishResource("recording", setOf("recording:-1"), ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
                    f.coordinator.record(command)
                }
                // Another command registers a replacement member after our final delete fence.
                f.coordinator.publishResource("recording", setOf("recording:-2"), ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
                return ApiResult.Success(Unit)
            }
        }
        val service = NumberDeletionService(f.backend.callers::numberDataImpact,
            { n, count, cmd -> coreDeletes++; f.backend.commandApi(cmd).callers.deleteNumberCore(n, count) }, recordings,
            { ApiResult.Success(emptyList<WidgetBinding>()) }, { _, _ -> error("no bindings") }, f.coordinator, command, active = { true })
        assertTrue(service.delete(DeleteNumberCommand("31234", 0, 1, 0)) is ApiResult.Failure)
        assertEquals(0, coreDeletes)
        assertEquals("keep", f.core.people.getValue("31234").name)
    }
    @Test fun unrelatedCoreCommitAfterArchiveCleanupDoesNotBlockTargetDeletion() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "delete", "unit", null)).requiredValue()
        val command = CommandContext("delete-target", f.coordinator.capture(), 0,
            expectedScopes = f.coordinator.expect(setOf("profile:31234"))) { true }
        val recordings = object : NumberDeletionRecordings {
            override suspend fun archives(number: String) = ApiResult.Success(listOf(NumberRecordingMetadata(-1, 2, ArchiveStatus.READY, 10, 1)))
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit> {
                f.coordinator.guard(command) {
                    f.coordinator.publishResource("recording", setOf("archive:-1"), ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
                    f.coordinator.record(command)
                }
                f.backend.settings.saveScoreSettings(com.greatwall.callring.contracts.settings.ScoreSettings(7, 2, 10, 30)).requiredValue()
                return ApiResult.Success(Unit)
            }
        }
        NumberDeletionService(f.backend.callers::numberDataImpact,
            { n, count, cmd -> f.backend.commandApi(cmd).callers.deleteNumberCore(n, count) }, recordings,
            { ApiResult.Success(emptyList<WidgetBinding>()) }, { _, _ -> error("no bindings") }, f.coordinator, command, active = { true })
            .delete(DeleteNumberCommand("31234", 0, 1, 0)).requiredValue()
        assertFalse(f.core.people.containsKey("31234"))
    }
    @Test fun previewPreservesAllCountsAndRejectsEachBusyStateAndExpectedCountConflict() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        var status = ArchiveStatus.COMPLETE
        var writes = 0
        val recordings = object : NumberDeletionRecordings {
            override suspend fun archives(number: String) = ApiResult.Success(listOf(NumberRecordingMetadata(-1, 2, status, 73, 8)))
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit> { writes++; return ApiResult.Success(Unit) }
        }
        val service = NumberDeletionService({ ApiResult.Success(NumberCoreImpact(it, true, 2)) },
            { _, _, _ -> writes++; ApiResult.Success(Unit) }, recordings,
            { ApiResult.Success(listOf(WidgetBinding(1, it))) }, { _, _ -> writes++; ApiResult.Success(Unit) }, coordinator, active = { true })
        val impact = service.impact("31234").requiredValue()
        assertTrue(impact.profileExists); assertEquals(2, impact.callCount); assertEquals(1, impact.archiveCount)
        assertEquals(73L, impact.audioBytes); assertEquals(1, impact.widgetBindingCount); assertFalse(impact.busy)
        for (expected in listOf(DeleteNumberCommand("31234", 1, 1, 1), DeleteNumberCommand("31234", 2, 0, 1), DeleteNumberCommand("31234", 2, 1, 0))) {
            assertEquals("number.delete_conflict", (service.delete(expected) as ApiResult.Failure).error.message)
        }
        for (busy in listOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING)) {
            status = busy
            assertTrue(service.impact("31234").requiredValue().busy)
            assertEquals("number.delete_busy", (service.delete(DeleteNumberCommand("31234", 2, 1, 1)) as ApiResult.Failure).error.message)
        }
        assertEquals(0, writes)
        val missing = NumberDeletionService({ ApiResult.Success(NumberCoreImpact(it, false, 0)) }, { _, _, _ -> error("no core") },
            EmptyNumberRecordings, { ApiResult.Success(emptyList<WidgetBinding>()) }, { _, _ -> error("no widgets") }, coordinator, active = { true })
        assertEquals("number.not_found", (missing.delete(DeleteNumberCommand("31234", 0, 0, 0)) as ApiResult.Failure).error.message)
    }
    @Test fun failureStopsCoreAndBindingsAndRetryOnlyProcessesRemainingArchivesInStableOrder() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        var profile = true
        var fail = true
        val archives = mutableListOf(NumberRecordingMetadata(-3, 1, ArchiveStatus.READY, 0, 1),
            NumberRecordingMetadata(-1, 1, ArchiveStatus.READY, 0, 3), NumberRecordingMetadata(-2, 1, ArchiveStatus.READY, 0, 3))
        val bindings = mutableListOf(WidgetBinding(1, "31234"))
        val visited = mutableListOf<String>()
        fun committed(resource: String, command: CommandContext, mutation: () -> Unit) = coordinator.guard(command) {
            mutation()
            coordinator.publishResource(resource, setOf(resource), ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
            coordinator.record(command)
        }
        val recordings = object : NumberDeletionRecordings {
            override suspend fun archives(number: String) = ApiResult.Success(archives.toList())
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit> {
                visited += "archive:$callId"
                if (fail && callId == -1L) return ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "fixture.archive"))
                committed("recording", command) { archives.removeAll { it.callId == callId } }
                return ApiResult.Success(Unit)
            }
        }
        val service = NumberDeletionService({ ApiResult.Success(NumberCoreImpact(it, profile, 0)) },
            { _, _, cmd -> visited += "core"; committed("core", cmd) { profile = false }; ApiResult.Success(Unit) }, recordings,
            { ApiResult.Success(bindings.toList()) },
            { binding, cmd -> visited += "widget:${binding.widgetId}"; committed("preferences", cmd) { bindings.remove(binding) }; ApiResult.Success(Unit) }, coordinator, active = { true })
        assertTrue(service.delete(DeleteNumberCommand("31234", 0, 3, 1)) is ApiResult.Failure)
        assertEquals(listOf("archive:-2", "archive:-1"), visited)
        assertTrue(profile); assertEquals(1, bindings.size); assertEquals(listOf(-3L, -1L), archives.map { it.callId })
        fail = false; visited.clear()
        service.delete(DeleteNumberCommand("31234", 0, 2, 1)).requiredValue()
        assertEquals(listOf("archive:-1", "archive:-3", "core", "widget:1"), visited)
        assertFalse(profile); assertTrue(archives.isEmpty()); assertTrue(bindings.isEmpty())
    }
}
