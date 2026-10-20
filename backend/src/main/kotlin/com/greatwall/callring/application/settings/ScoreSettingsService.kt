package com.greatwall.callring.application.settings

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.ScoreTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.ScoreSettings
import com.greatwall.callring.domain.model.SettingsRules
import kotlinx.coroutines.*

internal class ScoreSettingsService(private val transactions: ScoreTransactions,
    private val coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator)
    suspend fun get() = runner.io { transactions.readScore { it.score().toContract() } }
    suspend fun save(value: ScoreSettings): ApiResult<ScoreSettings> {
        currentCoroutineContext().ensureActive()
        SettingsRules.score(value.toDomain()).firstOrNull()?.let {
            return ApiResult.Failure(ApiError(ErrorCode.VALIDATION, it.message, mapOf(it.path to it.message)))
        }
        return update { value }
    }
    suspend fun reset() = save(ScoreSettings(5, 2, 10, 30))
    suspend fun edit(base: ScoreSettings, value: ScoreSettings) = update { current ->
        ScoreSettings(mergeSetting("offHoursWeight", base.offHoursWeight, value.offHoursWeight, current.offHoursWeight),
            mergeSetting("repeatWeight", base.repeatWeight, value.repeatWeight, current.repeatWeight),
            mergeSetting("repeatMinutes", base.repeatMinutes, value.repeatMinutes, current.repeatMinutes),
            mergeSetting("observationDays", base.observationDays, value.observationDays, current.observationDays))
    }
    private suspend fun update(desired: (ScoreSettings) -> ScoreSettings) = runner.write {
        committer.commit(command, {
            transactions.writeScore { writer ->
                val value = desired(writer.score().toContract())
                SettingsRules.score(value.toDomain()).firstOrNull()?.let { settingFailure(ErrorCode.VALIDATION, it.message, it.path) }
                val changed = writer.saveScore(value.toDomain())
                MutationDelta(value, if (changed) setOf("score") else emptySet(),
                    if (changed) ChangeImpact.wide(setOf(DataDomain.SCORE_SETTINGS)) else ChangeImpact(emptyMap()))
            }
        }) {}
    }
}

internal fun settingFailure(code: ErrorCode, message: String, field: String? = null): Nothing =
    throw BackendFailure(ApiError(code, message, if (field == null) emptyMap() else mapOf(field to message)))
internal fun <T> mergeSetting(field: String, base: T, desired: T, current: T): T {
    if (base == desired) return current
    if (base != current) settingFailure(ErrorCode.CONFLICT, "draft.field_changed", field)
    return desired
}
