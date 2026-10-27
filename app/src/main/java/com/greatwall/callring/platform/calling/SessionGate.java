package com.greatwall.callring.platform.calling;

public final class SessionGate {
    public static final int WAIT = 0, PLAY = 1, STOP = 2;
    private final String token;
    private final boolean preview;
    private final long started;
    private boolean sawRinging;
    public SessionGate(String token, boolean preview, long now) {
        this.token = token; this.preview = preview; this.started = now;
    }
    public boolean owns(String token) { return this.token.equals(token); }
    public int update(boolean ringing, boolean active, long now) {
        long elapsed = now - started;
        if (active || (!preview && elapsed >= 90000)) return STOP;
        if (preview) return ringing ? STOP : PLAY;
        if (ringing) { sawRinging = true; return PLAY; }
        return sawRinging || elapsed >= 4000 ? STOP : WAIT;
    }
}
