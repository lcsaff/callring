package com.greatwall.callring

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
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.entrypoint.recording.CaptureServiceController
import com.greatwall.callring.application.recording.RecordingService.CapturePlan
import com.greatwall.callring.application.ports.recording.AudioReference
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Experimental microphone capture is explicit and never represented as verified two-way audio. */
class CallCaptureService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val controller by lazy {
        CaptureServiceController(
            { foreground -> capturePlan(this, foreground) },
            { val service = BackendProvider.from(this).backendGraph.recordings.service
                object : CaptureServiceController.Results {
                    override fun attach(call: RecordingCall, audio: AudioReference) { service.attachCapture(call, audio) }
                    override fun failure(call: RecordingCall) { service.captureFailure(call, "recording.capture_restricted") }
                }
            },
            {
                val directory = BackendProvider.from(this).backendGraph.recordings.directory
                check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, UUID.randomUUID().toString() + ".m4a")
                object : CaptureServiceController.Target {
                    override val reference = AudioReference(file.absolutePath)
                    override fun delete() { file.delete() }
                }
            },
            { @Suppress("DEPRECATION") val recorder = MediaRecorder()
                object : CaptureServiceController.Media {
                    override fun configure(plan: CapturePlan, target: CaptureServiceController.Target) {
                    recorder.setAudioSource(if (plan.voiceCall) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(64000); recorder.setAudioSamplingRate(16000)
                    recorder.setOutputFile(target.reference.location)
                    recorder.setOnErrorListener { _, _, _ -> stopSelf() }
                    recorder.setMaxDuration(2 * 60 * 60 * 1000)
                    recorder.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopSelf() }
                    }
                    override fun prepare() = recorder.prepare()
                    override fun start() = recorder.start()
                    override fun stop() = recorder.stop()
                    override fun release() = recorder.release()
                }
            }, ::foreground, ::stopSelf, worker, worker::shutdown,
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.start(intent?.action == STOP)
        return START_NOT_STICKY
    }
    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "通话音频采集", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CallCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("正在尝试采集通话音频").setContentText("麦克风录音可能缺少对方声音，需回听确认")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()) }
        catch (failure: Exception) { throw CaptureServiceController.ForegroundFailure(failure) }
    }
    override fun onDestroy() { controller.destroy(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "call_capture"
        private const val NOTIFICATION = 73141
        private const val STOP = "com.greatwall.callring.STOP_CAPTURE"
        private fun capturePlan(context: Context, foreground: Boolean): CapturePlan? {
            val service = BackendProvider.from(context).backendGraph.recordings.service
            return service.capturePlan(
                context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, foreground)
        }
        fun phoneState(context: Context, active: Boolean) {
            CaptureServiceController.phoneState(active, { capturePlan(context, true) },
                { context.startForegroundService(Intent(context, CallCaptureService::class.java)) },
                { context.stopService(Intent(context, CallCaptureService::class.java)) },
                { BackendProvider.from(context).backendGraph.recordings.service.captureFailure(it, "recording.capture_restricted") })
        }
    }
}
