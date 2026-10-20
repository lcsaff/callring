package com.greatwall.callring.application.recording

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.recording.RecordingFolderView

/** Provider grants may change outside a persisted configuration commit. */
class RecordingFolderObservation(private val coordinator: BusinessRevisionCoordinator) {
    private var previous: Pair<String?, RecordingFolderView>? = null
    fun reobserve(read: (String?) -> RecordingFolderView, active: () -> Boolean) {
        val baseline = coordinator.guard { previous } ?: return
        com.greatwall.callring.application.coordination.requireOperationActive(active)
        val folder = read(baseline.first)
        coordinator.guard {
            com.greatwall.callring.application.coordination.requireOperationActive(active)
            if (previous == baseline) observed(baseline.first, folder)
        }
    }
    fun observed(uri: String?, folder: RecordingFolderView): RecordingFolderView = coordinator.guard {
        val old = previous
        previous = uri to folder
        // Changing the selected folder has already been published by the durable writer.
        if (old != null && old.first == uri && old.second != folder)
            coordinator.publishRuntime(setOf("runtime:recordingFolder"))
        folder
    }
}
