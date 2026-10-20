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
        val state = BackendState()
        override val legacy = object : BackendStore {
            override fun <T> read(block: (BackendState) -> T) = block(state)
            override fun <T> transaction(block: (BackendState) -> T) = block(state)
            override fun <T> captureFull(block: (BackendState, List<AuditRecord>) -> T) = block(state, emptyList())
        }
        override val fullSnapshot: FullSnapshotReader get() = legacy
        override val sessions = object : CoreUnitOfWork {
            private val session = object : CoreWriteSession {
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
                override fun positionNameExists(name: String, except: Long?): Boolean = error("unused")
                override fun positionReferenced(id: Long): Boolean = error("unused")
                override fun positionNumbers(id: Long): Set<String> = error("unused")
                override fun putPosition(value: PositionRecord, insert: Boolean): PositionRecord = error("unused")
                override fun deletePosition(id: Long): Unit = error("unused")
                override fun saveMyPosition(id: Long?): Unit = error("unused")
                override fun settingsSummary(): CoreSettingsSummary = error("unused")
                override fun quiet() = QuietConfiguration(state.quiet, state.lastQuietMode)
                override fun saveQuiet(value: QuietConfiguration): Boolean { state.quiet = value.settings; state.lastQuietMode = value.lastMode; return true }
            }
            override fun <T> readQuiet(block: (QuietReader) -> T) = block(session)
            override fun <T> writeQuiet(block: (QuietWriter) -> T) = block(session)
            override fun <T> readSession(block: (CoreReadSession) -> T) = block(session)
            override fun <T> writeSession(block: (CoreWriteSession) -> T) = block(session)
        }
        var query: () -> SourcePage = { SourcePage(emptyList(), 42) }
        override val scoped = object : ScopedReadPort {
            override val domains = setOf(AiDomain.D10)
            override fun version(domain: AiDomain): Long = error("resource must supply version outside lease")
            override fun query(spec: QuerySpec, after: String?) = query()
        }
        override fun stateLoadCount() = 7L
        override fun close() { closes++ }
    }
    @Test fun fakeCapabilitiesShareUniqueLazyInstanceAndMetadataDoesNotOpen() {
        val opens = AtomicInteger()
        val fake = Fake()
        val resources = StorageResources { opens.incrementAndGet(); fake }
        val source = resources.scopedReads(BusinessRevisionCoordinator())
        assertEquals(0L, resources.stateLoadCount()); assertEquals(0L, source.version(AiDomain.D10)); assertTrue(source.domains.contains(AiDomain.D10))
        assertEquals(0, opens.get())
        assertEquals(42L, source.query(QuerySpec(AiDomain.D10), null).matchedTotal)
        assertEquals(QuietMode.OFF, resources.readQuiet { it.quiet().settings.mode })
        resources.read { it.nextCallId }; resources.captureFull { _, _ -> Unit }
        assertEquals(1, opens.get()); assertEquals(7L, resources.stateLoadCount()); assertEquals(1L, resources.completeCaptures.get())
        resources.close(); resources.close(); assertEquals(1, fake.closes)
        assertThrows(BackendFailure::class.java) { resources.read {} }
        assertThrows(BackendFailure::class.java) { resources.transaction {} }
        assertThrows(BackendFailure::class.java) { resources.captureFull { _, _ -> } }
        assertThrows(BackendFailure::class.java) { resources.readQuiet {} }
        assertThrows(BackendFailure::class.java) { resources.writeQuiet {} }
        assertThrows(BackendFailure::class.java) { resources.readSession {} }
        assertThrows(BackendFailure::class.java) { resources.writeSession {} }
        assertThrows(BackendFailure::class.java) { resources.stateLoadCount() }
        assertThrows(BackendFailure::class.java) { source.version(AiDomain.D10) }
        assertThrows(BackendFailure::class.java) { source.query(QuerySpec(AiDomain.D10), null) }
        assertThrows(BackendFailure::class.java) { source.domains }
    }
    @Test fun failedFactoryCanRetryAndConcurrentFirstIoOnlyOpensOnce() {
        val opens = AtomicInteger()
        val fake = Fake()
        val resources = StorageResources { if (opens.incrementAndGet() == 1) error("open failed"); fake }
        assertThrows(IllegalStateException::class.java) { resources.read {} }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..16).map { pool.submit<Long> { resources.read { it.nextCallId } } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, opens.get())
        } finally { resources.close(); pool.shutdownNow() }
    }
    @Test fun versionDoesNotTakeResourceLeaseAndCloseWaitsForCompleteScopedOperation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fake = Fake()
        fake.query = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); assertEquals(0, fake.closes); SourcePage(emptyList(), 1) }
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
            while (closing.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, closing.state)
            assertThrows(BackendFailure::class.java) { source.version(AiDomain.D10) }
            assertThrows(BackendFailure::class.java) { resources.read {} }
            release.countDown()
            assertEquals(1L, query.get(5, TimeUnit.SECONDS).matchedTotal)
            closing.join(5000); assertFalse(closing.isAlive)
            assertEquals(1, fake.closes)
        } finally { release.countDown(); resources.close(); pool.shutdownNow() }
    }
    @Test fun changedVersionDuringQueryRetriesInsteadOfTaggingOldRowsWithNewVersion() {
        val coordinator = BusinessRevisionCoordinator(); val fake = Fake(); var reads = 0
        val entered = CountDownLatch(1); val changed = CountDownLatch(1)
        val writer = Thread { check(entered.await(5, TimeUnit.SECONDS)); coordinator.changed("core"); changed.countDown() }
        writer.start()
        fake.query = { reads++; if (reads == 1) { entered.countDown(); check(changed.await(5, TimeUnit.SECONDS)) }; SourcePage(emptyList(), reads.toLong()) }
        val resources = StorageResources { fake }
        try { assertEquals(2L, resources.scopedReads(coordinator).query(QuerySpec(AiDomain.D10), null).matchedTotal); assertEquals(2, reads) }
        finally { resources.close(); writer.join(5000) }
    }
}
