package com.christopher.strata;

/** 3.14: saved presets (speed curves): names and the stored curve. */
public class PresetTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) throws Exception {
        check("Names are trimmed", Presets.cleanName("  Hero slow  ").equals("Hero slow"));
        check("Names are one line", Presets.cleanName("a\nb").equals("a b"));
        check("Empty names get a default", Presets.cleanName("   ").equals("Preset") && Presets.cleanName(null).equals("Preset"));
        check("Long names are capped at 40", Presets.cleanName("x".repeat(80)).length() == 40);
        // a saved curve is the curve's own JSON: it must come back identical (hash equal) and apply to any clip
        SpeedCurve sc = SpeedCurve.preset(2);
        SpeedCurve back = SpeedCurve.fromJson(sc.toJson());
        check("A saved curve reloads identically", back != null && back.hash() == sc.hash());
        VProject.Clip a = new VProject.Clip(); a.in = 0; a.out = 2000000; a.srcDur = 10000000; a.speed = 1;
        VProject.Clip b = new VProject.Clip(); b.in = 3000000; b.out = 9000000; b.srcDur = 10000000; b.speed = 1;
        a.setCurve(back.copy()); b.setCurve(back.copy());
        check("The same curve fits clips of any length (relative to the footage)", Math.abs(b.dur() / (double) a.dur() - 3.0) < 0.01);
        System.out.println(count + " passed, 0 failed");
    }
}
