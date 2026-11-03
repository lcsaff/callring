package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private class Feed : com.greatwall.callring.contracts.change.ChangeFeed {
        val state =
            kotlinx.coroutines.flow.MutableStateFlow(
                com.greatwall.callring.contracts.change.InvalidationSnapshot(
                    "home",
                    0,
                    0,
                    emptyMap(),
                ),
            )

        override fun snapshots() = state

        fun publish(domain: com.greatwall.callring.contracts.change.DataDomain) {
            val revision = state.value.revision + 1
            state.value =
                com.greatwall.callring.contracts.change.InvalidationSnapshot(
                    "home",
                    0,
                    revision,
                    state.value.domains +
                        (domain to
                            com.greatwall.callring.contracts.change.DomainInvalidation(
                                revision,
                                revision,
                            )),
                )
        }
    }

    @Test
    fun realLoaderReadsOnlyActualDependenciesAndNoopWritesDoNotReload() = runTest {
        val feed = Feed()
        var reads = 0
        var statisticsValue = false
        val api =
            object : UiFakeApi() {
                override val changes = feed

                override suspend fun load(): ApiResult.Success<HomeData> {
                    reads++
                    return super.load()
                }

                override suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit> {
                    if (statisticsValue != enabled) {
                        statisticsValue = enabled
                        feed.publish(
                            com.greatwall.callring.contracts.change.DataDomain.STATISTICS
                        )
                    }
                    return ApiResult.Success(Unit)
                }
            }
        val model = HomeViewModel(api, backgroundScope)
        runCurrent()
        assertEquals(1, reads)
        for (domain in
            listOf(
                com.greatwall.callring.contracts.change.DataDomain.CALLERS,
                com.greatwall.callring.contracts.change.DataDomain.POSITIONS,
                com.greatwall.callring.contracts.change.DataDomain.SCORE_SETTINGS,
            )) {
            feed.publish(domain)
            runCurrent()
            assertEquals(1, reads)
        }
        model.statistics(true)
        runCurrent()
        assertEquals(2, reads)
        model.statistics(true)
        runCurrent()
        assertEquals(2, reads)
        for (domain in
            listOf(
                com.greatwall.callring.contracts.change.DataDomain.CALLS,
                com.greatwall.callring.contracts.change.DataDomain.WORK_CALENDAR,
                com.greatwall.callring.contracts.change.DataDomain.QUIET_HOURS,
                com.greatwall.callring.contracts.change.DataDomain.STATISTICS,
                com.greatwall.callring.contracts.change.DataDomain.RUNTIME,
                com.greatwall.callring.contracts.change.DataDomain.RINGTONE,
            )) {
            val before = reads
            feed.publish(domain)
            runCurrent()
            assertEquals(before + 1, reads)
        }
    }

    @Test
    fun loadInFlightCoalescesCommitsAndDoesNotPublishMixedOldHome() = runTest {
        val feed = Feed()
        var reads = 0
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api =
            object : UiFakeApi() {
                override val changes = feed

                override suspend fun load(): ApiResult.Success<HomeData> {
                    val value =
                        super.load().value.copy(meta = SnapshotMeta(feed.state.value.revision, 0))
                    if (++reads == 1) first.await()
                    return ApiResult.Success(value)
                }
            }
        val model = HomeViewModel(api, backgroundScope)
        runCurrent()
        feed.publish(com.greatwall.callring.contracts.change.DataDomain.CALLS)
        feed.publish(com.greatwall.callring.contracts.change.DataDomain.RUNTIME)
        runCurrent()
        assertEquals(1, reads)
        first.complete(Unit)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(2L, model.state.value.data!!.meta.dataRevision)
    }

}
