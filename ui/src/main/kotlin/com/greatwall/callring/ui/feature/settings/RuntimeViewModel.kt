package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.SnapshotViewModel

data class RuntimeUiData(val status: RuntimeStatus, val diagnostics: List<DiagnosticEntry>, val diagnosticsError: ApiError?)

class RuntimeViewModel(private val api: CallringUiApi) : SnapshotViewModel<RuntimeUiData>(
    api.changes, setOf(DataDomain.RUNTIME, DataDomain.RINGTONE), load = {
        when (val status = api.runtime.getStatus()) {
            is ApiResult.Failure -> status
            is ApiResult.Success -> when (val diagnostics = api.runtime.getDiagnostics()) {
                is ApiResult.Success -> ApiResult.Success(RuntimeUiData(status.value, diagnostics.value, null))
                is ApiResult.Failure -> ApiResult.Success(RuntimeUiData(status.value, emptyList(), diagnostics.error))
            }
        }
    }
) {
    fun synchronize() = write({ api.runtime.requestCallLogSync() })
}
