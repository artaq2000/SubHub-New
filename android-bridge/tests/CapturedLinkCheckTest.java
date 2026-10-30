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
        System.out.println("CapturedLinkCheckTest OK (" + n + " checks)");
    }
}
