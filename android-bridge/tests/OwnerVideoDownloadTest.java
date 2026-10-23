package com.artaq.subhub;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Local HTTP integration test for inspection -> quality selection -> parallel download. */
public final class OwnerVideoDownloadTest {
    private static void route(HttpServer server, String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route(server, "/master.m3u8", bytes("#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\n360/media.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2900000,RESOLUTION=1280x720\n720/media.m3u8\n"));
        route(server, "/720/media.m3u8", bytes("#EXTM3U\n#EXTINF:4,\n0.ts\n#EXTINF:4,\n1.ts\n#EXT-X-ENDLIST\n"));
        route(server, "/720/0.ts", bytes("first-part"));
        route(server, "/720/1.ts", bytes("second-part"));
        server.start();
        File dir = Files.createTempDirectory("subhub-owner-download").toFile();
        File output = new File(dir, "movie.ts");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/master.m3u8";
            List<HlsPlaylistInspector.Quality> qualities = OwnerVideoDownload.inspect(url);
            if (qualities.size() != 2 || qualities.get(1).height != 720)
                throw new AssertionError("Wrong quality options");
            final boolean[] complete = {false};
            File result = OwnerVideoDownload.downloadHlsTransportStream(
                qualities.get(1).url, output, 2, (stage, done, total) -> {
                    if ("complete-ts".equals(stage)) complete[0] = true;
                });
            if (!complete[0] || !result.equals(output)
                    || !Arrays.equals(Files.readAllBytes(output.toPath()), bytes("first-partsecond-part")))
                throw new AssertionError("Owner download result failed");
            System.out.println("Owner inspection -> 720p selection -> TS download passed");
        } finally {
            server.stop(0);
            output.delete();
            dir.delete();
        }
    }
}
