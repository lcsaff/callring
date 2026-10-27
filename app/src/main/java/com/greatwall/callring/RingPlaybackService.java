package com.greatwall.callring;

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
import com.greatwall.callring.platform.calling.AndroidCallEnvironment;
import com.greatwall.callring.platform.calling.CallStateSnapshot;
import com.greatwall.callring.entrypoint.calling.PlaybackServiceController;

/** Registered Android resource and command adapter for the playback controller. */
public class RingPlaybackService extends Service {
    public static final String PREPARE="prepare", CANCEL="cancel", PREVIEW="preview", STOP="stop", STOP_PREVIEW="stop_preview";
    private static RingPlaybackService current;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService mediaWorker=Executors.newSingleThreadExecutor();
    private AudioFocusRequest focus;
    private AudioAttributes attributes;
    private final PlaybackServiceController controller=new PlaybackServiceController(
        SystemClock::elapsedRealtime, new PlaybackServiceController.Scheduler() {
            public void post(Runnable task) { handler.post(task); }
            public void later(Runnable task,long millis) { handler.postDelayed(task,millis); }
            public void remove(Runnable task) { handler.removeCallbacks(task); }
            public void clear() { handler.removeCallbacksAndMessages(null); }
        }, mediaWorker, AndroidMedia::new,
        () -> graph().getAudio().enabledForCalls(),
        () -> AndroidCallEnvironment.phonePermission(this),
        () -> AndroidCallEnvironment.audible(this),
        () -> {
            int state=getSystemService(TelephonyManager.class).getCallState();
            return new PlaybackServiceController.Observation(state==TelephonyManager.CALL_STATE_RINGING,
                CallStateSnapshot.active(state,getSystemService(AudioManager.class).getMode()));
        },
        (mode,state) -> graph().getPlaybackState().mark(mode,state),
        () -> graph().getPlaybackState().clear(), event -> graph().getDiagnostics().enqueue(event),
        preview -> {
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RingPlaybackService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            startForeground(31,graph().getNotifications().playback(preview,stop));
        }, () -> {
            PowerManager.WakeLock lease=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RuleRing:audio-prepare");
            return new PlaybackServiceController.Lease() {
                public void acquire() { lease.acquire(10000); }
                public void release() { if (lease.isHeld()) lease.release(); }
            };
        }, loss -> {
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> { if (change<0) loss.run(); },handler).build();
            return getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }, () -> { if (focus!=null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null; } },
        () -> SyncJobService.schedule(this), () -> stopForeground(STOP_FOREGROUND_REMOVE), this::stopSelf, mediaWorker::shutdown);

    private final class AndroidMedia implements PlaybackServiceController.Media {
        private final MediaPlayer player;
        AndroidMedia() {
            attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            player=new MediaPlayer();
        }
        public void initialize(Runnable prepared,Runnable error) {
            player.setAudioAttributes(attributes);
            player.setWakeMode(RingPlaybackService.this,PowerManager.PARTIAL_WAKE_LOCK);
            player.setLooping(true);
            player.setOnErrorListener((mp,what,extra) -> { error.run();return true; });
            player.setOnPreparedListener(mp -> prepared.run());
        }
        public void configureSource() throws IOException { graph().getAudio().configureDataSource(player); }
        public void prepareAsync() { player.prepareAsync(); }
        public void start() { player.start(); }
        public void release() { player.release(); }
    }
    private BackendGraph graph() { return BackendProvider.from(this).getBackendGraph(); }
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
        RingPlaybackService service=current;
        return service!=null && service.controller.commitPrepared(token);
    }
    @Override public void onCreate() { super.onCreate();current=this; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent==null) { stopSelf();return START_NOT_STICKY; }
        ResultReceiver ready=(PREPARE.equals(intent.getAction()) || PREVIEW.equals(intent.getAction())) ? intent.getParcelableExtra("ready") : null;
        controller.command(decode(intent.getAction()),intent.getStringExtra("token"),ready==null?null:code -> ready.send(code,null));
        return START_NOT_STICKY;
    }
    private static PlaybackServiceController.Command decode(String action) {
        if (PREPARE.equals(action)) return PlaybackServiceController.Command.PREPARE;
        if (PREVIEW.equals(action)) return PlaybackServiceController.Command.PREVIEW;
        if (CANCEL.equals(action)) return PlaybackServiceController.Command.CANCEL;
        if (STOP_PREVIEW.equals(action)) return PlaybackServiceController.Command.STOP_PREVIEW;
        if (STOP.equals(action)) return PlaybackServiceController.Command.STOP;
        return PlaybackServiceController.Command.UNKNOWN;
    }
    @Override public void onDestroy() {
        if (current==this) current=null;
        controller.destroy();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
