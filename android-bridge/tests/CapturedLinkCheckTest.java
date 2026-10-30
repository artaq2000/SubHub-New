package com.artaq.subhub;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 322.3.126: captured links are looked at before they play (CapturedLinkCheck). */
public class CapturedLinkCheckTest {
    static int n = 0;

    static void eq(Object got, Object want, String what) {
        n++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": got «" + got + "», want «" + want + "»");
        }
    }

    static void yes(boolean v, String what) {
        n++;
        if (!v) throw new AssertionError(what);
    }

    static String media(double seg, int count, boolean complete) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n");
        for (int i = 0; i < count; i++) b.append("#EXTINF:").append(seg).append(",\nseg").append(i).append(".ts\n");
        if (complete) b.append("#EXT-X-ENDLIST\n");
        return b.toString();
    }

    static Object[] ok(String body) {
        return new Object[] { 200, body.getBytes(StandardCharsets.UTF_8) };
    }

    /** A fake web: url → answer; remembers what was asked. */
    static CapturedLinkCheck.Fetcher web(Map<String, Object[]> pages, List<String> asked) {
        return url -> {
            asked.add(url);
            Object[] r = pages.get(url);
            return r == null ? new Object[] { 404, new byte[0] } : r;
        };
    }

    public static void main(String[] a) throws Exception {
        // Android's HttpURLConnection sends Origin; the desktop JVM drops it unless allowed.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        List<String> asked = new ArrayList<>();
        Map<String, Object[]> pages = new HashMap<>();

        // a complete 30-second clip (an ad / the site's intro) → skipped
        pages.put("https://cdn.test/ad/index.m3u8", ok(media(6.0, 5, true)));
        String v = CapturedLinkCheck.verdict("https://cdn.test/ad/index.m3u8", web(pages, asked));
        yes(v.startsWith("مقطع قصير (0:30)"), "30 s complete clip is skipped: " + v);

        // the film: two hours → plays
        pages.put("https://cdn.test/film/index.m3u8", ok(media(6.0, 1200, true)));
        eq(CapturedLinkCheck.verdict("https://cdn.test/film/index.m3u8", web(pages, asked)), "", "2 h film plays");

        // a list still growing (no ENDLIST) is never judged short
        pages.put("https://cdn.test/live/index.m3u8", ok(media(6.0, 3, false)));
        eq(CapturedLinkCheck.verdict("https://cdn.test/live/index.m3u8", web(pages, asked)), "", "open list plays");

        // exactly the limit is not «short»; just under it is
        eq(CapturedLinkCheck.judgeMedia(media(6.0, 30, true)), "", "180 s is not short");
        yes(!CapturedLinkCheck.judgeMedia(media(5.9, 30, true)).isEmpty(), "177 s is short");

        // a master is judged by its first quality — relative link resolved against the master
        asked.clear();
        pages.put("https://cdn.test/m/ad/master.m3u8", ok("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh/index.m3u8\n"));
        pages.put("https://cdn.test/m/ad/low/index.m3u8", ok(media(5.0, 6, true)));
        v = CapturedLinkCheck.verdict("https://cdn.test/m/ad/master.m3u8", web(pages, asked));
        yes(v.startsWith("مقطع قصير"), "short master skipped: " + v);
        eq(asked.size(), 2, "master + first quality fetched");
        eq(asked.get(1), "https://cdn.test/m/ad/low/index.m3u8", "relative quality link resolved");

        pages.put("https://cdn.test/m/film/master.m3u8", ok("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n/abs/film360.m3u8\n"));
        pages.put("https://cdn.test/abs/film360.m3u8", ok(media(4.0, 2000, true)));
        eq(CapturedLinkCheck.verdict("https://cdn.test/m/film/master.m3u8", web(pages, asked)), "", "long master plays (absolute path)");

        // cannot judge → plays as before
        pages.put("https://cdn.test/m/broken/master.m3u8", ok("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmissing.m3u8\n"));
        eq(CapturedLinkCheck.verdict("https://cdn.test/m/broken/master.m3u8", web(pages, asked)), "", "quality not answering → plays");
        eq(CapturedLinkCheck.verdict("https://cdn.test/nothing.m3u8", web(pages, asked)), "", "404 → plays (ExoPlayer decides)");
        pages.put("https://cdn.test/html.m3u8", ok("<html>blocked</html>"));
        eq(CapturedLinkCheck.verdict("https://cdn.test/html.m3u8", web(pages, asked)), "", "not a playlist → plays");
        pages.put("https://cdn.test/403.m3u8", new Object[] { 403, media(6.0, 5, true).getBytes(StandardCharsets.UTF_8) });
        eq(CapturedLinkCheck.verdict("https://cdn.test/403.m3u8", web(pages, asked)), "", "403 → plays");
        eq(CapturedLinkCheck.verdict("https://cdn.test/x.m3u8", u -> new Object[] { -1, new byte[0] }), "", "no answer → plays");
        eq(CapturedLinkCheck.verdict("https://cdn.test/x.m3u8", u -> null), "", "null answer → plays");
        eq(CapturedLinkCheck.verdict("https://cdn.test/x.m3u8", u -> new Object[] { "x" }), "", "odd answer → plays");
        pages.put("https://cdn.test/nomedia.m3u8", ok("#EXTM3U\n#EXT-X-ENDLIST\n"));
        eq(CapturedLinkCheck.verdict("https://cdn.test/nomedia.m3u8", web(pages, asked)), "", "no segments → plays");

        // byte-order mark and spaces after EXTINF
        pages.put("https://cdn.test/bom.m3u8", ok("﻿#EXTM3U\n#EXTINF: 10.5,\na.ts\n#EXTINF:10,\nb.ts\n#EXT-X-ENDLIST"));
        v = CapturedLinkCheck.verdict("https://cdn.test/bom.m3u8", web(pages, asked));
        yes(v.contains("0:21"), "BOM + spaces read (20.5 s): " + v);

        // the real fetch: the page's own headers and cookies go with it
        final Map<String, String> got = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ad.m3u8", ex -> {
            got.put("ua", ex.getRequestHeaders().getFirst("User-Agent"));
            got.put("ref", ex.getRequestHeaders().getFirst("Referer"));
            got.put("origin", ex.getRequestHeaders().getFirst("Origin"));
            got.put("cookie", ex.getRequestHeaders().getFirst("Cookie"));
            got.put("x", ex.getRequestHeaders().getFirst("X-Requested-With"));
            byte[] body = media(7.5, 4, true).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> page = new HashMap<>();
            page.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) SubHubTest");
            page.put("Referer", "https://player.test/e/abc");
            page.put("Origin", "https://player.test");
            page.put("X-Requested-With", "com.artaq.subhub.bridge");
            v = CapturedLinkCheck.verdict(base + "/ad.m3u8", CapturedLinkCheck.http(page, u -> "cf=1; sid=2"));
            yes(v.startsWith("مقطع قصير (0:30)"), "real fetch judged: " + v);
            eq(got.get("ua"), "Mozilla/5.0 (Linux; Android 14) SubHubTest", "page User-Agent sent");
            eq(got.get("ref"), "https://player.test/e/abc", "page Referer sent");
            eq(got.get("origin"), "https://player.test", "page Origin sent");
            eq(got.get("cookie"), "cf=1; sid=2", "page cookies sent");
            eq(got.get("x"), null, "other page headers not copied");
            // a closed port: no answer → plays
            eq(CapturedLinkCheck.verdict("http://127.0.0.1:1/x.m3u8", CapturedLinkCheck.http(page, null)), "", "closed port → plays");
        } finally {
            server.stop(0);
        }

        eq(CapturedLinkCheck.clock(65.4), "1:05", "clock");

        files();
        System.out.println("CapturedLinkCheckTest OK (" + n + " checks)");
    }

    // ---------------------------------------------------------------- 322.3.127
    static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] bs(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static byte[] be(long v, int n) {
        byte[] b = new byte[n];
        for (int i = n - 1; i >= 0; i--) { b[i] = (byte) (v & 0xff); v >>>= 8; }
        return b;
    }

    /** One EBML element with a 1-byte size (data < 127 bytes) or 4-byte size. */
    static byte[] el(byte[] id, byte[] data) {
        byte[] size = data.length < 127 ? bs(0x80 | data.length)
                : bs(0x10, (data.length >> 16) & 0xff, (data.length >> 8) & 0xff, data.length & 0xff);
        return cat(id, size, data);
    }

    /** An MKV head: EBML header, Segment (unknown size), SeekHead, Void, Info{scale?, duration?}, Cluster. */
    static byte[] mkv(Long scale, Double durationUnits, boolean asFloat, byte[] docType) {
        byte[] ebml = el(bs(0x1A, 0x45, 0xDF, 0xA3), el(bs(0x42, 0x82), docType));
        byte[] info = new byte[0];
        if (scale != null) info = cat(info, el(bs(0x2A, 0xD7, 0xB1), be(scale, 3)));
        info = cat(info, el(bs(0x4D, 0x80), ascii("libebml")));   // MuxingApp (ignored)
        if (durationUnits != null) {
            byte[] d = asFloat ? be(Float.floatToIntBits(durationUnits.floatValue()) & 0xffffffffL, 4)
                    : be(Double.doubleToLongBits(durationUnits), 8);
            info = cat(info, el(bs(0x44, 0x89), d));
        }
        byte[] segmentBody = cat(
                el(bs(0x11, 0x4D, 0x9B, 0x74), new byte[40]),   // SeekHead
                el(bs(0xEC), new byte[10]),                     // Void
                el(bs(0x15, 0x49, 0xA9, 0x66), info),           // Info
                el(bs(0x1F, 0x43, 0xB6, 0x75), new byte[60]));  // Cluster
        return cat(ebml, bs(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), segmentBody, new byte[300]);
    }

    static byte[] box(String type, byte[] body) {
        return cat(be(8 + body.length, 4), ascii(type), body);
    }

    static byte[] mvhd(int version, long scale, long duration) {
        byte[] body = version == 1
                ? cat(bs(1, 0, 0, 0), new byte[16], be(scale, 4), be(duration, 8), new byte[80])
                : cat(bs(0, 0, 0, 0), new byte[8], be(scale, 4), be(duration, 4), new byte[80]);
        return box("mvhd", body);
    }

    static byte[] mp4(byte[] mvhd, boolean mediaFirst) {
        byte[] ftyp = box("ftyp", cat(ascii("isom"), be(0x200, 4), ascii("isomiso2avc1mp41")));
        byte[] moov = box("moov", cat(mvhd, box("trak", new byte[50])));
        byte[] mdat = cat(be(5000000, 4), ascii("mdat"), new byte[200]);   // its size runs past the head
        return mediaFirst ? cat(ftyp, mdat) : cat(ftyp, moov, mdat);
    }

    static CapturedLinkCheck.FileFetcher fixed(int code, byte[] head, String type, String range, String length) {
        return url -> new Object[] { code, head, type, range, length };
    }

    static void files() throws Exception {
        final String GB148 = "bytes 0-65535/1589137899";
        byte[] mk = ascii("matroska"), wm = ascii("webm");

        // the film: 1:52:11 in an MKV (Duration in ms, scale 1 ms) → plays
        byte[] film = mkv(1000000L, 6731000.0, false, mk);
        eq(CapturedLinkCheck.mediaSeconds(film) > 6730.9 && CapturedLinkCheck.mediaSeconds(film) < 6731.1, true, "MKV 1:52:11 read");
        eq(CapturedLinkCheck.fileVerdict("https://s3.test/a.mkv", fixed(206, film, "video/x-matroska", GB148, "65536")), "", "MKV film plays");

        // an ad: 30 s, float Duration, no TimecodeScale (default 1 ms) → skipped
        byte[] ad = mkv(null, 30000.0, true, wm);
        String v = CapturedLinkCheck.fileVerdict("https://ads.test/x.webm", fixed(206, ad, "video/webm", "bytes 0-65535/9000000", null));
        yes(v.startsWith("مقطع قصير (0:30)"), "30 s WebM ad skipped: " + v);

        // another scale (1 µs units)
        eq(Math.round(CapturedLinkCheck.mediaSeconds(mkv(1000L, 125000000.0, false, mk))), 125L, "MKV µs scale");

        // MKV without Duration: the size decides
        byte[] noDur = mkv(1000000L, null, false, mk);
        eq(CapturedLinkCheck.mediaSeconds(noDur), -1.0, "MKV without Duration = unknown");
        eq(CapturedLinkCheck.fileVerdict("https://s3.test/a.mkv", fixed(206, noDur, "video/x-matroska", GB148, null)), "", "1.48 GB plays");
        v = CapturedLinkCheck.fileVerdict("https://ads.test/a", fixed(206, noDur, "video/mp4", "bytes 0-65535/282624", null));
        yes(v.startsWith("ملف صغير (276 KB)"), "276 KB file skipped: " + v);

        // MP4 with moov first
        v = CapturedLinkCheck.fileVerdict("https://ads.test/p.mp4", fixed(206, mp4(mvhd(0, 1000, 45000), false), "video/mp4", GB148, null));
        yes(v.startsWith("مقطع قصير (0:45)"), "45 s MP4 skipped even when the size is big: " + v);
        eq(CapturedLinkCheck.fileVerdict("https://cdn.test/f.mp4", fixed(206, mp4(mvhd(0, 90000, 90000L * 7200), false), "video/mp4", null, null)),
                "", "2 h MP4 plays");
        eq(Math.round(CapturedLinkCheck.mediaSeconds(mp4(mvhd(1, 48000, 48000L * 5400), false))), 5400L, "mvhd version 1");
        eq(CapturedLinkCheck.mediaSeconds(mp4(mvhd(0, 1000, 0), false)), -1.0, "fragmented MP4 (no duration) = unknown");

        // MP4 with the media first: length unknown → the size
        byte[] late = mp4(mvhd(0, 1000, 45000), true);
        eq(CapturedLinkCheck.mediaSeconds(late), -1.0, "moov after mdat = unknown");
        eq(CapturedLinkCheck.fileVerdict("https://cdn.test/f.mp4", fixed(206, late, "video/mp4", "bytes 0-65535/1400000000", null)), "", "big late-moov MP4 plays");
        v = CapturedLinkCheck.fileVerdict("https://ads.test/f.mp4", fixed(206, late, "video/mp4", "bytes 0-65535/2097152", null));
        yes(v.startsWith("ملف صغير (2.0 MB)"), "2 MB late-moov MP4 skipped: " + v);

        // a server that ignores Range: 200 with the whole length
        v = CapturedLinkCheck.fileVerdict("https://ads.test/f.mp4", fixed(200, late, "video/mp4", null, "512000"));
        yes(v.startsWith("ملف صغير (500 KB)"), "200 + Content-Length used: " + v);
        eq(CapturedLinkCheck.fileVerdict("https://cdn.test/f.mp4", fixed(206, late, "video/mp4", null, "65536")), "",
                "206 without a total → the head's length is not the file's → plays");

        // not a video
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mp4", fixed(200, ascii("<!DOCTYPE html><p>blocked"), "text/html; charset=utf-8", null, "900")),
                "ليس ملف فيديو", "a web page is skipped");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mp4", fixed(200, ascii("\n  <html>"), "application/octet-stream", null, null)),
                "ليس ملف فيديو", "HTML without a type is skipped");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mp4", fixed(200, new byte[10], "image/png", null, null)), "ليس ملف فيديو", "a picture is skipped");

        // cannot judge → plays (ExoPlayer decides)
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mkv", fixed(403, film, "video/x-matroska", GB148, null)), "", "403 → plays");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mkv", fixed(-1, new byte[0], null, null, null)), "", "no answer → plays");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mkv", u -> null), "", "null → plays");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mkv", u -> { throw new RuntimeException("x"); }), "", "throws → plays");
        eq(CapturedLinkCheck.fileVerdict("https://x.test/a.mkv", fixed(206, new byte[64], "application/octet-stream", null, null)), "", "unknown bytes, no size → plays");
        eq(CapturedLinkCheck.mediaSeconds(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 1, 2 }), -1.0, "cut EBML head");
        byte[] cut = java.util.Arrays.copyOf(film, 70);
        eq(CapturedLinkCheck.mediaSeconds(cut), -1.0, "head cut before Info");

        // sizes and totals
        eq(CapturedLinkCheck.totalBytes(206, "bytes 0-0/1589137899", null), 1589137899L, "Content-Range total");
        eq(CapturedLinkCheck.totalBytes(206, "bytes 0-0/*", "1"), -1L, "unknown total");
        eq(CapturedLinkCheck.totalBytes(200, null, "777"), 777L, "full answer length");
        eq(CapturedLinkCheck.bytes(282624), "276 KB", "KB");
        eq(CapturedLinkCheck.bytes(1589137899L), "1.48 GB", "GB");
        eq(CapturedLinkCheck.bytes(31457280L), "30.0 MB", "MB");

        // which requests are a video file
        Map<String, String> range = new HashMap<>();
        range.put("Range", "bytes=0-");
        Map<String, String> none = new HashMap<>();
        yes(CapturedLinkCheck.isVideoFileRequest("https://s3.streamflixserver.site/AeV6iwQM.mkv", none), ".mkv");
        yes(CapturedLinkCheck.isVideoFileRequest("https://cdn.test/v/Film.MP4?token=1&e=2", none), ".MP4 with a query");
        yes(CapturedLinkCheck.isVideoFileRequest("https://kiwanisburials.qpon/v/3fa0cbfbf1dae9255ef811023", range), "no ending + Range");
        yes(!CapturedLinkCheck.isVideoFileRequest("https://cdn.test/seg/1.ts", range), ".ts piece with Range");
        yes(!CapturedLinkCheck.isVideoFileRequest("https://cdn.test/index.m3u8", range), "playlist");
        yes(!CapturedLinkCheck.isVideoFileRequest("https://cdn.test/app.js", range), "script with Range");
        yes(!CapturedLinkCheck.isVideoFileRequest("https://cdn.test/v/3fa0cbf", none), "no ending, no Range");
        yes(!CapturedLinkCheck.isVideoFileRequest("http://cdn.test/a.mkv", none), "plain http");
        yes(!CapturedLinkCheck.isVideoFileRequest("https://cdn.test/a.mkv.html", none), ".mkv.html");
        Map<String, String> lower = new HashMap<>();
        lower.put("range", " bytes=1000-");
        yes(CapturedLinkCheck.isVideoFileRequest("https://cdn.test/v/abc", lower), "range header in lower case");
        eq(CapturedLinkCheck.pathOf("https://h.test/A/B.MKV?x=1#t"), "/a/b.mkv", "path of a link");

        // the real head fetch: Range, the page's headers and cookies
        final Map<String, String> got = new HashMap<>();
        final byte[] big = cat(film, new byte[200000]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/film.mkv", ex -> {
            got.put("range", ex.getRequestHeaders().getFirst("Range"));
            got.put("ref", ex.getRequestHeaders().getFirst("Referer"));
            got.put("cookie", ex.getRequestHeaders().getFirst("Cookie"));
            got.put("ua", ex.getRequestHeaders().getFirst("User-Agent"));
            int end = Math.min(big.length, 65536) - 1;
            ex.getResponseHeaders().add("Content-Type", "video/x-matroska");
            ex.getResponseHeaders().add("Content-Range", "bytes 0-" + end + "/1589137899");
            ex.sendResponseHeaders(206, end + 1);
            try (OutputStream o = ex.getResponseBody()) { o.write(big, 0, end + 1); }
        });
        server.createContext("/ad", ex -> {   // ignores Range: the whole (small) file
            byte[] body = cat(mp4(mvhd(0, 1000, 300000), true), new byte[1000]);
            ex.getResponseHeaders().add("Content-Type", "video/mp4");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> page = new HashMap<>();
            page.put("User-Agent", "Mozilla/5.0 SubHubTest");
            page.put("Referer", "https://vidnest.fun/");
            page.put("Range", "bytes=0-");   // the page's own range is not copied
            Object[] r = CapturedLinkCheck.httpHead(page, u -> "a=1").head(base + "/film.mkv");
            eq(r[0], 206, "206 answer");
            eq(((byte[]) r[1]).length, 65536, "64 KB read");
            eq(got.get("range"), "bytes=0-65535", "our Range sent");
            eq(got.get("ref"), "https://vidnest.fun/", "page Referer sent");
            eq(got.get("cookie"), "a=1", "page cookies sent");
            eq(got.get("ua"), "Mozilla/5.0 SubHubTest", "page User-Agent sent");
            eq(CapturedLinkCheck.fileVerdict(base + "/film.mkv", CapturedLinkCheck.httpHead(page, null)), "", "real MKV film plays");
            v = CapturedLinkCheck.fileVerdict(base + "/ad", CapturedLinkCheck.httpHead(page, null));
            yes(v.startsWith("ملف صغير"), "real small file skipped: " + v);
            eq(CapturedLinkCheck.fileVerdict("http://127.0.0.1:1/x.mkv", CapturedLinkCheck.httpHead(page, null)), "", "closed port → plays");
        } finally {
            server.stop(0);
        }
    }
}
