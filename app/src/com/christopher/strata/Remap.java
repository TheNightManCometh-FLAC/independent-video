package com.christopher.strata;

/**
 * Geometry effects as coordinate maps: output doc px -> source doc px. This is the Java twin of
 * map() in fx_remap.frag and must stay identical: the engine uses it to work out which part of
 * the image each export tile reads (bounds), then renders only that part as the source. Pure Java.
 */
final class Remap {
    private static final double TAU = Math.PI * 2;

    static double fract(double x) { return x - Math.floor(x); }

    static double mirrorRepeat(double x, double w) {
        double m = x - Math.floor(x / (2 * w)) * 2 * w;   // 0..2w
        return m > w ? 2 * w - m : m;
    }

    static double clamp(double x, double a, double b) { return x < a ? a : (x > b ? b : x); }

    /** Maps (x, y) in doc px for effect id with params p on a W x H doc. out = source doc px. */
    static void map(int id, float[] p, double W, double H, double x, double y, double[] out) {
        double S = Math.min(W, H), L = Math.max(W, H);
        double sx = x, sy = y;
        switch (id) {
            case Fx.MIRROR: {
                int axis = Math.round(p[0]);
                boolean second = p[1] > 0.5f;
                if (axis == 0 || axis == 2) {
                    double X = p[2] * W;
                    if (!second && x > X) sx = 2 * X - x;
                    if (second && x < X) sx = 2 * X - x;
                }
                if (axis == 1 || axis == 2) {
                    double Y = p[3] * H;
                    if (!second && y > Y) sy = 2 * Y - y;
                    if (second && y < Y) sy = 2 * Y - y;
                }
                sx = mirrorRepeat(sx, W);
                sy = mirrorRepeat(sy, H);
                break;
            }
            case Fx.FLIP: {
                int d = Math.round(p[0]);
                if (d == 0 || d == 2) sx = W - x;
                if (d == 1 || d == 2) sy = H - y;
                break;
            }
            case Fx.REFLECT: {
                double Y = p[0] * H;
                if (y > Y) {
                    double d = y - Y;
                    double A = p[2] * L, lam = Math.max(1e-3, p[3] * L);
                    double grow = d / Math.max(1, H - Y);
                    sx = x + A * Math.sin(TAU * d / lam) * grow;
                    sy = Y - d + A * 0.5 * Math.cos(TAU * d / (lam * 0.7)) * grow;
                    sx = mirrorRepeat(sx, W);
                    sy = mirrorRepeat(sy, H);
                }
                break;
            }
            case Fx.OFFSET: {
                double ux = x - p[0] * W, uy = y - p[1] * H;
                if (p[2] < 0.5f) { sx = ux - Math.floor(ux / W) * W; sy = uy - Math.floor(uy / H) * H; }
                else { sx = mirrorRepeat(ux, W); sy = mirrorRepeat(uy, H); }
                break;
            }
            case Fx.TILE: {
                double cols = Math.max(1, Math.round(p[0])), rows = Math.max(1, Math.round(p[1]));
                double u = x / W * cols, v = y / H * rows;
                double fu = fract(u), fv = fract(v);
                if (p[2] > 0.5f) {
                    if (Math.floorMod((long) Math.floor(u), 2L) != 0) fu = 1 - fu;   // GLSL mod(): sign of the divisor
                    if (Math.floorMod((long) Math.floor(v), 2L) != 0) fv = 1 - fv;
                }
                sx = fu * W;
                sy = fv * H;
                break;
            }
            case Fx.KALEIDO: {
                double cx = p[3] * W, cy = p[4] * H;
                double qx = (x - cx) / p[2], qy = (y - cy) / p[2];
                int n = Math.max(2, Math.round(p[0]));
                double seg = TAU / n, rot = Math.toRadians(p[1]);
                double r = Math.hypot(qx, qy);
                double a = Math.atan2(qy, qx) - rot;
                a = a - Math.floor(a / seg) * seg;
                if (a > seg / 2) a = seg - a;
                a += Math.toRadians(p[6]);
                qx = r * Math.cos(a);
                qy = r * Math.sin(a);
                int k = Math.round(p[5]);
                for (int i = 0; i < k; i++) {
                    qx = Math.abs(qx) - S * 0.18;
                    qy = Math.abs(qy) - S * 0.18;
                    double c = Math.cos(rot + 0.6), s = Math.sin(rot + 0.6);
                    double tx = c * qx - s * qy, ty = s * qx + c * qy;
                    qx = tx * 1.25;
                    qy = ty * 1.25;
                }
                sx = mirrorRepeat(cx + qx, W);
                sy = mirrorRepeat(cy + qy, H);
                break;
            }
            case Fx.POLAR: {
                double cx = p[3] * W, cy = p[4] * H, R = 0.5 * S * p[2];
                double rot = Math.toRadians(p[1]);
                if (p[0] < 0.5f) {                       // little planet: angle -> x, radius -> y
                    double qx = x - cx, qy = y - cy;
                    double a = Math.atan2(qx, -qy) + rot;
                    double r = Math.hypot(qx, qy);
                    sx = fract(a / TAU + 0.5) * W;
                    sy = clamp(H * (1 - r / R), 0, H);
                } else {                                 // unwrap: x -> angle, y -> radius
                    double a = (x / W - 0.5) * TAU - rot;
                    double r = (1 - y / H) * R;
                    sx = mirrorRepeat(cx + r * Math.sin(a), W);
                    sy = mirrorRepeat(cy - r * Math.cos(a), H);
                }
                break;
            }
            case Fx.TWIRL: {
                double cx = p[2] * W, cy = p[3] * H, R = p[1] * S;
                double qx = x - cx, qy = y - cy, r = Math.hypot(qx, qy);
                if (r < R) {
                    double t = 1 - r / R, th = Math.toRadians(p[0]) * t * t;
                    double c = Math.cos(th), s = Math.sin(th);
                    double tx = c * qx - s * qy, ty = s * qx + c * qy;
                    qx = tx; qy = ty;
                }
                sx = mirrorRepeat(cx + qx, W);
                sy = mirrorRepeat(cy + qy, H);
                break;
            }
            case Fx.PINCH: {
                double cx = p[2] * W, cy = p[3] * H, R = p[1] * S;
                double qx = x - cx, qy = y - cy, r = Math.hypot(qx, qy);
                if (r < R && r > 1e-6) {
                    double n = r / R, e = 1 + Math.max(-0.9, p[0]);
                    double k = Math.pow(n, e) / n;
                    qx *= k; qy *= k;
                }
                sx = mirrorRepeat(cx + qx, W);
                sy = mirrorRepeat(cy + qy, H);
                break;
            }
            case Fx.WAVE: {
                double A = p[0] * L, lam = Math.max(1e-3, p[1] * L), ph = Math.toRadians(p[2]);
                int st = Math.round(p[3]);
                if (st == 0 || st == 2) sx = x + A * Math.sin(TAU * y / lam + ph);
                if (st == 1 || st == 2) sy = y + A * Math.sin(TAU * x / lam + ph);
                if (st == 3) {
                    double qx = x - p[4] * W, qy = y - p[5] * H, r = Math.max(1e-6, Math.hypot(qx, qy));
                    double d = A * Math.sin(TAU * r / lam - ph);
                    sx = x + qx / r * d;
                    sy = y + qy / r * d;
                }
                sx = mirrorRepeat(sx, W);
                sy = mirrorRepeat(sy, H);
                break;
            }
            case Fx.DROSTE: {
                double cx = p[3] * W, cy = p[4] * H, s = clamp(p[0], 0.1, 0.9);
                double qx = x - cx, qy = y - cy;
                double rot = Math.toRadians(p[2]), c = Math.cos(rot), si = Math.sin(rot);
                double tx = c * qx - si * qy, ty = si * qx + c * qy;
                qx = tx; qy = ty;
                double hw = W * 0.5, hh = H * 0.5;
                if (p[1] > 0.5f) {                       // Escher spiral: complex log, rotate+scale, exp
                    double lr = Math.log(Math.max(1e-6, Math.hypot(qx / hw, qy / hh))), a = Math.atan2(qy / hh, qx / hw);
                    double f = Math.log(1 / s), al = Math.atan(f / TAU), ca = Math.cos(al);
                    double u = ca * (lr * Math.cos(al) - a * Math.sin(al)), v = ca * (lr * Math.sin(al) + a * Math.cos(al));
                    double m = Math.exp(u);
                    qx = m * Math.cos(v) * hw;
                    qy = m * Math.sin(v) * hh;
                }
                double z = Math.pow(s, p[5]);
                qx *= z; qy *= z;
                for (int i = 0; i < 24; i++) {
                    double nrm = Math.max(Math.abs(qx) / hw, Math.abs(qy) / hh);
                    if (nrm >= s) break;
                    qx /= s; qy /= s;
                }
                sx = mirrorRepeat(cx + qx, W);
                sy = mirrorRepeat(cy + qy, H);
                break;
            }
            default:
                break;
        }
        out[0] = sx;
        out[1] = sy;
    }

    /** Extra margin (doc px) for maps whose extremes fall between sample points. */
    private static double pad(int id, float[] p, double W, double H) {
        double L = Math.max(W, H);
        switch (id) {
            case Fx.WAVE: return p[0] * L * 1.1 + 4;
            case Fx.REFLECT: return p[2] * L * 1.1 + 4;
            default: return 4;
        }
    }

    /**
     * Source bounding box (doc px: x, y, w, h, clamped to the doc) of everything the region
     * (doc px x, y, w, h) reads, by sampling the map on a 17 x 17 grid (edges included).
     */
    static double[] bounds(int id, float[] p, double W, double H, double rx, double ry, double rw, double rh) {
        double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
        double[] o = new double[2];
        int n = 16;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                map(id, p, W, H, rx + rw * i / n, ry + rh * j / n, o);
                x0 = Math.min(x0, o[0]); x1 = Math.max(x1, o[0]);
                y0 = Math.min(y0, o[1]); y1 = Math.max(y1, o[1]);
            }
        }
        // a grid cell can hide a little extra spread: add one cell's worth of the bbox plus padding
        double pd = pad(id, p, W, H) + Math.max(x1 - x0, y1 - y0) / n;
        x0 = Math.max(0, x0 - pd); y0 = Math.max(0, y0 - pd);
        x1 = Math.min(W, x1 + pd); y1 = Math.min(H, y1 + pd);
        return new double[]{x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};
    }
}
