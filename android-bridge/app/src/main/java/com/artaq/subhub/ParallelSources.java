package com.artaq.subhub;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 322.3.128: the player's side of ParallelLoad — Media3 data sources around the player's own
 * (same headers, cookies, redirects), so the pieces loaded ahead are requested exactly as the
 * player would request them, and measured as network by the player's speed meter.
 *
 *  - hls(…): playlists pass through and are read for their pieces; each stream's pieces load
 *    ahead (ParallelLoad.Segments); keys and the first part of the video pass through.
 *  - files(…): a whole file (MKV / MP4 taken from the page) in Range parts
 *    (ParallelLoad.Parts); the plain way when the server takes no ranges.
 *  - Counter: the bytes from the network and the connections open now (the owner's speed line,
 *    and «the video still loads» for the last server's longer wait).
 */
@UnstableApi
final class ParallelSources {
    private ParallelSources() {}

    static HlsDataSourceFactory hls(DataSource.Factory base, ParallelLoad.Session session) {
        return new Hls(base, session);
    }

    static DataSource.Factory files(DataSource.Factory base, ParallelLoad.Session session) {
        return new Files(base, session);
    }

    // ------------------------------------------------------------------ counting

    /** Bytes from the network and connections open now (all the player's requests). */
    static final class Counter implements TransferListener {
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger open = new AtomicInteger();

        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {}

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
            if (isNetwork) open.incrementAndGet();
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
            if (isNetwork) bytes.addAndGet(bytesTransferred);
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
            if (isNetwork && open.decrementAndGet() < 0) open.set(0);
        }
    }

    // ------------------------------------------------------------------ requests

    /** ParallelLoad's requests, made with the player's own data sources. */
    static final class Upstream implements ParallelLoad.Fetcher {
        private final DataSource.Factory factory;
        private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();

        Upstream(DataSource.Factory factory) {
            this.factory = factory;
        }

        /** The player's speed meter: our connections count as network for it too. */
        void listen(TransferListener l) {
            if (l != null && !listeners.contains(l)) listeners.add(l);
        }

        DataSource create() {
            DataSource d = factory.createDataSource();
            for (TransferListener l : listeners) d.addTransferListener(l);
            return d;
        }

        @Override
        public ParallelLoad.Response segment(String uri, long position, long length) throws IOException {
            return open(new DataSpec.Builder()
                    .setUri(Uri.parse(uri))
                    .setPosition(position)
                    .setLength(length < 0 ? C.LENGTH_UNSET : length)
                    .build());
        }

        @Override
        public ParallelLoad.Response range(String uri, long start, long end) throws IOException {
            // Our own Range header (position 0, length unset): a server that ignores it answers
            // 200 from the start, and the data source does not skip anything to reach `start`.
            Map<String, String> h = new HashMap<>();
            h.put("Range", "bytes=" + start + "-" + (end >= 0 ? String.valueOf(end) : ""));
            return open(new DataSpec.Builder()
                    .setUri(Uri.parse(uri))
                    .setHttpRequestHeaders(h)
                    .build());
        }

        private ParallelLoad.Response open(DataSpec spec) throws IOException {
            DataSource d = create();
            try {
                d.open(spec);
            } catch (HttpDataSource.InvalidResponseCodeException e) {
                quietly(d);
                throw new ParallelLoad.HttpStatus(e.responseCode, "");
            } catch (IOException | RuntimeException e) {
                quietly(d);
                throw e;
            }
            return new Answer(d);
        }
    }

    static final class Answer implements ParallelLoad.Response {
        private final DataSource d;
        private final Map<String, List<String>> headers;

        Answer(DataSource d) {
            this.d = d;
            this.headers = copy(d.getResponseHeaders());
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = d.read(buffer, offset, length);
            return n == C.RESULT_END_OF_INPUT ? -1 : n;
        }

        @Override
        public String header(String name) {
            return ParallelLoad.header(headers, name);
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public void close() {
            quietly(d);
        }
    }

    static Map<String, List<String>> copy(Map<String, List<String>> h) {
        TreeMap<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (h != null) {
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static void quietly(DataSource d) {
        if (d == null) return;
        try { d.close(); } catch (IOException | RuntimeException ignored) {}
    }

    // ------------------------------------------------------------------ HLS

    static final class Hls implements HlsDataSourceFactory {
        private final DataSource.Factory base;
        private final Upstream upstream;
        private final ParallelLoad.Segments segments;

        Hls(DataSource.Factory base, ParallelLoad.Session session) {
            this.base = base;
            this.upstream = new Upstream(base);
            this.segments = new ParallelLoad.Segments(session, upstream);
        }

        @Override
        public DataSource createDataSource(int dataType) {
            if (dataType == C.DATA_TYPE_MANIFEST) return new Tap(base.createDataSource(), segments);
            if (dataType == C.DATA_TYPE_MEDIA) return new Media(upstream, segments.window());
            return base.createDataSource();
        }
    }

    /** A playlist passing through to the player: its pieces are noted (a media playlist only). */
    static final class Tap implements DataSource {
        private static final int MAX = 8 * 1024 * 1024;
        private final DataSource inner;
        private final ParallelLoad.Segments segments;
        private ByteArrayOutputStream got;
        private Uri base;

        Tap(DataSource inner, ParallelLoad.Segments segments) {
            this.inner = inner;
            this.segments = segments;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            inner.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            got = new ByteArrayOutputStream();
            long n = inner.open(dataSpec);
            // the address the player parses the playlist with (after redirects)
            Uri u = inner.getUri();
            base = u != null ? u : dataSpec.uri;
            return n;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = inner.read(buffer, offset, length);
            ByteArrayOutputStream g = got;
            if (n > 0 && g != null) {
                if (g.size() + n > MAX) got = null;
                else g.write(buffer, offset, n);
            }
            return n;
        }

        @Override
        public Uri getUri() {
            return inner.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return inner.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            ByteArrayOutputStream g = got;
            Uri u = base;
            got = null;
            base = null;
            try {
                inner.close();
            } finally {
                if (g != null && u != null) learn(u, g.toByteArray());
            }
        }

        private void learn(Uri u, byte[] bytes) {
            try {
                if (!new String(bytes, 0, Math.min(bytes.length, 65536), StandardCharsets.UTF_8).contains("#EXTINF")) return;
                HlsPlaylist p = new HlsPlaylistParser().parse(u, new ByteArrayInputStream(bytes));
                if (!(p instanceof HlsMediaPlaylist)) return;
                HlsMediaPlaylist m = (HlsMediaPlaylist) p;
                List<ParallelLoad.Ref> refs = new ArrayList<>(m.segments.size());
                for (HlsMediaPlaylist.Segment s : m.segments) {
                    // the same address the player computes for the piece (HlsMediaChunk)
                    refs.add(new ParallelLoad.Ref(UriUtil.resolveToUri(m.baseUri, s.url).toString(),
                            s.byteRangeOffset, s.byteRangeLength));
                }
                segments.playlist(u.toString(), refs);
            } catch (Exception ignored) {
                // not understood: the player loads as before
            }
        }
    }

    /** The video pieces of one stream: from the piece loaded ahead, else directly as before. */
    static final class Media implements DataSource {
        private final Upstream upstream;
        private final ParallelLoad.Segments.Window window;
        private DataSource direct;
        private ParallelLoad.Hit hit;
        private DataSpec spec;
        private long readSoFar;
        private long left;

        Media(Upstream upstream, ParallelLoad.Segments.Window window) {
            this.upstream = upstream;
            this.window = window;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.listen(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            spec = dataSpec;
            readSoFar = 0;
            left = dataSpec.length;
            ParallelLoad.Hit h = window.open(dataSpec.uri.toString(), dataSpec.position, dataSpec.length);
            if (h != null) {
                hit = h;
                try {
                    h.awaitHeaders();
                } catch (IOException e) {
                    hit = null;
                    window.close(h);
                    throw e;
                }
                if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.length;
                long known = h.knownLength();
                return known >= 0 ? known : C.LENGTH_UNSET;
            }
            DataSource d = upstream.create();
            direct = d;
            long n = d.open(dataSpec);
            window.loadAhead();   // its connection is open: the next pieces now
            return n;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            ParallelLoad.Hit h = hit;
            if (h != null) {
                if (left == 0) return C.RESULT_END_OF_INPUT;
                int max = left == C.LENGTH_UNSET ? length : (int) Math.min(length, left);
                int n;
                try {
                    n = h.read(readSoFar, buffer, offset, max);
                } catch (ParallelLoad.PartFailed e) {
                    toDirect(h);
                    return read(buffer, offset, length);
                }
                if (n < 0) return C.RESULT_END_OF_INPUT;
                readSoFar += n;
                if (left != C.LENGTH_UNSET) left -= n;
                return n;
            }
            DataSource d = direct;
            return d == null ? C.RESULT_END_OF_INPUT : d.read(buffer, offset, length);
        }

        /** The piece loaded ahead failed: the rest of it directly, from where it stopped. */
        private void toDirect(ParallelLoad.Hit h) throws IOException {
            hit = null;
            window.close(h);
            DataSpec s = spec;
            if (s == null) throw new IOException("closed");
            String u = s.uri.toString();
            window.calm(u, h.piece);   // a server that refused extra connections: they close first
            DataSpec rest = readSoFar == 0 ? s : s.subrange(readSoFar);
            DataSource d = upstream.create();
            direct = d;
            d.open(rest);
        }

        @Override
        public Uri getUri() {
            DataSource d = direct;
            if (d != null && d.getUri() != null) return d.getUri();
            DataSpec s = spec;
            return s == null ? null : s.uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            DataSource d = direct;
            if (d != null) return d.getResponseHeaders();
            ParallelLoad.Hit h = hit;
            return h != null ? h.headers() : Collections.<String, List<String>>emptyMap();
        }

        @Override
        public void close() throws IOException {
            ParallelLoad.Hit h = hit;
            hit = null;
            if (h != null) window.close(h);
            DataSource d = direct;
            direct = null;
            if (d != null) d.close();
        }
    }

    // ------------------------------------------------------------------ whole files

    static final class Files implements DataSource.Factory {
        private final Upstream upstream;
        private final ParallelLoad.Parts parts;

        Files(DataSource.Factory base, ParallelLoad.Session session) {
            this.upstream = new Upstream(base);
            this.parts = new ParallelLoad.Parts(session, upstream);
        }

        @Override
        public DataSource createDataSource() {
            return new FileSource(upstream, parts);
        }
    }

    /** A whole file in parts, or the plain way (no ranges, one connection, not a web address). */
    static final class FileSource implements DataSource {
        private final Upstream upstream;
        private final ParallelLoad.Parts parts;
        private ParallelLoad.Parts.Reader reader;
        private DataSource plain;
        private DataSpec spec;

        FileSource(Upstream upstream, ParallelLoad.Parts parts) {
            this.upstream = upstream;
            this.parts = parts;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.listen(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            spec = dataSpec;
            String scheme = dataSpec.uri.getScheme();
            String sc = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
            if (("http".equals(sc) || "https".equals(sc))
                    && dataSpec.length == C.LENGTH_UNSET
                    && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) {
                ParallelLoad.Parts.Reader r = parts.open(dataSpec.uri.toString(), dataSpec.position);
                if (r != null) {
                    reader = r;
                    long n = r.length();
                    return n < 0 ? C.LENGTH_UNSET : n;
                }
            }
            DataSource d = upstream.create();
            plain = d;
            return d.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            ParallelLoad.Parts.Reader r = reader;
            if (r != null) {
                int n = r.read(buffer, offset, length);
                return n < 0 ? C.RESULT_END_OF_INPUT : n;
            }
            DataSource d = plain;
            return d == null ? C.RESULT_END_OF_INPUT : d.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            DataSource d = plain;
            if (d != null && d.getUri() != null) return d.getUri();
            DataSpec s = spec;
            return s == null ? null : s.uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            DataSource d = plain;
            if (d != null) return d.getResponseHeaders();
            ParallelLoad.Parts.Reader r = reader;
            return r != null ? r.headers() : Collections.<String, List<String>>emptyMap();
        }

        @Override
        public void close() throws IOException {
            ParallelLoad.Parts.Reader r = reader;
            reader = null;
            if (r != null) r.close();
            DataSource d = plain;
            plain = null;
            if (d != null) d.close();
        }
    }
}
