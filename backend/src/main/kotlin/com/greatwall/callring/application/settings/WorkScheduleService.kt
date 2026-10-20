package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.defaultWorkSchedule
import com.greatwall.callring.domain.model.SettingsRules
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal class WorkScheduleService(private val transactions: WorkScheduleTransactions,
    coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher, private val clock: Clock,
    private val afterCommit: (com.greatwall.callring.domain.model.WorkScheduleSettings) -> Unit,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun get() = runner.io { requireOperationActive(operationActive); transactions.readWorkSchedule { (it.latestWorkSchedule() ?: defaultWorkSchedule()).toContract() } }
    suspend fun save(value: SaveWorkSchedule): ApiResult<WorkScheduleSettings> {
        return runner.write { requireOperationActive(operationActive); validate(value); updateCommit { value } }
    }
    suspend fun reset() = defaultWorkSchedule().toContract().let { save(SaveWorkSchedule(it.zoneId, it.weekly, it.makeup)) }
    suspend fun edit(base: WorkScheduleSettings, value: WorkScheduleSettings) = update { current ->
        SaveWorkSchedule(mergeSetting("zoneId", base.zoneId, value.zoneId, current.zoneId),
            mergeSetting("weekly", base.weekly, value.weekly, current.weekly),
            mergeSetting("makeup", base.makeup, value.makeup, current.makeup))
    }
    private suspend fun update(desired: (WorkScheduleSettings) -> SaveWorkSchedule) = runner.write { requireOperationActive(operationActive); updateCommit(desired) }
    private fun validate(value: SaveWorkSchedule) {
        (SettingsRules.zone(value.zoneId, "zoneId") + SettingsRules.weekly(value.weekly.mapValues { (_, windows) -> windows.map { it.toDomain() } }, "weekly") +
            SettingsRules.windows(value.makeup.map { it.toDomain() }, "makeup")).firstOrNull()?.let { settingFailure(ErrorCode.VALIDATION, it.message, it.path) }
    }
    private fun updateCommit(desired: (WorkScheduleSettings) -> SaveWorkSchedule): WorkScheduleSettings {
        return committer.commit(command, {
            transactions.writeWorkSchedule { writer ->
                val latest = writer.latestWorkSchedule()
                val current = latest ?: defaultWorkSchedule()
                val value = desired(current.toContract())
                validate(value)
                val weekly = value.weekly.mapValues { (_, windows) -> windows.map { it.toDomain() } }
                val makeup = value.makeup.map { it.toDomain() }
                val effective = maxOf(clock.millis(), latest?.let { it.effectiveFromEpochMillis + 1 } ?: 0)
                val saved = writer.appendWorkSchedule(com.greatwall.callring.domain.model.WorkScheduleSettings(value.zoneId, weekly, makeup, 0, effective))
                MutationDelta(saved.toContract(), setOf("schedule"), ChangeImpact(mapOf(DataDomain.WORK_CALENDAR to setOf("schedule"))), true)
            }
        }) { afterCommit(it.value.toDomain()) }
    }
}
