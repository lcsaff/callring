package com.greatwall.callring.coordination

import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class InvalidationTrackerTest {
    private fun entity(domain: DataDomain, key: String) = ChangeSubscription(setOf(domain), mapOf(domain to key))
    @Test fun slowCollectorRetainsBothEntitiesWithoutRefreshingAnotherDomain() = runBlocking {
        val tracker = InvalidationTracker("process")
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<InvalidationSnapshot>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.snapshots.collect { values += it; if (values.size == 1) release.await() }
        }
        tracker.publish(1, ChangeImpact(mapOf(DataDomain.CALLERS to setOf("A"))))
        tracker.publish(2, ChangeImpact(mapOf(DataDomain.CALLERS to setOf("B"))))
        release.complete(Unit); yield()
        val latest = values.last()
        assertEquals(1L, latest.token(entity(DataDomain.CALLERS, "A")).revisions[DataDomain.CALLERS])
        assertEquals(2L, latest.token(entity(DataDomain.CALLERS, "B")).revisions[DataDomain.CALLERS])
        assertEquals(0L, latest.token(entity(DataDomain.WIDGET_BINDINGS, "A")).revisions[DataDomain.WIDGET_BINDINGS])
        job.cancelAndJoin()
    }
    @Test fun overflowOnlyPromotesTheAffectedDomainAndWideChangeClearsEntities() {
        val tracker = InvalidationTracker("process")
        tracker.publish(1, ChangeImpact(mapOf(DataDomain.WIDGET_BINDINGS to setOf("i"))))
        val frozen = tracker.snapshots.value
        repeat(1025) { tracker.publish(it + 2L, ChangeImpact(mapOf(DataDomain.CALLERS to setOf("$it")))) }
        val latest = tracker.snapshots.value
        assertEquals(1026L, latest.token(entity(DataDomain.CALLERS, "missing")).revisions[DataDomain.CALLERS])
        assertEquals(0L, latest.token(entity(DataDomain.WIDGET_BINDINGS, "j")).revisions[DataDomain.WIDGET_BINDINGS])
        assertEquals(1L, frozen.revision)
        assertFalse(frozen.domains.containsKey(DataDomain.CALLERS))
        tracker.publish(1027, ChangeImpact.wide(setOf(DataDomain.CALLERS)))
        assertTrue(tracker.snapshots.value.domains.getValue(DataDomain.CALLERS).entityRevisions.isEmpty())
    }
    @Test fun noOpPreservesSnapshotAndEpochInvalidatesAllTokens() {
        val tracker = InvalidationTracker("process")
        val before = tracker.snapshots.value
        tracker.publish(1, ChangeImpact(emptyMap()))
        assertSame(before, tracker.snapshots.value)
        val subscription = entity(DataDomain.QUIET_HOURS, "A")
        tracker.reset(1)
        assertNotEquals(before.token(subscription), tracker.snapshots.value.token(subscription))
    }
    @Test fun snapshotsAndProjectedTokensCannotBeMutated() {
        val tracker = InvalidationTracker("process")
        val keys = mutableSetOf("A")
        val impact = ChangeImpact(mapOf(DataDomain.CALLERS to keys))
        keys += "B"
        tracker.publish(1, impact)
        val snapshot = tracker.snapshots.value
        assertEquals(setOf("A"), snapshot.domains.getValue(DataDomain.CALLERS).entityRevisions.keys)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.domains as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.token(entity(DataDomain.CALLERS, "A")).revisions as MutableMap).clear() }
    }
}
