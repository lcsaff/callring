package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingPublicationTest {
    @Test fun independentIdentityBackgroundCasAndDeletePublishOnlyCommittedChanges() {
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(RuntimeEnvironment.getApplication(), "publication", cipher, coordinator)
        val before = coordinator.capture()
        val call = store.registerIncoming("a", "31234", 1000, null)
        assertNotEquals(before, coordinator.capture())
        val afterRegistration = coordinator.capture()
        store.registerIncoming("a", "31234", 1000, null)
        assertEquals(afterRegistration, coordinator.capture())
        val archive = store.get(call.id)!!
        assertFalse(store.compareAndSet(call.id, archive.generation + 1, archive))
        assertEquals(afterRegistration, coordinator.capture())
        assertTrue(store.compareAndSet(call.id, archive.generation, archive.copy(generation = archive.generation + 1, transcript = "complete-tail")))
        assertNotEquals(afterRegistration, coordinator.capture())
        store.delete(call.id)
        assertTrue(store.isDeleted(call.id))
        assertEquals(DataDomain.values().toSet(), coordinator.changes.value.domains)
    }
}
