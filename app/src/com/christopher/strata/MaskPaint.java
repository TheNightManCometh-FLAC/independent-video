package com.christopher.strata;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 3.15: the painted part of a video effect's mask, kept as the brush strokes themselves (and
 * fills), not as a picture: the project file stays small, there is no sidecar to save or lose,
 * undo and versions copy it for free, and the mask is redrawn at whatever size it is needed.
 * Points are fractions of the mask (0..1), sizes fractions of its long side. The brush is the
 * photo editor's: soft round dabs at 12% spacing, flow squared, hide = erase.
 */
final class MaskPaint {
    static final int STROKE = 0, REVEAL_ALL = 1, HIDE_ALL = 2, INVERT = 3;

    static final class Op {
        final int kind;
        final float[] pts;     // x0, y0, x1, y1... in 0..1 of the mask
        final float size, hard, flow;
        final boolean erase;

        Op(int kind, float[] pts, float size, float hard, float flow, boolean erase) {
            this.kind = kind; this.pts = pts; this.size = size; this.hard = hard; this.flow = flow; this.erase = erase;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("k", kind);
            if (kind == STROKE) {
                JSONArray a = new JSONArray();
                for (float f : pts) a.put(Math.round(f * 100000) / 100000.0);
                o.put("p", a);
                o.put("s", (double) size); o.put("h", (double) hard); o.put("f", (double) flow);
                if (erase) o.put("e", true);
            }
            return o;
        }

        static Op fromJson(JSONObject o) throws JSONException {
            int k = o.optInt("k", STROKE);
            if (k < STROKE || k > INVERT) return null;
            if (k != STROKE) return new Op(k, null, 0, 0, 0, false);
            JSONArray a = o.optJSONArray("p");
            if (a == null || a.length() < 2) return null;
            float[] p = new float[a.length() & ~1];
            for (int i = 0; i < p.length; i++) {
                double v = a.optDouble(i);
                if (Double.isNaN(v) || Double.isInfinite(v)) return null;
                p[i] = (float) Math.max(-1, Math.min(2, v));
            }
            float s = (float) o.optDouble("s", 0.05), h = (float) o.optDouble("h", 0.5), f = (float) o.optDouble("f", 0.6);
            if (Float.isNaN(s) || Float.isNaN(h) || Float.isNaN(f)) return null;
            return new Op(k, p, Math.max(1e-4f, Math.min(2, s)), Math.max(0, Math.min(1, h)), Math.max(0.01f, Math.min(1, f)), o.optBoolean("e"));
        }
    }

    private MaskPaint() {}

    static JSONArray toJson(ArrayList<Op> ops) throws JSONException {
        JSONArray a = new JSONArray();
        for (Op o : ops) a.put(o.toJson());
        return a;
    }

    static ArrayList<Op> fromJson(JSONArray a) throws JSONException {
        ArrayList<Op> out = new ArrayList<Op>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Op op = fromJson0(o);
            if (op != null) out.add(op);
        }
        return out;
    }

    private static Op fromJson0(JSONObject o) throws JSONException { return Op.fromJson(o); }

    /** Mask size for a video frame: the output size with the long side at most 1920. */
    static int[] size(float outW, float outH) {
        float k = Math.min(1f, 1920f / Math.max(outW, outH));
        return new int[]{Math.max(8, Math.round(outW * k)), Math.max(8, Math.round(outH * k))};
    }

    /** Draws ops onto a w x h ALPHA_8 bitmap, from hidden (nothing shows). */
    static Bitmap render(int w, int h, ArrayList<Op> ops) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Brush br = new Brush();
        for (Op o : ops) apply(b, c, o, br);
        return b;
    }

    /** Applies one op to an ALPHA_8 bitmap (and its canvas). */
    static void apply(Bitmap b, Canvas c, Op o, Brush br) {
        int w = b.getWidth(), h = b.getHeight();
        switch (o.kind) {
            case REVEAL_ALL: b.eraseColor(0xff000000); return;
            case HIDE_ALL: b.eraseColor(0); return;
            case INVERT: {
                Bitmap copy = b.copy(Bitmap.Config.ALPHA_8, false);
                b.eraseColor(0xff000000);
                Paint p = new Paint();
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                c.drawBitmap(copy, 0, 0, p);
                copy.recycle();
                return;
            }
            default: {
                float r = Math.max(0.5f, o.size * Math.max(w, h) / 2);
                br.begin(c, r, o.hard, o.flow, o.erase);
                br.dab(o.pts[0] * w, o.pts[1] * h);
                for (int i = 2; i + 1 < o.pts.length; i += 2)
                    br.to(o.pts[i - 2] * w, o.pts[i - 1] * h, o.pts[i] * w, o.pts[i + 1] * h);
            }
        }
    }

    /** The soft round brush (the photo editor's dab), with carried spacing along a stroke. */
    static final class Brush {
        private final Paint dab = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Canvas c;
        private float r, hard, flow, residual;
        private boolean erase;

        void begin(Canvas c, float r, float hard, float flow, boolean erase) {
            this.c = c; this.r = r; this.hard = hard; this.flow = flow; this.erase = erase; residual = 0;
        }

        void dab(float x, float y) {
            if (c == null) return;
            float h = Math.max(0.01f, Math.min(0.99f, hard));
            dab.setShader(new RadialGradient(x, y, r, new int[]{0xff000000, 0xff000000, 0x00000000}, new float[]{0, h, 1}, Shader.TileMode.CLAMP));
            dab.setAlpha(Math.max(1, Math.round(flow * flow * 255)));
            dab.setXfermode(erase ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
            c.drawCircle(x, y, r, dab);
        }

        void to(float x0, float y0, float x1, float y1) {
            float spacing = Math.max(0.5f, r * 0.12f);
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float t = spacing - residual;
            while (t <= len) {
                float f = len == 0 ? 0 : t / len;
                dab(x0 + dx * f, y0 + dy * f);
                t += spacing;
            }
            residual = len - (t - spacing);
        }
    }
}
