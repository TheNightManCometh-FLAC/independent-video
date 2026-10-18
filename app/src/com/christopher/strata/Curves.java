package com.christopher.strata;

/** Monotone cubic (Fritsch-Carlson) curves, baked to a half-float RGBA LUT. */
final class Curves {
    static final int N = 1024;

    /** Evaluate the curve through sorted points (x0,y0,x1,y1,...) at x. */
    static float eval(float[] p, float x) {
        int n = p.length / 2;
        if (x <= p[0]) return clamp(p[1]);
        if (x >= p[(n - 1) * 2]) return clamp(p[(n - 1) * 2 + 1]);
        float[] m = tangents(p);
        int k = 0;
        while (k < n - 2 && x > p[(k + 1) * 2]) k++;
        float x0 = p[k * 2], y0 = p[k * 2 + 1], x1 = p[k * 2 + 2], y1 = p[k * 2 + 3];
        float h = x1 - x0;
        if (h <= 1e-6f) return clamp(y1);
        float t = (x - x0) / h, t2 = t * t, t3 = t2 * t;
        float y = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[k]
                + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m[k + 1];
        return clamp(y);
    }

    static float[] tangents(float[] p) {
        int n = p.length / 2;
        float[] d = new float[Math.max(n - 1, 1)];
        float[] m = new float[n];
        for (int i = 0; i < n - 1; i++) {
            float h = p[i * 2 + 2] - p[i * 2];
            d[i] = h <= 1e-6f ? 0 : (p[i * 2 + 3] - p[i * 2 + 1]) / h;
        }
        if (n == 2) { m[0] = m[1] = d[0]; return m; }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = (d[i - 1] * d[i] <= 0) ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
            float a = m[i] / d[i], b = m[i + 1] / d[i];
            float s = a * a + b * b;
            if (s > 9) {
                float t = (float) (3 / Math.sqrt(s));
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
        return m;
    }

    /** RGBA half floats, N wide: r,g,b = channel curves, a = master curve. */
    static short[] buildLut(float[][] curve) {
        short[] out = new short[N * 4];
        int[] chan = {1, 2, 3, 0};
        for (int c = 0; c < 4; c++) {
            float[] p = curve[chan[c]];
            for (int i = 0; i < N; i++) out[i * 4 + c] = toHalf(eval(p, i / (float) (N - 1)));
        }
        return out;
    }

    static boolean isIdentity(float[] p) {
        return p.length == 4 && p[0] == 0 && p[1] == 0 && p[2] == 1 && p[3] == 1;
    }

    static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static short toHalf(float f) {
        int b = Float.floatToIntBits(f);
        int sign = (b >>> 16) & 0x8000;
        int val = (b & 0x7fffffff) + 0x1000;
        if (val >= 0x47800000) return (short) (sign | 0x7c00);
        if (val >= 0x38800000) return (short) (sign | ((val - 0x38000000) >>> 13));
        if (val < 0x33000000) return (short) sign;
        val = (b & 0x7fffffff) >>> 23;
        return (short) (sign | ((((b & 0x7fffff) | 0x800000) + (0x800000 >>> (val - 102))) >>> (126 - val)));
    }
}
