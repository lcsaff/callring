package com.greatwall.callring.application

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.saturatedAdd
import com.greatwall.callring.domain.model.saturatedSubtract
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
        throw BackendFailure(
            ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "calendar.sync_unavailable", retryable = true),
        )
    },
    internal val coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    internal val commandContext: CommandContext? = null,
    internal val decisions: AtomicReference<DecisionSnapshot?> = AtomicReference(null),
    internal val coreTransactions: com.greatwall.callring.application.ports.store.CoreUnitOfWork,
) {
    private fun patchCalendarHistory(
        history: com.greatwall.callring.application.ports.store.WorkHistory,
    ) {
        decisions.get()?.let { current ->
            decisions.set(
                DecisionSnapshot.capture(
                    current.inputs.copy(
                        calendarYears = history.calendarYears,
                        holidays = history.holidays,
                        makeupDates = history.makeupDates,
                        overrides = history.overrides,
                    ),
                ),
            )
        }
    }

    private fun patchLeaveHistory(
        history: com.greatwall.callring.application.ports.store.WorkHistory,
    ) {
        decisions.get()?.let { current ->
            decisions.set(
                DecisionSnapshot.capture(
                    current.inputs.copy(
                        leaves = history.leaves,
                        leaveVersion = history.leaveVersion,
                    ),
                ),
            )
        }
    }

    internal val callerQueries =
        com.greatwall.callring.application.callers.CallerQueryService(
            coreTransactions,
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            ::runtimeStatus,
        )
    internal val callerCommands =
        com.greatwall.callring.application.callers.CallerCommandService(
            coreTransactions,
            coordinator,
            dispatcher,
            { number, enabled ->
                decisions.get()?.let { current ->
                    val whitelist =
                        if (enabled) current.inputs.whitelistedNumbers + number
                        else current.inputs.whitelistedNumbers - number
                    decisions.set(
                        DecisionSnapshot.capture(
                            current.inputs.copy(whitelistedNumbers = whitelist),
                        ),
                    )
                }
            },
            commandContext,
        )
    internal val callersApi =
        com.greatwall.callring.application.callers.callersApi(callerQueries, callerCommands)
    internal val calendarService =
        com.greatwall.callring.application.settings.CalendarService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            calendarSource,
            com.greatwall.callring.application.ports.CalendarDocumentReader(
                documents::readCalendar,
            ),
            ::patchCalendarHistory,
            commandContext,
        )
    internal val leaveService =
        com.greatwall.callring.application.settings.LeaveService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            ::patchLeaveHistory,
            commandContext,
        )
    internal val workScheduleService =
        com.greatwall.callring.application.settings.WorkScheduleService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            { schedule ->
                decisions.get()?.let { current ->
                    decisions.set(
                        DecisionSnapshot.capture(
                            current.inputs.copy(schedules = current.inputs.schedules + schedule),
                        ),
                    )
                }
            },
            commandContext,
        )
    internal val scoreService =
        com.greatwall.callring.application.settings.ScoreSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            commandContext,
        )
    internal val appearanceService =
        com.greatwall.callring.application.settings.AppearanceService(
            preferences,
            coordinator,
            dispatcher,
            commandContext,
        )
    internal val overlayService =
        com.greatwall.callring.application.settings.OverlaySettingsService(
            coreTransactions,
            { runtimeProbe.getStatus().capabilities[Capability.OVERLAY] ?: AccessState.UNKNOWN },
            coordinator,
            dispatcher,
            { enabled ->
                decisions.get()?.let { current ->
                    decisions.set(
                        DecisionSnapshot.capture(current.inputs.copy(overlayEnabled = enabled)),
                    )
                }
            },
            commandContext,
        )
    internal val statisticsService =
        com.greatwall.callring.application.settings.StatisticsSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            clock,
            syncScheduler,
            { change ->
                decisions.get()?.let { current ->
                    val intervals =
                        if (change.before == null) current.inputs.statisticsIntervals + change.after
                        else
                            current.inputs.statisticsIntervals.map {
                                if (it == change.before) change.after else it
                            }
                    decisions.set(
                        DecisionSnapshot.capture(
                            current.inputs.copy(statisticsIntervals = intervals),
                        ),
                    )
                }
            },
            commandContext,
        )
    internal val positionService =
        com.greatwall.callring.application.settings.PositionService(
            coreTransactions,
            coordinator,
            dispatcher,
            commandContext,
        )
    internal val summaryService =
        com.greatwall.callring.application.settings.SettingsSummaryService(
            coreTransactions,
            preferences,
            coordinator,
            dispatcher,
            clock,
        )

    fun bound(command: CommandContext) =
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
            command,
            decisions,
            coreTransactions,
        )

    internal val quietService =
        com.greatwall.callring.application.settings.QuietSettingsService(
            coreTransactions,
            coordinator,
            dispatcher,
            { quiet ->
                decisions.get()?.let { current ->
                    decisions.set(DecisionSnapshot.capture(current.inputs.copy(quiet = quiet)))
                }
            },
            commandContext,
        )

    fun observe(): Flow<DataChange> = coordinator.observe()

    fun checkNumber(raw: String): NumberCheck {
        val identity = com.greatwall.callring.domain.model.NumberIdentity.parse(raw)
        return NumberCheck(identity.normalized, identity.validProfileNumber, identity.matchesRule)
    }

    internal val incomingCalls =
        com.greatwall.callring.application.calling.IncomingCallService(
            coreTransactions,
            coordinator,
            commandContext,
        )
    internal val callLogImports =
        com.greatwall.callring.application.calling.CallLogImportService(
            coreTransactions,
            coordinator,
            commandContext,
        )
    internal val decisionQueries =
        com.greatwall.callring.application.calling.DecisionQueryService(
            coreTransactions,
            coreTransactions,
            coordinator,
            decisions,
        )

    internal fun runtimeStatus(): RuntimeStatus = completeRuntimeStatus(runtimeProbe.getStatus())
}
