package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
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
                val result = audio.setEnabled(enabled)
                notifyChanged()
                result
            }

            override suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings> {
                val callerContext = currentCoroutineContext()
                return io {
                    val result = audio.importAudio(source) { callerContext.ensureActive() }
                    // No suspension or cancellation check between publication and notification.
                    notifyChanged()
                    result
                }
            }

            override suspend fun restoreBuiltIn(): ApiResult<RingtoneSettings> = write {
                val result = audio.restoreBuiltIn()
                notifyChanged()
                result
            }

            override suspend fun startPreview(): ApiResult<Unit> = io {
                capability(Capability.PHONE_STATE)
                val status = runtimeStatus()
                if (status.phoneActivity != PhoneActivity.IDLE)
                    fail(ErrorCode.SYSTEM_RESTRICTED, "audio.phone_active")
                if (status.audibility != Audibility.AUDIBLE)
                    fail(ErrorCode.SYSTEM_RESTRICTED, "audio.system_inaudible")
                audio.startPreview()
                notifyChanged()
            }

            override suspend fun stopPlayback(): ApiResult<Unit> = io {
                audio.stopPlayback()
                notifyChanged()
            }
        }
    }
