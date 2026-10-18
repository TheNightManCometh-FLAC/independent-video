package com.christopher.strata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

/** Histogram and curves editor. */
final class Views {

    static final int[] CHANNEL_COLORS = {0xffe7e7e9, 0xffff5a52, 0xff5ee07a, 0xff5aa0ff};

    /** RGB + luminance histogram. hist = 4 x 256 bins: r, g, b, luma. */
    static final class Hist extends View {
        int[] hist;
        int channel = -1;   // -1 = RGB overlay, 0 = luma, 1..3 = single channel
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Hist(Context c) { super(c); }

        void set(int[] h) { hist = h; invalidate(); }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(84));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight(), pad = Ui.dp(14);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.PANEL2);
            c.drawRoundRect(pad, Ui.dp(4), w - pad, h - Ui.dp(4), Ui.dp(6), Ui.dp(6), p);
            if (hist == null) return;
            draw(c, hist, channel, pad, Ui.dp(8), w - pad, h - Ui.dp(6), p, path);
            // clipping indicators (Lightroom's triangles): lit blue / red when shadows / highlights clip
            boolean[] f = ViewMath.clipFlags(hist);
            float ts = Ui.dp(8), y0 = Ui.dp(8), xl = pad + Ui.dp(4), xr = w - pad - Ui.dp(4);
            p.setStyle(Paint.Style.FILL);
            p.setXfermode(null);
            p.setColor(f[0] ? 0xff4f86ff : 0x40ffffff);
            path.reset(); path.moveTo(xl, y0); path.lineTo(xl + ts, y0); path.lineTo(xl, y0 + ts); path.close();
            c.drawPath(path, p);
            p.setColor(f[1] ? 0xffff4a3d : 0x40ffffff);
            path.reset(); path.moveTo(xr, y0); path.lineTo(xr - ts, y0); path.lineTo(xr, y0 + ts); path.close();
            c.drawPath(path, p);
        }

        static void draw(Canvas c, int[] hist, int channel, float x0, float y0, float x1, float y1, Paint p, Path path) {
            int max = 1;
            for (int i = 1; i < 255; i++)   // ignore clipped end bins when scaling
                for (int ch = 0; ch < 4; ch++) max = Math.max(max, hist[ch * 256 + i]);
            float lm = (float) Math.sqrt(max);
            int sc = c.saveLayer(x0, y0, x1, y1, null);
            int[] chans = channel < 0 ? new int[]{0, 1, 2} : new int[]{channel == 0 ? 3 : channel - 1};
            int[] cols = {0xffff4040, 0xff40ff60, 0xff4080ff, 0xffd0d0d0};
            p.setXfermode(channel < 0 ? new PorterDuffXfermode(PorterDuff.Mode.ADD) : null);
            for (int ch : chans) {
                path.reset();
                path.moveTo(x0, y1);
                for (int i = 0; i < 256; i++) {
                    float v = Math.min(1f, (float) Math.sqrt(hist[ch * 256 + i]) / lm);
                    path.lineTo(x0 + (x1 - x0) * i / 255f, y1 - (y1 - y0) * v);
                }
                path.lineTo(x1, y1);
                path.close();
                p.setColor((cols[ch] & 0x00ffffff) | 0x99000000);
                c.drawPath(path, p);
            }
            p.setXfermode(null);
            c.restoreToCount(sc);
        }
    }

    /** Curves editor: tap to add a point, drag to move, drag far off the graph to delete. */
    static final class CurveView extends View {
        interface Listener {
            void begin();
            void changed();
        }

        Layer layer;
        int channel;      // 0 master, 1 r, 2 g, 3 b
        int[] hist;
        Listener listener;
        private int drag = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CurveView(Context c) { super(c); }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.min(w, Ui.dp(260)));
        }

        private float gx0() { return Ui.dp(16); }
        private float gy0() { return Ui.dp(10); }
        private float gw() { return getWidth() - Ui.dp(32); }
        private float gh() { return getHeight() - Ui.dp(20); }

        @Override
        protected void onDraw(Canvas c) {
            if (layer == null) return;
            float x0 = gx0(), y0 = gy0(), w = gw(), h = gh();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.PANEL2);
            c.drawRect(x0, y0, x0 + w, y0 + h, p);
            if (hist != null) Hist.draw(c, hist, channel, x0, y0, x0 + w, y0 + h, p, path);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1));
            p.setColor(Ui.LINE);
            for (int i = 1; i < 4; i++) {
                c.drawLine(x0 + w * i / 4, y0, x0 + w * i / 4, y0 + h, p);
                c.drawLine(x0, y0 + h * i / 4, x0 + w, y0 + h * i / 4, p);
            }
            c.drawLine(x0, y0 + h, x0 + w, y0, p);
            // other channels faintly
            for (int ch = 0; ch < 4; ch++) {
                if (ch == channel || Curves.isIdentity(layer.curve[ch])) continue;
                drawCurve(c, layer.curve[ch], (CHANNEL_COLORS[ch] & 0x00ffffff) | 0x55000000, Ui.dp(1));
            }
            drawCurve(c, layer.curve[channel], CHANNEL_COLORS[channel], Ui.dp(2));
            float[] pts = layer.curve[channel];
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < pts.length / 2; i++) {
                float px = x0 + w * pts[i * 2], py = y0 + h * (1 - pts[i * 2 + 1]);
                p.setColor(0xff111111);
                c.drawCircle(px, py, Ui.dp(i == drag ? 9 : 7), p);
                p.setColor(i == drag ? Ui.ACCENT : CHANNEL_COLORS[channel]);
                c.drawCircle(px, py, Ui.dp(i == drag ? 7 : 5), p);
            }
        }

        private void drawCurve(Canvas c, float[] pts, int color, float width) {
            float x0 = gx0(), y0 = gy0(), w = gw(), h = gh();
            path.reset();
            for (int i = 0; i <= 128; i++) {
                float x = i / 128f, y = Curves.eval(pts, x);
                float px = x0 + w * x, py = y0 + h * (1 - y);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setColor(color);
            c.drawPath(path, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (layer == null) return false;
            float x = (e.getX() - gx0()) / gw(), y = 1 - (e.getY() - gy0()) / gh();
            float[] pts = layer.curve[channel];
            int n = pts.length / 2;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (listener != null) listener.begin();
                    pts = layer.curve[channel];
                    n = pts.length / 2;
                    float best = Ui.dp(30);
                    drag = -1;
                    for (int i = 0; i < n; i++) {
                        float dx = (pts[i * 2] - x) * gw(), dy = (pts[i * 2 + 1] - y) * gh();
                        float d = (float) Math.sqrt(dx * dx + dy * dy);
                        if (d < best) { best = d; drag = i; }
                    }
                    if (drag < 0) {
                        float cx = Math.max(0.005f, Math.min(0.995f, x));
                        int at = 0;
                        while (at < n && pts[at * 2] < cx) at++;
                        if (at == 0 || at == n) break;
                        float[] np = new float[pts.length + 2];
                        System.arraycopy(pts, 0, np, 0, at * 2);
                        np[at * 2] = cx;
                        np[at * 2 + 1] = Curves.eval(pts, cx);
                        System.arraycopy(pts, at * 2, np, at * 2 + 2, pts.length - at * 2);
                        layer.curve[channel] = np;
                        drag = at;
                        notifyChanged();
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (drag < 0) return true;
                    boolean end = drag == 0 || drag == n - 1;
                    float off = Math.max(-y, y - 1) * gh();
                    if (!end && off > Ui.dp(48)) {      // dragged off the graph: delete
                        float[] np = new float[pts.length - 2];
                        System.arraycopy(pts, 0, np, 0, drag * 2);
                        System.arraycopy(pts, drag * 2 + 2, np, drag * 2, pts.length - drag * 2 - 2);
                        layer.curve[channel] = np;
                        drag = -1;
                        notifyChanged();
                        invalidate();
                        return true;
                    }
                    float lo = drag == 0 ? 0 : pts[(drag - 1) * 2] + 0.01f;
                    float hi = drag == n - 1 ? 1 : pts[(drag + 1) * 2] - 0.01f;
                    float[] np = pts.clone();
                    np[drag * 2] = Math.max(lo, Math.min(hi, x));
                    np[drag * 2 + 1] = Math.max(0, Math.min(1, y));
                    layer.curve[channel] = np;
                    notifyChanged();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    invalidate();
                    return true;
            }
            return true;
        }

        private void notifyChanged() { if (listener != null) listener.changed(); }
    }
}
