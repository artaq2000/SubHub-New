package com.artaq.subhub;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict, network-free parser for simple unencrypted HLS media playlists.
 * Parsing does not imply segments can be concatenated into an MP4 container.
 */
public final class HlsMediaPlaylist {
    public final List<URI> segments;
    public final boolean transportStream;
    public final boolean ended;
    private HlsMediaPlaylist(List<URI> segments, boolean ts, boolean ended) {
        this.segments = Collections.unmodifiableList(segments);
        this.transportStream = ts;
        this.ended = ended;
    }
    public static HlsMediaPlaylist parse(URI playlistUri, String body) {
        if (playlistUri == null || !"https".equalsIgnoreCase(playlistUri.getScheme())
                && !"http".equalsIgnoreCase(playlistUri.getScheme()))
            throw new IllegalArgumentException("HTTP(S) playlist URL required");
        if (body == null || !body.trim().startsWith("#EXTM3U"))
            throw new IllegalArgumentException("Not an HLS playlist");
        List<URI> parts = new ArrayList<>();
        boolean end = false;
        boolean ts = true;
        boolean segmentExpected = false;
        boolean segmentListStarted = false;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA:")
                    || line.startsWith("#EXT-X-I-FRAME-STREAM-INF"))
                throw new IllegalArgumentException("Master playlist requires variant selection");
            if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY"))
                throw new IllegalArgumentException("Encrypted playlist unsupported");
            if (line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")
                    || line.startsWith("#EXT-X-DISCONTINUITY"))
                throw new IllegalArgumentException("Playlist requires container-aware processing");
            if (line.startsWith("#EXT-X-ENDLIST")) end = true;
            if (line.startsWith("#EXTINF:")) {
                if (segmentExpected) throw new IllegalArgumentException("Missing segment URI");
                segmentExpected = true;
                segmentListStarted = true;
            }
            if (line.startsWith("#")) continue;
            if (!segmentExpected) throw new IllegalArgumentException("Unexpected playlist URI");
            URI resolved = playlistUri.resolve(line);
            if (!"https".equalsIgnoreCase(resolved.getScheme()) && !"http".equalsIgnoreCase(resolved.getScheme()))
                throw new IllegalArgumentException("Unsupported segment URL");
            if (parts.size() >= 100000) throw new IllegalArgumentException("Too many HLS segments");
            parts.add(resolved);
            if (!resolved.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".ts")) ts = false;
            segmentExpected = false;
        }
        if (!segmentListStarted || parts.isEmpty() || segmentExpected) throw new IllegalArgumentException("No complete media segments");
        return new HlsMediaPlaylist(parts, ts, end);
    }
}
