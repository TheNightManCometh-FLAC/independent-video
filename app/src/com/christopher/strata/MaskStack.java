package com.christopher.strata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Editable mask components (3.6, audit package 5), modelled on Lightroom's masking: a mask is an
 * ordered stack. The painted raster (Layer.mask) is one entry with its own operation and place;
 * up to MAX shape components are the others. Each entry is Add, Subtract or Overlap (intersect),
 * optionally inverted and scaled by Amount, and is applied on top of everything below it,
 * starting from nothing.
 *
 * 3.15 adds shapes beyond the two gradients:
 *   RECT   a rectangle with rounded corners (Roundness 0 = square corners, 1 = a stadium/ellipse-ish pill)
 *   POLY   a regular polygon (3-16 sides) or a star (inner radius < the polygon's), optionally rounded
 *   SHAPE  a library shape (heart, bolt, cloud...) or a freehand outline, as a signed-distance texture
 *   TEXT   live text in any font, as a signed-distance texture: words, font and spacing stay editable
 * These "box" shapes share: centre (x0, y0), half size (x1, y1), rotation, Feather (a soft edge
 * centred on the outline, as a fraction of the shorter half size), Grow (pushes the edge out or
 * in), Outline (keeps only a band around the edge: outlined text), and Reveal (a wipe across the
 * shape for write-on animation). In video every number can be keyframed (keys) and driven by the
 * music (Music targets), and evaluating at a time is exact Java (at()), so preview = export.
 *
 * Shapes live in the mask's own pixel frame (Layer.maskAffine), so on a linked mask they move
 * with their layer like the paint does. The shader evaluates them per pixel (common.glsl
 * maskAt); flatten() is the exact Java twin for the CPU paths (cutouts, selections, refine).
 *
 * A layer with no shape components behaves exactly like the raster-only masks of 3.5.
 */
final class MaskStack {
    static final int LINEAR = 0, RADIAL = 1, RECT = 2, POLY = 3, SHAPE = 4, TEXT = 5;
    static final int ADD = 0, SUB = 1, INTERSECT = 2;
    /** Shapes per mask (the shader's uniform arrays are this long). */
    static final int MAX = 8;
    /** Shapes per mask that use a distance texture (SHAPE, TEXT): one texture unit each. */
    static final int MAX_RASTER = 4;
    static final String[] OP_NAMES = {"Add", "Subtract", "Overlap"};
    static final String[] TYPE_NAMES = {"Linear", "Radial", "Rectangle", "Polygon", "Shape", "Text"};
    static final String[] REVEAL_NAMES = {"Left to right", "Right to left", "Top down", "Bottom up", "From the centre"};

    // keyframe channels (Comp.vec order)
    static final int K_X0 = 0, K_Y0 = 1, K_X1 = 2, K_Y1 = 3, K_ANG = 4, K_FEATHER = 5, K_AMOUNT = 6,
            K_GROW = 7, K_OUTLINE = 8, K_REVEAL = 9, K_ROUND = 10, K_INNER = 11, K_N = 12;

    private MaskStack() {}

    static final class Comp {
        int type = LINEAR, op = ADD;
        boolean invert;
        float amount = 1f;
        /**
         * LINEAR: full effect at (x0, y0) fading to none at (x1, y1). RADIAL and the box shapes:
         * centre (x0, y0), half size / radii (x1, y1). Mask px.
         */
        float x0, y0, x1, y1;
        /** Rotation in degrees and feather (RADIAL: 0 hard edge .. 1 fades from the centre; boxes: soft edge width). */
        float angle, feather = 0.5f;
        // ---- 3.15 box shapes
        /** RECT corner roundness / POLY corner rounding, 0..1. */
        float round;
        /** POLY: number of sides or points (3..16; ELLIPSE_SIDES = an ellipse). */
        int sides = 5;
        /** POLY: inner radius of a star as a fraction of the outer (polygon: >= cos(pi / sides)). */
        float inner = 1f;
        /** Edge offset, fraction of the shorter half size (-1 shrinks, +1 grows). */
        float grow;
        /** 0 = filled; > 0 keeps a band this wide around the edge (fraction of the shorter half size). */
        float outline;
        /** Wipe-on progress 0..1 (1 = all shown) and its direction (REVEAL_NAMES). */
        float reveal = 1f;
        int revealMode;
        /** SHAPE: library id (MaskShapes) or null with pts: a freehand outline in -1..1 box units. */
        String shape;
        float[] pts;
        /** TEXT */
        String text = "Text", font = "asset:Anton.ttf";
        int weight = 0;          // variable fonts: 0 = the font's default
        boolean italic;
        float tracking = 0, leading = 1f;
        int align = 1;
        /** Video: keyframes of the numbers (vec(), K_*), local times; empty = still. */
        Keys keys = new Keys();
        /** The last distance texture this shape used (shown while a changed one is built). Not saved. */
        transient MaskRaster.Sdf last;
        /** Identity of this shape across copies (history, render snapshots); not saved. */
        transient int uid = MaskRaster.nextUid();

        Comp copy() {
            Comp c = new Comp();
            c.type = type; c.op = op; c.invert = invert; c.amount = amount;
            c.x0 = x0; c.y0 = y0; c.x1 = x1; c.y1 = y1; c.angle = angle; c.feather = feather;
            c.round = round; c.sides = sides; c.inner = inner; c.grow = grow; c.outline = outline;
            c.reveal = reveal; c.revealMode = revealMode;
            c.shape = shape; c.pts = pts == null ? null : pts.clone();
            c.text = text; c.font = font; c.weight = weight; c.italic = italic; c.tracking = tracking;
            c.leading = leading; c.align = align;
            c.keys = keys.copy();
            c.last = last;
            c.uid = uid;
            return c;
        }

        boolean isBox() { return type >= RECT; }
        boolean isRaster() { return type == SHAPE || type == TEXT; }

        String name(int index) {
            String n;
            switch (type) {
                case POLY: n = sides >= ELLIPSE_SIDES ? "Ellipse" : inner < polyInner(sides) - 1e-3f ? "Star"
                        : (sides == 3 ? "Triangle" : sides == 4 ? "Diamond" : sides == 5 ? "Pentagon" : sides == 6 ? "Hexagon" : sides == 8 ? "Octagon" : "Polygon"); break;
                case SHAPE: n = shape != null ? MaskShapes.name(shape) : "Freehand"; break;
                case TEXT: {
                    String t = text == null ? "" : text.replace('\n', ' ').trim();
                    if (t.length() > 14) t = t.substring(0, 13) + "…";
                    return "“" + t + "”";
                }
                case RECT: n = round > 0.98f ? "Pill" : "Rectangle"; break;
                default: n = TYPE_NAMES[Math.max(0, Math.min(TYPE_NAMES.length - 1, type))];
            }
            return n + " " + (index + 1);
        }

        /** The keyframable numbers, K_* order. */
        float[] vec() { return new float[]{x0, y0, x1, y1, angle, feather, amount, grow, outline, reveal, round, inner}; }

        void setVec(float[] v) {
            x0 = v[K_X0]; y0 = v[K_Y0]; x1 = v[K_X1]; y1 = v[K_Y1]; angle = v[K_ANG];
            feather = clamp01(v[K_FEATHER]); amount = clamp01(v[K_AMOUNT]);
            grow = Math.max(-1, Math.min(1, v[K_GROW])); outline = clamp01(v[K_OUTLINE]); reveal = clamp01(v[K_REVEAL]);
            round = clamp01(v[K_ROUND]); inner = Math.max(0.05f, Math.min(1, v[K_INNER]));
            if (type != LINEAR) { x1 = Math.max(0.5f, Math.abs(x1)); y1 = Math.max(0.5f, Math.abs(y1)); }
        }

        /** This shape at local time t (keys applied), or itself when it isn't animated. */
        Comp at(long t) {
            if (keys.isEmpty()) return this;
            Comp c = copy();
            float[] v = c.vec();
            keys.eval(t, v);
            c.setVec(v);
            return c;
        }

        // ---- whole-shape edits shared by handles, music links and keyframes
        float cx() { return type == LINEAR ? (x0 + x1) / 2 : x0; }
        float cy() { return type == LINEAR ? (y0 + y1) / 2 : y0; }

        void moveBy(float dx, float dy) {
            x0 += dx; y0 += dy;
            if (type == LINEAR) { x1 += dx; y1 += dy; }
        }

        void scaleBy(float f) {
            f = Math.max(0.01f, f);
            if (type == LINEAR) {
                float mx = cx(), my = cy();
                x0 = mx + (x0 - mx) * f; y0 = my + (y0 - my) * f; x1 = mx + (x1 - mx) * f; y1 = my + (y1 - my) * f;
            } else { x1 = Math.max(0.5f, x1 * f); y1 = Math.max(0.5f, y1 * f); }
        }

        void rotateBy(float deg) {
            if (type == LINEAR) {
                float mx = cx(), my = cy();
                double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
                float ax = x0 - mx, ay = y0 - my, bx = x1 - mx, by = y1 - my;
                x0 = (float) (mx + c * ax - s * ay); y0 = (float) (my + s * ax + c * ay);
                x1 = (float) (mx + c * bx - s * by); y1 = (float) (my + s * bx + c * by);
            } else angle += deg;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", type); o.put("op", op);
            if (invert) o.put("inv", true);
            o.put("amt", (double) amount);
            JSONArray g = new JSONArray();
            g.put((double) x0); g.put((double) y0); g.put((double) x1); g.put((double) y1);
            o.put("g", g);
            if (type != LINEAR) { o.put("ang", (double) angle); o.put("fe", (double) feather); }
            if (isBox()) {
                if (round != 0) o.put("rnd", (double) round);
                if (grow != 0) o.put("grow", (double) grow);
                if (outline != 0) o.put("ol", (double) outline);
                if (reveal != 1) o.put("rev", (double) reveal);
                if (revealMode != 0) o.put("revm", revealMode);
            }
            if (type == POLY) { o.put("n", sides); o.put("in", (double) inner); }
            if (type == SHAPE) {
                if (shape != null) o.put("shape", shape);
                if (pts != null) {
                    JSONArray p = new JSONArray();
                    for (float f : pts) p.put((double) f);
                    o.put("pts", p);
                }
            }
            if (type == TEXT) {
                o.put("text", text); o.put("font", font);
                if (weight != 0) o.put("wt", weight);
                if (italic) o.put("it", true);
                if (tracking != 0) o.put("trk", (double) tracking);
                if (leading != 1) o.put("lead", (double) leading);
                if (align != 1) o.put("al", align);
            }
            if (!keys.isEmpty()) o.put("keys", VProject.keysJson(keys));
            return o;
        }

        static Comp fromJson(JSONObject o) throws JSONException {
            Comp c = new Comp();
            c.type = o.optInt("t", LINEAR); c.op = o.optInt("op", ADD);
            c.invert = o.optBoolean("inv"); c.amount = (float) o.optDouble("amt", 1);
            JSONArray g = o.optJSONArray("g");
            if (g != null && g.length() >= 4) {
                c.x0 = (float) g.optDouble(0); c.y0 = (float) g.optDouble(1);
                c.x1 = (float) g.optDouble(2); c.y1 = (float) g.optDouble(3);
            }
            c.angle = (float) o.optDouble("ang", 0); c.feather = (float) o.optDouble("fe", c.type >= RECT ? 0 : 0.5);
            c.round = (float) o.optDouble("rnd", 0); c.grow = (float) o.optDouble("grow", 0);
            c.outline = (float) o.optDouble("ol", 0); c.reveal = (float) o.optDouble("rev", 1);
            c.revealMode = o.optInt("revm", 0);
            c.sides = o.optInt("n", 5); c.inner = (float) o.optDouble("in", 1);
            c.shape = o.has("shape") ? o.optString("shape", null) : null;
            JSONArray p = o.optJSONArray("pts");
            if (p != null) {
                c.pts = new float[p.length() & ~1];
                for (int i = 0; i < c.pts.length; i++) c.pts[i] = (float) p.optDouble(i);
            }
            c.text = o.optString("text", "Text"); c.font = o.optString("font", "asset:Anton.ttf");
            c.weight = o.optInt("wt", 0); c.italic = o.optBoolean("it");
            c.tracking = (float) o.optDouble("trk", 0); c.leading = (float) o.optDouble("lead", 1);
            c.align = o.optInt("al", 1);
            JSONArray k = o.optJSONArray("keys");
            if (k != null) c.keys = VProject.readKeys(k);
            return c;
        }

        /** Repairs a damaged component; false if it can't be used (dropped). */
        boolean sanitize() {
            if (type < LINEAR || type > TEXT) return false;
            if (op < ADD || op > INTERSECT) op = ADD;
            float[] v = {x0, y0, x1, y1, angle};
            for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return false;
            if (Float.isNaN(amount)) amount = 1;
            amount = clamp01(amount);
            if (Float.isNaN(feather)) feather = type >= RECT ? 0 : 0.5f;
            feather = clamp01(feather);
            if (type != LINEAR) { x1 = Math.max(0.5f, Math.abs(x1)); y1 = Math.max(0.5f, Math.abs(y1)); }
            else if (x0 == x1 && y0 == y1) x1 += 1;
            round = fin(round, 0, 0, 1); grow = fin(grow, 0, -1, 1); outline = fin(outline, 0, 0, 1);
            reveal = fin(reveal, 1, 0, 1);
            if (revealMode < 0 || revealMode >= REVEAL_NAMES.length) revealMode = 0;
            sides = Math.max(3, Math.min(ELLIPSE_SIDES, sides));
            inner = fin(inner, 1, 0.05f, 1);
            if (type == SHAPE) {
                if (shape != null && !MaskShapes.exists(shape)) shape = null;
                if (shape == null && (pts == null || pts.length < 6)) return false;
                if (pts != null) for (int i = 0; i < pts.length; i++) pts[i] = fin(pts[i], 0, -2, 2);
            }
            // keyframes: one width, finite, in time order
            for (int i = keys.list.size() - 1; i >= 0; i--) {
                Keys.Key k = keys.list.get(i);
                boolean ok = k.v != null && k.v.length == K_N;
                if (ok) for (float f : k.v) if (Float.isNaN(f) || Float.isInfinite(f)) ok = false;
                if (!ok) keys.list.remove(i);
            }
            java.util.Collections.sort(keys.list, new java.util.Comparator<Keys.Key>() {
                @Override public int compare(Keys.Key a, Keys.Key b) { return a.t < b.t ? -1 : a.t > b.t ? 1 : 0; }
            });
            if (type == TEXT) {
                if (text == null) text = "";
                if (text.length() > 400) text = text.substring(0, 400);
                if (font == null || font.isEmpty()) font = "asset:Anton.ttf";
                weight = Math.max(0, Math.min(1000, weight));
                tracking = fin(tracking, 0, -0.5f, 2); leading = fin(leading, 1, 0.3f, 4);
                if (align < 0 || align > 2) align = 1;
            }
            return true;
        }

        /** The shape's value at mask px (x, y), before invert, amount and the operation. */
        float shape(float x, float y) {
            if (type == LINEAR) {
                float dx = x1 - x0, dy = y1 - y0;
                float t = ((x - x0) * dx + (y - y0) * dy) / Math.max(dx * dx + dy * dy, 1e-6f);
                return 1f - smoothstep(0f, 1f, t);
            }
            double a = Math.toRadians(angle);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            float qx = x - x0, qy = y - y0;
            float rx = c * qx + s * qy, ry = -s * qx + c * qy;
            if (type == RADIAL) {
                float ex = rx / Math.max(x1, 1e-3f), ey = ry / Math.max(y1, 1e-3f);
                float r = (float) Math.sqrt(ex * ex + ey * ey);
                float e = feather < 1e-4f ? (r >= 1f ? 1f : 0f) : smoothstep(1f - feather, 1f, r);
                return 1f - e;
            }
            float sMin = Math.min(x1, y1);
            float d = sdf(rx, ry);
            return boxValue(d, sMin, rx / x1, ry / y1);
        }

        /** What this component contributes: shape, inverted if set, times amount. */
        float value(float x, float y) {
            float v = shape(x, y);
            if (invert) v = 1f - v;
            return v * amount;
        }

        /**
         * Box shape value from its distance d (mask px): grow, outline, a feather centred on the
         * edge (feather² of the short half size, at least 1 mask px of anti-aliasing), then the
         * reveal wipe at box-normalised (nx, ny).
         * Twin of common.glsl mbox().
         */
        float boxValue(float d, float sMin, float nx, float ny) {
            d -= grow * sMin;
            if (outline > 0) d = Math.abs(d) - outline * sMin * 0.5f;
            float w = Math.max(feather * feather * sMin, 1f);   // squared: fine control of small soft edges (text)
            float v = 1f - smoothstep(-0.5f * w, 0.5f * w, d);
            if (reveal < 0.9999f) {
                float e = 0.06f, t = revealT(revealMode, nx, ny);
                v *= 1f - smoothstep(reveal * (1 + e) - e, reveal * (1 + e), t);
            }
            return v;
        }

        /** Signed distance (mask px, negative inside) of a box shape at local point (rx, ry). */
        float sdf(float rx, float ry) {
            float sMin = Math.min(x1, y1);
            switch (type) {
                case RECT: {
                    float r = round * sMin;
                    float qx = Math.abs(rx) - x1 + r, qy = Math.abs(ry) - y1 + r;
                    float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
                    return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
                }
                case POLY:
                    return star(rx / x1, ry / y1, sides, inner, round) * sMin;
                default: {
                    MaskRaster.Sdf f = MaskRaster.get(this);
                    if (f == null) return 1e6f;
                    float u = 0.5f + 0.5f * (rx / x1) * f.sx, v = 0.5f + 0.5f * (ry / y1) * f.sy;
                    return (0.5f - f.sample(u, v)) * f.unit(x1, y1);
                }
            }
        }
    }

    /** A polygon with this many sides draws (and is named) as an ellipse: within 0.12% of one. */
    static final int ELLIPSE_SIDES = 64;

    /** The polygon's own inner radius: a star with this inner ratio is the regular polygon. */
    static float polyInner(int n) { return (float) Math.cos(Math.PI / Math.max(3, n)); }

    /**
     * Signed distance to a regular n-pointed star (outer radius 1, a point straight up, inner
     * radius m), corners rounded by rnd 0..1. Box units. Twin of common.glsl mstar().
     */
    static float star(float px, float py, int n, float m, float rnd) {
        float rr = rnd * 0.45f;
        float k = 1f - rr;
        px /= k; py /= k;
        float an = (float) Math.PI / n;
        float th = (float) Math.atan2(px, -py);              // 0 straight up, clockwise on screen
        float sec = 2 * an;
        th = th - sec * (float) Math.floor(th / sec);         // GLSL mod()
        if (th > an) th = sec - th;
        float r = (float) Math.sqrt(px * px + py * py);
        float qx = r * (float) Math.cos(th), qy = r * (float) Math.sin(th);
        float bx = m * (float) Math.cos(an), by = m * (float) Math.sin(an);
        float ex = bx - 1, ey = by;
        float wx = qx - 1, wy = qy;
        float h = Math.max(0, Math.min(1, (wx * ex + wy * ey) / (ex * ex + ey * ey)));
        float dx = wx - ex * h, dy = wy - ey * h;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float cr = ex * wy - ey * wx;                         // > 0: on the centre's side
        return (cr > 0 ? -dist : dist) * k - rr;
    }

    static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }

    private static float fin(float v, float def, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = def;
        return Math.max(lo, Math.min(hi, v));
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Reveal wipe coordinate 0..1 at box-normalised point (nx, ny) for a direction. */
    static float revealT(int mode, float nx, float ny) {
        switch (mode) {
            case 1: return (1 - nx) * 0.5f;
            case 2: return (ny + 1) * 0.5f;
            case 3: return (1 - ny) * 0.5f;
            case 4: return Math.min(1, (float) Math.sqrt(nx * nx + ny * ny) * 0.7071f);
            default: return (nx + 1) * 0.5f;
        }
    }

    static float combine(float m, float v, int op) {
        if (op == ADD) return Math.max(m, v);
        if (op == SUB) return m * (1f - v);
        return m * v;
    }

    /**
     * The mask value at one point: paint = the raster's value there (0..1); the raster sits at
     * stack position paintAt (0 = bottom) with operation paintOp; comps bottom first.
     */
    static float eval(float paint, int paintOp, int paintAt, ArrayList<Comp> comps, float x, float y) {
        if (comps.isEmpty()) return paint;
        float m = 0f;
        int n = Math.min(comps.size(), MAX);
        for (int k = 0; k <= n; k++) {
            if (k == paintAt) m = combine(m, paint, paintOp);
            if (k < n) {
                Comp c = comps.get(k);
                m = combine(m, c.value(x + 0.5f, y + 0.5f), c.op);
            }
        }
        return m;
    }

    /**
     * The whole mask flattened at the raster's resolution (w x h, one byte per px), the way the
     * shader shows it: shapes evaluated at pixel centres.
     */
    static byte[] flatten(byte[] raster, int w, int h, int paintOp, int paintAt, ArrayList<Comp> comps) {
        byte[] out = new byte[w * h];
        if (comps.isEmpty()) { System.arraycopy(raster, 0, out, 0, w * h); return out; }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float v = eval((raster[i] & 255) / 255f, paintOp, paintAt, comps, x, y);
                out[i] = (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
            }
        return out;
    }

    /** True if any shape is keyframed. */
    static boolean animated(ArrayList<Comp> comps) {
        for (Comp c : comps) if (!c.keys.isEmpty()) return true;
        return false;
    }

    /** The layer with its shapes at local time t (a private copy if any shape is keyframed). */
    static Layer at(Layer l, long t) {
        if (!animated(l.comps)) return l;
        Layer c = l.copy();
        for (int i = 0; i < c.comps.size(); i++) c.comps.set(i, l.comps.get(i).at(t));
        return c;
    }

    /**
     * Shader uniforms for maskAt, packed the way GlBase sends them: 5 vec4 per shape
     * (uMA..uME, common.glsl), raster slots numbered in stack order. out[k] = 20 floats.
     */
    static float[][] uniforms(ArrayList<Comp> comps) { return uniforms(comps, true); }

    /** As above; wait = false never blocks on a text or shape texture (the live preview). */
    static float[][] uniforms(ArrayList<Comp> comps, boolean wait) {
        int n = Math.min(comps.size(), MAX);
        float[][] u = new float[n][20];
        int slot = 0;
        for (int k = 0; k < n; k++) {
            Comp c = comps.get(k);
            double a = Math.toRadians(c.angle);
            float[] o = u[k];
            o[0] = c.type; o[1] = c.op; o[2] = c.invert ? 1 : 0; o[3] = c.amount;
            o[4] = c.x0; o[5] = c.y0; o[6] = c.x1; o[7] = c.y1;
            o[8] = (float) Math.cos(a); o[9] = (float) Math.sin(a); o[10] = c.feather; o[11] = c.round;
            o[12] = c.grow; o[13] = c.outline; o[14] = c.reveal; o[15] = c.revealMode;
            if (c.type == POLY) { o[16] = c.sides; o[17] = c.inner; }
            if (c.isRaster()) {
                MaskRaster.Sdf f = slot < MAX_RASTER ? MaskRaster.getNow(c, wait) : null;
                if (f != null) {
                    o[16] = f.sx; o[17] = f.sy; o[18] = f.unit(c.x1, c.y1); o[19] = slot++;
                } else { o[0] = RECT; o[1] = ADD; o[3] = 0; }   // past the texture budget (or no texture yet): neutral
            }
        }
        return u;
    }

    /**
     * Carries shapes into another mask frame: dst px = m (a, b, c, d, e, f) applied to src px
     * (linking or unlinking a mask). Radii follow the map's scale along each axis.
     */
    static void transform(ArrayList<Comp> comps, double[] m) {
        for (Comp c : comps) {
            if (c.type == LINEAR) {
                float[] p = apply(m, c.x0, c.y0), q = apply(m, c.x1, c.y1);
                c.x0 = p[0]; c.y0 = p[1]; c.x1 = q[0]; c.y1 = q[1];
            } else {
                float[] p = apply(m, c.x0, c.y0);
                double a = Math.toRadians(c.angle), ca = Math.cos(a), sa = Math.sin(a);
                double ux = m[0] * ca + m[1] * sa, uy = m[3] * ca + m[4] * sa;      // the x axis
                double vx = m[0] * -sa + m[1] * ca, vy = m[3] * -sa + m[4] * ca;    // the y axis
                c.x0 = p[0]; c.y0 = p[1];
                c.x1 = (float) (c.x1 * Math.hypot(ux, uy));
                c.y1 = (float) (c.y1 * Math.hypot(vx, vy));
                c.angle = (float) Math.toDegrees(Math.atan2(uy, ux));
            }
            if (!c.keys.isEmpty()) {
                // keyed positions and sizes move into the new frame too
                double sx = Math.hypot(m[0], m[3]), sy = Math.hypot(m[1], m[4]);
                double rot = Math.toDegrees(Math.atan2(m[3], m[0]));
                for (Keys.Key k : c.keys.list) {
                    if (k.v.length < K_N) continue;
                    if (c.type == LINEAR) {
                        float[] p = apply(m, k.v[K_X0], k.v[K_Y0]), q = apply(m, k.v[K_X1], k.v[K_Y1]);
                        k.v[K_X0] = p[0]; k.v[K_Y0] = p[1]; k.v[K_X1] = q[0]; k.v[K_Y1] = q[1];
                    } else {
                        float[] p = apply(m, k.v[K_X0], k.v[K_Y0]);
                        k.v[K_X0] = p[0]; k.v[K_Y0] = p[1];
                        k.v[K_X1] *= (float) sx; k.v[K_Y1] *= (float) sy;
                        k.v[K_ANG] += (float) rot;
                    }
                }
            }
        }
    }

    static float[] apply(double[] m, float x, float y) {
        return new float[]{(float) (m[0] * x + m[1] * y + m[2]), (float) (m[3] * x + m[4] * y + m[5])};
    }

    /** A short signature (detects a change while a background mask job ran). */
    static String key(ArrayList<Comp> comps, int paintOp, int paintAt) {
        StringBuilder b = new StringBuilder().append(paintOp).append('/').append(paintAt);
        for (Comp c : comps) {
            b.append('|').append(c.type).append(c.op).append(c.invert).append(c.amount)
                    .append(',').append(c.x0).append(',').append(c.y0).append(',').append(c.x1).append(',').append(c.y1)
                    .append(',').append(c.angle).append(',').append(c.feather);
            if (c.isBox()) b.append(',').append(c.round).append(',').append(c.grow).append(',').append(c.outline)
                    .append(',').append(c.reveal).append(',').append(c.revealMode);
            if (c.type == POLY) b.append(',').append(c.sides).append(',').append(c.inner);
            if (c.isRaster()) b.append(',').append(MaskRaster.contentKey(c));
            if (!c.keys.isEmpty()) b.append(",k").append(c.keys.size());
        }
        return b.toString();
    }

    static ArrayList<Comp> copyAll(ArrayList<Comp> comps) {
        ArrayList<Comp> o = new ArrayList<Comp>(comps.size());
        for (Comp c : comps) o.add(c.copy());
        return o;
    }
}
