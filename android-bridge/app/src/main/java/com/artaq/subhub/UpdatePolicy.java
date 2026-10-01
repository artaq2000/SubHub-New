package com.artaq.subhub;

public final class UpdatePolicy {
    public static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private UpdatePolicy() {}
    public static boolean isDue(long now, long lastAttempt) {
        return lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= INTERVAL_MS;
    }
}
