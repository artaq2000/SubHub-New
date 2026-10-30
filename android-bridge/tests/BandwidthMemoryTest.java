package com.artaq.subhub;

/** 322.3.127: where «تلقائي» starts (BandwidthMemory). */
public class BandwidthMemoryTest {
    static int n = 0;

    static void eq(Object got, Object want, String what) {
        n++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": got «" + got + "», want «" + want + "»");
        }
    }

    public static void main(String[] a) {
        // the first time: the middle (≈ 720p)
        eq(BandwidthMemory.initial(0L), 4_000_000L, "nothing saved");
        eq(BandwidthMemory.initial(-5L), 4_000_000L, "bad value");
        eq(BandwidthMemory.initial(299_999L), 4_000_000L, "below the floor = not a real value");
        // a weak connection measured last time: start low
        eq(BandwidthMemory.initial(900_000L), 900_000L, "weak connection");
        // a fast one: start high (bounded)
        eq(BandwidthMemory.initial(25_000_000L), 25_000_000L, "fast connection");
        eq(BandwidthMemory.initial(400_000_000L), 50_000_000L, "bounded");
        // keep only real measurements
        eq(BandwidthMemory.toSave(3_000_000L, false), -1L, "not measured");
        eq(BandwidthMemory.toSave(100_000L, true), -1L, "too low to trust");
        eq(BandwidthMemory.toSave(7_500_000L, true), 7_500_000L, "measured");
        eq(BandwidthMemory.toSave(90_000_000L, true), 50_000_000L, "bounded");
        // Wi-Fi and mobile data apart
        eq(BandwidthMemory.key("wifi"), "bw_start_wifi", "wifi");
        eq(BandwidthMemory.key("cell"), "bw_start_cell", "mobile data");
        eq(BandwidthMemory.key("vpn"), "bw_start_other", "other");
        eq(BandwidthMemory.key(null), "bw_start_other", "unknown");
        System.out.println("BandwidthMemoryTest OK (" + n + " checks)");
    }
}
