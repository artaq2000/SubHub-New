package com.artaq.subhub;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses HLS master playlist variants without guessing which qualities exist. */
public final class HlsMasterPlaylist {
    public static final class Variant {
        public final URI playlistUrl;
        public final long bandwidth;
        public final int width;
        public final int height;
        Variant(URI url, long bandwidth, int width, int height) {
            this.playlistUrl = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
        }
    }
    public final List<Variant> variants;
    private HlsMasterPlaylist(List<Variant> variants) {
        this.variants = Collections.unmodifiableList(variants);
    }
    private static String attr(String line, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?:^|,)" + name + "=(?:\\\"([^\\\"]*)\\\"|([^,]*))")
            .matcher(line);
        return matcher.find() ? (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)) : "";
    }
    public static HlsMasterPlaylist parse(URI source, String text) {
        if (source == null || !isHttp(source) || text == null || !text.trim().startsWith("#EXTM3U"))
            throw new IllegalArgumentException("Invalid HLS master playlist");
        List<Variant> variants = new ArrayList<>();
        String streamAttributes = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    || line.startsWith("#EXT-X-SESSION-KEY:"))
                throw new IllegalArgumentException("Alternative tracks or encrypted master playlists require mux-aware processing");
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (streamAttributes != null) throw new IllegalArgumentException("Missing variant URI");
                streamAttributes = line.substring("#EXT-X-STREAM-INF:".length());
                continue;
            }
            if (line.startsWith("#")) continue;
            if (streamAttributes == null) throw new IllegalArgumentException("Unexpected master playlist URI");
            URI url = source.resolve(line);
            if (!isHttp(url)) throw new IllegalArgumentException("Invalid variant URI");
            long bandwidth;
            int width = 0;
            int height = 0;
            try {
                bandwidth = Long.parseLong(attr(streamAttributes, "BANDWIDTH"));
                String resolution = attr(streamAttributes, "RESOLUTION");
                if (!resolution.isEmpty()) {
                    String[] dimensions = resolution.split("x");
                    if (dimensions.length != 2) throw new NumberFormatException();
                    width = Integer.parseInt(dimensions[0]);
                    height = Integer.parseInt(dimensions[1]);
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid HLS variant attributes", ex);
            }
            if (bandwidth <= 0 || width < 0 || height < 0)
                throw new IllegalArgumentException("Invalid HLS variant metrics");
            variants.add(new Variant(url, bandwidth, width, height));
            streamAttributes = null;
        }
        if (streamAttributes != null || variants.isEmpty())
            throw new IllegalArgumentException("No complete HLS variants");
        return new HlsMasterPlaylist(variants);
    }
    private static boolean isHttp(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }
}
