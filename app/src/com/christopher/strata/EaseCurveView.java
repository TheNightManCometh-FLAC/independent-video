package com.christopher.strata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * Bezier ease editor (Alight Motion / After Effects style): time across, progress up, from the
 * key's value (bottom-left) to the next key's (top-right). Two handles shape the curve; dragging
 * a handle above the top or below the bottom gives overshoot. Steep = fast, flat = slow.
 */
final class EaseCurveView extends View {
    interface Listener { void changed(float[] bez); }

    private final float[] bez;
    private final Listener lis;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int drag = -1;
    private static final float YLO = -0.6f, YHI = 1.6f;

    EaseCurveView(Context c, float[] bez, Listener lis) {
        super(c);
        this.bez = bez;
        this.lis = lis;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        setMeasuredDimension(w, Math.min(w, Ui.dp(320)));
    }

    private float side() { return Math.min(getWidth(), getHeight()) - Ui.dp(32); }
    private float ox() { return (getWidth() - side()) / 2; }
    private float sx(float x) { return ox() + x * side(); }
    private float sy(float y) { return Ui.dp(16) + (YHI - y) / (YHI - YLO) * side(); }
    private float ix(float px) { return (px - ox()) / side(); }
    private float iy(float py) { return YHI - (py - Ui.dp(16)) / side() * (YHI - YLO); }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.PANEL2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x14ffffff);
        c.drawRect(sx(0), sy(1), sx(1), sy(0), p);   // the 0..1 box (inside = no overshoot)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1));
        p.setColor(0x33ffffff);
        c.drawLine(sx(0), sy(0), sx(1), sy(1), p);   // linear, for reference
        path.reset();
        for (int i = 0; i <= 100; i++) {
            float x = i / 100f, y = Keys.bezier(bez, x);
            if (i == 0) path.moveTo(sx(x), sy(y)); else path.lineTo(sx(x), sy(y));
        }
        p.setStrokeWidth(Ui.dp(2.5f));
        p.setColor(Ui.ACCENT);
        c.drawPath(path, p);
        p.setStrokeWidth(Ui.dp(1.2f));
        p.setColor(0xaaffffff);
        c.drawLine(sx(0), sy(0), sx(bez[0]), sy(bez[1]), p);
        c.drawLine(sx(1), sy(1), sx(bez[2]), sy(bez[3]), p);
        p.setStyle(Paint.Style.FILL);
        for (int h = 0; h < 2; h++) {
            p.setColor(drag == h ? 0xffffffff : Ui.ACCENT);
            c.drawCircle(sx(bez[h * 2]), sy(bez[h * 2 + 1]), Ui.dp(drag == h ? 9 : 7), p);
        }
        p.setColor(Ui.DIM);
        p.setTextSize(Ui.dp(10));
        c.drawText("this key", sx(0) + Ui.dp(4), sy(0) - Ui.dp(4), p);
        String nx = "next key";
        c.drawText(nx, sx(1) - p.measureText(nx) - Ui.dp(4), sy(1) + Ui.dp(14), p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float best = Ui.dp(36);
                drag = -1;
                for (int h = 0; h < 2; h++) {
                    float d = (float) Math.hypot(sx(bez[h * 2]) - e.getX(), sy(bez[h * 2 + 1]) - e.getY());
                    if (d < best) { best = d; drag = h; }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (drag < 0) return true;
                bez[drag * 2] = Math.max(0, Math.min(1, ix(e.getX())));
                bez[drag * 2 + 1] = Math.max(YLO + 0.05f, Math.min(YHI - 0.05f, iy(e.getY())));
                if (lis != null) lis.changed(bez);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
