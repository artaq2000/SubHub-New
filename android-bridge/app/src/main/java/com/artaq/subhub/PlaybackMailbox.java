package com.artaq.subhub;

/** One user request, consumed once by the current media frame. No toggle retries. */
final class PlaybackMailbox {
    static final long CONNECT_TIMEOUT_MS = 3000L;
    static final long PLAY_TIMEOUT_MS = 15000L;

    static final class Request {
        final String id;
        String source;
        long deadline;
        boolean delivered;
        double resumeAt = -1;

        Request(String id, String source, long deadline) {
            this.id = id;
            this.source = source;
            this.deadline = deadline;
        }
    }

    private Request pending;

    private void expire(long now) {
        if (pending != null && now >= pending.deadline) pending = null;
    }

    synchronized boolean begin(String id, String source, long now) {
        expire(now);
        if (id == null || id.isEmpty() || pending != null) return false;
        pending = new Request(id, source, now + CONNECT_TIMEOUT_MS);
        return true;
    }

    synchronized boolean beginResume(String id, String source, double resumeAt, long now) {
        if (!begin(id, source, now)) return false;
        pending.resumeAt = Math.max(0, resumeAt);
        pending.deadline = now + 12000L;
        return true;
    }

    synchronized boolean isResuming() { return pending != null && pending.resumeAt >= 0; }

    synchronized Request take(String source, String currentSource, long now) {
        expire(now);
        if (pending == null || pending.delivered || source.isEmpty()) return null;
        // The native clock, not an old UI packet, chooses the media frame.
        if (!currentSource.isEmpty()) pending.source = currentSource;
        if (!source.equals(pending.source)) return null;
        pending.delivered = true;
        pending.deadline = now + PLAY_TIMEOUT_MS;
        return pending;
    }

    synchronized String liveRequest(String source, long now) {
        expire(now);
        return pending != null && source.equals(pending.source) ? pending.id : "";
    }

    synchronized boolean accept(String id, String source, boolean terminal, long now) {
        expire(now);
        if (pending == null || !pending.delivered || !pending.id.equals(id)
                || !pending.source.equals(source)) return false;
        if (terminal) pending = null;
        return true;
    }

    synchronized void cancel(String id) {
        if (pending != null && pending.id.equals(id)) pending = null;
    }

    synchronized void clear() { pending = null; }
}
