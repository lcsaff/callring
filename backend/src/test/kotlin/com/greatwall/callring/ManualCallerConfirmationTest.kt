package com.greatwall.callring

import com.greatwall.callring.application.callers.CallerEnrichmentService
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.domain.model.ProfileRecord
import org.junit.Assert.*
import org.junit.Test

class ManualCallerConfirmationTest {
    private val core = MemoryCoreUnitOfWork()
    private val coordinator = BusinessRevisionCoordinator()
    private val service = owner()
    private val archive = CallArchive(
        -3, "31234", 100, generation = 8, status = ArchiveStatus.COMPLETE,
        transcript = "I'm Ada from Engineering.",
        analysis = CallAnalysis(name = "Ada", department = "Engineering", region = "West",
            fieldEvidence = mapOf("name" to "I'm Ada", "department" to "from Engineering", "region" to "from West")),
        analysisIntent = ArchiveAnalysisIntent.MANUAL_TEXT,
        manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION,
        manualAnalysisResultVersion = 8,
    )

    @Test fun readIsSideEffectFreeAndOnlySelectedEvidenceIsStored() {
        val preview = checkNotNull(service.suggestions(archive))
        assertEquals(setOf(CallerSuggestionField.NAME, CallerSuggestionField.DEPARTMENT), preview.fields.map { it.field }.toSet())
        assertTrue(core.people.isEmpty())

        assertEquals(CallerSuggestionConfirmation.Applied, service.confirmWithoutCancellation(archive, selectName(preview)))
        val caller = core.people.getValue("31234")
        assertEquals("Ada", caller.name)
        assertEquals("", caller.department)
        assertEquals(setOf("name"), caller.identityMetadata.keys)
        val source = caller.identityMetadata.getValue("name")
        assertEquals("I'm Ada", source.evidence)
        assertEquals(-3L, source.archiveId)
        assertEquals(8L, source.analysisGeneration)
    }

    @Test fun receiptSurvivesRestartWhenArchiveStillShowsPending() {
        val preview = checkNotNull(service.suggestions(archive))
        val command = selectName(preview)
        assertEquals(CallerSuggestionConfirmation.Applied, service.confirmWithoutCancellation(archive, command))

        val restarted = owner()
        val saved = core.people.getValue("31234")
        assertTrue(restarted.hasConfirmation(archive))
        assertNull(restarted.suggestions(archive))
        assertEquals(CallerSuggestionConfirmation.Applied, restarted.confirmWithoutCancellation(archive, command))
        assertEquals(CallerSuggestionConfirmation.Conflict(null),
            restarted.confirmWithoutCancellation(archive, command.copy(fields = setOf(CallerSuggestionField.DEPARTMENT))))
        assertEquals(saved, core.people.getValue("31234"))
    }

    @Test fun interveningProfileEditReturnsLatestAndOldResultCannotBeConfirmed() {
        core.people["31234"] = ProfileRecord(number = "31234", name = "Previous")
        val preview = checkNotNull(service.suggestions(archive))
        core.people["31234"] = core.people.getValue("31234").copy(name = "Edited")
        val conflict = service.confirmWithoutCancellation(archive, selectName(preview)) as CallerSuggestionConfirmation.Conflict
        assertEquals("Edited", conflict.latest?.fields?.first { it.field == CallerSuggestionField.NAME }?.currentValue)
        assertEquals("Edited", core.people.getValue("31234").name)
        assertTrue(service.confirmWithoutCancellation(archive.copy(generation = 9, manualAnalysisResultVersion = 9),
            selectName(checkNotNull(conflict.latest))) is CallerSuggestionConfirmation.Conflict)
    }

    @Test fun deletedNumberCannotBeRecreatedFromRetainedHistoricalRecording() {
        val preview = checkNotNull(service.suggestions(archive))
        core.deletedCallers["31234"] = 110
        assertEquals(CallerSuggestionConfirmation.Conflict(null), service.confirmWithoutCancellation(archive, selectName(preview)))
        assertTrue(core.people.isEmpty())
    }

    @Test fun cancellationAtCoreWriteCheckpointLeavesProfileAndReceiptUntouched() {
        val preview = checkNotNull(service.suggestions(archive))
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            service.confirm(archive, selectName(preview)) {
                if (++checks == 2) throw java.util.concurrent.CancellationException()
            }
        }
        assertTrue(core.people.isEmpty())
        assertFalse(service.hasConfirmation(archive))
    }

    @Test fun failedCoreCommitRollsBackBothProfileAndReceipt() {
        val preview = checkNotNull(service.suggestions(archive))
        core.failCommit = true
        assertThrows(IllegalStateException::class.java) { service.confirmWithoutCancellation(archive, selectName(preview)) }
        assertTrue(core.people.isEmpty())
        assertFalse(service.hasConfirmation(archive))
    }

    private fun CallerEnrichmentService.confirmWithoutCancellation(archive: CallArchive, command: ConfirmCallerSuggestions) =
        confirm(archive, command) {}

    private fun owner() = CallerEnrichmentService(core, coordinator) { true }

    private fun selectName(preview: CallerSuggestions) =
        ConfirmCallerSuggestions(archive.callId, preview.token, setOf(CallerSuggestionField.NAME))
}
