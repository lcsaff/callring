package com.greatwall.callring

import com.greatwall.callring.application.BackendContext
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.createSettingsApi
import com.greatwall.callring.application.csvCell
import com.greatwall.callring.application.relation
import com.greatwall.callring.application.score
import com.greatwall.callring.domain.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression partitions carried from the retired Java policies into their production replacements.
 */
class DomainRegressionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // These helpers are pure: any accidental access to an external port fails immediately.
    private val calculations by lazy {
        BackendContext(
            unusedPort(),
            unusedPort(),
            unusedPort(),
            unusedPort(),
            unusedPort(),
            unusedPort(),
            Clock.fixed(Instant.parse("2026-10-14T02:00:00Z"), ZoneOffset.UTC),
            Dispatchers.Unconfined,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unusedPort(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw AssertionError("Pure calculation accessed external port: ${method.name}")
        } as T

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun state(): BackendState =
        BackendState().apply {
            schedules += defaultWorkSchedule(zone.id)
            calendarYears[2026] = CalendarYear(2026, "source", "2025-01-01", "2026.1")
        }

    private fun setWeekly(
        state: BackendState,
        weekly: Map<Int, List<TimeWindow>>,
        makeup: List<TimeWindow> = emptyList(),
    ) {
        state.schedules[0] = state.schedules.single().copy(weekly = weekly, makeup = makeup)
    }

    private fun work(state: BackendState, local: String) = WorkClassifier.classify(state, at(local))

    @Test
    fun defaultZoneFollowsThePhoneAndSplitShiftsKeepTheirExactBoundaries() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("UTC", defaultWorkSchedule().zoneId)
        } finally {
            TimeZone.setDefault(original)
        }
        val state = state()
        val cases =
            listOf(
                "2026-10-14T08:59:59" to WorkReason.OUTSIDE_WORK_HOURS,
                "2026-10-14T09:00:00" to WorkReason.WORKING,
                "2026-10-14T11:59:59" to WorkReason.WORKING,
                "2026-10-14T12:00:00" to WorkReason.OUTSIDE_WORK_HOURS,
                "2026-10-14T13:00:00" to WorkReason.WORKING,
                "2026-10-14T18:00:00" to WorkReason.OUTSIDE_WORK_HOURS,
                "2026-10-17T10:00:00" to WorkReason.REST_DAY,
                "2026-10-18T10:00:00" to WorkReason.REST_DAY,
            )
        for ((local, expected) in cases) assertEquals(local, expected, work(state, local).reason)
    }

    @Test
    fun manualRestAndHolidayStartSuppressOvernightCarryAndOfficialMakeup() {
        val friday = LocalDate.parse("2026-10-16")
        val saturday = friday.plusDays(1)
        val state = state()
        setWeekly(state, mapOf(5 to listOf(TimeWindow(1320, 120))), listOf(TimeWindow(540, 1080)))
        state.makeupDates += saturday
        state.overrides[saturday] = DateOverride(saturday, DateOverrideKind.REST, emptyList())
        assertEquals(WorkReason.REST_DAY, work(state, "2026-10-17T01:00:00").reason)
        assertEquals(WorkReason.REST_DAY, work(state, "2026-10-17T10:00:00").reason)
        state.overrides.clear()
        state.makeupDates.clear()
        state.holidays[friday] = "holiday on the start day"
        assertFalse(work(state, "2026-10-17T01:00:00").working)
    }

    @Test
    fun manualAndMakeupOvernightWindowsBelongToTheirStartDate() {
        val saturday = LocalDate.parse("2026-10-17")
        val manual = state()
        setWeekly(manual, emptyMap())
        manual.holidays[saturday] = "holiday replaced by manual work"
        manual.overrides[saturday] =
            DateOverride(saturday, DateOverrideKind.WORK, listOf(TimeWindow(1320, 120)))
        assertEquals(WorkReason.WORKING, work(manual, "2026-10-17T22:00:00").reason)
        assertTrue(work(manual, "2026-10-18T01:59:59").working)
        assertFalse(work(manual, "2026-10-18T02:00:00").working)

        val makeup = state()
        setWeekly(makeup, emptyMap(), listOf(TimeWindow(1320, 120)))
        makeup.makeupDates += saturday
        assertFalse(work(makeup, "2026-10-17T21:59:59").working)
        assertEquals(WorkReason.MAKEUP_WORKING, work(makeup, "2026-10-18T01:00:00").reason)
        assertFalse(work(makeup, "2026-10-18T02:00:00").working)
    }

    @Test
    fun crossingIntoAnUncoveredYearKeepsManualCarryButDoesNotTrustOfficialDates() {
        val state = state()
        val newYear = LocalDate.parse("2027-01-01")
        state.overrides[newYear.minusDays(1)] =
            DateOverride(newYear.minusDays(1), DateOverrideKind.WORK, listOf(TimeWindow(1320, 120)))
        state.holidays[newYear] = "unverified new year"
        val before = work(state, "2026-12-31T23:59:59")
        assertTrue(before.working)
        assertTrue(before.calendarVerified)
        val unknown = work(state, "2027-01-01T01:00:00")
        assertTrue(unknown.working)
        assertFalse(unknown.calendarVerified)
        assertNull(unknown.calendarVersion)
        assertFalse(work(state, "2027-01-01T02:00:00").working)
        state.calendarYears[2027] = CalendarYear(2027, "source", "2026-01-01", "2027.1")
        val verified = work(state, "2027-01-01T01:00:00")
        assertEquals(WorkReason.HOLIDAY, verified.reason)
        assertTrue(verified.calendarVerified)
        assertEquals("2027.1", verified.calendarVersion)
        state.overrides[newYear] = DateOverride(newYear, DateOverrideKind.REST, emptyList())
        assertEquals(WorkReason.REST_DAY, work(state, "2027-01-01T01:00:00").reason)
    }

    @Test
    fun officialDatesDoNotRepeatInLaterYearsAndUnknownYearsStillHonorManualWorkAndLeave() {
        val state = state()
        state.calendarYears[2027] = CalendarYear(2027, "source", "2026-01-01", "2027.1")
        state.holidays[LocalDate.parse("2026-01-01")] = "2026 only"
        assertEquals(WorkReason.HOLIDAY, work(state, "2026-01-01T10:00:00").reason)
        assertEquals(WorkReason.WORKING, work(state, "2027-01-01T10:00:00").reason)
        state.calendarYears.remove(2027)
        state.holidays[LocalDate.parse("2027-10-13")] = "unverified"
        assertTrue(work(state, "2027-10-13T10:00:00").working)
        val saturday = LocalDate.parse("2027-10-16")
        state.overrides[saturday] =
            DateOverride(saturday, DateOverrideKind.WORK, listOf(TimeWindow(540, 1080)))
        state.leaves[1] = LeaveRecord(1, at("2027-10-16T09:00:00"), at("2027-10-16T11:00:00"), "")
        val leave = work(state, "2027-10-16T10:00:00")
        assertEquals(WorkReason.PERSONAL_LEAVE, leave.reason)
        assertFalse(leave.calendarVerified)
        assertTrue(work(state, "2027-10-16T11:00:00").working)
    }

    @Test
    fun overlappingWindowsFormContinuousWorkAndUseTheConfiguredZone() {
        val state = state()
        setWeekly(state, mapOf(3 to listOf(TimeWindow(540, 660), TimeWindow(600, 720))))
        for (local in
            listOf(
                "2026-10-14T09:00:00",
                "2026-10-14T10:30:00",
                "2026-10-14T11:00:00",
                "2026-10-14T11:59:59",
            )) {
            assertTrue(local, work(state, local).working)
        }
        assertFalse(work(state, "2026-10-14T12:00:00").working)
        state.schedules[0] = state.schedules[0].copy(zoneId = "UTC")
        assertFalse(work(state, "2026-10-14T10:00:00").working)
        assertTrue(work(state, "2026-10-14T17:00:00").working)
    }

    @Test
    fun overlappingCrossMidnightLeavesRemainActiveUntilTheLastExclusiveEnd() {
        val state = state()
        setWeekly(state, mapOf(3 to listOf(TimeWindow(1320, 120))))
        state.leaves[1] =
            LeaveRecord(1, at("2026-10-14T23:00:00"), at("2026-10-15T01:00:00"), "first")
        state.leaves[2] =
            LeaveRecord(2, at("2026-10-15T00:30:00"), at("2026-10-15T01:30:00"), "second")
        assertTrue(work(state, "2026-10-14T22:59:59").working)
        for (local in
            listOf(
                "2026-10-14T23:00:00",
                "2026-10-15T00:00:00",
                "2026-10-15T01:00:00",
                "2026-10-15T01:29:59",
            )) {
            assertEquals(local, WorkReason.PERSONAL_LEAVE, work(state, local).reason)
        }
        assertTrue(work(state, "2026-10-15T01:30:00").working)
        assertFalse(work(state, "2026-10-15T02:00:00").working)
    }

    @Test
    fun multiDayLeaveContinuesAcrossYearBoundariesAndStopsAtNextMidnight() {
        val state = state()
        val end = LocalDate.parse("2027-01-02")
        state.overrides[end] = DateOverride(end, DateOverrideKind.WORK, listOf(TimeWindow(0, 60)))
        state.leaves[1] =
            LeaveRecord(1, at("2026-12-30T10:00:00"), at("2027-01-02T00:00:00"), "multi day")
        assertTrue(work(state, "2026-12-30T09:59:59").working)
        for (local in
            listOf(
                "2026-12-30T10:00:00",
                "2026-12-31T10:00:00",
                "2027-01-01T00:00:00",
                "2027-01-01T23:59:59",
            )) {
            assertEquals(local, WorkReason.PERSONAL_LEAVE, work(state, local).reason)
        }
        assertTrue(work(state, "2027-01-02T00:00:00").working)
    }

    @Test
    fun quietOffAndNonWorkRespectNumberScopeWhitelistAndWorkingTime() {
        data class Case(
            val mode: QuietMode,
            val number: String,
            val white: Boolean,
            val all: Boolean,
            val working: Boolean,
            val silence: Boolean,
        )
        val cases =
            listOf(
                Case(QuietMode.OFF, "31234", false, true, false, false),
                Case(QuietMode.NON_WORK, "31234", false, false, false, true),
                Case(QuietMode.NON_WORK, "31234", false, false, true, false),
                Case(QuietMode.NON_WORK, "123", false, false, false, false),
                Case(QuietMode.NON_WORK, "123", false, true, false, true),
                Case(QuietMode.NON_WORK, "123", true, true, false, false),
            )
        for (case in cases) {
            val state = state()
            state.quiet = QuietSettings(case.mode, case.all, emptyMap())
            if (case.white)
                state.profiles[case.number] = ProfileRecord(case.number, whitelisted = true)
            val time = if (case.working) "2026-10-14T10:00:00" else "2026-10-14T22:00:00"
            assertEquals(
                case.toString(),
                case.silence,
                DecisionSnapshot.capture(state).evaluate(case.number, at(time)).silence,
            )
        }
    }

    @Test
    fun customQuietOvernightRequiresBothItsWindowAndNonWorkClassification() {
        val state = state()
        state.quiet =
            QuietSettings(QuietMode.CUSTOM, false, mapOf(3 to listOf(TimeWindow(1320, 120))))
        val snapshot = DecisionSnapshot.capture(state)
        assertFalse(snapshot.evaluate("31234", at("2026-10-14T21:59:59")).silence)
        assertTrue(snapshot.evaluate("31234", at("2026-10-14T22:00:00")).silence)
        assertTrue(snapshot.evaluate("31234", at("2026-10-15T01:59:59")).silence)
        assertFalse(snapshot.evaluate("31234", at("2026-10-15T02:00:00")).silence)
        setWeekly(state, mapOf(3 to listOf(TimeWindow(1320, 120))))
        assertFalse(
            DecisionSnapshot.capture(state).evaluate("31234", at("2026-10-14T22:00:00")).silence
        )
    }

    private fun call(
        id: Long,
        time: Long,
        working: Boolean = true,
        counted: Boolean = true,
    ): CallDetail {
        val fact =
            ClassificationFact(
                working,
                if (working) WorkReason.WORKING else WorkReason.OUTSIDE_WORK_HOURS,
                null,
                true,
                "UTC",
                1,
                "v1",
                0,
            )
        return CallDetail(
            id,
            "31234",
            time,
            null,
            0,
            CallResult.ANSWERED,
            CallDecision.SYSTEM_LOG_ONLY,
            CallSource.SYSTEM_CALL_LOG,
            counted,
            fact,
            fact,
            false,
            null,
        )
    }

    private fun score(
        calls: List<CallDetail>,
        settings: ScoreSettings = ScoreSettings(5, 2, 3, 30),
        start: Long = 0,
        end: Long = Long.MAX_VALUE,
    ): ScoreSummary =
        calculations.score(
            BackendState().apply { score = settings },
            calls,
            TimeRange(start, end, "UTC"),
        )

    @Test
    fun equalTimestampCallsAddOneRepeatPerSuccessor() {
        val result = score(listOf(call(1, 10), call(2, 10), call(3, 10)))
        assertEquals(2L, result.repeatedCalls)
        assertEquals(96, result.points)
    }

    @Test
    fun repeatChainsUseAdjacentSortedCallsWithoutMutatingTheInput() {
        val input = listOf(call(1, 180000), call(2, 0), call(3, 120000), call(4, 60000))
        val originalOrder = input.map { it.id }
        val result = score(input)
        assertEquals(3L, result.repeatedCalls)
        assertEquals(94, result.points)
        assertEquals(originalOrder, input.map { it.id })
    }

    @Test
    fun rangeAndCountedEligibilityAreAppliedBeforeRepeatCounting() {
        val result =
            score(
                listOf(
                    call(1, 999, false),
                    call(2, 1000),
                    call(3, 1200, false, false),
                    call(4, 1500, false),
                    call(5, 2000, false),
                ),
                start = 1000,
                end = 2000,
            )
        assertEquals(2L, result.totalCalls)
        assertEquals(1L, result.offHoursCalls)
        assertEquals(1L, result.repeatedCalls)
        assertEquals(93, result.points)
    }

    @Test
    fun zeroWeightsKeepFullScoreAndExtremeTimestampDifferencesDoNotBecomeRepeats() {
        val free = score(listOf(call(1, 0, false), call(2, 1, false)), ScoreSettings(0, 0, 1, 30))
        assertEquals(100, free.points)
        val farApart =
            score(
                listOf(call(1, Long.MAX_VALUE - 1), call(2, Long.MIN_VALUE)),
                ScoreSettings(5, 2, Int.MAX_VALUE, 30),
                Long.MIN_VALUE,
                Long.MAX_VALUE,
            )
        assertEquals(2L, farApart.totalCalls)
        assertEquals(0L, farApart.repeatedCalls)
        assertEquals(100, farApart.points)
    }

    @Test
    fun invalidScoreWeightsAndIntervalsAreRejectedBeforeAnyStorageAccess() = runBlocking {
        val settings = createSettingsApi(calculations)
        val cases =
            listOf(
                ScoreSettings(-1, 2, 3, 30) to "offHoursWeight",
                ScoreSettings(5, -1, 3, 30) to "repeatWeight",
                ScoreSettings(5, 2, 0, 30) to "repeatMinutes",
                ScoreSettings(5, 2, -1, 30) to "repeatMinutes",
                ScoreSettings(5, 2, 3, 0) to "observationDays",
            )
        for ((input, field) in cases) {
            val failure = settings.saveScoreSettings(input) as ApiResult.Failure
            assertEquals(ErrorCode.VALIDATION, failure.error.code)
            assertTrue(failure.error.fieldErrors.containsKey(field))
        }
    }

    @Test
    fun normalizationRemovesOnlyLegacySeparatorsAndKeepsPrefixesAndNonAsciiDialCharacters() {
        val cases =
            listOf(
                " \t(31)-234\n" to "31234",
                "3\u00a012\u300034" to "31234",
                "+86 (31) 234" to "+8631234",
                "0086 31234" to "008631234",
                "3.12#34" to "3.12#34",
                "３１２３４" to "３１２３４",
            )
        for ((input, expected) in cases) assertEquals(
            input,
            expected,
            calculations.checkNumber(input).normalized,
        )
        assertFalse(calculations.checkNumber("+86 31234").matchesRule)
        assertFalse(calculations.checkNumber("0086 31234").matchesRule)
        assertFalse(calculations.checkNumber("３１２３４").validProfileNumber)
        assertFalse(calculations.checkNumber("3.12#34").validProfileNumber)
    }

    @Test
    fun profileNumberLengthAndStrictRuleBoundariesRemainIndependent() {
        for (input in listOf("0", "+0", "1".repeat(30), "+" + "1".repeat(30))) {
            assertTrue(input, calculations.checkNumber(input).validProfileNumber)
        }
        for (input in listOf("", " -() \n", "+", "1".repeat(31), "+" + "1".repeat(31))) {
            assertFalse(input, calculations.checkNumber(input).validProfileNumber)
        }
        for (input in listOf("30000", "39999", "3-1234\n")) assertTrue(
            input,
            calculations.checkNumber(input).matchesRule,
        )
        for (input in listOf("29999", "40000", "3000", "300000", "+31234")) assertFalse(
            input,
            calculations.checkNumber(input).matchesRule,
        )
    }

    @Test
    fun positionComparisonKeepsLowerSameAndMissingBaselineSemantics() {
        val state = BackendState()
        state.positions[1] = PositionRecord(1, "mine", 2, true)
        state.positions[2] = PositionRecord(2, "lower", 3, false)
        state.positions[3] = PositionRecord(3, "same", 2, true)
        state.myPositionId = 1
        assertEquals(PositionRelation.LOWER, calculations.relation(state, 2))
        assertEquals(PositionRelation.SAME, calculations.relation(state, 3))
        assertEquals(PositionRelation.UNKNOWN, calculations.relation(state, null))
        state.myPositionId = null
        assertEquals(PositionRelation.UNKNOWN, calculations.relation(state, 2))
    }

    @Test
    fun csvEscapesSignedAndWhitespacePrefixedFormulasAndQuotedMultilineData() {
        assertEquals("\"'+1\"", calculations.csvCell("+1"))
        assertEquals("\"'-1\"", calculations.csvCell("-1"))
        assertEquals("\"'  =1\"", calculations.csvCell("  =1"))
        assertEquals("\"'\tvalue\"", calculations.csvCell("\tvalue"))
        assertEquals("\"a,\"\"b\"\"\r\nnext\"", calculations.csvCell("a,\"b\"\r\nnext"))
        assertEquals("\"00123\"", calculations.csvCell("00123"))
    }
}
