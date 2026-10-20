package com.greatwall.callring.application.settings

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.store.AppearanceReader
import com.greatwall.callring.application.ports.store.SummaryTransactions
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher

internal class SettingsSummaryService(private val transactions: SummaryTransactions,
    private val preferences: AppearanceReader, private val coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher, private val clock: Clock) {
    private val runner = UseCaseRunner(dispatcher)
    suspend fun get(): ApiResult<SettingsSummary> = runner.io {
        repeat(3) {
            val before = coordinator.marker()
            val at = clock.millis()
            val appearance = preferences.getAppearance()
            val value = transactions.readSummary { it.settingsSummary() }
            if (coordinator.unchanged(setOf("score", "statistics", "overlay", "quiet", "myPosition", "positions", "schedule", "preferences"), before)) {
                return@io SettingsSummary(value.statisticsEnabled, value.overlayEnabled, value.quietMode.toContract(),
                    value.myPosition?.let { positionOption(it, it) }, value.zone, value.score.toContract(), appearance, SnapshotMeta(before, at))
            }
        }
        throw com.greatwall.callring.application.ports.BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
    }
}
