package com.artaq.subhub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 322.3.128: loading on several connections (ParallelLoad) against a local server that
 * gives each connection a limited speed — the kind of server where it helps:
 *  - a whole file in parts: the same bytes, faster with 3 than with 1, jumps (the MKV
 *    index at the end, then back: the first part is not loaded twice), the end;
 *  - a server without ranges: the plain way (nothing loaded twice); from its start: the
 *    same connection;
 *  - a server refusing extra connections (429): the film goes on, one connection, the
 *    server remembered;
 *  - HLS pieces: loaded ahead, the same bytes, faster; another quality / a jump back drop
 *    what was loaded; one connection = nothing ahead; a «too many» web page → read
 *    directly, the server remembered; byte ranges inside one file;
 *  - closing the player never leaves a reader waiting.
 */
public class ParallelLoadTest {
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

    // ------------------------------------------------------------------ the server

    static final int FILE = 8 * 1024 * 1024;
    static final int RATE = 2 * 1024 * 1024;            // bytes per second, per connection
    static final int SEG = 256 * 1024, SEGS = 24;
    static final byte[] DATA = new byte[FILE];
    static final byte[][] V1 = new byte[SEGS][], V2 = new byte[SEGS][];
    static final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());
    static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
    static final Map<String, AtomicInteger> busy = new ConcurrentHashMap<>();

    static void resetCounters() {
        maxActive.set(0);
        log.clear();
    }

    static void send(HttpExchange ex, byte[] all, boolean ranges, int rate, String type) throws IOException {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        try {
            String range = ex.getRequestHeaders().getFirst("Range");
            log.add(ex.getRequestURI().getPath() + " " + range);
            long s = 0, e = all.length - 1;
            int code = 200;
            if (ranges && range != null) {
                Matcher m = RANGE.matcher(range);
                if (m.matches()) {
                    s = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) e = Math.min(e, Long.parseLong(m.group(2)));
                    if (s >= all.length) {
                        ex.getResponseHeaders().add("Content-Range", "bytes */" + all.length);
                        ex.sendResponseHeaders(416, -1);
                        return;
                    }
                    code = 206;
                    ex.getResponseHeaders().add("Content-Range", "bytes " + s + "-" + e + "/" + all.length);
                }
            }
            if (type != null) ex.getResponseHeaders().add("Content-Type", type);
            ex.getResponseHeaders().add("Accept-Ranges", ranges ? "bytes" : "none");
            long len = e - s + 1;
            ex.sendResponseHeaders(code, len);
            OutputStream out = ex.getResponseBody();
            int chunk = 16 * 1024;
            long t0 = System.nanoTime();
            long sent = 0;
            try {
                while (sent < len) {
                    int c = (int) Math.min(chunk, len - sent);
                    out.write(all, (int) (s + sent), c);
                    sent += c;
                    if (rate > 0 && sent < len) {
                        long due = t0 + sent * 1_000_000_000L / rate;
                        long wait = due - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    }
                }
                out.close();
            } catch (IOException | InterruptedException ignored) {
                // the client closed early
            }
        } finally {
            active.decrementAndGet();
            ex.close();
        }
    }

    static HttpServer server() throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        srv.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        srv.createContext("/file.bin", ex -> send(ex, DATA, true, RATE, "video/x-matroska"));
        srv.createContext("/norange.bin", ex -> send(ex, DATA, false, RATE, "video/mp4"));
        srv.createContext("/slow.bin", ex -> send(ex, DATA, true, 64 * 1024, "video/mp4"));
        // refuses a second connection at the same time (429)
        srv.createContext("/refuse.bin", ex -> {
            AtomicInteger b = busy.computeIfAbsent("refuse", k -> new AtomicInteger());
            if (b.incrementAndGet() > 1) {
                b.decrementAndGet();
                log.add("/refuse.bin 429");
                ex.sendResponseHeaders(429, -1);
                ex.close();
                return;
            }
            try { send(ex, DATA, true, RATE, "video/mp4"); } finally { b.decrementAndGet(); }
        });
        srv.createContext("/hls/", ex -> {
            String p = ex.getRequestURI().getPath();   // /hls/v1/seg7.ts
            Matcher m = Pattern.compile("/hls/(v[12])/seg(\\d+)\\.ts").matcher(p);
            if (!m.matches()) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[][] v = "v1".equals(m.group(1)) ? V1 : V2;
            send(ex, v[Integer.parseInt(m.group(2))], true, RATE, "video/mp2t");
        });
        // a server answering a second connection with its «too many» web page (200)
        srv.createContext("/page/", ex -> {
            AtomicInteger b = busy.computeIfAbsent("page", k -> new AtomicInteger());
            Matcher m = Pattern.compile("/page/seg(\\d+)\\.ts").matcher(ex.getRequestURI().getPath());
            if (!m.matches()) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            if (b.incrementAndGet() > 1) {
                b.decrementAndGet();
                byte[] html = "<!doctype html><html><body>Too many connections</body></html>".getBytes("UTF-8");
                log.add("/page html");
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, html.length);
                ex.getResponseBody().write(html);
                ex.close();
                return;
            }
            try { send(ex, V1[Integer.parseInt(m.group(1))], true, RATE, "video/mp2t"); } finally { b.decrementAndGet(); }
        });
        srv.start();
        return srv;
    }

    // ------------------------------------------------------------------ the requests (plain Java)

    static final class Http implements ParallelLoad.Fetcher {
        final AtomicInteger opened = new AtomicInteger();

        public ParallelLoad.Response segment(String uri, long position, long length) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            if (position > 0 || length >= 0) {
                c.setRequestProperty("Range", "bytes=" + position + "-" + (length >= 0 ? String.valueOf(position + length - 1) : ""));
            }
            return answer(c);
        }

        public ParallelLoad.Response range(String uri, long start, long end) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setRequestProperty("Range", "bytes=" + start + "-" + (end >= 0 ? String.valueOf(end) : ""));
            return answer(c);
        }

        ParallelLoad.Response answer(final HttpURLConnection c) throws IOException {
            opened.incrementAndGet();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            final int code = c.getResponseCode();
            if (code >= 400) {
                c.disconnect();
                throw new ParallelLoad.HttpStatus(code, "");
            }
            final InputStream in = c.getInputStream();
            final Map<String, List<String>> h = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) if (e.getKey() != null) h.put(e.getKey(), e.getValue());
            return new ParallelLoad.Response() {
                public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
                public String header(String name) { return ParallelLoad.header(h, name); }
                public Map<String, List<String>> headers() { return h; }
                public void close() {
                    try { in.close(); } catch (IOException ignored) {}
                    c.disconnect();
                }
            };
        }
    }

    static final class Memory implements ParallelLoad.Hosts {
        final Set<String> off = Collections.synchronizedSet(new HashSet<>());
        public boolean refused(String host) { return off.contains(host); }
        public void refuse(String host) { off.add(host); }
    }

    static byte[] readAll(ParallelLoad.Parts.Reader r, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[37 * 1024];   // an odd size: reads cross the parts' edges
        while (max < 0 || out.size() < max) {
            int want = max < 0 ? buf.length : Math.min(buf.length, max - out.size());
            int k = r.read(buf, 0, want);
            if (k < 0) break;
            out.write(buf, 0, k);
        }
        return out.toByteArray();
    }

    static byte[] plain(String uri, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) >= 0) out.write(buf, 0, k);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static byte[] slice(byte[] a, long from, long len) {
        return Arrays.copyOfRange(a, (int) from, (int) (from + len));
    }

    /** One HLS piece as the player reads it: from the piece loaded ahead, else directly. */
    static byte[] playPiece(ParallelLoad.Segments.Window w, Http http, String uri, long position, long length,
                            int[] hits) throws IOException {
        ParallelLoad.Hit h = w.open(uri, position, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[29 * 1024];
        if (h != null) {
            hits[0]++;
            h.awaitHeaders();
            long read = 0;
            try {
                while (length < 0 || read < length) {
                    int want = length < 0 ? buf.length : (int) Math.min(buf.length, length - read);
                    int k = h.read(read, buf, 0, want);
                    if (k < 0) break;
                    out.write(buf, 0, k);
                    read += k;
                }
            } catch (ParallelLoad.PartFailed e) {
                hits[1]++;   // the piece failed: the rest directly (as the player's data source does)
                w.calm(uri, h.piece);
                try (ParallelLoad.Response r = http.segment(uri, position + read, length < 0 ? -1 : length - read)) {
                    int k;
                    while ((k = r.read(buf, 0, buf.length)) >= 0) out.write(buf, 0, k);
                }
            } finally {
                w.close(h);
            }
            return out.toByteArray();
        }
        try (ParallelLoad.Response r = http.segment(uri, position, length)) {
            w.loadAhead();   // connected: the next pieces now
            int k;
            while ((k = r.read(buf, 0, buf.length)) >= 0) out.write(buf, 0, k);
        }
        return out.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        Random rnd = new Random(128);
        rnd.nextBytes(DATA);
        for (int i = 0; i < SEGS; i++) {
            rnd.nextBytes(V1[i] = new byte[SEG]);
            rnd.nextBytes(V2[i] = new byte[SEG / 2]);
        }

        // ---- small rules
        eq(ParallelLoad.DEFAULT_CONNECTIONS, 3, "viewers: three connections");
        eq(ParallelLoad.nextChoice(3), 4, "the owner's switch 3 → 4");
        eq(ParallelLoad.nextChoice(4), 1, "4 → 1");
        eq(ParallelLoad.nextChoice(1), 3, "1 → 3");
        eq(ParallelLoad.clamp(9), 4, "at most four");
        eq(ParallelLoad.clamp(0), 1, "at least one");
        yes(ParallelLoad.refusal(429) && ParallelLoad.refusal(403) && ParallelLoad.refusal(503), "refusals");
        yes(!ParallelLoad.refusal(404) && !ParallelLoad.refusal(500) && !ParallelLoad.refusal(416), "not refusals");
        eq(ParallelLoad.hostOf("https://CDN.Example.com:8443/a/b.ts?x=1"), "cdn.example.com", "host");
        eq(ParallelLoad.hostOf("https://u:p@h.test/x"), "h.test", "host with a user");
        eq(ParallelLoad.hostOf("not a link"), "", "no host");
        eq(Arrays.toString(ParallelLoad.contentRange("bytes 100-199/1000")), "[100, 199, 1000]", "Content-Range");
        eq(Arrays.toString(ParallelLoad.contentRange("bytes 0-0/*")), "[0, 0, -1]", "unknown total");
        eq(ParallelLoad.contentRange("bytes */1000"), null, "a 416 answer");
        eq(ParallelLoad.contentRange(null), null, "none");
        eq(ParallelLoad.partSize(0), 512L * 1024, "first part small");
        eq(ParallelLoad.partSize(1), 1024L * 1024, "then 1 MB");
        eq(ParallelLoad.partSize(7), 4096L * 1024, "then 4 MB");
        byte[] page = "  <html>".getBytes("UTF-8");
        yes(ParallelLoad.isPage("text/html; charset=utf-8", page, page.length), "a web page");
        yes(!ParallelLoad.isPage("image/png", page, page.length), "a piece dressed as an image is not a page");
        yes(!ParallelLoad.isPage("text/html", new byte[] { 0x47, 0x40 }, 2), "video bytes under a wrong type");

        HttpServer srv = server();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        try {
            files(base);
            hls(base);
            closing(base);
        } finally {
            srv.stop(0);
        }
        System.out.println("ParallelLoadTest OK (" + n + " checks)");
    }

    static void files(String base) throws Exception {
        Http http = new Http();
        String file = base + "/file.bin";

        // ---- one connection (the plain way) for the time it takes
        resetCounters();
        long t0 = System.nanoTime();
        byte[] one = plain(file, 0);
        long single = System.nanoTime() - t0;
        yes(Arrays.equals(one, DATA), "plain read");

        // ---- three connections: the same bytes, faster
        ParallelLoad.Session s3 = new ParallelLoad.Session(3, new Memory());
        ParallelLoad.Parts parts = new ParallelLoad.Parts(s3, http);
        resetCounters();
        t0 = System.nanoTime();
        ParallelLoad.Parts.Reader r = parts.open(file, 0);
        yes(r != null, "a server with ranges: parts");
        eq(r.length(), (long) FILE, "its length");
        eq(r.single(), false, "in parts");
        byte[] got = readAll(r, -1);
        long multi = System.nanoTime() - t0;
        r.close();
        yes(Arrays.equals(got, DATA), "the same bytes in parts");
        yes(maxActive.get() >= 3, "three at a time: " + maxActive.get());
        yes(maxActive.get() <= 4, "not more: " + maxActive.get());
        yes(multi * 1.6 < single, "faster with three: " + multi / 1_000_000 + " ms vs " + single / 1_000_000 + " ms");
        System.out.println("  file " + (FILE >> 20) + " MB at " + (RATE >> 20) + " MB/s a connection: one "
                + single / 1_000_000 + " ms, three " + multi / 1_000_000 + " ms");

        // ---- jumps (a new reader each time, like the player): the MKV way — the start, the
        // index at the end, back to the start (the first part is not asked again)
        ParallelLoad.Session sj = new ParallelLoad.Session(3, new Memory());
        ParallelLoad.Parts pj = new ParallelLoad.Parts(sj, http);
        resetCounters();
        r = pj.open(file, 0);
        got = readAll(r, 100 * 1024);
        r.close();
        yes(Arrays.equals(got, slice(DATA, 0, 100 * 1024)), "the header");
        Thread.sleep(400);   // the first part finishes
        long tail = FILE - 300 * 1024;
        r = pj.open(file, tail);
        got = readAll(r, -1);
        r.close();
        yes(Arrays.equals(got, slice(DATA, tail, FILE - tail)), "the index at the end");
        r = pj.open(file, 4096);
        got = readAll(r, 3 * 1024 * 1024);
        yes(Arrays.equals(got, slice(DATA, 4096, 3 * 1024 * 1024)), "back near the start");
        r.close();
        int fromStart = 0;
        synchronized (log) {
            for (String l : log) if (l.equals("/file.bin bytes=0-") || l.startsWith("/file.bin bytes=4096-")
                    || l.startsWith("/file.bin bytes=0-")) fromStart++;
        }
        eq(fromStart, 1, "the first part was kept, not asked again: " + log);
        // random places
        Random rnd = new Random(7);
        for (int i = 0; i < 6; i++) {
            long at = (long) (rnd.nextDouble() * (FILE - 400 * 1024));
            r = pj.open(file, at);
            got = readAll(r, 400 * 1024);
            r.close();
            yes(Arrays.equals(got, slice(DATA, at, 400 * 1024)), "a jump to " + at);
        }
        // the very end
        r = pj.open(file, FILE);
        yes(r == null || readAll(r, -1).length == 0, "at the end: nothing");
        if (r != null) r.close();
        sj.close();

        // ---- one connection chosen: the plain way
        ParallelLoad.Session s1 = new ParallelLoad.Session(1, new Memory());
        eq(new ParallelLoad.Parts(s1, http).open(file, 1000), null, "one connection: the plain way");
        s1.close();

        // ---- the owner switches 3 → 1 → 3 while reading (the parts already loading are read
        // first: 512 KB + 1 MB + 2 MB here, then one connection)
        ParallelLoad.Session sw = new ParallelLoad.Session(3, new Memory());
        r = new ParallelLoad.Parts(sw, http).open(file, 0);
        ByteArrayOutputStream mix = new ByteArrayOutputStream();
        sw.setConnections(1);
        mix.write(readAll(r, 4 * 1024 * 1024));
        yes(r.single(), "one connection now");
        sw.setConnections(3);
        mix.write(readAll(r, -1));
        yes(!r.single(), "parts again");
        r.close();
        yes(Arrays.equals(mix.toByteArray(), DATA), "the same bytes through the switches");
        sw.close();

        // ---- a server without ranges
        ParallelLoad.Session sn = new ParallelLoad.Session(3, new Memory());
        ParallelLoad.Parts pn = new ParallelLoad.Parts(sn, http);
        resetCounters();
        eq(pn.open(base + "/norange.bin", 5000), null, "no ranges: the plain way");
        eq(pn.open(base + "/norange.bin", 9000), null, "known now: no second look");
        eq(log.size(), 1, "one look only: " + log);
        ParallelLoad.Parts pn0 = new ParallelLoad.Parts(sn, http);
        r = pn0.open(base + "/norange.bin", 0);
        yes(r != null && r.single(), "from the start: the same connection");
        eq(r.length(), (long) FILE, "its length");
        got = readAll(r, -1);
        r.close();
        yes(Arrays.equals(got, DATA), "the whole file on one connection");
        sn.close();

        // ---- a server refusing extra connections
        Memory mem = new Memory();
        ParallelLoad.Session sr = new ParallelLoad.Session(3, mem);
        ParallelLoad.Parts pr = new ParallelLoad.Parts(sr, http);
        r = pr.open(base + "/refuse.bin", 0);
        yes(r != null, "it gives ranges");
        got = readAll(r, -1);
        r.close();
        yes(Arrays.equals(got, DATA), "the film goes on: same bytes");
        yes(mem.refused("127.0.0.1"), "the server is remembered");
        yes(sr.refusedSeen(), "the owner's line can say so");
        eq(pr.open(base + "/refuse.bin", 100), null, "next time: the plain way");
        sr.close();
        // a new player: the memory says so from the start
        ParallelLoad.Session sr2 = new ParallelLoad.Session(3, mem);
        eq(new ParallelLoad.Parts(sr2, http).open(base + "/refuse.bin", 0), null, "remembered for the next films");
        sr2.close();
        s3.close();
    }

    static void hls(String base) throws Exception {
        Http http = new Http();
        List<ParallelLoad.Ref> v1 = new ArrayList<>(), v2 = new ArrayList<>();
        for (int i = 0; i < SEGS; i++) {
            v1.add(new ParallelLoad.Ref(base + "/hls/v1/seg" + i + ".ts", 0, -1));
            v2.add(new ParallelLoad.Ref(base + "/hls/v2/seg" + i + ".ts", 0, -1));
        }

        // ---- one connection: nothing ahead
        ParallelLoad.Session s1 = new ParallelLoad.Session(1, new Memory());
        ParallelLoad.Segments g1 = new ParallelLoad.Segments(s1, http);
        g1.playlist(base + "/v1.m3u8", v1);
        ParallelLoad.Segments.Window w1 = g1.window();
        int[] hits = new int[2];
        resetCounters();
        long t0 = System.nanoTime();
        for (int i = 0; i < SEGS; i++) {
            yes(Arrays.equals(playPiece(w1, http, v1.get(i).uri, 0, -1, hits), V1[i]), "piece " + i + " (one connection)");
        }
        long single = System.nanoTime() - t0;
        eq(hits[0], 0, "one connection: nothing loaded ahead");
        eq(log.size(), SEGS, "each piece asked once");
        eq(maxActive.get(), 1, "one at a time");
        s1.close();

        // ---- three connections
        ParallelLoad.Session s3 = new ParallelLoad.Session(3, new Memory());
        ParallelLoad.Segments g3 = new ParallelLoad.Segments(s3, http);
        g3.playlist(base + "/v1.m3u8", v1);
        g3.playlist(base + "/v2.m3u8", v2);
        g3.playlist(base + "/v1.m3u8", new ArrayList<>(v1));   // loaded again unchanged
        ParallelLoad.Segments.Window w = g3.window();
        hits = new int[2];
        resetCounters();
        t0 = System.nanoTime();
        for (int i = 0; i < SEGS; i++) {
            yes(Arrays.equals(playPiece(w, http, v1.get(i).uri, 0, -1, hits), V1[i]), "piece " + i + " (three)");
        }
        long multi = System.nanoTime() - t0;
        yes(hits[0] >= SEGS - 2, "the pieces came from loading ahead: " + hits[0]);
        eq(hits[1], 0, "none failed");
        eq(log.size(), SEGS, "still each piece once: " + log.size());
        // (the server counts a finished answer a moment longer than the player: one more at most)
        yes(maxActive.get() >= 3 && maxActive.get() <= 4, "three at a time: " + maxActive.get());
        yes(multi * 1.5 < single, "faster with three: " + multi / 1_000_000 + " ms vs " + single / 1_000_000 + " ms");
        System.out.println("  HLS " + SEGS + " pieces: one " + single / 1_000_000 + " ms, three " + multi / 1_000_000 + " ms");
        eq(w.ahead(), 0, "nothing left in memory at the end");

        // ---- another quality: the old one's pieces go
        hits = new int[2];
        playPiece(w, http, v1.get(5).uri, 0, -1, hits);
        eq(w.ahead(), 2, "two loading ahead");
        yes(Arrays.equals(playPiece(w, http, v2.get(6).uri, 0, -1, hits), V2[6]), "the other quality's piece");
        eq(w.ahead(), 2, "ahead in the new quality only");
        // a jump back
        playPiece(w, http, v2.get(10).uri, 0, -1, hits);
        yes(Arrays.equals(playPiece(w, http, v2.get(3).uri, 0, -1, hits), V2[3]), "a jump back");
        eq(w.ahead(), 2, "ahead of the new place only");
        // a request inside a piece (the player continues a piece it stopped)
        Thread.sleep(300);
        yes(Arrays.equals(playPiece(w, http, v2.get(4).uri, 1000, -1, hits),
                Arrays.copyOfRange(V2[4], 1000, V2[4].length)), "the rest of a piece");
        // not a piece of a known list (a key, the first part of the video)
        eq(w.open(base + "/key.bin", 0, 16), null, "unknown: read directly");
        w.release();
        eq(w.ahead(), 0, "released");

        // ---- a separate audio track: two streams loading ahead, our connections stay three
        ParallelLoad.Session sa = new ParallelLoad.Session(3, new Memory());
        ParallelLoad.Segments ga = new ParallelLoad.Segments(sa, http);
        ga.playlist(base + "/v1.m3u8", v1);
        ga.playlist(base + "/a.m3u8", v2);   // (the other list plays the audio track here)
        final ParallelLoad.Segments.Window video = ga.window(), audio = ga.window();
        final int[] maxSlots = new int[1];
        final boolean[] stop = new boolean[1];
        Thread watch = new Thread(() -> {
            while (!stop[0]) {
                maxSlots[0] = Math.max(maxSlots[0], sa.slotsUsed());
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        watch.start();
        final boolean[] audioOk = { true };
        final int[] audioHits = new int[2];
        Thread at = new Thread(() -> {
            try {
                for (int i = 0; i < SEGS; i++) {
                    if (!Arrays.equals(playPiece(audio, http, v2.get(i).uri, 0, -1, audioHits), V2[i])) audioOk[0] = false;
                }
            } catch (IOException e) {
                audioOk[0] = false;
            }
        });
        at.start();
        hits = new int[2];
        for (int i = 0; i < SEGS; i++) {
            yes(Arrays.equals(playPiece(video, http, v1.get(i).uri, 0, -1, hits), V1[i]), "video piece " + i + " with audio");
        }
        at.join(60_000);
        stop[0] = true;
        watch.join();
        yes(audioOk[0], "audio pieces with video");
        yes(maxSlots[0] <= 3, "our connections at once stay three: " + maxSlots[0]);
        yes(hits[0] + audioHits[0] >= SEGS, "both streams still loaded ahead: " + hits[0] + " + " + audioHits[0]);
        sa.close();

        // ---- byte ranges inside one file
        List<ParallelLoad.Ref> br = new ArrayList<>();
        for (int i = 0; i < 10; i++) br.add(new ParallelLoad.Ref(base + "/file.bin", i * 200_000L, 200_000L));
        g3.playlist(base + "/br.m3u8", br);
        ParallelLoad.Segments.Window wb = g3.window();
        hits = new int[2];
        for (int i = 0; i < 10; i++) {
            yes(Arrays.equals(playPiece(wb, http, base + "/file.bin", i * 200_000L, 200_000L, hits),
                    slice(DATA, i * 200_000L, 200_000L)), "byte range " + i);
        }
        yes(hits[0] >= 8, "byte ranges loaded ahead: " + hits[0]);
        Thread.sleep(300);
        playPiece(wb, http, base + "/file.bin", 0, 200_000L, hits);
        Thread.sleep(300);
        yes(Arrays.equals(playPiece(wb, http, base + "/file.bin", 201_000L, 199_000L, hits),
                slice(DATA, 201_000L, 199_000L)), "inside a byte range");
        wb.release();
        s3.close();

        // ---- a «too many connections» page (200, text/html)
        Memory mem = new Memory();
        ParallelLoad.Session sp = new ParallelLoad.Session(3, mem);
        ParallelLoad.Segments gp = new ParallelLoad.Segments(sp, http);
        List<ParallelLoad.Ref> pg = new ArrayList<>();
        for (int i = 0; i < 12; i++) pg.add(new ParallelLoad.Ref(base + "/page/seg" + i + ".ts", 0, -1));
        gp.playlist(base + "/page.m3u8", pg);
        ParallelLoad.Segments.Window wp = gp.window();
        hits = new int[2];
        for (int i = 0; i < 12; i++) {
            yes(Arrays.equals(playPiece(wp, http, pg.get(i).uri, 0, -1, hits), V1[i]), "page server piece " + i);
        }
        yes(mem.refused("127.0.0.1"), "the page counts as a refusal");
        eq(wp.ahead(), 0, "then nothing ahead for it");
        sp.close();
    }

    static void closing(String base) throws Exception {
        Http http = new Http();
        final ParallelLoad.Session s = new ParallelLoad.Session(3, new Memory());
        final ParallelLoad.Parts p = new ParallelLoad.Parts(s, http);
        final ParallelLoad.Parts.Reader r = p.open(base + "/slow.bin", 0);
        yes(r != null, "a slow file");
        final IOException[] err = new IOException[1];
        final int[] count = new int[1];
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int k;
                while ((k = r.read(buf, 0, buf.length)) >= 0) count[0] += k;
            } catch (IOException e) {
                err[0] = e;
            }
        });
        t.start();
        Thread.sleep(800);
        long t0 = System.nanoTime();
        s.close();   // the player is released
        t.join(10_000);
        yes(!t.isAlive(), "the reader did not stay waiting");
        yes(err[0] != null, "it got an error, not a false end: " + count[0]);
        yes((System.nanoTime() - t0) / 1_000_000 < 9_000, "quickly");

        // a reader waiting on a piece is freed by the player's interrupt
        ParallelLoad.Piece piece = new ParallelLoad.Piece(0, 100);
        final IOException[] e2 = new IOException[1];
        Thread w = new Thread(() -> {
            try { piece.read(0, new byte[10], 0, 10); } catch (IOException e) { e2[0] = e; }
        });
        w.start();
        Thread.sleep(300);
        w.interrupt();
        w.join(3000);
        yes(e2[0] instanceof InterruptedIOException && !(e2[0] instanceof ParallelLoad.PartFailed), "interrupted, not «failed»");
        // a dropped piece says «failed» (read it another way)
        ParallelLoad.Piece q = new ParallelLoad.Piece(0, 100);
        q.append(new byte[] { 1, 2, 3 }, 0, 3);
        q.cancel();
        byte[] b3 = new byte[10];
        eq(q.read(0, b3, 0, 10), 3, "what came stays readable");
        try {
            q.read(3, b3, 0, 10);
            yes(false, "a dropped piece");
        } catch (ParallelLoad.PartFailed e) {
            yes(true, "a dropped piece → «failed»");
        }
    }
}
