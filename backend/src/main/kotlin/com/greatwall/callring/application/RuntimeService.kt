package com.greatwall.callring.application

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createRuntimeApi(context: BackendContext): RuntimeApi =
    with(context) {
        object : RuntimeApi {
            override suspend fun getStatus(): ApiResult<RuntimeStatus> = io { runtimeStatus() }

            override suspend fun getDiagnostics(): ApiResult<List<DiagnosticEntry>> = io {
                frozenList(runtimeProbe.getDiagnostics())
            }

            override suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt> = io {
                coordinator.guard(commandContext) {}
                capability(Capability.CALL_LOG)
                syncScheduler.requestSync(clock.millis())
            }
        }
    }
