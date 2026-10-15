package com.greatwall.callring;

final class SessionGate {
    static final int WAIT = 0, PLAY = 1, STOP = 2;
    private final String token;
    private final boolean preview;
    private final long started;
    private boolean sawRinging;
    SessionGate(String token, boolean preview, long now) {
        this.token = token; this.preview = preview; this.started = now;
    }
    boolean owns(String token) { return this.token.equals(token); }
    int update(boolean ringing, boolean active, long now) {
        long elapsed = now - started;
        if (active || elapsed >= (preview ? 8000 : 90000)) return STOP;
        if (preview) return ringing ? STOP : PLAY;
        if (ringing) { sawRinging = true; return PLAY; }
        return sawRinging || elapsed >= 4000 ? STOP : WAIT;
    }
}
