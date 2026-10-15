package com.greatwall.callring.platform

import android.content.SharedPreferences
import android.util.Log
import com.greatwall.callring.application.api.DiagnosticEntry
import java.io.Closeable
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Only a closed set of codes can be recorded; caller numbers, document URIs and exceptions are
 * excluded.
 */
enum class DiagnosticEvent(val code: String) {
    AUDIO_PREPARING("audio.preparing"),
    AUDIO_PLAYING("audio.playing"),
    AUDIO_STOPPED("audio.stopped"),
    AUDIO_FAILED("audio.failed"),
    CALL_SYNC_QUEUED("call_sync.queued"),
    CALL_SYNC_FINISHED("call_sync.finished"),
    CALL_SYNC_FAILED("call_sync.failed"),
    CALL_SCREENED("call.screened"),
    CALL_RECORD_FAILED("call.record_failed"),
}

class AndroidDiagnosticsStore(
    private val preferences: SharedPreferences,
    private val clock: Clock,
) : Closeable {
    private val lifetime = Any()
    private var closed = false
    private val writer =
        Executors.newSingleThreadExecutor { work -> Thread(work, "callring-diagnostics") }

    /**
     * Deadline-sensitive services enqueue fixed events without touching preferences or their
     * monitor.
     */
    fun enqueue(event: DiagnosticEvent): Boolean =
        synchronized(lifetime) {
            if (closed) return false
            val occurredAt = clock.millis()
            writer.execute {
                try {
                    addAt(event, occurredAt)
                } catch (_: Exception) {
                    Log.i("Callring", "diagnostic.write_failed")
                }
            }
            true
        }

    fun add(event: DiagnosticEvent) = addAt(event, clock.millis())

    @Synchronized
    private fun addAt(event: DiagnosticEvent, occurredAt: Long) {
        Log.i("Callring", event.code)
        val old =
            preferences
                .getString("diagnostics_v2", "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(17)
                .toList()
        preferences
            .edit()
            .putString(
                "diagnostics_v2",
                (listOf("$occurredAt\t${event.code}") + old).joinToString("\n"),
            )
            .commitOrThrow()
    }

    @Synchronized
    fun read(): List<DiagnosticEntry> {
        val known = DiagnosticEvent.values().map { it.code }.toSet()
        val recent =
            preferences
                .getString("diagnostics_v2", "")
                .orEmpty()
                .lineSequence()
                .mapNotNull { line ->
                    val pieces = line.split('\t', limit = 2)
                    val epoch = pieces.firstOrNull()?.toLongOrNull()
                    if (pieces.size != 2 || epoch == null || pieces[1] !in known) null
                    else DiagnosticEntry(epoch, null, pieces[1])
                }
                .toList()
        val legacy =
            preferences
                .getString("events", "")
                .orEmpty()
                .lineSequence()
                .mapNotNull(::legacyDiagnostic)
                .toList()
        return (recent + legacy).take(18)
    }

    override fun close() {
        synchronized(lifetime) {
            if (closed) return
            closed = true
            writer.shutdown()
        }
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            writer.shutdownNow()
            throw java.io.IOException("diagnostics.close_timeout")
        }
    }
}

internal fun legacyDiagnostic(line: String): DiagnosticEntry? {
    val match = Regex("^([0-2][0-9]:[0-5][0-9]:[0-5][0-9])  (.+)$").matchEntire(line) ?: return null
    if (match.groupValues[1].substring(0, 2).toInt() > 23) return null
    // Old storage contains time-of-day only. Do not invent a date or re-log unstructured text.
    return DiagnosticEntry(null, match.groupValues[1], "diagnostic.legacy_event")
}
