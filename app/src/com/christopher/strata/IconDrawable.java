package com.christopher.strata;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/** One icon from {@link Icons}, scaled to its bounds. Paths are parsed once and cached. */
final class IconDrawable extends Drawable {
    private static final HashMap<String, Path> CACHE = new HashMap<String, Path>();
    private final Path src;
    private final boolean fill;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scaled = new Path();
    private final Matrix m = new Matrix();

    IconDrawable(String name, int color) {
        // 3.16.1: a missing glyph is cosmetic, so draw nothing and leave a breadcrumb instead of
        // crashing the editor. IconRefsTest fails the build-time check for any missing name.
        String d;
        if (Icons.has(name)) d = Icons.get(name);
        else { d = ""; Crash.note("missing icon " + name); }
        fill = d.startsWith("F|");
        synchronized (CACHE) {
            Path cached = CACHE.get(name);
            if (cached == null) {
                final Path path = new Path();
                SvgPath.parse(fill ? d.substring(2) : d, new SvgPath.Sink() {
                    @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
                    @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
                    @Override public void cubicTo(float a, float b, float c, float e, float x, float y) { path.cubicTo(a, b, c, e, x, y); }
                    @Override public void close() { path.close(); }
                });
                CACHE.put(name, path);
                cached = path;
            }
            src = cached;
        }
        p.setColor(color);
        p.setStyle(fill ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) { p.setColor(c); invalidateSelf(); }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float k = Math.min(b.width(), b.height()) / 24f;
        m.setScale(k, k);
        m.postTranslate(b.left + (b.width() - 24 * k) / 2f, b.top + (b.height() - 24 * k) / 2f);
        src.transform(m, scaled);
        p.setStrokeWidth((fill ? 1f : 1.75f) * k);
        c.drawPath(scaled, p);
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return Ui.dp(24); }
    @Override public int getIntrinsicHeight() { return Ui.dp(24); }
}
