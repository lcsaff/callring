package com.greatwall.callring.data

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.*
import java.time.Instant

/** Synthetic data only. All unrelated history belongs to B, so A has a fixed read closure. */
internal object LocalAccessFixture {
    const val TARGET = "31234"
    const val UNRELATED = "39999"
    val at: Long = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli()

    fun seed(store: SqliteBackendStore, unrelatedCalls: Int) {
        require(unrelatedCalls in setOf(1_000, 10_000))
        store.transaction { state ->
            state.positions[1L] = PositionRecord(1L, "Synthetic role", 1, true)
            state.nextPositionId = 2
            state.profiles[TARGET] = ProfileRecord(TARGET, "Synthetic A", "Fixture", 1L)
            state.profiles[UNRELATED] = ProfileRecord(UNRELATED, "Synthetic B", "Fixture", 1L)
            state.statisticsIntervals += StatisticsInterval(0, null)
            val originalVersion = state.schedules.maxOf { it.configVersion }
            val history = state.schedules.maxBy { it.configVersion }.copy(
                configVersion = state.nextConfigVersion++, effectiveFromEpochMillis = at - 3_600_000,
            )
            state.schedules += history
            val version = history.configVersion
            val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", version, null, 0)
            val original = fact.copy(working = false, reason = WorkReason.OUTSIDE_WORK_HOURS,
                configVersion = originalVersion)
            for (index in 0..unrelatedCalls) {
                val id = index + 1L
                state.calls[id] = RawCallRecord(
                    id, if (index == 0) TARGET else UNRELATED, at + index * 1_000L, 30,
                    CallResult.ANSWERED, CallDecision.SYSTEM_LOG_ONLY, CallSource.SYSTEM_CALL_LOG,
                    "synthetic-$id", null, true, fact, original,
                    legacyResult = if (index == unrelatedCalls) "synthetic-legacy-result" else null,
                )
            }
            state.nextCallId = unrelatedCalls + 2L
        }
    }
}
