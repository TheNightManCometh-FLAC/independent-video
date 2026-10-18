package com.christopher.strata;

/**
 * Magnetic main-track math, in microseconds. Clip i plays for dur[i] (source length / speed).
 * A transition between clip i and i+1 overlaps them by T_i, so clip i+1 starts T_i before clip i
 * ends. T_i is clamped to half of each neighbour so overlaps never chain. Pure Java.
 */
final class Timeline {
    /** Effective overlap between clip i and i+1. */
    static long trans(long[] dur, long[] trans, int i) {
        if (i < 0 || i >= dur.length - 1 || trans[i] <= 0) return 0;
        return Math.max(0, Math.min(trans[i], Math.min(dur[i] / 2, dur[i + 1] / 2)));
    }

    static long[] starts(long[] dur, long[] trans) {
        long[] s = new long[dur.length];
        for (int i = 1; i < dur.length; i++) s[i] = s[i - 1] + dur[i - 1] - trans(dur, trans, i - 1);
        return s;
    }

    static long end(long[] dur, long[] trans) {
        if (dur.length == 0) return 0;
        long[] s = starts(dur, trans);
        return s[dur.length - 1] + dur[dur.length - 1];
    }

    /**
     * What plays at time t: {a, b, p}. a = clip index (or -1), b = incoming clip during a
     * transition (or -1), p = transition progress 0..1 scaled by 1e6 (long for exactness).
     */
    static long[] at(long[] dur, long[] trans, long t) {
        long[] s = starts(dur, trans);
        int n = dur.length;
        for (int i = 0; i < n; i++) {
            long e = s[i] + dur[i];
            if (t < s[i] || t >= e) continue;
            // inside clip i; is the next clip already overlapping?
            if (i + 1 < n && t >= s[i + 1]) {
                long T = trans(dur, trans, i);
                long p = T <= 0 ? 1000000 : (t - s[i + 1]) * 1000000 / T;
                return new long[]{i, i + 1, Math.min(1000000, Math.max(0, p))};
            }
            return new long[]{i, -1, 0};
        }
        return new long[]{-1, -1, 0};
    }

    /** Index of the clip whose span contains t, preferring the later one in overlaps; -1 if none. */
    static int clipAt(long[] dur, long[] trans, long t) {
        long[] r = at(dur, trans, t);
        return r[1] >= 0 ? (int) r[1] : (int) r[0];
    }

    /** Snap t to the nearest candidate within tol; returns t unchanged when none is close. */
    static long snap(long t, long[] candidates, long tol) {
        long best = t, bd = tol + 1;
        for (long c : candidates) {
            long d = Math.abs(c - t);
            if (d < bd) { bd = d; best = c; }
        }
        return bd <= tol ? best : t;
    }

    // ---------------------------------------------------------------- frames
    /** Index of the frame at or nearest to t. */
    static long frameIndex(long t, int fps) { return Math.round(t * (double) fps / 1e6); }
    /** Start time (us) of frame n. */
    static long frameTime(long n, int fps) { return Math.round(n * 1e6 / fps); }
    /** t moved to the nearest frame boundary (also for durations and deltas). */
    static long quant(long t, int fps) { return frameTime(frameIndex(t, fps), fps); }
    /** One frame, rounded up so a one-frame item is never shorter than a frame. */
    static long frame(int fps) { return (1000000 + fps - 1) / fps; }

    /** Timecode m:ss:ff (ff = frame within the second). */
    static String timecode(long t, int fps) {
        long n = Math.max(0, frameIndex(t, fps));
        long s = n / fps, f = n % fps;
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 60, s % 60, f);
    }

    // ---------------------------------------------------------------- lanes
    /**
     * Packs items into display lanes so overlapping ones sit on separate rows: each item, in
     * order of start (ties: list order), goes to the first lane that is free by its start. With
     * every lane busy and `cap` lanes in use it goes to the lane that frees up first (it will be
     * drawn overlapping). Returns the lane per item; out[n] = lanes used (at least 1).
     */
    static int[] lanes(long[] start, long[] end, int cap) {
        int n = start.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        final long[] st = start;
        java.util.Arrays.sort(ord, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return st[a] != st[b] ? (st[a] < st[b] ? -1 : 1) : a - b; }
        });
        int[] out = new int[n + 1];
        long[] free = new long[Math.max(1, cap)];
        int used = 0;
        for (int k = 0; k < n; k++) {
            int i = ord[k], lane = -1;
            for (int j = 0; j < used; j++) if (free[j] <= start[i]) { lane = j; break; }
            if (lane < 0 && used < cap) lane = used++;
            if (lane < 0) { lane = 0; for (int j = 1; j < used; j++) if (free[j] < free[lane]) lane = j; }
            out[i] = lane;
            free[lane] = Math.max(free[lane], end[i]);
        }
        out[n] = Math.max(1, used);
        return out;
    }
}
