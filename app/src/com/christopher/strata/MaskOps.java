package com.christopher.strata;

/** Mask processing on 8-bit planes: feather (gaussian-like blur) and refine edge (fast guided filter). Pure Java. */
final class MaskOps {

    // ---- affine maps (a, b, c, d, e, f): x' = a x + b y + c, y' = d x + e y + f ----
    static double[] invert(double[] m) {
        double det = m[0] * m[4] - m[1] * m[3];
        if (Math.abs(det) < 1e-12) det = det < 0 ? -1e-12 : 1e-12;
        double a = m[4] / det, b = -m[1] / det, d = -m[3] / det, e = m[0] / det;
        return new double[]{a, b, -(a * m[2] + b * m[5]), d, e, -(d * m[2] + e * m[5])};
    }

    /** p then q: x -> q(p(x)). */
    static double[] then(double[] p, double[] q) {
        return new double[]{
                q[0] * p[0] + q[1] * p[3], q[0] * p[1] + q[1] * p[4], q[0] * p[2] + q[1] * p[5] + q[2],
                q[3] * p[0] + q[4] * p[3], q[3] * p[1] + q[4] * p[4], q[3] * p[2] + q[4] * p[5] + q[5]};
    }

    /** Length scale of an affine map (similarities: the exact scale). */
    static double scaleOf(double[] m) { return Math.sqrt(Math.abs(m[0] * m[4] - m[1] * m[3])); }

    /**
     * Resamples an 8-bit plane through an affine map: dst pixel (x, y) takes src at
     * dstToSrc(x + 0.5, y + 0.5), bilinear, edges clamped. That is exactly how the GPU samples a
     * mask texture (CLAMP_TO_EDGE, LINEAR), so a resampled mask renders like the original.
     */
    static byte[] warp(byte[] src, int sw, int sh, int dw, int dh, double[] dstToSrc) {
        byte[] out = new byte[dw * dh];
        double[] m = dstToSrc;
        for (int y = 0; y < dh; y++) {
            double yc = y + 0.5;
            double sx0 = m[1] * yc + m[2] - 0.5, sy0 = m[4] * yc + m[5] - 0.5;
            for (int x = 0; x < dw; x++) {
                double xc = x + 0.5;
                double fx = m[0] * xc + sx0, fy = m[3] * xc + sy0;
                if (fx < 0) fx = 0; else if (fx > sw - 1) fx = sw - 1;
                if (fy < 0) fy = 0; else if (fy > sh - 1) fy = sh - 1;
                int ix = (int) fx, iy = (int) fy;
                int ix1 = Math.min(ix + 1, sw - 1), iy1 = Math.min(iy + 1, sh - 1);
                double tx = fx - ix, ty = fy - iy;
                int r0 = iy * sw, r1 = iy1 * sw;
                double top = (src[r0 + ix] & 255) * (1 - tx) + (src[r0 + ix1] & 255) * tx;
                double bot = (src[r1 + ix] & 255) * (1 - tx) + (src[r1 + ix1] & 255) * tx;
                out[y * dw + x] = (byte) (int) (top * (1 - ty) + bot * ty + 0.5);
            }
        }
        return out;
    }

    /** Three box passes in each direction approximate a gaussian with sigma ~ r. */
    static void feather(byte[] a, int w, int h, int r) {
        if (r < 1) return;
        int br = Math.max(1, Math.round(r * 0.9f));
        int[] line = new int[Math.max(w, h)];
        int[] tmp = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) line[x] = a[y * w + x] & 255;
                box1(line, tmp, w, br);
                for (int x = 0; x < w; x++) a[y * w + x] = (byte) tmp[x];
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) line[y] = a[y * w + x] & 255;
                box1(line, tmp, h, br);
                for (int y = 0; y < h; y++) a[y * w + x] = (byte) tmp[y];
            }
        }
    }

    /** Running-sum box filter of a line, normalised by the samples actually inside. */
    private static void box1(int[] in, int[] out, int n, int r) {
        long s = 0;
        int lo = 0, hi = -1;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - r), b = Math.min(n - 1, i + r);
            while (hi < b) s += in[++hi];
            while (lo < a) s -= in[lo++];
            out[i] = (int) ((s + (b - a + 1) / 2) / (b - a + 1));
        }
    }

    private static void boxF(float[] in, float[] out, int w, int h, int r) {
        float[] tmp = new float[w * h];
        for (int y = 0; y < h; y++) {
            double s = 0;
            int lo = 0, hi = -1, o = y * w;
            for (int x = 0; x < w; x++) {
                int a = Math.max(0, x - r), b = Math.min(w - 1, x + r);
                while (hi < b) s += in[o + ++hi];
                while (lo < a) s -= in[o + lo++];
                tmp[o + x] = (float) (s / (b - a + 1));
            }
        }
        for (int x = 0; x < w; x++) {
            double s = 0;
            int lo = 0, hi = -1;
            for (int y = 0; y < h; y++) {
                int a = Math.max(0, y - r), b = Math.min(h - 1, y + r);
                while (hi < b) s += tmp[++hi * w + x];
                while (lo < a) s -= tmp[lo++ * w + x];
                out[y * w + x] = (float) (s / (b - a + 1));
            }
        }
    }

    /**
     * Fast guided filter (He et al. 2010, He and Sun 2015): the mask is re-fit as a local linear
     * function of the photo's luminance, so a rough mask edge snaps to the real edge (hair, leaves).
     * r: window radius in mask px; eps: edge sensitivity (smaller follows the photo more closely).
     */
    static byte[] refine(byte[] guide, byte[] mask, int w, int h, int r, float eps) {
        int s = Math.max(1, Math.round(r / 4f));
        int lw = (w + s - 1) / s, lh = (h + s - 1) / s;
        int rl = Math.max(1, Math.round(r / (float) s));
        float[] I = new float[lw * lh], P = new float[lw * lh], cnt = new float[lw * lh];
        for (int y = 0; y < h; y++) {
            int ly = (y / s) * lw;
            for (int x = 0; x < w; x++) {
                int i = ly + x / s;
                I[i] += (guide[y * w + x] & 255) / 255f;
                P[i] += (mask[y * w + x] & 255) / 255f;
                cnt[i]++;
            }
        }
        for (int i = 0; i < I.length; i++) { I[i] /= cnt[i]; P[i] /= cnt[i]; }
        float[] II = new float[I.length], IP = new float[I.length];
        for (int i = 0; i < I.length; i++) { II[i] = I[i] * I[i]; IP[i] = I[i] * P[i]; }
        float[] mI = new float[I.length], mP = new float[I.length], mII = new float[I.length], mIP = new float[I.length];
        boxF(I, mI, lw, lh, rl);
        boxF(P, mP, lw, lh, rl);
        boxF(II, mII, lw, lh, rl);
        boxF(IP, mIP, lw, lh, rl);
        float[] A = II, B = IP;   // reuse
        for (int i = 0; i < I.length; i++) {
            float var = mII[i] - mI[i] * mI[i], cov = mIP[i] - mI[i] * mP[i];
            A[i] = cov / (var + eps);
            B[i] = mP[i] - A[i] * mI[i];
        }
        float[] band = mP.clone();   // local mean of the rough mask: 0 or 1 away from its edge
        float[] mA = mI, mB = mP;
        boxF(A, mA, lw, lh, rl);
        boxF(B, mB, lw, lh, rl);
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / s - 0.5f;
            int y0 = Math.max(0, Math.min(lh - 1, (int) Math.floor(fy)));
            int y1 = Math.min(lh - 1, y0 + 1);
            float ty = Math.max(0, Math.min(1, fy - y0));
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / s - 0.5f;
                int x0 = Math.max(0, Math.min(lw - 1, (int) Math.floor(fx)));
                int x1 = Math.min(lw - 1, x0 + 1);
                float tx = Math.max(0, Math.min(1, fx - x0));
                float a = lerp(lerp(mA[y0 * lw + x0], mA[y0 * lw + x1], tx), lerp(mA[y1 * lw + x0], mA[y1 * lw + x1], tx), ty);
                float b = lerp(lerp(mB[y0 * lw + x0], mB[y0 * lw + x1], tx), lerp(mB[y1 * lw + x0], mB[y1 * lw + x1], tx), ty);
                float mb = lerp(lerp(band[y0 * lw + x0], band[y0 * lw + x1], tx), lerp(band[y1 * lw + x0], band[y1 * lw + x1], tx), ty);
                float orig = (mask[y * w + x] & 255) / 255f;
                float q = a * ((guide[y * w + x] & 255) / 255f) + b;
                q = (q - 0.5f) * 1.25f + 0.5f;                       // a touch of edge contrast
                // only the band around the rough edge is refit; solid inside/outside stays as painted
                float k = Math.min(1f, Math.min(mb, 1f - mb) * 12f);
                q = orig + (q - orig) * k;
                out[y * w + x] = (byte) Math.max(0, Math.min(255, Math.round(q * 255)));
            }
        }
        return out;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
