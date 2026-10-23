package com.greatwall.callring.application.callers

import com.greatwall.callring.domain.calling.NumberMatcher

import com.greatwall.callring.domain.calling.NumberRuleSnapshot

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.frozenList
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.PositionRecord

import com.greatwall.callring.domain.model.defaultWorkSchedule
import com.greatwall.callring.application.*
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.positionOption
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.report.DataQuality
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.saturatedAdd
import com.greatwall.callring.domain.model.saturatedSubtract
import com.greatwall.callring.domain.report.StreamingReportAccumulator
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal class CallerQueryService(
    private val profiles: CallerReads,
    private val queries: CallerQueryTransactions,
    private val coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val runtime: () -> RuntimeStatus,
    private val rule: () -> NumberRuleSnapshot,
    private val matcher: NumberMatcher,
) {
    private val runner = UseCaseRunner(dispatcher)

    private fun <T> consistent(keys: Set<String>, block: (Long) -> T): T {
        repeat(3) {
            val before = coordinator.marker()
            val value = block(before)
            if (coordinator.unchanged(keys, before)) return value
        }
        callerFailure(ErrorCode.CONFLICT, "business.changed")
    }

    private fun meta(before: Long) = SnapshotMeta(before, clock.millis())

    suspend fun editor(number: String?): ApiResult<CallerEditorData> = runner.io {
        val n = number?.let(::callerNumber)
        consistent(setOf("profile:$n", "positions", "myPosition")) { _ ->
            profiles.readCaller { reader ->
                val p = n?.let(reader::caller)
                val mine = reader.myPositionId()?.let(reader::position)
                val positions =
                    reader
                        .positions()
                        .sortedWith(
                            compareBy<PositionRecord> { it.level }
                                .thenBy { it.name }
                                .thenBy { it.id },
                        )
                CallerEditorData(
                    CallerDraft(
                        n.orEmpty(),
                        p?.name.orEmpty(),
                        p?.department.orEmpty(),
                        p?.positionId,
                        p?.whitelisted ?: false,
                    ),
                    frozenList(
                        positions
                            .filter { it.active || it.id == p?.positionId }
                            .map { positionOption(it, mine) },
                    ),
                    mine?.let { positionOption(it, mine) },
                )
            }
        }
    }

    suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> = runner.io {
        callerRequire(query.page.pageIndex >= 0, "page.pageIndex", "page.index.invalid")
        callerRequire(query.page.pageSize in 1..200, "page.pageSize", "page.size.invalid")
        consistent(setOf("profiles", "positions", "myPosition")) { before ->
            profiles.readCaller { reader ->
                val text = query.text.trim()
                val offset = query.page.pageIndex.toLong() * query.page.pageSize
                var total = 0L
                val page =
                    if (text.isEmpty()) {
                        total = reader.callerCount()
                        reader.callerPage(offset, query.page.pageSize)
                    } else {
                        val numbers = ArrayList<String>(query.page.pageSize)
                        reader.visitCallerSearch { row ->
                            if (
                                row.number.contains(text, true) ||
                                    row.name.contains(text, true) ||
                                    row.department.contains(text, true)
                            ) {
                                if (total >= offset && numbers.size < query.page.pageSize)
                                    numbers.add(row.number)
                                total++
                            }
                        }
                        val byNumber = reader.callers(numbers).associateBy { it.number }
                        numbers.map { byNumber.getValue(it) }
                    }
                val mine = reader.myPositionId()?.let(reader::position)
                // One bounded batch of role rows, never a per-profile history query.
                val wanted = page.mapNotNull { it.positionId }.toSet()
                val positions =
                    if (wanted.isEmpty()) emptyMap()
                    else reader.callerPositions(wanted).associateBy { it.id }
                Page(
                    frozenList(
                        page.map { callerProfile(it.number, it, positions[it.positionId], mine) },
                    ),
                    query.page.pageIndex,
                    query.page.pageSize,
                    total,
                    meta(before),
                )
            }
        }
    }

    suspend fun detail(id: Long): ApiResult<CallDetail> = runner.io {
        queries.readCallerCalls { reader ->
            val raw = reader.callerCall(id) ?: callerFailure(ErrorCode.NOT_FOUND, "call.not_found")
            callDetail(raw, raw.linkedScreenId?.let(reader::callerCall))
        }
    }

    suspend fun latest(): ApiResult<CallDetail?> = runner.io {
        queries.readCallerCalls { reader ->
            reader.latestCallerCall()?.let {
                callDetail(it, it.linkedScreenId?.let(reader::callerCall))
            }
        }
    }

    suspend fun overview(query: OverviewQuery): ApiResult<CallerOverview> = runner.io {
        val n = callerNumber(query.number)
        consistent(
            setOf(
                "profile:$n",
                "calls:$n",
                "positions",
                "myPosition",
                "score",
                "schedule",
                "calendar",
                "overrides",
                "leaves",
                "statistics",
                "quiet",
                "numberRule",
                "runtime:capability:CALL_LOG",
            ),
        ) { before ->
            val ruleSnapshot = rule()
            val status = runtime()
            queries.readCallerOverview { reader ->
                val p = reader.caller(n)
                val mine = reader.myPositionId()?.let(reader::position)
                val profile = callerProfile(n, p, p?.positionId?.let(reader::position), mine)
                val settings = reader.score()
                val history = reader.workHistory()
                val end = saturatedAdd(clock.millis(), 1)
                val zone =
                    history.schedules
                        .maxWithOrNull(
                            compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                                    it.effectiveFromEpochMillis
                                }
                                .thenBy { it.configVersion },
                        )
                        ?.zoneId ?: defaultWorkSchedule().zoneId
                val range =
                    com.greatwall.callring.domain.model.TimeRange(
                        saturatedSubtract(end, settings.observationDays * 86400000L),
                        end,
                        zone,
                    )
                val accumulator = StreamingReportAccumulator(settings, range)
                var count = 0L
                var latest: Long? = null
                var missing = false
                var pending = false
                var unresolved = false
                var unverified = false
                reader.visitVisibleCallerCalls(n) { raw ->
                    accumulator.accept(raw.time, raw.counted, raw.working, raw.duration, raw.result)
                    if (raw.counted) count++
                    latest = raw.id
                    missing = missing || (raw.counted && raw.duration == null)
                    pending =
                        pending ||
                            raw.result == com.greatwall.callring.domain.model.CallResult.PENDING
                    unresolved =
                        unresolved ||
                            raw.result == com.greatwall.callring.domain.model.CallResult.UNRESOLVED
                    unverified = unverified || !raw.calendarVerified
                }
                val last =
                    latest?.let(reader::callerCall)?.let {
                        callDetail(it, it.linkedScreenId?.let(reader::callerCall))
                    }
                CallerOverview(
                    profile,
                    accumulator.score().toContract(),
                    count,
                    last,
                    WorkClassifier.status(history, query.workAtEpochMillis ?: clock.millis())
                        .toContract(),
                    reader.quiet().settings.mode.toContract(),
                    checkNumber(n, ruleSnapshot, matcher),
                    DataQuality(
                        reader.statisticsHead().open != null,
                        status.capabilities[Capability.CALL_LOG] ?: AccessState.UNKNOWN,
                        missing,
                        pending,
                        unresolved,
                        unverified,
                    ),
                    meta(before),
                )
            }
        }
    }
}
