package com.greatwall.callring.application.settings

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.SyncScheduler
import com.greatwall.callring.application.ports.store.StatisticsTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.StatisticsInterval
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal data class StatisticsChange(val before: StatisticsInterval?, val after: StatisticsInterval)
internal class StatisticsSettingsService(private val transactions: StatisticsTransactions,
    coordinator: BusinessRevisionCoordinator, dispatcher: CoroutineDispatcher,
    private val clock: Clock, private val scheduler: SyncScheduler,
    private val afterCommit: (StatisticsChange) -> Unit, private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun set(enabled: Boolean): ApiResult<Unit> {
        val result = runner.write { requireOperationActive(operationActive);
            committer.commit(command, {
                transactions.writeStatistics { writer ->
                    val head = writer.statisticsHead()
                    if ((head.open != null) == enabled) MutationDelta<StatisticsChange?>(null)
                    else {
                        val old = head.open?.interval
                        val at = if (enabled) maxOf(clock.millis(), head.maxClosedEnd ?: Long.MIN_VALUE)
                            else maxOf(clock.millis(), requireNotNull(old).startMillis)
                        val interval = if (enabled) StatisticsInterval(at, null) else requireNotNull(old).copy(endMillis = at)
                        if (enabled) writer.openStatistics(at) else writer.closeStatistics(requireNotNull(head.open).id, at)
                        MutationDelta(StatisticsChange(old, interval), setOf("statistics"), ChangeImpact.wide(setOf(DataDomain.STATISTICS)), true)
                    }
                }
            }) { delta -> delta.value?.let(afterCommit) }
        }
        if (result is ApiResult.Failure) return result
        if (enabled && (result as ApiResult.Success).value != null) {
            try { scheduler.requestSync(clock.millis()) } catch (_: Exception) { /* Already committed. */ }
        }
        return ApiResult.Success(Unit)
    }
}
