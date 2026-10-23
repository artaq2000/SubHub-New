package com.artaq.subhub;

import java.net.URI;

public final class HlsMasterPlaylistTest {
    public static void main(String[] args) {
        String master = "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1280x720\nhigh/index.m3u8\n";
        HlsMasterPlaylist result = HlsMasterPlaylist.parse(URI.create("https://example.com/media/master.m3u8"), master);
        if (result.variants.size() != 2 || result.variants.get(0).height != 360
            || result.variants.get(1).height != 720 || result.variants.get(1).bandwidth != 2600000
            || !result.variants.get(1).playlistUrl.toString().equals("https://example.com/media/high/index.m3u8"))
            throw new AssertionError("HLS quality variants were not parsed correctly");
        try {
            HlsMasterPlaylist.parse(URI.create("https://example.com/master.m3u8"),
                "#EXTM3U\\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\\\"audio\\\",URI=\\\"audio.m3u8\\\"\\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\\\"audio\\\"\\nvideo.m3u8\\n");
            throw new AssertionError("Separate audio track must be rejected until muxing is supported");
        } catch (IllegalArgumentException expected) { }
        System.out.println("HLS master playlist variants passed");
    }
}
