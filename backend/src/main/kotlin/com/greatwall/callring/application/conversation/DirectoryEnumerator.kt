package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure

data class DirectoryNode(val id: String, val name: String, val mime: String, val modified: Long?, val size: Long?, val directory: Boolean = false)
class DirectoryEnumerator(private val query: (String) -> List<DirectoryNode>) {
    fun capture(root: String): List<DirectoryNode> {
        repeat(3) {
            val first = once(root)
            val second = once(root)
            if (first == second) return second.filter { !it.directory && (it.mime.startsWith("audio/") || it.name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "wav", "amr", "ogg", "aac", "flac")) }
        }
        fail("ai.snapshot_changed")
    }
    private fun once(root: String): List<DirectoryNode> {
        val seen = hashSetOf(root)
        val nodes = mutableListOf<DirectoryNode>()
        fun walk(id: String, depth: Int) {
            if (depth > 4) fail("recording.enumeration_limit")
            val children = query(id)
            for (child in children) {
                if (nodes.size >= 500) fail("recording.enumeration_limit")
                if (!seen.add(child.id)) fail("recording.enumeration_cycle")
                nodes += child
                if (child.directory) walk(child.id, depth + 1)
            }
        }
        walk(root, 0)
        return nodes.sortedBy { it.id }
    }
    private fun fail(key: String): Nothing = throw BackendFailure(ApiError(ErrorCode.IO_FAILURE, key, retryable = true))
}
