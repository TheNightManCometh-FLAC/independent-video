package com.christopher.strata;

/**
 * Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative). Arcs are converted
 * to cubic Béziers (SVG 1.1 implementation notes F.6). Pure Java: the same icon strings render
 * in Chromium for review and through this parser on the phone.
 */
final class SvgPath {
    interface Sink {
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);
        void close();
    }

    private final String s;
    private int i;

    private SvgPath(String s) { this.s = s; }

    static void parse(String d, Sink k) { new SvgPath(d).run(k); }

    private void skip() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++; else break;
        }
    }

    private boolean numberNext() {
        skip();
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
    }

    private float num() {
        skip();
        int st = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        boolean dot = false, exp = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { i++; continue; }
            if (c == '.' && !dot && !exp) { dot = true; i++; continue; }
            if ((c == 'e' || c == 'E') && !exp) {
                exp = true; i++;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                continue;
            }
            break;
        }
        return Float.parseFloat(s.substring(st, i));
    }

    /** Arc flags may be written without separators ("a1 1 0 01..."). */
    private boolean flag() {
        skip();
        char c = s.charAt(i++);
        return c == '1';
    }

    private void run(Sink k) {
        float cx = 0, cy = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;   // current, subpath start, last control
        char cmd = 0, prev = 0;
        while (true) {
            skip();
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') { cmd = c; i++; }
            else if (cmd == 0) throw new IllegalArgumentException("path data must start with a command");
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    cx = ox + num(); cy = oy + num();
                    sx = cx; sy = cy;
                    k.moveTo(cx, cy);
                    cmd = rel ? 'l' : 'L';   // further pairs are line-tos
                    prev = 'M';
                    lcx = cx; lcy = cy;
                    continue;
                }
                case 'L': cx = ox + num(); cy = oy + num(); k.lineTo(cx, cy); break;
                case 'H': cx = ox + num(); k.lineTo(cx, cy); break;
                case 'V': cy = (rel ? cy : 0) + num(); k.lineTo(cx, cy); break;
                case 'C': {
                    float x1 = ox + num(), y1 = oy + num(), x2 = ox + num(), y2 = oy + num();
                    cx = ox + num(); cy = oy + num();
                    k.cubicTo(x1, y1, x2, y2, cx, cy);
                    lcx = x2; lcy = y2;
                    prev = 'C';
                    continue;
                }
                case 'S': {
                    float x1 = cx, y1 = cy;
                    if (prev == 'C' || prev == 'S') { x1 = 2 * cx - lcx; y1 = 2 * cy - lcy; }
                    float x2 = ox + num(), y2 = oy + num();
                    cx = ox + num(); cy = oy + num();
                    k.cubicTo(x1, y1, x2, y2, cx, cy);
                    lcx = x2; lcy = y2;
                    prev = 'S';
                    continue;
                }
                case 'Q': {
                    float qx = ox + num(), qy = oy + num();
                    float nx = ox + num(), ny = oy + num();
                    quad(k, cx, cy, qx, qy, nx, ny);
                    cx = nx; cy = ny; lcx = qx; lcy = qy;
                    prev = 'Q';
                    continue;
                }
                case 'T': {
                    float qx = cx, qy = cy;
                    if (prev == 'Q' || prev == 'T') { qx = 2 * cx - lcx; qy = 2 * cy - lcy; }
                    float nx = ox + num(), ny = oy + num();
                    quad(k, cx, cy, qx, qy, nx, ny);
                    cx = nx; cy = ny; lcx = qx; lcy = qy;
                    prev = 'T';
                    continue;
                }
                case 'A': {
                    float rx = num(), ry = num(), rot = num();
                    boolean large = flag(), sweep = flag();
                    float nx = ox + num(), ny = oy + num();
                    arc(k, cx, cy, rx, ry, rot, large, sweep, nx, ny);
                    cx = nx; cy = ny;
                    break;
                }
                case 'Z': {
                    k.close();
                    cx = sx; cy = sy;
                    prev = 'Z';
                    lcx = cx; lcy = cy;
                    // a command letter must follow
                    skip();
                    if (i < s.length() && !Character.isLetter(s.charAt(i))) throw new IllegalArgumentException("number after Z");
                    cmd = 0;
                    continue;
                }
                default: throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            prev = Character.toUpperCase(cmd);
            lcx = cx; lcy = cy;
        }
    }

    private static void quad(Sink k, float x0, float y0, float qx, float qy, float x, float y) {
        k.cubicTo(x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y);
    }

    /** Endpoint arc -> centre parameterisation -> cubic segments of at most 90 degrees. */
    private static void arc(Sink k, float x0, float y0, float rx, float ry, float rotDeg, boolean large, boolean sweep, float x, float y) {
        if (x0 == x && y0 == y) return;
        rx = Math.abs(rx); ry = Math.abs(ry);
        if (rx == 0 || ry == 0) { k.lineTo(x, y); return; }
        double phi = Math.toRadians(rotDeg), cp = Math.cos(phi), sp = Math.sin(phi);
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double x1p = cp * dx + sp * dy, y1p = -sp * dx + cp * dy;
        double lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lam > 1) { double sq = Math.sqrt(lam); rx *= sq; ry *= sq; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
        double ccx = cp * cxp - sp * cyp + (x0 + x) / 2.0, ccy = sp * cxp + cp * cyp + (y0 + y) / 2.0;
        double t1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double t2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double dt = t2 - t1;
        if (sweep && dt < 0) dt += 2 * Math.PI;
        else if (!sweep && dt > 0) dt -= 2 * Math.PI;
        int n = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9);
        double seg = dt / n, kappa = 4.0 / 3.0 * Math.tan(seg / 4);
        double a = t1;
        for (int j = 0; j < n; j++) {
            double b = a + seg;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
            // points on the unit circle, scaled by radii, rotated, translated
            double p1x = ca - kappa * sa, p1y = sa + kappa * ca;
            double p2x = cb + kappa * sb, p2y = sb - kappa * cb;
            double[] q1 = map(p1x, p1y, rx, ry, cp, sp, ccx, ccy), q2 = map(p2x, p2y, rx, ry, cp, sp, ccx, ccy),
                    q3 = map(cb, sb, rx, ry, cp, sp, ccx, ccy);
            if (j == n - 1) { q3[0] = x; q3[1] = y; }
            k.cubicTo((float) q1[0], (float) q1[1], (float) q2[0], (float) q2[1], (float) q3[0], (float) q3[1]);
            a = b;
        }
    }

    private static double[] map(double ux, double uy, double rx, double ry, double cp, double sp, double cx, double cy) {
        double x = ux * rx, y = uy * ry;
        return new double[]{cp * x - sp * y + cx, sp * x + cp * y + cy};
    }
}
