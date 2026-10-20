package com.greatwall.callring.coordination

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.PositionService
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.PositionRecord
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PositionServiceTest {
    private class Positions : PositionTransactions {
        val rows = linkedMapOf(1L to PositionRecord(1, "Mine", 2, false), 2L to PositionRecord(2, "Other", 3, false))
        var mine: Long? = 1; var next = 3L; var referenced = false; var fail = false
        override fun <T> readPositions(block: (PositionReader) -> T): T = block(writer())
        override fun <T> writePositions(block: (PositionWriter) -> T): T {
            val before = rows.toMap(); val oldMine = mine
            try { return block(writer()).also { if (fail) error("commit.failed") } }
            catch (failure: Throwable) { rows.clear(); rows.putAll(before); mine = oldMine; throw failure }
        }
        private fun writer() = object : PositionWriter {
            override fun positions() = rows.values.toList()
            override fun position(id: Long) = rows[id]
            override fun myPositionId() = mine
            override fun positionNameExists(name: String, except: Long?) = rows.values.any { it.id != except && it.name == name }
            override fun positionReferenced(id: Long) = referenced
            override fun positionNumbers(id: Long) = if (referenced) setOf("39999") else emptySet()
            override fun putPosition(value: PositionRecord, insert: Boolean): PositionRecord = (if (insert) value.copy(id = next++) else value).also { rows[it.id] = it }
            override fun deletePosition(id: Long) { rows.remove(id) }
            override fun saveMyPosition(id: Long?) { mine = id }
        }
    }
    @Test fun inactiveMineCanBeRetainedButOtherInactiveCannotBeSelectedAndReferencesPreventDelete() = runBlocking {
        val tx = Positions(); val coordinator = BusinessRevisionCoordinator(); val service = PositionService(tx, coordinator, Dispatchers.Unconfined, operationActive = { true })
        assertTrue(service.mine(1) is ApiResult.Success); assertEquals(0L, coordinator.marker())
        assertEquals("position.inactive", (service.mine(2) as ApiResult.Failure).error.fieldErrors["positionId"])
        assertEquals(ErrorCode.IN_USE, (service.delete(1) as ApiResult.Failure).error.code)
        tx.referenced = true
        assertEquals(ErrorCode.IN_USE, (service.delete(2) as ApiResult.Failure).error.code)
        assertEquals(ErrorCode.NOT_FOUND, (service.delete(99) as ApiResult.Failure).error.code)
        assertEquals(0L, coordinator.marker())
    }
    @Test fun draftMergesOnlyDirtyFieldsInTransactionAndRetainsIdentityDeletedAndFieldConflictShapes() = runBlocking {
        val tx = Positions(); val coordinator = BusinessRevisionCoordinator(); val service = PositionService(tx, coordinator, Dispatchers.Unconfined, operationActive = { true })
        val base = SavePosition(2, "Other", 3, false)
        service.save(base.copy(level = 4))
        val merged = (service.edit(base, base.copy(name = " Changed ")) as ApiResult.Success).value
        assertEquals("Changed", merged.name); assertEquals(4, merged.level)
        assertEquals("draft.field_changed", (service.edit(base, base.copy(level = 5)) as ApiResult.Failure).error.message)
        assertEquals("draft.identity_changed", (service.edit(base, base.copy(id = 1)) as ApiResult.Failure).error.message)
        assertEquals("draft.baseline_required", (service.edit(null, base) as ApiResult.Failure).error.message)
        service.delete(2)
        assertEquals("draft.deleted", (service.edit(base, base) as ApiResult.Failure).error.message)
        val revision = coordinator.marker(); tx.fail = true
        assertTrue(service.save(SavePosition(null, "New", 5, true)) is ApiResult.Failure)
        assertEquals(revision, coordinator.marker()); assertEquals(1, tx.rows.size)
    }
}
