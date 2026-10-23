package com.artaq.subhub;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads a finite, unencrypted MPEG-TS HLS playlist to one .ts file.
 * Does not remux into MP4 and does not support DRM, fMP4, byte ranges,
 * discontinuities, separate audio playlists, or live streams.
 * Call from a worker thread, never from the Android UI thread.
 */
public final class HlsTransportStreamDownloader {
    public interface Progress { void onSegment(int completed, int total); }
    private HlsTransportStreamDownloader() {}
    private static final int BUFFER = 65536;

    public static void download(HlsMediaPlaylist playlist, File output, int parallel, Progress progress)
            throws Exception {
        if (playlist == null || !playlist.transportStream || !playlist.ended)
            throw new IOException("Only completed MPEG-TS HLS media playlists are supported");
        if (parallel < 1 || parallel > 16) throw new IllegalArgumentException("parallel must be 1..16");
        if (!output.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ts"))
            throw new IOException("Output must have .ts extension (not MP4)");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Output directory missing");
        File workspace = new File(parent, output.getName() + ".segments");
        if (!workspace.mkdir()) throw new IOException("Temporary workspace already exists");
        ExecutorService workers = Executors.newFixedThreadPool(parallel);
        List<Future<?>> jobs = new ArrayList<>();
        final int[] done = {0};
        File tempOutput = new File(workspace, "assembled.ts");
        try {
            for (int i = 0; i < playlist.segments.size(); i++) {
                final int index = i;
                final URI uri = playlist.segments.get(i);
                final File chunk = new File(workspace, String.format(java.util.Locale.ROOT, "%08d.ts", index));
                jobs.add(workers.submit(() -> {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) uri.toURL().openConnection();
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(20000);
                        connection.setRequestProperty("Accept-Encoding", "identity");
                        if (connection.getResponseCode() != 200)
                            throw new IOException("Segment HTTP " + connection.getResponseCode());
                        try (InputStream in = connection.getInputStream();
                             FileOutputStream out = new FileOutputStream(chunk)) {
                            byte[] buf = new byte[BUFFER];
                            int n;
                            long length = 0;
                            while ((n = in.read(buf)) != -1) {
                                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                                out.write(buf, 0, n);
                                length += n;
                                if (length > 256L * 1024 * 1024) throw new IOException("Segment exceeds size limit");
                            }
                            if (length == 0) throw new IOException("Empty segment");
                        }
                        synchronized (done) {
                            done[0]++;
                            if (progress != null) progress.onSegment(done[0], playlist.segments.size());
                        }
                    } catch (IOException e) { throw new RuntimeException(e); }
                    finally { if (connection != null) connection.disconnect(); }
                }));
            }
            for (Future<?> job : jobs) job.get();
            try (FileOutputStream assembled = new FileOutputStream(tempOutput)) {
                byte[] buf = new byte[BUFFER];
                for (int i = 0; i < playlist.segments.size(); i++) {
                    File chunk = new File(workspace, String.format(java.util.Locale.ROOT, "%08d.ts", i));
                    try (FileInputStream in = new FileInputStream(chunk)) {
                        int n;
                        while ((n = in.read(buf)) != -1) assembled.write(buf, 0, n);
                    }
                }
                assembled.getFD().sync();
            }
            if (output.exists()) throw new IOException("Destination already exists");
            if (!tempOutput.renameTo(output)) throw new IOException("Unable to finalize TS file");
        } catch (Exception e) {
            for (Future<?> job : jobs) job.cancel(true);
            throw e;
        } finally {
            workers.shutdownNow();
            File[] files = workspace.listFiles();
            if (files != null) for (File file : files) file.delete();
            workspace.delete();
        }
    }
}
