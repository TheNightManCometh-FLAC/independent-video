package com.christopher.strata;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * A clip's speed as a curve across its footage (CapCut's "Curve" speed, LumaFusion 5's speed
 * ramps): points at positions x (0..1 of the clip's source span, first at 0, last at 1) with a
 * speed each (0.1x - 10x).
 *
 * 3.15: every stretch between two points has its own shape, in log2(speed):
 *   SMOOTH  a monotone cubic through the points (never overshoots them), as before
 *   LINEAR  a straight change
 *   EASE    an S-curve that settles flat on both points
 *   HOLD    keeps the first point's speed, then jumps (a hard cut in speed)
 * Each stretch is a cubic Hermite (Hold: a constant), so a split anywhere is exact: the pieces
 * keep explicit end slopes (st) and play exactly like that stretch of the original.
 *
 * Playback time is the integral of 1/speed over the footage. A 1024-step table of that integral
 * maps output time to source position and back, so preview, export and every edit agree.
 * Pure Java.
 */
final class SpeedCurve {
    static final float MIN = 0.1f, MAX = 10f;
    private static final int N = 1024;
    static final int SMOOTH = 0, LINEAR = 1, EASE = 2, HOLD = 3;
    static final String[] SHAPES = {"Smooth", "Linear", "Ease", "Hold"};

    float[] x;   // 0..1, sorted, x[0] = 0, x[n-1] = 1
    float[] v;   // speed at each point
    /** Shape of the stretch from point i to i + 1 (length n - 1). */
    int[] seg;
    /**
     * Explicit slopes of log2(speed) per unit of x at both ends of each stretch (2 per stretch),
     * or null to derive them from the shapes. Set by sub(): a split keeps the exact ramp on both
     * sides of the cut. Any edit returns the curve to derived slopes.
     */
    float[] st;

    private transient double[] cum;   // cum[i] = integral of du / speed from 0 to i/N
    private transient float[] lv, a0, a1;   // log2 speeds; slopes at each stretch's two ends

    SpeedCurve(float[] x, float[] v) { this(x, v, null); }

    SpeedCurve(float[] x, float[] v, int[] seg) {
        this.x = x; this.v = v;
        this.seg = seg != null && seg.length == Math.max(0, x.length - 1) ? seg : new int[Math.max(0, x.length - 1)];
        normalise();
    }

    SpeedCurve copy() {
        SpeedCurve c = new SpeedCurve(x.clone(), v.clone(), seg.clone());
        c.st = st == null ? null : st.clone();
        return c;
    }

    int size() { return x.length; }

    /** Call after changing any point or shape. */
    void changed() { normalise(); st = null; cum = null; lv = null; }

    void setShape(int i, int shape) {
        if (i < 0 || i >= seg.length) return;
        seg[i] = Math.max(SMOOTH, Math.min(HOLD, shape));
        changed();
    }

    /** True if every stretch is Smooth (what 3.14 can read). */
    boolean allSmooth() {
        for (int k : seg) if (k != SMOOTH) return false;
        return true;
    }

    private void normalise() {
        int n = x.length;
        for (int i = 0; i < n; i++) {
            v[i] = Math.max(MIN, Math.min(MAX, Float.isNaN(v[i]) ? 1 : v[i]));
            x[i] = Math.max(0, Math.min(1, Float.isNaN(x[i]) ? 0 : x[i]));
        }
        // sorted by position (a hand-edited or damaged file may not be)
        boolean sorted = true;
        for (int i = 1; i < n; i++) if (x[i] < x[i - 1]) sorted = false;
        if (!sorted) java.util.Arrays.fill(seg, SMOOTH);   // a damaged file: its shapes no longer line up
        for (int i = 1; i < n; i++) {
            float kx = x[i], kv = v[i];
            int j = i - 1;
            while (j >= 0 && x[j] > kx) { x[j + 1] = x[j]; v[j + 1] = v[j]; j--; }
            x[j + 1] = kx; v[j + 1] = kv;
        }
        for (int i = 0; i < seg.length; i++) if (seg[i] < SMOOTH || seg[i] > HOLD) seg[i] = SMOOTH;
        x[0] = 0;
        x[n - 1] = 1;
        for (int i = 1; i < n; i++) if (x[i] < x[i - 1]) x[i] = x[i - 1];
    }

    // ---------------------------------------------------------------- evaluation
    private void tangents() {
        if (lv != null) return;
        int n = x.length;
        float[] l = new float[n];
        for (int i = 0; i < n; i++) l[i] = (float) (Math.log(v[i]) / Math.log(2));
        int ns = Math.max(0, n - 1);
        float[] s0 = new float[ns], s1 = new float[ns];
        if (st != null && st.length == 2 * ns) {
            for (int i = 0; i < ns; i++) { s0[i] = st[2 * i]; s1[i] = st[2 * i + 1]; }
        } else if (ns > 0) {
            // monotone point slopes (Fritsch-Carlson) for the Smooth stretches
            float[] d = new float[ns], m = new float[n];
            for (int i = 0; i < ns; i++) {
                float h = x[i + 1] - x[i];
                d[i] = h <= 1e-6f ? 0 : (l[i + 1] - l[i]) / h;
            }
            m[0] = d[0];
            m[n - 1] = d[ns - 1];
            for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
            for (int i = 0; i < ns; i++) {
                if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
                float p = m[i] / d[i], q = m[i + 1] / d[i], ss = p * p + q * q;
                if (ss > 9) {
                    float t = (float) (3 / Math.sqrt(ss));
                    m[i] = t * p * d[i];
                    m[i + 1] = t * q * d[i];
                }
            }
            for (int i = 0; i < ns; i++) {
                switch (seg[i]) {
                    case LINEAR: s0[i] = s1[i] = d[i]; break;     // a Hermite with both slopes = the chord is a line
                    case EASE: case HOLD: s0[i] = s1[i] = 0; break;
                    default: s0[i] = m[i]; s1[i] = m[i + 1];
                }
            }
        }
        a0 = s0; a1 = s1; lv = l;
    }

    private int segAt(double u) {
        int n = x.length, i = 0;
        while (i < n - 2 && u > x[i + 1]) i++;
        return i;
    }

    /** log2(speed) and its slope per unit x at u: the stretch's exact cubic (or its hold). */
    private double[] logAndSlope(double u) {
        tangents();
        int n = x.length;
        if (n == 1) return new double[]{lv[0], 0};
        if (u <= x[0]) return new double[]{lv[0], seg[0] == HOLD ? 0 : a0[0]};
        if (u >= x[n - 1]) return new double[]{lv[n - 1], seg[n - 2] == HOLD ? 0 : a1[n - 2]};
        int i = segAt(u);
        double h = x[i + 1] - x[i];
        if (h <= 1e-6) return new double[]{lv[i + 1], 0};
        if (seg[i] == HOLD) return new double[]{lv[i], 0};
        double t = (u - x[i]) / h, t2 = t * t, t3 = t2 * t;
        double y = (2 * t3 - 3 * t2 + 1) * lv[i] + (t3 - 2 * t2 + t) * h * a0[i] + (-2 * t3 + 3 * t2) * lv[i + 1] + (t3 - t2) * h * a1[i];
        double dy = ((6 * t2 - 6 * t) * lv[i] + (3 * t2 - 4 * t + 1) * h * a0[i] + (-6 * t2 + 6 * t) * lv[i + 1] + (3 * t2 - 2 * t) * h * a1[i]) / h;
        return new double[]{y, dy};
    }

    /** Speed at source position u (0..1). */
    float speedAt(double u) {
        tangents();
        int n = x.length;
        if (n == 1 || u <= x[0]) return v[0];
        if (u >= x[n - 1]) return v[n - 1];
        int i = segAt(u);
        if (seg[i] == HOLD) return v[i];
        return (float) Math.max(MIN, Math.min(MAX, Math.pow(2, logAndSlope(u)[0])));
    }

    private void table() {
        if (cum != null) return;
        double[] c = new double[N + 1];
        double prev = 1.0 / speedAt(0);
        for (int i = 1; i <= N; i++) {
            double cur = 1.0 / speedAt(i / (double) N);
            c[i] = c[i - 1] + (prev + cur) * 0.5 / N;
            prev = cur;
        }
        cum = c;
    }

    /** Playback length per unit of footage: clip duration = (out - in) x total(). */
    double total() { table(); return cum[N]; }

    /**
     * Playback time (in curve-span units) from position 0 to u, for any u: before 0 and after 1
     * the curve holds its end speeds, so a clip may window footage beyond the curve's own range.
     */
    double integ(double u) {
        table();
        if (u <= 0) return u / speedAt(0);
        if (u >= 1) return cum[N] + (u - 1) / speedAt(1);
        double p = u * N;
        int i = (int) Math.floor(p);
        return cum[i] + (cum[Math.min(N, i + 1)] - cum[i]) * (p - i);
    }

    /** Inverse of integ: the position reached after playback time I (curve-span units). */
    double uAtIntegral(double I) {
        table();
        if (I <= 0) return I * speedAt(0);
        if (I >= cum[N]) return 1 + (I - cum[N]) * speedAt(1);
        int lo = 0, hi = N;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (cum[mid] <= I) lo = mid; else hi = mid; }
        double span = cum[hi] - cum[lo];
        double k = span <= 0 ? 0 : (I - cum[lo]) / span;
        return (lo + k) / N;
    }

    /** Fraction of the playback (0..1) at which source position u is shown. */
    double fAtU(double u) {
        table();
        if (u <= 0) return 0;
        if (u >= 1) return 1;
        double p = u * N;
        int i = (int) Math.floor(p);
        double c = cum[i] + (cum[Math.min(N, i + 1)] - cum[i]) * (p - i);
        return c / cum[N];
    }

    /** Source position (0..1) shown at playback fraction f. */
    double uAtF(double f) {
        table();
        if (f <= 0) return 0;
        if (f >= 1) return 1;
        double target = f * cum[N];
        int lo = 0, hi = N;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (cum[mid] <= target) lo = mid; else hi = mid; }
        double span = cum[hi] - cum[lo];
        double k = span <= 0 ? 0 : (target - cum[lo]) / span;
        return (lo + k) / N;
    }

    // ---------------------------------------------------------------- editing
    /** Adds a point at u with the curve's current speed there; returns its index. */
    int addPoint(double u) {
        float s = speedAt(u);
        int n = x.length, at = 1;
        while (at < n - 1 && x[at] < u) at++;
        float[] nx = new float[n + 1], nv = new float[n + 1];
        System.arraycopy(x, 0, nx, 0, at); System.arraycopy(v, 0, nv, 0, at);
        nx[at] = (float) u; nv[at] = s;
        System.arraycopy(x, at, nx, at + 1, n - at); System.arraycopy(v, at, nv, at + 1, n - at);
        int[] ns = new int[n];   // the split stretch's shape goes to both halves
        System.arraycopy(seg, 0, ns, 0, at);
        ns[at - 1] = seg[at - 1];
        System.arraycopy(seg, at - 1, ns, at, n - at);
        if (seg[at - 1] == HOLD) nv[at] = v[at - 1];   // inside a hold the speed there is the hold's
        x = nx; v = nv; seg = ns;
        changed();
        return at;
    }

    /** Removes point i (never the two ends). */
    void removePoint(int i) {
        int n = x.length;
        if (i <= 0 || i >= n - 1) return;
        float[] nx = new float[n - 1], nv = new float[n - 1];
        System.arraycopy(x, 0, nx, 0, i); System.arraycopy(v, 0, nv, 0, i);
        System.arraycopy(x, i + 1, nx, i, n - i - 1); System.arraycopy(v, i + 1, nv, i, n - i - 1);
        int[] ns = new int[n - 2];   // the merged stretch keeps the shape of the one before the point
        System.arraycopy(seg, 0, ns, 0, i);
        System.arraycopy(seg, i + 1, ns, i, n - 2 - i);
        x = nx; v = nv; seg = ns;
        changed();
    }

    /** Moves point i: x stays between its neighbours (the ends stay at 0 and 1). */
    void movePoint(int i, double u, float speed) {
        int n = x.length;
        if (i > 0 && i < n - 1) x[i] = (float) Math.max(x[i - 1] + 0.01, Math.min(x[i + 1] - 0.01, u));
        v[i] = speed;
        changed();
    }

    /**
     * The part of the curve between source positions a and b (0..1), stretched to 0..1 (for a
     * split). A cubic cut anywhere is still a cubic: the cut ends take the curve's exact value
     * and slope there, the points inside keep theirs, so each half plays exactly like that
     * stretch of the original (same length, same footage at every moment).
     */
    SpeedCurve sub(double a, double b) {
        tangents();
        double w = b - a;
        int n = x.length;
        java.util.ArrayList<double[]> p = new java.util.ArrayList<double[]>();   // x', log2 speed
        java.util.ArrayList<Integer> sh = new java.util.ArrayList<Integer>();
        java.util.ArrayList<double[]> sl = new java.util.ArrayList<double[]>();  // per output stretch: slope at start, at end (per x')
        // points: a, the original points strictly inside, b
        java.util.ArrayList<Double> us = new java.util.ArrayList<Double>();
        java.util.ArrayList<Integer> orig = new java.util.ArrayList<Integer>();   // original point index, or -1 for a cut
        us.add(a); orig.add(-1);
        for (int i = 0; i < n; i++) if (x[i] > a + 1e-6 && x[i] < b - 1e-6) { us.add((double) x[i]); orig.add(i); }
        us.add(b); orig.add(-1);
        for (int k = 0; k < us.size(); k++) {
            double u = us.get(k);
            // an original point keeps its own speed (after a hold the next stretch starts from it)
            p.add(new double[]{(u - a) / w, orig.get(k) >= 0 ? lv[orig.get(k)] : logAndSlope(u)[0]});
        }
        for (int k = 0; k + 1 < us.size(); k++) {
            double u0 = us.get(k), u1 = us.get(k + 1), mid = (u0 + u1) / 2;
            int i = n == 1 ? 0 : segAt(mid);
            int shape = n == 1 ? SMOOTH : seg[i];
            sh.add(shape);
            if (shape == HOLD) { p.get(k)[1] = lv[i]; sl.add(new double[]{0, 0}); continue; }
            // slopes just inside this piece at both ends (the curve's own stretch i)
            double e = 1e-7;
            double s0 = slopeIn(i, u0 + e), s1 = slopeIn(i, u1 - e);
            sl.add(new double[]{s0 * w, s1 * w});
        }
        int m = p.size();
        float[] nx = new float[m], nv = new float[m], nst = new float[2 * (m - 1)];
        int[] nseg = new int[m - 1];
        for (int k = 0; k < m; k++) { nx[k] = (float) p.get(k)[0]; nv[k] = (float) Math.pow(2, p.get(k)[1]); }
        for (int k = 0; k < m - 1; k++) { nseg[k] = sh.get(k) == HOLD ? HOLD : SMOOTH; nst[2 * k] = (float) sl.get(k)[0]; nst[2 * k + 1] = (float) sl.get(k)[1]; }
        // a hold's end point keeps the next stretch's value; the hold itself is flat at its start value
        SpeedCurve c = new SpeedCurve(nx, nv, nseg);
        c.st = nst;
        return c;
    }

    /** Slope of log2(speed) per unit x inside stretch i at u (its cubic, extended if u is just outside). */
    private double slopeIn(int i, double u) {
        if (x.length == 1) return 0;
        double h = x[i + 1] - x[i];
        if (h <= 1e-6) return 0;
        double t = Math.max(0, Math.min(1, (u - x[i]) / h)), t2 = t * t;
        return ((6 * t2 - 6 * t) * lv[i] + (3 * t2 - 4 * t + 1) * h * a0[i] + (-6 * t2 + 6 * t) * lv[i + 1] + (3 * t2 - 2 * t) * h * a1[i]) / h;
    }

    long hash() {
        long h = 7;
        for (int i = 0; i < x.length; i++) h = h * 31 + Float.floatToIntBits(x[i]) * 17L + Float.floatToIntBits(v[i]) + (i < seg.length ? seg[i] * 1009L : 0);
        if (st != null) for (float f : st) h = h * 31 + Float.floatToIntBits(f);
        return h;
    }

    // ---------------------------------------------------------------- presets and files
    static final String[] PRESETS = {"Montage", "Hero", "Bullet", "Jump cut", "Flash in", "Flash out"};
    static final String[] PRESET_INFO = {
            "Fast, slow, fast, slow: the classic velocity edit",
            "Normal speed, a sudden slow-motion moment, back to normal",
            "Fast, an almost frozen moment in the middle, fast again",
            "Normal, a quick burst forward, normal",
            "Rushes in fast and settles to normal speed",
            "Starts normal and rushes out fast"};
    private static final float[][][] PRESET_PTS = {
            {{0, 1}, {0.25f, 3}, {0.5f, 0.4f}, {0.75f, 3}, {1, 0.5f}},
            {{0, 1}, {0.38f, 1}, {0.5f, 0.15f}, {0.62f, 1}, {1, 1}},
            {{0, 3}, {0.4f, 3}, {0.5f, 0.1f}, {0.6f, 3}, {1, 3}},
            {{0, 1}, {0.44f, 1}, {0.5f, 8}, {0.56f, 1}, {1, 1}},
            {{0, 6}, {0.3f, 6}, {0.65f, 1}, {1, 1}},
            {{0, 1}, {0.35f, 1}, {0.7f, 6}, {1, 6}}};

    static SpeedCurve preset(int i) {
        float[][] p = PRESET_PTS[i];
        float[] x = new float[p.length], v = new float[p.length];
        for (int k = 0; k < p.length; k++) { x[k] = p[k][0]; v[k] = p[k][1]; }
        return new SpeedCurve(x, v);
    }

    /** Index of the preset this curve is exactly, or -1 (custom). */
    int presetIndex() {
        for (int i = 0; i < PRESET_PTS.length; i++) {
            float[][] p = PRESET_PTS[i];
            if (p.length != x.length || !allSmooth() || st != null) continue;
            boolean same = true;
            for (int k = 0; k < p.length && same; k++) same = p[k][0] == x[k] && p[k][1] == v[k];
            if (same) return i;
        }
        return -1;
    }

    /**
     * Points as [x, speed] arrays. 3.15 curves with shapes or split slopes write
     * [x, speed, shape, slope at start, slope at end] per point (the last point's are 0): 3.14
     * read a third number as a point slope, so such projects are format 18 (3.14 refuses them).
     */
    /**
     * True if 3.14 can read this curve exactly: every stretch Smooth, and any split slopes
     * continuous at the points (a split of a smooth curve always is), written as point slopes.
     */
    boolean legacyForm() {
        if (!allSmooth()) return false;
        if (st == null) return true;
        for (int i = 1; i < x.length - 1; i++) if (Math.abs(st[2 * i - 1] - st[2 * i]) > 1e-4f * Math.max(1, Math.abs(st[2 * i]))) return false;
        return true;
    }

    JSONArray toJson() throws JSONException {
        if (legacyForm() && st != null) {   // the 3.14 form: [x, speed, point slope]
            JSONArray a = new JSONArray();
            for (int i = 0; i < x.length; i++) {
                JSONArray p = new JSONArray(); p.put((double) x[i]); p.put((double) v[i]);
                p.put((double) (i < x.length - 1 ? st[2 * i] : st[2 * i - 1]));
                a.put(p);
            }
            return a;
        }
        boolean rich = !legacyForm();
        JSONArray a = new JSONArray();
        for (int i = 0; i < x.length; i++) {
            JSONArray p = new JSONArray(); p.put((double) x[i]); p.put((double) v[i]);
            if (rich) {
                boolean last = i == x.length - 1;
                p.put(last ? 0 : seg[i]);
                p.put(last || st == null ? 0 : (double) st[2 * i]);
                p.put(last || st == null ? 0 : (double) st[2 * i + 1]);
            }
            a.put(p);
        }
        if (rich && st == null) {   // mark "no explicit slopes" with a 6th value on the first point
            a.getJSONArray(0).put(1);
        }
        return a;
    }

    /** Null if the array isn't a usable curve (fewer than 2 points). */
    static SpeedCurve fromJson(JSONArray a) {
        if (a == null || a.length() < 2) return null;
        try {
            int n = a.length();
            float[] x = new float[n], v = new float[n];
            int[] seg = new int[n - 1];
            float[] st = new float[2 * (n - 1)], pt = new float[n];
            boolean rich = true, oldTan = true, noSt = false;
            for (int i = 0; i < n; i++) {
                JSONArray p = a.getJSONArray(i);
                x[i] = (float) p.getDouble(0); v[i] = (float) p.getDouble(1);
                if (p.length() >= 5) {
                    if (i < n - 1) { seg[i] = p.getInt(2); st[2 * i] = (float) p.getDouble(3); st[2 * i + 1] = (float) p.getDouble(4); }
                    if (i == 0 && p.length() >= 6) noSt = true;
                    oldTan = false;
                } else {
                    rich = false;
                    if (p.length() == 3) pt[i] = (float) p.getDouble(2); else oldTan = false;
                }
            }
            if (rich) oldTan = false; else { seg = new int[n - 1]; }
            boolean sorted = true;
            for (int i = 1; i < n; i++) if (x[i] < x[i - 1]) sorted = false;
            SpeedCurve c = new SpeedCurve(x, v, seg);
            boolean finite = true;
            for (float f : st) if (Float.isNaN(f) || Float.isInfinite(f)) finite = false;
            for (float f : pt) if (Float.isNaN(f) || Float.isInfinite(f)) finite = false;
            if (sorted && finite) {
                if (rich && !noSt) c.st = st;
                else if (oldTan) {   // 3.14 point slopes: each stretch takes its two points' slopes
                    float[] s2 = new float[2 * (n - 1)];
                    for (int i = 0; i < n - 1; i++) { s2[2 * i] = pt[i]; s2[2 * i + 1] = pt[i + 1]; }
                    c.st = s2;
                }
            }
            return c;
        } catch (Exception e) { return null; }
    }
}
