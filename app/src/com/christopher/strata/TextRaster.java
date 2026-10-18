package com.christopher.strata;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * 3.15: text for mask components (MaskRaster.TextSource), drawn with the platform's text engine
 * so every bundled or imported font, kerning and ligatures work. The block is measured by its
 * ink (what the letters actually cover), so a text mask's box hugs the letters and its handles
 * sit on them, then drawn scaled into the distance texture's plane.
 */
final class TextRaster implements MaskRaster.TextSource {
    private static final float REF = 160f;   // reference text size for measuring (px)

    private static final class Measured {
        StaticLayout layout;
        float ix, iy, iw, ih;   // ink bounds in layout px
    }

    private final HashMap<String, Measured> cache = new HashMap<String, Measured>();

    static Typeface typeface(MaskStack.Comp c) {
        Typeface t = Fonts.typeface(c.font, c.weight, c.italic);
        if (t != null) return t;
        boolean bold = c.weight >= 600;
        return TextRender.typeface(c.font == null ? "sans-serif" : c.font, bold, c.italic);
    }

    private static StaticLayout layout(MaskStack.Comp c, float size) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        tp.setTypeface(typeface(c));
        tp.setTextSize(size);
        tp.setLetterSpacing(c.tracking);
        tp.setColor(0xffffffff);
        String s = c.text == null || c.text.isEmpty() ? " " : c.text;
        float maxW = 1;
        for (String line : s.split("\n", -1)) maxW = Math.max(maxW, Layout.getDesiredWidth(line, tp));
        Layout.Alignment al = c.align == 0 ? Layout.Alignment.ALIGN_NORMAL
                : (c.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER);
        return StaticLayout.Builder.obtain(s, 0, s.length(), tp, (int) Math.ceil(maxW) + 2)
                .setAlignment(al).setLineSpacing(0, Math.max(0.3f, c.leading)).setIncludePad(true).build();
    }

    private synchronized Measured measure(MaskStack.Comp c) {
        String k = MaskRaster.contentKey(c);
        Measured m = cache.get(k);
        if (m != null) return m;
        m = new Measured();
        m.layout = layout(c, REF);
        int pad = (int) (REF * 0.6f);
        int w = m.layout.getWidth() + 2 * pad, h = m.layout.getHeight() + 2 * pad;
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ALPHA_8);
        Canvas cv = new Canvas(b);
        cv.translate(pad, pad);
        m.layout.draw(cv);
        byte[] a = bytes(b);
        int rb = b.getRowBytes();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((a[y * rb + x] & 255) > 8) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
        b.recycle();
        if (x1 < 0) { m.ix = 0; m.iy = 0; m.iw = Math.max(1, m.layout.getWidth()); m.ih = Math.max(1, m.layout.getHeight()); }
        else { m.ix = x0 - pad; m.iy = y0 - pad; m.iw = x1 - x0 + 1; m.ih = y1 - y0 + 1; }
        if (cache.size() > 48) cache.clear();
        cache.put(k, m);
        return m;
    }

    @Override public float aspect(MaskStack.Comp c) {
        Measured m = measure(c);
        return m.iw / Math.max(1f, m.ih);
    }

    @Override public byte[] draw(MaskStack.Comp c, int W, int H, int ox, int oy, int cw, int ch) {
        Measured m = measure(c);
        Bitmap b;
        try { b = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8); } catch (OutOfMemoryError e) { return null; }
        Canvas cv = new Canvas(b);
        cv.translate(ox, oy);
        cv.scale(cw / m.iw, ch / m.ih);
        cv.translate(-m.ix, -m.iy);
        synchronized (m) { m.layout.draw(cv); }
        byte[] a = bytes(b);
        int rb = b.getRowBytes();
        b.recycle();
        if (rb == W) return a;
        byte[] o = new byte[W * H];
        for (int y = 0; y < H; y++) System.arraycopy(a, y * rb, o, y * W, W);
        return o;
    }

    static byte[] bytes(Bitmap b) {
        ByteBuffer buf = ByteBuffer.allocate(b.getRowBytes() * b.getHeight());
        b.copyPixelsToBuffer(buf);
        return buf.array();
    }

    /** An ALPHA_8 bitmap holding a distance texture (made once per texture, for upload). */
    static Bitmap image(MaskRaster.Sdf s) {
        Object o = s.image;
        if (o instanceof Bitmap && !((Bitmap) o).isRecycled()) return (Bitmap) o;
        Bitmap b = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ALPHA_8);
        int rb = b.getRowBytes();
        byte[] src = s.a;
        if (rb != s.w) {
            src = new byte[rb * s.h];
            for (int y = 0; y < s.h; y++) System.arraycopy(s.a, y * s.w, src, y * rb, s.w);
        }
        b.copyPixelsFromBuffer(ByteBuffer.wrap(src));
        s.image = b;
        return b;
    }
}
