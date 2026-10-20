package com.greatwall.callring.application

import com.greatwall.callring.contracts.runtime.*

internal fun completeRuntimeStatus(status: RuntimeStatus): RuntimeStatus =
    status.copy(
        capabilities =
            frozenMap(
                Capability.values().associateWith {
                    status.capabilities[it] ?: AccessState.UNKNOWN
                },
            ),
    )
