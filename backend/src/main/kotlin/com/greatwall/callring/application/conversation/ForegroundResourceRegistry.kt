package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*
import java.time.Clock
import java.util.UUID

class ForegroundResourceRegistry(private val clock: Clock = Clock.systemUTC()) {
    private data class Resource(val document: DocumentRef, val signature: String, val expires: Long, val generation: Long, var target: Long? = null)
    private val resources = linkedMapOf<String, Resource>()
    private var generation = 0L
    private fun signature(file: RecordingFile) = "${file.name}:${file.modifiedAt}:${file.size}:${file.modifiedKnown}"
    @Synchronized fun reset() { generation++; resources.clear() }
    @Synchronized fun lookup(handle:String):DocumentRef {
        val resource=requireNotNull(resources[handle]){"action.invalid_handle"}
        require(resource.expires>clock.millis()&&resource.generation==generation){"action.stale"}
        return resource.document
    }
    @Synchronized fun verify(handle:String,file:RecordingFile){val document=lookup(handle);require(document.uri==file.uri&&resources.getValue(handle).signature==signature(file)){"action.resource_changed"}}
    @Synchronized fun issue(file: RecordingFile): String {
        val signature = signature(file)
        resources.entries.removeAll { it.value.expires <= clock.millis() }
        return resources.entries.firstOrNull { it.value.document.uri == file.uri && it.value.signature == signature }?.key
            ?: UUID.randomUUID().toString().also { resources[it] = Resource(DocumentRef(file.uri), signature, clock.millis() + 600000, generation) }
    }
    @Synchronized fun resolve(handle: String, target: Long, currentFiles: List<RecordingFile>): DocumentRef {
        val resource = requireNotNull(resources[handle]) { "action.invalid_handle" }
        require(resource.expires > clock.millis() && resource.generation == generation && (resource.target == null || resource.target == target)) { "action.stale" }
        require(currentFiles.any { it.uri == resource.document.uri && signature(it) == resource.signature }) { "action.resource_changed" }
        resource.target = target
        return resource.document
    }
    fun project(files: List<RecordingFile>): AiJson = AiJson.Arr(files.map { aiObject("handle" to aiString(issue(it)), "name" to aiString(it.name), "modifiedAt" to if(it.modifiedKnown) aiNumber(it.modifiedAt) else AiJson.Null, "size" to aiNumber(it.size)) })
}
