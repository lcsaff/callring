package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createReportsApi(context: BackendContext): ReportsApi =
    with(context) {
        object : ReportsApi {
            override suspend fun load(query: ReportQuery): ApiResult<CallerReport> = io {
                val n = number(query.number)
                page(query.page)
                val status = runtimeStatus()
                read { s ->
                    report(s, n, timeRange(s, query.range), query.filter, query.page, status)
                }
            }

            override suspend fun exportCsv(
                query: ReportExportQuery,
                destination: DocumentRef,
            ): ApiResult<ExportReceipt> = io {
                val n = number(query.number)
                val status = runtimeStatus()
                val captured = read { s ->
                    val range = timeRange(s, query.range)
                    Triple(
                        report(s, n, range, CallFilter.ALL, PageRequest(), status),
                        visible(s, n).filter {
                            it.startedAtEpochMillis >= range.startInclusiveEpochMillis &&
                                it.startedAtEpochMillis < range.endExclusiveEpochMillis
                        },
                        s.positions[s.myPositionId],
                    )
                }
                val report = captured.first
                val rows = captured.second
                val myPosition = captured.third
                documents.write(destination) { stream ->
                    stream.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
                    fun row(vararg values: Any?) {
                        stream.write(
                            (values.joinToString(",") { csvCell(it?.toString().orEmpty()) } +
                                    "\r\n")
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                    row(
                        "number",
                        "name",
                        "department",
                        "position",
                        "relation",
                        "my_position_id",
                        "my_position_name",
                        "my_position_level",
                        "range_start",
                        "range_end",
                        "zone",
                    )
                    row(
                        "'${report.profile.number}",
                        report.profile.name,
                        report.profile.department,
                        report.profile.position?.name,
                        report.profile.relation,
                        myPosition?.id,
                        myPosition?.name,
                        myPosition?.level,
                        report.range.startInclusiveEpochMillis,
                        report.range.endExclusiveEpochMillis,
                        report.range.zoneId,
                    )
                    row(
                        "total",
                        "working",
                        "off_hours",
                        "known_duration_seconds",
                        "unknown_duration_count",
                        "missed_count",
                        "rejected_count",
                        "pending",
                        "score",
                    )
                    row(
                        report.totals.totalCalls,
                        report.totals.workingCalls,
                        report.totals.offHoursCalls,
                        report.totals.knownDurationSeconds,
                        report.totals.unknownDurationCount,
                        report.totals.missedCount,
                        report.totals.rejectedCount,
                        report.totals.pendingCount,
                        report.score.points,
                    )
                    row(
                        "id",
                        "number",
                        "started_at",
                        "screening_at",
                        "duration_seconds",
                        "result",
                        "decision",
                        "source",
                        "counted",
                        "working",
                        "reason",
                        "holiday",
                        "verified",
                        "zone",
                        "config_version",
                        "calendar_version",
                        "leave_version",
                        "original_working",
                        "original_reason",
                        "original_holiday",
                        "original_verified",
                        "original_zone",
                        "original_config_version",
                        "original_calendar_version",
                        "original_leave_version",
                        "corrected",
                        "decision_note",
                    )
                    for (call in rows) {
                        val c = call.classification
                        val o = call.originalClassification
                        row(
                            call.id,
                            "'${call.number}",
                            call.startedAtEpochMillis,
                            call.screeningAtEpochMillis,
                            call.durationSeconds,
                            call.result,
                            call.decision,
                            call.source,
                            call.counted,
                            c.working,
                            c.reason,
                            c.holidayName,
                            c.calendarVerified,
                            c.zoneId,
                            c.configVersion,
                            c.calendarVersion,
                            c.leaveVersion,
                            o.working,
                            o.reason,
                            o.holidayName,
                            o.calendarVerified,
                            o.zoneId,
                            o.configVersion,
                            o.calendarVersion,
                            o.leaveVersion,
                            call.corrected,
                            call.decisionNote,
                        )
                    }
                    stream.flush()
                }
                ExportReceipt(n, report.range, rows.size.toLong(), "text/csv")
            }
        }
    }
