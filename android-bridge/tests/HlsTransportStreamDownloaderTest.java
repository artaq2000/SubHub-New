package com.artaq.subhub;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;

/** Integration smoke test: local HTTP segments downloaded in parallel and assembled in playlist order. */
public final class HlsTransportStreamDownloaderTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[][] payloads = {"segment-A".getBytes("UTF-8"), "segment-B".getBytes("UTF-8")};
        for (int i = 0; i < payloads.length; i++) {
            final byte[] data = payloads[i];
            server.createContext("/" + i + ".ts", exchange -> {
                exchange.sendResponseHeaders(200, data.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(data); }
            });
        }
        server.start();
        File directory = Files.createTempDirectory("subhub-hls-test").toFile();
        File output = new File(directory, "movie.ts");
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            HlsMediaPlaylist playlist = HlsMediaPlaylist.parse(URI.create(base + "movie.m3u8"),
                "#EXTM3U\n#EXTINF:4,\n0.ts\n#EXTINF:4,\n1.ts\n#EXT-X-ENDLIST\n");
            final int[] last = {0};
            HlsTransportStreamDownloader.download(playlist, output, 2, (done, total) -> {
                if (total != 2 || done < 1 || done > 2) throw new AssertionError("Invalid progress");
                last[0] = done;
            });
            byte[] expected = "segment-Asegment-B".getBytes("UTF-8");
            if (!Arrays.equals(Files.readAllBytes(output.toPath()), expected) || last[0] != 2)
                throw new AssertionError("Downloaded file is not in playlist order");
            System.out.println("HLS parallel download and ordered assembly passed");
        } finally {
            server.stop(0);
            output.delete();
            directory.delete();
        }
    }
}
