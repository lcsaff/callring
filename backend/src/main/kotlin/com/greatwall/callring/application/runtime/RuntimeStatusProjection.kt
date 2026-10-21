package com.greatwall.callring.application.runtime

import com.greatwall.callring.application.mapping.frozenMap

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
