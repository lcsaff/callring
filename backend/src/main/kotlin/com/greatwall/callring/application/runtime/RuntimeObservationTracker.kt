package com.greatwall.callring.application.runtime

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.runtime.*

/** One baseline shared by all bound capabilities; playback has its own transition publisher. */
class RuntimeObservationTracker(private val coordinator: BusinessRevisionCoordinator) {
    private var previous: RuntimeStatus? = null
    fun observed(status: RuntimeStatus): RuntimeStatus = coordinator.guard {
        val old = previous
        previous = status
        if (old != null) {
            val keys = linkedSetOf<String>()
            if (old.capabilities != status.capabilities || old.audibility != status.audibility ||
                old.phoneActivity != status.phoneActivity || old.pinWidgetSupported != status.pinWidgetSupported) keys += "runtime:status"
            Capability.entries.forEach { capability ->
                if (old.capabilities[capability] != status.capabilities[capability]) keys += "runtime:capability:$capability"
            }
            if (old.pinWidgetSupported != status.pinWidgetSupported) keys += "runtime:pinWidget"
            coordinator.publishRuntime(keys)
        }
        status
    }
}
