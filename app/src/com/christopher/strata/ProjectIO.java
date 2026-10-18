package com.christopher.strata;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/**
 * Loading project files safely.
 *
 * 1. Recovery: doc.json / video.json is tried first, then the .tmp a save left behind if it
 *    crashed between writing and renaming (complete and fsynced, so newer and valid), then the
 *    .bak copy of the last file that loaded cleanly.
 * 2. Versions: every file carries "v". A file from a newer Emulsion is refused rather than
 *    loaded with its unknown fields dropped and then autosaved over (silent data loss after a
 *    downgrade). Older versions pass through migrate(), one step per version.
 * 3. Sanitizing: after parsing, every value the renderer, timeline or exporter indexes, divides
 *    by or loops over is clamped to its legal range, so a damaged or hand-edited file opens with
 *    defaults instead of crashing the editor (NaN coordinates, out-of-range blend modes,
 *    zero-length clips, mismatched keyframes, duplicate ids that would share a mask file).
 */
final class ProjectIO {
    /** Bump when the saved format changes, and add the step to migrate(). */
    static final int DOC_VERSION = 5, VIDEO_VERSION = 19;   // video v19 (3.16): equalizers, tape pitch, 0.1x-10x speed, shaped speed curves; doc v5 / video v18 (3.15): shape and text masks, mask keyframes, masks on video grades, visualizer styles; video v17: look groups in grade stacks; v16: markers, default transition; v15: timing locks; v14: edit-stable automation origins and split fade envelopes; v13: independent audio control, ranges, amount keys, B-roll mix; v12: footage look (pgrade), overlay grades; v11: ramps windowed by their clips; v10: per-property keys, Bezier eases; v9: speed curves, freeze frames; v8: visualizer echoes; v7: adaptive music response, visualizer auto level; v6: music sync track choice (off by default); v5: connected items; v4: music links   // doc v4: editable mask components; v2: layer groups and clipping; v3: per-save mask files, linked masks, retouch targets   // video v2: media overlays; v3: captions, text boxes, volume keys, ducking

    static final class Loaded {
        JSONObject json;
        /** Which file it came from: "" (the project file), ".tmp" or ".bak". */
        String from = "";
        /** What was repaired, for the crash log and a notice. Empty when the file was clean. */
        final ArrayList<String> fixes = new ArrayList<String>();
        boolean recovered() { return from.length() > 0; }
    }

    static final class NewerVersion extends Exception {
        NewerVersion() { super("this project was saved by a newer version of Independent Video. Update the app to open it."); }
    }

    /** Reads name ("doc.json" or "video.json") from dir with the recovery chain and version gate. */
    static Loaded read(File dir, String name, int current) throws Exception {
        File main = new File(dir, name), tmp = new File(dir, name + ".tmp"), bak = new File(dir, name + ".bak");
        Exception first = null;
        // a .tmp newer than the main file is a save that never got renamed into place
        File[] order = tmp.exists() && (!main.exists() || tmp.lastModified() >= main.lastModified())
                ? new File[]{tmp, main, bak} : new File[]{main, tmp, bak};
        for (File f : order) {
            if (!f.exists() || f.length() == 0) continue;
            JSONObject o;
            try {
                o = new JSONObject(Loader.readText(f));
            } catch (Exception e) {
                if (first == null) first = new Exception(f.getName() + " is damaged (" + e.getMessage() + ")");
                continue;
            }
            int v = o.optInt("v", 1);
            if (v > current) throw new NewerVersion();
            Loaded l = new Loaded();
            l.json = migrate(o, v, current);
            l.from = f == main ? "" : f.getName().substring(name.length());
            return l;
        }
        throw first != null ? first : new Exception(name + " is missing");
    }

    /** One step per version: v1 -> v2 -> ... (video v1 -> v2 only added fields, nothing to convert). */
    static JSONObject migrate(JSONObject o, int from, int to) throws JSONException {
        for (int v = from; v < to; v++) {
            switch (v) {
                default: break;
            }
        }
        o.put("v", to);
        return o;
    }

    /** Photo source files ("src") a saved doc refers to; empty if it's missing or unreadable. */
    static java.util.HashSet<String> sourcesIn(File f) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        try {
            if (!f.exists()) return out;
            org.json.JSONArray a = new JSONObject(Loader.readText(f)).optJSONArray("layers");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String s = a.getJSONObject(i).optString("src", null);
                if (s != null && s.startsWith("src_") && !s.contains("/")) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** After a clean load: keep a copy of the file that worked, for the next failure. */
    static void keepBackup(File dir, String name) {
        try {
            File main = new File(dir, name);
            if (main.exists() && main.length() > 0) Loader.writeText(new File(dir, name + ".bak"), Loader.readText(main));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ helpers
    static float fin(float v, float def) { return Float.isNaN(v) || Float.isInfinite(v) ? def : v; }
    static float clamp(float v, float lo, float hi, float def) { v = fin(v, def); return Math.max(lo, Math.min(hi, v)); }
    static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void fixArr(float[] a, float[] def, String what, Loaded log) {
        boolean bad = false;
        for (int i = 0; i < a.length; i++)
            if (Float.isNaN(a[i]) || Float.isInfinite(a[i])) { a[i] = def != null && i < def.length ? def[i] : 0; bad = true; }
        if (bad) log.fixes.add(what + ": invalid numbers reset");
    }

    // ------------------------------------------------------------------ photo documents
    static void sanitize(Doc d, Loaded log) {
        if (d.imgW < 1 || d.imgH < 1 || d.imgW > 100000 || d.imgH > 100000) {
            // nothing sensible to fall back on: the base layer's size, else refuse
            Layer base = null;
            for (Layer l : d.layers) if (l.type == Layer.IMAGE && l.isBase) base = l;
            if (base == null || base.srcW < 1 || base.srcH < 1) throw new IllegalStateException("the image size is missing");
            d.imgW = base.srcW; d.imgH = base.srcH;
            d.resetCrop();
            log.fixes.add("image size restored from the photo");
        }
        if (!(d.cropW >= 1 && d.cropH >= 1 && d.cropW < 1e6 && d.cropH < 1e6) || Float.isNaN(d.cropCx) || Float.isNaN(d.cropCy)
                || Float.isInfinite(d.cropCx) || Float.isInfinite(d.cropCy)) {
            d.resetCrop();
            log.fixes.add("crop reset");
        }
        d.rot90 = ((d.rot90 % 4) + 4) % 4;
        d.straighten = clamp(d.straighten, -45, 45, 0);
        int maxId = 0;
        HashSet<Integer> ids = new HashSet<Integer>();
        for (Layer l : d.layers) {
            sanitize(l, log);
            if (l.id < 1 || !ids.add(l.id)) l.id = -1;   // reassigned below
            else maxId = Math.max(maxId, l.id);
        }
        for (Layer l : d.layers) if (l.id == -1) {
            l.id = ++maxId;
            ids.add(l.id);
            l.mask = null;   // a duplicate id can't claim the other layer's mask file
            l.comps.clear();
            l.maskFile = null;
            log.fixes.add("duplicate layer id repaired (" + l.name + ")");
        }
        if (d.nextId <= maxId) {
            d.nextId = maxId + 1;   // otherwise the next new layer collides with an existing one
            log.fixes.add("layer id counter repaired");
        }
        d.normalizeGroups();   // groups contiguous, dangling group references and invalid clips dropped
    }

    static void sanitize(Layer l, Loaded log) {
        if (l.name == null) l.name = Layer.TYPE_NAMES[l.type];
        l.opacity = clamp(l.opacity, 0, 1, 1);
        l.mode = clampI(l.mode, 0, Editor.MODES.length - 1);
        l.cx = fin(l.cx, 0); l.cy = fin(l.cy, 0);
        l.scale = clamp(l.scale, 1e-4f, 1e4f, 1);
        l.rot = fin(l.rot, 0);
        if (l.srcW < 0) l.srcW = 0;
        if (l.srcH < 0) l.srcH = 0;
        fixArr(l.basic, null, l.name, log);
        fixArr(l.levels, Layer.defaultLevels(), l.name, log);
        fixArr(l.hsl, null, l.name, log);
        fixArr(l.det, new float[]{0, 1f, 0, 0, 0, 0}, l.name, log);
        fixArr(l.fxp, null, l.name, log);
        fixArr(l.rangeL, new float[]{0, 0, 1, 1}, l.name, log);
        fixArr(l.rangeC, new float[]{0.5f, 0.5f, 0.5f, 0.12f}, l.name, log);
        for (int c = 0; c < l.curve.length; c++) {
            float[] p = l.curve[c];
            boolean ok = p != null && p.length >= 4 && p.length % 2 == 0;
            if (ok) for (float v : p) if (!(v >= 0 && v <= 1)) { ok = false; break; }
            if (ok) for (int i = 2; i < p.length; i += 2) if (p[i] < p[i - 2]) { ok = false; break; }
            if (!ok) { l.curve[c] = new float[]{0, 0, 1, 1}; log.fixes.add(l.name + ": curve reset"); }
        }
        l.fx = clampI(l.fx, 0, Fx.ALL.length - 1);
        l.rangeMode = clampI(l.rangeMode, 0, 2);
        l.rangeF = clamp(l.rangeF, 0, 1, 0.1f);
        l.maskK = clamp(l.maskK, 0, 1000, 0);
        if (l.target < 0) l.target = 0;
        if (l.maskFile != null && (l.maskFile.contains("/") || l.maskFile.contains("\\") || !l.maskFile.startsWith("mask_"))) {
            l.maskFile = null;   // only ever a file name in the project folder
            log.fixes.add(l.name + ": mask file name reset");
        }
        l.align = clampI(l.align, 0, 2);
        l.textSize = clamp(l.textSize, 1, 20000, 200);
        l.tracking = clamp(l.tracking, -1, 5, 0);
        l.leading = clamp(l.leading, 0.1f, 10, 1);
        l.wrapW = Math.max(0, Math.min(20000, l.wrapW));
        if (l.text == null) l.text = "";
        if (l.font == null) l.font = "sans-serif";
        for (int i = l.strokes.size() - 1; i >= 0; i--) {
            Retouch.Stroke s = l.strokes.get(i);
            boolean ok = s.pts.length >= 2 && s.pts.length % 2 == 0 && s.rad > 0 && !Float.isNaN(s.rad) && !Float.isInfinite(s.rad)
                    && !Float.isNaN(s.offX) && !Float.isNaN(s.offY);
            if (ok) for (float v : s.pts) if (Float.isNaN(v) || Float.isInfinite(v)) { ok = false; break; }
            if (!ok) { l.strokes.remove(i); log.fixes.add(l.name + ": damaged retouch stroke dropped"); }
        }
        for(Music.Link k:l.links){
            k.audio=Math.max(AudioControl.SELF,k.audio);
            // Negative starts and empty ranges are intentional after a head trim or split.
            k.startUs=Math.max(-86400000000L,Math.min(86400000000L,k.startUs));
            k.endUs=Math.max(-1,Math.min(86400000000L,k.endUs));
            k.phaseUs=Math.max(-1,Math.min(86400000000L,k.phaseUs));
            k.amount=fin(k.amount,0);k.fadeIn=clamp(k.fadeIn,0,10,0);k.fadeOut=clamp(k.fadeOut,0,10,0);
            sanitize(k.amountKeys,new float[]{k.amount},l.name+" automation",log);
        }
        l.rebuildLut();
    }

    // ------------------------------------------------------------------ video projects
    static void sanitize(VProject p, Loaded log) {
        p.resIdx = clampI(p.resIdx, 0, VProject.RES.length - 1);
        p.aspectIdx = clampI(p.aspectIdx, 0, VProject.ASPECTS.length - 1);
        p.fpsIdx = clampI(p.fpsIdx, 0, VProject.FPS.length - 1);
        int maxId = 0;
        HashSet<Integer> ids = new HashSet<Integer>();
        ArrayList<Object> dup = new ArrayList<Object>();
        for (int i = p.clips.size() - 1; i >= 0; i--) {
            VProject.Clip c = p.clips.get(i);
            if (c.uri == null || c.uri.length() == 0) { p.clips.remove(i); log.fixes.add("clip without a file dropped"); continue; }
            c.kind = c.kind == VProject.IMAGE ? VProject.IMAGE : VProject.VIDEO;
            if (c.srcDur <= 0) c.srcDur = Math.max(1, c.out);
            c.speed = clamp(c.speed, 0.1f, 10, 1);
            if (c.curve != null && c.kind != VProject.VIDEO) c.curve = null;   // curves are for video footage
            if (c.curve != null && c.curveOut <= c.curveIn) { c.curveIn = c.in; c.curveOut = Math.max(c.in + 1000, c.out); log.fixes.add(c.name + ": speed curve range repaired"); }
            if (c.frameUs >= 0 && c.kind != VProject.IMAGE) c.frameUs = -1;
            long minLen = 1000;   // 1 ms: anything shorter divides the timeline by ~0
            if (c.kind == VProject.IMAGE) {
                c.in = 0;
                // any positive length is valid (edits allow one frame); only a broken value is reset
                if (c.out < 1000) { c.out = 3000000; log.fixes.add(c.name + ": length reset"); }
            } else {
                long in = Math.max(0, Math.min(c.in, c.srcDur - minLen)), out = Math.min(c.out, c.srcDur);
                if (out < in + minLen) { in = 0; out = c.srcDur; log.fixes.add(c.name + ": trim reset"); }
                c.in = in; c.out = out;
            }
            c.volume = clamp(c.volume, 0, 2, 1);
            c.fit = clampI(c.fit, 0, 1);
            c.scale = clamp(c.scale, 0.01f, 100, 1);
            c.panX = fin(c.panX, 0); c.panY = fin(c.panY, 0); c.rot = fin(c.rot, 0);
            c.trans = clampI(c.trans, 0, VProject.TRANS.length - 1);
            c.transDur = Math.max(0, Math.min(c.transDur, 10000000L));
            c.opacity = clamp(c.opacity, 0, 1, 1);
            c.reactAudio=Math.max(AudioControl.SELF,c.reactAudio);
            c.reactSrc = clampI(c.reactSrc, 0, 2);
            c.pulse = clamp(c.pulse, 0, 10, 0); c.shake = clamp(c.shake, 0, 10, 0); c.flash = clamp(c.flash, 0, 10, 0);
            if (c.srcW < 1 || c.srcH < 1) { c.srcW = Math.max(1, c.srcW); c.srcH = Math.max(1, c.srcH); log.fixes.add(c.name + ": size repaired"); }
            sanitize(c.keys, new float[]{c.scale, c.panX, c.panY, c.rot, c.opacity}, c.name, log);
            for (Layer l : c.grade) sanitize(l, log);
            if (!ids.add(c.id) || c.id < 1) dup.add(c); else maxId = Math.max(maxId, c.id);
        }
        for (int i = p.texts.size() - 1; i >= 0; i--) {
            VProject.TextItem t = p.texts.get(i);
            if (t.layer == null) { p.texts.remove(i); continue; }
            if (t.media != null) {
                VProject.Clip m = t.media;
                if (m.uri == null || m.uri.length() == 0) { p.texts.remove(i); log.fixes.add("overlay without a file dropped"); continue; }
                m.kind = m.kind == VProject.IMAGE ? VProject.IMAGE : VProject.VIDEO;
                if (m.srcW < 1 || m.srcH < 1) { m.srcW = Math.max(1, m.srcW); m.srcH = Math.max(1, m.srcH); log.fixes.add(m.name + ": size repaired"); }
                if (m.srcDur <= 0) m.srcDur = Math.max(1000, m.out);
                m.speed = clamp(m.speed, 0.1f, 10, 1);
                m.volume = clamp(m.volume, 0, 2, 1);
                if (m.kind == VProject.VIDEO) m.in = Math.max(0, Math.min(m.in, m.srcDur - 1000));
                else m.in = 0;
                if (t.layer.srcW < 1 || t.layer.srcH < 1) { t.layer.srcW = m.srcW; t.layer.srcH = m.srcH; }
                if (!ids.add(m.id) || m.id < 1) dup.add(m); else maxId = Math.max(maxId, m.id);
            }
            sanitize(t.layer, log);
            t.start = Math.max(0, t.start);
            // one frame is a valid length (3.4 trims to 1 frame); only a broken value is reset
            if (t.dur < 1000) { t.dur = 3000000; log.fixes.add(t.layer.name + ": length reset"); }
            if (t.media != null) VProject.syncOverlayOut(t);
            t.fadeOffset = Math.max(-86400000000L, Math.min(86400000000L, t.fadeOffset));
            t.fadeSpan = Math.max(-1, Math.min(86400000000L, t.fadeSpan));
            long envelope = Math.max(t.dur, t.fadeSpan);
            t.fadeIn = Math.max(0, Math.min(t.fadeIn, envelope)); t.fadeOut = Math.max(0, Math.min(t.fadeOut, envelope));
            t.reactAudio=Math.max(AudioControl.SELF,t.reactAudio);t.audioMode=clampI(t.audioMode,0,2);t.flash=clamp(t.flash,0,1,0);
            t.reactSrc = clampI(t.reactSrc, 0, 2);
            t.pulse = clamp(t.pulse, 0, 10, 0); t.shake = clamp(t.shake, 0, 10, 0);
            sanitize(t.keys, new float[]{t.layer.cx, t.layer.cy, t.layer.scale, t.layer.rot, t.layer.opacity}, t.layer.name, log);
            if (t.viz != null) {
                VProject.Viz v = t.viz;
                v.type = clampI(v.type, 0, VProject.Viz.TYPES.length - 1);
                v.bars = clampI(v.bars, 4, 256);
                v.gap = clamp(v.gap, 0, 0.95f, 0.3f); v.sens = clamp(v.sens, 0, 10, 1); v.release = clamp(v.release, 0, 5, 0.15f);
                v.glow = clamp(v.glow, 0, 5, 0.5f); v.thick = clamp(v.thick, 0, 1, 0.006f); v.inner = clamp(v.inner, 0, 1, 0.35f);
                v.span = clamp(v.span, 0, 1, 0.05f);
                v.trailLen = clamp(v.trailLen, 0, 3, 0); v.echoes = clampI(v.echoes, 1, 16); v.tZoom = clamp(v.tZoom, -5, 5, 0); v.tSpin = clamp(v.tSpin, -720, 720, 0);
                v.tPush = clamp(v.tPush, -5, 5, 0); v.tHue = clamp(v.tHue, -720, 720, 0);
            }
            if (!ids.add(t.id) || t.id < 1) dup.add(t); else maxId = Math.max(maxId, t.id);
        }
        for (int i = p.audio.size() - 1; i >= 0; i--) {
            VProject.AudioItem a = p.audio.get(i);
            if (a.uri == null || a.uri.length() == 0) { p.audio.remove(i); continue; }
            if (a.srcDur <= 0) a.srcDur = Math.max(1, a.out);
            long in = Math.max(0, Math.min(a.in, a.srcDur - 1000)), out = Math.min(a.out, a.srcDur);
            if (out < in + 1000) { in = 0; out = a.srcDur; log.fixes.add(a.name + ": trim reset"); }
            a.in = in; a.out = out;
            a.start = Math.max(0, a.start);
            a.volume = clamp(a.volume, 0, 2, 1);
            a.fadeOffset = Math.max(-86400000000L, Math.min(86400000000L, a.fadeOffset));
            a.fadeSpan = Math.max(-1, Math.min(86400000000L, a.fadeSpan));
            a.fadeIn = Math.max(0, Math.min(86400000000L,a.fadeIn)); a.fadeOut = Math.max(0, Math.min(86400000000L,a.fadeOut));
            sanitize(a.vol, new float[]{a.volume}, a.name + " volume", log);
            for (Keys.Key k : a.vol.list) k.v[0] = clamp(k.v[0], 0, 2, a.volume);
            if (!ids.add(a.id) || a.id < 1) dup.add(a); else maxId = Math.max(maxId, a.id);
        }
        for (Layer l : p.grade) sanitize(l, log);
        for (Layer l : p.pgrade) sanitize(l, log);
        for (VProject.TextItem t : p.texts) if (t.media != null) for (Layer l : t.media.grade) sanitize(l, log);
        // grade layers draw ids from the same counter
        for (VProject.Clip c : p.clips) for (Layer l : c.grade) maxId = Math.max(maxId, l.id);
        for (Layer l : p.grade) maxId = Math.max(maxId, l.id);
        for (Layer l : p.pgrade) maxId = Math.max(maxId, l.id);
        for (VProject.TextItem t : p.texts) if (t.media != null) for (Layer l : t.media.grade) maxId = Math.max(maxId, l.id);
        for (VProject.TextItem t : p.texts) maxId = Math.max(maxId, t.layer.id);
        for (Object o : dup) {
            int id = ++maxId;
            if (o instanceof VProject.Clip) ((VProject.Clip) o).id = id;   // main clips and overlay media
            else if (o instanceof VProject.TextItem) ((VProject.TextItem) o).id = id;
            else ((VProject.AudioItem) o).id = id;
            log.fixes.add("duplicate item id repaired");
        }
        if (p.nextId <= maxId) { p.nextId = maxId + 1; log.fixes.add("item id counter repaired"); }
        if (p.beats != null) {
            int n = 0;
            float[] b = new float[p.beats.length];
            for (float x : p.beats) if (x >= 0 && !Float.isInfinite(x)) b[n++] = x;
            java.util.Arrays.sort(b, 0, n);
            p.beats = n == b.length ? b : java.util.Arrays.copyOf(b, n);
        }
        p.bpm = clamp(p.bpm, 0, 1000, 0);
        if (p.validateSync()) log.fixes.add("music sync track missing: sync turned off");
        p.duckDb = clamp(p.duckDb, 0, 30, 0);
        // 3.13: markers sorted, on the timeline, legal colour, bounded note; default transition legal
        for (int i = p.markers.size() - 1; i >= 0; i--) {
            VProject.Marker m = p.markers.get(i);
            if (m.t < 0 || m.t > 86400000000L) { p.markers.remove(i); log.fixes.add("marker outside the timeline dropped"); continue; }
            m.color = clampI(m.color, 0, VProject.MARKER_COLORS.length - 1);
            if (m.note == null) m.note = "";
            if (m.note.length() > 200) m.note = m.note.substring(0, 200);
        }
        p.sortMarkers();
        // 3.14: look groups in grade stacks keep a valid structure (one level, children before their group)
        for (java.util.ArrayList<Layer> g : p.gradeLists()) if (GradeGroups.normalize(g)) log.fixes.add("grade group structure repaired");
        p.defTrans = clampI(p.defTrans, 0, VProject.TRANS.length - 1);
        p.defTransDur = Math.max(Timeline.frame(p.fps()), Math.min(p.defTransDur, 3000000L));
    }

    /**
     * Keys sorted by time, eases legal, every value vector the same width (eval reads b.v[j]).
     * Missing or invalid values take the item's own static value from def.
     */
    static void sanitize(Keys k, float[] def, String what, Loaded log) {
        int width = def.length;
        boolean fixed = false;
        for (int i = k.list.size() - 1; i >= 0; i--) {
            Keys.Key x = k.list.get(i);
            // keys before the item's start are valid: a split or head trim leaves the key that
            // starts the segment running into the item there, and evaluation needs it
            if (x.v == null || x.t == Long.MIN_VALUE || x.t == Long.MAX_VALUE) { k.list.remove(i); fixed = true; continue; }
            if (x.v.length != width) {
                float[] v = new float[width];
                for (int j = 0; j < width; j++) v[j] = j < x.v.length ? x.v[j] : def[j];
                x.v = v;
                fixed = true;
            }
            for (int j = 0; j < width; j++) if (Float.isNaN(x.v[j]) || Float.isInfinite(x.v[j])) { x.v[j] = def[j]; fixed = true; }
            if (x.ease < Keys.LINEAR || x.ease > Keys.BEZIER) { x.ease = Keys.SMOOTH; fixed = true; }
            if (x.ease == Keys.BEZIER) {
                boolean ok = x.bez != null && x.bez.length == 4;
                if (ok) for (float f : x.bez) if (Float.isNaN(f) || Float.isInfinite(f) || Math.abs(f) > 10) ok = false;
                if (!ok) { x.ease = Keys.SMOOTH; x.bez = null; fixed = true; }
                else { x.bez[0] = Math.max(0, Math.min(1, x.bez[0])); x.bez[2] = Math.max(0, Math.min(1, x.bez[2])); }
            }
            if (x.mask != Keys.ALL && (x.mask & ((1 << width) - 1)) == 0) { k.list.remove(i); fixed = true; continue; }
        }
        for (int i = 1; i < k.list.size(); i++) if (k.list.get(i).t < k.list.get(i - 1).t) { fixed = true; break; }
        Collections.sort(k.list, new Comparator<Keys.Key>() {
            @Override public int compare(Keys.Key a, Keys.Key b) { return a.t < b.t ? -1 : (a.t > b.t ? 1 : 0); }
        });
        if (fixed) log.fixes.add(what + ": keyframes repaired");
    }
}
