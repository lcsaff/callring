package com.greatwall.callring.application

import com.greatwall.callring.application.capability.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
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
        throw BackendFailure(
            ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "calendar.sync_unavailable", retryable = true),
        )
    },
    val coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    coreTransactions: com.greatwall.callring.application.ports.store.CoreUnitOfWork,
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
            coordinator,
            coreTransactions = coreTransactions,
        )
    override val conversation: ConversationApi = UnavailableConversationApi
    override val aiSettings: AiSettingsApi = UnavailableAiSettingsApi
    override val update: AppUpdateApi = UnavailableAppUpdateApi
    override val snapshots: AiSnapshotApi = UnavailableAiSnapshotApi
    override val recordings: RecordingApi = UnavailableRecordingApi
    override val home: HomeApi =
        com.greatwall.callring.application.home.HomeService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            { completeRuntimeStatus(runtimeProbe.getStatus()) },
            audio::getSettings,
        )
    override val callers: CallersApi = context.callersApi
    override val reports: ReportsApi =
        com.greatwall.callring.application.report.ReportService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            { completeRuntimeStatus(runtimeProbe.getStatus()) },
            { destination, write -> documents.write(destination, write) },
        )
    override val settings: SettingsApi = createSettingsApi(context)
    override val edits: UiEditsApi = createUiEditsApi(context)
    private val ringtoneService = com.greatwall.callring.application.ringtone.RingtoneService(audio, runtimeProbe::getStatus, coordinator, dispatcher)
    private val runtimeService = com.greatwall.callring.application.runtime.RuntimeService(runtimeProbe::getStatus, runtimeProbe::getDiagnostics, syncScheduler, clock, dispatcher, coordinator)
    private val widgetService = com.greatwall.callring.application.widget.WidgetBindingsService(preferences, runtimeProbe::getInstalledWidgets, coordinator, dispatcher)
    override val ringtone: RingtoneApi = ringtoneService
    override val runtime: RuntimeApi = runtimeService
    override val widgets: WidgetBindingsApi = widgetService
    override val numberDeletion: NumberDeletionApi = CoreNumberDeletionApi(callers, widgets)
    override val changes: ChangeFeed =
        object : ChangeFeed {
            override fun observe(): Flow<DataChange> = context.observe()

            override fun snapshots() = coordinator.invalidations.snapshots
        }

    override fun checkNumber(raw: String): NumberCheck = context.checkNumber(raw)

    override fun commandApi(context: CommandContext): CallringUiApi {
        val bound = this.context.bound(context)
        val deletion = this.context.bound(context.copy(continueOwnCommits = true))
        return object : CallringUiApi by this {
            override val callers = bound.callersApi
            override val settings = createSettingsApi(bound)
            override val edits = createUiEditsApi(bound)
            override val widgets = widgetService.bound(context)
            override val numberDeletion =
                CoreNumberDeletionApi(deletion.callersApi, widgetService.bound(context.copy(continueOwnCommits = true)))
            override val ringtone = ringtoneService.bound(context)
            override val runtime = runtimeService.bound(context)
        }
    }

    fun refreshDecisionSnapshot(): DecisionSnapshot = context.decisionQueries.refresh()

    fun decisionSnapshot(): DecisionSnapshot = context.decisionQueries.snapshot()

    @JvmOverloads
    fun recordIncoming(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String? = null,
        originalFacts: ClassificationFact? = null,
    ): Long = context.incomingCalls.record(number, at, decision, account, originalFacts)

    fun updateDecision(id: Long, decision: CallDecision) =
        context.incomingCalls.updateDecision(id, decision)

    fun importSystemCalls(records: List<SystemCallRecord>): Set<String> =
        context.callLogImports.import(records)

    fun statisticsStart(): Long = context.decisionQueries.statisticsStart()

    fun hasRecentPending(nowEpochMillis: Long): Boolean =
        context.decisionQueries.hasRecentPending(nowEpochMillis)

}
