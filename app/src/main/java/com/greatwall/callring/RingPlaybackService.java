package com.greatwall.callring;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
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
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import java.io.IOException;
import java.util.UUID;

public class RingPlaybackService extends Service {
    static final String PREPARE = "prepare", CANCEL = "cancel", PREVIEW = "preview", STOP = "stop";
    private static RingPlaybackService current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private ResultReceiver ready;
    private SessionGate gate;
    private PreparationGate preparation;
    private String token;
    private boolean preview, prepared, playing, closed;
    private PowerManager.WakeLock preparationLease;
    private final Runnable prepareTimeout = () -> finish("准备取消或超时");
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || gate == null) return;
            try {
                if (!AppSettings.audible(RingPlaybackService.this)
                        || (!preview && !AppSettings.enabled(RingPlaybackService.this))) {
                    finish("已停止：开关关闭或手机已静音"); return;
                }
                if (!AppSettings.phonePermission(RingPlaybackService.this)) {
                    finish("已停止：电话状态权限不可用"); return;
                }
                int callState = getSystemService(TelephonyManager.class).getCallState();
                boolean ringing = callState == TelephonyManager.CALL_STATE_RINGING;
                boolean active = CallStateSnapshot.active(callState, getSystemService(AudioManager.class).getMode());
                int state = gate.update(ringing, active, SystemClock.elapsedRealtime());
                if (state == SessionGate.STOP) { finish("播放结束：接听、挂断或超时"); return; }
                if (state == SessionGate.PLAY && !playing) {
                    player.start(); playing = true;
                    releasePreparationLease();
                    EventLog.add(RingPlaybackService.this, preview ? "试听开始（8 秒）" : "自定义来电铃声开始");
                }
                handler.postDelayed(this, 150);
            } catch (RuntimeException e) { finish("播放停止：系统状态不可用"); }
        }
    };

    static void command(Context c, String action, String token) {
        try { c.startService(new Intent(c, RingPlaybackService.class).setAction(action).putExtra("token", token)); }
        catch (RuntimeException e) { EventLog.add(c, "播放控制未送达"); }
    }
    static void preview(Context c) {
        c.startForegroundService(new Intent(c, RingPlaybackService.class).setAction(PREVIEW)
                .putExtra("token", UUID.randomUUID().toString()));
    }
    static void stop(Context c) { command(c, STOP, null); }

    static boolean commitPrepared(String token) {
        RingPlaybackService service = current;
        if (service == null || service.closed || !service.prepared || service.preview || service.gate != null) return false;
        try {
            if (!AppSettings.phonePermission(service) || !AppSettings.audible(service)) return false;
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
        if (STOP.equals(action)) { finish("手动停止"); return START_NOT_STICKY; }
        if (CANCEL.equals(action)) {
            if (token == null) { stopSelf(); return START_NOT_STICKY; }
            if (!token.equals(requestToken)) return START_NOT_STICKY;
            finish("准备已取消");
            return START_NOT_STICKY;
        }
        if (!PREPARE.equals(action) && !PREVIEW.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        ResultReceiver requestReady = intent.getParcelableExtra("ready");
        if (token != null && !closed) {
            if (requestReady != null) requestReady.send(0, null);
            EventLog.add(this, "已有播放会话，新请求保留系统铃声");
            return START_NOT_STICKY;
        }
        token = requestToken; ready = requestReady; closed = false;
        preparation = new PreparationGate(token, SystemClock.elapsedRealtime());
        preview = PREVIEW.equals(action);
        try {
            startForeground(31, notification());
            preparationLease=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RuleRing:audio-prepare");
            preparationLease.acquire(10000);
            if (!AppSettings.phonePermission(this) || !AppSettings.audible(this)) {
                finish("无法播放：需要电话状态权限且手机处于响铃模式"); return START_NOT_STICKY;
            }
            prepare();
        } catch (RuntimeException | IOException e) { finish("音频准备失败"); }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("ring_playback", "铃声播放", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RingPlaybackService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "ring_playback").setSmallIcon(R.drawable.ic_callring)
                .setContentTitle(preview ? "正在试听铃声" : "规则来电铃声")
                .setContentText("接听或挂断后自动停止，也可点此停止")
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    }

    private void prepare() throws IOException {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setLooping(true);
        if (AppSettings.prefs(this).getBoolean("custom", false)) {
            player.setDataSource(AppSettings.customAudio(this).getAbsolutePath());
        } else {
            try (AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.short_melody)) {
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
        }
        player.setOnErrorListener((mp, what, extra) -> { finish("音频解码或播放失败"); return true; });
        player.setOnPreparedListener(mp -> {
            if (closed) return;
            // Telecom owns and locks focus for actual ringing. A second request receives
            // transient loss as Telecom starts ringing. Preview has no Telecom owner.
            if (preview) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                            if (change < 0) finish("音频焦点已交还，停止铃声");
                        }, handler).build();
                if (getSystemService(AudioManager.class).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    finish("音频焦点不可用，取消试听"); return;
                }
            }
            prepared = true;
            preparation.ready();
            EventLog.add(this, "音频已就绪");
            if (preview) commit(); else reportReady(1);
        });
        handler.postDelayed(prepareTimeout, 2400);
        player.prepareAsync();
    }

    private void commit() {
        handler.removeCallbacks(prepareTimeout);
        gate = new SessionGate(token, preview, SystemClock.elapsedRealtime());
        handler.post(poll);
    }
    private void reportReady(int code) {
        if (ready != null) { ready.send(code, null); ready = null; }
    }
    private void finish(String message) {
        if (closed) return;
        closed = true;
        if (preparation != null) preparation.cancel();
        reportReady(0);
        release();
        EventLog.add(this, message);
        if(!preview)SyncJobService.schedule(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    private void release() {
        releasePreparationLease();
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (focus != null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); focus = null; }
        gate = null; prepared = false; playing = false;
    }
    private void releasePreparationLease(){if(preparationLease!=null){if(preparationLease.isHeld())preparationLease.release();preparationLease=null;}}
    @Override public void onDestroy() {
        closed = true;
        if (preparation != null) preparation.cancel();
        if (current == this) current = null;
        reportReady(0); release(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
