package com.christopher.strata;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 3.14 tool + test: defines the built-in looks from the app's own Layer / Fx code, writes them to
 * assets/looks/ in the saved-look format, and dumps every render pass (program + uniforms + curve
 * LUT) for tests/lookpipe.py, which renders them through the real shaders for a contact sheet.
 * Run: bash tests/run.sh BuiltinLooks <assets/looks dir> <spec.json>
 */
public class BuiltinLooks {
    static Layer basic(float temp, float tint, float ev, float contrast, float hi, float sh, float wh, float bl, float vib, float sat) {
        Layer l = new Layer(); l.type = Layer.BASIC; l.name = "Light & Color";
        l.basic = new float[]{temp, tint, ev, contrast, hi, sh, wh, bl, vib, sat};
        return l;
    }

    /** Curves: master, red, green, blue point lists (x0,y0,x1,y1,...); null = straight. */
    static Layer curves(float[] master, float[] r, float[] g, float[] b) {
        Layer l = new Layer(); l.type = Layer.CURVES; l.name = "Curves";
        float[] id = {0, 0, 1, 1};
        l.curve = new float[][]{master == null ? id : master, r == null ? id : r, g == null ? id : g, b == null ? id : b};
        l.rebuildLut();
        return l;
    }

    /** HSL band (0 red .. 7 magenta): hue (-1..1 = +-30 deg), saturation, luminance. */
    static Layer hsl(float[][] bands) {
        Layer l = new Layer(); l.type = Layer.HSL; l.name = "HSL";
        for (float[] b : bands) { int k = (int) b[0]; l.hsl[k * 3] = b[1]; l.hsl[k * 3 + 1] = b[2]; l.hsl[k * 3 + 2] = b[3]; }
        return l;
    }

    static Layer fx(int id, float... params) {
        Layer l = new Layer(); l.type = Layer.EFFECT; Fx.defaults(l, id); l.name = Fx.get(id).name;
        for (int i = 0; i < params.length; i++) if (!Float.isNaN(params[i])) l.fxp[i] = params[i];
        return l;
    }

    static final float N = Float.NaN;   // keep the effect's default

    static final class Look { String file, name; List<Layer> layers = new ArrayList<Layer>(); }

    static Look look(String file, String name, Layer... ls) {
        Look k = new Look(); k.file = file; k.name = name;
        for (Layer l : ls) k.layers.add(l);
        return k;
    }

    static List<Look> all() {
        List<Look> o = new ArrayList<Look>();
        o.add(look("teal_orange", "Teal & Orange",
                basic(-0.05f, 0, 0, 0.18f, -0.15f, 0.1f, 0, -0.05f, 0.1f, 0),
                fx(Fx.SPLIT, 30, 0.2f, 195, 0.28f, -0.1f),
                hsl(new float[][]{{1, 0, 0.12f, 0.05f}, {2, -0.35f, -0.15f, 0}, {3, 0.3f, -0.3f, -0.1f}, {4, 0.35f, 0.1f, -0.1f}, {5, -0.3f, 0.1f, -0.1f}})));
        o.add(look("bleach", "Bleach",
                fx(Fx.BLEACH, 0.6f),
                basic(-0.05f, 0, 0, 0.05f, -0.1f, 0, 0, 0, 0, -0.1f)));
        o.add(look("faded_film", "Faded film",
                curves(new float[]{0, 0.1f, 0.25f, 0.29f, 0.75f, 0.76f, 1, 0.93f}, null, null, new float[]{0, 0.05f, 1, 0.97f}),
                basic(0.12f, 0, 0, -0.05f, 0, 0, 0, 0, 0, -0.22f),
                fx(Fx.GRAIN, 0.14f, N, N)));
        o.add(look("night_neon", "Night neon",
                basic(-0.3f, 0.18f, 0.1f, 0.28f, -0.2f, 0.05f, 0.1f, -0.15f, 0.35f, 0),
                hsl(new float[][]{{5, 0, 0.3f, 0}, {6, 0, 0.3f, 0.05f}, {7, 0, 0.35f, 0.05f}, {2, 0, -0.3f, 0}}),
                fx(Fx.VIGNETTE, -0.45f, N, N, N, N, N)));
        o.add(look("warm_16mm", "Warm 16 mm",
                basic(0.28f, 0.04f, 0, 0, -0.1f, 0.05f, 0, 0, 0, -0.12f),
                curves(new float[]{0, 0.04f, 0.25f, 0.22f, 0.75f, 0.8f, 1, 0.97f}, null, null, null),
                fx(Fx.GRAIN, 0.28f, 0.0009f, 0.15f),
                fx(Fx.VIGNETTE, -0.35f, 0.55f, N, 0.6f, N, N)));
        o.add(look("mono_contrast", "Mono contrast",
                fx(Fx.BW),
                basic(0, 0, 0, 0.42f, -0.1f, -0.05f, 0.1f, -0.25f, 0, 0),
                fx(Fx.GRAIN, 0.15f, N, N)));
        o.add(look("vhs", "VHS",
                fx(Fx.CHROMA, 0.006f),
                basic(0.05f, 0.05f, 0, -0.12f, -0.2f, 0.1f, 0, 0.05f, 0, 0.18f),
                curves(new float[]{0, 0.07f, 1, 0.92f}, null, null, null),
                fx(Fx.GRAIN, 0.2f, 0.001f, 0.6f)));
        o.add(look("cold_steel", "Cold steel",
                basic(-0.35f, -0.03f, 0, 0.22f, -0.1f, 0, 0, -0.08f, 0, -0.35f),
                curves(null, null, null, new float[]{0, 0.06f, 0.5f, 0.52f, 1, 1})));
        o.add(look("pastel", "Pastel",
                basic(0.05f, 0.04f, 0.2f, -0.3f, -0.25f, 0.2f, 0, 0, 0.2f, -0.12f),
                curves(new float[]{0, 0.12f, 0.5f, 0.55f, 1, 0.97f}, null, null, null)));
        o.add(look("crushed_blacks", "Crushed blacks",
                basic(0, 0, 0, 0.25f, -0.05f, -0.05f, 0.05f, -0.35f, 0.05f, 0.05f),
                curves(new float[]{0, 0, 0.15f, 0.05f, 0.5f, 0.49f, 1, 1}, null, null, null)));
        o.add(look("sunset", "Sunset",
                basic(0.32f, 0.06f, 0, 0.08f, -0.15f, 0.05f, 0, 0, 0.05f, -0.05f),
                fx(Fx.SPLIT, 28, 0.25f, 285, 0.18f, 0)));
        o.add(look("cross_process", "Cross process",
                fx(Fx.CROSS, 0.7f),
                basic(0, 0, 0, 0.1f, 0, 0, 0, 0, 0, 0)));
        return o;
    }

    static JSONArray arr(float... v) throws Exception { JSONArray a = new JSONArray(); for (float x : v) a.put((double) x); return a; }

    static JSONObject pass(Layer l) throws Exception {
        JSONObject u = new JSONObject();
        JSONObject p = new JSONObject();
        p.put("prog", GlBase.adjProgram(l.type));
        u.put("uOpacity", arr(l.opacity)); u.put("uMode", arr(l.mode));
        switch (l.type) {
            case Layer.BASIC:
                u.put("uB0", arr(l.basic[0], l.basic[1], l.basic[2], l.basic[3]));
                u.put("uB1", arr(l.basic[4], l.basic[5], l.basic[6], l.basic[7]));
                u.put("uB2", arr(l.basic[8], l.basic[9], 0, 0));
                break;
            case Layer.CURVES: {
                u.put("uLutN", arr(Curves.N));
                JSONArray lut = new JSONArray();
                short[] h = l.lut;
                for (short s : h) lut.put((double) halfToFloat(s));
                p.put("lut", lut);
                break;
            }
            case Layer.HSL:
                for (int k = 0; k < 8; k++) u.put("uHsl[" + k + "]", arr(l.hsl[k * 3], l.hsl[k * 3 + 1], l.hsl[k * 3 + 2]));
                break;
            case Layer.EFFECT:
                u.put("uFx", arr(l.fx));
                u.put("uFxP0", arr(l.fxp[0], l.fxp[1], l.fxp[2], l.fxp[3]));
                u.put("uFxP1", arr(l.fxp[4], l.fxp[5], l.fxp[6], l.fxp[7]));
                for (int i = 0; i < 3; i++) { int c = l.fxc[i]; u.put("uFxC" + i, arr(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f)); }
                if (Fx.get(l.fx).twoPass) throw new IllegalStateException(l.name + ": built-in looks use single-pass effects only");
                break;
        }
        p.put("u", u);
        return p;
    }

    /** Serializer over the org.json API (the desktop shim has no toString); same JSON Android writes. */
    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            StringBuilder b = new StringBuilder("{");
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { String k = it.next(); if (b.length() > 1) b.append(','); b.append(str(k)).append(':').append(json(j.opt(k))); }
            return b.append('}').toString();
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) { if (i > 0) b.append(','); b.append(json(a.opt(i))); }
            return b.append(']').toString();
        }
        if (o instanceof String) return str((String) o);
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(o);
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static float halfToFloat(short h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
        float v = e == 0 ? m / 1024f * (float) Math.pow(2, -14) : (float) ((1 + m / 1024.0) * Math.pow(2, e - 15));
        return s == 1 ? -v : v;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        JSONArray spec = new JSONArray();
        int checks = 0;
        for (Look k : all()) {
            JSONObject o = new JSONObject();
            o.put("v", Looks.VERSION); o.put("name", k.name); o.put("created", 0); o.put("builtin", true);
            JSONArray la = new JSONArray();
            JSONArray passes = new JSONArray();
            for (Layer l : k.layers) {
                if (!Looks.videoOk(l)) throw new AssertionError(k.name + ": " + l.name + " is photo-only");
                la.put(Looks.strip(l).toJson());
                passes.put(pass(l));
            }
            o.put("layers", la);
            Files.write(new File(dir, k.file + ".json").toPath(), json(o).getBytes("UTF-8"));
            // every layer must come back through Layer.fromJson (the loader's path) unchanged in kind
            // (the desktop org.json shim can't parse text; tests/lookpipe.py parses the files)
            for (int i = 0; i < la.length(); i++) {
                Layer back = Layer.fromJson(la.getJSONObject(i));
                if (back.type != k.layers.get(i).type || back.fx != k.layers.get(i).fx) throw new AssertionError(k.name + ": layer " + i + " changed in JSON");
            }
            checks++;
            JSONObject s = new JSONObject(); s.put("file", k.file); s.put("name", k.name); s.put("passes", passes);
            spec.put(s);
            System.out.println("ok   " + k.name + " (" + k.layers.size() + " layers) written and reloads");
        }
        Files.write(new File(args[1]).toPath(), json(spec).getBytes("UTF-8"));
        System.out.println(checks + " passed, 0 failed");
    }
}
