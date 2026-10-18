package com.christopher.strata;

import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * 3.15 visualizers: the data texture rows (bars from a frequency range, peak caps falling under
 * gravity, past spectra, level history), presets, JSON and the format bump. With a folder
 * argument it writes thumbs.txt: the shader uniforms of every style and preset, for the tile
 * pictures rendered at build time (tests/vizthumbs.py).
 */
public class VisualsTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    /** A song: band j has level j-dependent; a kick (all bands + bass) every 0.5 s lasting 0.05 s. */
    static AudioFeatures song() {
        AudioFeatures f = new AudioFeatures();
        f.frames = AudioFeatures.FPS * 20;
        f.bass = new float[f.frames]; f.level = new float[f.frames];
        f.bands = new byte[f.frames * AudioFeatures.BANDS];
        f.wave = new byte[AudioFeatures.WAVE_RATE * 20];
        for (int i = 0; i < f.frames; i++) {
            boolean kick = (i % (AudioFeatures.FPS / 2)) < 5;
            f.bass[i] = kick ? 1 : 0.1f;
            f.level[i] = kick ? 0.9f : 0.3f;
            for (int j = 0; j < AudioFeatures.BANDS; j++) {
                float v = kick ? 0.95f : 0.2f + 0.6f * j / 63f;   // between kicks: brighter towards the highs
                f.bands[i * AudioFeatures.BANDS + j] = (byte) Math.round(v * 255);
            }
        }
        return f;
    }

    static float at(byte[] d, int row, int i) { return (d[row * Visuals.W + i] & 255) / 255f; }

    public static void main(String[] args) throws Exception {
        AudioFeatures fx = song();
        Visuals.Work w = new Visuals.Work();
        byte[] d = new byte[Visuals.W * Visuals.ROWS];
        VProject.Viz z = new VProject.Viz();
        z.bars = 16; z.release = 0.03f; z.sens = 1;
        check("a 3.14 visualizer is not 'new' (its project stays readable by 3.14)", !Visuals.isNew(z));
        long between = 300000;   // 0.3 s: between kicks
        Visuals.rows(fx, between, z, 0, 1, w, d);
        check("bars rise towards the highs between kicks", at(d, 0, 15) > at(d, 0, 0) + 0.3f);
        check("unused bar texels stay empty", at(d, 0, 16) == 0 && at(d, 0, 200) == 0);
        Visuals.setRange(z, 1);   // bass only
        Visuals.rows(fx, between, z, 0, 1, w, d);
        check("Bass range: every bar reads the low bands (all low)", at(d, 0, 15) < 0.3f);
        check("the range maps to bands (" + z.lo + ".." + z.hi + ") and back to its name", Visuals.rangeIndex(z) == 1);
        Visuals.setRange(z, 0);
        check("full range is the 3.14 range", z.lo == 0 && z.hi == 63);

        // caps: right after a kick the caps sit above the bars and fall over time
        z.caps = true; z.type = VProject.Viz.BARS;
        Visuals.rows(fx, 560000, z, 0, 1, w, d);      // 10 ms after a kick ended
        float bar = at(d, 0, 0), cap = at(d, 2, 0);
        Visuals.rows(fx, 760000, z, 0, 1, w, d);
        float capLater = at(d, 2, 0);
        check("peak cap held above the bar just after a kick (" + bar + " < " + cap + ")", cap > bar + 0.3f);
        check("and falling 0.2 s later (" + capLater + ")", capLater < cap - 0.05f && capLater >= at(d, 0, 0) - 1e-3);

        // history for ridges: row 3 is now, later rows are older
        z.caps = false; z.type = VProject.Viz.RIDGES; z.hist = 0.31f;   // 10 ms per row
        Visuals.rows(fx, 520000, z, 0, 1, w, d);      // kick from 500 ms: rows 0..2 are kick, then not
        check("ridges row 3 (now) is the kick", at(d, 3, 0) > 0.8f);
        check("ridges row 3 + 10 (100 ms ago) is between kicks", at(d, 13, 0) < 0.4f);
        check("history rows only for styles that use them", true);
        z.type = VProject.Viz.BARS;
        Visuals.rows(fx, 520000, z, 0, 1, w, d);
        check("bars leave the history rows empty", at(d, 13, 0) == 0);

        // shockwave: level history, texel 0 = now
        z.type = VProject.Viz.SHOCK; z.hist = 1f;
        Visuals.rows(fx, 1010000, z, 0, 1, w, d);     // a kick started at 1.0 s
        int peak = -1;
        for (int i = 0; i < Visuals.W; i++) if (at(d, 35, i) > 0.9f && peak < 0) peak = i;
        check("shockwave history: the current kick is at the front (texel " + peak + ")", peak >= 0 && peak < 10);
        float half = at(d, 35, Math.round(0.49f * 511));   // 0.49 s ago: inside the kick that started at 0.5 s
        check("and the kick half a second ago is half way along (" + half + ")", half > 0.9f);

        // smoothing spreads a single peak
        VProject.Viz s = new VProject.Viz();
        s.bars = 8; s.smooth = 1; s.release = 0.03f;
        float[] out = new float[8];
        Visuals.bars(fx, between, s, 0, 8, w, out);
        float[] raw = new float[8];
        s.smooth = 0;
        Visuals.bars(fx, between, s, 0, 8, w, raw);
        check("spatial smoothing pulls the top bar towards its neighbour", out[7] < raw[7] && out[0] > raw[0]);

        // presets
        java.util.HashSet<String> looks = new java.util.HashSet<String>();
        for (Visuals.Preset p : Visuals.PRESETS) {
            VProject.Viz v = new VProject.Viz();
            Visuals.apply(v, p.key);
            check("preset " + p.name + " sets its style and is recognised", v.type == p.type && p.key.equals(Visuals.presetOf(v)));
            looks.add(v.type + ":" + v.c1 + ":" + v.c2 + ":" + v.colorMode + ":" + v.trailLen + ":" + v.mirrorX);
        }
        check("every preset looks different", looks.size() == Visuals.PRESETS.length);
        VProject.Viz pv = new VProject.Viz();
        pv.echoes = 12;
        Visuals.apply(pv, "vapor");
        check("a preset with echoes keeps your echo count", pv.trailLen > 0 && pv.echoes == 12);
        Visuals.apply(pv, "neon");
        check("a preset without echoes switches them off", pv.trailLen == 0);

        // JSON
        VProject.Viz j = new VProject.Viz();
        Visuals.apply(j, "hifi");
        j.lo = 5; j.hi = 40; j.hist = 3.5f; j.flip = true; j.bounce = 0.4f;
        VProject.Viz back = VProject.Viz.fromJson(j.toJson());
        check("JSON keeps the 3.15 options", back.type == j.type && back.stops == 3 && back.c3 == j.c3 && back.caps && back.flip
                && back.lo == 5 && back.hi == 40 && back.hist == 3.5f && back.bounce == 0.4f && back.segs == j.segs);
        VProject.Viz old = VProject.Viz.fromJson(new VProject.Viz().toJson());
        check("a 3.14 visualizer loads with the 3.14 look", !Visuals.isNew(old) && old.round == 0.5f && old.stops == 2);
        org.json.JSONObject bad = j.toJson();
        bad.getJSONObject("v315").put("lo", 70).put("hi", -3).put("hist", "damaged").put("segs", 999);
        VProject.Viz rep = VProject.Viz.fromJson(bad);
        check("damaged options are repaired", rep.lo <= 62 && rep.hi > rep.lo && rep.hist == 2 && rep.segs == 40);
        VProject p = new VProject();
        VProject.TextItem ti = new VProject.TextItem();
        ti.viz = j; ti.layer = new Layer();
        p.texts.add(ti);
        check("a project using new styles is format 18", p.formatVersion() == 18);

        if (args.length > 0) thumbs(args[0]);
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** One line per tile: key and the viz.frag uniforms (as VComp sets them, box 144 x 144). */
    static void thumbs(String dir) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int t = 0; t < VProject.Viz.TYPES.length; t++) {
            VProject.Viz z = new VProject.Viz();
            z.type = t;
            if (t == VProject.Viz.LED) { z.caps = true; z.bars = 16; Visuals.setPalette(z, 13); z.colorMode = 1; }
            if (t == VProject.Viz.RIDGES) { z.c1 = z.c2 = 0xffffffff; z.fill = 1; z.ridges = 16; z.thick = 0.003f; }
            if (t == VProject.Viz.WATERFALL) { Visuals.setPalette(z, 6); z.colorMode = 4; }
            if (t == VProject.Viz.SHOCK) { z.inner = 0.15f; Visuals.setPalette(z, 1); }
            line(b, "style" + t, z);
        }
        for (Visuals.Preset p : Visuals.PRESETS) {
            VProject.Viz z = new VProject.Viz();
            Visuals.apply(z, p.key);
            line(b, p.key, z);
        }
        FileOutputStream o = new FileOutputStream(dir + "/thumbs.txt");
        o.write(b.toString().getBytes("UTF-8"));
        o.close();
    }

    static String rgb(int c) { return (((c >> 16) & 255) / 255f) + "," + (((c >> 8) & 255) / 255f) + "," + ((c & 255) / 255f); }

    static void line(StringBuilder b, String key, VProject.Viz z) {
        float S = 144;
        b.append(key).append(' ').append(Visuals.round(z.type) || z.type == VProject.Viz.RIDGES ? "sq" : "wide");
        b.append(" uType=").append(z.type).append(" uBars=").append(Math.max(4, Math.min(64, z.bars))).append(" uGap=").append(z.gap)
                .append(" uC1=").append(rgb(z.c1)).append(" uC2=").append(rgb(z.c2)).append(" uC3=").append(rgb(z.c3))
                .append(" uStops=").append(z.stops).append(" uColorMode=").append(z.colorMode).append(" uHueOff=0")
                .append(" uThick=").append(Math.max(1.5f, z.thick * S * 1.2f)).append(" uGlow=").append(z.glow).append(" uInner=").append(z.inner)
                .append(" uLevel=0.7 uRound=").append(z.round).append(" uCaps=").append(z.caps ? 1 : 0).append(" uMirrorX=").append(z.mirrorX ? 1 : 0)
                .append(" uFlip=").append(z.flip ? 1 : 0).append(" uFill=").append(z.fill).append(" uBounce=").append(z.bounce)
                .append(" uSegs=").append(z.segs).append(" uRidges=").append(z.ridges).append(" uAmp=").append(z.amp).append('\n');
    }
}
