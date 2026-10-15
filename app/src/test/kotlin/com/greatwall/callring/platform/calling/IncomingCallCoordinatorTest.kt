package com.greatwall.callring.platform.calling

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.IncomingDecision
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IncomingCallCoordinatorTest {
    private val fact = ClassificationFact(false, WorkReason.REST_DAY, null, true, "UTC", 1, "v1", 0)

    private inner class Engine(
        private val failRecord: Boolean = false,
        private val failCompletion: Boolean = false,
        private val statisticsEnabled: Boolean = true,
    ) : IncomingCallEngine {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CopyOnWriteArrayList<Pair<Long, CallDecision>>()

        override fun normalize(raw: String) = raw

        override fun evaluate(number: String, at: Long) =
            IncomingDecision(true, false, true, true, statisticsEnabled, fact)

        override fun record(
            number: String,
            at: Long,
            decision: CallDecision,
            account: String?,
            original: ClassificationFact,
        ): Long {
            writing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (failRecord) throw IOException("test.write_failure")
            return 42
        }

        override fun complete(id: Long, decision: CallDecision) {
            if (failCompletion) throw IOException("test.completion_failure")
            completed += id to decision
        }
    }

    @Test
    fun decisionDoesNotWaitForStorageAndFinalOutcomeSurvivesEarlyCompletion() {
        val engine = Engine()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
            ) {}
        try {
            val session = coordinator.inspect("session-1", "31234", 1000, "account")
            assertTrue(session.assessment.silence)
            assertTrue(engine.writing.await(2, TimeUnit.SECONDS))
            val done = coordinator.complete(session, CallDecision.QUIET_SILENCED)
            assertFalse(done.isDone)
            engine.release.countDown()
            done.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(42L to CallDecision.QUIET_SILENCED), engine.completed)
            coordinator.complete(session, CallDecision.NORMAL_RINGING).get(2, TimeUnit.SECONDS)
            assertEquals(1, engine.completed.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun failedPersistenceDoesNotAlterSilenceOrInvokeAnInvalidRecordUpdate() {
        val engine = Engine(true)
        val failures = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
            ) {
                failures += Unit
            }
        try {
            val session = coordinator.inspect("session-2", "31234", 1000, null)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
            assertTrue(session.assessment.silence)
            assertTrue(engine.completed.isEmpty())
            assertFalse(failures.isEmpty())
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun presentationFailureDoesNotInterruptFinalPersistence() {
        val engine = Engine()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) {
                        throw IllegalStateException("window.denied")
                    }
                },
            ) {}
        try {
            val session = coordinator.inspect("session-3", "31234", 1000, null)
            coordinator.show(session, CallDecision.QUIET_SILENCED, session.assessment)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
            assertEquals(1, engine.completed.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun successfulAndFailedRecordingBothRequestRecoveryOnceOnTheWriter() {
        for (failRecord in listOf(false, true)) {
            val engine = Engine(failRecord = failRecord)
            val caller = Thread.currentThread()
            val recoveryThreads = CopyOnWriteArrayList<Thread>()
            val coordinator =
                IncomingCallCoordinator(
                    engine,
                    object : IncomingCallUiSink {
                        override fun show(request: IncomingCallUiRequest) = Unit
                    },
                    afterCompletion = { recoveryThreads += Thread.currentThread() },
                ) {}
            try {
                val session = coordinator.inspect("recover-$failRecord", "31234", 1000, null)
                val first = coordinator.complete(session, CallDecision.QUIET_SILENCED)
                val duplicate = coordinator.complete(session, CallDecision.NORMAL_RINGING)
                assertSame(first, duplicate)
                assertTrue(recoveryThreads.isEmpty())
                engine.release.countDown()
                first.get(2, TimeUnit.SECONDS)
                assertEquals(1, recoveryThreads.size)
                assertNotSame(caller, recoveryThreads.single())
                assertEquals(
                    if (failRecord) emptyList() else listOf(42L to CallDecision.QUIET_SILENCED),
                    engine.completed,
                )
            } finally {
                engine.release.countDown()
                coordinator.close()
            }
        }
    }

    @Test
    fun rejectedRecoverySchedulingCannotChangeFinalDecisionOrFailCompletion() {
        val engine = Engine()
        val recoveryCalls = CopyOnWriteArrayList<Unit>()
        val failures = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
                afterCompletion = {
                    recoveryCalls += Unit
                    throw IllegalStateException("test.scheduler_rejected")
                },
            ) {
                failures += Unit
            }
        try {
            val session = coordinator.inspect("recover-rejected", "31234", 1000, null)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.CUSTOM_RINGTONE).get(2, TimeUnit.SECONDS)
            coordinator.complete(session, CallDecision.NORMAL_RINGING).get(2, TimeUnit.SECONDS)
            assertEquals(listOf(42L to CallDecision.CUSTOM_RINGTONE), engine.completed)
            assertEquals(1, recoveryCalls.size)
            assertEquals(1, failures.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun failedFinalWriteStillRequestsRecoveryWithoutFailingTheCall() {
        val engine = Engine(failCompletion = true)
        val recoveryCalls = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
                afterCompletion = { recoveryCalls += Unit },
            ) {}
        try {
            val session = coordinator.inspect("recover-write-failure", "31234", 1000, null)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
            assertTrue(engine.completed.isEmpty())
            assertEquals(1, recoveryCalls.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun statisticsDisabledSessionDoesNotWriteOrRequestRecovery() {
        val engine = Engine(statisticsEnabled = false)
        val recoveryCalls = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
                afterCompletion = { recoveryCalls += Unit },
            ) {}
        try {
            val session = coordinator.inspect("statistics-disabled", "31234", 1000, null)
            coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
            assertEquals(1L, engine.writing.count)
            assertTrue(engine.completed.isEmpty())
            assertTrue(recoveryCalls.isEmpty())
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun leasesCoverActualDecisionAndWriterTasksAndReleaseEvenWhenRecordingFails() {
        for (failRecord in listOf(false, true)) {
            val engine = Engine(failRecord = failRecord)
            val acquired = AtomicInteger()
            val released = CountDownLatch(3)
            val coordinator =
                IncomingCallCoordinator(
                    engine,
                    object : IncomingCallUiSink {
                        override fun show(request: IncomingCallUiRequest) = Unit
                    },
                    workLease = {
                        acquired.incrementAndGet()
                        Closeable { released.countDown() }
                    },
                ) {}
            try {
                val inspected = CompletableFuture<IncomingCallSession>()
                coordinator.execute {
                    inspected.complete(
                        coordinator.inspect("leased-$failRecord", "31234", 1000, null)
                    )
                }
                val session = inspected.get(2, TimeUnit.SECONDS)
                assertTrue(session.assessment.silence)
                assertTrue(engine.writing.await(2, TimeUnit.SECONDS))
                engine.release.countDown()
                coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
                assertTrue("A task retained its lease", released.await(2, TimeUnit.SECONDS))
                assertEquals(3, acquired.get())
                assertEquals(
                    if (failRecord) emptyList() else listOf(42L to CallDecision.QUIET_SILENCED),
                    engine.completed,
                )
            } finally {
                engine.release.countDown()
                coordinator.close()
            }
        }
    }

    @Test
    fun leaseAcquisitionFailureIsDiagnosticAndCannotChangeQuietOrPersistence() {
        val engine = Engine()
        val failures = CopyOnWriteArrayList<Unit>()
        val recoveryCalls = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
                workLease = { throw SecurityException("test.wake_lock_denied") },
                afterCompletion = { recoveryCalls += Unit },
            ) {
                failures += Unit
            }
        try {
            val inspected = CompletableFuture<IncomingCallSession>()
            coordinator.execute {
                inspected.complete(coordinator.inspect("lease-denied", "31234", 1000, null))
            }
            val session = inspected.get(2, TimeUnit.SECONDS)
            assertTrue(session.assessment.silence)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.QUIET_SILENCED).get(2, TimeUnit.SECONDS)
            assertEquals(listOf(42L to CallDecision.QUIET_SILENCED), engine.completed)
            assertEquals(1, recoveryCalls.size)
            assertEquals(3, failures.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun leaseReleaseFailureCannotFailTheCommittedOutcomeOrRecovery() {
        val engine = Engine()
        val released = AtomicInteger()
        val recoveryCalls = CopyOnWriteArrayList<Unit>()
        val coordinator =
            IncomingCallCoordinator(
                engine,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
                workLease = {
                    Closeable {
                        released.incrementAndGet()
                        throw IOException("test.release_failed")
                    }
                },
                afterCompletion = { recoveryCalls += Unit },
            ) {}
        try {
            val session = coordinator.inspect("release-failed", "31234", 1000, null)
            engine.release.countDown()
            coordinator.complete(session, CallDecision.CUSTOM_RINGTONE).get(2, TimeUnit.SECONDS)
            assertEquals(listOf(42L to CallDecision.CUSTOM_RINGTONE), engine.completed)
            assertEquals(2, released.get())
            assertEquals(1, recoveryCalls.size)
        } finally {
            engine.release.countDown()
            coordinator.close()
        }
    }
}
