package com.greatwall.callring.platform

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import com.greatwall.callring.R
import com.greatwall.callring.RingPlaybackService
import com.greatwall.callring.application.api.*
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
    private val coordinator: com.greatwall.callring.application.BusinessRevisionCoordinator = com.greatwall.callring.application.BusinessRevisionCoordinator(),
) : AudioController, Closeable {
    private val generation = AudioGeneration()
    private val files = AudioFiles(directoryProvider)
    @Volatile private var closed = false
    @Volatile private var settingsSnapshot: RingtoneSettings? = null

    override fun getSettings(): RingtoneSettings =
        synchronized(preferences) { readAndPublishSettings() }

    /** Telecom's deadline path never waits for preference loading, audio backups or fsync. */
    fun enabledForCalls(): Boolean = !closed && (settingsSnapshot?.enabled ?: false)

    private fun readAndPublishSettings(): RingtoneSettings {
        val custom = preferences.getBoolean("custom", false)
        return RingtoneSettings(
                preferences.getBoolean("enabled", false),
                if (custom) AudioSource.IMPORTED else AudioSource.BUILT_IN,
                if (custom) preferences.getString("audio_display_name", null) ?: "audio.imported"
                else "audio.built_in",
                8,
            )
            .also { settingsSnapshot = it }
    }

    override fun setEnabled(enabled: Boolean): RingtoneSettings {
        checkOpen()
        val settings =
            coordinator.commit("ringtone") { synchronized(preferences) {
                updatePreferences(preferences, mapOf("enabled" to enabled))
                readAndPublishSettings()
            } }
        if (!enabled)
            try {
                stopPlayback()
            } catch (_: RuntimeException) {
                // Playback polls enabled. A failed stop command must not misreport the committed
                // setting.
                android.util.Log.i("Callring", "audio.stop_delivery_failed")
            }
        return settings
    }

    override fun importAudio(source: DocumentRef): RingtoneSettings = importAudio(source) {}

    override fun importAudio(source: DocumentRef, checkCancellation: () -> Unit): RingtoneSettings {
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
            lateinit var settings: RingtoneSettings
            val published =
                coordinator.commit("ringtone") { generation.publish(ticket, checkCancellation) {
                    checkOpen()
                    stopPlayback()
                    synchronized(preferences) {
                        // Cancellation ends preparation until this commit boundary. File+metadata
                        // then
                        // finish or roll back together; no cancellation checks split the
                        // transaction.
                        checkCancellation()
                        replaceAudioAtomically(scratch, files.selected, checkCancellation) {
                            updatePreferences(
                                preferences,
                                mapOf("custom" to true, "audio_display_name" to name),
                            )
                        }
                        settings = readAndPublishSettings()
                    }
                } }
            if (!published)
                throw BackendFailure(
                    ApiError(ErrorCode.SYSTEM_RESTRICTED, "audio.import_superseded")
                )
            return settings
        } finally {
            scratch.delete()
        }
    }

    override fun restoreBuiltIn(): RingtoneSettings {
        checkOpen()
        lateinit var settings: RingtoneSettings
        coordinator.commit("ringtone") { generation.invalidate {
            stopPlayback()
            synchronized(preferences) {
                updatePreferences(
                    preferences,
                    mapOf("custom" to false, "audio_display_name" to null),
                )
                settings = readAndPublishSettings()
            }
        } }
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
    fun configureDataSource(player: MediaPlayer) {
        checkOpen()
        synchronized(preferences) {
            if (preferences.getBoolean("custom", false))
                player.setDataSource(files.selected.absolutePath)
            else
                context.resources.openRawResourceFd(R.raw.short_melody).use { descriptor ->
                    player.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                }
        }
    }

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
    }
}

internal fun sanitizeAudioName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { !it.isISOControl() }
        .take(160)
        .ifBlank { "audio.imported" }
