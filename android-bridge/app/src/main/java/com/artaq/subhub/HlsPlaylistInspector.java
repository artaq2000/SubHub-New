package com.artaq.subhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit HLS URL inspection; does not discover or intercept video streams.
 * Must be called from a background thread, only after an owner action.
 */
public final class HlsPlaylistInspector {
    public static final class Quality {
        public final URI url;
        public final int height;
        public final long bandwidth;
        Quality(URI url, int height, long bandwidth) {
            this.url = url;
            this.height = height;
            this.bandwidth = bandwidth;
        }
        public String label() {
            return height > 0 ? height + "p" : (bandwidth / 1000) + " kbps";
        }
    }
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private HlsPlaylistInspector() {}

    public static List<Quality> inspect(String url) throws Exception {
        URI source = URI.create(url);
        String playlist = read(source);
        if (playlist.contains("#EXT-X-STREAM-INF:")) {
            HlsMasterPlaylist master = HlsMasterPlaylist.parse(source, playlist);
            List<Quality> result = new ArrayList<>();
            for (HlsMasterPlaylist.Variant v : master.variants)
                result.add(new Quality(v.playlistUrl, v.height, v.bandwidth));
            return Collections.unmodifiableList(result);
        }
        HlsMediaPlaylist.parse(source, playlist);
        return Collections.singletonList(new Quality(source, 0, 0));
    }

    public static HlsMediaPlaylist loadMediaPlaylist(URI url) throws Exception {
        return HlsMediaPlaylist.parse(url, read(url));
    }

    private static String read(URI uri) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new IOException("Only HTTP(S) playlists are supported");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("Playlist HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_PLAYLIST_BYTES)
                        throw new IOException("Playlist exceeds size limit");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
