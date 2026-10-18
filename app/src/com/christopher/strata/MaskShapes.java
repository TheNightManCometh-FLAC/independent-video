package com.christopher.strata;

import java.util.ArrayList;

/**
 * The library of mask shapes beyond the analytic ones (rectangle, polygon, star, ellipse): SVG
 * path data in a 100 x 100 box, or generated outlines. Each becomes closed polylines in box units
 * (-1..1 across the shape's own bounds) that MaskRaster turns into a distance texture. Ids are
 * stored in projects: never rename, only add. Pure Java.
 */
final class MaskShapes {
    private MaskShapes() {}

    static final String[] IDS = {"heart", "bolt", "arrow", "chevron", "cloud", "bubble", "drop", "moon",
            "cross", "flower", "ring", "shield", "leaf", "flame", "eye", "wavy", "splat", "gear", "burst", "frame"};
    static final String[] NAMES = {"Heart", "Lightning", "Arrow", "Chevron", "Cloud", "Speech bubble", "Drop", "Moon",
            "Cross", "Flower", "Ring", "Shield", "Leaf", "Flame", "Eye", "Wavy circle", "Splat", "Gear", "Burst", "Frame"};

    private static String circle(float cx, float cy, float r) {
        return "M" + (cx - r) + " " + cy + " A" + r + " " + r + " 0 1 0 " + (cx + r) + " " + cy
                + " A" + r + " " + r + " 0 1 0 " + (cx - r) + " " + cy + " Z ";
    }

    /** Same circle, drawn the other way round (a hole under the nonzero rule). */
    private static String hole(float cx, float cy, float r) {
        return "M" + (cx - r) + " " + cy + " A" + r + " " + r + " 0 1 1 " + (cx + r) + " " + cy
                + " A" + r + " " + r + " 0 1 1 " + (cx - r) + " " + cy + " Z ";
    }

    static String path(String id) {
        switch (id) {
            case "heart": return "M50 90 C20 70 3 52 3 31 C3 15 15 4 29 4 C39 4 46 10 50 18 C54 10 61 4 71 4 C85 4 97 15 97 31 C97 52 80 70 50 90 Z";
            case "bolt": return "M60 2 L16 56 L46 56 L36 98 L84 38 L54 38 L66 2 Z";
            case "arrow": return "M2 36 L56 36 L56 10 L98 50 L56 90 L56 64 L2 64 Z";
            case "chevron": return "M8 8 L48 8 L92 50 L48 92 L8 92 L52 50 Z";
            case "cloud": return "M24 80 C10 80 2 70 2 60 C2 49 11 41 22 41 C22 26 34 15 50 15 C63 15 73 23 76 34 C89 34 98 44 98 57 C98 70 88 80 76 80 Z";
            case "bubble": return "M16 8 L84 8 C91 8 96 13 96 20 L96 60 C96 67 91 72 84 72 L46 72 L24 94 L28 72 L16 72 C9 72 4 67 4 60 L4 20 C4 13 9 8 16 8 Z";
            case "drop": return "M50 2 C50 2 16 42 16 64 C16 83 31 98 50 98 C69 98 84 83 84 64 C84 42 50 2 50 2 Z";
            case "moon": return "M58 4 C34 6 14 26 14 50 C14 75 34 96 60 96 C76 96 89 89 97 77 C91 79 85 80 79 80 C56 80 38 62 38 40 C38 25 46 12 58 4 Z";
            case "cross": return "M36 4 L64 4 L64 36 L96 36 L96 64 L64 64 L64 96 L36 96 L36 64 L4 64 L4 36 L36 36 Z";
            case "flower": {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    double a = -Math.PI / 2 + i * 2 * Math.PI / 5;
                    b.append(circle((float) (50 + 27 * Math.cos(a)), (float) (50 + 27 * Math.sin(a)), 21));
                }
                return b.append(circle(50, 50, 22)).toString();
            }
            case "ring": return circle(50, 50, 48) + hole(50, 50, 30);
            case "shield": return "M50 3 L91 16 L91 46 C91 71 73 88 50 97 C27 88 9 71 9 46 L9 16 Z";
            case "leaf": return "M8 92 C8 44 36 10 94 6 C92 62 62 92 8 92 Z";
            case "flame": return "M50 2 C56 22 82 34 82 64 C82 84 68 98 50 98 C32 98 18 84 18 64 C18 48 28 38 33 26 C37 38 43 44 48 46 C45 32 43 16 50 2 Z";
            case "eye": return "M2 50 C22 16 78 16 98 50 C78 84 22 84 2 50 Z";
            case "frame": return "M4 4 L96 4 L96 96 L4 96 Z M20 20 L20 80 L80 80 L80 20 Z";
            default: return null;
        }
    }

    static boolean exists(String id) {
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    static String name(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return "Shape";
    }

    /** Generated outlines (100 x 100 box), or null for path shapes. */
    private static float[][] generated(String id) {
        int n;
        switch (id) {
            case "wavy": {
                n = 240;
                float[] p = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    double t = i * 2 * Math.PI / n, r = 44 + 5 * Math.cos(12 * t);
                    p[2 * i] = (float) (50 + r * Math.cos(t)); p[2 * i + 1] = (float) (50 + r * Math.sin(t));
                }
                return new float[][]{p};
            }
            case "splat": {
                // a fixed, hand-tuned set of lobes and spikes (deterministic: same shape everywhere)
                double[] lobe = {1.0, 0.72, 1.1, 0.8, 0.95, 0.66, 1.15, 0.78, 1.02, 0.7, 0.9, 0.84, 1.08, 0.62};
                n = 420;
                float[] p = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    double t = i * 2 * Math.PI / n, u = t / (2 * Math.PI) * lobe.length;
                    int a = (int) Math.floor(u) % lobe.length, b = (a + 1) % lobe.length;
                    double f = u - Math.floor(u), s = f * f * (3 - 2 * f);
                    double r = 30 * (lobe[a] + (lobe[b] - lobe[a]) * s);
                    double spike = Math.pow(Math.max(0, Math.cos(t * 9 + 0.7)), 18) * 16 + Math.pow(Math.max(0, Math.cos(t * 5 + 2.1)), 30) * 12;
                    r += spike + 2.5 * Math.sin(t * 23);
                    p[2 * i] = (float) (50 + r * Math.cos(t)); p[2 * i + 1] = (float) (50 + r * Math.sin(t));
                }
                return new float[][]{p};
            }
            case "gear": {
                int teeth = 12;
                n = teeth * 4;
                float[] p = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    double t = i * 2 * Math.PI / n - Math.PI / n * 0.5;
                    double r = (i % 4 == 0 || i % 4 == 1) ? 48 : 38;
                    p[2 * i] = (float) (50 + r * Math.cos(t)); p[2 * i + 1] = (float) (50 + r * Math.sin(t));
                }
                float[] h = new float[96 * 2];   // the axle hole, clockwise the other way
                for (int i = 0; i < 96; i++) {
                    double t = -i * 2 * Math.PI / 96;
                    h[2 * i] = (float) (50 + 14 * Math.cos(t)); h[2 * i + 1] = (float) (50 + 14 * Math.sin(t));
                }
                return new float[][]{p, h};
            }
            case "burst": {
                n = 48;
                float[] p = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    double t = -Math.PI / 2 + i * 2 * Math.PI / n, r = i % 2 == 0 ? 49 : 36;
                    p[2 * i] = (float) (50 + r * Math.cos(t)); p[2 * i + 1] = (float) (50 + r * Math.sin(t));
                }
                return new float[][]{p};
            }
            default: return null;
        }
    }

    /** Closed polylines for a library shape in its 100 x 100 design box. */
    static float[][] design(String id) {
        float[][] g = generated(id);
        if (g != null) return g;
        String d = path(id);
        if (d == null) return null;
        final ArrayList<float[]> polys = new ArrayList<float[]>();
        final ArrayList<Float> cur = new ArrayList<Float>();
        final float[] last = new float[2];
        SvgPath.parse(d, new SvgPath.Sink() {
            void flush() {
                if (cur.size() >= 6) {
                    float[] a = new float[cur.size()];
                    for (int i = 0; i < a.length; i++) a[i] = cur.get(i);
                    polys.add(a);
                }
                cur.clear();
            }
            @Override public void moveTo(float x, float y) { flush(); cur.add(x); cur.add(y); last[0] = x; last[1] = y; }
            @Override public void lineTo(float x, float y) { cur.add(x); cur.add(y); last[0] = x; last[1] = y; }
            @Override public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
                float x0 = last[0], y0 = last[1];
                for (int i = 1; i <= 16; i++) {
                    float t = i / 16f, u = 1 - t;
                    cur.add(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x);
                    cur.add(u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y);
                }
                last[0] = x; last[1] = y;
            }
            @Override public void close() { flush(); }
        });
        if (cur.size() >= 6) {
            float[] a = new float[cur.size()];
            for (int i = 0; i < a.length; i++) a[i] = cur.get(i);
            polys.add(a);
        }
        return polys.toArray(new float[0][]);
    }

    /** Bounds of polylines: minX, minY, maxX, maxY. */
    static float[] bounds(float[][] polys) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] p : polys) for (int i = 0; i + 1 < p.length; i += 2) {
            b[0] = Math.min(b[0], p[i]); b[1] = Math.min(b[1], p[i + 1]);
            b[2] = Math.max(b[2], p[i]); b[3] = Math.max(b[3], p[i + 1]);
        }
        return b;
    }

    /** A library shape fitted to box units (-1..1 across its bounds on both axes). */
    static float[][] unit(String id) {
        float[][] d = design(id);
        if (d == null) return null;
        return fit(d);
    }

    static float[][] fit(float[][] d) {
        float[] b = bounds(d);
        float cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        float hx = Math.max(1e-3f, (b[2] - b[0]) / 2), hy = Math.max(1e-3f, (b[3] - b[1]) / 2);
        float[][] o = new float[d.length][];
        for (int k = 0; k < d.length; k++) {
            o[k] = new float[d[k].length];
            for (int i = 0; i + 1 < d[k].length; i += 2) {
                o[k][i] = (d[k][i] - cx) / hx;
                o[k][i + 1] = (d[k][i + 1] - cy) / hy;
            }
        }
        return o;
    }

    /** Width / height of a library shape's bounds (how it is first placed). */
    static float aspect(String id) {
        float[][] d = design(id);
        if (d == null) return 1;
        float[] b = bounds(d);
        return Math.max(0.05f, (b[2] - b[0]) / Math.max(1e-3f, b[3] - b[1]));
    }

    /**
     * A freehand outline (screen-order points in mask px) as a SHAPE: returns
     * {cx, cy, hx, hy, pts...} with pts in box units, or null if it encloses nothing.
     * The outline is simplified (Ramer-Douglas-Peucker, tol px) and capped at 400 points.
     */
    static float[] freehand(float[] pts, float tol) {
        if (pts == null || pts.length < 8) return null;
        float[] s = simplify(pts, tol);
        if (s.length < 6) return null;
        float[] b = bounds(new float[][]{s});
        float hx = (b[2] - b[0]) / 2, hy = (b[3] - b[1]) / 2;
        if (hx < 2 || hy < 2) return null;
        float cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        float[] o = new float[4 + s.length];
        o[0] = cx; o[1] = cy; o[2] = hx; o[3] = hy;
        for (int i = 0; i + 1 < s.length; i += 2) { o[4 + i] = (s[i] - cx) / hx; o[5 + i] = (s[i + 1] - cy) / hy; }
        return o;
    }

    static float[] simplify(float[] p, float tol) {
        int n = p.length / 2;
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        rdp(p, 0, n - 1, tol, keep);
        int c = 0;
        for (boolean k : keep) if (k) c++;
        int step = Math.max(1, (c + 399) / 400);
        float[] o = new float[((c + step - 1) / step) * 2];
        int j = 0, seen = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { if (seen++ % step == 0 && j + 1 < o.length) { o[j++] = p[2 * i]; o[j++] = p[2 * i + 1]; } }
        if (j < o.length) { float[] t = new float[j]; System.arraycopy(o, 0, t, 0, j); o = t; }
        return o;
    }

    private static void rdp(float[] p, int a, int b, float tol, boolean[] keep) {
        if (b <= a + 1) return;
        float ax = p[2 * a], ay = p[2 * a + 1], bx = p[2 * b], by = p[2 * b + 1];
        float dx = bx - ax, dy = by - ay, len = (float) Math.max(1e-6, Math.hypot(dx, dy));
        int best = -1;
        float bd = tol;
        for (int i = a + 1; i < b; i++) {
            float d = Math.abs((p[2 * i] - ax) * dy - (p[2 * i + 1] - ay) * dx) / len;
            if (d > bd) { bd = d; best = i; }
        }
        if (best < 0) return;
        keep[best] = true;
        rdp(p, a, best, tol, keep);
        rdp(p, best, b, tol, keep);
    }
}
