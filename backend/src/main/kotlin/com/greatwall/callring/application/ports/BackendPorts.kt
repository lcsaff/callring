package com.greatwall.callring.application.ports

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.AuditRecord
import com.greatwall.callring.domain.CalendarImport
import java.io.InputStream
import java.io.OutputStream

/**
 * Implementations serialize reads and writes, use one SQL snapshot, and roll back on exceptions.
 * State belongs to the callback only; it must never escape or be reused by the adapter.
 */
interface BackendStore : com.greatwall.callring.application.ports.store.FullSnapshotReader {
    override fun <T> captureFull(block: (BackendState, List<AuditRecord>) -> T): T =
        throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "snapshot.core_unavailable"))
    fun <T> read(block: (BackendState) -> T): T

    fun <T> transaction(block: (BackendState) -> T): T
}

/** Downloads a validated annual calendar; never invoked from the call decision path. */
fun interface CalendarSource {
    fun fetch(year: Int): CalendarImport
}

interface PreferenceStore {
    fun listBindings(): List<WidgetBinding> = throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "snapshot.bindings_unavailable"))
    fun getAppearance(): AppearanceSettings

    fun saveAppearance(settings: AppearanceSettings)

    fun getBinding(widgetId: Int): WidgetBinding?

    fun saveBinding(binding: WidgetBinding)

    fun removeBinding(widgetId: Int)
}

interface DocumentStore {
    fun <T> read(source: DocumentRef, block: (InputStream) -> T): T

    fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T

    /** Parse UTF-8 JSON with a hard 1 MiB limit; reject syntax errors. Core validates semantics. */
    fun readCalendar(source: DocumentRef): CalendarImport
}

interface RuntimeProbe {
    fun getInstalledWidgets(): WidgetInstallState = WidgetInstallState(emptyList(), AccessState.UNKNOWN)
    fun getStatus(): RuntimeStatus

    fun getDiagnostics(): List<DiagnosticEntry>
}

/**
 * Import/restore concurrency must use invocation generations to invalidate stale imports. Import
 * must validate <=20 MiB, playable duration, then atomically replace; failures preserve the
 * previous selection. No private paths in settings.
 */
interface AudioController {
    fun getSettings(): RingtoneSettings

    /**
     * The adapter persists first; disabling then stops playback on a best-effort basis. Playback
     * restrictions must not turn an already committed disable into a failure.
     */
    fun setEnabled(enabled: Boolean): RingtoneSettings
    fun setEnabled(enabled: Boolean, command: CommandContext?): RingtoneSettings {
        if(command != null) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE,"audio.guarded_commands_unavailable"))
        return setEnabled(enabled)
    }

    fun importAudio(source: DocumentRef): RingtoneSettings = importAudio(source) {}

    /**
     * Checks run during preparation and immediately before atomic publication. Once published, the
     * controller returns without checking cancellation so the caller can notify the commit.
     */
    fun importAudio(source: DocumentRef, checkCancellation: () -> Unit): RingtoneSettings
    fun importAudio(source: DocumentRef, checkCancellation: () -> Unit, command: CommandContext?): RingtoneSettings {
        if(command != null) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE,"audio.guarded_commands_unavailable"))
        return importAudio(source,checkCancellation)
    }

    fun restoreBuiltIn(): RingtoneSettings
    fun restoreBuiltIn(command: CommandContext?): RingtoneSettings {
        if(command != null) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE,"audio.guarded_commands_unavailable"))
        return restoreBuiltIn()
    }

    fun startPreview()

    fun stopPlayback()
}

interface SyncScheduler {
    fun requestSync(atEpochMillis: Long): SyncRequestReceipt
}

/** Adapters may throw this to preserve a structured platform failure. */
class BackendFailure(val error: ApiError) : RuntimeException(error.message)
