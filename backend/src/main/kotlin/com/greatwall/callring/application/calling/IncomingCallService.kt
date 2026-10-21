package com.greatwall.callring.application.calling

import com.greatwall.callring.domain.calling.CallCorrelation
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.RawCallRecord

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.toDomain
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*

internal class IncomingCallService(
    private val transactions: CallingTransactions,
    coordinator: BusinessRevisionCoordinator,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val committer = CommandCommitter(coordinator, operationActive)

    fun record(
        number: String,
        at: Long,
        decision: com.greatwall.callring.contracts.callers.CallDecision,
        account: String?,
        originalFacts: com.greatwall.callring.contracts.callers.ClassificationFact?,
    ): Long =
        committer.commit(
            command,
            {
                transactions.writeCalling { writer ->
                    val identity = NumberIdentity.parse(number)
                    if (!identity.matchesRule || !writer.statisticsContains(at))
                        return@writeCalling callingChanges(-1L, emptyList(), emptySet())
                    val n = identity.normalized
                    val known = account?.trim()?.takeIf { it.isNotEmpty() }
                    val key = "$n:$at" + (known?.let { ":$it" } ?: "")
                    writer.screeningCall(key)?.let {
                        return@writeCalling callingChanges(it.id, emptyList(), emptySet())
                    }
                    val profiles = if (writer.ensureCallingProfile(n)) setOf(n) else emptySet()
                    val current = WorkClassifier.classify(writer.callingWorkHistory(), at)
                    val row =
                        writer.insertCallingCall(
                            RawCallRecord(
                                0,
                                n,
                                at,
                                null,
                                CallResult.PENDING,
                                decision.toDomain(),
                                CallSource.SCREENING,
                                null,
                                key,
                                true,
                                current,
                                originalFacts?.toDomain() ?: current,
                                known,
                            ),
                        )
                    val patches = CallCorrelation.reconcile(writer.correlationCalls(n), listOf(at))
                    writer.updateCallingCalls(patches)
                    callingChanges(
                        row.id,
                        listOf(
                            null to (patches.firstOrNull { it.first.id == row.id }?.second ?: row),
                        ) + patches.filter { it.first.id != row.id },
                        profiles,
                    )
                }
            },
            {},
        )

    fun updateDecision(id: Long, decision: com.greatwall.callring.contracts.callers.CallDecision) {
        if (id <= 0) return
        committer.commit(
            command,
            {
                transactions.writeCalling { writer ->
                    val old = writer.callingCall(id)
                    if (old == null || old.decision == decision.toDomain())
                        return@writeCalling callingChanges(Unit, emptyList(), emptySet())
                    val updated = old.copy(decision = decision.toDomain(), legacyDecision = null)
                    writer.updateCallingCalls(listOf(old to updated))
                    callingChanges(Unit, listOf(old to updated), emptySet())
                }
            },
            {},
        )
    }
}
