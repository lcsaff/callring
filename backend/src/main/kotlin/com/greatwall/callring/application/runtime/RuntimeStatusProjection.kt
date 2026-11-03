package com.greatwall.callring.application.runtime

import com.greatwall.callring.application.mapping.frozenMap

import com.greatwall.callring.contracts.runtime.*

internal fun completeRuntimeStatus(status: RuntimeStatus): RuntimeStatus =
    status.copy(
        coreReadiness = coreReadiness(status.capabilities),
        capabilities =
            frozenMap(
                Capability.values().associateWith {
                    status.capabilities[it] ?: AccessState.UNKNOWN
                },
            ),
    )

/** Only these prerequisites determine the home warning; optional permissions and sound do not. */
private fun coreReadiness(capabilities: Map<Capability, AccessState>): CoreReadiness {
    val access = listOf(Capability.PHONE_STATE, Capability.CALL_SCREENING_ROLE)
        .map { capabilities[it] ?: AccessState.UNKNOWN }
    return when {
        AccessState.UNAVAILABLE in access -> CoreReadiness.UNAVAILABLE
        AccessState.DENIED in access -> CoreReadiness.MISSING
        access.all { it == AccessState.GRANTED } -> CoreReadiness.READY
        else -> CoreReadiness.UNKNOWN
    }
}
