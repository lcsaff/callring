package com.greatwall.callring.entrypoint.calling;

import com.greatwall.callring.platform.audio.PreparationGate;
import com.greatwall.callring.platform.calling.SessionGate;
import com.greatwall.callring.platform.diagnostics.DiagnosticEvent;
import com.greatwall.callring.contracts.runtime.PlaybackMode;
import com.greatwall.callring.contracts.runtime.PlaybackState;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Owns one playback attempt. Android resources are supplied by the registered service. */
public final class PlaybackServiceController {
    public enum Command { PREPARE, CANCEL, PREVIEW, STOP, STOP_PREVIEW, UNKNOWN }
    public interface Scheduler {
        void post(Runnable task);
        void later(Runnable task, long millis);
        void remove(Runnable task);
        void clear();
    }
    public interface Media {
        void initialize(Runnable prepared, Runnable error);
        void configureSource() throws IOException;
        void prepareAsync();
        void start();
        void release();
    }
    public interface Lease { void acquire(); void release(); }
    public static final class Observation {
        public final boolean ringing, active;
        public Observation(boolean ringing, boolean active) { this.ringing=ringing;this.active=active; }
    }
    private final LongSupplier now;
    private final Scheduler scheduler;
    private final Executor worker;
    private final Supplier<Media> newMedia;
    private final BooleanSupplier enabled, permitted, audible;
    private final Supplier<Observation> observe;
    private final BiConsumer<PlaybackMode, PlaybackState> mark;
    private final Runnable clear, abandonFocus, sync, stopForeground, stopSelf, shutdown;
    private final Consumer<DiagnosticEvent> diagnostic;
    private final Consumer<Boolean> foreground;
    private final Supplier<Lease> acquireLease;
    private final Function<Runnable, Boolean> acquireFocus;
    private volatile Media player;
    private Consumer<Integer> ready;
    private SessionGate gate;
    private PreparationGate preparation;
    private String token;
    private boolean preview, prepared, playing;
    private volatile boolean closed;
    private Lease preparationLease;
    private final Runnable prepareTimeout = this::finish;
    private final Runnable poll = this::poll;

    public PlaybackServiceController(LongSupplier now, Scheduler scheduler, Executor worker,
            Supplier<Media> newMedia, BooleanSupplier enabled, BooleanSupplier permitted,
            BooleanSupplier audible, Supplier<Observation> observe,
            BiConsumer<PlaybackMode, PlaybackState> mark, Runnable clear,
            Consumer<DiagnosticEvent> diagnostic, Consumer<Boolean> foreground,
            Supplier<Lease> acquireLease, Function<Runnable, Boolean> acquireFocus,
            Runnable abandonFocus, Runnable sync, Runnable stopForeground, Runnable stopSelf,
            Runnable shutdown) {
        this.now=now;this.scheduler=scheduler;this.worker=worker;this.newMedia=newMedia;
        this.enabled=enabled;this.permitted=permitted;this.audible=audible;this.observe=observe;
        this.mark=mark;this.clear=clear;this.diagnostic=diagnostic;this.foreground=foreground;
        this.acquireLease=acquireLease;this.acquireFocus=acquireFocus;this.abandonFocus=abandonFocus;
        this.sync=sync;this.stopForeground=stopForeground;this.stopSelf=stopSelf;this.shutdown=shutdown;
    }

    public void command(Command action, String requestToken, Consumer<Integer> requestReady) {
        if (Command.STOP_PREVIEW == action) {
            if (token == null) stopSelf.run();
            else if (preview) finish();
            return;
        }
        if (Command.STOP == action) { finish(); return; }
        if (Command.CANCEL == action) {
            if (token == null) { stopSelf.run(); return; }
            if (!token.equals(requestToken)) return;
            finish(); return;
        }
        if (Command.PREPARE != action && Command.PREVIEW != action) { stopSelf.run(); return; }
        if (token != null && !closed) {
            if (requestReady != null) requestReady.accept(0);
            diagnose(DiagnosticEvent.AUDIO_FAILED); return;
        }
        token=requestToken;ready=requestReady;closed=false;
        preparation=new PreparationGate(token, now.getAsLong());
        preview=Command.PREVIEW == action;
        try {
            foreground.accept(preview);
            mark.accept(mode(), PlaybackState.PREPARING);
            diagnose(DiagnosticEvent.AUDIO_PREPARING);
            preparationLease=acquireLease.get();
            preparationLease.acquire();
            if (!permitted.getAsBoolean() || !audible.getAsBoolean()) { finish(); return; }
            prepare();
        } catch (RuntimeException | IOException failure) { finish(); }
    }

    public boolean commitPrepared(String requestToken) {
        if (closed || !prepared || preview || gate != null) return false;
        try {
            if (!enabled.getAsBoolean() || !permitted.getAsBoolean() || !audible.getAsBoolean()) return false;
            if (observe.get().active) return false;
        } catch (RuntimeException failure) { return false; }
        if (!preparation.commit(requestToken, now.getAsLong())) return false;
        commit();return true;
    }

    private void prepare() throws IOException {
        player=newMedia.get();
        final Media preparing=player;
        preparing.initialize(() -> {
            if (closed || preparing != player) return;
            if (preview && !acquireFocus.apply(this::finish)) { finish();return; }
            prepared=true;preparation.ready();
            if (preview) commit();else reportReady(1);
        }, () -> { if (preparing == player) finish(); });
        scheduler.later(prepareTimeout, 2400);
        worker.execute(() -> {
            try {
                preparing.configureSource();
                if (!closed && player == preparing) preparing.prepareAsync();
            } catch (Exception failure) {
                scheduler.post(() -> { if (!closed && player == preparing) finish(); });
            }
        });
    }

    private void commit() {
        scheduler.remove(prepareTimeout);
        gate=new SessionGate(token, preview, now.getAsLong());
        scheduler.post(poll);
    }
    private void poll() {
        if (closed || gate == null) return;
        try {
            if (!audible.getAsBoolean() || (!preview && !enabled.getAsBoolean())) { finish();return; }
            if (!permitted.getAsBoolean()) { finish();return; }
            Observation observation=observe.get();
            int state=gate.update(observation.ringing, observation.active, now.getAsLong());
            if (state == SessionGate.STOP) { finish();return; }
            if (state == SessionGate.PLAY && !playing) {
                player.start();playing=true;releasePreparationLease();
                mark.accept(mode(), PlaybackState.PLAYING);diagnose(DiagnosticEvent.AUDIO_PLAYING);
            }
            scheduler.later(poll,150);
        } catch (RuntimeException failure) { finish(); }
    }
    private PlaybackMode mode() { return preview ? PlaybackMode.PREVIEW : PlaybackMode.RINGTONE; }
    private void diagnose(DiagnosticEvent event) { try { diagnostic.accept(event); } catch (RuntimeException ignored) {} }
    private void reportReady(int code) { if (ready != null) { ready.accept(code);ready=null; } }
    private void finish() {
        if (closed) return;
        closed=true;if (preparation != null) preparation.cancel();
        reportReady(0);release();diagnose(DiagnosticEvent.AUDIO_STOPPED);
        if (!preview) sync.run();
        stopForeground.run();stopSelf.run();
    }
    private void release() {
        releasePreparationLease();scheduler.clear();
        Media releasing=player;player=null;
        if (releasing != null) worker.execute(releasing::release);
        abandonFocus.run();gate=null;prepared=false;playing=false;clear.run();
    }
    private void releasePreparationLease() {
        if (preparationLease != null) { preparationLease.release();preparationLease=null; }
    }
    public void destroy() {
        closed=true;if (preparation != null) preparation.cancel();
        reportReady(0);release();shutdown.run();
    }
}
