package com.artaq.subhub;

import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * Owner-initiated download orchestration for an already known HLS URL.
 * No stream sniffing, no UI, no DRM bypass, and no MP4 remuxing.
 * Caller must select a quality returned by inspect() and invoke off the UI thread.
 */
public final class OwnerVideoDownload {
    public interface Progress {
        void onStage(String stage, int completed, int total);
    }

    private OwnerVideoDownload() {}

    public static List<HlsPlaylistInspector.Quality> inspect(String source) throws Exception {
        return HlsPlaylistInspector.inspect(source);
    }

    public static File downloadHlsTransportStream(
            URI selectedPlaylist,
            File destinationTs,
            int connections,
            Progress progress) throws Exception {
        if (selectedPlaylist == null) throw new IllegalArgumentException("Selected playlist required");
        if (progress != null) progress.onStage("inspect", 0, 1);
        HlsMediaPlaylist media = HlsPlaylistInspector.loadMediaPlaylist(selectedPlaylist);
        if (progress != null) progress.onStage("inspect", 1, 1);
        if (!media.ended)
            throw new IllegalArgumentException("القائمة لا تحتوي على علامة نهاية البث EXT-X-ENDLIST؛ لا يمكن تجميعها كملف مكتمل حالياً.");
        if (!media.transportStream)
            throw new IllegalArgumentException("مقاطع البث ليست بامتداد TS. يلزم فحص الحاوية ودعم fMP4 أو امتدادات المقاطع المختلفة قبل التنزيل.");
        HlsTransportStreamDownloader.download(
            media, destinationTs, connections,
            (done, total) -> {
                if (progress != null) progress.onStage("download", done, total);
            }
        );
        if (!destinationTs.isFile() || destinationTs.length() == 0)
            throw new java.io.IOException("Downloaded file is empty");
        if (progress != null) progress.onStage("complete-ts", 1, 1);
        return destinationTs;
    }
}
