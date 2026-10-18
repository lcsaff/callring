package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.io.IOException
import java.time.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application boundary. All SQL aggregates are computed inside a single storage snapshot. */
internal class BackendContext(
    internal val store: BackendStore,
    internal val preferences: PreferenceStore,
    internal val documents: DocumentStore,
    internal val runtimeProbe: RuntimeProbe,
    internal val audio: AudioController,
    internal val syncScheduler: SyncScheduler,
    internal val clock: Clock = Clock.systemUTC(),
    internal val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val calendarSource: CalendarSource = CalendarSource {
        throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "calendar.sync_unavailable", retryable = true))
    },
    internal val coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    internal val commandContext: CommandContext? = null,
    internal val decisions: AtomicReference<DecisionSnapshot?> = AtomicReference(null),
) {
    internal val lock = coordinator.fence
    internal val revision get() = coordinator.changes.value.dataRevision
    internal val allDomains = frozenSet(DataDomain.values().toSet())
    // Conflation retains a complete invalidation, so slow collectors never lose a changed domain.
    internal val events get() = coordinator.changes

    fun bound(command: CommandContext) = BackendContext(store, preferences, documents, runtimeProbe, audio, syncScheduler, clock, dispatcher, calendarSource, coordinator, command, decisions)

    fun observe(): Flow<DataChange> =
        events.onStart { emit(synchronized(lock) { DataChange(revision, allDomains, null, null) }) }

    fun checkNumber(raw: String): NumberCheck {
        val normalized =
            raw.filterNot {
                it == '-' ||
                    it == '(' ||
                    it == ')' ||
                    it.isWhitespace() ||
                    Character.isSpaceChar(it)
            }
        return NumberCheck(
            normalized,
            normalized.matches(Regex("\\+?[0-9]{1,30}")),
            normalized.matches(Regex("3[0-9]{4}")),
        )
    }

    internal suspend fun <T> io(block: () -> T): ApiResult<T> =
        try {
            withContext(dispatcher) { ApiResult.Success(block()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackendFailure) {
            ApiResult.Failure(failure.error)
        } catch (_: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "io.failure", retryable = true))
        } catch (_: Exception) {
            ApiResult.Failure(
                ApiError(ErrorCode.STORAGE_FAILURE, "storage.failure", retryable = true)
            )
        }

    internal fun fail(code: ErrorCode, message: String, field: String? = null): Nothing =
        throw BackendFailure(
            ApiError(code, message, if (field == null) emptyMap() else mapOf(field to message))
        )

    internal fun requireField(ok: Boolean, field: String, code: String) {
        if (!ok) fail(ErrorCode.VALIDATION, code, field)
    }

    internal fun number(raw: String): String =
        checkNumber(raw).let {
            requireField(it.validProfileNumber, "number", "number.invalid")
            it.normalized
        }

    internal fun page(value: PageRequest) {
        requireField(value.pageIndex >= 0, "page.pageIndex", "page.index.invalid")
        requireField(value.pageSize in 1..200, "page.pageSize", "page.size.invalid")
    }

    internal fun meta() = SnapshotMeta(revision, clock.millis())

    internal fun <T> read(block: (BackendState) -> T): T = synchronized(lock) { store.read(block) }

    internal fun notifyChanged() {
        coordinator.changed("core")
    }

    internal fun <T> mutate(block: (BackendState) -> T): T =
        coordinator.commit("core", commandContext) {
            val committed =
                store.transaction { state -> Pair(block(state), DecisionSnapshot.capture(state)) }
            decisions.set(committed.second)
            committed.first
        }

    fun refreshDecisionSnapshot(): DecisionSnapshot = read {
        DecisionSnapshot.capture(it).also(decisions::set)
    }

    fun decisionSnapshot(): DecisionSnapshot =
        decisions.get() ?: throw IllegalStateException("decision.snapshot.uninitialized")

    @JvmOverloads
    fun recordIncoming(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String? = null,
        originalFacts: ClassificationFact? = null,
    ): Long =
        synchronized(lock) {
            val normalized = checkNumber(number).normalized
            val result =
                store.transaction { s ->
                    if (
                        !checkNumber(normalized).matchesRule ||
                            s.statisticsIntervals.none {
                                at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                            }
                    )
                        return@transaction -1L
                    val knownAccount = account?.trim()?.takeIf { it.isNotEmpty() }
                    val key = "$normalized:$at" + (knownAccount?.let { ":$it" } ?: "")
                    s.calls.values
                        .firstOrNull { it.screeningKey == key }
                        ?.let {
                            return@transaction it.id
                        }
                    s.profiles.putIfAbsent(normalized, ProfileRecord(normalized))
                    val id = s.nextCallId++
                    val current = WorkClassifier.classify(s, at)
                    s.calls[id] =
                        RawCallRecord(
                            id,
                            normalized,
                            at,
                            null,
                            CallResult.PENDING,
                            decision,
                            CallSource.SCREENING,
                            null,
                            key,
                            true,
                            current,
                            originalFacts ?: current,
                            knownAccount,
                        )
                    CallCorrelation.reconcile(s, normalized, at)
                    id
                }
            if (result > 0) notifyChanged()
            result
        }

    fun updateDecision(id: Long, decision: CallDecision) {
        if (id <= 0) return
        synchronized(lock) {
            val changed =
                store.transaction { s ->
                    val call = s.calls[id] ?: return@transaction false
                    if (call.decision == decision) return@transaction false
                    s.calls[id] = call.copy(decision = decision, legacyDecision = null)
                    true
                }
            if (changed) notifyChanged()
        }
    }

    fun importSystemCalls(records: List<SystemCallRecord>): Set<String> =
        synchronized(lock) {
            val changed =
                store.transaction { s ->
                    val numbers = linkedSetOf<String>()
                    val affected = linkedSetOf<Pair<String, Long>>()
                    val byLog =
                        s.calls.values
                            .filter { it.logId != null }
                            .associateBy { it.logId }
                            .toMutableMap()
                    for (record in records) {
                        val n = checkNumber(record.number).normalized
                        if (
                            !checkNumber(n).matchesRule ||
                                s.statisticsIntervals.none {
                                    record.startedAtEpochMillis >= it.startMillis &&
                                        (it.endMillis == null ||
                                            record.startedAtEpochMillis < it.endMillis)
                                }
                        )
                            continue
                        requireField(record.logId.isNotBlank(), "logId", "log_id.required")
                        requireField(
                            record.durationSeconds == null || record.durationSeconds >= 0,
                            "durationSeconds",
                            "duration.invalid",
                        )
                        val previous = byLog[record.logId]
                        val account =
                            record.account?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.account
                        val identity =
                            previous == null ||
                                previous.number != n ||
                                previous.time != record.startedAtEpochMillis ||
                                previous.account != account
                        if (
                            !identity &&
                                previous!!.duration == record.durationSeconds &&
                                previous.result == record.result
                        )
                            continue
                        s.profiles.putIfAbsent(n, ProfileRecord(n))
                        val current =
                            if (
                                previous == null ||
                                    previous.number != n ||
                                    previous.time != record.startedAtEpochMillis
                            )
                                WorkClassifier.classify(s, record.startedAtEpochMillis)
                            else previous.classification
                        val updated =
                            if (previous == null)
                                RawCallRecord(
                                    s.nextCallId++,
                                    n,
                                    record.startedAtEpochMillis,
                                    record.durationSeconds,
                                    record.result,
                                    CallDecision.SYSTEM_LOG_ONLY,
                                    CallSource.SYSTEM_CALL_LOG,
                                    record.logId,
                                    null,
                                    true,
                                    current,
                                    current,
                                    account,
                                )
                            else
                                previous.copy(
                                    number = n,
                                    time = record.startedAtEpochMillis,
                                    duration = record.durationSeconds,
                                    result = record.result,
                                    account = account,
                                    classification = current,
                                    linkedScreenId =
                                        if (identity) null else previous.linkedScreenId,
                                    legacyResult = null,
                                )
                        s.calls[updated.id] = updated
                        byLog[record.logId] = updated
                        numbers += n
                        if (identity) {
                            previous?.let {
                                affected += it.number to it.time
                                numbers += it.number
                            }
                            affected += n to record.startedAtEpochMillis
                        }
                    }
                    // One complete candidate index per number, with only affected windows in the
                    // write set.
                    affected
                        .groupBy { it.first }
                        .forEach { (number, centers) ->
                            CallCorrelation.reconcile(s, number, centers.map { it.second })
                        }
                    frozenSet(numbers)
                }
            if (changed.isNotEmpty()) notifyChanged()
            changed
        }

    fun statisticsStart(): Long = read {
        it.statisticsIntervals.minOfOrNull { interval -> interval.startMillis } ?: 0L
    }

    fun hasRecentPending(nowEpochMillis: Long): Boolean = read { s ->
        val start = saturatedSubtract(nowEpochMillis, 300000)
        val end = saturatedAdd(nowEpochMillis, 1)
        s.calls.values.any {
            !it.hidden && it.counted && it.logId == null && it.time >= start && it.time < end
        }
    }

    /** Once a write starts, commit and its notification survive caller cancellation. */
    internal suspend fun <T> write(block: () -> T): ApiResult<T> {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { io(block) }
    }

    internal fun runtimeStatus(): RuntimeStatus {
        val status = runtimeProbe.getStatus()
        return status.copy(
            capabilities =
                frozenMap(
                    Capability.values().associateWith {
                        status.capabilities[it] ?: AccessState.UNKNOWN
                    }
                )
        )
    }

    internal fun capability(capability: Capability, role: Boolean = false) {
        val access = runtimeStatus().capabilities.getValue(capability)
        if (access != AccessState.GRANTED && access != AccessState.NOT_REQUIRED)
            throw BackendFailure(
                ApiError(
                    if (role) ErrorCode.ROLE_REQUIRED else ErrorCode.PERMISSION_REQUIRED,
                    "capability.required",
                    requiredCapabilities = frozenSet(setOf(capability)),
                )
            )
    }
}
