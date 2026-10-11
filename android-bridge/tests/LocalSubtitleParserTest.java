package com.artaq.subhub;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LocalSubtitleParserTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 0.0005; }

    public static void main(String[] args) {
        // SRT with Arabic, tags, CRLF, BOM and a cue whose end is before its start (dropped).
        String srt = "﻿1\r\n00:00:01,500 --> 00:00:03,250\r\n<i>مرحباً</i> يا {\\an8}(أحمد)\r\n\r\n"
                + "2\r\n00:00:05,000 --> 00:00:04,000\r\nخطأ\r\n\r\n"
                + "3\r\n00:01:02,5 --> 00:01:04,75\r\nسطر أول\r\nسطر ثانٍ\r\n";
        List<LocalSubtitleParser.Cue> a = LocalSubtitleParser.parse(srt);
        check(a.size() == 2, "srt count " + a.size());
        check(near(a.get(0).start, 1.5) && near(a.get(0).end, 3.25), "srt time");
        check(a.get(0).text.equals("مرحباً يا (أحمد)"), "srt tags: " + a.get(0).text);
        check(near(a.get(1).start, 62.5) && near(a.get(1).end, 64.75), "srt short ms");
        check(a.get(1).text.equals("سطر أول\nسطر ثانٍ"), "srt two lines");

        // VTT: header, NOTE block, cue ids, no hours, cue settings after the end time.
        String vtt = "WEBVTT\n\nNOTE هذا تعليق\n\nintro\n00:10.000 --> 00:12.500 align:center line:90%\nأهلاً\n\n"
                + "01:00:00.000 --> 01:00:01.000\nآخر\n";
        List<LocalSubtitleParser.Cue> b = LocalSubtitleParser.parse(vtt);
        check(b.size() == 2, "vtt count " + b.size());
        check(near(b.get(0).start, 10) && near(b.get(0).end, 12.5) && b.get(0).text.equals("أهلاً"), "vtt no hours");
        check(near(b.get(1).start, 3600), "vtt hours");

        // ASS: custom Format order, commas in text, \N, override tags, drawing, Comment lines.
        String ass = "[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name, Fontname\nStyle: Default,Arial\n\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,تعليق\n"
                + "Dialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,{\\b1}نعم،\\Nلا, ربما\n"
                + "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10{\\p0}\n"
                + "Dialogue: 0,1:00:00.10,1:00:01.00,Default,,0,0,0,,أخير\n";
        List<LocalSubtitleParser.Cue> c = LocalSubtitleParser.parse(ass);
        check(c.size() == 2, "ass count " + c.size());
        check(near(c.get(0).start, 2.5) && near(c.get(0).end, 4.0), "ass time");
        check(c.get(0).text.equals("نعم،\nلا, ربما"), "ass text: " + c.get(0).text);
        check(near(c.get(1).start, 3600.1), "ass hours");

        // Arabic saved as Windows-1256 (common for old .srt files) and UTF-16 with a BOM.
        String sample = "1\n00:00:01,000 --> 00:00:02,000\nشكراً لك\n";
        if (Charset.isSupported("windows-1256")) {
            byte[] cp = sample.getBytes(Charset.forName("windows-1256"));
            check(LocalSubtitleParser.decode(cp).equals(sample), "windows-1256");
        }
        byte[] utf8 = sample.getBytes(StandardCharsets.UTF_8);
        check(LocalSubtitleParser.decode(utf8).equals(sample), "utf-8");
        byte[] le = sample.getBytes(StandardCharsets.UTF_16LE);
        byte[] leBom = new byte[le.length + 2];
        leBom[0] = (byte) 0xFF; leBom[1] = (byte) 0xFE;
        System.arraycopy(le, 0, leBom, 2, le.length);
        check(LocalSubtitleParser.decode(leBom).equals(sample), "utf-16le bom");
        check(LocalSubtitleParser.decode(le).equals(sample), "utf-16le no bom");

        // Not a subtitle at all.
        check(LocalSubtitleParser.parse("hello world\nnothing here").isEmpty(), "junk");
        check(LocalSubtitleParser.cleanName("  ").equals("ترجمة من الجهاز"), "empty name");

        System.out.println("PASS: SRT, VTT (no hours, settings), ASS (format, \\N, tags, drawing), Windows-1256, UTF-16, junk");
    }
}
