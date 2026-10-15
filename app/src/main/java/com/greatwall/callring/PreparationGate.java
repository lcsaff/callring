package com.greatwall.callring;

final class PreparationGate {
    private final String token;
    private final long started;
    private boolean prepared, cancelled, committed;
    PreparationGate(String token, long started) { this.token = token; this.started = started; }
    void ready() { prepared = true; }
    void cancel() { cancelled = true; }
    boolean commit(String token, long now) {
        if (!prepared || cancelled || committed || !this.token.equals(token) || now - started >= 1800) return false;
        committed = true;
        return true;
    }
}
