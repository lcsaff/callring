package com.greatwall.callring.application.report

import com.greatwall.callring.application.*
import com.greatwall.callring.application.callers.*
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.defaultWorkSchedule
import com.greatwall.callring.domain.report.StreamingReportAccumulator
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher

internal class ReportService(
    private val reads: ReportReads,
    private val coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val runtime: () -> RuntimeStatus,
    private val writeDocument: (DocumentRef, (OutputStream) -> Unit) -> Unit,
) : ReportsApi {
    private val runner = UseCaseRunner(dispatcher)

    private fun <T> consistent(number: String, block: (Long) -> T): T {
        val keys =
            setOf(
                "profile:$number",
                "calls:$number",
                "positions",
                "myPosition",
                "score",
                "schedule",
                "statistics",
                "runtime:capability:CALL_LOG",
            )
        repeat(3) {
            val before = coordinator.marker()
            val value = block(before)
            if (coordinator.unchanged(keys, before)) return value
        }
        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    }

    override suspend fun load(query: ReportQuery): ApiResult<CallerReport> = runner.io {
        val number = callerNumber(query.number)
        callerRequire(query.page.pageIndex >= 0, "page.pageIndex", "page.index.invalid")
        callerRequire(query.page.pageSize in 1..200, "page.pageSize", "page.size.invalid")
        capture(number, query.range, query.filter, query.page, false).report
    }

    override suspend fun exportCsv(
        query: ReportExportQuery,
        destination: DocumentRef,
    ): ApiResult<ExportReceipt> = runner.io {
        val number = callerNumber(query.number)
        val captured = capture(number, query.range, CallFilter.ALL, PageRequest(), true)
        // External output is performed once, after all read retries and SQL leases have ended.
        writeReportCsv(
            writeDocument,
            destination,
            captured.report,
            captured.rows,
            captured.myPosition,
        )
        ExportReceipt(number, captured.report.range, captured.rows.size.toLong(), "text/csv")
    }

    private data class Capture(
        val report: CallerReport,
        val rows: List<CallDetail>,
        val myPosition: PositionRecord?,
    )

    private fun capture(
        number: String,
        dates: DateRange,
        filter: CallFilter,
        page: PageRequest,
        export: Boolean,
    ): Capture =
        consistent(number) { before ->
            // Preserve validation precedence: number/page, platform runtime, then SQL range
            // validation.
            val status = runtime()
            reads.readReport { reader ->
                val range = range(reader, dates)
                val accumulator = StreamingReportAccumulator(reader.score(), range.toDomain())
                var missing = false
                var pending = false
                var unresolved = false
                var unverified = false
                reader.visitReportCalls(
                    number,
                    range.startInclusiveEpochMillis,
                    range.endExclusiveEpochMillis,
                    true,
                ) { fact ->
                    val raw = fact.current
                    accumulator.accept(
                        raw.time,
                        raw.counted,
                        raw.classification.working,
                        raw.duration,
                        raw.result,
                    )
                    missing = missing || (raw.counted && raw.duration == null)
                    pending =
                        pending ||
                            raw.result == com.greatwall.callring.domain.model.CallResult.PENDING
                    unresolved =
                        unresolved ||
                            raw.result == com.greatwall.callring.domain.model.CallResult.UNRESOLVED
                    unverified = unverified || !raw.classification.calendarVerified
                }
                val details = ArrayList<CallDetail>(page.pageSize)
                val exported = if (export) ArrayList<CallDetail>() else null
                var total = 0L
                val offset = page.pageIndex.toLong() * page.pageSize
                reader.visitReportCalls(
                    number,
                    range.startInclusiveEpochMillis,
                    range.endExclusiveEpochMillis,
                    false,
                ) { fact ->
                    val raw = fact.current
                    val matches =
                        when (filter) {
                            CallFilter.ALL -> true
                            CallFilter.WORKING -> raw.classification.working
                            else -> raw.classification.reason.name == filter.name
                        }
                    if (matches) {
                        if (export || (total >= offset && details.size < page.pageSize)) {
                            val detail = callDetail(raw, fact.screening)
                            if (total >= offset && details.size < page.pageSize) details.add(detail)
                            exported?.add(detail)
                        }
                        total++
                    }
                }
                val profile = reader.caller(number)
                val mine = reader.myPositionId()?.let(reader::position)
                val meta = SnapshotMeta(before, clock.millis())
                val report =
                    CallerReport(
                        callerProfile(
                            number,
                            profile,
                            profile?.positionId?.let(reader::position),
                            mine,
                        ),
                        range,
                        accumulator.totals().toContract(),
                        accumulator.score().toContract(),
                        Page(frozenList(details), page.pageIndex, page.pageSize, total, meta),
                        DataQuality(
                            reader.statisticsEnabled(),
                            status.capabilities[Capability.CALL_LOG] ?: AccessState.UNKNOWN,
                            missing,
                            pending,
                            unresolved,
                            unverified,
                        ),
                        meta,
                    )
                Capture(report, exported ?: emptyList(), mine)
            }
        }

    private fun range(reader: ReportReader, dates: DateRange): TimeRange {
        val issue =
            com.greatwall.callring.domain.model.SettingsRules.dateOrder(
                    dates.startDate,
                    dates.endDateInclusive,
                    "range.endDateInclusive",
                )
                .firstOrNull()
        if (issue != null)
            throw BackendFailure(
                ApiError(ErrorCode.VALIDATION, issue.message, mapOf(issue.path to issue.message)),
            )
        val zone =
            try {
                ZoneId.of(
                    dates.zoneId
                        ?: reader.latestWorkSchedule()?.zoneId
                        ?: defaultWorkSchedule().zoneId,
                )
            } catch (_: Exception) {
                throw BackendFailure(
                    ApiError(
                        ErrorCode.VALIDATION,
                        "zone.invalid",
                        mapOf("range.zoneId" to "zone.invalid"),
                    ),
                )
            }
        return try {
            com.greatwall.callring.domain.model
                .calendarRange(dates.startDate, dates.endDateInclusive, zone)
                .toContract()
        } catch (_: Exception) {
            throw BackendFailure(
                ApiError(
                    ErrorCode.VALIDATION,
                    "range.invalid",
                    mapOf("range.endDateInclusive" to "range.invalid"),
                ),
            )
        }
    }
}
