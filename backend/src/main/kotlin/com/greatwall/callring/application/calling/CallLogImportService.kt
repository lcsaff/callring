package com.greatwall.callring.application.calling

import com.greatwall.callring.domain.calling.NumberMatcher

import com.greatwall.callring.domain.calling.NumberRuleSnapshot

import com.greatwall.callring.domain.calling.CallCorrelation
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.SystemCallRecord

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*

internal class CallLogImportService(
    private val transactions: CallingTransactions,
    coordinator: BusinessRevisionCoordinator,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
    private val rule: () -> NumberRuleSnapshot,
    private val matcher: NumberMatcher,
) {
    private val committer = CommandCommitter(coordinator, operationActive)

    fun import(records: List<SystemCallRecord>): Set<String> =
        committer.commit(
            command,
            {
                val batchRule = rule()
                transactions.writeCalling { writer ->
                    val initial =
                        writer
                            .providerCalls(records.map { it.logId }.toSet())
                            .associateBy { it.id }
                            .toMutableMap()
                    val byLog = initial.values.associateBy { it.logId }.toMutableMap()
                    val pending = linkedMapOf<Long, RawCallRecord>()
                    val created = linkedSetOf<Long>()
                    val profiles = linkedSetOf<String>()
                    val affected = linkedMapOf<String, MutableSet<Long>>()
                    var history: WorkHistory? = null
                    val statistics = mutableMapOf<Long, Boolean>()
                    for (record in records) {
                        val identity = NumberIdentity.parse(record.number)
                        if (
                            !matcher.matches(identity.normalized, batchRule.rule) ||
                                !statistics.getOrPut(record.startedAtEpochMillis) {
                                    writer.statisticsContains(record.startedAtEpochMillis)
                                }
                        )
                            continue
                        requireField(record.logId.isNotBlank(), "logId", "log_id.required")
                        requireField(
                            record.durationSeconds == null || record.durationSeconds >= 0,
                            "durationSeconds",
                            "duration.invalid",
                        )
                        val n = identity.normalized
                        val previous = byLog[record.logId]
                        val account =
                            record.account?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.account
                        val moved =
                            previous == null ||
                                previous.number != n ||
                                previous.time != record.startedAtEpochMillis ||
                                previous.account != account
                        if (
                            !moved &&
                                previous.duration == record.durationSeconds &&
                                previous.result == record.result
                        )
                            continue
                        if (writer.ensureCallingProfile(n)) profiles += n
                        val current =
                            if (
                                previous == null ||
                                    previous.number != n ||
                                    previous.time != record.startedAtEpochMillis
                            ) {
                                val inputs =
                                    history ?: writer.callingWorkHistory().also { history = it }
                                WorkClassifier.classify(inputs, record.startedAtEpochMillis)
                            } else previous.classification
                        val updated =
                            if (previous == null) {
                                writer
                                    .insertCallingCall(
                                        RawCallRecord(
                                            0,
                                            n,
                                            record.startedAtEpochMillis,
                                            record.durationSeconds,
                                            record.result,
                                            CallDecision.SYSTEM_LOG_ONLY,
                                            CallSource.SYSTEM_CALL_LOG,
                                            record.logId,
                                            null,
                                            true,
                                            current,
                                            current,
                                            account,
                                        ),
                                    )
                                    .also {
                                        created += it.id
                                        initial[it.id] = it
                                    }
                            } else
                                previous.copy(
                                    number = n,
                                    time = record.startedAtEpochMillis,
                                    duration = record.durationSeconds,
                                    result = record.result,
                                    account = account,
                                    classification = current,
                                    linkedScreenId = if (moved) null else previous.linkedScreenId,
                                    legacyResult = null,
                                )
                        pending[updated.id] = updated
                        byLog[record.logId] = updated
                        if (moved) {
                            previous?.let {
                                affected.getOrPut(it.number) { linkedSetOf() }.add(it.time)
                            }
                            affected.getOrPut(n) { linkedSetOf() }.add(record.startedAtEpochMillis)
                        }
                    }
                    affected.forEach { (n, centers) ->
                        val stored = writer.correlationCalls(n)
                        stored.forEach { initial.putIfAbsent(it.id, it) }
                        val rows =
                            (stored.associateBy { it.id } + pending).values.filter {
                                it.number == n
                            }
                        CallCorrelation.reconcile(rows, centers).forEach { (_, after) ->
                            pending[after.id] = after
                        }
                    }
                    val changes =
                        pending.values.mapNotNull { after ->
                            val before = initial[after.id]
                            if (after.id in created || before != after) before to after else null
                        }
                    // New rows already exist, but their final association and any batch corrections
                    // are staged.
                    writer.updateCallingCalls(
                        changes.map { (before, after) ->
                            checkNotNull(before) to after
                        },
                    )
                    callingChanges(
                        changes
                            .flatMap { (before, after) ->
                                listOfNotNull(before?.number, after.number)
                            }
                            .toSet(),
                        changes.map { (before, after) ->
                            (if (after.id in created) null else before) to after
                        },
                        profiles,
                    )
                }
            },
            {},
        )

    private fun requireField(ok: Boolean, field: String, code: String) {
        if (!ok) throw BackendFailure(ApiError(ErrorCode.VALIDATION, code, mapOf(field to code)))
    }
}
