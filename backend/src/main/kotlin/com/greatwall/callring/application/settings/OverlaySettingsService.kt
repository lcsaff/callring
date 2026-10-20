package com.greatwall.callring.application.settings

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.OverlayTransactions
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.CoroutineDispatcher

internal class OverlaySettingsService(private val transactions: OverlayTransactions,
    private val overlayAccess: () -> AccessState, coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher, private val afterCommit: (Boolean) -> Unit,
    private val command: CommandContext? = null) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator)
    suspend fun set(enabled: Boolean): ApiResult<Unit> {
        if (enabled) {
            val access = runner.io {
                val state = overlayAccess()
                if (state != AccessState.GRANTED && state != AccessState.NOT_REQUIRED)
                    throw com.greatwall.callring.application.ports.BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED,
                        "capability.required", requiredCapabilities = setOf(Capability.OVERLAY)))
            }
            if (access is ApiResult.Failure) return access
        }
        return runner.write {
            committer.commit(command, {
                transactions.writeOverlay { writer ->
                    val old = writer.overlay()
                    val changed = old != enabled && writer.saveOverlay(enabled)
                    MutationDelta(Unit, if (changed) setOf("overlay") else emptySet(),
                        if (changed) ChangeImpact.wide(setOf(DataDomain.OVERLAY_SETTINGS)) else ChangeImpact(emptyMap()), changed)
                }
            }) { afterCommit(enabled) }
        }
    }
}
