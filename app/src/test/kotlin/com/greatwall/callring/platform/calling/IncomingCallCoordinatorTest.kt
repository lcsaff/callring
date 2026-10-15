package com.greatwall.callring.platform.calling

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.IncomingDecision
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IncomingCallCoordinatorTest {
    private val fact = ClassificationFact(false, WorkReason.REST_DAY, null, true, "UTC", 1, "v1", 0)

    private inner class Engine(private val failRecord: Boolean = false) : IncomingCallEngine {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CopyOnWriteArrayList<Pair<Long, CallDecision>>()

        override fun normalize(raw: String) = raw

        override fun evaluate(number: String, at: Long) =
            IncomingDecision(true, false, true, true, true, fact)

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
}
