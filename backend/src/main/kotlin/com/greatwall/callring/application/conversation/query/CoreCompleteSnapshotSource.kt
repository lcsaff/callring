package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.store.FullSnapshotReader
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.RuntimeStatus
import com.greatwall.callring.contracts.settings.AppearanceSettings
import com.greatwall.callring.contracts.settings.RingtoneSettings
import kotlinx.coroutines.*

class CoreCompleteSnapshotSource(
    private val reader: FullSnapshotReader,
    private val runtime: suspend () -> ApiResult<RuntimeStatus>,
    private val appearance: suspend () -> ApiResult<AppearanceSettings>,
    private val ringtone: suspend () -> ApiResult<RingtoneSettings>,
) {
    suspend fun capture(at: Long): Map<String, SnapshotPart> =
        withSnapshotSource("D01-D12,D21") {
            withContext(Dispatchers.IO) {
                    projectCoreSnapshot(
                        reader.capture(),
                        at,
                        runtime().requiredValue(),
                        appearance().requiredValue(),
                        ringtone().requiredValue(),
                    )
                }
                .mapValues { (id, data) ->
                    SnapshotPart(
                        data,
                        count = snapshotSourceCount(id, data),
                        scope =
                            if (id in setOf("D05", "D06", "D09", "D12")) "live_probe"
                            else "all_stored",
                    )
                }
        }
}
