package com.artaq.subhub;

/** 322.3.127: start quality, down on stalls, back up when calm (StallGuard). */
public class StallGuardTest {
    static int n = 0;

    static void eq(Object got, Object want, String what) {
        n++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": got «" + got + "», want «" + want + "»");
        }
    }

    public static void main(String[] a) {
        final int[] vip = { 2160, 1080, 720, 480, 360 };
        final long S = 1000L, M = 60_000L;

        eq(StallGuard.START_HEIGHT, 720, "every film starts at 720");

        StallGuard g = new StallGuard();
        long t = 1_000_000L;
        g.begin(StallGuard.START_HEIGHT, t);
        eq(g.cap(), 720, "starts at 720");

        // one short stall: nothing
        eq(g.onStall(t += 30 * S, 720, vip), 0, "one stall");
        // a second within two minutes: one quality down
        eq(g.onStall(t += 60 * S, 720, vip), 480, "two stalls in 2 min → 480");
        eq(g.cap(), 480, "cap 480");
        // the count starts again after a change
        eq(g.onStall(t += 10 * S, 480, vip), 0, "first stall after the change");
        // a long stall: one quality down at once
        eq(g.onLongStall(t += 6 * S, 480, vip), 360, "6 s stall → 360");
        // the lowest: nothing lower
        eq(g.onLongStall(t += 6 * S, 360, vip), 0, "already the lowest");
        eq(g.cap(), 360, "still 360");

        // calm: one step up after 5 minutes, never above the start
        eq(g.onCalm(t += 4 * M, vip), 0, "4 min calm: not yet");
        eq(g.onCalm(t += 2 * M, vip), 480, "5+ min calm → 480");
        eq(g.onCalm(t += 2 * M, vip), 0, "2 min after the change: not yet");
        eq(g.onCalm(t += 4 * M, vip), 720, "→ 720 (the start)");
        eq(g.onCalm(t += 20 * M, vip), 0, "never above the start");
        eq(g.cap(), 720, "back at the start");

        // stalls spread out (more than 2 minutes apart): no change
        g.begin(720, t);
        eq(g.onStall(t += 10 * S, 720, vip), 0, "stall 1");
        eq(g.onStall(t += 3 * M, 720, vip), 0, "stall 2, 3 min later: still one in the window");
        eq(g.onStall(t += 90 * S, 720, vip), 480, "stall 3 within 2 min of stall 2 → down");

        // the viewer raised it by hand to 2160 (his own guard): the steps it offers him
        g.begin(2160, t);
        eq(g.cap(), 2160, "his pick");
        eq(g.onStall(t += 5 * S, 2160, vip), 0, "one stall: nothing yet");
        eq(g.onStall(t += 40 * S, 2160, vip), 1080, "two stalls → offer 1080");
        eq(g.onLongStall(t += 6 * S, 2160, vip), 720, "he kept 2160 and it stalls long → offer 720");

        // the stream plays lower than the cap already: down from what plays
        g.begin(720, t);
        eq(g.onLongStall(t += 6 * S, 480, vip), 360, "playing 480 under a 720 cap → 360");

        // a server without 720 (1080 / 480): the start keeps «720», the step goes to what exists
        final int[] odd = { 1080, 480 };
        g.begin(720, t);
        eq(g.onLongStall(t += 6 * S, 480, odd), 0, "480 is the lowest");
        g.begin(720, t);
        eq(g.onLongStall(t += 6 * S, 0, odd), 480, "height unknown: from the cap → 480");
        eq(g.onCalm(t += 6 * M, odd), 720, "up: 1080 is above the start → the start");

        // one quality only (a file): nothing to do
        g.begin(720, t);
        eq(g.onLongStall(t += 6 * S, 1080, new int[] { 1080 }), 0, "single quality");
        eq(g.onLongStall(t += 6 * S, 0, null), 0, "no qualities known");
        System.out.println("StallGuardTest OK (" + n + " checks)");
    }
}
