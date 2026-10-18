package com.christopher.strata;

/**
 * 3.15 equalizer: the RBJ biquads' response at the textbook points, real filtering of sine waves
 * matching the response, steep cuts, state carried across blocks, preview band mapping, presets,
 * JSON, varispeed (tape) length and pitch.
 */
public class AudioEqTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
    static boolean near(double a, double b, double e) { return Math.abs(a - b) <= e; }

    static AudioEq one(int type, float f, float g, float q) {
        AudioEq e = new AudioEq();
        e.bands = new AudioEq.Band[]{new AudioEq.Band(type, f, g, q)};
        return e;
    }

    /** dB gain the chain applies to a steady sine at f (measured after it settles), in blocks of 512. */
    static double measured(AudioEq e, double f) {
        AudioEq.Chain c = new AudioEq.Chain(e);
        int n = 48000;
        float[] s = new float[n * 2];
        for (int i = 0; i < n; i++) s[2 * i] = s[2 * i + 1] = (float) Math.sin(2 * Math.PI * f * i / 48000.0);
        for (int off = 0; off < n; off += 512) c.process(s, off * 2, Math.min(512, n - off));
        double in = 0, out = 0;
        for (int i = n / 2; i < n; i++) { double x = Math.sin(2 * Math.PI * f * i / 48000.0); in += x * x; out += s[2 * i] * s[2 * i]; }
        return 10 * Math.log10(out / in);
    }

    public static void main(String[] args) throws Exception {
        AudioEq bell = one(AudioEq.BELL, 1000, 6, 1);
        check("bell: +6 dB at its centre", near(bell.response(1000), 6, 0.01));
        check("bell: ~0 dB two octaves away", Math.abs(bell.response(250)) < 0.6 && Math.abs(bell.response(4000)) < 0.6);
        check("bell: the filter really boosts a 1 kHz sine by 6 dB (" + measured(bell, 1000) + ")", near(measured(bell, 1000), 6, 0.05));
        AudioEq ls = one(AudioEq.LOW_SHELF, 200, -9, 0.7f);
        check("low shelf: -9 dB deep in the lows, ~0 in the highs", near(ls.response(25), -9, 0.3) && Math.abs(ls.response(8000)) < 0.1);
        check("low shelf: half the gain at its corner (" + ls.response(200) + ")", near(ls.response(200), -4.5, 0.3));
        AudioEq hs = one(AudioEq.HIGH_SHELF, 5000, 5, 1);
        check("high shelf: +5 dB at 18 kHz, flat at 100 Hz", near(hs.response(18000), 5, 0.4) && Math.abs(hs.response(100)) < 0.05);
        AudioEq lc = one(AudioEq.LOW_CUT, 100, 0, 0.707f);
        check("low cut: -3 dB at its frequency (" + lc.response(100) + ")", near(lc.response(100), -3.01, 0.05));
        check("low cut: 12 dB per octave below (" + (lc.response(25) - lc.response(50)) + ")", near(lc.response(25) - lc.response(50), -12, 0.6));
        lc.bands[0].steep = true;
        check("steep low cut: still -3 dB at its frequency (" + lc.response(100) + ")", near(lc.response(100), -3.01, 0.05));
        check("steep low cut: 24 dB per octave below", near(lc.response(25) - lc.response(50), -24, 0.8));
        check("steep low cut measured on a 50 Hz sine matches the curve", near(measured(lc, 50), lc.response(50), 0.1));
        AudioEq hc = one(AudioEq.HIGH_CUT, 3000, 0, 0.707f);
        check("high cut: -3 dB at 3 kHz, flat at 300 Hz", near(hc.response(3000), -3.01, 0.05) && Math.abs(hc.response(300)) < 0.05);
        AudioEq nt = one(AudioEq.NOTCH, 60, 0, 8);
        check("notch: removes 60 Hz hum (" + nt.response(60) + " dB) and leaves 120 Hz (" + nt.response(120) + ")", nt.response(60) < -40 && Math.abs(nt.response(120)) < 0.3);

        AudioEq flat = new AudioEq();
        check("default EQ is flat (does nothing) and has the six classic bands", flat.flat() && flat.bands.length == 6 && new AudioEq.Chain(flat).empty());
        AudioEq off = AudioEq.preset(1); off.on = false;
        check("an EQ switched off is flat", off.flat() && off.response(80) == 0);

        // blocks: one long run = many small runs
        AudioEq mix = AudioEq.preset(5);
        AudioEq.Chain a = new AudioEq.Chain(mix), b = new AudioEq.Chain(mix);
        float[] s1 = new float[2000], s2 = new float[2000];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < s1.length; i++) s1[i] = s2[i] = r.nextFloat() * 2 - 1;
        a.process(s1, 0, 1000);
        for (int off2 = 0; off2 < 1000; off2 += 37) b.process(s2, off2 * 2, Math.min(37, 1000 - off2));
        double d = 0;
        for (int i = 0; i < s1.length; i++) d = Math.max(d, Math.abs(s1[i] - s2[i]));
        check("filtering in blocks equals filtering in one go (max diff " + d + ")", d < 1e-5);

        // preview mapping
        float[] g = AudioEq.previewGains(bell, null);
        int peak = 0;
        for (int i = 1; i < g.length; i++) if (g[i] > g[peak]) peak = i;
        double centre = 20 * Math.pow(1000, (peak + 0.5) / AudioEq.PREVIEW_BANDS);
        check("preview: the bell lands in the band around 1 kHz (" + Math.round(centre) + " Hz, " + g[peak] + " dB)", centre > 700 && centre < 1400 && g[peak] > 4.5);
        float[] gm = AudioEq.previewGains(bell, bell);
        check("preview: item and master EQ add", near(gm[peak], 2 * g[peak], 0.01));
        check("preview band edges climb from 20 Hz to 20 kHz", near(AudioEq.previewCutoff(AudioEq.PREVIEW_BANDS - 1), 20000, 1) && AudioEq.previewCutoff(0) > 20);

        // presets and JSON
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < AudioEq.PRESETS.length; i++) {
            AudioEq p = AudioEq.preset(i);
            StringBuilder k = new StringBuilder();
            for (int f = 30; f < 20000; f *= 2) k.append(Math.round(p.response(f) * 2)).append(',');
            seen.add(k.toString());
            check("preset " + AudioEq.PRESETS[i] + " is recognised", p.presetIndex() == i);
        }
        check("every preset sounds different", seen.size() == AudioEq.PRESETS.length);
        AudioEq t = AudioEq.preset(8);
        t.bands[2].q = 3.3f; t.bands[3].on = false;
        AudioEq back = AudioEq.fromJson(t.toJson());
        check("JSON round trip", back.same(t) && back.presetIndex() == -1);
        org.json.JSONObject bad = t.toJson();
        bad.getJSONArray("b").getJSONObject(1).put("f", "x").put("g", 99).put("t", 42);
        AudioEq rep = AudioEq.fromJson(bad);
        check("damaged bands are repaired", rep.bands[1].freq == 1000 && rep.bands[1].gain == AudioEq.G_MAX && rep.bands[1].type == AudioEq.NOTCH);

        // tape speed
        short[] tone = new short[48000 * 2];
        for (int i = 0; i < 48000; i++) tone[2 * i] = tone[2 * i + 1] = (short) (10000 * Math.sin(2 * Math.PI * 440 * i / 48000.0));
        short[] fast = AudioDsp.varispeed(tone, 2);
        check("tape at 2x: half as long", near(fast.length / 2, 24000, 2));
        int zc = 0;
        for (int i = 1; i < fast.length / 2; i++) if ((fast[2 * i - 2] < 0) != (fast[2 * i] < 0)) zc++;
        double hz = zc / 2.0 / (fast.length / 2 / 48000.0);
        check("tape at 2x: an octave up (" + Math.round(hz) + " Hz)", near(hz, 880, 6));

        short[] slow = AudioDsp.stretch(tone, 0.1f), quick = AudioDsp.stretch(tone, 10f);
        check("time-stretch reaches the new limits: 0.1x is 10x as long, 10x a tenth (" + slow.length / 2 + ", " + quick.length / 2 + ")",
                Math.abs(slow.length / 2 - 480000) < 2000 && Math.abs(quick.length / 2 - 4800) < 600);
        int zs = 0;
        for (int i = 48000; i < 96000; i++) if ((slow[2 * i - 2] < 0) != (slow[2 * i] < 0)) zs++;
        check("time-stretch at 0.1x keeps the pitch (" + zs / 2 + " Hz)", Math.abs(zs / 2 - 440) < 15);

        // in a project: format and save / load
        VProject p = new VProject();
        VProject.Clip c = new VProject.Clip();
        c.id = 1; c.uri = "u"; c.srcW = 1920; c.srcH = 1080; c.out = c.srcDur = 10000000; c.hasAudio = true;
        p.clips.add(c);
        int before = p.formatVersion();
        c.eq = AudioEq.preset(5); c.tape = true; c.speed = 0.5f;
        p.masterEq = AudioEq.preset(10);
        check("an EQ bumps the format to 19 (older apps refuse it instead of dropping the EQ)", before < 19 && p.formatVersion() == 19 && ProjectIO.VIDEO_VERSION >= 19);
        VProject q = VProject.fromJson(p.toJson());
        check("clip EQ, tape and master EQ survive save / load", q.clips.get(0).eq != null && q.clips.get(0).eq.same(c.eq) && q.clips.get(0).tape
                && q.masterEq != null && q.masterEq.same(p.masterEq));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
