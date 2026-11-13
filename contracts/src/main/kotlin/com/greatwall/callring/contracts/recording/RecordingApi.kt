package com.greatwall.callring.contracts.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*

enum class CaptureMode { SYSTEM_ONLY, MICROPHONE_EXPERIMENTAL }

enum class ArchiveStatus { WAITING_RECORDING, READY, QUEUED, TRANSCRIBING, ANALYZING, COMPLETE, FAILED, ENRICHING, ENRICHMENT_FAILED }

enum class RecordingSource { SYSTEM, IMPORTED, MICROPHONE }

enum class OfflineTranscriptionPhase { PREPARING, TRANSCRIBING, CANCELLING, FAILED }

data class OfflineTranscriptionRun(
    val id: Long, val phase: OfflineTranscriptionPhase, val error: String? = null,
)

/** Ineligible recording numbers retain analysis but never access caller storage. */
data class CallerIdentityBaseline(val epoch: String, val revisions: Map<String, Long>, val eligible: Boolean = true)

data class CallAnalysis(
    val name: String = "", val organization: String = "", val position: String = "",
    val purpose: String = "", val evidence: String = "", val inferredIdentity: String = "",
    val summary: String = "", val actionItems: List<String> = emptyList(),
    val department: String = "", val region: String = "",
    val fieldEvidence: Map<String, String> = emptyMap(),
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
    val enrichmentBaseline: CallerIdentityBaseline? = null,
    val enrichmentSourceGeneration: Long? = null,
    val offlineTranscription: OfflineTranscriptionRun? = null,
    val analysisIntent: ArchiveAnalysisIntent = ArchiveAnalysisIntent.AUTOMATIC,
    val manualAnalysisState: ManualAnalysisState? = null,
    val manualAnalysisResultVersion: Long? = null,
    val direction: com.greatwall.callring.contracts.callers.CallDirection = com.greatwall.callring.contracts.callers.CallDirection.INCOMING,
    val resetPaused: Boolean = false,
)

enum class ManualAnalysisState { PENDING_CONFIRMATION, CONFIRMED }

enum class ArchiveAnalysisIntent { AUTOMATIC, MANUAL_TEXT, MANUAL_LEGACY, LEGACY_UNCLASSIFIED }

enum class CallerSuggestionField { NAME, DEPARTMENT, POSITION, REGION }

data class CallerSuggestion(
    val field: CallerSuggestionField, val currentValue: String, val suggestedValue: String, val evidence: String,
)

data class CallerSuggestions(
    val callId: Long, val number: String, val resultVersion: Long, val token: String, val fields: List<CallerSuggestion>,
)

data class ConfirmCallerSuggestions(val callId: Long, val token: String, val fields: Set<CallerSuggestionField>)

sealed interface CallerSuggestionConfirmation {
    data object Applied : CallerSuggestionConfirmation
    data class Conflict(val latest: CallerSuggestions?) : CallerSuggestionConfirmation
}

data class RecordingFile(val uri: String, val name: String, val modifiedAt: Long, val size: Long? = null, val modifiedKnown: Boolean = true)

data class RecordingPlaybackSession(val callId: Long, val token: String)

interface RecordingApi {
    suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity>
    suspend fun queryArchives(query: ArchiveListQuery = ArchiveListQuery()): ApiResult<ArchiveListPage>
    suspend fun getArchive(callId: Long): ApiResult<CallArchive?>
    suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED): ApiResult<CallArchive>
    suspend fun transcribeOffline(callId: Long): ApiResult<Long>
    suspend fun cancelOfflineTranscription(callId: Long, runId: Long): ApiResult<Unit>
    suspend fun analyzeTranscript(callId: Long): ApiResult<Unit>
    suspend fun getCallerSuggestions(callId: Long): ApiResult<CallerSuggestions?>
    suspend fun confirmCallerSuggestions(command: ConfirmCallerSuggestions): ApiResult<CallerSuggestionConfirmation>
    suspend fun retry(callId: Long): ApiResult<Unit>
    suspend fun delete(callId: Long): ApiResult<Unit>
    suspend fun getPlaybackSession(): ApiResult<RecordingPlaybackSession?>
    /** A fresh opaque token lets the page stop only this playback when it leaves. */
    suspend fun playOwned(callId: Long, token: String): ApiResult<Unit>
    suspend fun stopPlaybackSession(token: String): ApiResult<Unit>
    suspend fun play(callId: Long): ApiResult<Unit>
    suspend fun stop(): ApiResult<Unit>
    suspend fun setSystemFolder(document: DocumentRef): ApiResult<Unit>
    suspend fun systemRecordings(): ApiResult<List<RecordingFile>>
    suspend fun inspectSystemRecording(document:DocumentRef):ApiResult<RecordingFile>
}

data class RecordingIdentity(val callId: Long, val number: String, val startedAtEpochMillis: Long, val direction: com.greatwall.callring.contracts.callers.CallDirection = com.greatwall.callring.contracts.callers.CallDirection.INCOMING)

/** Small list projection. Full transcripts, paths and analysis bodies are detail-only. */
data class ArchiveListItem(
    val callId: Long, val generation: Long, val number: String, val startedAtEpochMillis: Long,
    val status: ArchiveStatus, val source: RecordingSource, val durationMillis: Long?,
    val hasAudio: Boolean, val summary: String,
    val direction: com.greatwall.callring.contracts.callers.CallDirection = com.greatwall.callring.contracts.callers.CallDirection.INCOMING,
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
