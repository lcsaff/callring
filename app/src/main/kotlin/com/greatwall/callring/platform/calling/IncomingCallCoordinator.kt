package com.greatwall.callring.platform.calling

import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.IncomingDecision
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Application decisions and persistence are separate so a database writer cannot delay Telecom. */
interface IncomingCallEngine {
    fun normalize(raw: String): String

    fun evaluate(number: String, at: Long): IncomingDecision

    fun record(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String?,
        original: ClassificationFact,
    ): Long

    fun complete(id: Long, decision: CallDecision)
}

class BackendIncomingCallEngine(private val backend: CallringBackend) : IncomingCallEngine {
    override fun normalize(raw: String): String = backend.checkNumber(raw).normalized

    override fun evaluate(number: String, at: Long): IncomingDecision {
        val snapshot =
            try {
                backend.decisionSnapshot()
            } catch (_: IllegalStateException) {
                backend.refreshDecisionSnapshot()
            }
        return snapshot.evaluate(number, at)
    }

    override fun record(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String?,
        original: ClassificationFact,
    ): Long = backend.recordIncoming(number, at, decision, account, original)

    override fun complete(id: Long, decision: CallDecision) = backend.updateDecision(id, decision)
}

class IncomingCallSession
internal constructor(
    val sessionId: String,
    val number: String,
    val at: Long,
    val assessment: IncomingDecision,
    internal val record: CompletableFuture<Long>,
) {
    internal var completion: CompletableFuture<Unit>? = null
    val initialDecision: CallDecision
        get() =
            when {
                assessment.whitelisted -> CallDecision.WHITELIST_ALLOWED
                assessment.silence -> CallDecision.QUIET_SILENCED
                else -> CallDecision.NORMAL_RINGING
            }
}

class IncomingCallCoordinator(
    private val engine: IncomingCallEngine,
    private val presentation: IncomingCallUiSink,
    private val onFailure: () -> Unit,
) : Closeable {
    private val lifetime = Any()
    private var closed = false
    private val decisions: ExecutorService = Executors.newFixedThreadPool(2)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    fun execute(task: Runnable) =
        synchronized(lifetime) {
            check(!closed) { "backend.closed" }
            decisions.execute(task)
        }

    fun inspect(sessionId: String, raw: String, at: Long, account: String?): IncomingCallSession {
        val number = engine.normalize(raw)
        val assessment = engine.evaluate(number, at)
        val initial =
            when {
                assessment.whitelisted -> CallDecision.WHITELIST_ALLOWED
                assessment.silence -> CallDecision.QUIET_SILENCED
                else -> CallDecision.NORMAL_RINGING
            }
        val record =
            synchronized(lifetime) {
                check(!closed) { "backend.closed" }
                if (!assessment.statisticsEnabled) CompletableFuture.completedFuture(-1L)
                else
                    tracked(
                        CompletableFuture.supplyAsync(
                            {
                                engine.record(
                                    number,
                                    at,
                                    initial,
                                    account,
                                    assessment.classification,
                                )
                            },
                            writer,
                        )
                    )
            }
        return IncomingCallSession(sessionId, number, at, assessment, record)
    }

    fun inspectAgain(session: IncomingCallSession): IncomingDecision =
        engine.evaluate(session.number, session.at)

    fun complete(session: IncomingCallSession, decision: CallDecision): CompletableFuture<Unit> =
        synchronized(lifetime) {
            check(!closed) { "backend.closed" }
            synchronized(session) {
                session.completion
                    ?: tracked(
                            session.record.handleAsync(
                                { id, error ->
                                    if (error != null) safeFailure()
                                    else if (id > 0)
                                        try {
                                            engine.complete(id, decision)
                                        } catch (_: Exception) {
                                            safeFailure()
                                        }
                                    Unit
                                },
                                writer,
                            )
                        )
                        .also { session.completion = it }
            }
        }

    fun show(session: IncomingCallSession, decision: CallDecision, latest: IncomingDecision) {
        if (!latest.overlayEnabled || !latest.matched) return
        try {
            presentation.show(
                IncomingCallUiRequest(session.sessionId, session.number, session.at, decision)
            )
        } catch (_: Exception) {
            safeFailure()
        }
    }

    private fun <T> tracked(future: CompletableFuture<T>): CompletableFuture<T> {
        pending.add(future)
        future.whenComplete { _, _ -> pending.remove(future) }
        return future
    }

    private fun safeFailure() {
        try {
            onFailure()
        } catch (_: Exception) {
            /* Diagnostics cannot affect a call. */
        }
    }

    override fun close() {
        val outstanding =
            synchronized(lifetime) {
                if (closed) return
                closed = true
                decisions.shutdownNow()
                pending.toTypedArray()
            }
        try {
            try {
                CompletableFuture.allOf(*outstanding).get(10, TimeUnit.SECONDS)
            } catch (_: ExecutionException) {
                safeFailure()
            }
        } finally {
            writer.shutdown()
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) writer.shutdownNow()
        }
    }
}
