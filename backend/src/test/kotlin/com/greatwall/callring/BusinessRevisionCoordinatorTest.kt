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
            coordinator.commit("core", command) { coordinator.publishScopes(setOf("profile:31234:exists")) }
            coordinator.commit("preferences", command) { }
            active = false
            assertTrue(runCatching { coordinator.guard(command) { } }.isFailure)
            active = true
            coordinator.guard { coordinator.publishScopes(setOf("profile:31234:exists")); coordinator.changed("core") }
            assertTrue(runCatching { coordinator.guard(command) { } }.isFailure)
        }
    }

    @Test fun ordinaryCommandsCannotReuseTheirOwnStaleBaseline() {
        val coordinator = BusinessRevisionCoordinator()
        val command = CommandContext("ordinary", coordinator.capture(), 1) { true }
        coordinator.commit("core", command) { }
        assertTrue(runCatching { coordinator.commit("core", command) { } }.isFailure)
    }
    @Test fun failedCommitDoesNotAdvanceAndStaleCommandNeverWrites() {
        val coordinator = BusinessRevisionCoordinator()
        val before = coordinator.capture()
        runCatching { coordinator.commit("core") { error("rollback") } }
        assertEquals(before, coordinator.capture())
        coordinator.commit("core") { 1 }
        var wrote = false
        val stale = CommandContext("op", before, 1) { true }
        assertTrue(runCatching { coordinator.commit("core", stale) { wrote = true } }.isFailure)
        assertFalse(wrote)
    }
    @Test fun stoppedGenerationCannotCommitAndChangesRetainAllDomains() {
        val coordinator = BusinessRevisionCoordinator()
        val stopped = CommandContext("op", coordinator.capture(), 1) { false }
        assertTrue(runCatching { coordinator.commit("recording", stopped) { 1 } }.isFailure)
        coordinator.commit("recording") { 1 }
        assertEquals(DataDomain.values().toSet(), coordinator.changes.value.domains)
        assertEquals(1L, coordinator.capture().values["recording"])
    }
}
