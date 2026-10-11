package com.artaq.subhub;

/**
 * 322.3.112: the two timing rules of the app player, kept free of Android so
 * they are tested on their own (tests/EpisodeTimingTest.java).
 *
 *  - creditsAtMs: when «الحلقة التالية» appears. 10 s after the last subtitle
 *    line when that line is in the last fifth of the episode (the credits);
 *    otherwise 40 s before the end. A last line very near the end: right after
 *    it, never over the dialogue. A short video (under 5 minutes): only its end.
 *  - lastDialogueEndMs (322.3.114): the end of the last line that is really
 *    dialogue — the translator's credit at the very end («ترجمة …», «@…»), and a
 *    line left alone after a long silence in the last two minutes, are not.
 *  - autoPhaseLimitMs: smart waiting in «مشاهدة تلقائية». Opening the page 10 s,
 *    the server 12 s, its video link 20 s, starting the video 15 s; a server
 *    whose usual start time is known gets twice that, at most 30 s.
 */
public final class EpisodeTiming {
    public static final long NEXT_AFTER_LAST_LINE_MS = 10000L;
    public static final long NEXT_NO_SUBTITLE_MS = 40000L;
    public static final int NEXT_COUNTDOWN_S = 10;          // 322.3.114: 10 s (was 5)
    public static final long CREDIT_GAP_MS = 60000L;        // silence before a lone last line
    public static final long CREDIT_TAIL_MS = 120000L;      // ... that sits in the last 2 minutes
    private static final java.util.regex.Pattern CREDIT_LINE = java.util.regex.Pattern.compile(
            "ترجم|تعديل|تدقيق|مراجعة|توقيت|تحيات|متابعتكم|حصري|تيليجرام|تلجرام|تليجرام|انستقرام|انستغرام"
            + "|subtitle|subbed|translat|caption|resync|synced|@|www\\.|\\.com|\\.net|telegram|instagram|subhub",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    public static final long SHORT_VIDEO_MS = 300000L;

    public static final long AUTO_PAGE_MS = 10000L;
    public static final long AUTO_SERVER_MS = 12000L;
    public static final long AUTO_LINK_MS = 20000L;
    public static final long AUTO_START_MS = 15000L;
    public static final long AUTO_MAX_MS = 30000L;

    private EpisodeTiming() {}

    public static long creditsAtMs(long durationMs, long lastLineEndMs) {
        if (durationMs <= 0L) return Long.MAX_VALUE;
        if (durationMs < SHORT_VIDEO_MS) return durationMs;   // a short video: only its end
        if (lastLineEndMs > durationMs * 8L / 10L && lastLineEndMs < durationMs) {
            long at = lastLineEndMs + NEXT_AFTER_LAST_LINE_MS;
            long latest = durationMs - (NEXT_COUNTDOWN_S + 1) * 1000L;
            if (at > latest) at = Math.max(lastLineEndMs + 1000L, latest);
            return at;
        }
        return Math.max(0L, durationMs - NEXT_NO_SUBTITLE_MS);
    }

    public static boolean isCreditLine(String text) {
        return text != null && CREDIT_LINE.matcher(text).find();
    }

    /**
     * Video time (ms) where the dialogue ends, -1 if unknown. startS/endS in
     * seconds as in the subtitle file; offsetMs moves them onto the video.
     */
    public static long lastDialogueEndMs(double[] startS, double[] endS, String[] text,
                                         long offsetMs, long durationMs) {
        if (startS == null || endS == null || text == null) return -1L;
        int n = Math.min(startS.length, Math.min(endS.length, text.length));
        if (n <= 0) return -1L;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(endS[a], endS[b]));
        int i = n - 1, skipped = 0;
        while (i >= 0 && skipped < 6) {
            int c = idx[i];
            if (isCreditLine(text[c])) { i--; skipped++; continue; }
            if (i > 0 && durationMs > 0L) {
                long start = Math.round(startS[c] * 1000d) + offsetMs;
                long prevEnd = Math.round(endS[idx[i - 1]] * 1000d) + offsetMs;
                if (start - prevEnd >= CREDIT_GAP_MS && start >= durationMs - CREDIT_TAIL_MS) {
                    i--; skipped++; continue;
                }
            }
            break;
        }
        if (i < 0) return -1L;
        double end = endS[idx[i]];
        return end > 0d ? Math.round(end * 1000d) + offsetMs : -1L;
    }

    /** phase: 1–2 page, 3 server, 4 video link, 5–6 starting the video. */
    public static long autoPhaseLimitMs(int phase, long expectMs) {
        long base;
        if (phase <= 2) base = AUTO_PAGE_MS;
        else if (phase == 3) base = AUTO_SERVER_MS;
        else if (phase == 4) base = AUTO_LINK_MS;
        else base = AUTO_START_MS;
        if (expectMs > 0L) base = Math.max(base, Math.min(AUTO_MAX_MS, expectMs * 2L));
        return base;
    }
}
