package com.greatwall.callring.application

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
    private val preferences: PreferenceStore,
    private val documents: DocumentStore,
    private val runtimeProbe: RuntimeProbe,
    private val audio: AudioController,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calendarSource: CalendarSource,
    val coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    private val coreTransactions: com.greatwall.callring.application.ports.store.CoreUnitOfWork,
    private val numberRecordings: com.greatwall.callring.application.callers.NumberDeletionRecordings,
    private val identityArchive: suspend (Long) -> ApiResult<CallArchive?>,
    private val operationActive: () -> Boolean,
    private val persistenceActive: () -> Boolean,
    private val conversationProvider: () -> ConversationApi,
    private val aiSettingsProvider: (CommandContext?) -> AiSettingsApi,
    private val updateProvider: () -> AppUpdateApi,
    private val snapshotsProvider: () -> AiSnapshotApi,
    private val recordingsProvider: (CommandContext?) -> RecordingApi,
    private val decisions: com.greatwall.callring.application.calling.DecisionMirror = com.greatwall.callring.application.calling.DecisionMirror(),
    private val commandContext: CommandContext? = null,
) : CallringUiApi {
    private val callerQueries =
        com.greatwall.callring.application.callers.CallerQueryService(
            coreTransactions,
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            ::runtimeStatus,
        )
    private val callerCommands =
        com.greatwall.callring.application.callers.CallerCommandService(
            coreTransactions,
            coordinator,
            dispatcher,
            decisions::whitelist,
            commandContext, operationActive = operationActive)
    private val callersApi =
        com.greatwall.callring.application.callers.callersApi(callerQueries, callerCommands)
    private val calendarService =
        com.greatwall.callring.application.settings.CalendarService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            calendarSource,
            com.greatwall.callring.application.ports.CalendarDocumentReader(
                documents::readCalendar,
            ),
            decisions::calendar,
            commandContext, operationActive = operationActive)
    private val leaveService =
        com.greatwall.callring.application.settings.LeaveService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            decisions::leaves,
            commandContext, operationActive = operationActive)
    private val workScheduleService =
        com.greatwall.callring.application.settings.WorkScheduleService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            decisions::work,
            commandContext, operationActive = operationActive)
    private val scoreService =
        com.greatwall.callring.application.settings.ScoreSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            commandContext, operationActive = operationActive)
    private val appearanceService =
        com.greatwall.callring.application.settings.AppearanceService(
            preferences,
            coordinator,
            dispatcher,
            commandContext, operationActive = operationActive)
    private val overlayService =
        com.greatwall.callring.application.settings.OverlaySettingsService(
            coreTransactions,
            { runtimeProbe.getStatus().capabilities[Capability.OVERLAY] ?: AccessState.UNKNOWN },
            coordinator,
            dispatcher,
            decisions::overlay,
            commandContext, operationActive = operationActive)
    private val statisticsService =
        com.greatwall.callring.application.settings.StatisticsSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            syncScheduler,
            decisions::statistics,
            commandContext, operationActive = operationActive)
    private val positionService =
        com.greatwall.callring.application.settings.PositionService(
            coreTransactions,
            coordinator,
            dispatcher,
            commandContext, operationActive = operationActive)
    private val summaryService =
        com.greatwall.callring.application.settings.SettingsSummaryService(
            coreTransactions,
            preferences,
            coordinator,
            dispatcher,
            clock,
        )

    private val quietService =
        com.greatwall.callring.application.settings.QuietSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            decisions::quiet,
            commandContext, operationActive = operationActive)

    private val incomingCalls =
        com.greatwall.callring.application.calling.IncomingCallService(
            coreTransactions,
            coordinator,
            commandContext, operationActive = persistenceActive)
    private val callLogImports =
        com.greatwall.callring.application.calling.CallLogImportService(
            coreTransactions,
            coordinator,
            commandContext, operationActive = persistenceActive)
    private val decisionQueries =
        com.greatwall.callring.application.calling.DecisionQueryService(
            coreTransactions,
            coreTransactions,
            coordinator,
            decisions.snapshots,
        )

    private fun runtimeStatus(): RuntimeStatus = completeRuntimeStatus(runtimeProbe.getStatus())
    override val conversation: ConversationApi get() = conversationProvider()
    override val aiSettings: AiSettingsApi get() = aiSettingsProvider(commandContext)
    override val update: AppUpdateApi get() = updateProvider()
    override val snapshots: AiSnapshotApi get() = snapshotsProvider()
    override val recordings: RecordingApi get() = recordingsProvider(commandContext)
    override val home: HomeApi =
        com.greatwall.callring.application.home.HomeService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            { completeRuntimeStatus(runtimeProbe.getStatus()) },
            audio::getSettings,
        )
    override val callers: CallersApi = callersApi
    override val reports: ReportsApi =
        com.greatwall.callring.application.report.ReportService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            { completeRuntimeStatus(runtimeProbe.getStatus()) },
            { destination, write -> documents.write(destination, write) },
        )
    override val settings: SettingsApi = settingsApi()
    override val edits: UiEditsApi = editsApi()
    private val ringtoneService = com.greatwall.callring.application.ringtone.RingtoneService(audio, runtimeProbe::getStatus, coordinator, dispatcher, command = commandContext, operationActive = operationActive)
    private val runtimeService = com.greatwall.callring.application.runtime.RuntimeService(runtimeProbe::getStatus, runtimeProbe::getDiagnostics, syncScheduler, clock, dispatcher, coordinator, command = commandContext, operationActive = operationActive)
    private val widgetService = com.greatwall.callring.application.widget.WidgetBindingsService(preferences, runtimeProbe::getInstalledWidgets, coordinator, dispatcher, command = commandContext, operationActive = operationActive)
    override val ringtone: RingtoneApi = ringtoneService
    override val runtime: RuntimeApi = runtimeService
    override val widgets: WidgetBindingsApi = widgetService
    private fun deletion(command: CommandContext? = commandContext) = com.greatwall.callring.application.callers.NumberDeletionService(
        callers::numberDataImpact,
        { number, count, cmd -> bound(cmd).callers.deleteNumberCore(number, count) },
        numberRecordings, widgetService::bindingsForNumber,
        { binding, cmd -> widgetService.bound(cmd).removeStoredBinding(binding) }, coordinator, command, operationActive)
    private fun adoption(command: CommandContext? = commandContext) = com.greatwall.callring.application.recording.IdentityAdoptionService(
        identityArchive, { callers.getEditor(it) },
        { base, value, cmd -> bound(cmd).edits.profile(base, value) }, coordinator, command, operationActive)
    override val numberDeletion: NumberDeletionApi = deletion()
    override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long) = adoption().adoptIdentity(callId, expectedGeneration)
    override val changes: ChangeFeed =
        object : ChangeFeed {
            override fun observe(): Flow<DataChange> = coordinator.observe()

            override fun snapshots() = coordinator.invalidations.snapshots
        }

    override fun checkNumber(raw: String): NumberCheck {
        val identity = com.greatwall.callring.domain.model.NumberIdentity.parse(raw)
        return NumberCheck(identity.normalized, identity.validProfileNumber, identity.matchesRule)
    }

    private fun settingsApi() = createSettingsApi(summaryService, statisticsService, overlayService,
        quietService, positionService, workScheduleService, calendarService, leaveService, scoreService, appearanceService)
    private fun editsApi() = createUiEditsApi(callerCommands, quietService, workScheduleService,
        scoreService, appearanceService, positionService, leaveService)

    private fun bound(command: CommandContext) = CallringBackend(preferences, documents, runtimeProbe,
        audio, syncScheduler, clock, dispatcher, calendarSource, coordinator, coreTransactions,
        numberRecordings, identityArchive, operationActive, persistenceActive, conversationProvider, aiSettingsProvider,
        updateProvider, snapshotsProvider, recordingsProvider, decisions, command)

    override fun commandApi(context: CommandContext): CallringUiApi = bound(context)

    fun refreshDecisionSnapshot(): DecisionSnapshot = decisionQueries.refresh()

    fun decisionSnapshot(): DecisionSnapshot = decisionQueries.snapshot()

    @JvmOverloads
    fun recordIncoming(
        number: String,
        at: Long,
        decision: CallDecision,
        account: String? = null,
        originalFacts: ClassificationFact? = null,
    ): Long = incomingCalls.record(number, at, decision, account, originalFacts)

    fun updateDecision(id: Long, decision: CallDecision) =
        incomingCalls.updateDecision(id, decision)

    fun importSystemCalls(records: List<SystemCallRecord>): Set<String> =
        callLogImports.import(records)

    fun statisticsStart(): Long = decisionQueries.statisticsStart()

    fun hasRecentPending(nowEpochMillis: Long): Boolean =
        decisionQueries.hasRecentPending(nowEpochMillis)

}
