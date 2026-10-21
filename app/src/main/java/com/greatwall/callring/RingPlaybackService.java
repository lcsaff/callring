package com.greatwall.callring;

import com.greatwall.callring.platform.audio.PreparationGate;
import com.greatwall.callring.platform.calling.CallStateSnapshot;
import com.greatwall.callring.platform.calling.SessionGate;

import android.app.Notification;


import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.greatwall.callring.bootstrap.BackendProvider;
import com.greatwall.callring.bootstrap.BackendGraph;
import com.greatwall.callring.platform.diagnostics.DiagnosticEvent;
import com.greatwall.callring.platform.calling.AndroidCallEnvironment;
import com.greatwall.callring.contracts.runtime.PlaybackMode;
import com.greatwall.callring.contracts.runtime.PlaybackState;

public class RingPlaybackService extends Service {
    public static final String PREPARE = "prepare", CANCEL = "cancel", PREVIEW = "preview", STOP = "stop";
    private static RingPlaybackService current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaWorker = Executors.newSingleThreadExecutor();
    private volatile MediaPlayer player;
    private AudioFocusRequest focus;
    private ResultReceiver ready;
    private SessionGate gate;
    private PreparationGate preparation;
    private String token;
    private boolean preview, prepared, playing;
    private volatile boolean closed;
    private PowerManager.WakeLock preparationLease;
    private final Runnable prepareTimeout = () -> finish();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || gate == null) return;
            try {
                if (!AndroidCallEnvironment.audible(RingPlaybackService.this)
                        || (!preview && !graph().getAudio().enabledForCalls())) {
                    finish(); return;
                }
                if (!AndroidCallEnvironment.phonePermission(RingPlaybackService.this)) {
                    finish(); return;
                }
                int callState = getSystemService(TelephonyManager.class).getCallState();
                boolean ringing = callState == TelephonyManager.CALL_STATE_RINGING;
                boolean active = CallStateSnapshot.active(callState, getSystemService(AudioManager.class).getMode());
                int state = gate.update(ringing, active, SystemClock.elapsedRealtime());
                if (state == SessionGate.STOP) { finish(); return; }
                if (state == SessionGate.PLAY && !playing) {
                    player.start(); playing = true;
                    releasePreparationLease();
                    graph().getPlaybackState().mark(preview ? PlaybackMode.PREVIEW : PlaybackMode.RINGTONE, PlaybackState.PLAYING);
                    diagnostic(DiagnosticEvent.AUDIO_PLAYING);
                }
                handler.postDelayed(this, 150);
            } catch (RuntimeException e) { finish(); }
        }
    };

    public static void command(Context c, String action, String token) {
        try { c.startService(new Intent(c, RingPlaybackService.class).setAction(action).putExtra("token", token)); }
        catch (RuntimeException e) { /* Best-effort cancellation. */ }
    }
    public static void preview(Context c) {
        c.startForegroundService(new Intent(c, RingPlaybackService.class).setAction(PREVIEW)
                .putExtra("token", UUID.randomUUID().toString()));
    }
    public static void stop(Context c) { command(c, STOP, null); }

    public static boolean commitPrepared(String token) {
        RingPlaybackService service = current;
        if (service == null || service.closed || !service.prepared || service.preview || service.gate != null) return false;
        try {
            if (!service.graph().getAudio().enabledForCalls()
                    || !AndroidCallEnvironment.phonePermission(service) || !AndroidCallEnvironment.audible(service)) return false;
            if (CallStateSnapshot.active(service.getSystemService(TelephonyManager.class).getCallState(),
                    service.getSystemService(AudioManager.class).getMode())) return false;
        } catch (RuntimeException e) { return false; }
        if (!service.preparation.commit(token, SystemClock.elapsedRealtime())) return false;
        service.commit();
        return true;
    }
    @Override public void onCreate() { super.onCreate(); current = this; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        String requestToken = intent.getStringExtra("token");
        if (STOP.equals(action)) { finish(); return START_NOT_STICKY; }
        if (CANCEL.equals(action)) {
            if (token == null) { stopSelf(); return START_NOT_STICKY; }
            if (!token.equals(requestToken)) return START_NOT_STICKY;
            finish();
            return START_NOT_STICKY;
        }
        if (!PREPARE.equals(action) && !PREVIEW.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        ResultReceiver requestReady = intent.getParcelableExtra("ready");
        if (token != null && !closed) {
            if (requestReady != null) requestReady.send(0, null);
            diagnostic(DiagnosticEvent.AUDIO_FAILED);
            return START_NOT_STICKY;
        }
        token = requestToken; ready = requestReady; closed = false;
        preparation = new PreparationGate(token, SystemClock.elapsedRealtime());
        preview = PREVIEW.equals(action);
        try {
            startForeground(31, notification());
            graph().getPlaybackState().mark(preview ? PlaybackMode.PREVIEW : PlaybackMode.RINGTONE, PlaybackState.PREPARING);
            diagnostic(DiagnosticEvent.AUDIO_PREPARING);
            preparationLease=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RuleRing:audio-prepare");
            preparationLease.acquire(10000);
            if (!AndroidCallEnvironment.phonePermission(this) || !AndroidCallEnvironment.audible(this)) {
                finish(); return START_NOT_STICKY;
            }
            prepare();
        } catch (RuntimeException | IOException e) { finish(); }
        return START_NOT_STICKY;
    }

    private BackendGraph graph() { return BackendProvider.from(this).getBackendGraph(); }
    private void diagnostic(DiagnosticEvent event) {
        try { graph().getDiagnostics().enqueue(event); } catch (RuntimeException ignored) { }
    }
    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RingPlaybackService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return graph().getNotifications().playback(preview, stop);
    }

    private void prepare() throws IOException {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setLooping(true);
        player.setOnErrorListener((mp, what, extra) -> { if (mp == player) finish(); return true; });
        player.setOnPreparedListener(mp -> {
            if (closed || mp != player) return;
            // Telecom owns and locks focus for actual ringing. A second request receives
            // transient loss as Telecom starts ringing. Preview has no Telecom owner.
            if (preview) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                            if (change < 0) finish();
                        }, handler).build();
                if (getSystemService(AudioManager.class).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    finish(); return;
                }
            }
            prepared = true;
            preparation.ready();
            if (preview) commit(); else reportReady(1);
        });
        handler.postDelayed(prepareTimeout, 2400);
        final MediaPlayer preparing = player;
        mediaWorker.execute(() -> {
            try {
                graph().getAudio().configureDataSource(preparing);
                if (!closed && player == preparing) preparing.prepareAsync();
            } catch (Exception failure) {
                handler.post(() -> { if (!closed && player == preparing) finish(); });
            }
        });
    }

    private void commit() {
        handler.removeCallbacks(prepareTimeout);
        gate = new SessionGate(token, preview, SystemClock.elapsedRealtime());
        handler.post(poll);
    }
    private void reportReady(int code) {
        if (ready != null) { ready.send(code, null); ready = null; }
    }
    private void finish() {
        if (closed) return;
        closed = true;
        if (preparation != null) preparation.cancel();
        reportReady(0);
        release();
        diagnostic(DiagnosticEvent.AUDIO_STOPPED);
        if(!preview)SyncJobService.schedule(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    private void release() {
        releasePreparationLease();
        handler.removeCallbacksAndMessages(null);
        MediaPlayer releasing = player;
        player = null;
        if (releasing != null) mediaWorker.execute(releasing::release);
        if (focus != null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); focus = null; }
        gate = null; prepared = false; playing = false;
        graph().getPlaybackState().clear();
    }
    private void releasePreparationLease(){if(preparationLease!=null){if(preparationLease.isHeld())preparationLease.release();preparationLease=null;}}
    @Override public void onDestroy() {
        closed = true;
        if (preparation != null) preparation.cancel();
        if (current == this) current = null;
        reportReady(0); release(); mediaWorker.shutdown(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
