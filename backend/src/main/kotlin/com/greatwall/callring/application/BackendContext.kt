package com.greatwall.callring.application

import com.greatwall.callring.domain.model.saturatedAdd
import com.greatwall.callring.domain.model.saturatedSubtract
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
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
    internal val coreTransactions: com.greatwall.callring.application.ports.store.CoreUnitOfWork,
) {
    private fun patchCalendarHistory(history: com.greatwall.callring.application.ports.store.WorkHistory) {
        decisions.get()?.let { current -> decisions.set(DecisionSnapshot.capture(current.inputs.copy(
            calendarYears = history.calendarYears, holidays = history.holidays, makeupDates = history.makeupDates, overrides = history.overrides))) }
    }
    private fun patchLeaveHistory(history: com.greatwall.callring.application.ports.store.WorkHistory) {
        decisions.get()?.let { current -> decisions.set(DecisionSnapshot.capture(current.inputs.copy(leaves = history.leaves, leaveVersion = history.leaveVersion))) }
    }
    internal val callerQueries = com.greatwall.callring.application.callers.CallerQueryService(
        coreTransactions, coreTransactions, coordinator, dispatcher, clock, ::runtimeStatus,
    )
    internal val callerCommands = com.greatwall.callring.application.callers.CallerCommandService(
        coreTransactions,
        coordinator,
        dispatcher,
        { number, enabled ->
            decisions.get()?.let { current ->
                val whitelist = if (enabled) current.inputs.whitelistedNumbers + number
                    else current.inputs.whitelistedNumbers - number
                decisions.set(DecisionSnapshot.capture(current.inputs.copy(whitelistedNumbers = whitelist)))
            }
        },
        commandContext,
    )
    internal val callersApi = com.greatwall.callring.application.callers.callersApi(callerQueries, callerCommands)
    internal val calendarService = com.greatwall.callring.application.settings.CalendarService(coreTransactions, coordinator, dispatcher, clock, calendarSource,
        com.greatwall.callring.application.ports.CalendarDocumentReader(documents::readCalendar), ::patchCalendarHistory, commandContext)
    internal val leaveService = com.greatwall.callring.application.settings.LeaveService(coreTransactions, coordinator, dispatcher, clock, ::patchLeaveHistory, commandContext)
    internal val workScheduleService = com.greatwall.callring.application.settings.WorkScheduleService(coreTransactions, coordinator, dispatcher, clock, { schedule ->
        decisions.get()?.let { current -> decisions.set(DecisionSnapshot.capture(current.inputs.copy(schedules = current.inputs.schedules + schedule))) }
    }, commandContext)
    internal val scoreService = com.greatwall.callring.application.settings.ScoreSettingsService(coreTransactions, coordinator, dispatcher, commandContext)
    internal val appearanceService = com.greatwall.callring.application.settings.AppearanceService(preferences, coordinator, dispatcher, commandContext)
    internal val overlayService = com.greatwall.callring.application.settings.OverlaySettingsService(coreTransactions, { runtimeProbe.getStatus().capabilities[Capability.OVERLAY] ?: AccessState.UNKNOWN }, coordinator, dispatcher, { enabled ->
        decisions.get()?.let { current -> decisions.set(DecisionSnapshot.capture(current.inputs.copy(overlayEnabled = enabled))) }
    }, commandContext)
    internal val statisticsService = com.greatwall.callring.application.settings.StatisticsSettingsService(coreTransactions, coordinator, dispatcher, clock, syncScheduler, { change ->
        decisions.get()?.let { current ->
            val intervals = if (change.before == null) current.inputs.statisticsIntervals + change.after
                else current.inputs.statisticsIntervals.map { if (it == change.before) change.after else it }
            decisions.set(DecisionSnapshot.capture(current.inputs.copy(statisticsIntervals = intervals)))
        }
    }, commandContext)
    internal val positionService = com.greatwall.callring.application.settings.PositionService(coreTransactions, coordinator, dispatcher, commandContext)
    internal val summaryService = com.greatwall.callring.application.settings.SettingsSummaryService(coreTransactions, preferences, coordinator, dispatcher, clock)
    internal val lock = coordinator.fence
    internal val revision get() = coordinator.changes.value.dataRevision

    fun bound(command: CommandContext) = BackendContext(store, preferences, documents, runtimeProbe, audio, syncScheduler, clock, dispatcher, calendarSource, coordinator, command, decisions, coreTransactions)
    internal val quietService = com.greatwall.callring.application.settings.QuietSettingsService(coreTransactions, coordinator, dispatcher, { quiet ->
        decisions.get()?.let { current -> decisions.set(DecisionSnapshot.capture(current.inputs.copy(quiet = quiet))) }
    }, commandContext)

    fun observe(): Flow<DataChange> =
        coordinator.observe()

    fun checkNumber(raw: String): NumberCheck {
        val identity = com.greatwall.callring.domain.model.NumberIdentity.parse(raw)
        return NumberCheck(identity.normalized, identity.validProfileNumber, identity.matchesRule)
    }

    private val runner = com.greatwall.callring.application.coordination.UseCaseRunner(dispatcher)

    internal suspend fun <T> io(block: () -> T): ApiResult<T> = runner.io(block)

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
    private fun <T> coreTransaction(block:(BackendState)->T):T {
        val committed=store.transaction{state->
            val before=coreScopeValues(state)
            val result=block(state)
            result to changedScopeKeys(before,coreScopeValues(state))
        }
        coordinator.publishScopes(committed.second)
        return committed.first
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
                coreTransaction { s ->
                    if (
                        !checkNumber(normalized).matchesRule ||
                            s.statisticsIntervals.none {
                                at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                            }
                    )
                        return@coreTransaction -1L
                    val knownAccount = account?.trim()?.takeIf { it.isNotEmpty() }
                    val key = "$normalized:$at" + (knownAccount?.let { ":$it" } ?: "")
                    s.calls.values
                        .firstOrNull { it.screeningKey == key }
                        ?.let {
                            return@coreTransaction it.id
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
                            CallResult.PENDING.toDomain(),
                            decision.toDomain(),
                            CallSource.SCREENING.toDomain(),
                            null,
                            key,
                            true,
                            current,
                            originalFacts?.toDomain() ?: current,
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
                coreTransaction { s ->
                    val call = s.calls[id] ?: return@coreTransaction false
                    if (call.decision == decision.toDomain()) return@coreTransaction false
                    s.calls[id] = call.copy(decision = decision.toDomain(), legacyDecision = null)
                    true
                }
            if (changed) notifyChanged()
        }
    }

    fun importSystemCalls(records: List<SystemCallRecord>): Set<String> =
        synchronized(lock) {
            val changed =
                coreTransaction { s ->
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
                                    CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                                    CallSource.SYSTEM_CALL_LOG.toDomain(),
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
    internal suspend fun <T> write(block: () -> T): ApiResult<T> = runner.write(block)

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
