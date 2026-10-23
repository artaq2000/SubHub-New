package com.artaq.subhub;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Range-based downloader for owner-authorized direct HTTP(S) media URLs.
 * Not a stream extractor. Caller must run download() off the UI thread.
 */
public final class SegmentedVideoDownloader {
    public interface Progress { void onBytes(long downloaded, long total); }
    public static final class Result {
        public final boolean ranged;
        public final long bytes;
        Result(boolean ranged, long bytes) { this.ranged = ranged; this.bytes = bytes; }
    }
    private static final int TIMEOUT_MS = 20000;
    private static final int BUFFER = 64 * 1024;
    private SegmentedVideoDownloader() {}

    private static HttpURLConnection open(String source) throws IOException {
        URL url = new URL(source);
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol()))
            throw new IOException("Only HTTP(S) video sources are supported");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    public static Result download(String source, File destination, int connections, Progress progress)
            throws Exception {
        if (connections < 1 || connections > 16) throw new IllegalArgumentException("connections must be 1..16");
        File temp = new File(destination.getAbsolutePath() + ".part");
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot clear previous partial download");
        HttpURLConnection probe = open(source);
        long size;
        String validator;
        boolean ranged;
        try {
            probe.setRequestMethod("HEAD");
            int status = probe.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HEAD failed: " + status);
            size = probe.getContentLengthLong();
            validator = probe.getHeaderField("ETag");
            if (validator == null || validator.startsWith("W/"))
                validator = probe.getHeaderField("Last-Modified");
            ranged = size > 0 && "bytes".equalsIgnoreCase(probe.getHeaderField("Accept-Ranges"));
        } finally { probe.disconnect(); }
        if (size <= 0) throw new IOException("Unknown content length");
        // Some servers advertise Accept-Ranges but ignore Range on actual GETs.
        // Verify support before scheduling parallel writes. Never concatenate 200
        // responses from range workers: that would corrupt the output.
        if (ranged) {
            HttpURLConnection check = open(source);
            try {
                check.setRequestProperty("Range", "bytes=0-0");
                if (validator != null) check.setRequestProperty("If-Range", validator);
                int status = check.getResponseCode();
                String expected = "bytes 0-0/" + size;
                if (status != 206 || !expected.equals(check.getHeaderField("Content-Range"))) {
                    if (status != 200) throw new IOException("Range probe HTTP " + status);
                    ranged = false; // Safely use one ordinary GET instead.
                }
            } finally { check.disconnect(); }
        }
        int parts = ranged ? (int)Math.min(connections, Math.max(1, size / (1024 * 1024))) : 1;
        final boolean useRanges = ranged;
        final String stableValidator = validator;
        final long total = size;
        final Object lock = new Object();
        final long[] completed = {0L};
        ExecutorService pool = Executors.newFixedThreadPool(parts);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            try (RandomAccessFile file = new RandomAccessFile(temp, "rw")) { file.setLength(size); }
            for (int i = 0; i < parts; i++) {
                final long start = size * i / parts;
                final long end = size * (i + 1) / parts - 1;
                jobs.add(pool.submit(() -> {
                    HttpURLConnection c = null;
                    try {
                        c = open(source);
                        if (useRanges) {
                            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                            if (stableValidator != null) c.setRequestProperty("If-Range", stableValidator);
                        }
                        int status = c.getResponseCode();
                        if (useRanges && status != 206) throw new IOException("Server rejected byte range");
                        if (!useRanges && status != 200) throw new IOException("HTTP " + status);
                        if (useRanges) {
                            String expected = "bytes " + start + "-" + end + "/" + total;
                            if (!expected.equals(c.getHeaderField("Content-Range")))
                                throw new IOException("Mismatched Content-Range");
                        }
                        try (java.io.InputStream in = c.getInputStream();
                             RandomAccessFile out = new RandomAccessFile(temp, "rw")) {
                            out.seek(start);
                            byte[] buffer = new byte[BUFFER];
                            long remaining = end - start + 1;
                            while (remaining > 0) {
                                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                                int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                                if (n < 0) throw new IOException("Truncated response");
                                out.write(buffer, 0, n);
                                remaining -= n;
                                synchronized (lock) {
                                    completed[0] += n;
                                    if (progress != null) progress.onBytes(completed[0], total);
                                }
                            }
                        }
                    } catch (IOException e) { throw new RuntimeException(e); }
                    finally { if (c != null) c.disconnect(); }
                }));
            }
            for (Future<?> job : jobs) job.get();
            if (completed[0] != size) throw new IOException("Incomplete file");
            if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace destination");
            if (!temp.renameTo(destination)) throw new IOException("Cannot finalize download");
            return new Result(useRanges, size);
        } catch (Exception e) {
            for (Future<?> job : jobs) job.cancel(true);
            temp.delete();
            throw e;
        } finally { pool.shutdownNow(); }
    }
}
