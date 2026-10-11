package com.artaq.subhub;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 322.3.109: a subtitle file the viewer adds from the device (SRT, VTT, ASS/SSA).
 * Pure Java (no Android classes) so it can be tested on its own.
 * Arabic files are often saved as Windows-1256 instead of UTF-8 — both are read.
 */
final class LocalSubtitleParser {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_CUES = 30000;

    static final class Cue {
        final double start;
        final double end;
        final String text;
        Cue(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private LocalSubtitleParser() {}

    // ---------------------------------------------------------------- text
    static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        int n = bytes.length;
        if (n >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
            return new String(bytes, 3, n - 3, StandardCharsets.UTF_8);
        }
        if (n >= 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE) {
            return new String(bytes, 2, n - 2, StandardCharsets.UTF_16LE);
        }
        if (n >= 2 && (bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF) {
            return new String(bytes, 2, n - 2, StandardCharsets.UTF_16BE);
        }
        // UTF-16 without a BOM: every other byte is zero for Latin text and digits.
        int sample = Math.min(n, 4000), zeroOdd = 0, zeroEven = 0;
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) { if ((i & 1) == 1) zeroOdd++; else zeroEven++; }
        }
        if (zeroOdd > sample / 4) return new String(bytes, StandardCharsets.UTF_16LE);
        if (zeroEven > sample / 4) return new String(bytes, StandardCharsets.UTF_16BE);
        try {
            CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer out = strict.decode(ByteBuffer.wrap(bytes));
            return out.toString();
        } catch (CharacterCodingException notUtf8) {
            for (String name : new String[] {"windows-1256", "Cp1256"}) {
                try {
                    if (Charset.isSupported(name)) return new String(bytes, Charset.forName(name));
                } catch (Exception ignored) {}
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    // --------------------------------------------------------------- parse
    static List<Cue> parse(String raw) {
        String text = raw == null ? "" : raw.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        List<Cue> cues = looksLikeAss(text) ? parseAss(text) : parseSrtOrVtt(text);
        Collections.sort(cues, (a, b) -> Double.compare(a.start, b.start));
        if (cues.size() > MAX_CUES) cues = new ArrayList<>(cues.subList(0, MAX_CUES));
        return cues;
    }

    private static boolean looksLikeAss(String text) {
        return text.contains("[Events]") || Pattern.compile("(?m)^Dialogue:").matcher(text).find();
    }

    // 00:01:02,345 / 00:01:02.345 / 01:02.345 (VTT without hours)
    private static final Pattern TIMING = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})");

    private static double seconds(String h, String m, String s, String frac) {
        String f = (frac + "000").substring(0, 3);
        return (h == null ? 0 : Integer.parseInt(h)) * 3600.0
                + Integer.parseInt(m) * 60.0
                + Integer.parseInt(s)
                + Integer.parseInt(f) / 1000.0;
    }

    private static List<Cue> parseSrtOrVtt(String text) {
        List<Cue> out = new ArrayList<>();
        String[] blocks = text.split("\\n[ \\t]*\\n");
        for (String block : blocks) {
            String[] lines = block.split("\\n");
            int at = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher t = TIMING.matcher(lines[i]);
                if (t.find()) { at = i; m = t; break; }
            }
            if (at < 0) continue;
            double start = seconds(m.group(1), m.group(2), m.group(3), m.group(4));
            double end = seconds(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder body = new StringBuilder();
            for (int i = at + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
            String clean = cleanTags(body.toString());
            if (end > start && !clean.isEmpty()) out.add(new Cue(start, end, clean));
        }
        return out;
    }

    static String cleanTags(String s) {
        String v = s.replaceAll("<[^>]+>", "").replaceAll("\\{[^}]*\\}", "");
        v = v.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        StringBuilder out = new StringBuilder();
        for (String line : v.split("\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    // H:MM:SS.cc
    private static final Pattern ASS_TIME = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})");

    private static double assSeconds(String v) {
        Matcher m = ASS_TIME.matcher(v.trim());
        if (!m.matches()) return Double.NaN;
        String frac = m.group(4);
        double f = frac.length() == 1 ? Integer.parseInt(frac) / 10.0
                : frac.length() == 2 ? Integer.parseInt(frac) / 100.0
                : Integer.parseInt(frac) / 1000.0;
        return Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0
                + Integer.parseInt(m.group(3)) + f;
    }

    private static List<Cue> parseAss(String text) {
        List<Cue> out = new ArrayList<>();
        // Default Events format when the file has none.
        String[] format = {"layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"};
        boolean inEvents = !text.contains("[Events]");
        for (String rawLine : text.split("\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("[")) {
                inEvents = line.equalsIgnoreCase("[Events]");
                continue;
            }
            if (!inEvents) continue;
            if (line.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] f = line.substring(7).split(",");
                for (int i = 0; i < f.length; i++) f[i] = f[i].trim().toLowerCase(java.util.Locale.ROOT);
                format = f;
                continue;
            }
            if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;
            String[] parts = line.substring(9).split(",", format.length);
            if (parts.length < format.length) continue;
            int si = -1, ei = -1, ti = format.length - 1;
            for (int i = 0; i < format.length; i++) {
                if ("start".equals(format[i])) si = i;
                else if ("end".equals(format[i])) ei = i;
                else if ("text".equals(format[i])) ti = i;
            }
            if (si < 0 || ei < 0) continue;
            double start = assSeconds(parts[si]);
            double end = assSeconds(parts[ei]);
            if (Double.isNaN(start) || Double.isNaN(end) || end <= start) continue;
            String body = parts[ti];
            if (Pattern.compile("\\\\p[1-9]").matcher(body).find()) continue;   // vector drawing, not text
            body = body.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
            String clean = cleanTags(body);
            if (!clean.isEmpty()) out.add(new Cue(start, end, clean));
        }
        return out;
    }

    // ------------------------------------------------------------- display
    static String cleanName(String name) {
        String v = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (v.isEmpty()) v = "ترجمة من الجهاز";
        if (v.length() > 80) v = v.substring(0, 77) + "…";
        return v;
    }
}
