package com.christopher.strata;

import java.util.ArrayList;

/**
 * Keyframes for one item: a time-sorted list of value vectors. Times are microseconds relative to
 * the item's own start, so keys travel with the item when it moves.
 *
 * Properties animate independently (as in Alight Motion and LumaFusion 5): each key carries a
 * mask of the channels it sets, and a channel is interpolated only between keys that set it, so
 * keying the opacity at 2 s doesn't pin the position there. Keys from before per-property
 * keyframes set every channel (mask ALL) and behave exactly as they did.
 *
 * The segment that starts at a key uses that key's ease: Linear, Smooth (smoothstep), Hold, or
 * a cubic Bezier ease (CSS cubic-bezier: time handles x1, x2 in 0..1, value handles y1, y2,
 * allowed past 0..1 for overshoot). Before a channel's first key / after its last, it holds.
 * Pure Java.
 */
final class Keys {
    static final int LINEAR = 0, SMOOTH = 1, HOLD = 2, BEZIER = 3;
    static final String[] EASE = {"Linear", "Smooth", "Hold", "Curve"};
    static final int ALL = -1;

    /** Named Bezier eases (CSS / After Effects conventions). */
    static final String[] EASE_PRESETS = {"Ease in", "Ease out", "Ease in-out", "Overshoot"};
    static final float[][] EASE_BEZ = {{0.42f, 0, 1, 1}, {0, 0, 0.58f, 1}, {0.42f, 0, 0.58f, 1}, {0.34f, 1.56f, 0.64f, 1}};

    static final class Key {
        long t;
        float[] v;
        int ease = SMOOTH;
        int mask = ALL;          // channels this key sets (bit j = channel j)
        float[] bez;             // x1, y1, x2, y2 when ease == BEZIER

        boolean has(int j) { return mask == ALL || (mask >> j & 1) != 0; }

        Key copy() {
            Key k = new Key();
            k.t = t;
            k.v = v.clone();
            k.ease = ease;
            k.mask = mask;
            k.bez = bez == null ? null : bez.clone();
            return k;
        }
    }

    final ArrayList<Key> list = new ArrayList<Key>();

    boolean isEmpty() { return list.isEmpty(); }
    int size() { return list.size(); }

    Keys copy() {
        Keys k = new Keys();
        for (Key x : list) k.list.add(x.copy());
        return k;
    }

    static float ease(int type, float x) {
        if (type == HOLD) return 0;
        if (type == LINEAR) return x;
        return x * x * (3 - 2 * x);
    }

    /** Eased progress for the segment starting at key a, at linear progress x (0..1). */
    static float ease(Key a, float x) {
        if (a.ease == BEZIER && a.bez != null) return bezier(a.bez, x);
        return ease(a.ease, x);
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) at time x: solve the curve's parameter for x, return y. */
    static float bezier(float[] b, float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double x1 = Math.max(0, Math.min(1, b[0])), y1 = b[1], x2 = Math.max(0, Math.min(1, b[2])), y2 = b[3];
        double s = x;
        for (int it = 0; it < 8; it++) {   // Newton on x(s) = x
            double cx = bez(s, x1, x2) - x, d = bezD(s, x1, x2);
            if (Math.abs(cx) < 1e-7) break;
            if (Math.abs(d) < 1e-6) break;
            s -= cx / d;
        }
        if (s < 0 || s > 1 || Math.abs(bez(s, x1, x2) - x) > 1e-5) {   // fall back to bisection
            double lo = 0, hi = 1;
            for (int it = 0; it < 40; it++) { s = (lo + hi) / 2; if (bez(s, x1, x2) < x) lo = s; else hi = s; }
        }
        return (float) bez(s, y1, y2);
    }

    private static double bez(double s, double p1, double p2) { double u = 1 - s; return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s; }
    private static double bezD(double s, double p1, double p2) { double u = 1 - s; return 3 * u * u * p1 + 6 * u * s * (p2 - p1) + 3 * s * s * (1 - p2); }

    /** Values at local time t into out (only channels that have keys). Returns false when there are none. */
    boolean eval(long t, float[] out) {
        int n = list.size();
        if (n == 0) return false;
        for (int j = 0; j < out.length; j++) {
            Key a = null, b = null;
            for (int i = 0; i < n; i++) {
                Key k = list.get(i);
                if (!k.has(j) || j >= k.v.length) continue;
                if (k.t <= t) a = k;
                else { b = k; break; }
            }
            if (a == null && b == null) continue;           // channel not animated: its own value
            if (a == null) { out[j] = b.v[j]; continue; }   // before its first key
            if (b == null) { out[j] = a.v[j]; continue; }   // after its last key
            float x = b.t == a.t ? 1 : (t - a.t) / (float) (b.t - a.t);
            out[j] = a.v[j] + (b.v[j] - a.v[j]) * ease(a, x);
        }
        return true;
    }

    /** True if any key sets channel j. */
    boolean animated(int j) {
        for (Key k : list) if (k.has(j)) return true;
        return false;
    }

    /** Index of the key within tol of t, or -1. */
    int near(long t, long tol) {
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            long d = Math.abs(list.get(i).t - t);
            if (d <= tol && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Sets every channel of the key near t (within tol) to v, or inserts a new one. Returns its index. */
    int set(long t, float[] v, long tol) {
        int i = near(t, tol);
        if (i >= 0) {
            Key k = list.get(i);
            k.v = v.clone();
            k.mask = ALL;
            return i;
        }
        Key k = insert(t, v.clone());
        return list.indexOf(k);
    }

    /**
     * Keys channel j at t to value: added to the key near t (within tol), or a new key that sets
     * only this channel. Returns the key's index.
     */
    int setChannel(long t, int j, float value, long tol) { return setChannel(t, j, value, tol, null); }

    /**
     * As above; base is the item's whole value vector (its width and the values of the channels
     * this key doesn't set), so a new key is as wide as the item's other keys.
     */
    int setChannel(long t, int j, float value, long tol, float[] base) {
        int i = near(t, tol);
        Key k;
        if (i >= 0) k = list.get(i);
        else {
            int w = Math.max(j + 1, base != null ? base.length : list.isEmpty() ? 0 : list.get(0).v.length);
            float[] cur = new float[w];
            if (base != null) System.arraycopy(base, 0, cur, 0, Math.min(w, base.length));
            eval(t, cur);   // the other channels' values, for reference only (not set by this key)
            k = insert(t, cur);
            k.mask = 0;
        }
        if (k.v.length <= j) { float[] nv = new float[j + 1]; System.arraycopy(k.v, 0, nv, 0, k.v.length); k.v = nv; }
        k.v[j] = value;
        if (k.mask != ALL) k.mask |= 1 << j;
        return list.indexOf(k);
    }

    /** Stops key i from setting channel j; the key goes when it sets nothing. */
    void unsetChannel(int i, int j) {
        if (i < 0 || i >= list.size()) return;
        Key k = list.get(i);
        int all = (1 << k.v.length) - 1;
        int m = k.mask == ALL ? all : k.mask;
        m &= ~(1 << j);
        if ((m & all) == 0) list.remove(i); else k.mask = m;
    }

    /** Removes channel j from every key (stops animating it). */
    void clearChannel(int j) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).has(j)) unsetChannel(i, j);
    }

    private Key insert(long t, float[] v) {
        Key k = new Key();
        k.t = t;
        k.v = v;
        int at = 0;
        while (at < list.size() && list.get(at).t < t) at++;
        // a new key between two others inherits the ease of the segment it splits
        if (at > 0) { Key p = list.get(at - 1); k.ease = p.ease; k.bez = p.bez == null ? null : p.bez.clone(); }
        list.add(at, k);
        return k;
    }

    void remove(int i) { if (i >= 0 && i < list.size()) list.remove(i); }

    /** Latest key time strictly before t (minus tol), or Long.MIN_VALUE. */
    long prev(long t, long tol) {
        long r = Long.MIN_VALUE;
        for (Key k : list) if (k.t < t - tol) r = k.t;
        return r;
    }

    /** Earliest key time strictly after t (plus tol), or Long.MAX_VALUE. */
    long next(long t, long tol) {
        for (Key k : list) if (k.t > t + tol) return k.t;
        return Long.MAX_VALUE;
    }

    /** Moves every key by dt (used when an item's start moves but its content shouldn't). */
    void shift(long dt) { for (Key k : list) k.t += dt; }

    /** True when v differs from what the keys give at t (so an edit should be keyed). */
    boolean differs(long t, float[] v) {
        float[] e = v.clone();
        if (!eval(t, e)) return false;
        for (int j = 0; j < v.length; j++) if (Math.abs(e[j] - v[j]) > 1e-4f * Math.max(1, Math.abs(v[j]))) return true;
        return false;
    }

    /**
     * After an edit: every animated channel whose value v[j] differs from its keys at t gets
     * keyed there (only that channel). Channels that aren't animated keep their plain value.
     * Returns true if anything was keyed.
     */
    boolean capture(long t, float[] v, long tol) {
        float[] e = v.clone();
        if (!eval(t, e)) return false;
        boolean any = false;
        for (int j = 0; j < v.length; j++) {
            if (!animated(j)) continue;
            if (Math.abs(e[j] - v[j]) > 1e-4f * Math.max(1, Math.abs(v[j]))) { setChannel(t, j, v[j], tol, v); any = true; }
        }
        return any;
    }
}
