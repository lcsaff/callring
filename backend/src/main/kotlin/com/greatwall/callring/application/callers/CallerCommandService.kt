package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.model.ProfileRecord
import kotlinx.coroutines.*

internal class CallerCommandService(
    private val transactions: CallerTransactions,
    coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher,
    private val patchWhitelist: (String, Boolean) -> Unit,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)

    suspend fun save(value: SaveCallerProfile) = saveMerged(value, false)

    suspend fun edit(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> =
        saveMerged(
            SaveCallerProfile(
                value.number,
                value.name,
                value.department,
                value.positionId,
                value.whitelisted,
                base,
                dirty(base, value),
            ),
            true,
        )

    private suspend fun saveMerged(
        value: SaveCallerProfile,
        draft: Boolean,
    ): ApiResult<CallerProfile> = runner.write { requireOperationActive(operationActive);
        val directNumber = if (draft) null else callerNumber(value.number)
        committer.commit(
            command,
            {
                if (draft)
                    callerRequire(
                        value.baseline!!.let { it.number.isEmpty() || it.number == value.number },
                        "number",
                        "draft.number_changed",
                    )
                val n = directNumber ?: callerNumber(value.number)
                transactions.writeCaller { writer ->
                    val old = writer.caller(n)
                    val base = value.baseline
                    val dirty = value.dirtyFields
                    fun validateBaseline() {
                        callerRequire(
                            dirty == null ||
                                (base != null &&
                                    (base.number.isEmpty() || base.number == n) &&
                                    dirty.all {
                                        it in
                                            setOf("name", "department", "positionId", "whitelisted")
                                    }),
                            "baseline",
                            "draft.baseline_required",
                        )
                    }
                    if (!draft) validateBaseline()
                    fun <T> merge(field: String, wanted: T, current: T, baseline: T): T {
                        if (dirty == null) return wanted
                        if (field !in dirty) return current
                        if (baseline != current)
                            callerFailure(
                                if (draft) ErrorCode.CONFLICT else ErrorCode.VALIDATION,
                                "draft.field_changed",
                                field,
                            )
                        return wanted
                    }
                    val name = merge("name", value.name, old?.name.orEmpty(), base?.name.orEmpty())
                    val department =
                        merge(
                            "department",
                            value.department,
                            old?.department.orEmpty(),
                            base?.department.orEmpty(),
                        )
                    val position =
                        merge("positionId", value.positionId, old?.positionId, base?.positionId)
                    val white =
                        merge(
                            "whitelisted",
                            value.whitelisted ?: old?.whitelisted ?: false,
                            old?.whitelisted ?: false,
                            base?.whitelisted ?: false,
                        )
                    if (draft) validateBaseline()
                    val role = position?.let {
                        writer.position(it)
                            ?: callerFailure(
                                ErrorCode.VALIDATION,
                                "position.not_found",
                                "positionId",
                            )
                    }
                    callerRequire(
                        role == null || role.active || position == old?.positionId,
                        "positionId",
                        "position.inactive",
                    )
                    val saved = ProfileRecord(n, name.trim(), department.trim(), position, white)
                    if (old != saved) writer.putCaller(saved, old == null)
                    val mine = writer.myPositionId()?.let(writer::position)
                    MutationDelta(
                        callerProfile(n, saved, role, mine),
                        keys(old, saved),
                        profileImpact(n),
                        (old?.whitelisted ?: false) != white,
                    )
                }
            },
        ) { delta ->
            patchWhitelist(delta.value.number, delta.value.whitelisted)
        }
    }

    suspend fun whitelist(number: String, enabled: Boolean): ApiResult<CallerProfile> =
        runner.write { requireOperationActive(operationActive);
            val n = callerNumber(number)
            committer.commit(
                command,
                {
                    transactions.writeCaller { writer ->
                        val old = writer.caller(n)
                        val saved = (old ?: ProfileRecord(n)).copy(whitelisted = enabled)
                        if (old != saved) writer.putCaller(saved, old == null)
                        val mine = writer.myPositionId()?.let(writer::position)
                        MutationDelta(
                            callerProfile(n, saved, saved.positionId?.let(writer::position), mine),
                            keys(old, saved),
                            profileImpact(n),
                            (old?.whitelisted ?: false) != enabled,
                        )
                    }
                },
            ) { delta ->
                if (delta.decisionInputsChanged)
                    patchWhitelist(delta.value.number, delta.value.whitelisted)
            }
        }

    suspend fun impact(number: String): ApiResult<NumberCoreImpact> = runner.io { requireOperationActive(operationActive);
        val n = callerNumber(number)
        transactions.readCaller { reader ->
            NumberCoreImpact(
                n,
                reader.caller(n) != null,
                Math.toIntExact(reader.callCount(reader.matchingCallNumbers(n))),
            )
        }
    }

    suspend fun delete(number: String, expected: Int): ApiResult<Unit> = runner.write { requireOperationActive(operationActive);
        val n = callerNumber(number)
        committer.commit(
            command,
            {
                transactions.writeCaller { writer ->
                    val old = writer.caller(n)
                    val variants = writer.matchingCallNumbers(n)
                    val count = writer.callCount(variants)
                    callerRequire(old != null || count > 0, "number", "number.not_found")
                    callerRequire(count == expected.toLong(), "number", "number.delete_conflict")
                    val callKeys = linkedSetOf<String>()
                    writer.visitCallerCallIdentities(variants) { id, rawNumber ->
                        callKeys.add("call:$id")
                        callKeys.add("calls:$rawNumber")
                    }
                    writer.removeCallerCalls(variants, count)
                    if (old != null) writer.removeCaller(n)
                    val keys =
                        keys(old, null, n) +
                            callKeys +
                            if (count > 0) setOf("calls", "calls:$n") else emptySet()
                    MutationDelta(
                        Unit,
                        keys,
                        ChangeImpact(
                            buildMap {
                                put(DataDomain.CALLERS, setOf(n))
                                put(DataDomain.REPORTS, setOf(n))
                                if (count > 0) put(DataDomain.CALLS, setOf(n))
                            },
                        ),
                        old?.whitelisted == true,
                    )
                }
            },
        ) { delta ->
            if (delta.decisionInputsChanged) patchWhitelist(callerNumber(number), false)
        }
    }

    private fun profileImpact(n: String) =
        ChangeImpact(mapOf(DataDomain.CALLERS to setOf(n), DataDomain.REPORTS to setOf(n)))

    private fun keys(
        old: ProfileRecord?,
        saved: ProfileRecord?,
        n: String = saved!!.number,
    ): Set<String> =
        if (old == saved) emptySet()
        else
            buildSet {
                add("profiles")
                add("profile:$n")
                if ((old == null) != (saved == null)) add("profile:$n:exists")
                if (old?.name != saved?.name) add("profile:$n:name")
                if (old?.department != saved?.department) add("profile:$n:department")
                if (old?.positionId != saved?.positionId) {
                    add("profile:$n:positionId")
                    old?.positionId?.let { add("positionRefs:$it") }
                    saved?.positionId?.let { add("positionRefs:$it") }
                }
                if (old?.whitelisted != saved?.whitelisted) add("profile:$n:whitelisted")
            }

    private fun dirty(a: CallerDraft, b: CallerDraft) =
        setOfNotNull(
            if (a.name != b.name) "name" else null,
            if (a.department != b.department) "department" else null,
            if (a.positionId != b.positionId) "positionId" else null,
            if (a.whitelisted != b.whitelisted) "whitelisted" else null,
        )
}
