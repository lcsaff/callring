package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Small composition root; capabilities share one transaction/revision coordinator. */
class CallringBackend(
    store: BackendStore,
    preferences: PreferenceStore,
    documents: DocumentStore,
    runtimeProbe: RuntimeProbe,
    audio: AudioController,
    syncScheduler: SyncScheduler,
    clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    calendarSource: CalendarSource = CalendarSource {
        throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "calendar.sync_unavailable", retryable = true))
    },
) : CallringUiApi {
    private val context =
        BackendContext(
            store,
            preferences,
            documents,
            runtimeProbe,
            audio,
            syncScheduler,
            clock,
            dispatcher,
            calendarSource,
        )
    override val home: HomeApi = createHomeApi(context)
    override val callers: CallersApi = createCallersApi(context)
    override val reports: ReportsApi = createReportsApi(context)
    override val settings: SettingsApi = createSettingsApi(context)
    override val ringtone: RingtoneApi = createRingtoneApi(context)
    override val runtime: RuntimeApi = createRuntimeApi(context)
    override val widgets: WidgetBindingsApi = createWidgetBindingsApi(context)
    override val changes: ChangeFeed =
        object : ChangeFeed {
            override fun observe(): Flow<DataChange> = context.observe()
        }

    override fun checkNumber(raw: String): NumberCheck = context.checkNumber(raw)

    fun refreshDecisionSnapshot(): DecisionSnapshot = context.refreshDecisionSnapshot()

    fun decisionSnapshot(): DecisionSnapshot = context.decisionSnapshot()

    @JvmOverloads
    fun recordIncoming(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String? = null,
        originalFacts: ClassificationFact? = null,
    ): Long = context.recordIncoming(number, at, decision, account, originalFacts)

    fun updateDecision(id: Long, decision: CallDecision) = context.updateDecision(id, decision)

    fun importSystemCalls(records: List<SystemCallRecord>): Set<String> =
        context.importSystemCalls(records)

    fun statisticsStart(): Long = context.statisticsStart()

    fun hasRecentPending(nowEpochMillis: Long): Boolean = context.hasRecentPending(nowEpochMillis)
}
