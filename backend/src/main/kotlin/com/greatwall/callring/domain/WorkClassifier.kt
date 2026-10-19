package com.greatwall.callring.domain

import com.greatwall.callring.application.api.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every evaluation uses the schedule effective at the event, together with corrected calendar
 * facts.
 */
object WorkClassifier {
    val evaluations=java.util.concurrent.atomic.AtomicLong()
    fun schedule(state: BackendState, at: Long): WorkScheduleSettings =
        state.schedules
            .filter { it.effectiveFromEpochMillis <= at }
            .maxWithOrNull(
                compareBy<WorkScheduleSettings> { it.effectiveFromEpochMillis }
                    .thenBy { it.configVersion }
            )
            ?: state.schedules.minByOrNull { it.effectiveFromEpochMillis }
            ?: defaultWorkSchedule()

    fun classify(state: BackendState, at: Long): ClassificationFact {
        evaluations.incrementAndGet()
        val config = schedule(state, at)
        val local = Instant.ofEpochMilli(at).atZone(ZoneId.of(config.zoneId))
        val date = local.toLocalDate()
        val calendar = state.calendarYears[date.year]
        fun fact(working: Boolean, reason: WorkReason, holiday: String? = null) =
            ClassificationFact(
                working,
                reason,
                holiday,
                calendar != null,
                config.zoneId,
                config.configVersion,
                calendar?.version,
                state.leaveVersion,
            )
        if (state.leaves.values.any { at >= it.startMillis && at < it.endMillis })
            return fact(false, WorkReason.PERSONAL_LEAVE)
        val today = plan(state, config, date)
        if (today.explicitRest) return fact(false, today.reason, today.holiday)
        val minute = local.hour * 60 + local.minute
        if (inStart(today.windows, minute)) return fact(true, today.reason)
        val previous = plan(state, config, date.minusDays(1))
        if (inCarry(previous.windows, minute)) return fact(true, previous.reason)
        return fact(
            false,
            if (today.windows.isEmpty()) WorkReason.REST_DAY else WorkReason.OUTSIDE_WORK_HOURS,
        )
    }

    fun reclassify(state: BackendState) {
        state.calls.replaceAll { _, call ->
            val updated = classify(state, call.time)
            if (updated == call.classification) call else call.copy(classification = updated)
        }
    }

    fun status(state: BackendState, at: Long): WorkStatus {
        val current = classify(state, at)
        val config = schedule(state, at)
        val zone = ZoneId.of(config.zoneId)
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val candidates = sortedSetOf<Long>()
        fun addLocalBoundary(local: java.time.LocalDateTime) {
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.isEmpty()) {
                // A skipped local minute becomes effective at the forward transition itself.
                zone.rules.getTransition(local)?.instant?.toEpochMilli()?.let { candidates += it }
            } else {
                // Overlap windows occur twice; the later offset can be the next real boundary.
                offsets.forEach { candidates += local.toInstant(it).toEpochMilli() }
            }
        }
        // Include complete local windows and zone transitions, not fixed-length day arithmetic.
        for (offset in -1L..8L) {
            val day = date.plusDays(offset)
            addLocalBoundary(day.plusDays(1).atStartOfDay())
            for (window in plan(state, config, day).windows) {
                addLocalBoundary(day.atStartOfDay().plusMinutes(window.startMinute.toLong()))
                val endDay = if (window.startMinute > window.endMinute) day.plusDays(1) else day
                addLocalBoundary(endDay.atStartOfDay().plusMinutes(window.endMinute.toLong()))
            }
        }
        for (leave in state.leaves.values) {
            candidates += leave.startMillis
            candidates += leave.endMillis
        }
        for (schedule in state.schedules) candidates += schedule.effectiveFromEpochMillis
        zone.rules.nextTransition(Instant.ofEpochMilli(at))?.instant?.toEpochMilli()?.let {
            candidates += it
        }
        val next = candidates.firstOrNull {
            it > at && (classify(state, it) != current || Instant.ofEpochMilli(it).atZone(zone).toLocalDate() != date)
        }
        val today = plan(state, config, date)
        val dayKind = when {
            today.reason == WorkReason.HOLIDAY -> WorkDayKind.HOLIDAY
            today.explicitRest || today.windows.isEmpty() -> WorkDayKind.REST_DAY
            else -> WorkDayKind.WORKDAY
        }
        return WorkStatus(at, current, next, dayKind, today.holiday)
    }

    fun inWeekly(windows: Map<Int, List<TimeWindow>>, zone: ZoneId, at: Long): Boolean {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val day = local.dayOfWeek.value
        val minute = local.hour * 60 + local.minute
        return inStart(windows[day].orEmpty(), minute) ||
            inCarry(windows[if (day == 1) 7 else day - 1].orEmpty(), minute)
    }

    private data class Plan(
        val windows: List<TimeWindow>,
        val reason: WorkReason,
        val explicitRest: Boolean = false,
        val holiday: String? = null,
    )

    private fun plan(state: BackendState, config: WorkScheduleSettings, date: LocalDate): Plan {
        state.overrides[date]?.let {
            return Plan(
                it.windows,
                if (it.kind == DateOverrideKind.REST) WorkReason.REST_DAY else WorkReason.WORKING,
                it.kind == DateOverrideKind.REST,
            )
        }
        if (date.year in state.calendarYears) {
            state.holidays[date]?.let {
                return Plan(emptyList(), WorkReason.HOLIDAY, true, it)
            }
            if (date in state.makeupDates) return Plan(config.makeup, WorkReason.MAKEUP_WORKING)
        }
        return Plan(config.weekly[date.dayOfWeek.value].orEmpty(), WorkReason.WORKING)
    }

    private fun inStart(windows: List<TimeWindow>, minute: Int) =
        windows.any {
            if (it.startMinute < it.endMinute) minute >= it.startMinute && minute < it.endMinute
            else minute >= it.startMinute
        }

    private fun inCarry(windows: List<TimeWindow>, minute: Int) =
        windows.any { it.startMinute > it.endMinute && minute < it.endMinute }
}
