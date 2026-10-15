package com.greatwall.callring.platform.calling

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.IncomingDecision
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class ScreeningControllerTest {
    @Test
    fun inspectionDeadlineAllowsAt800MillisAndLateSessionIsFinalizedAsFallback() {
        val rig = Rig()
        rig.begin()
        rig.clock.advanceTo(799)
        assertTrue(rig.responses().isEmpty())
        rig.clock.advanceTo(800)
        assertEquals(listOf(false), rig.responses())
        rig.calls.runNext()
        rig.clock.runCurrent()
        assertEquals(listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
        assertEquals(0, rig.audio.prepareCount)
        assertEquals(1, rig.calls.shown.size)
        assertEquals(listOf(false), rig.responses())
    }

    @Test
    fun delayedMainLoopCannotAcceptInspectionAfterItsDeadline() {
        val rig = Rig()
        rig.begin()
        rig.calls.runNext()
        rig.clock.elapseWithoutRunning(801)
        rig.clock.runCurrent()
        assertEquals(listOf(false), rig.responses())
        assertEquals(listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
        assertEquals(0, rig.audio.prepareCount)
    }

    @Test
    fun preparationDeadlineAllowsAt1800MillisAndLateReadyCannotSilenceOrCommit() {
        val rig = Rig()
        rig.prepare()
        rig.clock.advanceTo(1799)
        assertTrue(rig.responses().isEmpty())
        rig.clock.advanceTo(1800)
        assertEquals(listOf(false), rig.responses())
        rig.audio.ready(true)
        rig.clock.runCurrent()
        assertEquals(0, rig.calls.rechecks)
        assertEquals(0, rig.audio.commits)
        assertTrue(rig.audio.cancels >= 1)
        assertEquals(listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
    }

    @Test
    fun readyRecheckUsesOriginalDeadlineEvenWhenItsLateResultWouldBeQuiet() {
        val rig = Rig()
        rig.prepare()
        rig.clock.advanceTo(1700)
        rig.audio.ready(true)
        rig.clock.runCurrent()
        rig.calls.latest = assessment(silence = true)
        rig.clock.advanceTo(1800)
        assertEquals(listOf(false), rig.responses())
        rig.calls.runNext()
        rig.clock.runCurrent()
        assertEquals(listOf(false), rig.responses())
        assertEquals(0, rig.audio.commits)
        assertEquals(listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
    }

    @Test
    fun prepareFailureNegativeReadyAndCommitFailurePreserveSystemRinging() {
        for (failure in listOf("prepare", "ready", "commit")) {
            val rig = Rig()
            if (failure == "prepare") rig.audio.prepareFailure = IllegalStateException("restricted")
            if (failure == "commit") rig.audio.commitResult = false
            rig.prepare()
            if (failure != "prepare") {
                rig.audio.ready(failure != "ready")
                rig.clock.runCurrent()
                if (failure == "commit") {
                    rig.calls.runNext()
                    rig.clock.runCurrent()
                }
            }
            assertEquals(failure, listOf(false), rig.responses())
            assertEquals(failure, listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
            assertTrue(failure, rig.audio.cancels >= 1)
        }
    }

    @Test
    fun latestQuietCancelsPreparedAudioAndSilencesWithoutCheckingAudioAgain() {
        val rig = Rig()
        rig.prepare()
        rig.calls.latest = assessment(silence = true)
        rig.audio.canReplace = false
        rig.audio.ready(true)
        rig.clock.runCurrent()
        rig.calls.runNext()
        rig.clock.runCurrent()
        assertEquals(listOf(true), rig.responses())
        assertEquals(listOf(CallDecision.QUIET_SILENCED), rig.outcomes())
        assertEquals(1, rig.audio.eligibilityReads)
        assertEquals(0, rig.audio.commits)
        assertEquals(1, rig.audio.cancels)
    }

    @Test
    fun quietAndUnmatchedCallsNeverLoadAudioSettings() {
        for (initial in listOf(assessment(silence = true), assessment(matched = false))) {
            val rig = Rig()
            rig.calls.initial["session"] = initial
            rig.audio.onRead = { throw AssertionError("audio settings must not be read") }
            rig.prepare()
            assertEquals(0, rig.audio.settingsReads)
            assertEquals(0, rig.audio.eligibilityReads)
            assertEquals(0, rig.audio.prepareCount)
            assertEquals(listOf(initial.silence), rig.responses())
            assertEquals(
                listOf(
                    if (initial.silence) CallDecision.QUIET_SILENCED
                    else CallDecision.NORMAL_RINGING
                ),
                rig.outcomes(),
            )
        }
    }

    @Test
    fun quietCallRespondsWhileAnotherCallsAudioSettingsReadHasNotReturned() {
        val rig = Rig()
        rig.calls.initial["quiet"] = assessment(silence = true)
        rig.audio.onRead = {
            rig.begin("quiet")
            rig.calls.runNext()
            rig.clock.runCurrent()
            assertEquals(listOf(true), rig.responses("quiet"))
            assertTrue(rig.responses().isEmpty())
            true
        }
        rig.begin()
        rig.calls.runNext()
        rig.clock.runCurrent()
        assertEquals(1, rig.audio.settingsReads)
        assertEquals(listOf(CallDecision.QUIET_SILENCED), rig.outcomes("quiet"))
        assertEquals(1, rig.audio.prepareCount)
    }

    @Test
    fun audioSettingsExceptionStillCompletesTheInspectedSession() {
        val rig = Rig()
        rig.audio.onRead = { throw IllegalStateException("preferences unavailable") }
        rig.prepare()
        assertEquals(listOf(false), rig.responses())
        assertEquals(listOf(CallDecision.NORMAL_RINGING), rig.outcomes())
        assertEquals(0, rig.audio.prepareCount)
        assertEquals(1, rig.calls.shown.size)
    }

    @Test
    fun successfulCommitHasOneResponseAndFinalFactEvenIfUiThrowsOrReadyRepeats() {
        val rig = Rig()
        rig.calls.latest = assessment(whitelisted = true)
        rig.calls.showFailure = IllegalStateException("window denied")
        rig.prepare()
        rig.audio.ready(true)
        rig.audio.ready(true)
        rig.clock.runCurrent()
        assertEquals(1, rig.calls.work.size)
        rig.calls.runNext()
        rig.clock.runCurrent()
        rig.audio.ready(true)
        rig.clock.advanceTo(5000)
        assertEquals(listOf(true), rig.responses())
        assertEquals(listOf(CallDecision.CUSTOM_RINGTONE), rig.outcomes())
        assertEquals(1, rig.audio.commits)
        assertEquals(0, rig.audio.cancels)
        assertEquals(1, rig.calls.rechecks)
        assertEquals(1, rig.calls.shown.size)
    }

    @Test
    fun failedResponseTransportCancelsCommittedAudioAndStillFinalizesExactlyOnce() {
        val rig = Rig()
        rig.begin(onResponse = { throw IllegalStateException("telecom response failed") })
        rig.calls.runNext()
        rig.clock.runCurrent()
        rig.audio.ready(true)
        rig.clock.runCurrent()
        rig.calls.runNext()
        rig.clock.runCurrent()
        assertEquals(listOf(true), rig.responses())
        assertEquals(1, rig.audio.commits)
        assertEquals(1, rig.audio.cancels)
        assertEquals(listOf(CallDecision.SYSTEM_RINGTONE_FALLBACK), rig.outcomes())
        assertEquals(1, rig.calls.shown.size)
    }

    @Test
    fun executorOrPolicyFailuresAllowExactlyOnceAndNeverPrepareAudio() {
        for (reject in listOf(true, false)) {
            val rig = Rig()
            rig.calls.rejectExecution = reject
            rig.calls.inspectFailure = IllegalStateException("policy unavailable")
            rig.begin()
            if (!reject) rig.calls.runNext()
            rig.clock.advanceTo(5000)
            assertEquals(listOf(false), rig.responses())
            assertEquals(0, rig.audio.prepareCount)
            assertTrue(rig.outcomes().isEmpty())
        }
    }

    private class Rig {
        val clock = VirtualScheduler()
        val calls = FakeCalls()
        val audio = FakePlayback()
        val controller = ScreeningController(clock, audio, calls)
        val received = mutableListOf<Pair<String, Boolean>>()

        fun begin(id: String = "session", onResponse: (Boolean) -> Unit = {}) {
            controller.begin("31234", 1000, "account", id, "token-$id") { silence ->
                received += id to silence
                onResponse(silence)
            }
        }

        fun prepare() {
            begin()
            calls.runNext()
            clock.runCurrent()
        }

        fun responses(id: String = "session") = received.filter { it.first == id }.map { it.second }

        fun outcomes(id: String = "session") =
            calls.completed.filter { it.first == id }.map { it.second }
    }

    private class FakeCalls : IncomingCallOperations {
        val work = ArrayDeque<Runnable>()
        val initial = mutableMapOf<String, IncomingDecision>()
        var latest = assessment()
        var rejectExecution = false
        var inspectFailure: Exception? = null
        var showFailure: Exception? = null
        var rechecks = 0
        val completed = mutableListOf<Pair<String, CallDecision>>()
        val shown = mutableListOf<Pair<String, CallDecision>>()

        override fun execute(task: Runnable) {
            if (rejectExecution) throw IllegalStateException("executor closed")
            work.addLast(task)
        }

        fun runNext() {
            work.removeFirst().run()
        }

        override fun inspect(
            sessionId: String,
            raw: String,
            at: Long,
            account: String?,
        ): IncomingCallSession {
            inspectFailure?.let { throw it }
            return IncomingCallSession(
                sessionId,
                raw,
                at,
                initial[sessionId] ?: assessment(),
                CompletableFuture.completedFuture(1L),
            )
        }

        override fun inspectAgain(session: IncomingCallSession): IncomingDecision {
            rechecks++
            return latest
        }

        override fun complete(
            session: IncomingCallSession,
            decision: CallDecision,
        ): CompletableFuture<Unit> {
            completed += session.sessionId to decision
            return CompletableFuture.completedFuture(Unit)
        }

        override fun show(
            session: IncomingCallSession,
            decision: CallDecision,
            latest: IncomingDecision,
        ) {
            shown += session.sessionId to decision
            showFailure?.let { throw it }
        }
    }

    private class FakePlayback : ScreeningPlayback {
        var settingsReads = 0
        var eligibilityReads = 0
        var prepareCount = 0
        var commits = 0
        var cancels = 0
        var canReplace = true
        var commitResult = true
        var prepareFailure: Exception? = null
        var onRead: (() -> Boolean)? = null
        private var callback: ((Boolean) -> Unit)? = null

        override fun readEnabled(): Boolean {
            settingsReads++
            return onRead?.invoke() ?: true
        }

        override fun canReplace(): Boolean {
            eligibilityReads++
            return canReplace
        }

        override fun prepare(token: String, ready: (Boolean) -> Unit) {
            prepareCount++
            callback = ready
            prepareFailure?.let { throw it }
        }

        override fun commit(token: String): Boolean {
            commits++
            return commitResult
        }

        override fun cancel(token: String) {
            cancels++
        }

        fun ready(success: Boolean) {
            checkNotNull(callback)(success)
        }
    }

    private class VirtualScheduler : ScreeningScheduler {
        private data class Scheduled(
            val at: Long,
            val sequence: Long,
            val task: Runnable,
            var cancelled: Boolean = false,
        )

        private val tasks =
            PriorityQueue<Scheduled>(compareBy<Scheduled> { it.at }.thenBy { it.sequence })
        private var time = 0L
        private var sequence = 0L

        override fun nowMillis(): Long = time

        override fun post(task: Runnable) {
            schedule(0, task)
        }

        override fun after(delayMillis: Long, task: Runnable): ScreeningCancellation =
            schedule(delayMillis, task)

        private fun schedule(delay: Long, task: Runnable): ScreeningCancellation {
            val item = Scheduled(time + delay, sequence++, task)
            tasks += item
            return ScreeningCancellation { item.cancelled = true }
        }

        fun runCurrent() = advanceTo(time)

        fun elapseWithoutRunning(delay: Long) {
            time += delay
        }

        fun advanceTo(target: Long) {
            require(target >= time)
            while (true) {
                val task = tasks.peek() ?: break
                if (task.at > target) break
                tasks.remove()
                time = maxOf(time, task.at)
                if (!task.cancelled) task.task.run()
            }
            time = maxOf(time, target)
        }
    }

    companion object {
        private fun assessment(
            matched: Boolean = true,
            silence: Boolean = false,
            whitelisted: Boolean = false,
        ) =
            IncomingDecision(
                matched,
                whitelisted,
                silence,
                true,
                true,
                ClassificationFact(false, WorkReason.REST_DAY, null, true, "UTC", 1, "fixture", 0),
            )
    }
}
