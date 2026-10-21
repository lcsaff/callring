package com.greatwall.callring.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord

import com.greatwall.callring.application.*
import com.greatwall.callring.application.callers.*
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.*
import java.time.Clock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CallerServiceTest {
    private class Profiles : CallerTransactions, CallerProfileWriter {
        val rows = linkedMapOf("31234" to ProfileRecord("31234", "A", "D"))
        val roles = linkedMapOf<Long, PositionRecord>()
        var reads = 0
        var writes = 0
        var puts = 0
        var checkRead: () -> Unit = {}
        var afterRead: () -> Unit = {}

        override fun <T> readCaller(block: (CallerProfileReader) -> T): T {
            reads++
            checkRead()
            return block(this).also { afterRead() }
        }

        override fun <T> writeCaller(block: (CallerProfileWriter) -> T): T {
            writes++
            return block(this)
        }

        override fun caller(number: String) = rows[number]

        override fun callerCount() = rows.size.toLong()

        override fun callerPage(offset: Long, limit: Int) =
            rows.values
                .sortedBy { it.number }
                .drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .take(limit)

        override fun callers(numbers: List<String>) = numbers.mapNotNull(rows::get)

        override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit) {
            rows.values
                .sortedBy { it.number }
                .forEach { accept(CallerSearchRow(it.number, it.name, it.department)) }
        }

        override fun putCaller(value: ProfileRecord, insert: Boolean) {
            puts++
            rows[value.number] = value
        }

        override fun removeCaller(number: String) {
            rows.remove(number)
        }

        override fun matchingCallNumbers(number: String) = emptyList<String>()

        override fun visitCallerCallIdentities(
            numbers: List<String>,
            accept: (Long, String) -> Unit,
        ) = Unit

        override fun callCount(numbers: List<String>) = 0L

        override fun removeCallerCalls(numbers: List<String>, expectedCount: Long) = Unit

        override fun callerPositions(ids: Set<Long>) = ids.mapNotNull(roles::get)

        override fun positions() = roles.values.toList()

        override fun position(id: Long) = roles[id]

        override fun myPositionId(): Long? = null
    }

    private val unusedQueries =
        object : CallerQueryTransactions {
            override fun <T> readCallerOverview(block: (CallerOverviewReader) -> T): T =
                error("unexpected overview")

            override fun <T> readCallerCalls(block: (CallerCallReader) -> T): T =
                error("unexpected calls")
        }

    @Test
    fun queriesRunOutsideFenceAndRetryRelevantChanges() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val query =
            CallerQueryService(p, unusedQueries, c, Dispatchers.Unconfined, Clock.systemUTC()) {
                error("unexpected runtime")
            }
        p.checkRead = {
            assertFalse("query must not hold coordinator fence", Thread.holdsLock(c.fence))
        }
        p.afterRead = {
            if (p.reads == 1) {
                p.rows["31234"] = p.rows.getValue("31234").copy(name = "new")
                c.publishCore(setOf("profile:31234"), ChangeImpact(emptyMap()))
            }
        }
        assertEquals("new", query.editor("31234").requiredValue().draft.name)
        assertEquals(2, p.reads)
    }

    @Test
    fun directInvalidNumberPrecedesGuardAndUsesNoStorage() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val cmd = CommandContext("blocked", c.capture(), 1, active = { false })
        val service = CallerCommandService(p, c, Dispatchers.Unconfined, { _, _ -> }, cmd, operationActive = { true })
        val result = service.save(SaveCallerProfile("invalid", "", "", null)) as ApiResult.Failure
        assertEquals("number.invalid", result.error.message)
        assertEquals(0, p.writes)
    }

    @Test
    fun dirtyFieldsMergeFinalCurrentAndKeepDirectAndUiConflictCodes() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val patches = mutableListOf<Pair<String, Boolean>>()
        val service =
            CallerCommandService(p, c, Dispatchers.Unconfined, { n, b -> patches.add(n to b) }, operationActive = { true })
        val base = CallerDraft("31234", "A", "D", null, false)
        p.rows["31234"] =
            p.rows.getValue("31234").copy(department = "concurrent", whitelisted = true)
        val saved =
            service
                .save(
                    SaveCallerProfile("31234", " new ", "ignored", null, null, base, setOf("name")),
                )
                .requiredValue()
        assertEquals("new", saved.name)
        assertEquals("concurrent", saved.department)
        assertTrue(saved.whitelisted)
        assertEquals(listOf("31234" to true), patches)
        assertEquals(1L, c.marker())
        val direct =
            service.save(SaveCallerProfile("31234", "next", "", null, null, base, setOf("name")))
                as ApiResult.Failure
        assertEquals(ErrorCode.VALIDATION, direct.error.code)
        assertEquals("name", direct.error.fieldErrors.keys.single())
        val draft =
            service.edit(base, base.copy(name = "next", department = "changed"))
                as ApiResult.Failure
        assertEquals(ErrorCode.CONFLICT, draft.error.code)
        assertEquals("name", draft.error.fieldErrors.keys.single())
        assertEquals(listOf("31234" to true), patches)
        assertEquals(1, p.puts)
    }

    @Test
    fun noDirtyMissingProfileCreatesOnceAndNoopRecordsCommand() = runBlocking {
        val p = Profiles()
        p.rows.clear()
        val c = BusinessRevisionCoordinator()
        val command =
            CommandContext(
                "noop",
                c.capture(),
                0,
                expectedScopes = c.expect(setOf("profile:31234")),
            ) {
                true
            }
        val service = CallerCommandService(p, c, Dispatchers.Unconfined, { _, _ -> }, operationActive = { true })
        val base = CallerDraft("31234", "", "", null, false)
        service.edit(base, base).requiredValue()
        assertEquals(1, p.puts)
        assertEquals(1L, c.marker())
        val noop =
            CallerCommandService(
                p,
                c,
                Dispatchers.Unconfined,
                { _, _ -> },
                command.copy(
                    expectedVersions = c.capture(),
                    expectedScopes = c.expect(setOf("profile:31234")),
                ), operationActive = { true })
        noop.edit(base, base).requiredValue()
        assertEquals(1, p.puts)
        assertEquals(1L, c.marker())
        assertNotNull(c.committed("noop"))
    }

    @Test
    fun baselineWhitelistAndRetainedInactiveValidationStayInFinalTransaction() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val service = CallerCommandService(p, c, Dispatchers.Unconfined, { _, _ -> }, operationActive = { true })
        p.roles[4] = PositionRecord(4, "retained", 2, false)
        p.roles[5] = PositionRecord(5, "other", 3, false)
        p.rows["31234"] = p.rows.getValue("31234").copy(positionId = 4)
        service.save(SaveCallerProfile("31234", "A", "D", 4)).requiredValue()
        assertEquals(
            "position.inactive",
            (service.save(SaveCallerProfile("31234", "A", "D", 5)) as ApiResult.Failure)
                .error
                .message,
        )
        val invalid =
            service.save(
                SaveCallerProfile(
                    "31234",
                    "A",
                    "D",
                    999,
                    baseline = null,
                    dirtyFields = setOf("other"),
                ),
            ) as ApiResult.Failure
        assertEquals("draft.baseline_required", invalid.error.message)
        val base = CallerDraft("wrong", "A", "D", 4, false)
        assertEquals(
            "draft.number_changed",
            (service.edit(base, base.copy(number = "31234")) as ApiResult.Failure).error.message,
        )
        assertEquals(0, p.puts)
    }

    @Test
    fun cancellationAndDeniedCommandNeverTouchStorage() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val blocked = CommandContext("denied", c.capture(), 0, mayCommit = { false }) { true }
        val service = CallerCommandService(p, c, Dispatchers.Unconfined, { _, _ -> }, blocked, operationActive = { true })
        assertEquals(
            "action.foreground_required",
            (service.save(SaveCallerProfile("31234", "", "", null)) as ApiResult.Failure)
                .error
                .message,
        )
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try {
                    service.save(SaveCallerProfile("bad", "", "", null))
                    fail("cancelled")
                } catch (_: CancellationException) {}
            }
        job.join()
        assertEquals(0, p.writes)
    }

    @Test
    fun unrelatedCommitDoesNotRetryAndMetadataRetainsTheBeforeRevision() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val query =
            CallerQueryService(p, unusedQueries, c, Dispatchers.Unconfined, Clock.systemUTC()) {
                error("runtime")
            }
        p.afterRead = { c.publishCore(setOf("quiet"), ChangeImpact(emptyMap())) }
        val result = query.search(CallerSearch("A")).requiredValue()
        assertEquals(1, p.reads)
        assertEquals(0L, result.meta.dataRevision)
        assertEquals(1L, c.marker())
    }

    @Test
    fun queryRetriesCollectionScopeButRejectsPersistentRelevantChurn() = runBlocking {
        val p = Profiles()
        val c = BusinessRevisionCoordinator()
        val query =
            CallerQueryService(p, unusedQueries, c, Dispatchers.Unconfined, Clock.systemUTC()) {
                error("runtime")
            }
        p.afterRead = { c.publishCore(setOf("profiles"), ChangeImpact(emptyMap())) }
        val result = query.search(CallerSearch()) as ApiResult.Failure
        assertEquals("business.changed", result.error.message)
        assertEquals(3, p.reads)
    }

    @Test
    fun uiDirtyConflictPrecedesDirectBaselineIdentityValidationForFormattedDraftNumbers() =
        runBlocking {
            val p = Profiles()
            val service =
                CallerCommandService(
                    p,
                    BusinessRevisionCoordinator(),
                    Dispatchers.Unconfined,
                    { _, _ -> }, operationActive = { true })
            val base = CallerDraft("3-1234", "old", "D", null, false)
            val conflict = service.edit(base, base.copy(name = "wanted")) as ApiResult.Failure
            assertEquals(ErrorCode.CONFLICT, conflict.error.code)
            assertEquals("draft.field_changed", conflict.error.message)
            val unchanged = base.copy(name = "A")
            assertEquals(
                "draft.baseline_required",
                (service.edit(unchanged, unchanged) as ApiResult.Failure).error.message,
            )
        }
}
