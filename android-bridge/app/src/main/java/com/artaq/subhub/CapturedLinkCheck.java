package com.artaq.subhub;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 322.3.126: a captured stream link is looked at before it plays.
 *
 * The provider page now works hidden behind the SubHub screen, and these pages
 * often play a short clip first (an ad, or the site's own intro). Its link is a
 * complete playlist (#EXT-X-ENDLIST) of a minute or two. Such a link is skipped,
 * and the capture goes on for the film's own link. A master playlist is judged
 * by its first quality. Anything that cannot be judged — no answer, not a
 * playlist, a list that is still growing — plays exactly as before.
 *
 * Pure Java (no Android classes), so the CI tests it with plain javac.
 */
final class CapturedLinkCheck {
    /** A complete playlist shorter than this is not the film or the episode. */
    static final double MIN_FILM_SECONDS = 180.0;
    static final int MAX_BYTES = 1_500_000;
    static final int CONNECT_MS = 3000;
    static final int READ_MS = 4000;

    private static final Pattern EXTINF = Pattern.compile("#EXTINF:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private CapturedLinkCheck() {}

    /** How a link is fetched: {HTTP code (or -1), body bytes}. Tests give their own. */
    interface Fetcher {
        Object[] get(String url);
    }

    /** The page's cookies for a link (the WebView's CookieManager in the app). */
    interface Cookies {
        String forUrl(String url);
    }

    /** "" = play it; otherwise the reason it was skipped (the owner sees it). */
    static String verdict(String url, Fetcher fetch) {
        String text = playlist(fetch.get(url));
        if (text == null) return "";
        if (text.contains("#EXT-X-STREAM-INF")) {
            String variant = firstVariant(text);
            if (variant == null) return "";
            String media = playlist(fetch.get(resolve(url, variant)));
            return media == null ? "" : judgeMedia(media);
        }
        return judgeMedia(text);
    }

    /** A media playlist: complete and short → skipped; otherwise "". */
    static String judgeMedia(String media) {
        if (media == null || !media.contains("#EXT-X-ENDLIST")) return "";
        double seconds = totalSeconds(media);
        if (seconds <= 0) return "";
        if (seconds < MIN_FILM_SECONDS) return "مقطع قصير (" + clock(seconds) + ") — غالباً إعلان";
        return "";
    }

    static double totalSeconds(String media) {
        double sum = 0;
        Matcher m = EXTINF.matcher(media);
        while (m.find()) {
            try { sum += Double.parseDouble(m.group(1)); } catch (Exception ignored) {}
        }
        return sum;
    }

    /** The first quality's link in a master playlist (the line after its #EXT-X-STREAM-INF). */
    static String firstVariant(String master) {
        boolean armed = false;
        for (String raw : master.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.startsWith("#EXT-X-STREAM-INF")) { armed = true; continue; }
            if (armed && !l.isEmpty() && !l.startsWith("#")) return l;
        }
        return null;
    }

    /** The body as a playlist (2xx and starting with #EXTM3U), else null. */
    static String playlist(Object[] r) {
        if (r == null || r.length < 2 || !(r[0] instanceof Integer)) return null;
        int code = (Integer) r[0];
        if (code < 200 || code >= 300 || !(r[1] instanceof byte[])) return null;
        String t = new String((byte[]) r[1], StandardCharsets.UTF_8).replace("﻿", "").trim();
        return t.startsWith("#EXTM3U") ? t : null;
    }

    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); }
        catch (Exception e) { return rel; }
    }

    static String clock(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * The real fetch: the request the page itself made (its User-Agent, Referer,
     * Origin) plus the page's cookies; short time limits, so a silent server
     * never holds the start for long.
     */
    static Fetcher http(final Map<String, String> pageHeaders, final Cookies cookies) {
        return url -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept", "*/*");
                if (pageHeaders != null) {
                    for (Map.Entry<String, String> e : pageHeaders.entrySet()) {
                        String k = e.getKey();
                        if (k == null || e.getValue() == null) continue;
                        String low = k.toLowerCase(Locale.ROOT);
                        if (low.equals("user-agent") || low.equals("referer") || low.equals("origin")) {
                            c.setRequestProperty(k, e.getValue());
                        }
                    }
                }
                if (cookies != null) {
                    String ck = null;
                    try { ck = cookies.forUrl(url); } catch (Exception ignored) {}
                    if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (out.size() < MAX_BYTES
                            && (n = in.read(buf, 0, Math.min(buf.length, MAX_BYTES - out.size()))) > 0) {
                        out.write(buf, 0, n);
                    }
                    try { in.close(); } catch (Exception ignored) {}
                }
                return new Object[] { code, out.toByteArray() };
            } catch (Exception e) {
                return new Object[] { -1, new byte[0] };
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            }
        };
    }
}
