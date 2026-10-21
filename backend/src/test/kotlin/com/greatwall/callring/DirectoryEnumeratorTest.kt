package com.greatwall.callring

import com.greatwall.callring.application.conversation.query.DirectoryEnumerator
import com.greatwall.callring.application.conversation.query.DirectoryNode

import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryEnumeratorTest {
    @Test fun depthFourIsCompleteButDepthFiveWithChildrenFails() {
        fun source(max: Int) = DirectoryEnumerator { id ->
            val depth = id.toInt()
            if(depth < max) listOf(DirectoryNode((depth+1).toString(), "folder", "directory", 0, 0, true))
            else listOf(DirectoryNode("audio", "tail.wav", "audio/wav", 1, 1))
        }
        assertEquals("tail.wav", source(4).capture("0").single().name)
        assertTrue(runCatching { source(5).capture("0") }.isFailure)
        val emptyFifthLevel = DirectoryEnumerator { id -> if(id.toInt() < 5) listOf(DirectoryNode((id.toInt()+1).toString(), "folder", "directory", 0, 0, true)) else emptyList() }
        assertTrue(runCatching { emptyFifthLevel.capture("0") }.isFailure)
    }
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
