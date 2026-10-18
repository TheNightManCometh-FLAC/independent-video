package com.christopher.strata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * What visualizers and music-reactive effects read: per 10 ms frame, 64 log-spaced band levels
 * (30 Hz - 12 kHz), a bass level and an overall level, all normalised 0..1 per song; plus an
 * 8-bit mono copy of the waveform at 12 kHz for oscilloscope views. Computed once per song
 * (STFT at 24 kHz, 2048-point Hann, hop 240) and cached on disk. Pure Java.
 *
 * Levels are released at read time by a look-back peak hold with exponential decay, so any
 * frame can be evaluated on its own (scrubbing and export get identical values).
 */
final class AudioFeatures {
    static final int SR = 24000, NFFT = 2048, HOP = 240, FPS = SR / HOP, BANDS = 64, WAVE_RATE = 12000;
    static final float F_LO = 30, F_HI = 12000;
    private static final int MAGIC = 0x454d4658, VERSION = 2;   // "EMFX"

    int frames;
    byte[] bands;      // frames * BANDS, 0..255
    float[] bass, level;
    byte[] wave;       // signed, peak-normalised

    // ---------------------------------------------------------------- analysis
    /** mono: the song at SR (24 kHz), roughly -1..1. */
    static AudioFeatures compute(float[] mono) { return compute(mono, mono.length); }

    /** Only the first n samples of mono are used (lets the decoder's growable buffer go in as is). */
    static AudioFeatures compute(float[] mono, int n) {
        AudioFeatures f = new AudioFeatures();
        f.frames = Math.max(1, n / HOP + 1);
        float[] db = new float[f.frames * BANDS];
        float[] bassDb = new float[f.frames], levDb = new float[f.frames];
        float[] win = new float[NFFT];
        for (int i = 0; i < NFFT; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT));
        // band edges in bins (fractional)
        double df = SR / (double) NFFT;
        double[] edge = new double[BANDS + 1];
        for (int j = 0; j <= BANDS; j++) edge[j] = F_LO * Math.pow(F_HI / F_LO, j / (double) BANDS) / df;
        float[] tilt = new float[BANDS];
        for (int j = 0; j < BANDS; j++) {
            double fc = Math.sqrt(edge[j] * edge[j + 1]) * df;
            tilt[j] = (float) (3.0 * Math.log(fc / 1000.0) / Math.log(2));   // +3 dB/oct: pink noise reads flat
        }
        float[] re = new float[NFFT], im = new float[NFFT];
        double[] pw = new double[NFFT / 2 + 1];
        for (int fr = 0; fr < f.frames; fr++) {
            int c = fr * HOP;           // frame centre
            double sq = 0;
            for (int i = 0; i < NFFT; i++) {
                int k = c - NFFT / 2 + i;
                float x = k >= 0 && k < n ? mono[k] : 0;
                re[i] = x * win[i];
                im[i] = 0;
                if (i >= NFFT / 2 - HOP && i < NFFT / 2 + HOP) sq += x * x;
            }
            levDb[fr] = (float) (10 * Math.log10(sq / (2 * HOP) + 1e-10));
            Beats.fft(re, im);
            for (int k = 0; k <= NFFT / 2; k++) pw[k] = re[k] * re[k] + im[k] * im[k];   // absolute scale is irrelevant: levels are normalised per song
            double bassSum = 0;
            int bassN = 0;
            for (int j = 0; j < BANDS; j++) {
                int k0 = (int) Math.ceil(edge[j]), k1 = (int) Math.ceil(edge[j + 1]);
                double p;
                if (k1 > k0) {
                    double s = 0;
                    for (int k = k0; k < k1 && k <= NFFT / 2; k++) s += pw[k];
                    p = s / (k1 - k0);
                } else {   // band narrower than a bin: interpolate at its centre
                    double ck = Math.sqrt(edge[j] * edge[j + 1]);
                    int k = (int) Math.floor(ck);
                    double t = ck - k;
                    p = pw[Math.min(k, NFFT / 2)] * (1 - t) + pw[Math.min(k + 1, NFFT / 2)] * t;
                }
                db[fr * BANDS + j] = (float) (10 * Math.log10(p + 1e-12)) + tilt[j];
            }
            for (int k = (int) Math.ceil(35 / df); k * df < 150; k++) { bassSum += pw[k]; bassN++; }
            bassDb[fr] = (float) (10 * Math.log10(bassSum / Math.max(1, bassN) + 1e-12));
        }
        f.bands = new byte[db.length];
        float ref = percentile(db, 0.995f);
        for (int i = 0; i < db.length; i++) f.bands[i] = levDb[i / BANDS] < -80 ? 0 : (byte) Math.round(255 * clamp01((db[i] - (ref - 50)) / 50));
        f.bass = normalise(bassDb, 0.99f, 30);
        f.level = normalise(levDb, 0.99f, 36);
        for (int i = 0; i < f.frames; i++) if (levDb[i] < -80) { f.bass[i] = 0; f.level[i] = 0; }
        // waveform: 24 k -> 12 k by pair averaging, peak-normalised to 8 bits
        int wn = n / 2;
        float peak = 1e-6f;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(mono[i]));
        f.wave = new byte[wn];
        for (int i = 0; i < wn; i++) f.wave[i] = (byte) Math.round(127 * Math.max(-1, Math.min(1, (mono[i * 2] + mono[i * 2 + 1]) * 0.5f / peak)));
        return f;
    }

    private static float clamp01(float x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    private static float percentile(float[] a, float q) {
        float[] s = a.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) (q * (s.length - 1)))];
    }

    private static float[] normalise(float[] db, float q, float range) {
        float ref = percentile(db, q);
        float[] o = new float[db.length];
        if (ref < -90) return o;
        for (int i = 0; i < db.length; i++) o[i] = clamp01((db[i] - (ref - range)) / range);
        return o;
    }

    // ---------------------------------------------------------------- reading
    private float decay(int k, float releaseS) { return (float) Math.exp(-k / (FPS * Math.max(0.01f, releaseS))); }

    /** Band levels at song time us into out[BANDS] (0..1), released over releaseS seconds. */
    void bandsAt(long us, float releaseS, float[] out) {
        Arrays.fill(out, 0);
        int f0 = (int) Math.floor(us * FPS / 1e6);
        int K = Math.min(400, (int) (FPS * releaseS * 4) + 1);
        for (int k = 0; k < K; k++) {
            int f = f0 - k;
            if (f < 0) break;
            if (f >= frames) continue;
            float d = decay(k, releaseS) / 255f;
            int base = f * BANDS;
            for (int j = 0; j < BANDS; j++) {
                float v = (bands[base + j] & 255) * d;
                if (v > out[j]) out[j] = v;
            }
        }
    }

    private float held(float[] a, long us, float releaseS) {
        int f0 = (int) Math.floor(us * FPS / 1e6);
        int K = Math.min(400, (int) (FPS * releaseS * 4) + 1);
        float m = 0;
        for (int k = 0; k < K; k++) {
            int f = f0 - k;
            if (f < 0) break;
            if (f >= frames) continue;
            m = Math.max(m, a[f] * decay(k, releaseS));
        }
        return m;
    }

    float bassAt(long us, float releaseS) { return held(bass, us, releaseS); }
    float levelAt(long us, float releaseS) { return held(level, us, releaseS); }

    /** n waveform samples (-1..1) covering spanS seconds centred on us. */
    void waveAt(long us, float spanS, float[] out) {
        int n = out.length;
        double c = us / 1e6 * WAVE_RATE, half = spanS * WAVE_RATE / 2;
        for (int i = 0; i < n; i++) {
            double p = c - half + (n == 1 ? half : i * 2 * half / (n - 1));
            int k = (int) Math.floor(p);
            double t = p - k;
            float a = k >= 0 && k < wave.length ? wave[k] : 0, b = k + 1 >= 0 && k + 1 < wave.length ? wave[k + 1] : 0;
            out[i] = (float) ((a + (b - a) * t) / 127.0);
        }
    }

    // ---------------------------------------------------------------- derived sources (music links)
    /** Band index (fractional) of a frequency. */
    static double bandOf(double hz) { return BANDS * Math.log(hz / F_LO) / Math.log(F_HI / F_LO); }

    /**
     * Per-frame level of a frequency range (mean of its bands), spread to 0..1 per song: the
     * 20th percentile maps to 0 (the floor of the mix), the 99th to 1.
     */
    float[] range(double loHz, double hiHz) { return spread(rangeRaw(loHz, hiHz), 0.20f, 0.99f); }

    /** Same range level before spreading: 0..1 spans the bands' 50 dB, so differences are in dB / 50. */
    float[] rangeRaw(double loHz, double hiHz) {
        int j0 = Math.max(0, (int) Math.floor(bandOf(loHz))), j1 = Math.min(BANDS - 1, (int) Math.ceil(bandOf(hiHz)) - 1);
        float[] v = new float[frames];
        for (int f = 0; f < frames; f++) {
            float s = 0;
            for (int j = j0; j <= j1; j++) s += bands[f * BANDS + j] & 255;
            v[f] = s / ((j1 - j0 + 1) * 255f);
        }
        return v;
    }

    /** Spectral flux: how much the spectrum rose since the last frame. Peaks on hits of any kind. */
    float[] flux() { return spread(fluxRaw(), 0.5f, 0.995f); }

    float[] fluxRaw() {
        // The 85 ms analysis window starts seeing an onset ~25 ms before it happens, and flux (unlike
        // a held level) peaks right there; measured on synthetic kicks and hats, it led by 2-3
        // frames. Shift it 3 frames later so hits land on the sound, not before it.
        final int LAG = 3;
        float[] v = new float[frames];
        for (int f = 1; f < frames; f++) {
            float s = 0;
            for (int j = 0; j < BANDS; j++) {
                int d = (bands[f * BANDS + j] & 255) - (bands[(f - 1) * BANDS + j] & 255);
                if (d > 0) s += d;
            }
            if (f + LAG < frames) v[f + LAG] = s;
        }
        return v;
    }

    /**
     * Spectral centre of the frame (energy-weighted band position), spread so the song's 5th
     * percentile is 0 and its 95th is 1. Silent frames keep the previous value.
     */
    float[] brightness() {
        float[] v = new float[frames];
        float prev = 0.5f;
        for (int f = 0; f < frames; f++) {
            double sw = 0, sj = 0;
            for (int j = 0; j < BANDS; j++) {
                double w = bands[f * BANDS + j] & 255;
                w *= w;
                sw += w; sj += w * j;
            }
            prev = sw < 2000 ? prev : (float) (sj / sw / (BANDS - 1));
            v[f] = prev;
        }
        return spread(v, 0.05f, 0.95f);
    }

    private static float[] spread(float[] v, float qlo, float qhi) {
        float lo = percentile(v, qlo), hi = percentile(v, qhi);
        if (hi - lo < 1e-6f) hi = lo + 1e-6f;
        float[] o = new float[v.length];
        for (int i = 0; i < v.length; i++) o[i] = clamp01((v[i] - lo) / (hi - lo));
        return o;
    }

    // ---------------------------------------------------------------- auto level (visualizers)
    private float[] bandMax;
    private float[] wavePeak;
    /** Auto level: the recent peak falls by this much per second (cava raises sensitivity slowly, drops it fast). */
    static final float AUTO_FALL_PER_S = 0.1f;   // band units: 5 dB/s
    static final float AUTO_LOOK_S = 8f;

    /**
     * Auto level for bars, the way cava's autosens works: how far (in band units, 1 = 50 dB) the
     * loudest band has recently been below full. Adding it to every band is a gain in dB that
     * brings quiet passages up to fill the box; capped at 0.5 (25 dB) and eased off towards
     * silence so a quiet room tone doesn't turn into full bars.
     */
    synchronized float autoGain(long us) {
        if (bandMax == null) {
            bandMax = new float[frames];
            for (int f = 0; f < frames; f++) {
                int m = 0;
                for (int j = 0; j < BANDS; j++) m = Math.max(m, bands[f * BANDS + j] & 255);
                bandMax[f] = m / 255f;
            }
        }
        float pk = hold(bandMax, us);
        float g = Math.min(0.5f, 1 - pk);
        return pk < 0.25f ? g * pk / 0.25f : g;
    }

    /** Auto level for the line and ring styles: a multiplier from the recent waveform peak (max 8x). */
    synchronized float autoWaveGain(long us) {
        if (wavePeak == null) {
            wavePeak = new float[frames];
            int per = WAVE_RATE / FPS;   // 120 samples per frame
            for (int f = 0; f < frames; f++) {
                int m = 0;
                for (int i = f * per; i < (f + 1) * per && i < wave.length; i++) m = Math.max(m, Math.abs(wave[i]));
                // in dB units like the bands (0..1 over 50 dB), so the same fall rate applies
                wavePeak[f] = m <= 0 ? 0 : Math.max(0, 1 + (float) (20 * Math.log10(m / 127.0)) / 50f);
            }
        }
        float pk = hold(wavePeak, us);
        if (pk < 0.25f) return 1;   // near silence: leave it
        return (float) Math.min(8, Math.pow(10, (1 - pk) * 50 / 20));
    }

    /** Peak hold with a linear fall in dB: max over the last AUTO_LOOK_S of a[f-k] - k * fall. */
    private static float hold(float[] a, long us) {
        int f0 = (int) Math.floor(us * FPS / 1e6), K = (int) (AUTO_LOOK_S * FPS);
        float step = AUTO_FALL_PER_S / FPS, m = 0;
        for (int k = 0; k < K; k++) {
            int f = f0 - k;
            if (f < 0) break;
            if (f >= a.length) continue;
            float v = a[f] - k * step;
            if (v > m) m = v;
        }
        return m;
    }

    // ---------------------------------------------------------------- cache file
    void write(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            o.writeInt(MAGIC);
            o.writeInt(VERSION);
            o.writeInt(frames);
            o.write(bands);
            for (float x : bass) o.writeFloat(x);
            for (float x : level) o.writeFloat(x);
            o.writeInt(wave.length);
            o.write(wave);
        } finally { o.close(); }
        if (!tmp.renameTo(f)) throw new IOException("cache rename failed");
    }

    static AudioFeatures read(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("old cache");
            AudioFeatures a = new AudioFeatures();
            a.frames = in.readInt();
            a.bands = new byte[a.frames * BANDS];
            in.readFully(a.bands);
            a.bass = new float[a.frames];
            for (int i = 0; i < a.frames; i++) a.bass[i] = in.readFloat();
            a.level = new float[a.frames];
            for (int i = 0; i < a.frames; i++) a.level[i] = in.readFloat();
            a.wave = new byte[in.readInt()];
            in.readFully(a.wave);
            return a;
        } finally { in.close(); }
    }

    /** Cache file name for a song URI. */
    static String cacheName(String uri) { return "fx_" + Integer.toHexString(uri.hashCode()) + "_" + uri.length() + ".bin"; }
}
