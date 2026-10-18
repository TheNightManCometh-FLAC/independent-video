package com.christopher.strata;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The "Independent Video" wordmark as an old 3D-movie (anaglyph) print: a white core with a red
 * copy shifted left and a blue copy shifted right, each with its own glow. The three layers are
 * PNGs rendered at build time (tools/brand/wordmark.py) from the same glyph mask, so they line up
 * exactly; only the shift is animated. The blue layer is drawn additively over the red, like two
 * projector beams, so where their glows meet they mix toward violet instead of covering each other.
 *
 * Motion: on {@link #play()} the two copies start wide apart and converge (the moment the glasses
 * "lock"), then drift very slowly around the rest offset. 30 fps is plenty for that drift, and the
 * animator stops whenever the view leaves the window.
 */
final class WordmarkView extends View {
    /** Rest shift of each colour copy, as a fraction of the drawn width (the build preview used this). */
    static final float REST = 0.005f, OPEN = 0.016f, DRIFT = 0.0018f;

    private final Bitmap red, blue, core;
    private final Paint normal = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint add = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private float shift = OPEN;          // current shift, fraction of width
    private ValueAnimator anim;
    private long startMs = -1;

    WordmarkView(Context c) {
        super(c);
        red = asset(c, "brand/wm_red.png");
        blue = asset(c, "brand/wm_blue.png");
        core = asset(c, "brand/wm_core.png");
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        src.set(0, 0, core.getWidth(), core.getHeight());
        setContentDescription("Independent Video");
    }

    /** Brand images live in assets/, not res/, so nothing depends on the generated R class
     *  (its package follows the application id, which differs from the Java package). */
    static Bitmap asset(Context c, String path) {
        try (java.io.InputStream in = c.getAssets().open(path)) { return BitmapFactory.decodeStream(in); }
        catch (java.io.IOException e) { throw new IllegalStateException("missing " + path, e); }
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * core.getHeight() / (float) core.getWidth()));
    }

    /** Converge from wide apart, then drift. Safe to call again (restarts). */
    void play() { startMs = android.os.SystemClock.uptimeMillis(); ensureAnim(); }

    /** Show at rest with no intro (e.g. when coming back from an editor). */
    void rest() { if (startMs < 0) startMs = android.os.SystemClock.uptimeMillis() - 2000; ensureAnim(); }

    private void ensureAnim() {
        if (anim != null || !isAttachedToWindow()) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            long lastFrame;
            @Override public void onAnimationUpdate(ValueAnimator a) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastFrame < 33) return;   // ~30 fps is plenty for a slow drift
                lastFrame = now;
                shift = shiftAt(now - startMs);
                invalidate();
            }
        });
        anim.start();
    }

    /** Pure: shift at t ms after play(). 1.1 s ease-out convergence, then a 7 s sine drift. */
    static float shiftAt(long ms) {
        if (ms < 0) return OPEN;
        float t = ms / 1000f;
        if (t < 1.1f) { float u = t / 1.1f; u = 1f - (1f - u) * (1f - u) * (1f - u); return OPEN + (REST - OPEN) * u; }
        return REST + DRIFT * (float) Math.sin((t - 1.1f) * Math.PI * 2 / 7.0);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (startMs >= 0) ensureAnim(); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v != VISIBLE && anim != null) { anim.cancel(); anim = null; }
        else if (v == VISIBLE && startMs >= 0) ensureAnim();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0) return;
        float dx = shift * w;
        dst.set(-dx, 0, w - dx, h); c.drawBitmap(red, src, dst, normal);
        dst.set(dx, 0, w + dx, h); c.drawBitmap(blue, src, dst, add);
        dst.set(0, 0, w, h); c.drawBitmap(core, src, dst, normal);
    }
}
