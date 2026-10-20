package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class StorageResourcesTest {
    private class Fake : CoreStorageResource {
        var closes = 0
        var quietSettings = QuietSettings(QuietMode.OFF, false, emptyMap())
        var lastMode = QuietMode.NON_WORK
        override val fullSnapshot = FullSnapshotReader {
            FullSnapshotFacts(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                WorkHistory(
                    emptyList(),
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptyMap(),
                    emptyMap(),
                    0,
                ),
                emptyList(),
                null,
                QuietSettings(QuietMode.OFF, false, emptyMap()),
                QuietMode.NON_WORK,
                ScoreSettings(5, 2, 10, 30),
                false,
                emptyList(),
            )
        }

        override val sessions =
            object : CoreUnitOfWork {
                private val session =
                    object : CoreWriteSession {
                        override fun visitReportCalls(
                            number: String,
                            start: Long,
                            end: Long,
                            ascending: Boolean,
                            accept: (ReportCallFact) -> Unit,
                        ): Unit = error("unused")

                        override fun statisticsEnabled(): Boolean = error("unused")

                        override fun homeQuietMode(): QuietMode = error("unused")

                        override fun latestVisibleReportCall(): ReportCallFact? = error("unused")

                        override fun decisionInputs(): DecisionInputs = error("unused")

                        override fun statisticsContains(at: Long): Boolean = error("unused")

                        override fun statisticsStart(): Long = error("unused")

                        override fun hasRecentPending(start: Long, end: Long): Boolean =
                            error("unused")

                        override fun callingWorkHistory(): WorkHistory = error("unused")

                        override fun screeningCall(key: String): RawCallRecord? = error("unused")

                        override fun callingCall(id: Long): RawCallRecord? = error("unused")

                        override fun visibleRecordingLinks(screenId: Long): List<RawCallRecord> = error("unused")

                        override fun providerCalls(logIds: Set<String>): List<RawCallRecord> =
                            error("unused")

                        override fun correlationCalls(number: String): List<RawCallRecord> =
                            error("unused")

                        override fun ensureCallingProfile(number: String): Boolean = error("unused")

                        override fun insertCallingCall(row: RawCallRecord): RawCallRecord =
                            error("unused")

                        override fun updateCallingCalls(
                            changes: List<Pair<RawCallRecord, RawCallRecord>>,
                        ): Unit = error("unused")

                        override fun caller(number: String): ProfileRecord? = error("unused")

                        override fun callerCount(): Long = error("unused")

                        override fun callerPage(offset: Long, limit: Int): List<ProfileRecord> =
                            error("unused")

                        override fun callers(numbers: List<String>): List<ProfileRecord> =
                            error("unused")

                        override fun callerPositions(ids: Set<Long>): List<PositionRecord> =
                            error("unused")

                        override fun visitCallerSearch(accept: (CallerSearchRow) -> Unit): Unit =
                            error("unused")

                        override fun putCaller(value: ProfileRecord, insert: Boolean): Unit =
                            error("unused")

                        override fun removeCaller(number: String): Unit = error("unused")

                        override fun matchingCallNumbers(number: String): List<String> =
                            error("unused")

                        override fun visitCallerCallIdentities(
                            numbers: List<String>,
                            accept: (Long, String) -> Unit,
                        ): Unit = error("unused")

                        override fun callCount(numbers: List<String>): Long = error("unused")

                        override fun removeCallerCalls(
                            numbers: List<String>,
                            expectedCount: Long,
                        ): Unit = error("unused")

                        override fun callerCall(id: Long): RawCallRecord? = error("unused")

                        override fun latestCallerCall(): RawCallRecord? = error("unused")

                        override fun visitVisibleCallerCalls(
                            number: String,
                            accept: (CallerOverviewFact) -> Unit,
                        ): Unit = error("unused")

                        override fun calendarOverview() = CalendarOverview(emptyList(), emptyList())

                        override fun calendarYear(
                            year: Int,
                        ): com.greatwall.callring.domain.CalendarImport? = null

                        override fun replaceCalendar(
                            value: com.greatwall.callring.domain.CalendarImport,
                        ) = Unit

                        override fun dateOverride(
                            date: java.time.LocalDate,
                        ): com.greatwall.callring.domain.model.DateOverride? = null

                        override fun putDateOverride(
                            value: com.greatwall.callring.domain.model.DateOverride,
                        ) = Unit

                        override fun removeDateOverride(date: java.time.LocalDate) = Unit

                        override fun leaves() =
                            emptyList<com.greatwall.callring.domain.LeaveRecord>()

                        override fun leave(id: Long): com.greatwall.callring.domain.LeaveRecord? =
                            null

                        override fun putLeave(
                            value: com.greatwall.callring.domain.LeaveRecord,
                            insert: Boolean,
                        ) = value

                        override fun removeLeave(id: Long) = Unit

                        override fun incrementLeaveVersion() = Unit

                        override fun appendAudit(value: com.greatwall.callring.domain.AuditRecord) =
                            Unit

                        override fun workHistory() =
                            WorkHistory(
                                emptyList(),
                                emptyMap(),
                                emptyMap(),
                                emptySet(),
                                emptyMap(),
                                emptyMap(),
                                0,
                            )

                        override fun classificationBatch(afterId: Long, limit: Int) =
                            emptyList<HistoricalClassification>()

                        override fun updateClassification(
                            before: HistoricalClassification,
                            current: com.greatwall.callring.domain.model.ClassificationFact,
                        ) = Unit

                        override fun latestWorkSchedule():
                            com.greatwall.callring.domain.model.WorkScheduleSettings? = null

                        override fun appendWorkSchedule(
                            value: com.greatwall.callring.domain.model.WorkScheduleSettings,
                        ) = value

                        override fun score(): ScoreSettings = error("unused")

                        override fun saveScore(value: ScoreSettings): Boolean = error("unused")

                        override fun overlay(): Boolean = error("unused")

                        override fun saveOverlay(value: Boolean): Boolean = error("unused")

                        override fun statisticsHead(): StatisticsHead = error("unused")

                        override fun openStatistics(at: Long): Unit = error("unused")

                        override fun closeStatistics(id: Long, at: Long): Unit = error("unused")

                        override fun positions(): List<PositionRecord> = error("unused")

                        override fun position(id: Long): PositionRecord? = error("unused")

                        override fun myPositionId(): Long? = error("unused")

                        override fun positionNameExists(name: String, except: Long?): Boolean =
                            error("unused")

                        override fun positionReferenced(id: Long): Boolean = error("unused")

                        override fun positionNumbers(id: Long): Set<String> = error("unused")

                        override fun putPosition(
                            value: PositionRecord,
                            insert: Boolean,
                        ): PositionRecord = error("unused")

                        override fun deletePosition(id: Long): Unit = error("unused")

                        override fun saveMyPosition(id: Long?): Unit = error("unused")

                        override fun settingsSummary(): CoreSettingsSummary = error("unused")

                        override fun quiet() = QuietConfiguration(quietSettings, lastMode)

                        override fun saveQuiet(value: QuietConfiguration): Boolean {
                            quietSettings = value.settings
                            lastMode = value.lastMode
                            return true
                        }
                    }

                override fun <T> readQuiet(block: (QuietReader) -> T) = block(session)

                override fun <T> writeQuiet(block: (QuietWriter) -> T) = block(session)

                override fun <T> readSession(block: (CoreReadSession) -> T) = block(session)

                override fun <T> writeSession(block: (CoreWriteSession) -> T) = block(session)
            }
        var query: () -> SourcePage = { SourcePage(emptyList(), 42) }
        override val scoped =
            object : ScopedReadPort {
                override val domains = setOf(AiDomain.D10)

                override fun version(domain: AiDomain): Long =
                    error("resource must supply version outside lease")

                override fun query(spec: QuerySpec, after: String?) = query()
            }

        override fun narrowAccessCounts() = mapOf("fixtureReads" to 7L)

        override fun close() {
            closes++
        }
    }

    @Test
    fun fakeCapabilitiesShareUniqueLazyInstanceAndMetadataDoesNotOpen() {
        val opens = AtomicInteger()
        val fake = Fake()
        val resources = StorageResources {
            opens.incrementAndGet()
            fake
        }
        val source = resources.scopedReads(BusinessRevisionCoordinator())
        assertTrue(resources.narrowAccessCounts().isEmpty())
        assertEquals(0L, source.version(AiDomain.D10))
        assertTrue(source.domains.contains(AiDomain.D10))
        assertEquals(0, opens.get())
        assertEquals(42L, source.query(QuerySpec(AiDomain.D10), null).matchedTotal)
        assertEquals(QuietMode.OFF, resources.readQuiet { it.quiet().settings.mode })
        resources.readSession { it.quiet(); 0L }
        resources.capture()
        assertEquals(1, opens.get())
        assertEquals(7L, resources.narrowAccessCounts().getValue("fixtureReads"))
        assertEquals(1L, resources.completeCaptures.get())
        resources.close()
        resources.close()
        assertEquals(1, fake.closes)
        assertThrows(BackendFailure::class.java) { resources.readSession {} }
        assertThrows(BackendFailure::class.java) { resources.writeSession {} }
        assertThrows(BackendFailure::class.java) { resources.capture() }
        assertThrows(BackendFailure::class.java) { resources.readQuiet {} }
        assertThrows(BackendFailure::class.java) { resources.writeQuiet {} }
        assertThrows(BackendFailure::class.java) { resources.readSession {} }
        assertThrows(BackendFailure::class.java) { resources.writeSession {} }
        assertThrows(BackendFailure::class.java) { resources.narrowAccessCounts() }
        assertThrows(BackendFailure::class.java) { source.version(AiDomain.D10) }
        assertThrows(BackendFailure::class.java) { source.query(QuerySpec(AiDomain.D10), null) }
        assertThrows(BackendFailure::class.java) { source.domains }
    }

    @Test
    fun failedFactoryCanRetryAndConcurrentFirstIoOnlyOpensOnce() {
        val opens = AtomicInteger()
        val fake = Fake()
        val resources = StorageResources {
            if (opens.incrementAndGet() == 1) error("open failed")
            fake
        }
        assertThrows(IllegalStateException::class.java) { resources.readSession {} }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..16).map { pool.submit<Long> { resources.readSession { it.quiet(); 0L } } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, opens.get())
        } finally {
            resources.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun versionDoesNotTakeResourceLeaseAndCloseWaitsForCompleteScopedOperation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = Fake()
        fake.query = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            assertEquals(0, fake.closes)
            SourcePage(emptyList(), 1)
        }
        val resources = StorageResources { fake }
        val source = resources.scopedReads(BusinessRevisionCoordinator())
        val pool = Executors.newFixedThreadPool(3)
        try {
            val query = pool.submit<SourcePage> { source.query(QuerySpec(AiDomain.D10), null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A version read must never wait on the resource monitor held by SQL.
            val version = pool.submit<Long> { source.version(AiDomain.D10) }
            assertEquals(0L, version.get(1, TimeUnit.SECONDS))
            val closing = Thread { resources.close() }
            closing.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closing.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread
                .yield()
            assertEquals(Thread.State.BLOCKED, closing.state)
            assertThrows(BackendFailure::class.java) { source.version(AiDomain.D10) }
            assertThrows(BackendFailure::class.java) { resources.readSession {} }
            release.countDown()
            assertEquals(1L, query.get(5, TimeUnit.SECONDS).matchedTotal)
            closing.join(5000)
            assertFalse(closing.isAlive)
            assertEquals(1, fake.closes)
        } finally {
            release.countDown()
            resources.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun changedVersionDuringQueryRetriesInsteadOfTaggingOldRowsWithNewVersion() {
        val coordinator = BusinessRevisionCoordinator()
        val fake = Fake()
        var reads = 0
        val entered = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val writer = Thread {
            check(entered.await(5, TimeUnit.SECONDS))
            coordinator.publishCore(
                setOf("quiet"),
                com.greatwall.callring.application.coordination.ChangeImpact.wide(
                    setOf(com.greatwall.callring.contracts.change.DataDomain.QUIET_HOURS),
                ),
            )
            changed.countDown()
        }
        writer.start()
        fake.query = {
            reads++
            if (reads == 1) {
                entered.countDown()
                check(changed.await(5, TimeUnit.SECONDS))
            }
            SourcePage(emptyList(), reads.toLong())
        }
        val resources = StorageResources { fake }
        try {
            assertEquals(
                2L,
                resources
                    .scopedReads(coordinator)
                    .query(QuerySpec(AiDomain.D10), null)
                    .matchedTotal,
            )
            assertEquals(2, reads)
        } finally {
            resources.close()
            writer.join(5000)
        }
    }
}
