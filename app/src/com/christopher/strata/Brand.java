package com.christopher.strata;

import android.content.res.AssetManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/** Independent Video's look: the anaglyph colours, the display face, and the mode-card frame. */
final class Brand {
    static final int RED = 0xffff2638, BLUE = 0xff1e96ff;
    /** 1.0 home palette: true black, one neutral card, orange for everything you can press. */
    static final int BLACK = 0xff000000, CARD = 0xff0c0c0c, CARD_LINE = 0xff242424, MUTED = 0xff8a8a8a;
    static final int ORANGE = 0xffff8a1e, ORANGE_TINT = 0x1fff8a1e, ORANGE_LINE = 0x80ff8a1e;
    static final int BODY = 0xffb9bdc6;   // description text: brighter than Ui.DIM, still secondary
    static final String NAME = "Independent Video";
    static final String CONTACT = "cporter31415@gmail.com";

    /**
     * Ko-fi link, or null. It comes from assets/brand/donate.txt so the same code builds both
     * ways: the sideload APK ships the file; a Google Play build can leave it out (Play's
     * Payments policy restricts in-app links to donation pages that aren't tax-exempt).
     */
    static String donateUrl(AssetManager a) {
        try (java.io.InputStream in = a.open("brand/donate.txt")) {
            byte[] b = new byte[512]; int n = in.read(b);
            String s = n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
            return s.startsWith("https://") ? s : null;
        } catch (Exception e) { return null; }
    }

    static void openUrl(android.app.Activity act, String url) {
        try { act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception e) { android.widget.Toast.makeText(act, "No browser to open " + url, android.widget.Toast.LENGTH_LONG).show(); }
    }

    static void email(android.app.Activity act, String subject, String body) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + CONTACT));
        i.putExtra(android.content.Intent.EXTRA_SUBJECT, subject);
        if (body != null) i.putExtra(android.content.Intent.EXTRA_TEXT, body);
        try { act.startActivity(i); }
        catch (Exception e) { android.widget.Toast.makeText(act, "No email app. Write to " + CONTACT, android.widget.Toast.LENGTH_LONG).show(); }
    }

    private Brand() {}

    private static final HashMap<String, Typeface> faces = new HashMap<String, Typeface>();

    /** Archivo (bundled, OFL) at a weight, in its widest cut (wdth 125) to match the wordmark. */
    static synchronized Typeface display(AssetManager a, int weight) {
        String k = "w" + weight;
        Typeface t = faces.get(k);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(a, "fonts/Archivo.ttf").setFontVariationSettings("'wght' " + weight + ", 'wdth' 125").build();
        } catch (Throwable e) { t = null; }
        if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        faces.put(k, t);
        return t;
    }

    /** Mode card: a flat near-black panel with a hairline border (the press ripple is orange). */
    static final class Card extends Drawable {
        private final float r = Ui.dp(18);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Card() {
            fill.setColor(CARD);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(1));
            line.setColor(CARD_LINE);
        }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float h = Ui.dp(0.5f);
            box.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        }

        @Override public void draw(Canvas c) { c.drawRoundRect(box, r, r, fill); c.drawRoundRect(box, r, r, line); }

        /** Shape used as the ripple mask, so the press highlight stays inside the panel. */
        Drawable mask() {
            return new Drawable() {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                final RectF m = new RectF();
                @Override public void draw(Canvas c) { m.set(getBounds()); p.setColor(0xffffffff); c.drawRoundRect(m, r, r, p); }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(ColorFilter cf) {}
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            };
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * The title's frame: a camera viewfinder. Orange corner brackets over a faint hairline box,
     * so the wordmark reads as the picture in a frame.
     */
    static final class Viewfinder extends Drawable {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final RectF f = new RectF();

        Viewfinder() {
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(Ui.dp(1));
            box.setColor(0xff1c1c1c);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(Ui.dp(2));
            corner.setStrokeCap(Paint.Cap.ROUND);
            corner.setStrokeJoin(Paint.Join.ROUND);
            corner.setColor(ORANGE);
        }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float s = Ui.dp(1);
            f.set(b.left + s, b.top + s, b.right - s, b.bottom - s);
            float L = Ui.dp(20), rr = Ui.dp(6);
            path.reset();
            // each bracket: a short arm, a rounded corner, a short arm
            path.moveTo(f.left, f.top + L); path.lineTo(f.left, f.top + rr); path.quadTo(f.left, f.top, f.left + rr, f.top); path.lineTo(f.left + L, f.top);
            path.moveTo(f.right - L, f.top); path.lineTo(f.right - rr, f.top); path.quadTo(f.right, f.top, f.right, f.top + rr); path.lineTo(f.right, f.top + L);
            path.moveTo(f.right, f.bottom - L); path.lineTo(f.right, f.bottom - rr); path.quadTo(f.right, f.bottom, f.right - rr, f.bottom); path.lineTo(f.right - L, f.bottom);
            path.moveTo(f.left + L, f.bottom); path.lineTo(f.left + rr, f.bottom); path.quadTo(f.left, f.bottom, f.left, f.bottom - rr); path.lineTo(f.left, f.bottom - L);
        }

        @Override public void draw(Canvas c) {
            float r = Ui.dp(6);
            c.drawRoundRect(f, r, r, box);
            c.drawPath(path, corner);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
