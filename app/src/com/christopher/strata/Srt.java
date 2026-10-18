package com.christopher.strata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SubRip (.srt) captions: a forgiving reader and a strict writer. Pure Java.
 *
 * Reader accepts what real files contain: a UTF-8 byte-order mark, CRLF or CR line ends,
 * missing or wrong cue numbers, '.' instead of ',' before the milliseconds, 1-3 digit
 * milliseconds, hours over 99, position hints after the times (X1:... ignored), basic
 * &lt;i&gt;/&lt;b&gt;/&lt;u&gt;/&lt;font&gt; tags and {\an8}-style tags (stripped), and blank lines
 * inside a cue's text (the next time line is what starts a new cue).
 */
final class Srt {
    static final class Cue {
        long start, end;   // microseconds
        String text;
        Cue(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final Pattern TIME = Pattern.compile(
            "^\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3}).*$");

    static List<Cue> parse(String s) {
        ArrayList<Cue> out = new ArrayList<Cue>();
        if (s == null) return out;
        if (s.startsWith("﻿")) s = s.substring(1);
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Cue cur = null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = TIME.matcher(line);
            if (m.matches()) {
                if (cur != null) finish(cur, text, out);
                try { cur = new Cue(us(m, 1), us(m, 5), ""); }
                catch (IllegalArgumentException | ArithmeticException invalid) { cur=null; }
                text.setLength(0);
                continue;
            }
            if (cur == null) continue;
            // a bare number right before a time line is the next cue's index, not text
            if (line.trim().matches("\\d+") && i + 1 < lines.length && TIME.matcher(lines[i + 1]).matches()) continue;
            if (text.length() > 0 || !line.trim().isEmpty()) text.append(line).append('\n');
        }
        if (cur != null) finish(cur, text, out);
        Collections.sort(out, new Comparator<Cue>() {
            @Override public int compare(Cue a, Cue b) { return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0); }
        });
        return out;
    }

    private static void finish(Cue c, StringBuilder text, List<Cue> out) {
        String t = clean(text.toString());
        if (t.isEmpty() || c.end <= c.start) return;
        c.text = t;
        out.add(c);
    }

    /** Drops markup and trims each line; collapses blank lines at the ends. */
    static String clean(String t) {
        t = t.replaceAll("\\{\\\\[^}]*\\}", "");          // {\an8} etc.
        t = t.replaceAll("(?i)</?(i|b|u|s|font)(\\s[^>]*)?>", "");
        StringBuilder sb = new StringBuilder();
        for (String l : t.split("\n")) {
            String x = l.trim();
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        return sb.toString().trim().replaceAll("\n\\s*\n+", "\n");   // no blank lines inside a caption
    }

    private static long us(Matcher m, int g) {
        long h = Long.parseLong(m.group(g)), mi = Long.parseLong(m.group(g + 1)), se = Long.parseLong(m.group(g + 2));
        String ms = m.group(g + 3);
        long frac = Long.parseLong(ms);
        if (ms.length() == 1) frac *= 100; else if (ms.length() == 2) frac *= 10;   // ",5" means 500 ms
        if(mi>=60||se>=60)throw new IllegalArgumentException("Invalid caption time");
        return Math.multiplyExact(Math.addExact(Math.multiplyExact(Math.addExact(Math.addExact(Math.multiplyExact(h,3600),mi*60),se),1000),frac),1000);
    }

    static String write(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        ArrayList<Cue> ordered=new ArrayList<Cue>(cues);
        Collections.sort(ordered,new Comparator<Cue>(){@Override public int compare(Cue a,Cue b){return Long.compare(a.start,b.start);}});
        for (Cue c : ordered) {
            if (c.text == null || c.text.trim().isEmpty() || c.end <= c.start) continue;
            sb.append(n++).append("\r\n");
            long startMs=Math.max(0,Math.round(c.start/1000.0)),endMs=Math.max(startMs+1,Math.round(c.end/1000.0));
            sb.append(ts(startMs*1000)).append(" --> ").append(ts(endMs*1000)).append("\r\n");
            // a blank line inside the text would end the cue early in strict players
            sb.append(c.text.replace("\r\n","\n").replace('\r','\n').trim().replaceAll("\n\\s*\n+", "\n").replace("\n", "\r\n")).append("\r\n\r\n");
        }
        return sb.toString();
    }

    static String ts(long us) {
        long ms = Math.max(0, (us + 500) / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }
}
