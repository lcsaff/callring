package com.greatwall.callring

import com.greatwall.callring.application.runtime.completeRuntimeStatus
import com.greatwall.callring.contracts.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeReadinessTest {
    private fun projected(phone: AccessState?, screening: AccessState?): RuntimeStatus = completeRuntimeStatus(
        RuntimeStatus(buildMap {
            phone?.let { put(Capability.PHONE_STATE, it) }
            screening?.let { put(Capability.CALL_SCREENING_ROLE, it) }
        }, Audibility.SILENT, PhoneActivity.UNKNOWN, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), false, 0))

    private fun readiness(status: RuntimeStatus): String {
        val getter = status.javaClass.methods.firstOrNull { it.name == "getCoreReadiness" }
        assertNotNull("RuntimeStatus must expose the shared core readiness projection", getter)
        return getter!!.invoke(status).toString()
    }

    @Test fun onlyGrantedCoreConditionsAreReadyRegardlessOfOptionalPermissionsAndSound() {
        assertEquals("READY", readiness(projected(AccessState.GRANTED, AccessState.GRANTED)))
        assertEquals("MISSING", readiness(projected(AccessState.DENIED, AccessState.GRANTED)))
        assertEquals("UNAVAILABLE", readiness(projected(AccessState.GRANTED, AccessState.UNAVAILABLE)))
        assertEquals("UNKNOWN", readiness(projected(AccessState.UNKNOWN, AccessState.GRANTED)))
        assertEquals("UNKNOWN", readiness(projected(null, null)))
        assertEquals("UNKNOWN", readiness(projected(AccessState.NOT_REQUIRED, AccessState.GRANTED)))
    }

    @Test fun unavailableAndDeniedRemainDistinguishableWhenOtherCoreStatusIsUnknown() {
        assertEquals("UNAVAILABLE", readiness(projected(AccessState.UNKNOWN, AccessState.UNAVAILABLE)))
        assertEquals("MISSING", readiness(projected(AccessState.DENIED, AccessState.UNKNOWN)))
    }
}
