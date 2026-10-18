package com.christopher.strata;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import org.json.JSONObject;

/**
 * 3.14: the user's own presets, one folder per kind under filesDir/presets (speed curves now; text
 * styles and motion presets later). Each is {"v", "name", "created", "data"}; data is whatever the
 * kind stores. Like looks, they are app-wide, not part of a project.
 */
final class Presets {
    static File root;
    static final int VERSION = 1;
    static final String SPEED = "speed";

    static final class Preset {
        File file;
        String name;
        long created;
        Object data;   // JSONArray / JSONObject as stored
    }

    static File dir(String kind) { return root == null ? null : new File(root, kind); }

    static ArrayList<Preset> list(String kind) {
        ArrayList<Preset> out = new ArrayList<Preset>();
        File d = dir(kind);
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = new JSONObject(Loader.readText(f));
                if (o.optInt("v", 1) > VERSION) continue;   // a newer app's preset: skipped, not mangled
                Preset p = new Preset();
                p.file = f;
                p.name = o.optString("name", "Preset");
                p.created = o.optLong("created", f.lastModified());
                p.data = o.opt("data");
                if (p.data != null) out.add(p);
            } catch (Exception ignored) {}
        }
        Collections.sort(out, new Comparator<Preset>() {
            @Override public int compare(Preset a, Preset b) { return a.created < b.created ? -1 : a.created > b.created ? 1 : 0; }
        });
        return out;
    }

    static void save(String kind, String name, Object data) throws Exception {
        File d = dir(kind);
        if (d == null) throw new Exception("no storage");
        d.mkdirs();
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        o.put("v", VERSION);
        o.put("name", cleanName(name));
        o.put("created", now);
        o.put("data", data);
        Loader.writeText(new File(d, kind + "_" + now + ".json"), o.toString());
    }

    static void rename(Preset p, String name) throws Exception {
        JSONObject o = new JSONObject(Loader.readText(p.file));
        o.put("name", cleanName(name));
        Loader.writeText(p.file, o.toString());
    }

    static void delete(Preset p) { p.file.delete(); }

    /** Pure: a preset name is one trimmed line, at most 40 characters, never empty. */
    static String cleanName(String s) {
        String t = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 40) t = t.substring(0, 40).trim();
        return t.isEmpty() ? "Preset" : t;
    }
}
