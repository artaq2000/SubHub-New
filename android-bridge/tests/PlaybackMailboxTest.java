package com.artaq.subhub;

public final class PlaybackMailboxTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        PlaybackMailbox m = new PlaybackMailbox();
        check(m.begin("one", "old-leaf", 0));
        check(!m.begin("double-tap", "old-leaf", 1));
        check(m.take("wrapper", "new-leaf", 2) == null);
        PlaybackMailbox.Request one = m.take("new-leaf", "new-leaf", 3);
        check(one != null && one.id.equals("one"));
        check(m.take("new-leaf", "new-leaf", 4) == null);
        check(!m.accept("one", "wrapper", true, 5));
        check(m.accept("one", "new-leaf", false, 6));
        check(m.accept("one", "new-leaf", true, 7));
        check(!m.accept("one", "new-leaf", true, 8));
        for (int i = 0; i < 6; i++) {
            String id = "toggle-" + i;
            check(m.begin(id, "new-leaf", 100+i));
            check(m.take("new-leaf", "new-leaf", 110+i) != null);
            check(m.accept(id, "new-leaf", true, 120+i));
        }
        check(m.begin("closed", "leaf", 200));
        check(m.take("leaf", "leaf", 201) != null);
        m.clear();
        check(m.liveRequest("leaf", 202).isEmpty());
        check(m.begin("reopened", "new-leaf", 203));
        check(!m.accept("closed", "leaf", true, 204));
        m.cancel("closed");
        check(m.take("new-leaf", "new-leaf", 205) != null);
        check(m.liveRequest("new-leaf", 206).equals("reopened"));
        m.cancel("reopened");
        check(m.liveRequest("new-leaf", 207).isEmpty());
        check(m.begin("unreachable", "leaf", 1000));
        check(m.take("leaf", "leaf", 4000) == null);
        check(m.begin("buffering", "leaf", 5000));
        check(m.take("leaf", "leaf", 5001) != null);
        check(!m.accept("buffering", "leaf", true, 20001));
        check(m.liveRequest("leaf", 20002).isEmpty());
        System.out.println("Native mailbox: exactly-once delivery, source handoff, repeat resume, close/reopen, expiry: PASS");
    }
}
