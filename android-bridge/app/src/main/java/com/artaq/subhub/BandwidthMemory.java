package com.artaq.subhub;

/**
 * 322.3.127: «تلقائي» (automatic quality) starts from the speed this device measured
 * last time, instead of ExoPlayer's general guess for the country and connection
 * type (a guess above a weak connection makes the start stall until the quality
 * drops). Wi-Fi and mobile data are kept apart. The first time (nothing measured)
 * it starts at a middle speed — about 720p, since the automatic choice uses 70 % of
 * the estimate — and rises or falls with the real speed while playing, as before.
 *
 * Pure Java (no Android classes), so the CI tests it with plain javac.
 */
final class BandwidthMemory {
    /** The first start: 4 Mbit/s → the automatic choice takes qualities up to ~2.8 Mbit/s. */
    static final long DEFAULT_BPS = 4_000_000L;
    static final long MIN_BPS = 300_000L;
    static final long MAX_BPS = 50_000_000L;

    private BandwidthMemory() {}

    /** The saved value's name for a connection kind ("wifi", "cell", anything else = "other"). */
    static String key(String network) {
        String n = network == null ? "" : network;
        return "bw_start_" + ("wifi".equals(n) || "cell".equals(n) ? n : "other");
    }

    /** Where the automatic quality starts: the saved speed (bounded), or the middle. */
    static long initial(long saved) {
        if (saved < MIN_BPS) return DEFAULT_BPS;
        return Math.min(MAX_BPS, saved);
    }

    /** What to keep when a player ends: a real measurement only (bounded), else -1. */
    static long toSave(long estimate, boolean measured) {
        if (!measured || estimate < MIN_BPS) return -1;
        return Math.min(MAX_BPS, estimate);
    }
}
