package com.greatwall.callring.contracts.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*

enum class CaptureMode { SYSTEM_ONLY, MICROPHONE_EXPERIMENTAL }

enum class ArchiveStatus { WAITING_RECORDING, READY, QUEUED, TRANSCRIBING, ANALYZING, COMPLETE, FAILED }

enum class RecordingSource { SYSTEM, IMPORTED, MICROPHONE }

data class CallAnalysis(
    val name: String = "", val organization: String = "", val position: String = "",
    val purpose: String = "", val evidence: String = "", val inferredIdentity: String = "",
    val summary: String = "", val actionItems: List<String> = emptyList(),
)

data class CallArchive(
    val callId: Long, val number: String, val startedAtEpochMillis: Long,
    val generation: Long = 0, val status: ArchiveStatus = ArchiveStatus.WAITING_RECORDING,
    val source: RecordingSource = RecordingSource.IMPORTED, val audioPath: String? = null,
    val audioQuality: String = "recording.unverified", val durationMillis: Long? = null,
    val transcript: String = "", val analysis: CallAnalysis? = null,
    val error: String? = null, val attempts: Int = 0, val nextAttemptAt: Long = 0,
    val analyzedAt: Long? = null, val analysisModel: String = "", val transcriptionModel: String = "",
    val analysisProtocol: AiProtocol? = null,
    val manualAnalysis: Boolean = false,
)

data class RecordingFile(val uri: String, val name: String, val modifiedAt: Long, val size: Long? = null, val modifiedKnown: Boolean = true)

interface RecordingApi {
    suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity>
    suspend fun queryArchives(query: ArchiveListQuery = ArchiveListQuery()): ApiResult<ArchiveListPage>
    suspend fun getArchive(callId: Long): ApiResult<CallArchive?>
    suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED): ApiResult<CallArchive>
    suspend fun retry(callId: Long): ApiResult<Unit>
    suspend fun delete(callId: Long): ApiResult<Unit>
    suspend fun play(callId: Long): ApiResult<Unit>
    suspend fun stop(): ApiResult<Unit>
    suspend fun setSystemFolder(document: DocumentRef): ApiResult<Unit>
    suspend fun systemRecordings(): ApiResult<List<RecordingFile>>
    suspend fun inspectSystemRecording(document:DocumentRef):ApiResult<RecordingFile>
}

data class RecordingIdentity(val callId: Long, val number: String, val startedAtEpochMillis: Long)

/** Small list projection. Full transcripts, paths and analysis bodies are detail-only. */
data class ArchiveListItem(
    val callId: Long, val generation: Long, val number: String, val startedAtEpochMillis: Long,
    val status: ArchiveStatus, val source: RecordingSource, val durationMillis: Long?,
    val hasAudio: Boolean, val summary: String,
)

/** A cursor is valid only for its owner, archive revision and page size. */
data class ArchiveListCursor(
    val incarnation: String, val revision: Long, val startedAtEpochMillis: Long, val callId: Long, val limit: Int,
)
data class ArchiveListQuery(val cursor: ArchiveListCursor? = null, val limit: Int = 50)
data class ArchiveListPage(
    val items: List<ArchiveListItem>, val nextCursor: ArchiveListCursor?, val complete: Boolean,
    val preparing: Boolean = false, val remaining: Long = 0,
)
