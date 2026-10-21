package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.StatisticsInterval

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.domain.*
import java.time.Instant

/** Synthetic data only. All unrelated history belongs to B, so A has a fixed read closure. */
internal object LocalAccessFixture {
    const val TARGET = "31234"
    const val UNRELATED = "39999"
    val at: Long = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli()

    fun seed(store: SqliteCoreStorageResource, unrelatedCalls: Int) {
        require(unrelatedCalls in setOf(1_000, 10_000))
        store.fixtureSql { state ->
            state.seedPosition(PositionRecord(1L, "Synthetic role", 1, true))
            state.seedSequence("positions", 2)
            state.seedCaller(ProfileRecord(TARGET, "Synthetic A", "Fixture", 1L))
            state.seedCaller(ProfileRecord(UNRELATED, "Synthetic B", "Fixture", 1L))
            state.seedInterval(StatisticsInterval(0, null))
            val originalVersion = state.scheduleFacts().maxOf { it.configVersion }
            val history = state.scheduleFacts().maxBy { it.configVersion }.copy(
                configVersion = state.fixtureNextId("config_versions"), effectiveFromEpochMillis = at - 3_600_000,
            )
            state.seedSchedule(history)
            val version = history.configVersion
            val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", version, null, 0)
            val original = fact.copy(working = false, reason = WorkReason.OUTSIDE_WORK_HOURS,
                configVersion = originalVersion)
            for (index in 0..unrelatedCalls) {
                val id = index + 1L
                state.seedCall(RawCallRecord(
                    id, if (index == 0) TARGET else UNRELATED, at + index * 1_000L, 30,
                    CallResult.ANSWERED.toDomain(), CallDecision.SYSTEM_LOG_ONLY.toDomain(), CallSource.SYSTEM_CALL_LOG.toDomain(),
                    "synthetic-$id", null, true, fact.toDomain(), original.toDomain(),
                    legacyResult = if (index == unrelatedCalls) "synthetic-legacy-result" else null,
                ))
            }
            state.seedSequence("calls", unrelatedCalls + 2L)
        }
    }
}
