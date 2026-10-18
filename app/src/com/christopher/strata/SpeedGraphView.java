package com.christopher.strata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * The selected clip's speed curve: footage across (0..100 %), speed up the side on a log scale
 * (0.1x to 10x, so 2x faster and 2x slower are the same distance from 1x). Drag a point up or
 * down to change its speed (it settles on 1x when close), sideways to move it; tap empty graph to
 * jump the playhead to that moment of the footage. The playhead shows as a vertical line.
 */
final class SpeedGraphView extends View {
    private final VideoEditor ed;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int drag = -1;
    private boolean begun, moved;
    private float downX, downY;

    SpeedGraphView(Context c, VideoEditor ed) {
        super(c);
        this.ed = ed;
    }

    private float padL() { return Ui.dp(40); }
    private float padR() { return Ui.dp(16); }
    private float padT() { return Ui.dp(10); }
    private float padB() { return Ui.dp(18); }
    private float gw() { return getWidth() - padL() - padR(); }
    private float gh() { return getHeight() - padT() - padB(); }

    float xAt(double u) { return (float) (padL() + u * gw()); }
    float yAt(double speed) { return (float) (padT() + (1 - (Math.log10(speed) + 1) / 2) * gh()); }
    double uAt(float x) { return Math.max(0, Math.min(1, (x - padL()) / gw())); }
    float speedAt(float y) {
        double l = (1 - (y - padT()) / gh()) * 2 - 1;
        float s = (float) Math.pow(10, Math.max(-1, Math.min(1, l)));
        return Math.abs(s - 1) < 0.04f ? 1 : s;   // settles on normal speed
    }

    @Override
    protected void onDraw(Canvas c) {
        VProject.Clip k = ed.selClip();
        if (k == null || k.curve == null) return;
        SpeedCurve cv = k.curve;
        double span = Math.max(1, k.out - k.in);
        c.drawColor(Ui.PANEL2);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(Ui.dp(10));
        float[] grid = {0.1f, 0.25f, 0.5f, 1, 2, 4, 10};
        for (float s : grid) {
            float y = yAt(s);
            p.setColor(s == 1 ? 0x55ffffff : 0x1effffff);
            c.drawRect(padL(), y - (s == 1 ? Ui.dp(0.75f) : Ui.dp(0.5f)), padL() + gw(), y + (s == 1 ? Ui.dp(0.75f) : Ui.dp(0.5f)), p);
            p.setColor(Ui.DIM);
            String lab = s < 1 ? (s == 0.25f ? ".25×" : (s == 0.5f ? ".5×" : ".1×")) : ((int) s) + "×";
            c.drawText(lab, Ui.dp(6), y + Ui.dp(3.5f), p);
        }
        // the curve, with the area under it shaded
        path.reset();
        int n = 160;
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;   // across the clip's footage
            float x = xAt(u), y = yAt(k.speedAtSrc(k.in + Math.round(u * span)));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        Path fill = new Path(path);
        fill.lineTo(xAt(1), padT() + gh());
        fill.lineTo(xAt(0), padT() + gh());
        fill.close();
        p.setColor(0x22e9a23b);
        c.drawPath(fill, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(2));
        p.setColor(Ui.ACCENT);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        // playhead (where it is in the footage)
        int ci = ed.proj.clipIndex(k.id);
        long s0 = ed.proj.start(ci);
        if (ed.time >= s0 && ed.time < s0 + k.dur()) {
            double u = (ed.proj.srcTime(ci, ed.time) - k.in) / (double) Math.max(1, k.out - k.in);
            p.setColor(0xccffffff);
            float x = xAt(u);
            c.drawRect(x - Ui.dp(0.75f), padT(), x + Ui.dp(0.75f), padT() + gh(), p);
        }
        // points
        for (int i = 0; i < cv.size(); i++) {
            double gu = (k.curveSrc(cv.x[i]) - k.in) / span;   // where the point sits on this clip
            if (gu < -0.001 || gu > 1.001) continue;              // trimmed off: not shown
            float x = xAt(gu), y = yAt(cv.v[i]);
            boolean sel = i == ed.curvePoint;
            p.setColor(sel ? 0xffffffff : Ui.ACCENT);
            c.drawCircle(x, y, Ui.dp(sel ? 7 : 5.5f), p);
            p.setColor(Ui.PANEL2);
            c.drawCircle(x, y, Ui.dp(sel ? 3 : 2.5f), p);
        }
        p.setColor(Ui.DIM);
        c.drawText("start of footage", padL(), getHeight() - Ui.dp(4), p);
        float tw = p.measureText("end");
        c.drawText("end", padL() + gw() - tw, getHeight() - Ui.dp(4), p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        VProject.Clip k = ed.selClip();
        if (k == null || k.curve == null) return false;
        SpeedCurve cv = k.curve;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX(); downY = e.getY();
                begun = false; moved = false;
                drag = -1;
                float best = Ui.dp(26);
                double span = Math.max(1, k.out - k.in);
                for (int i = 0; i < cv.size(); i++) {
                    double gu = (k.curveSrc(cv.x[i]) - k.in) / span;
                    if (gu < -0.001 || gu > 1.001) continue;
                    float d = (float) Math.hypot(xAt(gu) - e.getX(), yAt(cv.v[i]) - e.getY());
                    if (d < best) { best = d; drag = i; }
                }
                if (drag >= 0) { ed.curvePoint = drag; invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > Ui.dp(4)) moved = true;
                if (drag < 0 || !moved) return true;
                if (!begun) { begun = true; ed.pause(); ed.beginEdit(); }
                VProject.Clip cur = ed.selClip();
                if (cur == null || cur.curve == null || drag >= cur.curve.size()) return true;
                long src = cur.in + Math.round(uAt(e.getX()) * (cur.out - cur.in));
                cur.curve.movePoint(drag, cur.curveU(src), speedAt(e.getY()));
                ed.changed();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!moved && drag < 0) {
                    // tap on the graph: jump to the moment showing that footage
                    int ci = ed.proj.clipIndex(k.id);
                    long src = k.in + Math.round(uAt(e.getX()) * (k.out - k.in));
                    ed.scrubTo(ed.proj.start(ci) + k.localAt(src));
                    ed.curvePoint = -1;
                }
                if (begun) ed.syncPlayers();
                drag = -1;
                ed.rebuildPanel();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                return true;
        }
        return true;
    }
}
