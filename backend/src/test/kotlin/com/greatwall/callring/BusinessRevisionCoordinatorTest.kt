package com.greatwall.callring

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.change.*
import org.junit.Assert.*
import org.junit.Test

class BusinessRevisionCoordinatorTest {
    @Test fun compositeCommandAcceptsOwnCommitsButRejectsLaterForeignChangesAndCancellation() {
        for (scoped in listOf(false, true)) {
            val coordinator = BusinessRevisionCoordinator()
            var active = true
            val command = CommandContext("sequence", coordinator.capture(), 1,
                expectedScopes = if (scoped) coordinator.expect(setOf("profile:31234:exists")) else null,
                continueOwnCommits = true) { active }
            coordinator.guard(command) { coordinator.publishCore(setOf("profile:31234:exists"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap())); coordinator.record(command) }
            coordinator.guard(command) { coordinator.publishResource("preferences", setOf("appearance"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap())); coordinator.record(command) }
            active = false
            assertTrue(runCatching { coordinator.guard(command) { } }.isFailure)
            active = true
            coordinator.guard { coordinator.publishCore(setOf("profile:31234:exists"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap())); coordinator.publishResource("core", setOf("core"), com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(com.greatwall.callring.contracts.change.DataDomain.CALLERS))) }
            assertTrue(runCatching { coordinator.guard(command) { } }.isFailure)
        }
    }

    @Test fun ordinaryCommandsCannotReuseTheirOwnStaleBaseline() {
        val coordinator = BusinessRevisionCoordinator()
        val command = CommandContext("ordinary", coordinator.capture(), 1) { true }
        coordinator.guard(command) { coordinator.publishCore(setOf("core"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap())); coordinator.record(command) }
        assertTrue(runCatching { coordinator.guard(command) { coordinator.publishCore(setOf("core"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap())); coordinator.record(command) } }.isFailure)
    }
    @Test fun failedCommitDoesNotAdvanceAndStaleCommandNeverWrites() {
        val coordinator = BusinessRevisionCoordinator()
        val before = coordinator.capture()
        runCatching { coordinator.guard { error("rollback") } }
        assertEquals(before, coordinator.capture())
        coordinator.publishCore(setOf("core"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        var wrote = false
        val stale = CommandContext("op", before, 1) { true }
        assertTrue(runCatching { coordinator.guard(stale) { wrote = true } }.isFailure)
        assertFalse(wrote)
    }
    @Test fun stoppedGenerationCannotCommitAndPublicationHasOnlyActualDomain() {
        val coordinator = BusinessRevisionCoordinator()
        val stopped = CommandContext("op", coordinator.capture(), 1) { false }
        assertTrue(runCatching { coordinator.guard(stopped) { error("must not execute") } }.isFailure)
        coordinator.publishResource("recording", setOf("archive:1"), com.greatwall.callring.application.coordination.ChangeImpact.wide(setOf(DataDomain.RECORDING_ARCHIVES)))
        assertEquals(setOf(DataDomain.RECORDING_ARCHIVES), coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys })
        assertEquals(1L, coordinator.capture().values["recording"])
    }
}
