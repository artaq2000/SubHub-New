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
 * 322.3.127: direct video files (MKV / MP4 / WebM) are looked at too — see fileVerdict.
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

    // ------------------------------------------------------------------------
    // 322.3.127: direct video files (MKV / MP4 / WebM) — Vidnest on fbox plays a
    // 1.48 GB MKV file, not a playlist, and an ad of 276 KB before it. The file's
    // first 64 KB tell its length when they hold it (MKV/WebM «Info», MP4 «mvhd»):
    // the same rule as a playlist, under 3 minutes is not the film. Otherwise its
    // size decides: under 30 MB is not the film. An answer that is not a video
    // (a web page, a picture) is skipped; anything that cannot be judged plays.

    /** A file smaller than this is not the film or the episode. */
    static final long MIN_FILM_BYTES = 30L * 1024 * 1024;
    static final int HEAD_BYTES = 64 * 1024;

    /**
     * How a file's head is fetched: {HTTP code (or -1), head bytes, Content-Type,
     * Content-Range, Content-Length} — the last three may be null. Tests give their own.
     */
    interface FileFetcher {
        Object[] head(String url);
    }

    /** "" = play it; otherwise the reason it was skipped (the owner sees it). */
    static String fileVerdict(String url, FileFetcher fetch) {
        Object[] r;
        try { r = fetch.head(url); } catch (Exception e) { return ""; }
        if (r == null || r.length < 2 || !(r[0] instanceof Integer)) return "";
        int code = (Integer) r[0];
        if (code < 200 || code >= 300) return "";
        byte[] head = r[1] instanceof byte[] ? (byte[]) r[1] : new byte[0];
        String type = r.length > 2 && r[2] instanceof String ? ((String) r[2]).toLowerCase(Locale.ROOT) : "";
        String range = r.length > 3 && r[3] instanceof String ? (String) r[3] : "";
        String length = r.length > 4 && r[4] instanceof String ? (String) r[4] : "";

        if (notVideo(type, head)) return "ليس ملف فيديو";
        double seconds = mediaSeconds(head);
        if (seconds > 0) {
            return seconds < MIN_FILM_SECONDS ? "مقطع قصير (" + clock(seconds) + ") — غالباً إعلان" : "";
        }
        long size = totalBytes(code, range, length);
        if (size > 0 && size < MIN_FILM_BYTES) return "ملف صغير (" + bytes(size) + ") — غالباً إعلان";
        return "";
    }

    /** A web page, a picture, text or a script answered instead of a video. */
    static boolean notVideo(String type, byte[] head) {
        String t = type == null ? "" : type.trim();
        if (t.startsWith("text/") || t.startsWith("image/") || t.contains("json")
                || t.contains("javascript") || t.contains("html") || t.contains("xml")) return true;
        if (head != null && head.length > 0) {
            int i = 0;
            while (i < head.length && i < 64 && (head[i] == ' ' || head[i] == '\n' || head[i] == '\r' || head[i] == '\t')) i++;
            if (i < head.length && head[i] == '<') return true;   // <!DOCTYPE html> / <html>
        }
        return false;
    }

    /** The whole file's size: «bytes 0-65535/1590000000», or the length of a full answer. */
    static long totalBytes(int code, String contentRange, String contentLength) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    long v = Long.parseLong(contentRange.substring(slash + 1).trim());
                    if (v > 0) return v;
                } catch (Exception ignored) {}
            }
        }
        if (code == 200 && contentLength != null) {
            try {
                long v = Long.parseLong(contentLength.trim());
                if (v > 0) return v;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    static String bytes(long n) {
        if (n < 1024L * 1024) return String.format(Locale.ROOT, "%d KB", Math.max(1L, Math.round(n / 1024.0)));
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    /** The length the file's head states (MKV/WebM or MP4), or -1. */
    static double mediaSeconds(byte[] head) {
        if (head == null || head.length < 12) return -1;
        try {
            if ((head[0] & 0xff) == 0x1A && (head[1] & 0xff) == 0x45 && (head[2] & 0xff) == 0xDF && (head[3] & 0xff) == 0xA3) {
                return matroskaSeconds(head);
            }
            if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return mp4Seconds(head);
        } catch (Exception ignored) {}
        return -1;
    }

    // --- Matroska / WebM (EBML) ---
    private static final long EBML_ID = 0x1A45DFA3L, SEGMENT_ID = 0x18538067L, INFO_ID = 0x1549A966L,
            CLUSTER_ID = 0x1F43B675L, SCALE_ID = 0x2AD7B1L, DURATION_ID = 0x4489L;

    /** {value, length} of an EBML element ID at p (marker bits kept), or null. */
    private static long[] ebmlId(byte[] b, int p) {
        if (p >= b.length) return null;
        int first = b[p] & 0xff, len;
        if ((first & 0x80) != 0) len = 1;
        else if ((first & 0x40) != 0) len = 2;
        else if ((first & 0x20) != 0) len = 3;
        else if ((first & 0x10) != 0) len = 4;
        else return null;
        if (p + len > b.length) return null;
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[p + i] & 0xff);
        return new long[] { v, len };
    }

    /** {value, length} of an EBML size at p (marker removed; value -1 = unknown), or null. */
    private static long[] ebmlSize(byte[] b, int p) {
        if (p >= b.length) return null;
        int first = b[p] & 0xff, len = 1, mask = 0x80;
        while (len <= 8 && (first & mask) == 0) { len++; mask >>= 1; }
        if (len > 8 || p + len > b.length) return null;
        long v = first & (mask - 1);
        boolean allOnes = v == (mask - 1);
        for (int i = 1; i < len; i++) {
            int x = b[p + i] & 0xff;
            v = (v << 8) | x;
            if (x != 0xff) allOnes = false;
        }
        return new long[] { allOnes ? -1 : v, len };
    }

    static double matroskaSeconds(byte[] b) {
        long[] id = ebmlId(b, 0);
        if (id == null || id[0] != EBML_ID) return -1;
        long[] sz = ebmlSize(b, (int) id[1]);
        if (sz == null || sz[0] < 0) return -1;
        int p = (int) (id[1] + sz[1] + sz[0]);
        id = ebmlId(b, p);
        if (id == null || id[0] != SEGMENT_ID) return -1;
        sz = ebmlSize(b, p + (int) id[1]);
        if (sz == null) return -1;
        p += (int) (id[1] + sz[1]);
        while (p < b.length) {
            id = ebmlId(b, p);
            if (id == null) return -1;
            sz = ebmlSize(b, p + (int) id[1]);
            if (sz == null || sz[0] < 0) return -1;
            int data = p + (int) (id[1] + sz[1]);
            if (id[0] == INFO_ID) return matroskaInfo(b, data, (int) Math.min(b.length, data + sz[0]));
            if (id[0] == CLUSTER_ID) return -1;
            long next = data + sz[0];
            if (next > b.length) return -1;
            p = (int) next;
        }
        return -1;
    }

    private static double matroskaInfo(byte[] b, int p, int end) {
        long scale = 1000000L;
        double duration = -1;
        while (p < end) {
            long[] id = ebmlId(b, p);
            if (id == null) break;
            long[] sz = ebmlSize(b, p + (int) id[1]);
            if (sz == null || sz[0] < 0) break;
            int data = p + (int) (id[1] + sz[1]);
            int n = (int) sz[0];
            if (data + n > b.length) break;
            if (id[0] == SCALE_ID && n >= 1 && n <= 8) {
                long v = 0;
                for (int i = 0; i < n; i++) v = (v << 8) | (b[data + i] & 0xff);
                if (v > 0) scale = v;
            } else if (id[0] == DURATION_ID && (n == 4 || n == 8)) {
                long v = 0;
                for (int i = 0; i < n; i++) v = (v << 8) | (b[data + i] & 0xff);
                duration = n == 4 ? Float.intBitsToFloat((int) v) : Double.longBitsToDouble(v);
            }
            p = data + n;
        }
        if (!(duration > 0) || Double.isInfinite(duration)) return -1;
        return duration * scale / 1e9;
    }

    // --- MP4 (ISO BMFF): the «moov» box when it comes before the media ---
    private static long u32(byte[] b, int p) {
        return ((b[p] & 0xffL) << 24) | ((b[p + 1] & 0xffL) << 16) | ((b[p + 2] & 0xffL) << 8) | (b[p + 3] & 0xffL);
    }

    private static long u64(byte[] b, int p) {
        return (u32(b, p) << 32) | u32(b, p + 4);
    }

    private static boolean type(byte[] b, int p, String t) {
        return p + 8 <= b.length && b[p + 4] == t.charAt(0) && b[p + 5] == t.charAt(1)
                && b[p + 6] == t.charAt(2) && b[p + 7] == t.charAt(3);
    }

    static double mp4Seconds(byte[] b) {
        int p = 0;
        while (p + 8 <= b.length) {
            long size = u32(b, p);
            int header = 8;
            if (size == 1) {
                if (p + 16 > b.length) return -1;
                size = u64(b, p + 8);
                header = 16;
            } else if (size == 0) {
                size = b.length - p;
            }
            if (size < header) return -1;
            if (type(b, p, "moov")) return mvhdIn(b, p + header, (int) Math.min(b.length, p + size));
            if (type(b, p, "mdat")) return -1;   // the media first: its length is at the end of the file
            if (p + size > b.length) return -1;
            p += (int) size;
        }
        return -1;
    }

    private static double mvhdIn(byte[] b, int p, int end) {
        while (p + 8 <= end) {
            long size = u32(b, p);
            if (size < 8) return -1;
            if (type(b, p, "mvhd")) {
                int d = p + 8;
                if (d + 4 > b.length) return -1;
                int version = b[d] & 0xff;
                long scale, duration;
                if (version == 1) {
                    if (d + 32 > b.length) return -1;
                    scale = u32(b, d + 20);
                    duration = u64(b, d + 24);
                } else {
                    if (d + 20 > b.length) return -1;
                    scale = u32(b, d + 12);
                    duration = u32(b, d + 16);
                }
                if (scale <= 0 || duration <= 0 || duration == 0xffffffffL) return -1;
                return duration / (double) scale;
            }
            p += (int) size;
        }
        return -1;
    }

    /**
     * The real head fetch: the first 64 KB with «Range», the page's own request
     * (User-Agent, Referer, Origin) and its cookies; short time limits.
     */
    static FileFetcher httpHead(final Map<String, String> pageHeaders, final Cookies cookies) {
        return url -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=0-" + (HEAD_BYTES - 1));
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
                String ctype = c.getContentType();
                String crange = c.getHeaderField("Content-Range");
                String clen = c.getHeaderField("Content-Length");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (code >= 200 && code < 300) {
                    InputStream in = c.getInputStream();
                    if (in != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while (out.size() < HEAD_BYTES
                                && (n = in.read(buf, 0, Math.min(buf.length, HEAD_BYTES - out.size()))) > 0) {
                            out.write(buf, 0, n);
                        }
                        try { in.close(); } catch (Exception ignored) {}
                    }
                }
                return new Object[] { code, out.toByteArray(), ctype, crange, clen };
            } catch (Exception e) {
                return new Object[] { -1, new byte[0], null, null, null };
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            }
        };
    }

    // ------------------------------------------------------------------------
    // 322.3.127: which requests of the hidden page are a video file being played.

    private static final java.util.regex.Pattern VIDEO_FILE =
            java.util.regex.Pattern.compile(".*\\.(mkv|mp4|webm|m4v|mov)$");
    private static final java.util.regex.Pattern NOT_MEDIA = java.util.regex.Pattern.compile(
            ".*\\.(js|mjs|css|png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|json|m3u8|ts|m4s|vtt|srt|ass|html?|php|txt|xml|wasm|map|aac|mp3|m4a)$");

    /** The path of a link, lower case, without the query. */
    static String pathOf(String url) {
        if (url == null) return "";
        String v = url;
        int q = v.indexOf('?');
        if (q >= 0) v = v.substring(0, q);
        int h = v.indexOf('#');
        if (h >= 0) v = v.substring(0, h);
        int s = v.indexOf("://");
        if (s >= 0) {
            int slash = v.indexOf('/', s + 3);
            v = slash >= 0 ? v.substring(slash) : "/";
        }
        return v.toLowerCase(Locale.ROOT);
    }

    /**
     * A video file the page's player asks for: a known video ending, or a byte
     * range («Range: bytes=…», how a video element reads a file) on a link that
     * is not a page part (script, picture, playlist piece, subtitle).
     */
    static boolean isVideoFileRequest(String url, Map<String, String> headers) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return false;
        String path = pathOf(url);
        if (VIDEO_FILE.matcher(path).matches()) return true;
        if (headers == null || NOT_MEDIA.matcher(path).matches()) return false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Range")
                    && e.getValue() != null && e.getValue().trim().toLowerCase(Locale.ROOT).startsWith("bytes=")) {
                return true;
            }
        }
        return false;
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
