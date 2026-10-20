package com.greatwall.callring.coordination

import com.greatwall.callring.application.*
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.*
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HistorySettingsServiceTest {
    private class Work : WorkScheduleTransactions {
        var rows = listOf<com.greatwall.callring.domain.model.WorkScheduleSettings>()
        var nextId = 9L
        var writes = 0
        override fun <T> readWorkSchedule(block: (WorkScheduleReader) -> T): T = block(object : WorkScheduleReader { override fun latestWorkSchedule() = rows.lastOrNull() })
        override fun <T> writeWorkSchedule(block: (WorkScheduleWriter) -> T): T {
            writes++
            return block(object : WorkScheduleWriter {
                override fun latestWorkSchedule() = rows.lastOrNull()
                override fun appendWorkSchedule(value: com.greatwall.callring.domain.model.WorkScheduleSettings) = value.copy(configVersion=nextId++).also { rows=rows+it }
            })
        }
    }
    /** These services instantiate with work-only facts: no BackendContext/BackendState or general store. */
    private class History : CalendarTransactions, LeaveTransactions {
        var state=WorkHistory(listOf(defaultWorkSchedule()),emptyMap(),emptyMap(),emptySet(),emptyMap(),emptyMap(),0)
        var audits=listOf<AuditRecord>()
        var nextId=12L
        var inTransaction=false
        var writes=0
        var fail=false
        private fun writer() = object : CalendarWriter, LeaveWriter {
            override fun workHistory()=state
            override fun calendarOverview()=CalendarOverview(state.calendarYears.values.sortedBy { it.year },state.overrides.values.sortedBy { it.date })
            override fun calendarYear(year: Int)=state.calendarYears[year]?.let { CalendarImport(it,state.holidays.filterKeys { it.year==year },state.makeupDates.filterTo(linkedSetOf()) { it.year==year }) }
            override fun replaceCalendar(value: CalendarImport) {
                val year=value.metadata.year
                state=state.copy(calendarYears=state.calendarYears+(year to value.metadata),holidays=state.holidays.filterKeys { it.year!=year }+value.holidays,
                    makeupDates=state.makeupDates.filterTo(linkedSetOf()) { it.year!=year }+value.makeupDates)
            }
            override fun dateOverride(date: LocalDate)=state.overrides[date]
            override fun putDateOverride(value: com.greatwall.callring.domain.model.DateOverride) { state=state.copy(overrides=state.overrides+(value.date to value)) }
            override fun removeDateOverride(date: LocalDate) { state=state.copy(overrides=state.overrides-date) }
            override fun leaves()=state.leaves.values.sortedWith(compareByDescending<LeaveRecord> { it.startMillis }.thenByDescending { it.id })
            override fun leave(id: Long)=state.leaves[id]
            override fun putLeave(value: LeaveRecord, insert: Boolean)= (if(insert) value.copy(id=nextId++) else value).also { state=state.copy(leaves=state.leaves+(it.id to it)) }
            override fun removeLeave(id: Long) { state=state.copy(leaves=state.leaves-id) }
            override fun incrementLeaveVersion() { state=state.copy(leaveVersion=state.leaveVersion+1) }
            override fun appendAudit(value: AuditRecord) { audits=audits+value }
            override fun classificationBatch(afterId: Long, limit: Int)=emptyList<HistoricalClassification>()
            override fun updateClassification(before: HistoricalClassification, current: com.greatwall.callring.domain.model.ClassificationFact)=error("unexpected")
        }
        override fun <T> readCalendar(block: (CalendarReader) -> T): T=block(writer())
        override fun <T> readLeaves(block: (LeaveReader) -> T): T=block(writer())
        override fun <T> writeCalendar(block: (CalendarWriter) -> T): T=transaction { block(writer()) }
        override fun <T> writeLeaves(block: (LeaveWriter) -> T): T=transaction { block(writer()) }
        private fun <T> transaction(block: () -> T): T {
            val before=state; val auditBefore=audits; val idBefore=nextId
            writes++; inTransaction=true
            try { return block().also { if(fail) error("synthetic.rollback") } }
            catch(t: Throwable) { state=before; audits=auditBefore; nextId=idBefore; throw t }
            finally { inTransaction=false }
        }
    }
    private val clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC)
    private fun imported(version: String="v1", published: String="2026-01-01")=CalendarImport(com.greatwall.callring.domain.model.CalendarYear(2026,"feed",published,version),mapOf(LocalDate.of(2026,1,1) to "holiday"),emptySet())
    private fun calendar(tx: History, coordinator: BusinessRevisionCoordinator, source: CalendarSource=CalendarSource { imported() },
        docs: CalendarDocumentReader=CalendarDocumentReader { imported() }, command: CommandContext?=null, after: (WorkHistory)->Unit={})=
        CalendarService(tx,coordinator,Dispatchers.Unconfined,clock,source,docs,after,command)
    private fun <T> ApiResult<T>.value()=(this as ApiResult.Success).value
    @Test fun sameSourceVersionIgnoresFetchedPublicationDateBeforeValidatingFinalMetadata()=runBlocking {
        val tx=History(); val c=BusinessRevisionCoordinator(); var fetched=imported()
        val service=calendar(tx,c,CalendarSource { fetched })
        service.synchronize(2026).value()
        fetched=imported(published="not-a-date")
        assertTrue("fetched published is ignored for an already stored source/version",service.synchronize(2026) is ApiResult.Success)
        assertEquals("2026-01-01",tx.state.calendarYears.getValue(2026).published)
        assertEquals(1L,c.marker()); assertEquals(1,tx.audits.size)
    }
    @Test fun workEmptyHistoryKeepsZeroEffectiveAndDirtyMergeUsesFinalCurrentFields()=runBlocking {
        val tx=Work(); val c=BusinessRevisionCoordinator()
        val service=WorkScheduleService(tx,c,Dispatchers.Unconfined,Clock.fixed(Instant.ofEpochMilli(-1),ZoneOffset.UTC),{})
        val base=service.get().value()
        val first=service.save(SaveWorkSchedule("UTC",emptyMap(),base.makeup)).value()
        assertEquals(0,first.effectiveFromEpochMillis)
        val merged=service.edit(base,base.copy(makeup=listOf(TimeWindow(60,120)))).value()
        assertEquals("UTC",merged.zoneId); assertEquals(emptyMap<Int,List<TimeWindow>>(),merged.weekly)
        assertEquals(1,merged.effectiveFromEpochMillis)
        assertEquals(2L,c.marker()); assertEquals(setOf("schedule"),c.invalidations.snapshots.value.domains.getValue(com.greatwall.callring.contracts.change.DataDomain.WORK_CALENDAR).entityRevisions.keys)
    }
    @Test fun preparationIsOutsideTransactionAndFenceAndRechecksConcurrentMetadataAtFinalCommit()=runBlocking {
        val tx=History(); val c=BusinessRevisionCoordinator(); val service=calendar(tx,c)
        service.synchronize(2026).value()
        val concurrent=calendar(tx,c,docs=CalendarDocumentReader { imported(published="2026-02-01") })
        val preparing=calendar(tx,c,CalendarSource {
            assertFalse(Thread.holdsLock(c.fence)); assertFalse(tx.inTransaction)
            runBlocking { concurrent.import(DocumentRef("synthetic")).value() }
            imported(published="2026-03-01")
        })
        preparing.synchronize(2026).value()
        assertEquals("2026-02-01",tx.state.calendarYears.getValue(2026).published)
        assertEquals(2L,c.marker())
    }
    @Test fun commandScopeConflictsNoopCompletionFailureRollbackAndCancellationHaveNoExtraPublications()=runBlocking {
        val tx=History(); val c=BusinessRevisionCoordinator()
        val stale=CommandContext("stale",c.capture(),0,expectedScopes=c.expect(setOf("overrides"))) { true }
        val value=DateOverride(LocalDate.of(2026,1,1),DateOverrideKind.REST,emptyList())
        val service=calendar(tx,c)
        service.saveOverride(value).value()
        assertEquals("business.changed",(calendar(tx,c,command=stale).deleteOverride(value.date) as ApiResult.Failure).error.message)
        val command=CommandContext("noop",c.capture(),0,expectedScopes=c.expect(setOf("overrides"))) { true }
        calendar(tx,c,command=command).saveOverride(value).value()
        assertNotNull(c.committed("noop")); assertEquals(1L,c.marker())
        tx.fail=true
        assertTrue(service.deleteOverride(value.date) is ApiResult.Failure)
        assertEquals(1,tx.audits.size); assertEquals(1L,c.marker()); assertNotNull(tx.state.overrides[value.date])
        tx.fail=false
        val entered=CountDownLatch(1); val interrupted=CountDownLatch(1)
        val blocked=calendar(tx,c,CalendarSource {
            entered.countDown()
            try { CountDownLatch(1).await() } catch(e: InterruptedException) { interrupted.countDown(); throw e }
            imported()
        })
        val job=launch(Dispatchers.Default) { blocked.synchronize(2026) }
        assertTrue(entered.await(5,TimeUnit.SECONDS)); job.cancelAndJoin()
        assertTrue(interrupted.await(5,TimeUnit.SECONDS)); assertEquals(1L,c.marker())
    }
    @Test fun leaveDirtyMergePreservesConcurrentPeriodDeletedDraftFailsAndOnlyNoteChangesVersion()=runBlocking {
        val tx=History(); val c=BusinessRevisionCoordinator(); var patches=0
        val service=LeaveService(tx,c,Dispatchers.Unconfined,clock,{ patches++ })
        val saved=service.save(SaveLeave(period=LeavePeriod.Timed(1,2),note="a")).value()
        val base=SaveLeave(saved.id,LeavePeriod.Timed(1,2),"a")
        service.save(base.copy(period=LeavePeriod.Timed(3,4))).value()
        val merged=service.edit(base,base.copy(note="b")).value()
        assertEquals(3,merged.startInclusiveEpochMillis); assertEquals(3L,tx.state.leaveVersion)
        assertEquals(3,patches)
        service.save(SaveLeave(saved.id,LeavePeriod.Timed(3,4),"b")).value()
        assertEquals(3L,c.marker()); assertEquals(3,patches)
        service.delete(saved.id).value()
        assertEquals("draft.deleted",(service.edit(base,base.copy(note="c")) as ApiResult.Failure).error.message)
        val next=service.save(SaveLeave(period=LeavePeriod.Timed(1,2),note="next")).value()
        assertTrue(next.id>saved.id)
        assertEquals("draft.identity_changed",(service.edit(base,base.copy(id=next.id)) as ApiResult.Failure).error.message)
    }
}
