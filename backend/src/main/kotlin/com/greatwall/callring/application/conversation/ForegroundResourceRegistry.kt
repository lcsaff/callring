package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import java.time.Clock
import java.util.UUID

class ForegroundResourceRegistry(private val clock: Clock = Clock.systemUTC()) {
    private data class Resource(
        val document: DocumentRef,
        val signature: String,
        val expires: Long,
        val generation: Long,
        var target: Long? = null,
        var delivered: Boolean = true,
        var pending: Int = 0,
    )

    private val resources = linkedMapOf<String, Resource>()
    private var generation = 0L

    private fun signature(file: RecordingFile) =
        "${file.name}:${file.modifiedAt}:${file.size}:${file.modifiedKnown}"

    @Synchronized
    fun reset() {
        generation++
        resources.clear()
    }

    @Synchronized
    fun lookup(handle: String): DocumentRef {
        val resource = requireNotNull(resources[handle]) { "action.invalid_handle" }
        require(resource.expires > clock.millis() && resource.generation == generation) {
            "action.stale"
        }
        return resource.document
    }

    @Synchronized
    fun verify(handle: String, file: RecordingFile) {
        val document = lookup(handle)
        require(
            document.uri == file.uri && resources.getValue(handle).signature == signature(file),
        ) {
            "action.resource_changed"
        }
    }

    @Synchronized fun issue(file: RecordingFile): String = issue(file, false)

    @Synchronized fun issuePending(file: RecordingFile): String = issue(file, true)

    private fun issue(file: RecordingFile, pending: Boolean): String {
        val signature = signature(file)
        resources.entries.removeAll { it.value.expires <= clock.millis() }
        val existing =
            resources.entries.firstOrNull {
                it.value.document.uri == file.uri && it.value.signature == signature
            }
        if (existing != null) {
            if (pending) existing.value.pending++ else existing.value.delivered = true
            return existing.key
        }
        // The explicit full snapshot itself allows at most 20,000 items.
        if (resources.size >= 20000)
            throw com.greatwall.callring.application.ports.BackendFailure(
                ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.capacity_exceeded"),
            )
        return UUID.randomUUID().toString().also {
            resources[it] =
                Resource(
                    DocumentRef(file.uri),
                    signature,
                    clock.millis() + 600000,
                    generation,
                    delivered = !pending,
                    pending = if (pending) 1 else 0,
                )
        }
    }

    @Synchronized
    fun delivered(handle: String) {
        resources[handle]?.let {
            it.delivered = true
            if (it.pending > 0) it.pending--
        }
    }

    @Synchronized
    fun discardPending(handle: String) {
        resources[handle]?.let {
            if (it.pending > 0) it.pending--
            if (!it.delivered && it.pending == 0) resources.remove(handle)
        }
    }

    @Synchronized
    fun resolve(handle: String, target: Long, currentFiles: List<RecordingFile>): DocumentRef {
        val resource = requireNotNull(resources[handle]) { "action.invalid_handle" }
        require(
            resource.expires > clock.millis() &&
                resource.generation == generation &&
                (resource.target == null || resource.target == target),
        ) {
            "action.stale"
        }
        require(
            currentFiles.any {
                it.uri == resource.document.uri && signature(it) == resource.signature
            },
        ) {
            "action.resource_changed"
        }
        resource.target = target
        return resource.document
    }

    suspend fun resolveScoped(
        handle: String,
        target: Long,
        probe: suspend (DocumentRef) -> RecordingFile,
    ): DocumentRef {
        val document = lookup(handle)
        val file = probe(document)
        return synchronized(this) {
            verify(handle, file)
            val resource = resources.getValue(handle)
            require(resource.target == null || resource.target == target) { "action.stale" }
            resource.target = target
            document
        }
    }

    fun snapshotPart(files: List<RecordingFile>, status: SnapshotDomainStatus): SnapshotPart {
        if (files.size > 20000)
            throw com.greatwall.callring.application.ports.BackendFailure(
                ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.context_budget_exceeded"),
            )
        val handles = mutableListOf<String>()
        try {
            val rows = files.map { file ->
                val handle = issuePending(file)
                handles += handle
                aiObject(
                    "handle" to aiString(handle),
                    "name" to aiString(file.name),
                    "modifiedAt" to
                        if (file.modifiedKnown) aiNumber(file.modifiedAt) else AiJson.Null,
                    "size" to aiNumber(file.size),
                )
            }
            return SnapshotPart(
                AiJson.Arr(rows),
                status,
                "provider_walk_not_atomic",
                files.size.toLong(),
                publishResources = { handles.forEach { delivered(it) } },
                discardResources = { handles.forEach { discardPending(it) } },
            )
        } catch (failure: Throwable) {
            handles.forEach { discardPending(it) }
            throw failure
        }
    }

    fun project(files: List<RecordingFile>): AiJson =
        AiJson.Arr(
            files.map {
                aiObject(
                    "handle" to aiString(issue(it)),
                    "name" to aiString(it.name),
                    "modifiedAt" to if (it.modifiedKnown) aiNumber(it.modifiedAt) else AiJson.Null,
                    "size" to aiNumber(it.size),
                )
            },
        )
}
