package com.christopher.strata;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Breadcrumbs: the last few things the user did, written into the crash log. Thread-safe. */
final class Crash {
    private static final int N = 24;
    private static final String[] ring = new String[N];
    private static int next;
    private static final SimpleDateFormat T = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static synchronized void note(String s) {
        String last = ring[(next + N - 1) % N];
        if (last != null && last.endsWith(" " + s)) return;   // collapse repeats
        ring[next] = T.format(new Date()) + " " + s;
        next = (next + 1) % N;
    }

    static synchronized String trail() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < N; i++) {
            String s = ring[(next + i) % N];
            if (s != null) b.append("  ").append(s).append('\n');
        }
        return b.length() == 0 ? "  (none)\n" : b.toString();
    }
}
