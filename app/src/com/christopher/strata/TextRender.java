package com.christopher.strata;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Text layers are vector until rendered: every render (preview, detail view, export tile) lays
 * the text out at its size in layer px and draws only the requested rect at the needed scale,
 * so text stays sharp at any output size.
 */
final class TextRender {
    static File fontsDir;
    static final String[] BUILTIN = {"sans-serif", "sans-serif-medium", "sans-serif-black", "sans-serif-light",
            "sans-serif-thin", "sans-serif-condensed", "serif", "monospace", "serif-monospace", "casual", "cursive",
            "sans-serif-smallcaps"};
    static final String[] BUILTIN_NAMES = {"Sans", "Sans Medium", "Sans Black", "Sans Light", "Sans Thin",
            "Sans Condensed", "Serif", "Mono", "Serif Mono", "Casual", "Script", "Small Caps"};
    private static final HashMap<String, Typeface> cache = new HashMap<String, Typeface>();

    static synchronized Typeface typeface(String font, boolean bold, boolean italic) {
        String key = font + (bold ? "|b" : "") + (italic ? "|i" : "");
        Typeface t = cache.get(key);
        if (t != null) return t;
        Typeface base = null;
        if (font.startsWith("asset:")) {
            // 3.15 bundled fonts: a variable font draws Bold at weight 700; a static one gets the platform's bold
            Fonts.F f = Fonts.byKey(font);
            Typeface at = Fonts.typeface(font, bold && f != null && f.variable() ? 700 : 0, false);
            if (at != null) {
                int st = (bold && (f == null || !f.variable()) ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
                t = st == 0 ? at : Typeface.create(at, st);
                cache.put(key, t);
                return t;
            }
        } else if (font.startsWith("file:")) {
            File f = new File(fontsDir, font.substring(5));
            try { if (f.exists()) base = Typeface.createFromFile(f); } catch (Throwable ignored) {}
        } else base = Typeface.create(font, Typeface.NORMAL);
        if (base == null) base = Typeface.SANS_SERIF;
        int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
        t = style == 0 ? base : Typeface.create(base, style);
        cache.put(key, t);
        return t;
    }

    /** Imported font files, as font keys ("file:name.ttf"). */
    static ArrayList<String> importedFonts() {
        ArrayList<String> out = new ArrayList<String>();
        File[] fs = fontsDir == null ? null : fontsDir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".ttf") || n.endsWith(".otf")) out.add("file:" + f.getName());
        }
        java.util.Collections.sort(out);
        return out;
    }

    static String displayName(String font) {
        Fonts.F af = Fonts.byKey(font);
        if (af != null) return af.name;
        if (font.startsWith("file:")) {
            String n = font.substring(5);
            int d = n.lastIndexOf('.');
            return d > 0 ? n.substring(0, d) : n;
        }
        for (int i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].equals(font)) return BUILTIN_NAMES[i];
        return font;
    }

    /** (Re)builds the layout and the layer's size. Call after any text parameter changes. */
    static void relayout(Layer l) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        tp.setTypeface(typeface(l.font, l.bold, l.italic));
        tp.setTextSize(Math.max(1f, l.textSize));
        tp.setLetterSpacing(l.tracking);
        tp.setColor(l.fillColor | 0xff000000);
        String s = l.text == null || l.text.isEmpty() ? " " : l.text;
        float maxW = 1;
        for (String line : s.split("\n", -1)) maxW = Math.max(maxW, Layout.getDesiredWidth(line, tp));
        int width = (int) Math.ceil(maxW) + 2;
        // wrapping: lay out at the wrap width, then shrink to the widest line it produced
        if (l.wrapW > 0 && width > l.wrapW) {
            Layout probe = StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(8, l.wrapW))
                    .setLineSpacing(0, Math.max(0.3f, l.leading)).setIncludePad(true).build();
            float wide = 1;
            for (int i = 0; i < probe.getLineCount(); i++) wide = Math.max(wide, probe.getLineWidth(i));
            width = Math.min(l.wrapW, (int) Math.ceil(wide) + 2);
        }
        Layout.Alignment al = l.align == 0 ? Layout.Alignment.ALIGN_NORMAL
                : (l.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER);
        l.layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, width)
                .setAlignment(al).setLineSpacing(0, Math.max(0.3f, l.leading)).setIncludePad(true).build();
        l.textPad = (int) Math.ceil(l.textSize * 0.35f) + 2;
        l.srcW = width + 2 * l.textPad;
        l.srcH = l.layout.getHeight() + 2 * l.textPad;
    }

    /** Renders the layer-px rect r at f bitmap px per layer px (capped at 8192 px per side). */
    static Bitmap render(Layer l, RectF r, float f) {
        if (l.layout == null) relayout(l);
        float bw = r.width() * f, bh = r.height() * f;
        float cap = Math.max(bw, bh);
        if (cap > 8192) f *= 8192 / cap;
        int w = Math.max(1, (int) Math.ceil(r.width() * f)), h = Math.max(1, (int) Math.ceil(r.height() * f));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / r.width(), h / r.height());
        c.translate(-r.left, -r.top);
        if ((l.textBg >>> 24) != 0) {
            // a box behind the text, the size of the layer (text plus its padding), softly rounded
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(l.textBg);
            float rad = l.textSize * 0.22f;
            c.drawRoundRect(new RectF(0, 0, l.srcW, l.srcH), rad, rad, bp);
        }
        c.translate(l.textPad, l.textPad);
        l.layout.draw(c);
        return b;
    }

    /** Preview bitmap of the whole layer, long edge up to 2048 px. */
    static void refreshPreview(Layer l) {
        // only text has a layout: a photo / video overlay's size is its media's (3.2 relaid out
        // B-roll and logos as text on every open and format change, shrinking them to a blank box)
        if (l.type != Layer.TEXT) return;
        relayout(l);
        float f = Math.min(1.5f, 2048f / Math.max(l.srcW, l.srcH));
        l.preview = render(l, new RectF(0, 0, l.srcW, l.srcH), f);
        l.thumb = null;
    }
}
