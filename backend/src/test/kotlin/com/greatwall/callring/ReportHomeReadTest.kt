package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.home.HomeService
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.report.ReportService
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.ProfileRecord
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.defaultWorkSchedule
import com.greatwall.callring.domain.model.CallDecision as DomainDecision
import com.greatwall.callring.domain.model.CallResult as DomainResult
import com.greatwall.callring.domain.model.CallSource as DomainSource
import com.greatwall.callring.domain.model.ClassificationFact as DomainFact
import com.greatwall.callring.domain.model.QuietMode as DomainQuietMode
import com.greatwall.callring.domain.model.ScoreSettings as DomainScore
import com.greatwall.callring.domain.model.WorkReason as DomainReason
import java.io.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReportHomeReadTest {
    private val day = LocalDate.of(2026, 3, 8)
    private val start = day.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val dates = DateRange(day, day, "America/New_York")
    private val fact =
        DomainFact(false, DomainReason.OUTSIDE_WORK_HOURS, null, true, "UTC", 1, null, 0)
    private val runtime =
        RuntimeStatus(
            mapOf(Capability.CALL_LOG to AccessState.GRANTED),
            Audibility.AUDIBLE,
            PhoneActivity.IDLE,
            PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE),
            false,
            0,
        )
    private val clock = Clock.fixed(Instant.ofEpochMilli(start + 1000), ZoneOffset.UTC)

    private fun row(
        id: Long,
        at: Long = start + id * 1000,
        counted: Boolean = true,
        duration: Long? = 30,
        result: DomainResult = DomainResult.ANSWERED,
        classification: DomainFact = fact,
    ) =
        RawCallRecord(
            id,
            "31234",
            at,
            duration,
            result,
            DomainDecision.SYSTEM_LOG_ONLY,
            DomainSource.SYSTEM_CALL_LOG,
            "log$id",
            null,
            counted,
            classification,
            classification,
        )

    private inner class Reads : ReportReads, ReportReader, HomeReads, HomeReader {
        var rows = listOf(ReportCallFact(row(1), null))
        var captures = 0
        var active = false
        var after: () -> Unit = {}
        var profile = ProfileRecord("31234", "A", "D", 1)
        var scoreValue = DomainScore(5, 2, 10, 30)
        var enabled = true
        var quietMode = DomainQuietMode.OFF
        var overlayValue = false
        val mine = PositionRecord(2, "Mine", 2, true)
        val role = PositionRecord(1, "Role", 1, false)
        var history =
            WorkHistory(
                listOf(defaultWorkSchedule("America/New_York")),
                emptyMap(),
                emptyMap(),
                emptySet(),
                emptyMap(),
                emptyMap(),
                0,
            )
        val requests = mutableListOf<Triple<String, Long, Long>>()

        private fun <T> capture(block: () -> T): T {
            captures++
            active = true
            val value =
                try {
                    block()
                } finally {
                    active = false
                }
            after()
            return value
        }

        override fun <T> readReport(block: (ReportReader) -> T): T = capture { block(this) }

        override fun <T> readHome(block: (HomeReader) -> T): T = capture { block(this) }

        override fun caller(number: String): ProfileRecord {
            assertEquals("31234", number)
            return profile
        }

        override fun position(id: Long) = if (id == 1L) role else if (id == 2L) mine else null

        override fun myPositionId() = 2L

        override fun latestWorkSchedule() = history.schedules.lastOrNull()

        override fun score() = scoreValue

        override fun statisticsEnabled() = enabled

        override fun homeQuietMode() = quietMode

        override fun overlay() = overlayValue

        override fun workHistory() = history

        override fun latestVisibleReportCall() =
            rows.maxWithOrNull(
                compareBy<ReportCallFact> { it.current.time }.thenBy { it.current.id },
            )

        override fun visitReportCalls(
            number: String,
            start: Long,
            end: Long,
            ascending: Boolean,
            accept: (ReportCallFact) -> Unit,
        ) {
            requests += Triple(number, start, end)
            val order = compareBy<ReportCallFact> { it.current.time }.thenBy { it.current.id }
            rows
                .filter {
                    !it.current.hidden &&
                        it.current.number == number &&
                        it.current.time >= start &&
                        it.current.time < end
                }
                .sortedWith(if (ascending) order else order.reversed())
                .forEach(accept)
        }
    }

    private fun service(
        reads: Reads,
        coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
        runtimeRead: () -> RuntimeStatus = { runtime },
        write: (DocumentRef, (OutputStream) -> Unit) -> Unit = { _, _ -> error("unused") },
    ) = ReportService(reads, coordinator, Dispatchers.Unconfined, clock, runtimeRead, write)

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    private fun ApiResult<*>.error() = (this as ApiResult.Failure).error

    @Test
    fun rangeOwnsRepeatsTotalsQualityAndDetailsHaveIndependentFilterAndLongPage() = runBlocking {
        val reads = Reads()
        val working = fact.copy(working = true, reason = DomainReason.MAKEUP_WORKING)
        val holiday = fact.copy(reason = DomainReason.HOLIDAY, holidayName = "A holiday")
        reads.rows =
            listOf(
                ReportCallFact(row(0, start - 1), null),
                ReportCallFact(row(1, start, duration = null), null),
                ReportCallFact(row(2, start + 1000, classification = working), null),
                ReportCallFact(
                    row(
                        3,
                        start + 1000,
                        counted = false,
                        duration = null,
                        result = DomainResult.PENDING,
                        classification = holiday.copy(calendarVerified = false),
                    ),
                    null,
                ),
                ReportCallFact(
                    row(4, start + 2000, counted = false, result = DomainResult.UNRESOLVED),
                    null,
                ),
                ReportCallFact(row(5, start + 23 * 3600000L), null),
            )
        val api = service(reads)
        val all = api.load(ReportQuery("31-234", dates, page = PageRequest(0, 2))).value()
        assertEquals(
            23 * 3600000L,
            all.range.endExclusiveEpochMillis - all.range.startInclusiveEpochMillis,
        )
        assertEquals(4L, all.details.totalItems)
        assertEquals(listOf(4L, 3L), all.details.items.map { it.id })
        assertEquals(2L, all.totals.totalCalls)
        assertEquals(1L, all.totals.unknownDurationCount)
        assertEquals(2L, all.totals.pendingCount)
        assertEquals(1L, all.score.repeatedCalls)
        assertEquals(93, all.score.points)
        assertTrue(
            all.quality.hasUnknownDuration &&
                all.quality.hasPendingResults &&
                all.quality.hasUnresolvedRecords &&
                all.quality.hasUnverifiedCalendar,
        )
        assertEquals(PositionRelation.HIGHER, all.profile.relation)
        assertFalse(all.profile.position!!.active)
        val filtered =
            api.load(ReportQuery("31234", dates, CallFilter.HOLIDAY, PageRequest(0, 1))).value()
        assertEquals(1L, filtered.details.totalItems)
        assertEquals(3L, filtered.details.items.single().id)
        assertEquals(all.totals, filtered.totals)
        assertEquals(all.score, filtered.score)
        assertEquals(all.quality, filtered.quality)
        val work = api.load(ReportQuery("31234", dates, CallFilter.WORKING)).value()
        assertEquals(listOf(2L), work.details.items.map { it.id })
        val huge =
            api.load(ReportQuery("31234", dates, page = PageRequest(Int.MAX_VALUE, 200))).value()
        assertEquals(4L, huge.details.totalItems)
        assertTrue(huge.details.items.isEmpty())
        try {
            (all.details.items as MutableList).clear()
            fail("mutable details")
        } catch (_: UnsupportedOperationException) {}
        assertTrue(
            reads.requests.all {
                it.first == "31234" && it.second == start && it.third == start + 23 * 3600000L
            },
        )
    }

    @Test
    fun validationPreservesFirstErrorAndRuntimePrecedesRange() = runBlocking {
        val reads = Reads()
        var probes = 0
        val api =
            service(
                reads,
                runtimeRead = {
                    probes++
                    runtime
                },
            )
        val bad = DateRange(day.plusDays(1), day, "invalid")
        assertEquals(
            "number.invalid",
            api.load(ReportQuery("", bad, page = PageRequest(-1))).error().message,
        )
        assertEquals(
            "page.index.invalid",
            api.load(ReportQuery("31234", bad, page = PageRequest(-1))).error().message,
        )
        assertEquals(
            "page.size.invalid",
            api.load(ReportQuery("31234", bad, page = PageRequest(0, 201))).error().message,
        )
        assertEquals(0, probes)
        assertEquals("range.order.invalid", api.load(ReportQuery("31234", bad)).error().message)
        assertEquals(1, probes)
        assertEquals(
            "zone.invalid",
            api.load(ReportQuery("31234", dates.copy(zoneId = "invalid"))).error().message,
        )
        assertEquals(
            "range.invalid",
            api.load(ReportQuery("31234", DateRange(LocalDate.MAX, LocalDate.MAX, "UTC")))
                .error()
                .message,
        )
    }

    @Test
    fun defaultZoneAndFallDstUseCalendarDays() = runBlocking {
        val reads = Reads()
        val fall = LocalDate.of(2026, 11, 1)
        val report = service(reads).load(ReportQuery("31234", DateRange(fall, fall))).value()
        assertEquals("America/New_York", report.range.zoneId)
        assertEquals(
            25 * 3600000L,
            report.range.endExclusiveEpochMillis - report.range.startInclusiveEpochMillis,
        )
        assertNull(report.score.points)
    }

    @Test
    fun directScreenOwnsOriginalDecisionAndNoteWhileProviderOwnsCurrentAndTiming() = runBlocking {
        val reads = Reads()
        val origin = fact.copy(working = true, reason = DomainReason.WORKING, configVersion = 8)
        val screen =
            row(1, start, classification = origin)
                .copy(
                    logId = null,
                    decision = DomainDecision.UNKNOWN,
                    decisionNote = "legacy decision",
                )
        val current = row(2, start + 100).copy(linkedScreenId = 1)
        reads.rows = listOf(ReportCallFact(current, screen))
        val detail = service(reads).load(ReportQuery("31234", dates)).value().details.items.single()
        assertEquals(start, detail.screeningAtEpochMillis)
        assertEquals(start + 100, detail.startedAtEpochMillis)
        assertEquals(CallSource.MERGED, detail.source)
        assertEquals(CallDecision.UNKNOWN, detail.decision)
        assertEquals("legacy decision", detail.decisionNote)
        assertEquals(8L, detail.originalClassification.configVersion)
        assertEquals(1L, detail.classification.configVersion)
        assertTrue(detail.corrected)
    }

    @Test
    fun relatedChangeRetriesCaptureAndUnrelatedChangeKeepsBeforeMarker() = runBlocking {
        val reads = Reads()
        val coordinator = BusinessRevisionCoordinator()
        reads.after = {
            if (reads.captures == 1) {
                reads.profile = reads.profile.copy(name = "new")
                coordinator.publishScopes(setOf("profile:31234"))
            }
        }
        val value = service(reads, coordinator).load(ReportQuery("31234", dates)).value()
        assertEquals(2, reads.captures)
        assertEquals("new", value.profile.name)
        assertEquals(1L, value.meta.dataRevision)
        reads.captures = 0
        reads.after = { coordinator.publishScopes(setOf("profile:39999")) }
        val unrelated = service(reads, coordinator).load(ReportQuery("31234", dates)).value()
        assertEquals(1, reads.captures)
        assertEquals(1L, unrelated.meta.dataRevision)
        assertEquals(2L, coordinator.marker())
    }

    @Test
    fun persistentRelatedRaceConflictsAfterThreeCapturesAndNeverWrites() = runBlocking {
        val reads = Reads()
        val coordinator = BusinessRevisionCoordinator()
        var writes = 0
        reads.after = { coordinator.publishScopes(setOf("calls:31234")) }
        val result =
            service(reads, coordinator, write = { _, _ -> writes++ })
                .exportCsv(ReportExportQuery("31234", dates), DocumentRef("memory"))
        assertEquals(ErrorCode.CONFLICT, result.error().code)
        assertEquals(3, reads.captures)
        assertEquals(0, writes)
    }

    @Test
    fun csvCaptureRetriesButExternalWriteRunsOnceOutsideReadLeaseAndFence() = runBlocking {
        val reads = Reads()
        val coordinator = BusinessRevisionCoordinator()
        var writes = 0
        reads.after = {
            if (reads.captures == 1) {
                reads.profile = reads.profile.copy(name = "new")
                coordinator.publishScopes(setOf("score"))
            }
        }
        val bytes = ByteArrayOutputStream()
        val api =
            service(
                reads,
                coordinator,
                write = { _, output ->
                    assertFalse(reads.active)
                    val acquired = java.util.concurrent.CountDownLatch(1)
                    val thread = Thread {
                        synchronized(coordinator.fence) { acquired.countDown() }
                    }
                        .apply { start() }
                    assertTrue(
                        "external output holds coordinator fence",
                        acquired.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    )
                    thread.join()
                    writes++
                    coordinator.publishScopes(setOf("calls:31234"))
                    output(bytes)
                },
            )
        val receipt =
            api.exportCsv(ReportExportQuery("31234", dates), DocumentRef("memory")).value()
        assertEquals(2, reads.captures)
        assertEquals(1, writes)
        assertEquals(1L, receipt.recordCount)
        assertTrue(bytes.toString("UTF-8").contains("\"new\""))
        assertArrayEquals(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            bytes.toByteArray().take(3).toByteArray(),
        )
    }

    @Test
    fun csvEscapesQuotesCommasNewlinesAndFormulaPrefixesAndIncludesUncountedDescending() =
        runBlocking {
            val reads = Reads()
            reads.profile = reads.profile.copy(name = " =sum(1,2)\n\"A\"", department = "@D")
            reads.rows =
                listOf(
                    ReportCallFact(row(1), null),
                    ReportCallFact(row(2, counted = false, result = DomainResult.PENDING), null),
                )
            val bytes = ByteArrayOutputStream()
            val receipt =
                service(reads, write = { _, output -> output(bytes) })
                    .exportCsv(ReportExportQuery("31234", dates), DocumentRef("memory"))
                    .value()
            val text = bytes.toString("UTF-8")
            assertEquals(2L, receipt.recordCount)
            assertEquals("text/csv", receipt.mimeType)
            assertEquals("America/New_York", receipt.range.zoneId)
            assertTrue(text.contains("\"' =sum(1,2)\n\"\"A\"\"\""))
            assertTrue(text.contains("\"'@D\""))
            assertTrue(text.indexOf("\"2\",\"'31234\"") < text.indexOf("\"1\",\"'31234\""))
            assertTrue(
                text.contains("\"PENDING\",\"SYSTEM_LOG_ONLY\",\"SYSTEM_CALL_LOG\",\"false\""),
            )
            assertTrue(text.endsWith("\r\n"))
        }

    @Test
    fun everyDeclaredReportDependencyIsRecheckedAndHomePersistentRaceConflicts() = runBlocking {
        for (key in
            setOf(
                "profile:31234",
                "calls:31234",
                "positions",
                "myPosition",
                "score",
                "schedule",
                "statistics",
                "runtime",
            )) {
            val reads = Reads()
            val coordinator = BusinessRevisionCoordinator()
            reads.after = { coordinator.publishScopes(setOf(key)) }
            assertEquals(
                key,
                ErrorCode.CONFLICT,
                service(reads, coordinator).load(ReportQuery("31234", dates)).error().code,
            )
            assertEquals(key, 3, reads.captures)
        }
        for (key in
            setOf(
                "calls",
                "schedule",
                "calendar",
                "overrides",
                "leaves",
                "quiet",
                "overlay",
                "statistics",
                "runtime",
                "ringtone",
            )) {
            val reads = Reads()
            val coordinator = BusinessRevisionCoordinator()
            reads.after = { coordinator.publishScopes(setOf(key)) }
            val api =
                HomeService(
                    reads,
                    coordinator,
                    Dispatchers.Unconfined,
                    clock,
                    { runtime },
                    { RingtoneSettings(false, AudioSource.BUILT_IN, "Ring", 5) },
                )
            assertEquals(key, ErrorCode.CONFLICT, api.load().error().code)
            assertEquals(key, 3, reads.captures)
        }
    }

    @Test
    fun outputFailureAndReadbackFailureNeverReplayExternalWrite() = runBlocking {
        for (readback in listOf(false, true)) {
            val reads = Reads()
            val coordinator = BusinessRevisionCoordinator()
            var writes = 0
            val api =
                service(
                    reads,
                    coordinator,
                    write = { _, output ->
                        writes++
                        if (readback) output(ByteArrayOutputStream())
                        coordinator.publishScopes(setOf("calls:31234"))
                        throw IOException(if (readback) "readback failed" else "write failed")
                    },
                )
            assertEquals(
                ErrorCode.IO_FAILURE,
                api.exportCsv(ReportExportQuery("31234", dates), DocumentRef("memory"))
                    .error()
                    .code,
            )
            assertEquals(1, reads.captures)
            assertEquals(1, writes)
        }
    }

    @Test
    fun homeRetriesRelevantFactsSamplesClockOnceAndIgnoresProfileScore() = runBlocking {
        val reads = Reads()
        val coordinator = BusinessRevisionCoordinator()
        var samples = 0
        var rings = 0
        var probes = 0
        val sampled =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant {
                    samples++
                    return Instant.ofEpochMilli(start + samples * 1000L)
                }
            }
        reads.after = {
            if (reads.captures == 1) {
                reads.quietMode = DomainQuietMode.NON_WORK
                coordinator.publishScopes(setOf("quiet"))
            }
        }
        val api =
            HomeService(
                reads,
                coordinator,
                Dispatchers.Unconfined,
                sampled,
                {
                    probes++
                    completeRuntimeStatus(runtime)
                },
                {
                    rings++
                    RingtoneSettings(false, AudioSource.BUILT_IN, "Ring", 5)
                },
            )
        val value = api.load().value()
        assertEquals(2, reads.captures)
        assertEquals(2, rings)
        assertEquals(2, probes)
        assertEquals(1, samples)
        assertEquals(start + 1000, value.meta.loadedAtEpochMillis)
        assertEquals(value.meta.loadedAtEpochMillis, value.currentWork.evaluatedAtEpochMillis)
        assertEquals(QuietMode.NON_WORK, value.quietMode)
        assertEquals(Capability.values().toSet(), value.runtime.capabilities.keys)
        try {
            (value.runtime.capabilities as MutableMap).clear()
            fail("mutable runtime")
        } catch (_: UnsupportedOperationException) {}
        reads.captures = 0
        reads.after = { coordinator.publishScopes(setOf("profile:31234", "score")) }
        val unrelated = api.load().value()
        assertEquals(1, reads.captures)
        assertEquals(1L, unrelated.meta.dataRevision)
    }
}
