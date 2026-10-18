package com.christopher.strata;

import java.util.ArrayList;

/**
 * Heal/clone strokes. Everything is in image px. A stroke is a simplified polyline with a
 * radius and a source offset; the renderer draws it in chunks of at most 16 points, each with
 * up to 32 boundary sample points just outside the stroke (used by healing). Pure Java.
 */
final class Retouch {
    static final int MAX_PTS = 16, MAX_BND = 32;

    static final class Stroke {
        final float[] pts;
        final float rad, feather;
        final float offX, offY;
        final boolean heal;
        private ArrayList<float[][]> chunks;   // depends only on pts and rad, shared by copies

        Stroke(float[] pts, float rad, float feather, float offX, float offY, boolean heal) {
            this.pts = pts; this.rad = rad; this.feather = feather;
            this.offX = offX; this.offY = offY; this.heal = heal;
        }

        Stroke withOffset(float ox, float oy) {
            Stroke s = new Stroke(pts, rad, feather, ox, oy, heal);
            s.chunks = chunks;
            return s;
        }

        Stroke withHeal(boolean h) {
            Stroke s = new Stroke(pts, rad, feather, offX, offY, h);
            s.chunks = chunks;
            return s;
        }

        /**
         * The stroke carried through a similarity map (the target photo moved): points mapped, the
         * radius scaled by k, the source offset turned and scaled with the map's linear part.
         */
        Stroke transformed(double[] m, float k) {
            float[] p = new float[pts.length];
            for (int i = 0; i + 1 < pts.length; i += 2) {
                double x = pts[i], y = pts[i + 1];
                p[i] = (float) (m[0] * x + m[1] * y + m[2]);
                p[i + 1] = (float) (m[3] * x + m[4] * y + m[5]);
            }
            float ox = (float) (m[0] * offX + m[1] * offY), oy = (float) (m[3] * offX + m[4] * offY);
            return new Stroke(p, rad * k, feather, ox, oy, heal);
        }

        /** Each chunk: {points, boundary points}. */
        synchronized ArrayList<float[][]> chunks() {
            if (chunks == null) chunks = chunk(pts, rad);
            return chunks;
        }

        /** Destination box incl. the healing boundary: minx, miny, maxx, maxy. */
        float[] box() {
            float[] b = bbox(pts);
            float m = rad * 1.35f + 2;
            b[0] -= m; b[1] -= m; b[2] += m; b[3] += m;
            return b;
        }

        float[] centroid() {
            float x = 0, y = 0;
            int n = pts.length / 2;
            for (int i = 0; i < n; i++) { x += pts[i * 2]; y += pts[i * 2 + 1]; }
            return new float[]{x / n, y / n};
        }

        /** Distance from (x, y) to the stroke's centre line. */
        float distTo(float x, float y) { return polyDist(pts, x, y); }
    }

    // ------------------------------------------------------------------ geometry
    static float[] bbox(float[] p) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < p.length / 2; i++) {
            b[0] = Math.min(b[0], p[i * 2]); b[1] = Math.min(b[1], p[i * 2 + 1]);
            b[2] = Math.max(b[2], p[i * 2]); b[3] = Math.max(b[3], p[i * 2 + 1]);
        }
        return b;
    }

    static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 < 1e-9f ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        float ex = px - ax - dx * t, ey = py - ay - dy * t;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    static float polyDist(float[] p, float x, float y) {
        int n = p.length / 2;
        if (n == 1) return (float) Math.hypot(x - p[0], y - p[1]);
        float d = Float.MAX_VALUE;
        for (int i = 0; i < n - 1; i++)
            d = Math.min(d, segDist(x, y, p[i * 2], p[i * 2 + 1], p[i * 2 + 2], p[i * 2 + 3]));
        return d;
    }

    static float length(float[] p) {
        float l = 0;
        for (int i = 0; i < p.length / 2 - 1; i++)
            l += (float) Math.hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1]);
        return l;
    }

    /** Ramer-Douglas-Peucker on an x,y array. */
    static float[] simplify(float[] p, float tol) {
        int n = p.length / 2;
        if (n <= 2) return p.clone();
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        rdp(p, 0, n - 1, tol, keep);
        int c = 0;
        for (boolean k : keep) if (k) c++;
        float[] o = new float[c * 2];
        int j = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { o[j++] = p[i * 2]; o[j++] = p[i * 2 + 1]; }
        return o;
    }

    private static void rdp(float[] p, int a, int b, float tol, boolean[] keep) {
        if (b <= a + 1) return;
        float best = -1;
        int bi = -1;
        for (int i = a + 1; i < b; i++) {
            float d = segDist(p[i * 2], p[i * 2 + 1], p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1]);
            if (d > best) { best = d; bi = i; }
        }
        if (best > tol) {
            keep[bi] = true;
            rdp(p, a, bi, tol, keep);
            rdp(p, bi, b, tol, keep);
        }
    }

    /** Insert points so no segment is longer than maxSeg. */
    static float[] subdivide(float[] p, float maxSeg) {
        int n = p.length / 2;
        ArrayList<Float> o = new ArrayList<Float>();
        o.add(p[0]); o.add(p[1]);
        for (int i = 0; i < n - 1; i++) {
            float ax = p[i * 2], ay = p[i * 2 + 1], bx = p[i * 2 + 2], by = p[i * 2 + 3];
            int k = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / maxSeg));
            for (int j = 1; j <= k; j++) { o.add(ax + (bx - ax) * j / k); o.add(ay + (by - ay) * j / k); }
        }
        float[] r = new float[o.size()];
        for (int i = 0; i < r.length; i++) r[i] = o.get(i);
        return r;
    }

    /** Raw finger path (img px) -> stroke points: simplified, then evenly subdivided. */
    static float[] prepare(float[] raw, float rad) {
        if (raw.length <= 2) return raw.clone();
        float[] s = simplify(raw, Math.max(0.5f, rad * 0.2f));
        if (s.length == 4 && Math.hypot(s[2] - s[0], s[3] - s[1]) < rad * 0.3f) return new float[]{s[0], s[1]};
        return subdivide(s, rad * 4f);
    }

    static ArrayList<float[][]> chunk(float[] p, float rad) {
        ArrayList<float[][]> out = new ArrayList<float[][]>();
        int n = p.length / 2;
        if (n == 1) {
            out.add(new float[][]{p.clone(), boundary(p, rad, true, true, MAX_BND, 1.2f)});
            return out;
        }
        float maxLen = rad * 12f;
        int start = 0;
        while (start < n - 1) {
            int end = start;
            float len = 0;
            while (end < n - 1 && end - start < MAX_PTS - 1) {
                float seg = (float) Math.hypot(p[end * 2 + 2] - p[end * 2], p[end * 2 + 3] - p[end * 2 + 1]);
                if (len + seg > maxLen && end > start) break;
                len += seg;
                end++;
            }
            float[] cp = new float[(end - start + 1) * 2];
            System.arraycopy(p, start * 2, cp, 0, cp.length);
            out.add(new float[][]{cp, boundary(cp, rad, start == 0, end == n - 1, MAX_BND, 1.2f)});
            start = end;
        }
        return out;
    }

    /**
     * Points at distance rad*ring from the centre line: along both sides, plus round end caps
     * where the stroke really ends (an internal chunk joint gets no cap, since the stroke
     * continues there). Points that land inside the stroke (inner side of a sharp bend) are
     * dropped.
     */
    static float[] boundary(float[] p, float rad, boolean capS, boolean capE, int max, float ring) {
        int n = p.length / 2;
        float R = rad * ring;
        ArrayList<Float> b = new ArrayList<Float>();
        if (n == 1) {
            int k = Math.min(24, max);
            for (int i = 0; i < k; i++) {
                double a = 2 * Math.PI * i / k;
                b.add((float) (p[0] + R * Math.cos(a)));
                b.add((float) (p[1] + R * Math.sin(a)));
            }
            return toArray(b);
        }
        int capN = 5;
        int caps = (capS ? capN : 0) + (capE ? capN : 0);
        int perSide = Math.max(2, (max - caps) / 2);
        float L = length(p);
        for (int i = 0; i < perSide; i++) {
            float t = L * (i + 0.5f) / perSide;
            float[] pt = along(p, t);
            b.add(pt[0] - pt[3] * R); b.add(pt[1] + pt[2] * R);
            b.add(pt[0] + pt[3] * R); b.add(pt[1] - pt[2] * R);
        }
        if (capS) cap(b, p[0], p[1], p[0] - p[2], p[1] - p[3], R, capN);
        if (capE) cap(b, p[n * 2 - 2], p[n * 2 - 1], p[n * 2 - 2] - p[n * 2 - 4], p[n * 2 - 1] - p[n * 2 - 3], R, capN);
        ArrayList<Float> f = new ArrayList<Float>();
        for (int i = 0; i < b.size() / 2; i++) {
            float x = b.get(i * 2), y = b.get(i * 2 + 1);
            if (polyDist(p, x, y) >= rad * 1.05f && f.size() < max * 2) { f.add(x); f.add(y); }
        }
        return toArray(f);
    }

    private static void cap(ArrayList<Float> b, float x, float y, float dx, float dy, float R, int k) {
        float l = (float) Math.hypot(dx, dy);
        if (l < 1e-6f) { dx = 1; dy = 0; } else { dx /= l; dy /= l; }
        for (int i = 0; i < k; i++) {
            double a = Math.toRadians(-60 + 120.0 * i / (k - 1));
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            b.add(x + R * (dx * c - dy * s));
            b.add(y + R * (dx * s + dy * c));
        }
    }

    /** Point and unit tangent at arc length t: {x, y, tx, ty}. */
    static float[] along(float[] p, float t) {
        int n = p.length / 2;
        for (int i = 0; i < n - 1; i++) {
            float ax = p[i * 2], ay = p[i * 2 + 1], bx = p[i * 2 + 2], by = p[i * 2 + 3];
            float l = (float) Math.hypot(bx - ax, by - ay);
            if (t <= l || i == n - 2) {
                float f = l < 1e-6f ? 0 : Math.min(1, t / l);
                float tx = l < 1e-6f ? 1 : (bx - ax) / l, ty = l < 1e-6f ? 0 : (by - ay) / l;
                return new float[]{ax + (bx - ax) * f, ay + (by - ay) * f, tx, ty};
            }
            t -= l;
        }
        return new float[]{p[0], p[1], 1, 0};
    }

    private static float[] toArray(ArrayList<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    // ------------------------------------------------------------------ automatic source
    /** Reads the photo the retouch layer works on, in image px. */
    interface Sampler {
        /** 0xRRGGBB, or -1 outside the photo. */
        int rgb(float x, float y);
    }

    /**
     * Picks a source offset: tries 24 directions x 4 distances, rejects offsets whose source
     * overlaps the stroke or leaves the photo, and scores how well the source's surroundings
     * match the destination's (two rings just outside the stroke) plus how calm the source
     * interior is relative to the destination ring. Lowest score wins.
     */
    static float[] autoSource(float[] pts, float rad, Sampler s) {
        float[] dense = pts.length == 2 ? pts : subdivide(pts, rad * 0.5f);
        float[] r1 = boundary(pts, rad, true, true, 64, 1.25f);
        float[] r2 = boundary(pts, rad, true, true, 64, 1.7f);
        float[] inner = pts.length == 2 ? circle(pts[0], pts[1], rad * 0.6f, 12) : interiorSamples(dense, rad);
        // reference: colours just outside the destination
        int nr = r1.length / 2 + r2.length / 2;
        int[] ref = new int[nr];
        for (int i = 0; i < r1.length / 2; i++) ref[i] = s.rgb(r1[i * 2], r1[i * 2 + 1]);
        for (int i = 0; i < r2.length / 2; i++) ref[r1.length / 2 + i] = s.rgb(r2[i * 2], r2[i * 2 + 1]);
        double best = Double.MAX_VALUE;
        float bx = rad * 2.5f, by = 0;
        float[] mults = {2.3f, 3.1f, 4.3f, 6f};
        for (float m : mults) {
            for (int k = 0; k < 24; k++) {
                double a = 2 * Math.PI * k / 24;
                float ox = (float) (Math.cos(a) * m * rad), oy = (float) (Math.sin(a) * m * rad);
                if (overlaps(dense, ox, oy, rad * 2.05f)) continue;
                double sc = 0;
                int cnt = 0;
                boolean bad = false;
                for (int i = 0; i < nr && !bad; i++) {
                    float x = i < r1.length / 2 ? r1[i * 2] : r2[(i - r1.length / 2) * 2];
                    float y = i < r1.length / 2 ? r1[i * 2 + 1] : r2[(i - r1.length / 2) * 2 + 1];
                    int c = s.rgb(x + ox, y + oy);
                    if (c < 0) { bad = true; break; }
                    if (ref[i] < 0) continue;
                    sc += diff2(ref[i], c);
                    cnt++;
                }
                if (bad) continue;
                // interior of the source should resemble the destination's surroundings (texture, not a new object)
                double in = 0;
                int ic = 0;
                for (int i = 0; i < inner.length / 2; i++) {
                    int c = s.rgb(inner[i * 2] + ox, inner[i * 2 + 1] + oy);
                    if (c < 0) { bad = true; break; }
                    in += nearestDiff(c, ref);
                    ic++;
                }
                if (bad || cnt == 0) continue;
                double score = (sc / cnt + 0.6 * (ic > 0 ? in / ic : 0)) * (1 + 0.04 * m);
                if (score < best) { best = score; bx = ox; by = oy; }
            }
        }
        return new float[]{bx, by};
    }

    private static float[] circle(float x, float y, float r, int k) {
        float[] o = new float[k * 2 + 2];
        o[0] = x; o[1] = y;
        for (int i = 0; i < k; i++) {
            double a = 2 * Math.PI * i / k;
            o[2 + i * 2] = (float) (x + r * Math.cos(a));
            o[3 + i * 2] = (float) (y + r * Math.sin(a));
        }
        return o;
    }

    private static float[] interiorSamples(float[] dense, float rad) {
        int n = dense.length / 2, step = Math.max(1, n / 16);
        ArrayList<Float> o = new ArrayList<Float>();
        for (int i = 0; i < n; i += step) {
            o.add(dense[i * 2]); o.add(dense[i * 2 + 1]);
        }
        return toArray(o);
    }

    private static boolean overlaps(float[] dense, float ox, float oy, float minD) {
        int n = dense.length / 2;
        float m2 = minD * minD;
        for (int i = 0; i < n; i++) {
            float x = dense[i * 2] + ox, y = dense[i * 2 + 1] + oy;
            for (int j = 0; j < n; j++) {
                float dx = x - dense[j * 2], dy = y - dense[j * 2 + 1];
                if (dx * dx + dy * dy < m2) return true;
            }
        }
        return false;
    }

    private static double diff2(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255);
        int dg = ((a >> 8) & 255) - ((b >> 8) & 255);
        int db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }

    private static double nearestDiff(int c, int[] ref) {
        double best = Double.MAX_VALUE;
        for (int r : ref) if (r >= 0) best = Math.min(best, diff2(c, r));
        return best == Double.MAX_VALUE ? 0 : best;
    }
}
