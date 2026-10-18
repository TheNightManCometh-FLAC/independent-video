package com.christopher.strata;

/**
 * Selection maths on 8-bit coverage grids (0 = not selected, 255 = selected), row-major w x h.
 * Pure Java so it is tested on the desktop. Selections live at mask resolution in image space,
 * exactly like layer masks, so a selection becomes a mask with no resampling.
 */
final class SelOps {
    static final int NEW = 0, ADD = 1, SUBTRACT = 2, INTERSECT = 3;
    static final String[] MODE_NAMES = {"New", "Add", "Subtract", "Intersect"};

    /** Combines a new shape into the selection (cur may be null = nothing selected). In place on shape for NEW. */
    static byte[] combine(byte[] cur, byte[] shape, int mode) {
        if (cur == null || mode == NEW) {
            if (mode == SUBTRACT || mode == INTERSECT) return cur == null ? new byte[shape.length] : cur;
            return shape;
        }
        byte[] out = new byte[cur.length];
        for (int i = 0; i < out.length; i++) {
            int a = cur[i] & 255, b = shape[i] & 255, v;
            switch (mode) {
                case ADD: v = Math.max(a, b); break;
                case SUBTRACT: v = a * (255 - b) / 255; break;
                default: v = Math.min(a, b); break;   // INTERSECT
            }
            out[i] = (byte) v;
        }
        return out;
    }

    static void invert(byte[] a) { for (int i = 0; i < a.length; i++) a[i] = (byte) (255 - (a[i] & 255)); }

    static boolean empty(byte[] a) {
        for (byte b : a) if ((b & 255) > 2) return false;
        return true;
    }

    /** Bounding box {x0, y0, x1, y1} (inclusive) of coverage above 2/255, or null. */
    static int[] bounds(byte[] a, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((a[y * w + x] & 255) > 2) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    y1 = y;
                }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    /**
     * Grow (r > 0) or shrink (r < 0) by |r| px: a max / min filter over an octagon (within a pixel
     * of a disc). Outside the image counts as neither, so shrinking a full selection keeps it full.
     */
    static void grow(byte[] a, int w, int h, int r) {
        if (r == 0) return;
        boolean shrink = r < 0;
        if (shrink) invert(a);
        int k = Math.abs(r);
        // square (half-side a) then diamond (radius b) = an octagon. a + b = k on the axes and
        // a*sqrt2 + b/sqrt2 = k on the diagonals gives a = (sqrt2 - 1) k, b = k - a: round to within a pixel.
        int box = Math.round(k * 0.41421f);
        maxBox(a, w, h, box);
        maxDiamond(a, w, h, k - box);
        if (shrink) invert(a);
    }

    /** Separable sliding-window max, window 2r+1, O(n) per row/column via block prefix/suffix maxima. */
    static void maxBox(byte[] a, int w, int h, int r) {
        if (r <= 0) return;
        int n = Math.max(w, h);
        int[] in = new int[n], out = new int[n], pre = new int[n], suf = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) in[x] = a[y * w + x] & 255;
            max1(in, out, pre, suf, w, r);
            for (int x = 0; x < w; x++) a[y * w + x] = (byte) out[x];
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) in[y] = a[y * w + x] & 255;
            max1(in, out, pre, suf, h, r);
            for (int y = 0; y < h; y++) a[y * w + x] = (byte) out[y];
        }
    }

    /** van Herk / Gil-Werman: max over [i-r, i+r] for every i. */
    private static void max1(int[] in, int[] out, int[] pre, int[] suf, int n, int r) {
        int bs = 2 * r + 1;
        for (int i = 0; i < n; i++) pre[i] = (i % bs == 0) ? in[i] : Math.max(pre[i - 1], in[i]);
        for (int i = n - 1; i >= 0; i--) suf[i] = (i == n - 1 || (i + 1) % bs == 0) ? in[i] : Math.max(suf[i + 1], in[i]);
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - r), hi = Math.min(n - 1, i + r);
            if (lo / bs == hi / bs) out[i] = lo % bs == 0 ? pre[hi] : suf[lo];   // clipped window inside one block
            else out[i] = Math.max(suf[lo], pre[hi]);
        }
    }

    /** r steps of a 4-neighbour max (each step grows a diamond by 1 px). */
    private static void maxDiamond(byte[] a, int w, int h, int r) {
        byte[] t = new byte[a.length];
        for (int s = 0; s < r; s++) {
            System.arraycopy(a, 0, t, 0, a.length);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x, v = t[i] & 255;
                    if (x > 0) v = Math.max(v, t[i - 1] & 255);
                    if (x < w - 1) v = Math.max(v, t[i + 1] & 255);
                    if (y > 0) v = Math.max(v, t[i - w] & 255);
                    if (y < h - 1) v = Math.max(v, t[i + w] & 255);
                    a[i] = (byte) v;
                }
        }
    }

    /** Blur then re-harden: rounds jagged corners and removes specks smaller than ~r. */
    static void smooth(byte[] a, int w, int h, int r) {
        MaskOps.feather(a, w, h, Math.max(1, r));
        for (int i = 0; i < a.length; i++) {
            int v = a[i] & 255;
            int s = (v - 128) * 4 + 128;   // steepen around 50% so the edge stays soft but defined
            a[i] = (byte) Math.max(0, Math.min(255, s));
        }
    }

    /**
     * Magic wand on an RGB grid: pixels within tol (0..255, max channel difference) of the seed
     * colour. contiguous = flood fill from the seed (4-connected); otherwise every matching pixel.
     * Pixels whose colour is the "outside" marker (alpha 0 in argb) never match.
     */
    static byte[] wand(int[] argb, int w, int h, int sx, int sy, int tol, boolean contiguous) {
        byte[] out = new byte[w * h];
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) return out;
        int seed = argb[sy * w + sx];
        if ((seed >>> 24) == 0) return out;
        int sr = (seed >> 16) & 255, sg = (seed >> 8) & 255, sb = seed & 255;
        if (!contiguous) {
            for (int i = 0; i < out.length; i++) if (match(argb[i], sr, sg, sb, tol)) out[i] = (byte) 255;
            return out;
        }
        int[] stack = new int[Math.min(w * h, 1 << 16)];
        int sp = 0;
        stack[sp++] = sy * w + sx;
        out[sy * w + sx] = (byte) 255;
        while (sp > 0) {
            int p = stack[--sp];
            int x = p % w, y = p / w;
            int[] nb = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
            for (int q : nb) {
                if (q < 0 || out[q] != 0 || !match(argb[q], sr, sg, sb, tol)) continue;
                out[q] = (byte) 255;
                if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                stack[sp++] = q;
            }
        }
        return out;
    }

    private static boolean match(int c, int r, int g, int b, int tol) {
        if ((c >>> 24) == 0) return false;
        return Math.abs(((c >> 16) & 255) - r) <= tol && Math.abs(((c >> 8) & 255) - g) <= tol && Math.abs((c & 255) - b) <= tol;
    }

    /** Bilinear resize of a coverage grid (wand runs on a smaller grid than the mask). */
    static byte[] resize(byte[] a, int w, int h, int nw, int nh) {
        byte[] o = new byte[nw * nh];
        float sx = w / (float) nw, sy = h / (float) nh;
        for (int y = 0; y < nh; y++) {
            float fy = Math.max(0, (y + 0.5f) * sy - 0.5f);
            int y0 = Math.min(h - 1, (int) fy), y1 = Math.min(h - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < nw; x++) {
                float fx = Math.max(0, (x + 0.5f) * sx - 0.5f);
                int x0 = Math.min(w - 1, (int) fx), x1 = Math.min(w - 1, x0 + 1);
                float tx = fx - x0;
                float v = (a[y0 * w + x0] & 255) * (1 - tx) * (1 - ty) + (a[y0 * w + x1] & 255) * tx * (1 - ty)
                        + (a[y1 * w + x0] & 255) * (1 - tx) * ty + (a[y1 * w + x1] & 255) * tx * ty;
                o[y * nw + x] = (byte) Math.round(v);
            }
        }
        return o;
    }
}
