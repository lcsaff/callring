package com.greatwall.callring.data

import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.data.database.CallringDatabase
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.values
import com.greatwall.callring.data.query.SqliteScopedReadAdapter

import com.greatwall.callring.domain.model.defaultWorkSchedule
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.CoreStorageResource
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.StorageResources
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class QuerySnapshotConsistencyTest {
    @Test
    fun aggregateAndWorkRejectChangesToEveryNonPrimaryDependency() = runBlocking {
        for (key in listOf("score", "schedule", "overrides", "leaves")) {
            val coordinator = BusinessRevisionCoordinator()
            SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "dependency-race-$key.db")
                .use { physical ->
                    val original = physical.fixtureScopedReads(coordinator)
                    fun commit() {
                        physical.sessions.writeSession { writer ->
                            when (key) {
                                "score" ->
                                    writer.saveScore(writer.score().copy(offHoursWeight = 33))
                                "schedule" -> {
                                    val old =
                                        writer.latestWorkSchedule()
                                            ?: com.greatwall.callring.domain.model.defaultWorkSchedule(
                                                "UTC",
                                            )
                                    writer.appendWorkSchedule(
                                        old.copy(
                                            zoneId = "Asia/Tokyo",
                                            effectiveFromEpochMillis =
                                                old.effectiveFromEpochMillis + 1,
                                        ),
                                    )
                                }
                                "overrides" ->
                                    writer.putDateOverride(
                                        com.greatwall.callring.domain.model.DateOverride(
                                            java.time.LocalDate.now(),
                                            com.greatwall.callring.domain.model.DateOverrideKind
                                                .REST,
                                            emptyList(),
                                        ),
                                    )
                                else -> {
                                    writer.putLeave(
                                        com.greatwall.callring.domain.model.LeaveRecord(
                                            0,
                                            0,
                                            Long.MAX_VALUE,
                                            "race",
                                        ),
                                        true,
                                    )
                                    writer.incrementLeaveVersion()
                                }
                            }
                        }
                        coordinator.publishCore(
                            setOf(key),
                            ChangeImpact.wide(
                                setOf(
                                    if (key == "score") DataDomain.SCORE_SETTINGS
                                    else DataDomain.WORK_CALENDAR,
                                ),
                            ),
                        )
                    }
                    var sourceFailure: Throwable? = null
                    val source =
                        object : ScopedReadPort by original {
                            override fun aggregate(spec: AggregateSpec) =
                                try {
                                    original.aggregate(spec).also { commit() }
                                } catch (failure: Throwable) {
                                    sourceFailure = failure
                                    throw failure
                                }

                            override fun query(spec: QuerySpec, after: String?) =
                                try {
                                    original.query(spec, after).also { commit() }
                                } catch (failure: Throwable) {
                                    sourceFailure = failure
                                    throw failure
                                }
                        }
                    val query = QueryService(listOf(source), revision = coordinator::marker)
                    val result =
                        if (key in setOf("score", "schedule"))
                            query.aggregate(AggregateSpec(AiDomain.D05, "sumDuration"))
                        else query.query(QuerySpec(AiDomain.D06, "work"))
                    sourceFailure?.let { throw it }
                    assertTrue(key, result is ApiResult.Failure)
                    assertEquals(key, ErrorCode.CONFLICT, (result as ApiResult.Failure).error.code)
                }
        }
    }

    @Test
    fun leaveStatusSamplesClockOnceAndDisclosesLiveObservation() = runBlocking {
        val helper = CallringDatabase(RuntimeEnvironment.getApplication(), "leave-live-clock.db")
        try {
            val db = helper.writableDatabase
            db.insertOrThrow("leaves", null, values("start" to 10L, "end" to 20L, "note" to "time"))
            var samples = 0
            val source =
                SqliteScopedReadAdapter(
                    db,
                    { 0L },
                    {
                        samples++
                        if (samples == 1) 9L else 11L
                    },
                )
            val query = QueryService(listOf(source))
            val first = query.query(QuerySpec(AiDomain.D09)).requiredValue()
            assertEquals(1, samples)
            assertEquals("UPCOMING", first.items.single()["status"].string())
            assertEquals("live_probe", first.stamp.consistency)
            val second = query.query(QuerySpec(AiDomain.D09)).requiredValue()
            assertEquals(2, samples)
            assertEquals("ACTIVE", second.items.single()["status"].string())
            assertEquals(first.stamp.sourceVersion, second.stamp.sourceVersion)
        } finally {
            helper.close()
        }
    }

    @Test
    fun threeRelatedSqlChangesReturnConflictInsteadOfRetaggingOldFacts() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val physical =
            SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "snapshot-related-race.db")
        var reads = 0
        var race = true
        val resource =
            object : CoreStorageResource by physical {
                override val scoped =
                    object : ScopedReadPort by physical.scoped {
                        override fun query(spec: QuerySpec, after: String?): SourcePage {
                            val captured = physical.scoped.query(spec, after)
                            reads++
                            if (race) {
                                physical.sessions.writeQuiet { writer ->
                                    val old = writer.quiet()
                                    writer.saveQuiet(
                                        old.copy(
                                            settings =
                                                old.settings.copy(
                                                    allNumbers = !old.settings.allNumbers,
                                                ),
                                        ),
                                    )
                                }
                                coordinator.publishCore(
                                    setOf("quiet"),
                                    ChangeImpact.wide(setOf(DataDomain.QUIET_HOURS)),
                                )
                            }
                            return captured
                        }
                    }
            }
        val resources = StorageResources { resource }
        try {
            val query =
                QueryService(
                    listOf(resources.scopedReads(coordinator)),
                    revision = coordinator::marker,
                )
            val failed = query.query(QuerySpec(AiDomain.D10))
            assertTrue(failed is ApiResult.Failure)
            assertEquals(ErrorCode.CONFLICT, (failed as ApiResult.Failure).error.code)
            assertEquals(3, reads)
            assertNull(query.observedRevision(AiDomain.D10, "configuration"))
            race = false
            val value = query.query(QuerySpec(AiDomain.D10)).requiredValue()
            assertEquals(3L, value.stamp.sourceVersion)
            assertEquals(3L, value.stamp.scopeRevision)
            assertEquals(1L, value.items.single()["all_numbers"].string().toLong())
        } finally {
            resources.close()
        }
    }
}
