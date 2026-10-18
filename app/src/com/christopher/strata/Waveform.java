package com.christopher.strata;

/**
 * 3.13: waveform columns for footage on the timeline (LumaFusion draws clip waveforms that follow
 * gain). Pure: peaks are the 100-per-second maxima from Media.analyze; a column covers the source
 * span its timeline pixels show, through the clip's own time map, so speed, ramps and trims line up.
 */
final class Waveform {
    static final long PEAK_US = 10000;   // one peak per 10 ms

    interface SrcMap { long src(long localUs); }

    /** Largest peak in source [a, b) (either order); 0 outside the file. */
    static float span(float[] pk, long a, long b) {
        if (pk == null || pk.length == 0) return 0;
        if (b < a) { long t = a; a = b; b = t; }
        // half-open [a, b): neighbouring columns don't share a boundary peak (a column narrower than
        // one peak still shows the peak it sits in)
        int k0 = (int) Math.max(0, a / PEAK_US), k1 = (int) Math.min(pk.length - 1, Math.max(a, b - 1) / PEAK_US);
        float m = 0;
        for (int k = k0; k <= k1; k++) m = Math.max(m, pk[k]);
        return m;
    }

    /**
     * Column heights (0..1) for n columns across localFrom..localTo of an item's own time. gain is the
     * item's volume; shown up to 1 (louder than full just fills the band).
     */
    static float[] columns(float[] pk, SrcMap map, long localFrom, long localTo, int n, float gain) {
        float[] out = new float[Math.max(0, n)];
        if (pk == null || n <= 0 || localTo <= localFrom) return out;
        double step = (localTo - localFrom) / (double) n;
        long prev = map.src(localFrom);
        for (int i = 0; i < n; i++) {
            long next = map.src(localFrom + Math.round(step * (i + 1)));
            out[i] = Math.min(1f, span(pk, prev, next) * Math.max(0f, gain));
            prev = next;
        }
        return out;
    }
}
