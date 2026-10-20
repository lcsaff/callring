package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.runtime.*
import org.junit.Assert.*
import org.junit.Test

class T11RuntimeObservationTest {
    @Test fun clockOnlyReobservePublishesNothingAndFactChangesHaveExactEntities() {
        val coordinator = BusinessRevisionCoordinator()
        val observations = com.greatwall.callring.application.runtime.RuntimeObservationTracker(coordinator)
        val initial = RuntimeStatus(Capability.entries.associateWith { AccessState.GRANTED }, Audibility.AUDIBLE,
            PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 1)
        observations.observed(initial)
        observations.observed(initial.copy(checkedAtEpochMillis = 999))
        assertEquals(0L, coordinator.marker())
        val denied = initial.copy(capabilities = initial.capabilities + (Capability.CALL_LOG to AccessState.DENIED), checkedAtEpochMillis = 1000)
        observations.observed(denied)
        assertEquals(1L, coordinator.marker())
        assertEquals(setOf("status", "capability:CALL_LOG"), coordinator.invalidations.snapshots.value.domains.getValue(DataDomain.RUNTIME).entityRevisions.keys)
        observations.observed(denied.copy(checkedAtEpochMillis = 2000))
        assertEquals(1L, coordinator.marker())
    }
    @Test fun trackerPublishedPlaybackIsNeverRepublishedByPlatformReobserve() {
        val coordinator = BusinessRevisionCoordinator()
        val observations = com.greatwall.callring.application.runtime.RuntimeObservationTracker(coordinator)
        val initial = RuntimeStatus(emptyMap(), Audibility.AUDIBLE, PhoneActivity.IDLE,
            PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 1)
        observations.observed(initial)
        coordinator.publishRuntime(setOf("runtime:ringtonePlayback"))
        observations.observed(initial.copy(playback = PlaybackStatus(PlaybackMode.PREVIEW, PlaybackState.PLAYING)))
        assertEquals(1L, coordinator.marker())
    }
}
