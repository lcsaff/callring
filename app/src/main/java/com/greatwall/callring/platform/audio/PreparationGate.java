package com.greatwall.callring.platform.audio;

public final class PreparationGate {
    private final String token;
    private final long started;
    private boolean prepared, cancelled, committed;
    public PreparationGate(String token, long started) { this.token = token; this.started = started; }
    public void ready() { prepared = true; }
    public void cancel() { cancelled = true; }
    public boolean commit(String token, long now) {
        if (!prepared || cancelled || committed || !this.token.equals(token) || now - started >= 1800) return false;
        committed = true;
        return true;
    }
}
