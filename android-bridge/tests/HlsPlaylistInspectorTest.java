package com.artaq.subhub;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** End-to-end local HTTP inspection of real advertised HLS quality variants. */
public final class HlsPlaylistInspectorTest {
    private static void serve(HttpServer server, String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/master.m3u8", "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\n360/index.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2900000,RESOLUTION=1280x720\n720/index.m3u8\n");
        serve(server, "/720/index.m3u8", "#EXTM3U\n#EXTINF:4.0,\n000.ts\n#EXT-X-ENDLIST\n");
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            List<HlsPlaylistInspector.Quality> qualities = HlsPlaylistInspector.inspect(root + "/master.m3u8");
            if (qualities.size() != 2 || qualities.get(0).height != 360 || qualities.get(1).height != 720)
                throw new AssertionError("Wrong actual HLS qualities");
            URI selected = qualities.get(1).url;
            HlsMediaPlaylist media = HlsPlaylistInspector.loadMediaPlaylist(selected);
            if (media.segments.size() != 1
                || !media.segments.get(0).toString().equals(root + "/720/000.ts"))
                throw new AssertionError("Selected quality media segments are wrong");
            System.out.println("HLS inspection and selected-quality media playlist passed");
        } finally { server.stop(0); }
    }
}
