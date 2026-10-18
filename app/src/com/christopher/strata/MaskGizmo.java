package com.christopher.strata;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * On-picture handles for one mask shape, shared by the photo canvas and the video viewer. Works in
 * mask px through a Map to the screen, so it doesn't care how the picture is zoomed or framed.
 *
 *   Linear  start (full effect), end (none), middle (moves it)            as in 3.6
 *   Radial  centre, side (width and angle), top (height)                  as in 3.6
 *   Boxes   drag inside or the centre dot to move; the corner scales evenly; the side and bottom
 *           dots stretch one way; the knob on the stalk above turns it (snaps to 15° steps
 *           within 4°). Text, rectangles, polygons, stars, library and freehand shapes.
 */
final class MaskGizmo {
    interface Map {
        float[] toScreen(float mx, float my);
        float[] toMask(float sx, float sy);
    }

    static final int NONE = -1, CENTRE = 0, CORNER = 1, SIDE = 2, BOTTOM = 3, ROTATE = 4, INSIDE = 5;
    // linear: 0 start, 1 end, 2 middle; radial: 0 centre, 1 side, 2 top
    int handle = NONE;
    boolean moved;
    private float grabDx, grabDy, startX1, startY1, startLen, lastMx, lastMy;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // ---------------------------------------------------------------- geometry
    static float[] local(MaskStack.Comp c, float mx, float my) {
        double a = Math.toRadians(c.angle);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float qx = mx - c.x0, qy = my - c.y0;
        return new float[]{ca * qx + sa * qy, -sa * qx + ca * qy};
    }

    static float[] world(MaskStack.Comp c, float lx, float ly) {
        double a = Math.toRadians(c.angle);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        return new float[]{c.x0 + ca * lx - sa * ly, c.y0 + sa * lx + ca * ly};
    }

    /** Handle positions in mask px (boxes: centre, corner, side, bottom; the rotate knob is placed on screen). */
    static float[][] handles(MaskStack.Comp c) {
        if (c.type == MaskStack.LINEAR)
            return new float[][]{{c.x0, c.y0}, {c.x1, c.y1}, {(c.x0 + c.x1) / 2, (c.y0 + c.y1) / 2}};
        if (c.type == MaskStack.RADIAL) {
            double a = Math.toRadians(c.angle);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            return new float[][]{{c.x0, c.y0}, {c.x0 + ca * c.x1, c.y0 + sa * c.x1}, {c.x0 + sa * c.y1, c.y0 - ca * c.y1}};
        }
        return new float[][]{{c.x0, c.y0}, world(c, c.x1, c.y1), world(c, c.x1, 0), world(c, 0, c.y1)};
    }

    /** Screen position of the rotate knob: a stalk above the top edge, a fixed screen length. */
    private static float[] knob(MaskStack.Comp c, Map m) {
        float[] top = m.toScreen(world(c, 0, -c.y1)[0], world(c, 0, -c.y1)[1]);
        float[] cen = m.toScreen(c.x0, c.y0);
        float dx = top[0] - cen[0], dy = top[1] - cen[1];
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) { double a = Math.toRadians(c.angle); dx = (float) Math.sin(a); dy = (float) -Math.cos(a); len = 1; }
        float k = Ui.dp(30);
        return new float[]{top[0] + dx / len * k, top[1] + dy / len * k, top[0], top[1]};
    }

    // ---------------------------------------------------------------- touch
    /** A finger went down at screen (sx, sy): true if it took the shape (a handle or inside a box). */
    boolean grab(MaskStack.Comp c, float sx, float sy, Map m) {
        handle = NONE;
        moved = false;
        if (c == null) return false;
        float[][] h = handles(c);
        float best = Ui.dp(28);
        if (c.isBox()) {
            float[] k = knob(c, m);
            float d = (float) Math.hypot(sx - k[0], sy - k[1]);
            if (d < best) { best = d; handle = ROTATE; }
        }
        for (int i = 0; i < h.length; i++) {
            float[] s = m.toScreen(h[i][0], h[i][1]);
            float d = (float) Math.hypot(sx - s[0], sy - s[1]);
            if (d < best) { best = d; handle = i; }
        }
        float[] mp = m.toMask(sx, sy);
        if (handle == NONE && c.isBox()) {
            float[] l = local(c, mp[0], mp[1]);
            if (Math.abs(l[0]) <= c.x1 && Math.abs(l[1]) <= c.y1) handle = INSIDE;
        }
        if (handle == NONE) return false;
        lastMx = mp[0]; lastMy = mp[1];
        if (handle < h.length && handle != ROTATE) { grabDx = h[handle][0] - mp[0]; grabDy = h[handle][1] - mp[1]; }
        startX1 = c.x1; startY1 = c.y1;
        float[] l = local(c, mp[0] + grabDx, mp[1] + grabDy);
        startLen = (float) Math.max(1e-3, Math.hypot(l[0], l[1]));
        return true;
    }

    /** The finger moved; true if the shape changed (the caller starts an undo step on the first one). */
    boolean drag(MaskStack.Comp c, float sx, float sy, Map m) {
        if (c == null || handle == NONE) return false;
        moved = true;
        float[] mp = m.toMask(sx, sy);
        float px = mp[0] + grabDx, py = mp[1] + grabDy;
        if (c.type == MaskStack.LINEAR) {
            if (handle == 0) { c.x0 = px; c.y0 = py; }
            else if (handle == 1) { c.x1 = px; c.y1 = py; }
            else {
                float dx = px - (c.x0 + c.x1) / 2, dy = py - (c.y0 + c.y1) / 2;
                c.x0 += dx; c.y0 += dy; c.x1 += dx; c.y1 += dy;
            }
            if (Math.hypot(c.x1 - c.x0, c.y1 - c.y0) < 1) c.x1 = c.x0 + 1;
            return true;
        }
        if (c.type == MaskStack.RADIAL) {
            if (handle == 0) { c.x0 = px; c.y0 = py; }
            else if (handle == 1) {
                c.x1 = (float) Math.max(0.5, Math.hypot(px - c.x0, py - c.y0));
                c.angle = (float) Math.toDegrees(Math.atan2(py - c.y0, px - c.x0));
            } else c.y1 = (float) Math.max(0.5, Math.hypot(px - c.x0, py - c.y0));
            return true;
        }
        switch (handle) {
            case INSIDE:
                c.x0 += mp[0] - lastMx; c.y0 += mp[1] - lastMy;
                break;
            case CENTRE:
                c.x0 = px; c.y0 = py;
                break;
            case CORNER: {
                float[] l = local(c, px, py);
                float f = (float) Math.hypot(l[0], l[1]) / startLen;
                c.x1 = Math.max(1, startX1 * f); c.y1 = Math.max(1, startY1 * f);
                break;
            }
            case SIDE: c.x1 = Math.max(1, Math.abs(local(c, px, py)[0])); break;
            case BOTTOM: c.y1 = Math.max(1, Math.abs(local(c, px, py)[1])); break;
            case ROTATE: {
                float a = (float) Math.toDegrees(Math.atan2(mp[1] - c.y0, mp[0] - c.x0)) + 90;
                float snap = Math.round(a / 15f) * 15f;
                if (Math.abs(a - snap) < 4) a = snap;
                // keep the angle continuous with the current one (keyframes interpolate the number)
                while (a - c.angle > 180) a -= 360;
                while (a - c.angle < -180) a += 360;
                c.angle = a;
                break;
            }
        }
        lastMx = mp[0]; lastMy = mp[1];
        return true;
    }

    void release() { handle = NONE; }

    // ---------------------------------------------------------------- drawing
    void draw(Canvas cv, MaskStack.Comp c, Map m, int viewW, int viewH) {
        if (c == null) return;
        float[][] h = handles(c);
        float[][] s = new float[h.length][];
        for (int i = 0; i < h.length; i++) s[i] = m.toScreen(h[i][0], h[i][1]);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setPathEffect(null);
        if (c.type == MaskStack.LINEAR) {
            float dx = s[1][0] - s[0][0], dy = s[1][1] - s[0][1], len = (float) Math.max(1, Math.hypot(dx, dy));
            float L = (float) Math.hypot(viewW, viewH);
            float nx = -dy / len * L, ny = dx / len * L;
            int[] cols = {0xeeffffff, 0x88ffffff, 0x66ffffff};
            float[] wid = {Ui.dp(2), Ui.dp(1.5f), Ui.dp(1)};
            for (int i = 0; i < 3; i++) {
                line(cv, s[i][0] - nx, s[i][1] - ny, s[i][0] + nx, s[i][1] + ny, wid[i], cols[i]);
            }
        } else if (c.type == MaskStack.RADIAL) {
            for (int pass = 0; pass < 2; pass++) {
                float k = pass == 0 ? 1f : 1f - c.feather;
                if (pass == 1 && (c.feather < 0.02f || k < 0.02f)) break;
                path.reset();
                for (int i = 0; i <= 72; i++) {
                    double t = i * Math.PI * 2 / 72;
                    float[] w = world(c, (float) (Math.cos(t) * c.x1 * k), (float) (Math.sin(t) * c.y1 * k));
                    float[] q = m.toScreen(w[0], w[1]);
                    if (i == 0) path.moveTo(q[0], q[1]); else path.lineTo(q[0], q[1]);
                }
                outline(cv, pass == 0 ? Ui.dp(2) : Ui.dp(1), pass == 0 ? 0xeeffffff : 0x88ffffff);
            }
        } else {
            // the shape's own outline where it is a formula, the box always (dashed)
            path.reset();
            if (c.type == MaskStack.RECT) {
                float r = c.round * Math.min(c.x1, c.y1);
                int seg = 10;
                float[][] corners = {{c.x1 - r, -c.y1 + r, -90}, {c.x1 - r, c.y1 - r, 0}, {-c.x1 + r, c.y1 - r, 90}, {-c.x1 + r, -c.y1 + r, 180}};
                boolean first = true;
                for (float[] k : corners)
                    for (int i = 0; i <= seg; i++) {
                        double t = Math.toRadians(k[2] + 90.0 * i / seg);
                        float[] w = world(c, k[0] + (float) Math.cos(t) * r, k[1] + (float) Math.sin(t) * r);
                        float[] q = m.toScreen(w[0], w[1]);
                        if (first) { path.moveTo(q[0], q[1]); first = false; } else path.lineTo(q[0], q[1]);
                    }
                path.close();
                outline(cv, Ui.dp(1.5f), 0xeeffffff);
            } else if (c.type == MaskStack.POLY) {
                int n = c.sides * 2;
                for (int i = 0; i <= n; i++) {
                    double t = -Math.PI / 2 + i * Math.PI / c.sides;
                    float rr = i % 2 == 0 ? 1 : c.inner;
                    float[] w = world(c, (float) Math.cos(t) * rr * c.x1, (float) Math.sin(t) * rr * c.y1);
                    float[] q = m.toScreen(w[0], w[1]);
                    if (i == 0) path.moveTo(q[0], q[1]); else path.lineTo(q[0], q[1]);
                }
                outline(cv, Ui.dp(1.5f), 0xeeffffff);
            }
            path.reset();
            float[][] bx = {world(c, -c.x1, -c.y1), world(c, c.x1, -c.y1), world(c, c.x1, c.y1), world(c, -c.x1, c.y1)};
            for (int i = 0; i < 4; i++) {
                float[] q = m.toScreen(bx[i][0], bx[i][1]);
                if (i == 0) path.moveTo(q[0], q[1]); else path.lineTo(q[0], q[1]);
            }
            path.close();
            p.setPathEffect(new DashPathEffect(new float[]{Ui.dp(5), Ui.dp(4)}, 0));
            outline(cv, Ui.dp(1.2f), 0xccffffff);
            p.setPathEffect(null);
            float[] k = knob(c, m);
            line(cv, k[2], k[3], k[0], k[1], Ui.dp(1.2f), 0xccffffff);
            dot(cv, k[0], k[1], Ui.dp(7.5f), 0xff7fd0ff);
        }
        for (int i = 0; i < s.length; i++) {
            boolean main = c.type == MaskStack.LINEAR ? i == 2 : i == 0;
            int col = main ? Ui.ACCENT : (c.type == MaskStack.LINEAR && i == 1 ? 0xff333333 : 0xffffffff);
            dot(cv, s[i][0], s[i][1], c.isBox() && i > 0 ? Ui.dp(6.5f) : Ui.dp(7.5f), col);
        }
    }

    private void line(Canvas cv, float x0, float y0, float x1, float y1, float w, int col) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w + Ui.dp(1.5f));
        p.setColor(0x55000000);
        cv.drawLine(x0, y0, x1, y1, p);
        p.setStrokeWidth(w);
        p.setColor(col);
        cv.drawLine(x0, y0, x1, y1, p);
    }

    private void outline(Canvas cv, float w, int col) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w + Ui.dp(1.5f));
        p.setColor(0x55000000);
        cv.drawPath(path, p);
        p.setStrokeWidth(w);
        p.setColor(col);
        cv.drawPath(path, p);
    }

    private void dot(Canvas cv, float x, float y, float r, int col) {
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x88000000);
        cv.drawCircle(x, y, r + Ui.dp(2.5f), p);
        p.setColor(col);
        cv.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1.5f));
        p.setColor(0xffffffff);
        cv.drawCircle(x, y, r, p);
    }

    /** Freehand outline being drawn (screen px pairs), with a closing hint. */
    void drawLasso(Canvas cv, java.util.ArrayList<Float> pts) {
        if (pts.size() < 4) return;
        path.reset();
        path.moveTo(pts.get(0), pts.get(1));
        for (int i = 2; i + 1 < pts.size(); i += 2) path.lineTo(pts.get(i), pts.get(i + 1));
        outline(cv, Ui.dp(2), 0xeeffffff);
        p.setPathEffect(new DashPathEffect(new float[]{Ui.dp(4), Ui.dp(4)}, 0));
        line(cv, pts.get(pts.size() - 2), pts.get(pts.size() - 1), pts.get(0), pts.get(1), Ui.dp(1), 0x99ffffff);
        p.setPathEffect(null);
    }
}
