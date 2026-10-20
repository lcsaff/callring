package com.greatwall.callring.application.ports

import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.domain.CalendarImport
import java.io.InputStream
import java.io.OutputStream

/** Downloads a validated annual calendar; never invoked from the call decision path. */
fun interface CalendarSource {
    fun fetch(year: Int): CalendarImport
}

interface PreferenceStore : com.greatwall.callring.application.ports.store.AppearanceStore,
    com.greatwall.callring.application.widget.WidgetBindingStore

fun interface CalendarDocumentReader { fun readCalendar(source: DocumentRef): CalendarImport }

interface DocumentStore : CalendarDocumentReader {
    fun <T> read(source: DocumentRef, block: (InputStream) -> T): T

    fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T

    /** Parse UTF-8 JSON with a hard 1 MiB limit; reject syntax errors. Core validates semantics. */
    override fun readCalendar(source: DocumentRef): CalendarImport
}

interface RuntimeProbe {
    fun getInstalledWidgets(): WidgetInstallState
    fun getStatus(): RuntimeStatus

    fun getDiagnostics(): List<DiagnosticEntry>
}

/**
 * Import/restore concurrency must use invocation generations to invalidate stale imports. Import
 * must validate <=20 MiB, playable duration, then atomically replace; failures preserve the
 * previous selection. No private paths in settings.
 */
interface PreparedRingtoneImport : AutoCloseable {
    /** Final generation/cancellation validation and atomic file/metadata replacement. */
    fun commit(checkCancellation: () -> Unit): RingtoneSettings
    override fun close()
}

interface AudioController {
    fun getSettings(): RingtoneSettings
    fun setEnabled(enabled: Boolean): RingtoneSettings
    fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport
    fun restoreBuiltIn(): RingtoneSettings
    fun startPreview()
    fun stopPlayback()
}

interface SyncScheduler {
    fun requestSync(atEpochMillis: Long): SyncRequestReceipt
}

/** Adapters may throw this to preserve a structured platform failure. */
class BackendFailure(val error: ApiError) : RuntimeException(error.message)
