package com.greatwall.callring

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import org.junit.Assert.*
import org.junit.Test

class ForegroundResourceRegistryTest {
    @Test fun changedOrRemovedFileCannotReuseIssuedHandle() {
        val registry = ForegroundResourceRegistry()
        val file = RecordingFile("content://fixture/one", "one.wav", 10, 20)
        val handle = registry.issue(file)
        assertEquals(file.uri, registry.resolve(handle, 1, listOf(file)).uri)
        assertTrue(runCatching { registry.resolve(handle, 1, listOf(file.copy(size = 21))) }.isFailure)
        assertTrue(runCatching { registry.resolve(handle, 1, emptyList()) }.isFailure)
        assertTrue(runCatching { registry.resolve(handle, 2, listOf(file)) }.isFailure)
        registry.reset()
        assertTrue(runCatching { registry.resolve(handle, 1, listOf(file)) }.isFailure)
    }
}
