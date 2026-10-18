package com.christopher.strata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * 3.15: the equalizer curve with a dot per band, frequency across (20 Hz - 20 kHz, log) and gain
 * up the side (+-18 dB). Drag a dot sideways for frequency and up / down for gain (cuts and the
 * notch move sideways only); a tap selects a band for its sliders; double-tap a dot to switch
 * that band off or on. The drag is one undo step.
 */
final class EqView extends View {
    interface Host {
        AudioEq eq();
        int selected();
        void select(int band);
        void beginEdit();
        void changed();
        void done();
    }

    static final int[] COLORS = {0xff7fd0ff, 0xffe9a23b, 0xffff6fa8, 0xff9be36a, 0xffb48cff, 0xff4fe0d0, 0xffffd54a, 0xffff7a4a};
    private final Host h;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int drag = -1;
    private boolean begun;
    private float downX, downY, grabDx, grabDy;
    private long lastTap;
    private int lastTapBand = -1;

    EqView(Context c, Host h) { super(c); this.h = h; }

    private float padL() { return Ui.dp(34); }
    private float padR() { return Ui.dp(12); }
    private float padT() { return Ui.dp(10); }
    private float padB() { return Ui.dp(18); }
    private float gw() { return getWidth() - padL() - padR(); }
    private float gh() { return getHeight() - padT() - padB(); }
    float xAt(double f) { return (float) (padL() + Math.log(f / AudioEq.F_MIN) / Math.log(AudioEq.F_MAX / AudioEq.F_MIN) * gw()); }
    float yAt(double db) { return (float) (padT() + (1 - (db + AudioEq.G_MAX) / (2 * AudioEq.G_MAX)) * gh()); }
    double fAt(float x) { return AudioEq.F_MIN * Math.pow(AudioEq.F_MAX / AudioEq.F_MIN, Math.max(0, Math.min(1, (x - padL()) / gw()))); }
    double dbAt(float y) { return Math.max(-AudioEq.G_MAX, Math.min(AudioEq.G_MAX, (1 - (y - padT()) / gh()) * 2 * AudioEq.G_MAX - AudioEq.G_MAX)); }

    /** Where a band's dot sits: its frequency, and its gain (cuts: the curve there). */
    float[] dot(AudioEq e, int i) {
        AudioEq.Band b = e.bands[i];
        double y = b.hasGain() ? b.gain : (b.type == AudioEq.NOTCH ? -AudioEq.G_MAX * 0.6 : e.response(b.freq));
        return new float[]{xAt(b.freq), yAt(Math.max(-AudioEq.G_MAX, Math.min(AudioEq.G_MAX, y)))};
    }

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(190)); }

    @Override
    protected void onDraw(Canvas c) {
        AudioEq e = h.eq();
        c.drawColor(Ui.PANEL2);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(Ui.dp(9.5f));
        int[] fs = {50, 100, 200, 500, 1000, 2000, 5000, 10000};
        for (int f : fs) {
            float x = xAt(f);
            p.setColor(0x1effffff);
            c.drawRect(x - Ui.dp(0.5f), padT(), x + Ui.dp(0.5f), padT() + gh(), p);
            p.setColor(Ui.DIM);
            String l = f >= 1000 ? (f / 1000) + "k" : String.valueOf(f);
            c.drawText(l, x - p.measureText(l) / 2, getHeight() - Ui.dp(5), p);
        }
        int[] gs = {-12, -6, 0, 6, 12};
        for (int g : gs) {
            float y = yAt(g);
            p.setColor(g == 0 ? 0x55ffffff : 0x1effffff);
            c.drawRect(padL(), y - Ui.dp(g == 0 ? 0.75f : 0.5f), padL() + gw(), y + Ui.dp(g == 0 ? 0.75f : 0.5f), p);
            p.setColor(Ui.DIM);
            String l = (g > 0 ? "+" : "") + g;
            c.drawText(l, Ui.dp(6), y + Ui.dp(3.5f), p);
        }
        if (e == null) return;
        // the curve, shaded to the 0 dB line
        path.reset();
        int n = 180;
        float y0 = yAt(0);
        for (int i = 0; i <= n; i++) {
            float x = padL() + gw() * i / n;
            float y = yAt(Math.max(-AudioEq.G_MAX - 6, Math.min(AudioEq.G_MAX + 6, e.response(fAt(x)))));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        Path fill = new Path(path);
        fill.lineTo(padL() + gw(), y0);
        fill.lineTo(padL(), y0);
        fill.close();
        c.save();
        c.clipRect(padL(), padT(), padL() + gw(), padT() + gh());
        p.setColor(e.on ? 0x30e9a23b : 0x18ffffff);
        c.drawPath(fill, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(2));
        p.setColor(e.on ? Ui.ACCENT : Ui.DIM);
        c.drawPath(path, p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
        int sel = h.selected();
        for (int i = 0; i < e.bands.length; i++) {
            float[] d = dot(e, i);
            AudioEq.Band b = e.bands[i];
            int col = COLORS[i % COLORS.length];
            float r = Ui.dp(i == sel ? 9 : 7);
            p.setColor(0x99000000);
            c.drawCircle(d[0], d[1], r + Ui.dp(2), p);
            p.setColor(b.on ? col : 0xff555a63);
            c.drawCircle(d[0], d[1], r, p);
            p.setColor(0xff111111);
            p.setTextSize(Ui.dp(9));
            String lab = String.valueOf(i + 1);
            c.drawText(lab, d[0] - p.measureText(lab) / 2, d[1] + Ui.dp(3.2f), p);
            if (i == sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(1.5f));
                p.setColor(0xffffffff);
                c.drawCircle(d[0], d[1], r + Ui.dp(3), p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        if (!e.on) {
            p.setColor(Ui.DIM);
            p.setTextSize(Ui.dp(12));
            String s = "EQ bypassed";
            c.drawText(s, padL() + gw() - p.measureText(s) - Ui.dp(6), padT() + Ui.dp(14), p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        AudioEq e = h.eq();
        if (e == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = ev.getX(); downY = ev.getY();
                begun = false;
                drag = -1;
                float best = Ui.dp(28);
                for (int i = 0; i < e.bands.length; i++) {
                    float[] d = dot(e, i);
                    float dd = (float) Math.hypot(d[0] - downX, d[1] - downY);
                    if (dd < best) { best = dd; drag = i; grabDx = d[0] - downX; grabDy = d[1] - downY; }
                }
                if (drag >= 0 && drag != h.selected()) { h.select(drag); invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag < 0) return true;
                if (!begun && Math.hypot(ev.getX() - downX, ev.getY() - downY) < Ui.dp(4)) return true;
                if (!begun) { h.beginEdit(); begun = true; }
                AudioEq cur = h.eq();
                if (cur == null || drag >= cur.bands.length) return true;
                AudioEq.Band b = cur.bands[drag];
                b.freq = (float) Math.max(AudioEq.F_MIN, Math.min(AudioEq.F_MAX, fAt(ev.getX() + grabDx)));
                if (b.hasGain()) {
                    double g = dbAt(ev.getY() + grabDy);
                    b.gain = (float) (Math.abs(g) < 0.4 ? 0 : g);   // settles on flat
                    if (!b.on) b.on = true;
                } else if (!b.on) b.on = true;
                h.changed();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!begun && drag >= 0) {
                    long now = ev.getEventTime();
                    if (now - lastTap < 320 && lastTapBand == drag) {   // double tap: band on / off
                        h.beginEdit();
                        AudioEq cur = h.eq();
                        if (cur != null && drag < cur.bands.length) cur.bands[drag].on = !cur.bands[drag].on;
                        h.changed();
                        lastTap = 0;
                    } else { lastTap = now; lastTapBand = drag; }
                }
                if (begun || drag >= 0) h.done();
                drag = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                return true;
        }
        return true;
    }
}
