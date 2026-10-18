package com.christopher.strata;

import android.graphics.Bitmap;
import android.text.StaticLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** One layer. Arrays are cloned on copy; bitmaps and strokes are shared (both treated as immutable). */
final class Layer {
    static final int IMAGE = 0, FILL = 1, BASIC = 2, CURVES = 3, LEVELS = 4, HSL = 5, DETAIL = 6, TEXT = 7, RETOUCH = 8, LUT = 9, EFFECT = 10, GROUP = 11;
    static final String[] TYPE_NAMES = {"Photo", "Fill", "Light & Color", "Curves", "Levels", "HSL", "Detail", "Text", "Retouch", "LUT", "Effect", "Group"};

    int id;
    int type;
    String name;
    boolean visible = true;
    float opacity = 1f;
    int mode = 0;

    // placed layers (photo, text): source size in layer px and placement in image px
    String src;              // photo: file name inside the project folder
    int srcW, srcH;          // full-resolution, orientation-corrected size (text: layout size)
    boolean isBase;
    Bitmap preview;          // transient, shared
    Bitmap thumb;            // transient, small copy for the layer list
    float cx, cy, scale = 1f, rot = 0f;

    int fillColor = 0xffffffff;   // fill layers and text colour

    // Light & Color: temp, tint, exposure, contrast, highlights, shadows, whites, blacks, vibrance, saturation
    float[] basic = new float[10];
    // Curves: points x0,y0,x1,y1... for master, red, green, blue
    float[][] curve = {{0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}};
    short[] lut;
    int lutVer;
    // Levels: per channel (master, r, g, b): inBlack, inWhite, gamma, outBlack, outWhite
    float[] levels = defaultLevels();
    // HSL: 8 bands x (hue, sat, lum)
    float[] hsl = new float[24];
    // Detail: sharpen amount, radius (img px), threshold, masking, NR luminance, NR color
    float[] det = {0, 1f, 0, 0, 0, 0};
    // LUT: file name in the shared luts folder
    String lutFile;
    // Effect: catalog id (Fx), parameters, colours
    int fx;
    float[] fxp = new float[8];
    int[] fxc = {0xff000000, 0xff808080, 0xffffffff};

    // Text
    String text = "";
    String font = "sans-serif";
    boolean bold, italic;
    float textSize = 200;    // layer px
    float tracking = 0;      // em
    float leading = 1f;      // line height multiplier
    int align = 1;           // 0 left, 1 centre, 2 right
    int textBg;              // box behind the text (ARGB, 0 = none): captions over busy footage
    /** Id of the group this layer is in (0 = top level). Children sit directly below their group's layer. */
    int parent;
    /** Clipped to the nearest unclipped layer below (in the same group): shows only where that layer has pixels. */
    boolean clip;
    int wrapW;               // wrap lines wider than this many px (0 = only at line breaks)
    StaticLayout layout;     // transient
    int textPad;             // transient

    // Retouch strokes (image px), drawn in order
    ArrayList<Retouch.Stroke> strokes = new ArrayList<Retouch.Stroke>();

    // Range mask: Blend If on the composite below
    int rangeMode;                               // 0 off, 1 luminosity, 2 color
    float[] rangeL = {0, 0, 1, 1};
    float[] rangeC = {0.5f, 0.5f, 0.5f, 0.12f};  // rgb + tolerance
    float rangeF = 0.1f;
    boolean rangeInv;

    // painted mask (ALPHA_8), null = none
    Bitmap mask;
    int maskVer;
    int maskSavedVer = -1;
    /**
     * 0: the mask covers the canvas (image space, Doc.maskW x maskH), as in Emulsion 3.0.
     * > 0: the mask is linked to this layer: mask px = maskK x (layer px - layer centre) + mask
     * centre, so moving, scaling or rotating the layer carries the mask with it. A retouch layer
     * links to its target photo (its placement fields hold a copy of the target's).
     */
    float maskK;
    /**
     * Editable mask components (MaskStack): shapes on top of / around the painted raster, which
     * sits at stack position paintAt with operation paintOp. Only used while mask != null.
     */
    java.util.ArrayList<MaskStack.Comp> comps = new java.util.ArrayList<MaskStack.Comp>();
    int paintOp = MaskStack.ADD, paintAt = 0;
    /**
     * 3.15 video effect masks: the painted part as brush strokes (MaskPaint), redrawn into `mask`
     * at vmW x vmH when the project opens. null = not a video mask (photo masks are PNG files).
     */
    ArrayList<MaskPaint.Op> paint;
    int vmW, vmH;
    /** Loaded from a file that said this layer had a mask (to report a missing mask file). */
    transient boolean hadMask;
    /** File the mask was last saved to (each save of a changed mask writes a new file). */
    String maskFile;
    /** Retouch: id of the photo layer it works on (0 = not resolved yet: the nearest photo below). */
    int target;
    /** Base correction: the photo layer id this Light & Color layer was made for by Quick adjust (0 = an ordinary layer). */
    int baseOf;
    /** Video grades: settings driven by the music (Music.Link). Empty for photo layers. */
    ArrayList<Music.Link> links = new ArrayList<Music.Link>();

    static float[] defaultLevels() {
        float[] l = new float[20];
        for (int c = 0; c < 4; c++) { l[c * 5] = 0; l[c * 5 + 1] = 1; l[c * 5 + 2] = 1; l[c * 5 + 3] = 0; l[c * 5 + 4] = 1; }
        return l;
    }

    boolean isAdjustment() { return (type >= BASIC && type <= DETAIL) || type == LUT || type == EFFECT; }

    boolean isPlaced() { return type == IMAGE || type == TEXT; }

    boolean movable() { return (type == IMAGE && !isBase) || type == TEXT || type == GROUP; }

    /** Can be a clipping base: has its own pixels. */
    boolean pixelLayer() { return type == IMAGE || type == TEXT || type == FILL; }

    Layer copy() {
        Layer l = new Layer();
        l.id = id; l.type = type; l.name = name; l.visible = visible; l.opacity = opacity; l.mode = mode;
        l.src = src; l.srcW = srcW; l.srcH = srcH; l.isBase = isBase; l.preview = preview; l.thumb = thumb;
        l.cx = cx; l.cy = cy; l.scale = scale; l.rot = rot;
        l.fillColor = fillColor;
        l.basic = basic.clone();
        l.curve = new float[4][];
        for (int i = 0; i < 4; i++) l.curve[i] = curve[i].clone();
        l.lut = lut; l.lutVer = lutVer;
        l.levels = levels.clone();
        l.hsl = hsl.clone();
        l.det = det.clone();
        l.lutFile = lutFile;
        l.fx = fx; l.fxp = fxp.clone(); l.fxc = fxc.clone();
        l.text = text; l.font = font; l.bold = bold; l.italic = italic; l.textSize = textSize;
        l.tracking = tracking; l.leading = leading; l.align = align; l.layout = layout; l.textPad = textPad;
        l.textBg = textBg; l.wrapW = wrapW;
        l.parent = parent; l.clip = clip;
        l.strokes = new ArrayList<Retouch.Stroke>(strokes);
        l.rangeMode = rangeMode; l.rangeL = rangeL.clone(); l.rangeC = rangeC.clone(); l.rangeF = rangeF; l.rangeInv = rangeInv;
        l.mask = mask; l.maskVer = maskVer; l.maskSavedVer = maskSavedVer;
        l.maskK = maskK; l.maskFile = maskFile; l.target = target; l.baseOf = baseOf;
        l.comps = MaskStack.copyAll(comps); l.paintOp = paintOp; l.paintAt = paintAt;
        l.paint = paint == null ? null : new ArrayList<MaskPaint.Op>(paint); l.vmW = vmW; l.vmH = vmH;
        l.links = new ArrayList<Music.Link>(links.size());
        for (Music.Link k : links) l.links.add(k.copy());
        return l;
    }

    void rebuildLut() {
        if (type != CURVES) return;
        lut = Curves.buildLut(curve);
        lutVer++;
    }

    /** Image px -> layer px affine rows (inverse of the placement). */
    void layerRows(float[] r0, float[] r1) {
        double t = Math.toRadians(rot), c = Math.cos(t), s = Math.sin(t);
        double sc = scale;
        r0[0] = (float) (c / sc); r0[1] = (float) (s / sc); r0[2] = (float) ((-c * cx - s * cy) / sc + srcW / 2.0);
        r1[0] = (float) (-s / sc); r1[1] = (float) (c / sc); r1[2] = (float) ((s * cx - c * cy) / sc + srcH / 2.0);
    }

    /**
     * Image px -> mask px of this layer's mask (a, b, c, d, e, f), for a mask of mw x mh.
     * The shader, the brush, the overlay and every mask conversion use this one map.
     */
    double[] maskAffine(int imgW, int imgH, int mw, int mh) {
        if (maskK <= 0) return new double[]{mw / (double) imgW, 0, 0, 0, mh / (double) imgH, 0};
        double t = Math.toRadians(rot), c = Math.cos(t), s = Math.sin(t), k = maskK / scale;
        // layer px - centre = R(-rot) (img - (cx, cy)) / scale
        return new double[]{k * c, k * s, -k * (c * cx + s * cy) + mw / 2.0,
                -k * s, k * c, -k * (-s * cx + c * cy) + mh / 2.0};
    }

    double[] maskAffine(int imgW, int imgH) { return maskAffine(imgW, imgH, mask.getWidth(), mask.getHeight()); }

    /** Layer px -> image px. */
    void layerToImg(float lx, float ly, float[] out) {
        double t = Math.toRadians(rot), c = Math.cos(t), s = Math.sin(t);
        double x = (lx - srcW / 2.0) * scale, y = (ly - srcH / 2.0) * scale;
        out[0] = (float) (cx + c * x - s * y);
        out[1] = (float) (cy + s * x + c * y);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("type", type); o.put("name", name); o.put("visible", visible);
        o.put("opacity", (double) opacity); o.put("mode", mode);
        if (src != null) o.put("src", src);
        o.put("srcW", srcW); o.put("srcH", srcH); o.put("isBase", isBase);
        if (parent != 0) o.put("parent", parent);
        if (clip) o.put("clip", true);
        o.put("cx", (double) cx); o.put("cy", (double) cy); o.put("scale", (double) scale); o.put("rot", (double) rot);
        o.put("fill", fillColor);
        o.put("basic", arr(basic));
        JSONArray cv = new JSONArray();
        for (float[] c : curve) cv.put(arr(c));
        o.put("curve", cv);
        o.put("levels", arr(levels));
        o.put("hsl", arr(hsl));
        o.put("det", arr(det));
        if (lutFile != null) o.put("lut", lutFile);
        if (type == EFFECT) {
            o.put("fx", fx);
            o.put("fxp", arr(fxp));
            JSONArray fc = new JSONArray();
            for (int c : fxc) fc.put(c);
            o.put("fxc", fc);
        }
        if (type == TEXT) {
            o.put("text", text); o.put("font", font); o.put("bold", bold); o.put("italic", italic);
            o.put("textSize", (double) textSize); o.put("tracking", (double) tracking);
            o.put("leading", (double) leading); o.put("align", align);
            if (textBg != 0) o.put("textBg", textBg);
            if (wrapW > 0) o.put("wrapW", wrapW);
        }
        if (!strokes.isEmpty()) {
            JSONArray sa = new JSONArray();
            for (Retouch.Stroke s : strokes) {
                JSONObject j = new JSONObject();
                j.put("pts", arr(s.pts)); j.put("rad", (double) s.rad); j.put("feather", (double) s.feather);
                j.put("ox", (double) s.offX); j.put("oy", (double) s.offY); j.put("heal", s.heal);
                sa.put(j);
            }
            o.put("strokes", sa);
        }
        o.put("rangeMode", rangeMode); o.put("rangeL", arr(rangeL)); o.put("rangeC", arr(rangeC));
        o.put("rangeF", (double) rangeF); o.put("rangeInv", rangeInv);
        o.put("hasMask", mask != null);
        if (mask != null && maskFile != null) o.put("maskFile", maskFile);
        if (maskK > 0) o.put("maskK", (double) maskK);
        if (!comps.isEmpty()) {
            JSONArray ca = new JSONArray();
            for (MaskStack.Comp c : comps) ca.put(c.toJson());
            o.put("mc", ca);
            o.put("po", paintOp);
            o.put("pa", paintAt);
        }
        if (paint != null) {
            o.put("vpaint", MaskPaint.toJson(paint));
            JSONArray vm = new JSONArray();
            vm.put(vmW); vm.put(vmH);
            o.put("vm", vm);
        }
        if (target != 0) o.put("target", target);
        if (baseOf != 0) o.put("baseOf", baseOf);
        if (!links.isEmpty()) {
            JSONArray la = new JSONArray();
            for (Music.Link k : links) la.put(k.toJson());
            o.put("links", la);
        }
        return o;
    }

    static Layer fromJson(JSONObject o) throws JSONException {
        Layer l = new Layer();
        l.id = o.getInt("id"); l.type = o.getInt("type");
        if (l.type < 0 || l.type >= TYPE_NAMES.length) l.type = BASIC;
        l.name = o.optString("name", TYPE_NAMES[l.type]);
        l.visible = o.optBoolean("visible", true); l.opacity = (float) o.optDouble("opacity", 1); l.mode = o.optInt("mode", 0);
        l.src = o.has("src") ? o.getString("src") : null;
        l.srcW = o.optInt("srcW"); l.srcH = o.optInt("srcH"); l.isBase = o.optBoolean("isBase");
        l.parent = o.optInt("parent", 0); l.clip = o.optBoolean("clip");
        l.cx = (float) o.optDouble("cx"); l.cy = (float) o.optDouble("cy");
        l.scale = (float) o.optDouble("scale", 1); l.rot = (float) o.optDouble("rot", 0);
        l.fillColor = o.optInt("fill", 0xffffffff);
        readInto(o.optJSONArray("basic"), l.basic);
        JSONArray cv = o.optJSONArray("curve");
        if (cv != null) for (int i = 0; i < 4 && i < cv.length(); i++) {
            float[] p = toFloats(cv.getJSONArray(i));
            if (p.length >= 4) l.curve[i] = p;
        }
        readInto(o.optJSONArray("levels"), l.levels);
        readInto(o.optJSONArray("hsl"), l.hsl);
        readInto(o.optJSONArray("det"), l.det);
        l.lutFile = o.has("lut") ? o.getString("lut") : null;
        if (l.type == EFFECT) {
            l.fx = o.optInt("fx");
            readInto(o.optJSONArray("fxp"), l.fxp);
            JSONArray fc = o.optJSONArray("fxc");
            if (fc != null) for (int i = 0; i < Math.min(3, fc.length()); i++) l.fxc[i] = fc.getInt(i);
        }
        l.text = o.optString("text", ""); l.font = o.optString("font", "sans-serif");
        l.bold = o.optBoolean("bold"); l.italic = o.optBoolean("italic");
        l.textSize = (float) o.optDouble("textSize", 200); l.tracking = (float) o.optDouble("tracking", 0);
        l.leading = (float) o.optDouble("leading", 1); l.align = o.optInt("align", 1);
        l.textBg = o.optInt("textBg", 0); l.wrapW = Math.max(0, o.optInt("wrapW", 0));
        JSONArray sa = o.optJSONArray("strokes");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject j = sa.getJSONObject(i);
            float[] pts = toFloats(j.getJSONArray("pts"));
            if (pts.length < 2) continue;
            l.strokes.add(new Retouch.Stroke(pts, (float) j.getDouble("rad"), (float) j.optDouble("feather", 0.35),
                    (float) j.getDouble("ox"), (float) j.getDouble("oy"), j.optBoolean("heal", true)));
        }
        l.rangeMode = o.optInt("rangeMode", 0);
        readInto(o.optJSONArray("rangeL"), l.rangeL);
        readInto(o.optJSONArray("rangeC"), l.rangeC);
        l.rangeF = (float) o.optDouble("rangeF", 0.1);
        l.rangeInv = o.optBoolean("rangeInv");
        l.maskFile = o.has("maskFile") ? o.getString("maskFile") : null;
        l.hadMask = o.optBoolean("hasMask");
        l.maskK = (float) o.optDouble("maskK", 0);
        JSONArray mc = o.optJSONArray("mc");
        if (mc != null) for (int i = 0; i < mc.length() && l.comps.size() < MaskStack.MAX; i++) {
            JSONObject co = mc.optJSONObject(i);
            if (co == null) continue;
            MaskStack.Comp c = MaskStack.Comp.fromJson(co);
            if (c.sanitize()) l.comps.add(c);
        }
        l.paintOp = o.optInt("po", MaskStack.ADD);
        if (l.paintOp < MaskStack.ADD || l.paintOp > MaskStack.INTERSECT) l.paintOp = MaskStack.ADD;
        l.paintAt = Math.max(0, Math.min(l.comps.size(), o.optInt("pa", 0)));
        JSONArray vm = o.optJSONArray("vm");
        if (vm != null && vm.length() >= 2) {
            l.paint = MaskPaint.fromJson(o.optJSONArray("vpaint"));
            l.vmW = Math.max(8, Math.min(4096, vm.optInt(0)));
            l.vmH = Math.max(8, Math.min(4096, vm.optInt(1)));
        }
        l.target = o.optInt("target", 0);
        l.baseOf = o.optInt("baseOf", 0);
        JSONArray la = o.optJSONArray("links");
        if (la != null) for (int i = 0; i < la.length(); i++) l.links.add(Music.Link.fromJson(la.getJSONObject(i)));
        l.rebuildLut();
        return l;
    }

    private static JSONArray arr(float[] a) throws JSONException {
        JSONArray j = new JSONArray();
        for (float f : a) j.put((double) f);
        return j;
    }

    private static float[] toFloats(JSONArray a) throws JSONException {
        float[] p = new float[a.length()];
        for (int k = 0; k < p.length; k++) p[k] = (float) a.getDouble(k);
        return p;
    }

    private static void readInto(JSONArray j, float[] a) throws JSONException {
        if (j == null) return;
        for (int i = 0; i < a.length && i < j.length(); i++) a[i] = (float) j.getDouble(i);
    }
}
