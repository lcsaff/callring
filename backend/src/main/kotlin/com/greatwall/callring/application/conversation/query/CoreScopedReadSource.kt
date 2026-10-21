package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.query.*
import com.greatwall.callring.application.ports.store.WorkHistory
import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.*
import com.greatwall.callring.domain.model.TimeRange
import com.greatwall.callring.domain.report.*
import java.time.*

class CoreScopedReadSource(
    private val facts: CoreQueryFactsPort,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScopedReadPort by facts {
    override fun query(spec: QuerySpec, after: String?): SourcePage {
        if (spec.domain != AiDomain.D06 || spec.projection != "work")
            return facts.query(spec, after)
        return facts.readWork { reader ->
            val at = clock()
            val candidates =
                WorkHistory(
                    reader.scheduleCandidates(at),
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptyMap(),
                    emptyMap(),
                    0,
                )
            val zone = ZoneId.of(WorkClassifier.schedule(candidates, at).zoneId)
            val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val end = date.plusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()
            val history = reader.history(at, end, date.minusDays(1), date.plusDays(10))
            SourcePage(
                listOf(
                    SourceRow(
                        "work",
                        safeValue(WorkClassifier.status(history, at).toContract()) as AiJson.Obj,
                    ),
                ),
                1,
            )
        }
    }

    override fun aggregate(spec: AggregateSpec): AiJson {
        if (spec.metric == "count") return facts.aggregate(spec)
        return facts.readStatistics(spec) { reader -> statistics(spec, reader) }
    }

    private fun statistics(spec: AggregateSpec, reader: StatisticsQueryFacts): AiJson {
        require(spec.groupBy == null || spec.groupBy == "number")
        require(spec.metric != "score" || spec.filter.number != null || spec.groupBy == "number")
        val settings = reader.scoreSettings()
        val zone = spec.filter.zone ?: reader.latestScheduleZone()
        ZoneId.of(zone)
        val range =
            TimeRange(spec.filter.start ?: Long.MIN_VALUE, spec.filter.end ?: Long.MAX_VALUE, zone)
        val totals = ReportTotalsAccumulator()
        val groups = linkedMapOf<String, StreamingReportAccumulator>()
        val deadline = System.nanoTime() + 5_000_000_000L
        var scanned = 0L
        var unverified = false
        var unresolved = false
        reader.visit { row ->
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted)
                throw BackendFailure(
                    ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.aggregate_incomplete"),
                )
            scanned++
            val number = row.number
            val at = row.at
            val counted = row.counted
            val working = row.working
            val duration = row.duration
            val result = row.result
            totals.accept(counted, working, duration, result)
            unverified = unverified || !row.calendarVerified
            unresolved = unresolved || result == CallResult.UNRESOLVED
            if (spec.filter.number != null || spec.groupBy == "number") {
                if (number !in groups && groups.size >= 200)
                    throw BackendFailure(
                        ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.aggregate_incomplete"),
                    )
                groups
                    .getOrPut(number) {
                        StreamingReportAccumulator(settings, range, spec.filter.end == null)
                    }
                    .accept(at, counted, working, duration, result)
            }
        }
        if (spec.metric == "sumDuration")
            return aiObject(
                "knownDurationSeconds" to aiNumber(totals.value().knownDurationSeconds),
                "unknownDurationCount" to aiNumber(totals.value().unknownDurationCount),
                "scanned" to aiNumber(scanned),
                "range" to safeValue(range),
            )
        fun result(acc: StreamingReportAccumulator) =
            aiObject(
                "totals" to safeValue(acc.totals().toContract()),
                "score" to safeValue(acc.score().toContract()),
                "range" to safeValue(range.toContract()),
            )
        if (spec.groupBy == "number")
            return AiJson.Arr(
                groups.map { (number, acc) ->
                    AiJson.Obj(result(acc).fields + ("number" to aiString(number)))
                },
            )
        val value =
            groups.values.singleOrNull()?.let(::result)
                ?: aiObject(
                    "totals" to safeValue(totals.value().toContract()),
                    "range" to safeValue(range.toContract()),
                    "scoreRequiresNumber" to aiBool(true),
                )
        return AiJson.Obj(
            value.fields +
                mapOf(
                    "scanned" to aiNumber(scanned),
                    "hasUnverifiedCalendar" to aiBool(unverified),
                    "hasUnresolvedRecords" to aiBool(unresolved),
                    "runtimeQualitySource" to aiString("D19"),
                    "ruleVersion" to aiString("shared-score-v1"),
                ),
        )
    }
}
