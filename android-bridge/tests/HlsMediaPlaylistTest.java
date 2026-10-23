package com.artaq.subhub;

import java.net.URI;

/** JVM-only smoke checks: javac HlsMediaPlaylist.java HlsMediaPlaylistTest.java && java com.artaq.subhub.HlsMediaPlaylistTest */
public final class HlsMediaPlaylistTest {
    private static void expectFailure(String playlist) {
        try {
            HlsMediaPlaylist.parse(URI.create("https://example.org/videos/master.m3u8"), playlist);
            throw new AssertionError("Expected rejection");
        } catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        HlsMediaPlaylist p = HlsMediaPlaylist.parse(
            URI.create("https://example.org/videos/movie.m3u8"),
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\n000.ts\n#EXTINF:5.0,\nsub/001.ts\n#EXT-X-ENDLIST\n");
        if (p.segments.size() != 2 || !p.transportStream || !p.ended)
            throw new AssertionError("Incorrect HLS media playlist");
        if (!p.segments.get(1).toString().equals("https://example.org/videos/sub/001.ts"))
            throw new AssertionError("Relative segment URL resolution failed");
        expectFailure("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvariant.m3u8\n");
        expectFailure("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\n000.ts\n");
        expectFailure("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\n000.m4s\n");
        System.out.println("HLS parser smoke checks passed");
    }
}
