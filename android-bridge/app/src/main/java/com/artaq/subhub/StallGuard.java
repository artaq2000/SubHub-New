package com.artaq.subhub;

import java.util.ArrayDeque;

/**
 * 322.3.127: the quality a film plays at, and what happens when it stalls.
 *
 * Every film starts at 720 (or the nearest quality under it: 480 when there is no
 * 720) — never what was picked last time (owner's choice: viewers raise it without
 * knowing that some servers, or some connections, are slow) — and the automatic
 * choice never goes above that start. When it stalls twice within two minutes, or
 * once for six seconds, it goes one quality down (and the viewer is told). After
 * five minutes without a stall it goes one quality back up, never above the start.
 * A quality the viewer raised by hand is never changed by itself: the same rule only
 * offers him «⬇ خفّض» (a second guard, see DirectStreamPlayer).
 *
 * Heights only (2160 / 1080 / 720 / 480 …): the numbers the viewer sees in «الجودة».
 * Pure Java (no Android classes), so the CI tests it with plain javac.
 */
final class StallGuard {
    static final int START_HEIGHT = 720;
    static final long WINDOW_MS = 120_000L;     // two stalls within this → one quality down
    static final long LONG_STALL_MS = 6_000L;   // or one stall this long
    static final long CALM_MS = 300_000L;       // this long without a stall → one quality up

    private final ArrayDeque<Long> stalls = new ArrayDeque<>();
    private int top = START_HEIGHT;   // the start: the automatic choice never goes above it
    private int cap = START_HEIGHT;   // what it may use now (lower after stalls)
    private long changedAt = 0L;
    private long lastStallAt = 0L;

    /** A new film (or «تلقائي» chosen again) at 720; a hand pick at its own height. */
    void begin(int topHeight, long now) {
        top = topHeight > 0 ? topHeight : START_HEIGHT;
        cap = top;
        stalls.clear();
        changedAt = now;
        lastStallAt = 0L;
    }

    int cap() { return cap; }

    int top() { return top; }

    /** A stall began. The new (lower) cap, or 0 when nothing changes. */
    int onStall(long now, int playingHeight, int[] heights) {
        lastStallAt = now;
        while (!stalls.isEmpty() && now - stalls.peekFirst() > WINDOW_MS) stalls.pollFirst();
        stalls.addLast(now);
        if (stalls.size() < 2) return 0;
        return stepDown(now, playingHeight, heights);
    }

    /** The same stall is still going after LONG_STALL_MS. */
    int onLongStall(long now, int playingHeight, int[] heights) {
        lastStallAt = now;
        return stepDown(now, playingHeight, heights);
    }

    private int stepDown(long now, int playingHeight, int[] heights) {
        int from = playingHeight > 0 ? Math.min(cap, playingHeight) : cap;
        int lower = 0;
        if (heights != null) for (int h : heights) if (h > 0 && h < from && h > lower) lower = h;
        if (lower <= 0) return 0;   // already the lowest quality there is
        cap = lower;
        changedAt = now;
        stalls.clear();
        return cap;
    }

    /**
     * Playing smoothly: after CALM_MS without a stall (and since the last change),
     * one quality up — never above the start. The new cap, or 0 when nothing changes.
     */
    int onCalm(long now, int[] heights) {
        if (cap >= top) return 0;
        if (now - lastStallAt < CALM_MS || now - changedAt < CALM_MS) return 0;
        int higher = Integer.MAX_VALUE;
        if (heights != null) for (int h : heights) if (h > cap && h < higher) higher = h;
        cap = higher <= top ? higher : top;
        changedAt = now;
        return cap;
    }
}
