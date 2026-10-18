package com.greatwall.callring

import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryEnumeratorTest {
    @Test fun exactNodeLimitIsCompleteAndExtraNodeFails() {
        val entries = (1..500).map { DirectoryNode(it.toString(), "$it.wav", "audio/wav", 1, 1) }
        assertEquals(500, DirectoryEnumerator { entries }.capture("root").size)
        assertTrue(runCatching { DirectoryEnumerator { entries + DirectoryNode("extra", "extra", "text/plain", 1, 1) }.capture("root") }.isFailure)
    }
    @Test fun changingSourceAndCyclesAreErrors() {
        var count = 0L
        assertTrue(runCatching { DirectoryEnumerator { listOf(DirectoryNode("a", "a.wav", "audio/wav", count++, 1)) }.capture("root") }.isFailure)
        assertTrue(runCatching { DirectoryEnumerator { listOf(DirectoryNode("root", "loop", "directory", null, null, true)) }.capture("root") }.isFailure)
    }
}
