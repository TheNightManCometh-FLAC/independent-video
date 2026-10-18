package com.christopher.strata;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signed-distance textures for mask shapes that aren't a formula: library shapes, freehand
 * outlines (pure Java) and text (drawn by the platform through TextSource, Android canvas on the
 * phone). The shape is drawn at twice the texture's resolution, the nearest pixel of the other kind is
 * propagated (8SSEDT), and the signed distance is averaged down 2 x 2. That
 * gives half-texel accurate edges, so the shader can scale a shape up, feather it, grow it or
 * outline it from one small texture: the text stays editable (words, font, spacing) and only a
 * change of those redraws it. Moving, sizing, rotating or animating a shape never does.
 *
 * Texture layout: the shape's box (its bounds, mapped to the component's half size x1, y1) sits in
 * the middle, sx x sy of the texture, with a margin of `spread` texels on every side; a texel holds
 * 0.5 - distance / (2 spread). Cached by content (LRU), shared by the preview, export and history.
 */
final class MaskRaster {
    private MaskRaster() {}

    static final class Sdf {
        final int w, h;
        final byte[] a;
        /** The box's share of the texture's width and height. */
        final float sx, sy;
        /** Texels of distance from 0.5 to 0 or 1. */
        final float spread;
        /** Platform texture image (android Bitmap), made once on first upload. */
        volatile Object image;

        Sdf(int w, int h, byte[] a, float sx, float sy, float spread) {
            this.w = w; this.h = h; this.a = a; this.sx = sx; this.sy = sy; this.spread = spread;
        }

        /** Bilinear sample at uv (0..1, texel centres at (i + 0.5) / w), clamped to the edge: as the GPU reads it. */
        float sample(float u, float v) {
            float fx = u * w - 0.5f, fy = v * h - 0.5f;
            if (fx < 0) fx = 0; else if (fx > w - 1) fx = w - 1;
            if (fy < 0) fy = 0; else if (fy > h - 1) fy = h - 1;
            int ix = (int) fx, iy = (int) fy;
            int ix1 = Math.min(ix + 1, w - 1), iy1 = Math.min(iy + 1, h - 1);
            float tx = fx - ix, ty = fy - iy;
            float top = (a[iy * w + ix] & 255) * (1 - tx) + (a[iy * w + ix1] & 255) * tx;
            float bot = (a[iy1 * w + ix] & 255) * (1 - tx) + (a[iy1 * w + ix1] & 255) * tx;
            return (top * (1 - ty) + bot * ty) / 255f;
        }

        /** Mask px of distance per unit of texel value, for a box of half size (x1, y1). */
        float unit(float x1, float y1) {
            float cw = sx * w, ch = sy * h;
            return 2 * spread * (float) Math.sqrt((2 * x1 / cw) * (2 * y1 / ch));
        }
    }

    /** Draws text coverage (the phone: TextRaster on an Android canvas). */
    interface TextSource {
        /** Width / height of the laid-out text block (with no margin). */
        float aspect(MaskStack.Comp c);
        /**
         * Coverage 0..255 of the text block scaled to exactly cw x ch px, drawn into a W x H plane
         * at (ox, oy). Null if it can't draw.
         */
        byte[] draw(MaskStack.Comp c, int W, int H, int ox, int oy, int cw, int ch);
    }

    static volatile TextSource text;

    /** Target texel count of the shape's box: ~300k (a square 548 x 548, a 3:1 word 949 x 316). */
    static final int AREA = 300_000;
    private static final int CACHE = 24;

    private static final LinkedHashMap<String, Sdf> cache = new LinkedHashMap<String, Sdf>(32, 0.75f, true);

    /** What the texture depends on (not where the shape is, how big, or how it's animated). */
    static String contentKey(MaskStack.Comp c) {
        if (c.type == MaskStack.SHAPE) {
            if (c.shape != null) return "s:" + c.shape;
            StringBuilder b = new StringBuilder("p:");
            if (c.pts != null) { b.append(c.pts.length).append(':'); b.append(java.util.Arrays.hashCode(c.pts)); }
            return b.toString();
        }
        return "t:" + c.font + '|' + c.weight + '|' + c.italic + '|' + c.tracking + '|' + c.leading + '|' + c.align + '|' + c.text;
    }

    static synchronized Sdf peek(MaskStack.Comp c) { return cache.get(contentKey(c)); }

    /** The distance texture of a SHAPE or TEXT component (built on first use), or null. */
    static Sdf get(MaskStack.Comp c) {
        if (!c.isRaster()) return null;
        String k = contentKey(c);
        synchronized (MaskRaster.class) {
            Sdf s = cache.get(k);
            if (s != null) return s;
        }
        Sdf s = build(c);
        if (s == null) return null;
        synchronized (MaskRaster.class) {
            cache.put(k, s);
            Iterator<Map.Entry<String, Sdf>> it = cache.entrySet().iterator();
            while (cache.size() > CACHE && it.hasNext()) { it.next(); it.remove(); }
        }
        return s;
    }

    /** Called (any thread) when a texture started by getNow(..., false) is ready: re-render. */
    static volatile Runnable onReady;
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "mask-sdf"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; }
            });
    private static final java.util.HashSet<String> pending = new java.util.HashSet<String>();
    private static int uidCounter;
    /** The texture each shape (by uid) last showed: what the preview keeps showing while a new one is built. */
    private static final LinkedHashMap<Integer, Sdf> lastById = new LinkedHashMap<Integer, Sdf>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Sdf> e) { return size() > 64; }
    };

    static synchronized int nextUid() { return ++uidCounter; }

    /**
     * The texture for c. wait = true builds it here if needed (export, CPU paths). wait = false
     * (the live preview) never blocks: a missing texture is built in the background (onReady
     * fires) and meanwhile the shape's previous texture is used, or nothing.
     */
    static Sdf getNow(final MaskStack.Comp c, boolean wait) {
        if (!c.isRaster()) return null;
        final String k = contentKey(c);
        synchronized (MaskRaster.class) {
            Sdf s = cache.get(k);
            if (s != null) { c.last = s; lastById.put(c.uid, s); return s; }
            if (!wait) {
                if (pending.add(k)) {
                    final MaskStack.Comp job = c.copy();
                    worker.execute(new Runnable() {
                        @Override public void run() {
                            Sdf made = null;
                            try { made = get(job); } catch (Throwable ignored) {}
                            synchronized (MaskRaster.class) { pending.remove(k); if (made != null) lastById.put(job.uid, made); }
                            Runnable r = onReady;
                            if (r != null) r.run();
                        }
                    });
                }
                Sdf prev = lastById.get(c.uid);
                if (prev != null) c.last = prev;
                return c.last;
            }
        }
        Sdf s = get(c);
        if (s != null) { c.last = s; synchronized (MaskRaster.class) { lastById.put(c.uid, s); } }
        return s;
    }

    /** Box size for an aspect: about AREA texels, long side <= 2048, short side >= 96. */
    static int[] boxSize(float aspect) {
        aspect = Math.max(1 / 21.3f, Math.min(21.3f, aspect));
        int cw = (int) Math.round(Math.sqrt(AREA * aspect)), ch = (int) Math.round(Math.sqrt(AREA / aspect));
        if (cw > 2048) { cw = 2048; ch = Math.max(96, Math.round(2048 / aspect)); }
        if (ch > 2048) { ch = 2048; cw = Math.max(96, Math.round(2048 * aspect)); }
        return new int[]{Math.max(8, cw), Math.max(8, ch)};
    }

    /** Natural width / height of a raster component's content (for placing it). */
    static float aspect(MaskStack.Comp c) {
        if (c.type == MaskStack.TEXT) { TextSource t = text; return t == null ? 3 : t.aspect(c); }
        if (c.shape != null) return MaskShapes.aspect(c.shape);
        return 1;
    }

    private static Sdf build(MaskStack.Comp c) {
        float aspect;
        float[][] polys = null;
        if (c.type == MaskStack.TEXT) {
            TextSource t = text;
            if (t == null) return null;
            aspect = t.aspect(c);
        } else {
            if (c.shape != null) polys = MaskShapes.unit(c.shape);
            else if (c.pts != null && c.pts.length >= 6) polys = new float[][]{c.pts};
            if (polys == null) return null;
            aspect = c.shape != null ? MaskShapes.aspect(c.shape) : 1;
        }
        int[] bs = boxSize(aspect);
        int cw = bs[0], ch = bs[1];
        int pad = Math.round(0.25f * Math.min(cw, ch) + 6);
        int w = cw + 2 * pad, h = ch + 2 * pad;
        int W = 2 * w, H = 2 * h;
        byte[] cov;
        if (polys != null) cov = fill(polys, W, H, 2 * pad, 2 * pad, 2 * cw, 2 * ch);
        else {
            cov = text.draw(c, W, H, 2 * pad, 2 * pad, 2 * cw, 2 * ch);
            if (cov == null) return null;
        }
        byte[] a = sdf(cov, W, H, pad);
        return new Sdf(w, h, a, cw / (float) w, ch / (float) h, pad);
    }

    /**
     * Fills closed polylines given in box units (-1..1) into a W x H plane, the box mapped to
     * (ox, oy, bw, bh). Nonzero winding, sampled at pixel centres: 255 inside, 0 outside.
     */
    static byte[] fill(float[][] polys, int W, int H, int ox, int oy, int bw, int bh) {
        byte[] out = new byte[W * H];
        int total = 0;
        for (float[] p : polys) total += p.length / 2;
        float[] xs = new float[total];
        int[] dir = new int[total];
        for (int y = 0; y < H; y++) {
            float py = (y + 0.5f - oy) / bh * 2 - 1;
            int n = 0;
            for (float[] p : polys) {
                int m = p.length / 2;
                for (int i = 0; i < m; i++) {
                    int j = (i + 1) % m;
                    float ay = p[2 * i + 1], by = p[2 * j + 1];
                    if ((ay <= py) == (by <= py)) continue;
                    float ax = p[2 * i], bx = p[2 * j];
                    float t = (py - ay) / (by - ay);
                    xs[n] = ax + (bx - ax) * t;
                    dir[n] = by > ay ? 1 : -1;
                    n++;
                }
            }
            if (n < 2) continue;
            // insertion sort by x (few crossings per row), carrying the winding direction
            for (int i = 1; i < n; i++) {
                float xv = xs[i]; int dv = dir[i]; int j = i - 1;
                while (j >= 0 && xs[j] > xv) { xs[j + 1] = xs[j]; dir[j + 1] = dir[j]; j--; }
                xs[j + 1] = xv; dir[j + 1] = dv;
            }
            int wind = 0;
            for (int i = 0; i < n - 1; i++) {
                wind += dir[i];
                if (wind == 0) continue;
                float x0 = (xs[i] + 1) / 2 * bw + ox, x1 = (xs[i + 1] + 1) / 2 * bw + ox;
                int a = Math.max(0, (int) Math.ceil(x0 - 0.5f)), b = Math.min(W - 1, (int) Math.ceil(x1 - 0.5f) - 1);
                for (int x = a; x <= b; x++) out[y * W + x] = (byte) 255;
            }
        }
        return out;
    }

    /**
     * For every pixel, the index of the nearest pixel centre of the other kind (inside vs outside),
     * by 8-point sequential propagation (8SSEDT: two raster sweeps, each with a back pass along the
     * row). One propagation serves both sides: a neighbour of the other kind is itself a candidate,
     * a neighbour of the same kind offers its own nearest. Returns the squared distances in
     * pixels (Integer.MAX_VALUE: none, a plane all one kind).
     */
    static int[] nearestOther(boolean[] in, int W, int H) {
        int N = W * H;
        int[] nx = new int[N], ny = new int[N], d2 = new int[N];
        java.util.Arrays.fill(d2, Integer.MAX_VALUE);
        // seeds: a pixel next to the other kind has it at distance 1 (4-neighbours) or 2 (diagonals)
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = y * W + x;
                boolean v = in[p];
                for (int k = 0; k < 8; k++) {
                    int qx = x + DX[k], qy = y + DY[k];
                    if (qx < 0 || qy < 0 || qx >= W || qy >= H || in[qy * W + qx] == v) continue;
                    int dd = DX[k] * DX[k] + DY[k] * DY[k];
                    if (dd < d2[p]) { d2[p] = dd; nx[p] = qx; ny[p] = qy; }
                }
            }
        // propagate the seeds' points (a pixel of either kind takes a same-kind neighbour's nearest)
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = y * W + x;
                if (x > 0) prop(in, nx, ny, d2, p, p - 1, x, y);
                if (y > 0) {
                    prop(in, nx, ny, d2, p, p - W, x, y);
                    if (x > 0) prop(in, nx, ny, d2, p, p - W - 1, x, y);
                    if (x < W - 1) prop(in, nx, ny, d2, p, p - W + 1, x, y);
                }
            }
            for (int x = W - 2; x >= 0; x--) { int p = y * W + x; prop(in, nx, ny, d2, p, p + 1, x, y); }
        }
        for (int y = H - 1; y >= 0; y--) {
            for (int x = W - 1; x >= 0; x--) {
                int p = y * W + x;
                if (x < W - 1) prop(in, nx, ny, d2, p, p + 1, x, y);
                if (y < H - 1) {
                    prop(in, nx, ny, d2, p, p + W, x, y);
                    if (x > 0) prop(in, nx, ny, d2, p, p + W - 1, x, y);
                    if (x < W - 1) prop(in, nx, ny, d2, p, p + W + 1, x, y);
                }
            }
            for (int x = 1; x < W; x++) { int p = y * W + x; prop(in, nx, ny, d2, p, p - 1, x, y); }
        }
        return d2;
    }

    private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1}, DY = {0, 0, -1, 1, -1, -1, 1, 1};

    private static void prop(boolean[] in, int[] nx, int[] ny, int[] d2, int p, int q, int x, int y) {
        if (d2[q] == Integer.MAX_VALUE || in[q] != in[p]) return;   // other kind: seeds cover it
        int cx = nx[q] - x, cy = ny[q] - y;
        int dd = cx * cx + cy * cy;
        if (dd < d2[p]) { d2[p] = dd; nx[p] = nx[q]; ny[p] = ny[q]; }
    }

    /**
     * Coverage at 2x (W x H) -> encoded signed distance at 1x (W/2 x H/2): distance to the
     * nearest pixel of the other kind minus half a pixel (the edge lies between centres), averaged
     * 2 x 2 and halved into 1x texels, then 0.5 - d / (2 spread).
     */
    static byte[] sdf(byte[] cov, int W, int H, float spread) {
        boolean[] in = new boolean[W * H];
        for (int i = 0; i < in.length; i++) in[i] = (cov[i] & 255) >= 128;
        int[] d2 = nearestOther(in, W, H);
        int w = W / 2, h = H / 2;
        byte[] a = new byte[w * h];
        float far = 4 * spread + 4;            // 2x px; beyond this the value is clamped anyway
        float far2 = (far + 0.5f) * (far + 0.5f);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0;
                int p0 = 2 * y * W + 2 * x;
                for (int j = 0; j < 2; j++)
                    for (int i = 0; i < 2; i++) {
                        int p = p0 + j * W + i;
                        int dd = d2[p];
                        float d = dd >= far2 ? far : (float) Math.sqrt(dd) - 0.5f;
                        s += in[p] ? -d : d;
                    }
                float d1 = s / 4 / 2;
                float e = 0.5f - d1 / (2 * spread);
                a[y * w + x] = (byte) Math.round(Math.max(0, Math.min(1, e)) * 255);
            }
        return a;
    }
}
