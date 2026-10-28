package com.greatwall.callring.application.backup

import com.greatwall.callring.application.ports.backup.BackupArchivePort
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*

/** One formal, cancellable entry point shared by UI and local application consumers. */
class BackupService(
    private val port: BackupArchivePort,
    private val dispatcher: CoroutineDispatcher,
    private val operationActive: () -> Boolean,
) : BackupApi {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val state = kotlinx.coroutines.flow.MutableStateFlow(BackupProgress(BackupPhase.IDLE))
    override val progress: kotlinx.coroutines.flow.StateFlow<BackupProgress> get() = state
    override suspend fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean) = secret(password, BackupPhase.EXPORTING, BackupPhase.COMPLETE) { check -> port.export(destination, password, includeAudio, check) }
    override suspend fun preview(source: DocumentRef, password: CharArray) = secret(password, BackupPhase.VALIDATING, BackupPhase.READY) { check -> port.preview(source, password, check) }
    override suspend fun restore(previewToken: String) = result(BackupPhase.RESTORING, BackupPhase.COMPLETE, ownerReplacement = true) { check -> port.restore(previewToken, check) }
    override suspend fun discard(previewToken: String): ApiResult<Unit> = withContext(dispatcher) {
        try { currentCoroutineContext().ensureActive(); port.discard(previewToken); ApiResult.Success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "backup.failed", retryable = true)) }
    }

    private suspend fun <T> secret(password: CharArray, phase: BackupPhase, finished: BackupPhase, block: ((() -> Unit)) -> T): ApiResult<T> =
        try { result(phase, finished, block = block) } finally { password.fill(0.toChar()) }

    private suspend fun <T> result(phase: BackupPhase, finished: BackupPhase, ownerReplacement: Boolean = false, block: ((() -> Unit)) -> T): ApiResult<T> = withContext(dispatcher) {
        if (!running.compareAndSet(false, true)) return@withContext ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "backup.busy", retryable = true))
        val caller = currentCoroutineContext()
        val check = { caller.ensureActive(); if (!operationActive()) throw CancellationException("backend.closed") }
        try {
            check()
            state.value = BackupProgress(phase)
            val value = block(check)
            if (!ownerReplacement) {
                if (!operationActive() && value is BackupPreview) port.discard(value.token)
                check()
            }
            if (operationActive()) state.value = BackupProgress(finished)
            ApiResult.Success(value)
        } catch (cancelled: CancellationException) { if (operationActive()) state.value = BackupProgress(BackupPhase.IDLE); throw cancelled }
        catch (failure: BackendFailure) { if (operationActive()) state.value = BackupProgress(BackupPhase.FAILED); ApiResult.Failure(failure.error) }
        catch (_: Exception) { if (operationActive()) state.value = BackupProgress(BackupPhase.FAILED); ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "backup.failed", retryable = true)) }
        finally { running.set(false) }
    }
}
