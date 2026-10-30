package com.artaq.subhub;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 322.3.128: a film loads on several connections at once — like download managers (IDM) —
 * for servers that give each connection only part of their speed. The player itself
 * (Media3) loads one piece at a time on one connection.
 *
 *  - HLS (m3u8): while the player reads one piece, the next pieces load at the same time
 *    (N connections in all) and wait in memory until it asks for them.
 *  - A whole file taken from the page (MKV / MP4): the part ahead is cut into ranges
 *    (Range requests), N loading at a time, read in order.
 *
 * Three connections for viewers; the owner can try 1 / 3 / 4 from his speed line. A server
 * that takes no ranges plays on one connection as before; one that refuses the extra
 * connections (403 / 429 / 503 …, or a web page instead of the video) goes back to one
 * connection by itself — the film does not stop for it — and is remembered for a few days.
 *
 * Pure Java (no Android classes): the player hands it a Fetcher made of its own data
 * sources (the same headers, cookies and speed measurement); the CI tests it against a
 * local server with plain javac (tests/ParallelLoadTest.java).
 */
final class ParallelLoad {
    static final int DEFAULT_CONNECTIONS = 3;
    static final int MAX_CONNECTIONS = 4;
    /** A server that refused extra connections plays on one connection for this long. */
    static final long REFUSE_FOR_MS = 3L * 24 * 60 * 60 * 1000;
    /** The first refusal is enough: an extra connection is not worth a stall in the film. */
    static final int REFUSALS_TO_STOP = 1;
    /** Before going on with one connection: the dropped ones close first (the server counts them). */
    static final long SETTLE_MS = 2500L;
    /** HLS pieces loaded ahead and still waiting in memory (one quality / one audio track). */
    static final long MAX_AHEAD_BYTES = 32L * 1024 * 1024;
    /** File parts already read, kept for a jump back (MKV reads its index at the end, then returns). */
    static final long KEEP_DONE_BYTES = 8L * 1024 * 1024;
    /** File parts still loading when the player jumps: kept when near the new place, dropped when far. */
    static final long NEAR_BEHIND = 8L * 1024 * 1024;
    static final long NEAR_AHEAD = 16L * 1024 * 1024;
    static final int BLOCK = 64 * 1024;

    private ParallelLoad() {}

    static int clamp(int n) {
        return Math.max(1, Math.min(MAX_CONNECTIONS, n));
    }

    /** The owner's switch on his speed line: 3 → 4 → 1 → 3. */
    static int nextChoice(int n) {
        int c = clamp(n);
        return c == 3 ? 4 : c == 4 ? 1 : 3;
    }

    /** Answers that mean «not that many at once»: the server refuses the extra connections. */
    static boolean refusal(int code) {
        return code == 403 || code == 405 || code == 409 || code == 429 || code == 503 || code == 509;
    }

    private static final Pattern HOST = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9+.-]*://(?:[^@/?#]*@)?(\\[[^\\]/?#]*\\]|[^:/?#]*)");

    static String hostOf(String uri) {
        if (uri == null) return "";
        Matcher m = HOST.matcher(uri);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "^\\s*bytes\\s+(\\d+)\\s*-\\s*(\\d+)\\s*/\\s*(\\d+|\\*)\\s*$", Pattern.CASE_INSENSITIVE);

    /** «bytes 100-199/1000» → {100, 199, 1000} (-1 for «*»); null when absent or another form. */
    static long[] contentRange(String header) {
        if (header == null) return null;
        Matcher m = CONTENT_RANGE.matcher(header);
        if (!m.matches()) return null;
        try {
            long s = Long.parseLong(m.group(1)), e = Long.parseLong(m.group(2));
            long t = "*".equals(m.group(3)) ? -1L : Long.parseLong(m.group(3));
            return e < s ? null : new long[] { s, e, t };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The parts of a file: small first (the picture starts soon), then larger (fewer requests). */
    static long partSize(int seq) {
        if (seq <= 0) return 512L * 1024;
        if (seq == 1) return 1024L * 1024;
        if (seq == 2) return 2048L * 1024;
        return 4096L * 1024;
    }

    /** A web page where a video piece should be (a server's «too many» page answered with 200). */
    static boolean isPage(String contentType, byte[] head, int n) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) return false;
        for (int i = 0; i < n; i++) {
            byte c = head[i];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            return c == '<';
        }
        return false;
    }

    static String header(Map<String, List<String>> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
            }
        }
        return null;
    }

    /** Waits (a little) until these pieces' connections are closed: a server counting connections. */
    static void awaitSettled(List<Piece> pieces, long maxMs) throws InterruptedIOException {
        long end = System.nanoTime() + maxMs * 1_000_000L;
        for (Piece p : pieces) {
            while (!p.settled() && System.nanoTime() < end) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
        }
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException | RuntimeException ignored) {}
    }

    // ------------------------------------------------------------------ the requests

    /** The player's way to ask the server (its data sources: headers, cookies, measuring). */
    interface Fetcher {
        /** An HLS piece: the same request the player itself makes (byte range by position/length). */
        Response segment(String uri, long position, long length) throws IOException;

        /**
         * A part of a file with our own «Range: bytes=start-end» (end -1 = to the end of the
         * file). Nothing is skipped when the server ignores it: the answer simply has no
         * Content-Range, and we close it.
         */
        Response range(String uri, long start, long end) throws IOException;
    }

    interface Response extends Closeable {
        /** -1 at the end. */
        int read(byte[] buffer, int offset, int length) throws IOException;

        String header(String name);

        Map<String, List<String>> headers();
    }

    /** An answer that is not 2xx. */
    static final class HttpStatus extends IOException {
        final int code;

        HttpStatus(int code, String message) {
            super("HTTP " + code + (message == null || message.isEmpty() ? "" : " " + message));
            this.code = code;
        }
    }

    /** A piece loading on its own connection ended badly (or was dropped): read it another way. */
    static final class PartFailed extends IOException {
        PartFailed(IOException cause) {
            super(cause == null ? "part failed" : String.valueOf(cause.getMessage()), cause);
        }
    }

    /** Servers that refused extra connections lately (kept by the player for a few days). */
    interface Hosts {
        boolean refused(String host);

        void refuse(String host);
    }

    // ------------------------------------------------------------------ one player

    /** One player's loading: its threads, how many connections, the servers that refused. */
    static final class Session {
        private final ThreadPoolExecutor pool;
        private final Hosts hosts;
        private volatile int connections;
        private volatile boolean closed;
        private volatile boolean refusedSeen;
        private final Map<String, Integer> refusals = new HashMap<>();
        private final Set<String> stopped = new HashSet<>();
        private final Set<Piece> live = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final AtomicInteger loading = new AtomicInteger();
        private int slotsUsed = 0;

        Session(int connections, Hosts hosts) {
            this.connections = clamp(connections);
            this.hosts = hosts;
            pool = new ThreadPoolExecutor(MAX_CONNECTIONS + 2, MAX_CONNECTIONS + 2, 20, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "subhub-parallel");
                        t.setDaemon(true);
                        return t;
                    });
            pool.allowCoreThreadTimeOut(true);
        }

        int connections() { return connections; }

        void setConnections(int n) { connections = clamp(n); }

        boolean closed() { return closed; }

        /** More than one connection for this server now? */
        boolean parallelFor(String host) {
            if (closed || connections <= 1) return false;
            synchronized (this) {
                if (stopped.contains(host)) return false;
            }
            boolean off = false;
            if (hosts != null && host != null && !host.isEmpty()) {
                try { off = hosts.refused(host); } catch (RuntimeException ignored) {}
            }
            if (off) refusedSeen = true;
            return !off;
        }

        /** A refusal of an extra connection: after REFUSALS_TO_STOP, one connection for this server. */
        void noteRefusal(String host) {
            boolean stopNow;
            synchronized (this) {
                Integer before = refusals.get(host);
                int n = before == null ? 1 : before + 1;
                refusals.put(host, n);
                stopNow = n >= REFUSALS_TO_STOP && stopped.add(host);
            }
            if (!stopNow) return;
            refusedSeen = true;
            if (hosts != null && host != null && !host.isEmpty()) {
                try { hosts.refuse(host); } catch (RuntimeException ignored) {}
            }
        }

        /** A server of this player refused the extra connections (the owner's speed line says so). */
        boolean refusedSeen() { return refusedSeen; }

        /** Pieces loading right now on our own connections. */
        int loadingParts() { return loading.get(); }

        boolean run(Piece p, Runnable r) {
            if (closed) return false;
            live.add(p);
            try {
                pool.execute(r);
                return true;
            } catch (RuntimeException e) {
                live.remove(p);
                return false;
            }
        }

        void done(Piece p) { live.remove(p); }

        /**
         * Our own connections at once stay within the chosen number (a film with a separate audio
         * track has two streams loading ahead). false: dropped or closed while waiting.
         */
        synchronized boolean enter(Piece p) throws InterruptedIOException {
            while (slotsUsed >= Math.max(1, connections) && !closed && !p.canceled()) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            if (closed || p.canceled()) return false;
            slotsUsed++;
            return true;
        }

        synchronized void leave() {
            if (slotsUsed > 0) slotsUsed--;
            notifyAll();
        }

        /** Connections of ours open now (for the tests). */
        synchronized int slotsUsed() { return slotsUsed; }

        void close() {
            closed = true;
            synchronized (this) { notifyAll(); }   // pieces waiting for a connection give up
            for (Piece p : live.toArray(new Piece[0])) p.cancel();
            live.clear();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ a piece in memory

    /** Bytes loaded by one connection, read by the player while they come. */
    static final class Piece {
        final long start;   // where it starts in the server's file
        final long end;     // exclusive; -1 = until the answer ends
        private final ArrayList<byte[]> blocks = new ArrayList<>();
        private long size;
        private boolean done, gotHeaders;
        private IOException error;
        private volatile boolean canceled, started, settled;
        private Map<String, List<String>> headers = Collections.emptyMap();
        private volatile long lastUse;

        Piece(long start, long end) {
            this.start = start;
            this.end = end;
            lastUse = System.nanoTime();
        }

        long want() { return end < 0 ? -1 : end - start; }

        synchronized void headers(Map<String, List<String>> h) {
            headers = h == null ? Collections.<String, List<String>>emptyMap() : h;
            gotHeaders = true;
            notifyAll();
        }

        synchronized Map<String, List<String>> headers() { return headers; }

        synchronized void append(byte[] b, int off, int len) {
            int put = 0;
            while (put < len) {
                int at = (int) (size % BLOCK);
                if (at == 0) blocks.add(new byte[BLOCK]);
                byte[] last = blocks.get(blocks.size() - 1);
                int n = Math.min(len - put, BLOCK - at);
                System.arraycopy(b, off + put, last, at, n);
                size += n;
                put += n;
            }
            notifyAll();
        }

        synchronized void finish() {
            done = true;
            gotHeaders = true;
            notifyAll();
        }

        synchronized void fail(IOException e) {
            if (!done) {
                error = e == null ? new IOException("failed") : e;
                done = true;
            }
            gotHeaders = true;
            notifyAll();
        }

        void cancel() {
            canceled = true;
            fail(new InterruptedIOException("dropped"));
        }

        boolean canceled() { return canceled; }

        void markStarted() { started = true; }

        boolean started() { return started; }

        /** Its connection is closed (or it never had one). */
        void markSettled() { settled = true; }

        boolean settled() { return settled || !started; }

        synchronized long size() { return size; }

        synchronized boolean done() { return done; }

        synchronized boolean failed() { return error != null; }

        synchronized boolean complete() { return done && error == null; }

        long lastUse() { return lastUse; }

        /** Waits until the answer's headers came (or the piece ended). */
        synchronized void awaitHeaders() throws IOException {
            while (!gotHeaders && !done) waitABit();
        }

        /** Bytes from `at` (counted from the piece's start); waits for them; -1 at its end. */
        synchronized int read(long at, byte[] b, int off, int len) throws IOException {
            if (len <= 0) return 0;
            while (true) {
                if (at < size) {
                    int n = (int) Math.min(len, size - at);
                    int copied = 0;
                    while (copied < n) {
                        long p = at + copied;
                        byte[] blk = blocks.get((int) (p / BLOCK));
                        int bo = (int) (p % BLOCK);
                        int c = Math.min(n - copied, BLOCK - bo);
                        System.arraycopy(blk, bo, b, off + copied, c);
                        copied += c;
                    }
                    lastUse = System.nanoTime();
                    return n;
                }
                if (done) {
                    if (error != null) throw new PartFailed(error);
                    return -1;
                }
                waitABit();
            }
        }

        private void waitABit() throws InterruptedIOException {
            try {
                wait(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }
    }

    // ------------------------------------------------------------------ HLS

    /** One piece of an HLS playlist: its address and byte range (length -1 = the whole answer). */
    static final class Ref {
        final String uri;
        final long offset;
        final long length;

        Ref(String uri, long offset, long length) {
            this.uri = uri;
            this.offset = Math.max(0L, offset);
            this.length = length;
        }
    }

    /** What the player gets for a request: a piece loaded ahead, and where the request starts in it. */
    static final class Hit {
        final Piece piece;
        final long at;

        Hit(Piece piece, long at) {
            this.piece = piece;
            this.at = at;
        }

        int read(long readSoFar, byte[] b, int off, int len) throws IOException {
            return piece.read(at + readSoFar, b, off, len);
        }

        /** Bytes from the request's start when the piece is complete; -1 while it loads. */
        long knownLength() {
            return piece.complete() ? Math.max(0L, piece.size() - at) : -1L;
        }

        Map<String, List<String>> headers() { return piece.headers(); }

        void awaitHeaders() throws IOException { piece.awaitHeaders(); }
    }

    /** The pieces of the HLS playlists the player loaded, and the loading ahead. */
    static final class Segments {
        private final Session session;
        private final Fetcher fetcher;
        private final Map<String, List<Ref>> lists = new HashMap<>();
        private final Map<String, List<Spot>> spots = new HashMap<>();

        private static final class Spot {
            final List<Ref> list;
            final int index;

            Spot(List<Ref> list, int index) {
                this.list = list;
                this.index = index;
            }
        }

        Segments(Session session, Fetcher fetcher) {
            this.session = session;
            this.fetcher = fetcher;
        }

        /** A media playlist the player loaded (its pieces in order, addresses already resolved). */
        synchronized void playlist(String playlistUri, List<Ref> refs) {
            if (playlistUri == null || refs == null) return;
            List<Ref> old = lists.get(playlistUri);
            if (old != null && same(old, refs)) return;   // loaded again unchanged: what loads ahead stays
            List<Ref> list = Collections.unmodifiableList(new ArrayList<>(refs));
            lists.put(playlistUri, list);
            if (old != null) {
                for (Ref r : old) {
                    List<Spot> s = spots.get(r.uri);
                    if (s == null) continue;
                    for (Iterator<Spot> it = s.iterator(); it.hasNext();) if (it.next().list == old) it.remove();
                    if (s.isEmpty()) spots.remove(r.uri);
                }
            }
            for (int i = 0; i < list.size(); i++) {
                Ref r = list.get(i);
                List<Spot> s = spots.get(r.uri);
                if (s == null) spots.put(r.uri, s = new ArrayList<>(1));
                s.add(new Spot(list, i));
            }
        }

        private static boolean same(List<Ref> a, List<Ref> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                Ref x = a.get(i), y = b.get(i);
                if (!x.uri.equals(y.uri) || x.offset != y.offset || x.length != y.length) return false;
            }
            return true;
        }

        private Spot find(String uri, long position) {
            List<Spot> s = spots.get(uri);
            if (s == null) return null;
            for (Spot x : s) {
                Ref r = x.list.get(x.index);
                if (r.length < 0 ? position >= r.offset : position >= r.offset && position < r.offset + r.length) return x;
            }
            return null;
        }

        /** One stream of the player (one quality at a time, or the audio track): its loading ahead. */
        Window window() { return new Window(); }

        final class Window {
            private List<Ref> list;
            private final TreeMap<Integer, Piece> ahead = new TreeMap<>();
            private int waiting = -1;   // the piece the player reads itself: loading ahead after it connects

            /**
             * The player asks for [uri, position, length]: the piece loaded ahead for it — the next
             * pieces start loading now; or null: the player reads it directly as before, and calls
             * loadAhead() once its own connection is open (it stays the server's first connection).
             */
            Hit open(String uri, long position, long length) throws InterruptedIOException {
                List<Piece> gone;
                synchronized (Segments.this) {
                    Spot s = find(uri, position);
                    if (s == null) return null;   // a key, the first part of the video, an unknown list
                    Ref here = s.list.get(s.index);
                    if (session.connections() > 1 && !session.parallelFor(hostOf(here.uri))) {
                        // the server refused extra connections: what loads ahead goes, and the
                        // player's own request waits until their connections are closed
                        gone = new ArrayList<>(ahead.values());
                        dropAll();
                        list = s.list;
                    } else {
                        gone = null;
                    }
                    if (gone == null) return openHere(s, position);
                }
                awaitSettled(gone, SETTLE_MS);
                return null;
            }

            /** (under the lock) */
            private Hit openHere(Spot s, long position) {
                if (list != s.list) {        // another quality: what was loaded for the old one goes
                    dropAll();
                    list = s.list;
                }
                int n = session.connections();
                Piece p = ahead.remove(s.index);
                for (Iterator<Map.Entry<Integer, Piece>> it = ahead.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<Integer, Piece> e = it.next();
                    int k = e.getKey();
                    if (k < s.index || k > s.index + n - 1) {   // behind (a jump back) or too far
                        e.getValue().cancel();
                        it.remove();
                    }
                }
                Hit hit = null;
                if (p != null) {
                    // still waiting for a free connection, or failed: the player reads it itself
                    if (!p.started() || p.failed()) p.cancel();
                    else hit = new Hit(p, position - s.list.get(s.index).offset);
                }
                if (hit != null) {
                    waiting = -1;
                    schedule(s.index);
                } else {
                    waiting = s.index;
                }
                return hit;
            }

            /** The player's own request for a piece is connected: the next pieces start loading. */
            void loadAhead() {
                synchronized (Segments.this) {
                    if (list != null && waiting >= 0) schedule(waiting);
                    waiting = -1;
                }
            }

            /**
             * A piece loaded ahead failed and the player reads it itself now: when the server
             * refused extra connections, those (and the failed one) close first.
             */
            void calm(String uri, Piece failed) throws InterruptedIOException {
                List<Piece> gone = new ArrayList<>();
                if (failed != null) gone.add(failed);
                synchronized (Segments.this) {
                    if (session.connections() > 1 && !session.parallelFor(hostOf(uri))) {
                        gone.addAll(ahead.values());
                        dropAll();
                    }
                }
                awaitSettled(gone, SETTLE_MS);
            }

            /** The player is done with this request's piece. */
            void close(Hit hit) {
                if (hit != null) hit.piece.cancel();   // a finished piece stays finished; a partial one stops
            }

            void release() {
                synchronized (Segments.this) {
                    dropAll();
                    list = null;
                    waiting = -1;
                }
            }

            /** Pieces of this stream loading or waiting (for the tests). */
            int ahead() {
                synchronized (Segments.this) { return ahead.size(); }
            }

            private void dropAll() {
                for (Piece p : ahead.values()) p.cancel();
                ahead.clear();
            }

            private void schedule(int current) {
                if (list == null) return;
                int n = session.connections();
                for (int j = current + 1; j < list.size() && j <= current + n - 1; j++) {
                    if (ahead.containsKey(j)) continue;
                    final Ref r = list.get(j);
                    if (!session.parallelFor(hostOf(r.uri))) break;
                    if (aheadBytes() >= MAX_AHEAD_BYTES) break;
                    final Piece p = new Piece(r.offset, r.length < 0 ? -1 : r.offset + r.length);
                    ahead.put(j, p);
                    if (!session.run(p, () -> fillSegment(p, r))) {
                        p.cancel();
                        p.markSettled();
                    }
                }
            }

            private long aheadBytes() {
                long t = 0;
                for (Piece p : ahead.values()) t += p.size();
                return t;
            }
        }

        private void fillSegment(Piece p, Ref r) {
            Response res = null;
            boolean slot = false;
            try {
                if (p.canceled() || session.closed()) {
                    p.cancel();
                    return;
                }
                try {
                    slot = session.enter(p);
                } catch (InterruptedIOException e) {
                    slot = false;
                }
                if (!slot) {
                    p.cancel();
                    return;
                }
                p.markStarted();
                session.loading.incrementAndGet();
                try {
                    res = fetcher.segment(r.uri, r.offset, r.length);
                    p.headers(res.headers());
                    byte[] buf = new byte[BLOCK];
                    boolean first = true;
                    while (!p.canceled() && !session.closed()) {
                        int n = res.read(buf, 0, buf.length);
                        if (n < 0) break;
                        if (first && n > 0) {
                            first = false;
                            if (isPage(res.header("Content-Type"), buf, n)) throw new HttpStatus(403, "a page instead of the video");
                        }
                        p.append(buf, 0, n);
                    }
                    if (p.canceled() || session.closed()) p.cancel();
                    else p.finish();
                } catch (HttpStatus e) {
                    if (refusal(e.code)) session.noteRefusal(hostOf(r.uri));   // first: the reader wakes on the failure
                    p.fail(e);
                } catch (IOException e) {
                    p.fail(e);
                } catch (RuntimeException e) {
                    p.fail(new IOException(e));
                } finally {
                    session.loading.decrementAndGet();
                }
            } finally {
                closeQuietly(res);
                if (slot) session.leave();
                p.markSettled();
                session.done(p);
            }
        }
    }

    // ------------------------------------------------------------------ a whole file

    /** A file in parts (Range requests), N loading at a time, read in order. */
    static final class Parts {
        private final Session session;
        private final Fetcher fetcher;
        private final Map<String, Boolean> ranged = new HashMap<>();
        private final Map<String, Long> totals = new HashMap<>();
        private final Map<String, Map<String, List<String>>> firstHeaders = new HashMap<>();
        private final Map<String, TreeMap<Long, Piece>> store = new HashMap<>();
        private final List<Piece> orphans = new ArrayList<>();

        Parts(Session session, Fetcher fetcher) {
            this.session = session;
            this.fetcher = fetcher;
        }

        /**
         * Opens the file at `position` in parts. null → the plain way (one connection as before):
         * one connection chosen, a server that refused, or one that takes no ranges.
         */
        Reader open(String uri, long position) throws IOException {
            String host = hostOf(uri);
            Boolean known;
            synchronized (this) { known = ranged.get(uri); }
            if (Boolean.FALSE.equals(known) || !session.parallelFor(host)) return null;
            Response head = null;
            if (known == null) {
                // The first time: the rest of the file from here with our own Range. A server
                // without ranges answers the whole file (no Content-Range): closed at once.
                try {
                    head = fetcher.range(uri, position, -1);
                } catch (IOException e) {
                    return null;   // the plain way meets the real answer (and its message)
                }
                long[] cr = contentRange(head.header("Content-Range"));
                if (cr == null || cr[0] != position) {
                    synchronized (this) { ranged.put(uri, false); }
                    if (cr == null && position == 0) {
                        // the whole file from its start is just what was asked: read it on this connection
                        return new Reader(uri, host, position, head, true);
                    }
                    closeQuietly(head);
                    return null;
                }
                synchronized (this) {
                    ranged.put(uri, true);
                    if (cr[2] >= 0) totals.put(uri, cr[2]);
                    firstHeaders.put(uri, head.headers());
                }
            }
            return new Reader(uri, host, position, head, false);
        }

        synchronized long total(String uri) {
            Long t = totals.get(uri);
            return t == null ? -1L : t;
        }

        private synchronized void setTotal(String uri, long t) {
            if (t >= 0) totals.put(uri, t);
        }

        private synchronized void endAt(String uri, long t) {
            Long old = totals.get(uri);
            if (old == null || old < 0 || t < old) totals.put(uri, t);
        }

        private TreeMap<Long, Piece> storeOf(String uri) {
            TreeMap<Long, Piece> s = store.get(uri);
            if (s == null) store.put(uri, s = new TreeMap<>());
            return s;
        }

        /** Parts of a file held in memory now (for the tests). */
        synchronized int stored(String uri) {
            TreeMap<Long, Piece> s = store.get(uri);
            return s == null ? 0 : s.size();
        }

        /** A usable part holding `at`. */
        private Piece covering(TreeMap<Long, Piece> st, long at) {
            Map.Entry<Long, Piece> e = st.floorEntry(at);
            if (e == null) return null;
            Piece p = e.getValue();
            if (p.failed() || p.canceled()) {
                st.remove(e.getKey());
                return null;
            }
            return at < p.end ? p : null;
        }

        private void fillPart(String uri, Piece p, Response head) {
            Response res = head;
            boolean slot = false;
            try {
                if (p.canceled() || session.closed()) {
                    p.cancel();
                    return;
                }
                if (head == null) {
                    try {
                        slot = session.enter(p);
                    } catch (InterruptedIOException e) {
                        slot = false;
                    }
                    if (!slot) {
                        p.cancel();
                        return;
                    }
                }
                p.markStarted();
                session.loading.incrementAndGet();
                try {
                    if (res == null) {
                        res = fetcher.range(uri, p.start, p.end - 1);
                        long[] cr = contentRange(res.header("Content-Range"));
                        // ranges on the first connection, none on an extra one (often its «too
                        // many» page): a refusal
                        if (cr == null || cr[0] != p.start) throw new HttpStatus(429, "no range on an extra connection");
                        if (cr[2] >= 0) setTotal(uri, cr[2]);
                    }
                    p.headers(res.headers());
                    byte[] buf = new byte[BLOCK];
                    long want = p.want();
                    while (!p.canceled() && !session.closed() && p.size() < want) {
                        int n = res.read(buf, 0, (int) Math.min(buf.length, want - p.size()));
                        if (n < 0) break;
                        p.append(buf, 0, n);
                    }
                    if (p.canceled() || session.closed()) {
                        p.cancel();
                    } else if (p.size() < want) {
                        long t = total(uri);
                        if (t >= 0 && p.start + p.size() < t) throw new IOException("cut short");
                        endAt(uri, p.start + p.size());   // the file is shorter than said: it ends here
                        p.finish();
                    } else {
                        p.finish();
                    }
                } catch (HttpStatus e) {
                    if (e.code == 416) {   // past the end of the file
                        endAt(uri, p.start);
                        p.finish();
                    } else {
                        if (refusal(e.code)) session.noteRefusal(hostOf(uri));   // first: the reader wakes on the failure
                        p.fail(e);
                    }
                } catch (IOException e) {
                    p.fail(e);
                } catch (RuntimeException e) {
                    p.fail(new IOException(e));
                } finally {
                    session.loading.decrementAndGet();
                }
            } finally {
                closeQuietly(res);
                if (slot) session.leave();
                p.markSettled();
                session.done(p);
            }
        }

        /** The player reading the file from one place onward (until it jumps: then a new reader). */
        final class Reader {
            private final String uri, host;
            private final long from;
            private long pos;
            private final ArrayList<Piece> window = new ArrayList<>();
            private int seq;
            private Response single;           // one connection, no parts
            private boolean singleByChoice;    // the owner chose one connection: parts again when he chooses more
            private boolean closed;

            Reader(String uri, String host, long position, Response head, boolean wholeFile) {
                this.uri = uri;
                this.host = host;
                this.from = position;
                this.pos = position;
                if (wholeFile) {
                    single = head;
                    synchronized (Parts.this) {
                        long len;
                        try { len = Long.parseLong(String.valueOf(head.header("Content-Length")).trim()); }
                        catch (NumberFormatException e) { len = -1L; }
                        if (len >= 0) totals.put(uri, len);
                        firstHeaders.put(uri, head.headers());
                    }
                    return;
                }
                synchronized (Parts.this) {
                    TreeMap<Long, Piece> st = storeOf(uri);
                    // parts still loading from before the jump: kept when near, dropped when far
                    for (Piece o : orphans) {
                        if (o.done() || (o.start >= pos - NEAR_BEHIND && o.start <= pos + NEAR_AHEAD)) continue;
                        o.cancel();
                        if (st.get(o.start) == o) st.remove(o.start);
                    }
                    orphans.clear();
                    if (head != null) {
                        long t = total(uri);
                        long end = position + partSize(seq++);
                        if (t >= 0) end = Math.min(end, t);
                        Map.Entry<Long, Piece> after = st.higherEntry(position);
                        if (after != null && after.getKey() < end) end = after.getKey();
                        if (end <= position) {
                            closeQuietly(head);
                        } else {
                            final Piece p = new Piece(position, end);
                            st.put(position, p);
                            window.add(p);
                            final Response h = head;
                            if (!session.run(p, () -> fillPart(uri, p, h))) {
                                p.cancel();
                                p.markSettled();
                                closeQuietly(h);
                            }
                        }
                    }
                    schedule();
                }
            }

            /** Bytes from here to the end of the file; -1 when the server did not say. */
            long length() {
                long t = total(uri);
                return t < 0 ? -1L : Math.max(0L, t - from);
            }

            Map<String, List<String>> headers() {
                synchronized (Parts.this) {
                    Map<String, List<String>> h = firstHeaders.get(uri);
                    return h == null ? Collections.<String, List<String>>emptyMap() : h;
                }
            }

            /** On one connection now (no parts)? */
            boolean single() { return single != null; }

            int read(byte[] b, int off, int len) throws IOException {
                if (len <= 0) return 0;
                while (true) {
                    if (closed || session.closed()) throw new IOException("closed");
                    long t = total(uri);
                    if (t >= 0 && pos >= t) return -1;
                    if (single != null) {
                        if (singleByChoice && session.parallelFor(host)) {   // the owner chose more again
                            closeQuietly(single);
                            single = null;
                            singleByChoice = false;
                            synchronized (Parts.this) { schedule(); }
                            continue;
                        }
                        int n = single.read(b, off, len);
                        if (n > 0) pos += n;
                        return n;
                    }
                    Piece p;
                    synchronized (Parts.this) {
                        while (!window.isEmpty() && window.get(0).end <= pos) window.remove(0);
                        if (window.isEmpty() && session.parallelFor(host)) schedule();
                        p = window.isEmpty() ? null : window.get(0);
                    }
                    if (p == null || pos < p.start) {
                        // one connection chosen, or the server refused: the rest on one connection
                        goSingle(session.connections() <= 1, null);
                        continue;
                    }
                    int max = (int) Math.min(len, p.end - pos);
                    int n;
                    try {
                        n = p.read(pos - p.start, b, off, max);
                    } catch (PartFailed e) {
                        goSingle(false, e);
                        continue;
                    }
                    if (n < 0) {   // the part ended early: the file is shorter (its end is set), or a gap
                        synchronized (Parts.this) {
                            window.remove(p);
                            if (total(uri) < 0 || pos < total(uri)) {
                                Piece q = storeOf(uri).get(p.start);
                                if (q == p) storeOf(uri).remove(p.start);
                            }
                        }
                        long t2 = total(uri);
                        if (t2 >= 0 && pos >= t2) return -1;
                        goSingle(false, new IOException("part ended early"));
                        continue;
                    }
                    pos += n;
                    if (pos >= p.end) {
                        synchronized (Parts.this) {
                            window.remove(p);
                            schedule();
                        }
                    }
                    return n;
                }
            }

            /** The rest from `pos` on one connection (a server that takes ranges: open-ended range). */
            private void goSingle(boolean byChoice, IOException cause) throws IOException {
                List<Piece> gone = new ArrayList<>();
                synchronized (Parts.this) {
                    TreeMap<Long, Piece> st = storeOf(uri);
                    for (Piece p : window) {
                        if (p.done()) continue;
                        p.cancel();
                        gone.add(p);
                        if (st.get(p.start) == p) st.remove(p.start);
                    }
                    window.clear();
                }
                // a server counting connections: the dropped ones close first
                awaitSettled(gone, SETTLE_MS);
                if (session.closed()) throw new IOException("closed");
                Response r = null;
                for (int attempt = 0; r == null; attempt++) {
                    try {
                        r = fetcher.range(uri, pos, -1);
                    } catch (HttpStatus e) {
                        if (!refusal(e.code)) throw e;
                        session.noteRefusal(host);
                        if (attempt >= 2) throw e;
                        try {
                            Thread.sleep(400L * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException();
                        }
                    }
                }
                long[] cr = contentRange(r.header("Content-Range"));
                if (cr == null || cr[0] != pos) {
                    closeQuietly(r);
                    synchronized (Parts.this) { ranged.put(uri, false); }   // the player's retry goes the plain way
                    throw cause != null ? cause : new IOException("the server stopped giving ranges");
                }
                single = r;
                singleByChoice = byChoice;
            }

            /** Keeps up to N parts loading from the current place on (fewer near the end). */
            private void schedule() {
                if (single != null || closed) return;
                TreeMap<Long, Piece> st = storeOf(uri);
                // one connection now (chosen, or the server refused): no new parts — the reader
                // finishes those already loading, then goes on with one connection
                int n = session.parallelFor(host) ? Math.max(1, session.connections()) : 0;
                long t = total(uri);
                if (window.isEmpty()) {
                    Piece ex = covering(st, pos);
                    if (ex != null) window.add(ex);
                }
                long next = window.isEmpty() ? pos : window.get(window.size() - 1).end;
                while (window.size() < n && (t < 0 || next < t)) {
                    Piece ex = covering(st, next);
                    if (ex != null) {
                        if (!window.contains(ex)) window.add(ex);
                        next = ex.end;
                        continue;
                    }
                    long end = next + partSize(seq++);
                    if (t >= 0) end = Math.min(end, t);
                    Map.Entry<Long, Piece> after = st.higherEntry(next);
                    if (after != null && after.getKey() < end) end = after.getKey();
                    if (end <= next) break;
                    final Piece p = new Piece(next, end);
                    st.put(next, p);
                    window.add(p);
                    if (!session.run(p, () -> fillPart(uri, p, null))) {
                        p.cancel();
                        p.markSettled();
                    }
                    next = end;
                }
                trim(st);
            }

            /** Parts read and not in the window: the latest used ones are kept (KEEP_DONE_BYTES). */
            private void trim(TreeMap<Long, Piece> st) {
                List<Piece> old = new ArrayList<>();
                for (Iterator<Piece> it = st.values().iterator(); it.hasNext();) {
                    Piece p = it.next();
                    if (window.contains(p)) continue;
                    if (p.failed() || p.canceled()) { it.remove(); continue; }
                    if (p.done()) old.add(p);
                }
                old.sort((a, c) -> Long.compare(c.lastUse(), a.lastUse()));
                long kept = 0;
                for (Piece p : old) {
                    kept += p.size();
                    if (kept > KEEP_DONE_BYTES) st.remove(p.start);
                }
            }

            void close() {
                if (closed) return;
                closed = true;
                closeQuietly(single);
                single = null;
                synchronized (Parts.this) {
                    for (Piece p : window) if (!p.done()) orphans.add(p);
                    window.clear();
                }
            }
        }
    }
}
