package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
import org.junit.Assert.*
import org.junit.Test

class BusinessRevisionCoordinatorTest {
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
