package com.christopher.strata;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 3.15: the shape part of the mask inspector, shared by the photo and video editors: the
 * "Add a shape" sheet (with real previews of every shape), making a shape where you're looking,
 * and the settings of the selected shape (operation, type options, text and font, feather, grow,
 * outline, reveal). The host owns the stack, undo and where things go; video adds keyframes.
 */
final class MaskPanel {
    interface Host {
        Activity act();
        /** The selected shape of the live layer, or null. */
        MaskStack.Comp shape();
        int shapeIndex();
        void beginEdit();
        void changed();
        void rebuild();
        void move(int dir);
        void delete();
        void duplicate();
        /** Host-specific rows under the settings (video: keyframes and music). */
        void extra(LinearLayout panel, MaskStack.Comp c);
        /** The user chose "Import a font file" in the font browser for this text shape. */
        void importFont();
    }

    private MaskPanel() {}

    // ---------------------------------------------------------------- making shapes
    static final String[] BASIC = {"rect", "rounded", "pill", "ellipse", "tri", "diamond", "pent", "hex", "oct", "star", "star6", "burst12"};
    static final String[] BASIC_NAMES = {"Rectangle", "Rounded", "Pill", "Ellipse", "Triangle", "Diamond", "Pentagon", "Hexagon", "Octagon", "Star", "Soft star", "Starburst"};

    /**
     * A new shape of a kind (BASIC, a MaskShapes id, or "text"), centred at (cx, cy) mask px,
     * about `size` mask px across. Text gets its box from its own ink (MaskRaster.aspect).
     */
    static MaskStack.Comp make(String kind, float cx, float cy, float size, String text) {
        MaskStack.Comp c = new MaskStack.Comp();
        c.x0 = cx; c.y0 = cy;
        c.feather = 0;
        float h = size / 2;
        c.x1 = h; c.y1 = h;
        if (kind.equals("text")) {
            c.type = MaskStack.TEXT;
            c.text = text == null || text.trim().isEmpty() ? "TEXT" : text;
            c.font = "asset:Anton.ttf";
            fitText(c, h * 1.6f);
            return c;
        }
        if (MaskShapes.exists(kind)) {
            c.type = MaskStack.SHAPE;
            c.shape = kind;
            float a = MaskShapes.aspect(kind);
            if (a >= 1) c.y1 = h / a; else c.x1 = h * a;
            return c;
        }
        c.type = MaskStack.POLY;
        switch (kind) {
            case "rect": c.type = MaskStack.RECT; c.y1 = h * 0.62f; break;
            case "rounded": c.type = MaskStack.RECT; c.y1 = h * 0.62f; c.round = 0.35f; break;
            case "pill": c.type = MaskStack.RECT; c.y1 = h * 0.4f; c.round = 1; break;
            case "ellipse": c.sides = MaskStack.ELLIPSE_SIDES; c.y1 = h * 0.75f; break;
            case "tri": c.sides = 3; break;
            case "diamond": c.sides = 4; c.x1 = h * 0.75f; break;
            case "pent": c.sides = 5; break;
            case "hex": c.sides = 6; break;
            case "oct": c.sides = 8; break;
            case "star": c.sides = 5; c.inner = 0.42f; break;
            case "star6": c.sides = 6; c.inner = 0.55f; c.round = 0.35f; break;
            default: c.sides = 12; c.inner = 0.72f; break;   // burst12
        }
        if (c.type == MaskStack.POLY && c.inner >= 1) c.inner = MaskStack.polyInner(c.sides);
        return c;
    }

    /** Sizes a text shape's box to its ink at half width hx (keeps the centre). */
    static void fitText(MaskStack.Comp c, float hx) {
        float a = Math.max(0.05f, MaskRaster.aspect(c));
        c.x1 = Math.max(1, hx);
        c.y1 = Math.max(1, hx / a);
    }

    /** After a text or font change: same height of letters, width follows the new ink. */
    static void refitText(MaskStack.Comp c) {
        float a = Math.max(0.05f, MaskRaster.aspect(c));
        c.x1 = Math.max(1, c.y1 * a);
    }

    /** A small picture of a shape kind (white on transparent) for the sheet's tiles. */
    static Bitmap thumb(String kind, int px) {
        int[] argb = new int[px * px];
        if (MaskShapes.exists(kind)) {
            float a = MaskShapes.aspect(kind);
            int bw = (int) (px * 0.78f * Math.min(1, a)), bh = (int) (px * 0.78f / Math.max(1, a));
            int S = 3;   // 3x3 supersampling for a smooth edge
            byte[] cov = MaskRaster.fill(MaskShapes.unit(kind), px * S, px * S, (px - bw) * S / 2, (px - bh) * S / 2, bw * S, bh * S);
            for (int y = 0; y < px; y++)
                for (int x = 0; x < px; x++) {
                    int s = 0;
                    for (int j = 0; j < S; j++) for (int i = 0; i < S; i++) s += cov[(y * S + j) * px * S + x * S + i] & 255;
                    argb[y * px + x] = ((s / (S * S)) << 24) | 0xffffff;
                }
        } else {
            MaskStack.Comp c = make(kind, px / 2f, px / 2f, px * 0.78f, null);
            for (int y = 0; y < px; y++)
                for (int x = 0; x < px; x++) {
                    int v = Math.round(c.value(x + 0.5f, y + 0.5f) * 255);
                    argb[y * px + x] = (v << 24) | 0xffffff;
                }
        }
        return Bitmap.createBitmap(argb, px, px, Bitmap.Config.ARGB_8888);
    }

    interface Pick { void kind(String kind); }

    /** The "Add a shape" sheet: basic shapes, the library, freehand. */
    static void addSheet(Activity a, final Pick cb) {
        Sheet sh = new Sheet(a, "Add a shape to the mask");
        sh.section("Basic");
        for (int i = 0; i < BASIC.length; i++) {
            final String k = BASIC[i];
            sh.tile("mask", BASIC_NAMES[i], new Runnable() { @Override public void run() { cb.kind(k); } });
            Sheet.setThumb(sh.lastTileDisc(), thumb(k, 96));
        }
        sh.section("Shapes");
        for (int i = 0; i < MaskShapes.IDS.length; i++) {
            final String k = MaskShapes.IDS[i];
            sh.tile("mask", MaskShapes.NAMES[i], new Runnable() { @Override public void run() { cb.kind(k); } });
            Sheet.setThumb(sh.lastTileDisc(), thumb(k, 96));
        }
        sh.section("Draw your own");
        sh.item("pencil", "Freehand outline", "Draw around anything with one finger; it closes itself", new Runnable() {
            @Override public void run() { cb.kind("freehand"); }
        });
        sh.show();
    }

    // ---------------------------------------------------------------- settings of the selected shape
    private static Ui.Slider slider(final Host h, String name, float min, float max, float def, float val,
                                    float scale, int dec, String unit, final Setter s) {
        Ui.Slider sl = new Ui.Slider(h.act(), name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { h.beginEdit(); }
            @Override public void change(float v) {
                MaskStack.Comp c = h.shape();
                if (c == null) return;
                s.set(c, v);
                h.changed();
            }
        });
        sl.display(scale, dec, unit);
        return sl;
    }

    interface Setter { void set(MaskStack.Comp c, float v); }

    private static View chip(final Host h, String label, boolean on, final Setter s, final float v) {
        return Ui.chip(h.act(), label, on, new View.OnClickListener() {
            @Override public void onClick(View x) {
                MaskStack.Comp c = h.shape();
                if (c == null) return;
                h.beginEdit();
                s.set(c, v);
                h.changed();
                h.rebuild();
            }
        });
    }

    static TextView hint(Activity a, String s) {
        TextView t = Ui.text(a, s, 12, Ui.DIM);
        t.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(4));
        return t;
    }

    /** Operation chips, invert and order/delete for the selected shape. */
    static void header(LinearLayout panel, final Host h, MaskStack.Comp c) {
        Activity a = h.act();
        panel.addView(Ui.label(a, c.name(h.shapeIndex())));
        View[] ops = new View[3];
        for (int i = 0; i < 3; i++) ops[i] = chip(h, MaskStack.OP_NAMES[i], c.op == i, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.op = (int) v; } }, i);
        panel.addView(Ui.strip(a, ops));
        panel.addView(Ui.strip(a,
                chip(h, "Invert", c.invert, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.invert = !x.invert; } }, 0),
                Ui.chip(a, "up", "Up", false, new View.OnClickListener() { @Override public void onClick(View v) { h.move(1); } }),
                Ui.chip(a, "down", "Down", false, new View.OnClickListener() { @Override public void onClick(View v) { h.move(-1); } }),
                Ui.chip(a, "copy", "Duplicate", false, new View.OnClickListener() { @Override public void onClick(View v) { h.duplicate(); } }),
                Ui.chip(a, "trash", "Delete", false, new View.OnClickListener() { @Override public void onClick(View v) { h.delete(); } })));
    }

    /** Everything for the selected shape below the header. */
    static void settings(final LinearLayout panel, final Host h, final MaskStack.Comp c) {
        final Activity a = h.act();
        if (c.type == MaskStack.TEXT) textRows(panel, h, c);
        if (c.type == MaskStack.SHAPE && c.shape != null) {
            TextView b = Ui.button(a, "Shape: " + MaskShapes.name(c.shape) + "   ›", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addSheet(a, new Pick() {
                        @Override public void kind(String k) {
                            MaskStack.Comp x = h.shape();
                            if (x == null || !MaskShapes.exists(k)) return;
                            h.beginEdit();
                            float area = x.x1 * x.y1, ar = MaskShapes.aspect(k);
                            x.shape = k;
                            x.x1 = (float) Math.sqrt(area * ar); x.y1 = (float) Math.sqrt(area / ar);
                            h.changed();
                            h.rebuild();
                        }
                    });
                }
            });
            panel.addView(b, blp());
        }
        if (c.type == MaskStack.POLY && c.sides < MaskStack.ELLIPSE_SIDES) {
            final boolean star = c.inner < MaskStack.polyInner(c.sides) - 1e-3f;
            panel.addView(Ui.strip(a,
                    chip(h, "Polygon", !star, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.inner = MaskStack.polyInner(x.sides); } }, 0),
                    chip(h, "Star", star, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.inner = Math.min(x.inner, 0.45f); if (x.inner >= MaskStack.polyInner(x.sides) - 1e-3f) x.inner = 0.45f; } }, 0)));
            panel.addView(slider(h, star ? "Points" : "Sides", 3, 16, 5, c.sides, 1, 0, "", new Setter() {
                @Override public void set(MaskStack.Comp x, float v) {
                    boolean wasStar = x.inner < MaskStack.polyInner(x.sides) - 1e-3f;
                    x.sides = Math.round(v);
                    if (!wasStar) x.inner = MaskStack.polyInner(x.sides);
                    else x.inner = Math.min(x.inner, MaskStack.polyInner(x.sides) - 0.01f);
                }
            }));
            if (star) panel.addView(slider(h, "Point depth", 0.05f, 0.95f, 0.45f, 1 - c.inner, 100, 0, "%", new Setter() {
                @Override public void set(MaskStack.Comp x, float v) { x.inner = Math.min(1 - v, MaskStack.polyInner(x.sides) - 0.01f); }
            }));
        }
        if (c.type == MaskStack.RECT || c.type == MaskStack.POLY && c.sides < MaskStack.ELLIPSE_SIDES)
            panel.addView(slider(h, "Roundness", 0, 1, 0, c.round, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.round = v; } }));
        panel.addView(slider(h, "Amount", 0, 1, 1, c.amount, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.amount = v; } }));
        if (c.type == MaskStack.RADIAL)
            panel.addView(slider(h, "Feather", 0, 1, 0.5f, c.feather, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.feather = v; } }));
        if (c.isBox()) {
            panel.addView(slider(h, "Soft edge", 0, 1, 0, c.feather, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.feather = v; } }));
            panel.addView(slider(h, "Grow / shrink", -1, 1, 0, c.grow, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.grow = v; } }));
            panel.addView(slider(h, "Outline only", 0, 1, 0, c.outline, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.outline = v; } }));
            panel.addView(slider(h, "Rotation", -180, 180, 0, wrap(c.angle), 1, 0, "°", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.angle = v; } }));
            panel.addView(slider(h, "Reveal", 0, 1, 1, c.reveal, 100, 0, "%", new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.reveal = v; } }));
            if (c.reveal < 0.9999f || !c.keys.isEmpty()) {
                View[] rv = new View[MaskStack.REVEAL_NAMES.length];
                for (int i = 0; i < rv.length; i++)
                    rv[i] = chip(h, MaskStack.REVEAL_NAMES[i], c.revealMode == i, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.revealMode = (int) v; } }, i);
                panel.addView(Ui.strip(a, rv));
            }
        }
        h.extra(panel, c);
        panel.addView(hint(a, c.type == MaskStack.LINEAR
                ? "Drag on the picture: ● full effect, ○ none, the middle dot moves the whole gradient."
                : c.type == MaskStack.RADIAL
                ? "Drag on the picture: the centre dot moves it, the side dot sets width and angle, the top dot sets height."
                : "Drag inside the shape to move it. Corner dot: size. Side and bottom dots: stretch. Blue knob: rotate (snaps every 15°). Reveal wipes it on: animate it for write-on titles."));
    }

    static float wrap(float a) {
        a = a % 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static LinearLayout.LayoutParams blp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(4));
        return p;
    }

    private static void textRows(LinearLayout panel, final Host h, final MaskStack.Comp c) {
        final Activity a = h.act();
        TextView body = Ui.button(a, (c.text.length() > 60 ? c.text.substring(0, 60) + "…" : c.text) + "   ✎", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MaskStack.Comp x = h.shape();
                if (x == null) return;
                Pickers.text(a, "Mask text", x.text, true, new Pickers.TextCb() {
                    @Override public void text(String s) {
                        MaskStack.Comp y = h.shape();
                        if (y == null) return;
                        h.beginEdit();
                        y.text = s;
                        refitText(y);
                        h.changed();
                        h.rebuild();
                    }
                });
            }
        });
        body.setTypeface(TextRaster.typeface(c));
        body.setTextSize(20);
        body.setSingleLine(true);
        panel.addView(body, blp());
        TextView font = Ui.button(a, "Font: " + TextRender.displayName(c.font) + "   ›", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MaskStack.Comp x = h.shape();
                if (x == null) return;
                FontPicker.show(a, x.font, x.text, new Pickers.Pick() {
                    @Override public void picked(String key) { setFont(h, key); }
                    @Override public void importNew() { h.importFont(); }
                });
            }
        });
        panel.addView(font, blp());
        Fonts.F f = Fonts.byKey(c.font);
        ArrayList<View> row = new ArrayList<View>();
        if (f == null || !f.variable())
            row.add(chip(h, "Bold", c.weight >= 600, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.weight = x.weight >= 600 ? 0 : 700; refitText(x); } }, 0));
        row.add(chip(h, "Italic", c.italic, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.italic = !x.italic; refitText(x); } }, 0));
        String[] al = {"Left", "Center", "Right"};
        if (c.text.indexOf('\n') >= 0)
            for (int i = 0; i < 3; i++) row.add(chip(h, al[i], c.align == i, new Setter() { @Override public void set(MaskStack.Comp x, float v) { x.align = (int) v; refitText(x); } }, i));
        panel.addView(Ui.strip(a, row.toArray(new View[0])));
        if (f != null && f.variable())
            panel.addView(slider(h, "Weight", f.wmin, f.wmax, f.wdef, Fonts.weightFor(f, c.weight), 1, 0, "", new Setter() {
                @Override public void set(MaskStack.Comp x, float v) { x.weight = Math.round(v / 10f) * 10; refitText(x); }
            }));
        panel.addView(slider(h, "Letter spacing", -0.2f, 1f, 0, c.tracking, 100, 0, "", new Setter() {
            @Override public void set(MaskStack.Comp x, float v) { x.tracking = v; refitText(x); }
        }));
        if (c.text.indexOf('\n') >= 0)
            panel.addView(slider(h, "Line spacing", 0.5f, 2.5f, 1, c.leading, 1, 2, "×", new Setter() {
                @Override public void set(MaskStack.Comp x, float v) { x.leading = v; refitText(x); }
            }));
    }

    static void setFont(Host h, String key) {
        MaskStack.Comp x = h.shape();
        if (x == null) return;
        h.beginEdit();
        x.font = key;
        x.weight = 0;
        refitText(x);
        h.changed();
        h.rebuild();
    }
}
