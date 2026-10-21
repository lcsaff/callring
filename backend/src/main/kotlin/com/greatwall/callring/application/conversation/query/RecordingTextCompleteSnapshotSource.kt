package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.contracts.ai.AiJson
import kotlinx.coroutines.*

class RecordingTextCompleteSnapshotSource(
    private val read: () -> Map<String, AiJson>,
) {
    suspend fun capture(at: Long): Map<String, SnapshotPart> =
        withSnapshotSource("D15,D16") {
            withContext(Dispatchers.IO) {
                read().mapValues { (id, data) ->
                    SnapshotPart(data, count = snapshotSourceCount(id, data))
                }
            }
        }
}
