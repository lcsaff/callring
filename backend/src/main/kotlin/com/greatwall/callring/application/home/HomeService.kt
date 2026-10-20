package com.greatwall.callring.application.home

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.callers.callDetail
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.HomeReads
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.RuntimeStatus
import com.greatwall.callring.contracts.settings.RingtoneSettings
import com.greatwall.callring.domain.WorkClassifier
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal class HomeService(
    private val reads: HomeReads,
    private val coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val runtime: () -> RuntimeStatus,
    private val ringtone: () -> RingtoneSettings,
) : HomeApi {
    private val runner = UseCaseRunner(dispatcher)

    override suspend fun load(): ApiResult<HomeData> = runner.io {
        val now = clock.millis()
        val keys =
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
            )
        repeat(3) {
            val before = coordinator.marker()
            val ring = ringtone()
            val status = runtime()
            val value = reads.readHome { reader ->
                HomeData(
                    ring,
                    status,
                    WorkClassifier.status(reader.workHistory(), now).toContract(),
                    reader.latestVisibleReportCall()?.let { callDetail(it.current, it.screening) },
                    reader.statisticsEnabled(),
                    reader.overlay(),
                    reader.homeQuietMode().toContract(),
                    SnapshotMeta(before, now),
                )
            }
            if (coordinator.unchanged(keys, before)) return@io value
        }
        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    }
}
