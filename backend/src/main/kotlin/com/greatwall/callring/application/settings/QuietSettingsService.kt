package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.model.SettingsRules
import kotlinx.coroutines.*

/** A single business entry for direct, command-bound and draft edits. */
internal class QuietSettingsService(private val transactions: QuietTransactions,
    private val coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher,
    private val afterQuietCommit: (com.greatwall.callring.domain.model.QuietSettings) -> Unit,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun get(): ApiResult<QuietSettings> = runner.io { requireOperationActive(operationActive);
        repeat(3) {
            val before = coordinator.marker()
            val value = transactions.readQuiet { it.quiet().settings.toContract() }
            if (coordinator.unchanged(setOf("quiet", "core"), before)) return@io value
        }
        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    }
    suspend fun save(value: QuietSettings): ApiResult<QuietSettings> {
        currentCoroutineContext().ensureActive()
        // Validation is pure and runs before acquiring the SQL lease.
        val invalid = SettingsRules.quiet(value.toDomain()).firstOrNull()
        if (invalid != null) return ApiResult.Failure(ApiError(ErrorCode.VALIDATION, invalid.message, mapOf(invalid.path to invalid.message)))
        return update { value }
    }
    suspend fun mode(mode: QuietMode) = update { it.settings.toContract().copy(mode = mode) }
    suspend fun enabled(enabled: Boolean) = update { old ->
        val mode = when {
            !enabled -> QuietMode.OFF
            old.settings.mode != com.greatwall.callring.domain.model.QuietMode.OFF -> old.settings.mode.toContract()
            old.lastMode == com.greatwall.callring.domain.model.QuietMode.CUSTOM && old.settings.weeklyWindows.values.any { it.isNotEmpty() } -> QuietMode.CUSTOM
            else -> QuietMode.NON_WORK
        }
        old.settings.toContract().copy(mode = mode)
    }
    suspend fun edit(base: QuietSettings, value: QuietSettings) = update { old ->
        val current = old.settings.toContract()
        fun <T> merge(field: String, before: T, desired: T, actual: T): T {
            if (before == desired) return actual
            if (before != actual) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "draft.field_changed", mapOf(field to "draft.field_changed")))
            return desired
        }
        QuietSettings(merge("mode", base.mode, value.mode, current.mode), merge("allNumbers", base.allNumbers, value.allNumbers, current.allNumbers), merge("weeklyWindows", base.weeklyWindows, value.weeklyWindows, current.weeklyWindows))
    }
    private suspend fun update(desired: (QuietConfiguration) -> QuietSettings): ApiResult<QuietSettings> = runner.write { requireOperationActive(operationActive);
        committer.commit(command, {
            transactions.writeQuiet { writer ->
                val old = writer.quiet()
                val value = desired(old).toDomain()
                SettingsRules.quiet(value).firstOrNull()?.let { throw BackendFailure(ApiError(ErrorCode.VALIDATION, it.message, mapOf(it.path to it.message))) }
                val last = value.mode.takeUnless { it == com.greatwall.callring.domain.model.QuietMode.OFF }
                    ?: old.settings.mode.takeUnless { it == com.greatwall.callring.domain.model.QuietMode.OFF } ?: old.lastMode
                val changed = writer.saveQuiet(QuietConfiguration(value, last))
                MutationDelta(value.toContract(), if (changed) setOf("quiet") else emptySet(),
                    if (changed) ChangeImpact.wide(setOf(DataDomain.QUIET_HOURS)) else ChangeImpact(emptyMap()), changed && old.settings != value)
            }
        }) { delta -> if (delta.decisionInputsChanged) afterQuietCommit(delta.value.toDomain()) }
    }
}
