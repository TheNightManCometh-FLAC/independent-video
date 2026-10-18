package com.christopher.strata;

/** 3.13: footage waveforms map timeline pixels through the clip's own time map. */
public class WaveformTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) {
        // 10 s of peaks: loud only between 4.00 and 4.10 s of the file
        float[] pk = new float[1000];
        for (int k = 400; k < 410; k++) pk[k] = 0.8f;

        check("Span finds the loud part", Waveform.span(pk, 3990000, 4050000) == 0.8f);
        check("Span is order-agnostic", Waveform.span(pk, 4050000, 3990000) == 0.8f);
        check("Span outside the file is silent", Waveform.span(pk, 20000000, 30000000) == 0f);
        check("No peaks yet: no columns drawn", Waveform.columns(null, null, 0, 1, 10, 1)[9] == 0f);

        // 1x clip, In at 3 s: the hit is 1 s into the clip
        final VProject.Clip a = new VProject.Clip();
        a.in = 3000000; a.out = 8000000; a.speed = 1; a.srcDur = 10000000;
        float[] c1 = Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return a.srcAt(t); } }, 0, 5000000, 50, 1);
        check("1x: the hit lands in column 10 of 50 (1.0-1.1 s)", c1[10] > 0.7f && c1[9] == 0f && c1[11] == 0f);

        // 2x clip from the same In: the hit is 0.5 s into the clip
        final VProject.Clip b = new VProject.Clip();
        b.in = 3000000; b.out = 8000000; b.speed = 2; b.srcDur = 10000000;
        float[] c2 = Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return b.srcAt(t); } }, 0, 2500000, 50, 1);
        check("2x: the hit moves to 0.5 s (column 10 of 50 over 2.5 s)", c2[10] > 0.7f && c2[12] == 0f);

        check("Volume scales the drawn height", Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return a.srcAt(t); } }, 0, 5000000, 50, 0.5f)[10] == 0.4f);
        check("Above 100 % fills the band but no more", Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return a.srcAt(t); } }, 0, 5000000, 50, 2f)[10] == 1f);

        // B-roll at 2x placed at 20 s on the timeline
        final VProject.TextItem ti = new VProject.TextItem();
        ti.media = b; ti.start = 20000000; ti.dur = 2500000;
        float[] c3 = Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return VProject.overlaySrc(ti, ti.start + t); } }, 0, 2500000, 50, 1);
        check("B-roll uses its own speed map", c3[10] > 0.7f && c3[12] == 0f);

        // a ramp: speeds change across the clip but every column maps forward through the file
        final VProject.Clip r = new VProject.Clip();
        r.in = 0; r.out = 10000000; r.speed = 1; r.srcDur = 10000000;
        r.setCurve(SpeedCurve.preset(2));
        long d = r.dur();
        long prev = -1; boolean forward = true;
        for (int i = 0; i <= 40; i++) { long s = r.srcAt(d * i / 40); if (s < prev) forward = false; prev = s; }
        float[] c4 = Waveform.columns(pk, new Waveform.SrcMap() { public long src(long t) { return r.srcAt(t); } }, 0, d, 200, 1);
        int hits = 0; for (float v : c4) if (v > 0) hits++;
        check("Ramped clip maps forward and still shows the hit", forward && hits > 0);
        System.out.println(count + " passed, 0 failed");
    }
}
