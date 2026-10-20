package com.greatwall.callring

import com.greatwall.callring.contracts.recording.*
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.IBinder
import com.greatwall.callring.contracts.recording.CaptureMode
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.application.RecordingCall
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Experimental microphone capture is explicit and never represented as verified two-way audio. */
class CallCaptureService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var call: RecordingCall? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (recorder != null || call != null) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "通话音频采集", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CallCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("正在尝试采集通话音频").setContentText("麦克风录音可能缺少对方声音，需回听确认")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        worker.execute {
            try { synchronized(this) {
                if (destroyed || recorder != null) return@execute
                val runtime = BackendProvider.from(this).backendGraph.recordings
                val config = runtime.store.settings()
                val candidate = runtime.captureCandidate()
                if (!config.recordingEnabled || candidate == null) { stopSelf(); return@execute }
                call = candidate
                val privileged = checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
                if (!privileged && (config.captureMode != CaptureMode.MICROPHONE_EXPERIMENTAL ||
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
                    runtime.captureFailure(candidate, "recording.permission_required"); stopSelf(); return@execute
                }
                try {
                    check(runtime.directory.isDirectory || runtime.directory.mkdirs())
                    val target = File(runtime.directory, UUID.randomUUID().toString() + ".m4a")
                    file = target
                    @Suppress("DEPRECATION") val next = MediaRecorder()
                    recorder = next
                    next.setAudioSource(if (privileged) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC)
                    next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    next.setAudioEncodingBitRate(64000); next.setAudioSamplingRate(16000)
                    next.setOutputFile(target.absolutePath)
                    next.setOnErrorListener { _, _, _ -> stopSelf() }
                    next.setMaxDuration(2 * 60 * 60 * 1000)
                    next.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopSelf() }
                    next.prepare(); next.start()
                } catch (_: Exception) {
                    recorder?.release(); recorder = null; file?.delete(); file = null
                    runtime.captureFailure(candidate, "recording.capture_restricted"); stopSelf()
                }
            } } catch (_: Exception) { stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        synchronized(this) { destroyed = true }
        worker.execute {
            synchronized(this) {
                val current = recorder; recorder = null
                var valid = false
                try { current?.stop(); valid = current != null } catch (_: Exception) {}
                finally { current?.release() }
                val audio = file; val candidate = call
                if (audio != null && candidate != null) {
                    val runtime = BackendProvider.from(this).backendGraph.recordings
                    try {
                        if (valid) runtime.attachCapture(candidate, audio)
                        else { audio.delete(); runtime.captureFailure(candidate, "recording.capture_restricted") }
                    } catch (_: Exception) { audio.delete(); runtime.captureFailure(candidate, "recording.capture_restricted") }
                }
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "call_capture"
        private const val NOTIFICATION = 73141
        private const val STOP = "com.greatwall.callring.STOP_CAPTURE"
        fun phoneState(context: Context, active: Boolean) {
            if (!active) { context.stopService(Intent(context, CallCaptureService::class.java)); return }
            val runtime = BackendProvider.from(context).backendGraph.recordings
            val settings = runtime.store.settings()
            if (!settings.recordingEnabled) return
            val call = runtime.captureCandidate() ?: return
            runtime.noteMissing(call)
            val privileged = context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
            if (privileged || settings.captureMode == CaptureMode.MICROPHONE_EXPERIMENTAL) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runtime.captureFailure(call, "recording.permission_required"); return
                }
                try { context.startForegroundService(Intent(context, CallCaptureService::class.java)) }
                catch (_: Exception) { runtime.captureFailure(call, "recording.capture_restricted") }
            }
        }
    }
}
