package com.christopher.strata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Saved looks and the adjustment clipboard, shared by the photo and video editors.
 *
 * A look is an ordered stack of adjustment layers (bottom first) with their settings, blend
 * modes, opacity and Blend If ranges. Masks are not part of a look: they belong to one image.
 * Looks live in filesDir/looks as JSON; LUT layers refer to LUTs by name in the app-wide LUT
 * folder, so a look works in every project.
 *
 * The clipboard is in memory for this app session, so copying a photo's adjustments and
 * pasting them onto a clip (or the other way round) works across projects.
 */
final class Looks {
    static File dir;
    static final int VERSION = 1;

    /** Layer types video grading renders. Detail (two-pass sharpen/NR) is photo-only. */
    static boolean videoOk(Layer l) {
        return l.type == Layer.BASIC || l.type == Layer.CURVES || l.type == Layer.LEVELS || l.type == Layer.HSL
                || l.type == Layer.LUT || l.type == Layer.EFFECT;
    }

    static boolean lookable(Layer l) { return l.isAdjustment(); }

    /** A settings-only copy: no mask, no pixels, no id. */
    static Layer strip(Layer l) {
        Layer c = l.copy();
        c.mask = null;
        c.maskK = 0;
        c.comps.clear();
        c.paintOp = MaskStack.ADD;
        c.paintAt = 0;
        c.maskFile = null;
        c.target = 0;
        c.maskVer = 0;
        c.maskSavedVer = -1;
        c.preview = null;
        c.thumb = null;
        c.src = null;
        c.id = 0;
        c.visible = true;
        c.parent = 0;     // structure belongs to the document, not the look
        c.clip = false;
        return c;
    }

    // ------------------------------------------------------------------ clipboard
    private static ArrayList<Layer> clip;
    private static String clipLabel;

    static synchronized void copy(List<Layer> ls, String label) {
        ArrayList<Layer> c = new ArrayList<Layer>();
        for (Layer l : ls) if (lookable(l)) c.add(strip(l));
        clip = c.isEmpty() ? null : c;
        clipLabel = label;
        structured = null;
    }

    /**
     * 3.14: a video grade copied with its look groups (and their Strength) kept, for pasting onto
     * other clips. The flat copy above is still made, so a photo can paste the same adjustments.
     */
    private static ArrayList<Layer> structured;

    static synchronized void copyStructured(List<Layer> ls, String label) {
        copy(ls, label);
        ArrayList<Layer> s = new ArrayList<Layer>();
        for (Layer l : ls) if (l.type == Layer.GROUP || (lookable(l) && videoOk(l))) {
            Layer c = strip(l);
            c.id = l.id; c.parent = l.parent; c.visible = l.visible;
            s.add(c);
        }
        structured = s.isEmpty() ? null : s;
    }

    /** Copies of the structured clipboard (ids are the originals'; the caller renumbers), or null. */
    static synchronized ArrayList<Layer> pasteStructured() {
        if (structured == null) return null;
        ArrayList<Layer> out = new ArrayList<Layer>();
        for (Layer l : structured) out.add(l.copy());
        return out;
    }

    static synchronized boolean hasClip() { return clip != null && !clip.isEmpty(); }

    static synchronized String clipLabel() { return clipLabel == null ? "" : clipLabel; }

    /** Fresh copies of the clipboard (bottom first); the caller assigns ids. */
    static synchronized ArrayList<Layer> paste() {
        ArrayList<Layer> out = new ArrayList<Layer>();
        if (clip != null) for (Layer l : clip) out.add(l.copy());
        return out;
    }

    /** "3 adjustments" / "Curves" style summary. */
    static String describe(List<Layer> ls) {
        if (ls.size() == 1) return ls.get(0).name;
        return ls.size() + " adjustments";
    }

    // ------------------------------------------------------------------ saved looks
    /** 3.14: built-in looks ship as assets/looks/*.json in the same format as saved looks. */
    static android.content.res.AssetManager assets;

    static ArrayList<Look> builtIns() {
        ArrayList<Look> out = new ArrayList<Look>();
        if (assets == null) return out;
        try {
            String[] names = assets.list("looks");
            if (names == null) return out;
            java.util.Arrays.sort(names);
            for (String n : names) {
                if (!n.endsWith(".json")) continue;
                try {
                    JSONObject o = new JSONObject(assetText("looks/" + n));
                    Look k = new Look();
                    k.asset = "looks/" + n;
                    k.name = o.optString("name", "Look");
                    JSONArray a = o.optJSONArray("layers");
                    k.count = a == null ? 0 : a.length();
                    k.summary = k.count + (k.count == 1 ? " adjustment" : " adjustments");
                    out.add(k);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String assetText(String path) throws java.io.IOException {
        java.io.InputStream in = assets.open(path);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    static final class Look {
        String asset;   // built in (read-only), else a file in dir
        File file;
        String name;
        long created;
        int count;
        String summary = "";
        boolean photoOnly;   // contains layers video can't render
    }

    static ArrayList<Look> list() {
        ArrayList<Look> out = new ArrayList<Look>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = new JSONObject(Loader.readText(f));
                Look k = new Look();
                k.file = f;
                k.name = o.optString("name", "Look");
                k.created = o.optLong("created", f.lastModified());
                JSONArray a = o.optJSONArray("layers");
                k.count = a == null ? 0 : a.length();
                StringBuilder sb = new StringBuilder();
                if (a != null) for (int i = 0; i < a.length() && i < 4; i++) {
                    JSONObject j = a.getJSONObject(i);
                    int ty = j.optInt("type");
                    if (ty == Layer.DETAIL) k.photoOnly = true;
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(j.optString("name", ty >= 0 && ty < Layer.TYPE_NAMES.length ? Layer.TYPE_NAMES[ty] : "?"));
                }
                if (a != null) for (int i = 4; i < a.length(); i++) if (a.getJSONObject(i).optInt("type") == Layer.DETAIL) k.photoOnly = true;
                if (k.count > 4) sb.append(" · +").append(k.count - 4);
                k.summary = sb.toString();
                if (o.optInt("v", 1) <= VERSION) out.add(k);   // a newer app's look is skipped, not mangled
            } catch (Exception ignored) {}
        }
        Collections.sort(out, new Comparator<Look>() {
            @Override public int compare(Look a, Look b) { return a.created > b.created ? -1 : (a.created < b.created ? 1 : 0); }
        });
        return out;
    }

    static void save(String name, List<Layer> ls) throws Exception {
        if (dir == null) throw new Exception("no storage");
        dir.mkdirs();
        JSONObject o = new JSONObject();
        long now = System.currentTimeMillis();
        o.put("v", VERSION);
        o.put("name", name);
        o.put("created", now);
        JSONArray a = new JSONArray();
        for (Layer l : ls) if (lookable(l)) a.put(strip(l).toJson());
        if (a.length() == 0) throw new Exception("nothing to save: choose at least one adjustment");
        o.put("layers", a);
        Loader.writeText(new File(dir, "look_" + now + ".json"), o.toString());
    }

    /** The look's layers (bottom first), sanitized; the caller assigns ids. */
    static ArrayList<Layer> load(Look k) throws Exception {
        JSONObject o = new JSONObject(k.asset != null ? assetText(k.asset) : Loader.readText(k.file));
        JSONArray a = o.getJSONArray("layers");
        ArrayList<Layer> out = new ArrayList<Layer>();
        ProjectIO.Loaded log = new ProjectIO.Loaded();
        for (int i = 0; i < a.length(); i++) {
            Layer l = Layer.fromJson(a.getJSONObject(i));
            if (!lookable(l)) continue;
            ProjectIO.sanitize(l, log);
            out.add(strip(l));
        }
        return out;
    }

    static void rename(Look k, String name) throws Exception {
        JSONObject o = new JSONObject(Loader.readText(k.file));
        o.put("name", name);
        Loader.writeText(k.file, o.toString());
    }

    static void delete(Look k) { k.file.delete(); }
}
