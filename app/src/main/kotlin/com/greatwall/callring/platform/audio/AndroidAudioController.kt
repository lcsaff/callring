package com.greatwall.callring.platform.audio

import com.greatwall.callring.application.ports.platform.AudioController
import com.greatwall.callring.application.ports.platform.DocumentStore
import com.greatwall.callring.application.ports.platform.PreparedRingtoneImport

import com.greatwall.callring.data.preferences.updatePreferences
import com.greatwall.callring.platform.document.DocumentLimitExceeded
import com.greatwall.callring.platform.document.copyBounded

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import com.greatwall.callring.R
import com.greatwall.callring.RingPlaybackService
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.application.ports.*
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Every graph owns its generation; fixtures cannot cancel another application's import. */
class AndroidAudioController(
    private val context: Context,
    private val preferences: SharedPreferences,
    directoryProvider: () -> File,
    private val documents: DocumentStore,
) : AudioController, Closeable {
    private val generation = AudioGeneration()
    private val fileAccess = com.greatwall.callring.platform.ownership.OwnedFileAccess()
    private val playbackLeases = java.util.concurrent.atomic.AtomicInteger()
    private val files = AudioFiles(directoryProvider)
    @Volatile private var closed = false
    @Volatile private var settingsSnapshot: RingtoneSettings? = null

    override fun getSettings(): RingtoneSettings =
        synchronized(preferences) { readAndPublishSettings() }

    /** Telecom's deadline path never waits for preference loading, audio backups or fsync. */
    fun enabledForCalls(): Boolean = !closed && (settingsSnapshot?.enabled ?: false)

    private fun readAndPublishSettings(): RingtoneSettings {
        val custom = preferences.getBoolean("custom", false)
        val builtIn = selectedBuiltIn()
        return RingtoneSettings(
                preferences.getBoolean("enabled", false),
                if (custom) AudioSource.IMPORTED else AudioSource.BUILT_IN,
                if (custom) preferences.getString("audio_display_name", null) ?: "audio.imported"
                else "audio.built_in.${builtIn.name.lowercase(java.util.Locale.ROOT)}",
                null,
                builtIn,
            )
            .also { settingsSnapshot = it }
    }

    override fun setEnabled(enabled: Boolean): RingtoneSettings {
        checkOpen()
        return synchronized(preferences) {
            updatePreferences(preferences, mapOf("enabled" to enabled))
            readAndPublishSettings()
        }
    }

    override fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport {
        val lease = fileAccess.acquire()
        return try { prepareAudioLeased(source, { fileAccess.checkOpen(); checkCancellation() }, lease) }
        catch (failure: Throwable) { lease.close(); throw failure }
    }
    private fun prepareAudioLeased(source: DocumentRef, checkCancellation: () -> Unit, lease: Closeable): PreparedRingtoneImport {
        checkOpen()
        checkCancellation()
        val ticket = generation.begin()
        val directory = files.directory
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("audio.directory_unavailable")
        checkCancellation()
        val scratch = File.createTempFile("audio-import-", ".tmp", directory)
        try {
            try {
                documents.read(source) { input ->
                    FileOutputStream(scratch).use { output ->
                        copyBounded(input, output, 20 * 1024 * 1024L, checkCancellation)
                        output.fd.sync()
                    }
                }
            } catch (_: DocumentLimitExceeded) {
                throw BackendFailure(ApiError(ErrorCode.VALIDATION, "audio.too_large"))
            }
            checkCancellation()
            val player = MediaPlayer()
            try {
                player.setDataSource(scratch.absolutePath)
                player.prepare()
                if (player.duration <= 0) throw IOException("audio.invalid_duration")
            } catch (_: Exception) {
                checkCancellation()
                throw BackendFailure(ApiError(ErrorCode.VALIDATION, "audio.unplayable"))
            } finally {
                player.release()
            }
            checkCancellation()
            val name = displayName(source)
            val backup = prepareAudioBackup(files.selected)
            return object : PreparedRingtoneImport {
                override fun commit(checkCancellation: () -> Unit): RingtoneSettings {
                    lateinit var settings: RingtoneSettings
                    val published = generation.publish(ticket, checkCancellation) {
                        checkOpen()
                        stopPlayback()
                        synchronized(preferences) {
                            checkCancellation()
                            replaceAudioAtomically(scratch, files.selected, backup, checkCancellation) {
                                updatePreferences(preferences, mapOf("custom" to true, "audio_display_name" to name))
                            }
                            settings = readAndPublishSettings()
                        }
                    }
                    if (!published) throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.import_superseded"))
                    return settings
                }
                override fun close() { try { scratch.delete(); backup.close() } finally { lease.close() } }
            }
        } catch (failure: Throwable) {
            scratch.delete()
            throw failure
        }
    }

    override fun restoreBuiltIn(ringtone: BuiltInRingtone): RingtoneSettings {
        checkOpen()
        lateinit var settings: RingtoneSettings
        generation.invalidate {
            synchronized(preferences) {
                if (preferences.getBoolean("custom", false) || selectedBuiltIn() != ringtone) {
                    updatePreferences(preferences, mapOf("custom" to false, "audio_display_name" to null, "built_in_ringtone" to ringtone.name))
                }
                settings = readAndPublishSettings()
            }
        }
        return settings
    }

    override fun startPreview() {
        checkOpen()
        dispatchPlaybackCommand {
            context.startForegroundService(
                Intent(context, RingPlaybackService::class.java)
                    .setAction("preview")
                    .putExtra("token", UUID.randomUUID().toString())
            )
        }
    }

    override fun stopPreview() {
        checkOpen()
        dispatchPlaybackCommand {
            context.startService(Intent(context, RingPlaybackService::class.java).setAction(RingPlaybackService.STOP_PREVIEW))
        }
    }

    override fun stopPlayback() {
        checkOpen()
        dispatchPlaybackCommand {
            context.startService(Intent(context, RingPlaybackService::class.java).setAction("stop"))
        }
    }

    /**
     * Platform playback consumes the configured source without exposing private paths through the
     * UI API.
     */
    fun acquirePlaybackLease(): Closeable {
        val lease = fileAccess.acquire()
        playbackLeases.incrementAndGet()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        return Closeable { if (released.compareAndSet(false, true)) { playbackLeases.decrementAndGet(); lease.close() } }
    }
    fun configureDataSource(player: MediaPlayer) {
        checkOpen()
        synchronized(preferences) {
            if (preferences.getBoolean("custom", false))
                player.setDataSource(files.selected.absolutePath)
            else
                context.resources.openRawResourceFd(builtInResource(selectedBuiltIn())).use { descriptor ->
                    player.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                }
        }
    }

    private fun selectedBuiltIn(): BuiltInRingtone =
        BuiltInRingtone.entries.firstOrNull { it.name == preferences.getString("built_in_ringtone", null) }
            ?: BuiltInRingtone.MELODY

    private fun displayName(source: DocumentRef): String {
        // Metadata is optional; failure to read a provider label must not discard validated audio.
        return try {
            context.contentResolver
                .query(
                    Uri.parse(source.uri),
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use {
                    if (it.moveToFirst() && !it.isNull(0)) sanitizeAudioName(it.getString(0))
                    else null
                } ?: "audio.imported"
        } catch (_: Exception) {
            "audio.imported"
        }
    }

    private fun checkOpen() {
        if (closed) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
    }

    override fun close() {
        generation.invalidate { closed = true }
        fileAccess.seal()
        if (playbackLeases.get() > 0) RingPlaybackService.stopOwnedPlayback(this)
        fileAccess.close()
    }
}

internal fun sanitizeAudioName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { !it.isISOControl() }
        .take(160)
        .ifBlank { "audio.imported" }

internal fun builtInResource(ringtone: BuiltInRingtone): Int = when (ringtone) {
    BuiltInRingtone.MELODY -> R.raw.short_melody
    BuiltInRingtone.CHIME -> R.raw.ring_chime
    BuiltInRingtone.GENTLE -> R.raw.ring_gentle
    BuiltInRingtone.CLASSIC -> R.raw.ring_classic
    BuiltInRingtone.DIGITAL -> R.raw.ring_digital
}
