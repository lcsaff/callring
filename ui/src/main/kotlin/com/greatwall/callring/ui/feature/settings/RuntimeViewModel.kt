package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.SnapshotViewModel
import kotlinx.coroutines.CoroutineScope

data class RuntimeUiData(val status: RuntimeStatus, val diagnostics: List<DiagnosticEntry>, val diagnosticsError: ApiError?)

class RuntimeViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<RuntimeUiData>(
    api.changes, setOf(DataDomain.RUNTIME), load = {
        when (val status = api.runtime.getStatus()) {
            is ApiResult.Failure -> status
            is ApiResult.Success -> when (val diagnostics = api.runtime.getDiagnostics()) {
                is ApiResult.Success -> ApiResult.Success(RuntimeUiData(status.value, diagnostics.value, null))
                is ApiResult.Failure -> ApiResult.Success(RuntimeUiData(status.value, emptyList(), diagnostics.error))
            }
        }
    }, testScope = testScope, entities = mapOf(DataDomain.RUNTIME to setOf("status", "ringtonePlayback", "diagnostics"))
) {
    fun synchronize() = write({ api.runtime.requestCallLogSync() }, "已提交同步请求")
}
