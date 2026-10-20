package com.greatwall.callring.application.ringtone

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.completeRuntimeStatus
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.runtime.requireCapability
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.*

class RingtoneService(
    private val audio: AudioController,
    private val status: () -> RuntimeStatus,
    private val coordinator: BusinessRevisionCoordinator,
    private val dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null,
) : RingtoneApi {
    private val runner = UseCaseRunner(dispatcher)
    fun bound(command: CommandContext) = RingtoneService(audio, status, coordinator, dispatcher, command)
    override suspend fun getSettings() = runner.io { audio.getSettings() }
    private fun publish() = coordinator.publishResource("ringtone", setOf("ringtone"), ChangeImpact.wide(setOf(DataDomain.RINGTONE)))
    override suspend fun setEnabled(enabled: Boolean) = runner.write {
        if (enabled) {
            requireCapability(completeRuntimeStatus(status()), Capability.CALL_SCREENING_ROLE, true)
            requireCapability(completeRuntimeStatus(status()), Capability.PHONE_STATE)
        }
        val saved = coordinator.guard(command) {
            val before = audio.getSettings()
            val result = if (before.enabled == enabled) before else audio.setEnabled(enabled)
            if (before != result) publish()
            coordinator.record(command)
            result
        }
        if (!enabled) runCatching { audio.stopPlayback() }
        saved
    }
    override suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings> {
        val caller = currentCoroutineContext()
        return runner.io {
            audio.prepareAudio(source) { caller.ensureActive() }.use { prepared ->
                coordinator.guard(command) {
                    val saved = prepared.commit { caller.ensureActive() }
                    // A new audio file is an actual change even when its public metadata is equal.
                    publish()
                    coordinator.record(command)
                    saved
                }
            }
        }
    }
    override suspend fun restoreBuiltIn() = runner.write {
        val saved = coordinator.guard(command) {
            val before = audio.getSettings()
            val result = audio.restoreBuiltIn()
            if (before != result) publish()
            coordinator.record(command)
            result
        }
        runCatching { audio.stopPlayback() }
        saved
    }
    override suspend fun startPreview() = runner.io {
        requireCapability(completeRuntimeStatus(status()), Capability.PHONE_STATE)
        val current = completeRuntimeStatus(status())
        if (current.phoneActivity != PhoneActivity.IDLE) throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.phone_active"))
        if (current.audibility != Audibility.AUDIBLE) throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.system_inaudible"))
        launchPlayback(audio::startPreview)
    }
    override suspend fun stopPlayback() = runner.io { launchPlayback(audio::stopPlayback) }
    private fun launchPlayback(launch: () -> Unit) {
        coordinator.guard(command) {}
        launch()
        try { coordinator.guard(command) { coordinator.record(command) } }
        catch (_: BackendFailure) { throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.launch_state_changed")) }
    }
}
