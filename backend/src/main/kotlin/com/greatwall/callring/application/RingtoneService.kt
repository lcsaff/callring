package com.greatwall.callring.application

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createRingtoneApi(context: BackendContext): RingtoneApi =
    with(context) {
        object : RingtoneApi {
            override suspend fun getSettings(): ApiResult<RingtoneSettings> = io {
                audio.getSettings()
            }

            override suspend fun setEnabled(enabled: Boolean): ApiResult<RingtoneSettings> = write {
                if (enabled) {
                    capability(Capability.CALL_SCREENING_ROLE, true)
                    capability(Capability.PHONE_STATE)
                }
                val before = coordinator.capture()
                val result = audio.setEnabled(enabled,commandContext)
                if(coordinator.capture() == before) coordinator.guard(commandContext) { notifyChanged(); coordinator.record(commandContext) }
                result
            }

            override suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings> {
                val callerContext = currentCoroutineContext()
                return io {
                    val before = coordinator.capture()
                    val result = audio.importAudio(source, { callerContext.ensureActive() }, commandContext)
                    // No suspension or cancellation check between publication and notification.
                    if(coordinator.capture() == before) coordinator.guard(commandContext) { notifyChanged(); coordinator.record(commandContext) }
                    result
                }
            }

            override suspend fun restoreBuiltIn(): ApiResult<RingtoneSettings> = write {
                val before = coordinator.capture()
                val result = audio.restoreBuiltIn(commandContext)
                if(coordinator.capture() == before) coordinator.guard(commandContext) { notifyChanged(); coordinator.record(commandContext) }
                result
            }

            override suspend fun startPreview(): ApiResult<Unit> = io {
                capability(Capability.PHONE_STATE)
                val status = runtimeStatus()
                if (status.phoneActivity != PhoneActivity.IDLE)
                    fail(ErrorCode.SYSTEM_RESTRICTED, "audio.phone_active")
                if (status.audibility != Audibility.AUDIBLE)
                    fail(ErrorCode.SYSTEM_RESTRICTED, "audio.system_inaudible")
                coordinator.guard(commandContext) {}
                audio.startPreview()
                try { coordinator.guard(commandContext) { coordinator.runtimeChanged(); coordinator.record(commandContext) } }
                catch(_: BackendFailure) { throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"audio.launch_state_changed")) }
            }

            override suspend fun stopPlayback(): ApiResult<Unit> = io {
                coordinator.guard(commandContext) {}
                audio.stopPlayback()
                try { coordinator.guard(commandContext) { coordinator.runtimeChanged(); coordinator.record(commandContext) } }
                catch(_: BackendFailure) { throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"audio.launch_state_changed")) }
            }
        }
    }
