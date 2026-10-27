package com.greatwall.callring.application.settings

import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.application.calling.NumberRuleState
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.toSnapshot
import com.greatwall.callring.application.ports.store.NumberRuleTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.*

internal class NumberRuleSettingsService(
    private val transactions: NumberRuleTransactions,
    private val coordinator: BusinessRevisionCoordinator,
    private val state: NumberRuleState,
    dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
    private val afterInstall: (NumberRuleSnapshot) -> Unit,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun get(): ApiResult<NumberRuleSettings> = runner.io {
        val context = currentCoroutineContext()
        synchronized(coordinator.fence) {
            context.ensureActive()
            requireOperationActive(operationActive)
            val value = transactions.readNumberRule { it.numberRule().detached() }
            val prepared = value.toSnapshot()
            context.ensureActive()
            requireOperationActive(operationActive)
            state.install(prepared)
            afterInstall(prepared)
            value
        }
    }
    /** Cold callers serialize with commands and share the installed immutable snapshot. */
    suspend fun prepare(): ApiResult<Unit> = runner.io {
        val context = currentCoroutineContext()
        synchronized(coordinator.fence) {
            context.ensureActive()
            requireOperationActive(operationActive)
            if (!state.initialized()) {
                val prepared = transactions.readNumberRule { it.numberRule().toSnapshot() }
                context.ensureActive()
                requireOperationActive(operationActive)
                state.install(prepared)
                afterInstall(prepared)
            }
        }
    }
    suspend fun save(value: SaveNumberRule): ApiResult<NumberRuleSettings> {
        currentCoroutineContext().ensureActive()
        val base = value.base.detached()
        val desired = freeze(value.rules)
        fun validate(rules: List<NumberRuleEntry>, path: String) {
            rules.forEachIndexed { index, rule ->
                val row = "${path}rules[$index]."
                if (rule.prefix.any { it !in '0'..'9' }) settingFailure(ErrorCode.VALIDATION,"number_rule.prefix",row+"prefix")
                if (rule.length !in 1..30) settingFailure(ErrorCode.VALIDATION,"number_rule.length",row+"length")
                if (rule.prefix.length > rule.length) settingFailure(ErrorCode.VALIDATION,"number_rule.prefix_length",row+"prefix")
            }
        }
        return runner.write {
            if (base.revision < 0) settingFailure(ErrorCode.VALIDATION,"number_rule.revision","base.revision")
            validate(base.rules,"base.")
            validate(desired,"")
            var prepared: NumberRuleSnapshot? = null
            var changed = false
            synchronized(coordinator.fence) {
                val result = committer.commit(command, {
                    transactions.writeNumberRule { writer ->
                        val current = writer.numberRule().detached()
                        if (current != base) settingFailure(ErrorCode.CONFLICT,"number_rule.changed","numberRule")
                        changed = current.rules != desired
                        val result = if (changed) NumberRuleSettings(desired,Math.addExact(current.revision,1)) else current
                        prepared = result.toSnapshot()
                        if (changed) writer.saveNumberRule(result)
                        MutationDelta(result,if (changed) setOf("numberRule") else emptySet(),
                            if (changed) ChangeImpact.wide(setOf(DataDomain.NUMBER_RULES)) else ChangeImpact(emptyMap()))
                    }
                }) { state.install(checkNotNull(prepared)); afterInstall(checkNotNull(prepared)) }
                // A durable no-op is also a successful preparation, without an invalidation.
                if (!changed) { state.install(checkNotNull(prepared)); afterInstall(checkNotNull(prepared)) }
                result
            }
        }
    }
    private fun NumberRuleSettings.detached(): NumberRuleSettings = copy(rules = freeze(rules))
    private fun freeze(rules: List<NumberRuleEntry>): List<NumberRuleEntry> =
        java.util.Collections.unmodifiableList(ArrayList(rules))
}
