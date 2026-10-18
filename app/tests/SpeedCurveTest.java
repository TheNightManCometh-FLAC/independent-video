package com.christopher.strata;

/**
 * 3.15 speed curves: per-stretch shapes (Smooth, Linear, Ease, Hold), exact splits of curves that
 * mix them, point edits keeping shapes in step, JSON (new and 3.14 forms) and the format bump.
 */
public class SpeedCurveTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
    static boolean near(double a, double b, double e) { return Math.abs(a - b) <= e; }

    public static void main(String[] args) throws Exception {
        SpeedCurve lin = new SpeedCurve(new float[]{0, 1}, new float[]{1, 4}, new int[]{SpeedCurve.LINEAR});
        check("Linear: halfway between 1x and 4x is 2x (straight in log speed)", near(lin.speedAt(0.5), 2, 1e-3));
        check("Linear: a quarter of the way is 2^0.5", near(lin.speedAt(0.25), Math.sqrt(2), 1e-3));
        SpeedCurve ease = new SpeedCurve(new float[]{0, 1}, new float[]{1, 4}, new int[]{SpeedCurve.EASE});
        check("Ease: flat at the start (1.0x at 2%)", ease.speedAt(0.02) < 1.01);
        check("Ease: flat at the end (4x at 98%)", ease.speedAt(0.98) > 3.95);
        check("Ease: still 2x in the middle", near(ease.speedAt(0.5), 2, 1e-3));
        SpeedCurve hold = new SpeedCurve(new float[]{0, 0.5f, 1}, new float[]{0.5f, 3, 3}, new int[]{SpeedCurve.HOLD, SpeedCurve.SMOOTH});
        check("Hold: keeps 0.5x right up to the next point", near(hold.speedAt(0.49), 0.5, 1e-6));
        check("Hold: jumps to the next point's speed", near(hold.speedAt(0.51), 3, 1e-3));
        // playback length of a hold: half the footage at 0.5x (1.0) + half at 3x (1/6)
        check("Hold: playback length is exact (" + hold.total() + ")", near(hold.total(), 1 + 1 / 6.0, 3e-3));

        // splits are exact for every shape
        SpeedCurve mix = new SpeedCurve(new float[]{0, 0.2f, 0.45f, 0.7f, 1}, new float[]{1, 4, 0.3f, 0.3f, 6},
                new int[]{SpeedCurve.EASE, SpeedCurve.LINEAR, SpeedCurve.HOLD, SpeedCurve.SMOOTH});
        double[][] cuts = {{0, 0.33}, {0.33, 1}, {0.1, 0.6}, {0.5, 0.65}, {0.6, 0.9}};
        for (double[] cut : cuts) {
            double a = cut[0], b = cut[1], w = b - a;
            SpeedCurve s = mix.sub(a, b);
            double worst = 0;
            for (int i = 1; i < 200; i++) {
                double u = i / 200.0;
                double o = mix.speedAt(a + u * w), q = s.speedAt(u);
                if (Math.abs((a + u * w) - 0.45) < 0.004 || Math.abs((a + u * w) - 0.7) < 0.004) continue;   // the jump itself
                worst = Math.max(worst, Math.abs(o - q) / o);
            }
            double lenO = mix.integ(b) - mix.integ(a), lenS = s.total() * w;
            check(String.format("split %.2f-%.2f: same speed everywhere (worst %.4f%%) and same length (%.4f vs %.4f)", a, b, worst * 100, lenO, lenS),
                    worst < 2e-3 && near(lenO, lenS, 4e-3 * lenO));
        }
        SpeedCurve s1 = mix.sub(0.1, 0.6), s2 = s1.sub(0.3, 0.8);
        double worst = 0;
        for (int i = 1; i < 100; i++) {
            double u = i / 100.0, ou = 0.1 + (0.3 + u * 0.5) * 0.5;
            if (Math.abs(ou - 0.45) < 0.004) continue;
            worst = Math.max(worst, Math.abs(mix.speedAt(ou) - s2.speedAt(u)) / mix.speedAt(ou));
        }
        check("a split of a split is still exact (" + worst + ")", worst < 2e-3);

        // point edits keep shapes in step
        SpeedCurve e = mix.copy();
        int at = e.addPoint(0.3);   // inside the Linear stretch
        check("adding a point splits its stretch; both halves keep the shape", e.size() == 6 && e.seg[at - 1] == SpeedCurve.LINEAR && e.seg[at] == SpeedCurve.LINEAR && e.seg[at + 1] == SpeedCurve.HOLD);
        int h = e.addPoint(0.6);   // inside the Hold
        check("a point added inside a hold takes the hold's speed", near(e.v[h], 0.3, 1e-4) && e.seg[h - 1] == SpeedCurve.HOLD && e.seg[h] == SpeedCurve.HOLD);
        e.removePoint(at);
        check("removing a point merges its stretches", e.size() == 6 && e.seg[at - 1] == SpeedCurve.LINEAR);
        e.setShape(0, SpeedCurve.HOLD);
        check("setShape changes one stretch", e.seg[0] == SpeedCurve.HOLD && near(e.speedAt(0.1), 1, 1e-6));

        // JSON
        SpeedCurve back = SpeedCurve.fromJson(mix.toJson());
        check("JSON keeps shapes", back != null && java.util.Arrays.equals(back.seg, mix.seg) && back.st == null && back.hash() == mix.hash());
        SpeedCurve sb = SpeedCurve.fromJson(s1.toJson());
        double w2 = 0;
        for (int i = 0; i <= 50; i++) w2 = Math.max(w2, Math.abs(sb.speedAt(i / 50.0) - s1.speedAt(i / 50.0)));
        check("JSON keeps a split's exact slopes", sb.st != null && w2 < 1e-4);
        org.json.JSONArray old = new org.json.JSONArray();
        float[][] pts = {{0, 1, 0.5f}, {0.5f, 2, 0}, {1, 1, -0.5f}};
        for (float[] p : pts) { org.json.JSONArray q = new org.json.JSONArray(); for (float f : p) q.put((double) f); old.put(q); }
        SpeedCurve o = SpeedCurve.fromJson(old);
        check("a 3.14 curve with point slopes loads as Smooth with those slopes", o != null && o.allSmooth() && o.st != null && o.st[0] == 0.5f && o.st[3] == -0.5f);
        check("plain curves keep the 3.14 file form", SpeedCurve.preset(1).toJson().getJSONArray(0).length() == 2);
        check("presets are recognised; a shaped curve is custom", SpeedCurve.preset(3).presetIndex() == 3 && mix.presetIndex() == -1);

        VProject p = new VProject();
        VProject.Clip c = new VProject.Clip();
        c.id = 1; c.uri = "u"; c.srcW = 1920; c.srcH = 1080; c.out = c.srcDur = 10000000;
        p.clips.add(c);
        int before = p.formatVersion();
        c.setCurve(SpeedCurve.preset(0));
        check("a plain curve doesn't change the format", p.formatVersion() == before);
        c.setCurve(mix.copy());
        check("a shaped curve is format 19", p.formatVersion() == 19);
        c.curve = null; c.speed = 6;
        check("constant speeds beyond 4x are format 19", p.formatVersion() == 19);
        c.speed = 2; c.tape = true;
        check("tape pitch is format 19", p.formatVersion() == 19);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
