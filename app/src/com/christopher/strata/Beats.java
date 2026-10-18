package com.christopher.strata;

import java.util.ArrayList;

/**
 * Beat tracking for cutting to music: spectral-flux onset envelope (log magnitudes, 11025 Hz,
 * 1024-point frames, 256 hop), tempo from the envelope's autocorrelation weighted toward 120 BPM,
 * then Ellis (2007) dynamic programming to place beats that follow the onsets while keeping the
 * tempo steady. Pure Java.
 */
final class Beats {
    static final int SR = 11025, NFFT = 1024, HOP = 256;

    static final class Result {
        float bpm;
        float[] beats;    // seconds
        float[] onsets;   // seconds, strong onsets (for snapping too)
    }

    /** mono: samples at SR (use downsampleMono first). */
    static Result analyze(float[] mono) {
        Result r = new Result();
        double energy = 0;
        for (float sample : mono) energy += sample * sample;
        if (mono.length == 0 || energy / mono.length < 1e-8) { r.beats = r.onsets = new float[0]; return r; }
        float[] env = onsetEnvelope(mono);
        int n = env.length;
        float fps = SR / (float) HOP;
        r.onsets = pickOnsets(env, fps);
        int evidence = 0;
        for (float e : env) if (e > 1.5f) evidence++;
        if (r.onsets.length < 3 || evidence < 3 || n < fps * 4) { r.beats = new float[0]; r.bpm = 0; return r; }
        // tempo: autocorrelation over 60..200 BPM with a log-gaussian prior around 120
        int minLag = Math.round(fps * 60 / 200f), maxLag = Math.round(fps * 60 / 60f);
        double bestScore = -1;
        int bestLag = Math.round(fps * 0.5f);
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += env[i] * env[i - lag];
            s /= (n - lag);
            double bpm = 60.0 * fps / lag;
            double prior = Math.exp(-0.5 * Math.pow(Math.log(bpm / 120.0) / Math.log(2) / 0.9, 2));
            double sc = s * prior;
            if (sc > bestScore) { bestScore = sc; bestLag = lag; }
        }
        // refine lag to sub-frame by parabolic interpolation on the raw autocorrelation
        double period = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double a = ac(env, bestLag - 1), b = ac(env, bestLag), c = ac(env, bestLag + 1);
            double d = a - 2 * b + c;
            if (d < 0) period = bestLag + 0.5 * (a - c) / d;
        }
        r.bpm = (float) (60.0 * fps / period);
        r.beats = track(env, period, fps);
        return r;
    }

    static Result fromFeatures(AudioFeatures fx) {
        int n = (int) ((long) fx.wave.length * SR / AudioFeatures.WAVE_RATE);
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            double at = i * (double) AudioFeatures.WAVE_RATE / SR;
            int k = (int) at;
            float a = fx.wave[k] / 127f, b = k + 1 < fx.wave.length ? fx.wave[k + 1] / 127f : a;
            int frame=(int)(at*AudioFeatures.FPS/AudioFeatures.WAVE_RATE);
            mono[i] = frame<fx.frames && fx.level[frame]>0 ? (float) (a + (b - a) * (at - k)) : 0;
        }
        return analyze(mono);
    }

    private static double ac(float[] e, int lag) {
        double s = 0;
        for (int i = lag; i < e.length; i++) s += e[i] * e[i - lag];
        return s / (e.length - lag);
    }

    /** Average down to SR from any rate (mono). */
    static float[] downsampleMono(short[] stereo48k) {
        int frames = stereo48k.length / 2;
        double step = AudioDsp.RATE / (double) SR;
        int out = (int) (frames / step);
        float[] m = new float[out];
        for (int i = 0; i < out; i++) {
            int a = (int) (i * step), b = (int) Math.min(frames, (i + 1) * step);
            double s = 0;
            for (int j = a; j < b; j++) s += stereo48k[j * 2] + stereo48k[j * 2 + 1];
            m[i] = (float) (s / Math.max(1, (b - a) * 2) / 32768.0);
        }
        return m;
    }

    /**
     * Band-wise spectral flux: 24 log-spaced bands (40 Hz - 5 kHz), log band energy, positive
     * differences summed per band, so a kick (few low bins) weighs as much as a broadband hat.
     */
    static float[] onsetEnvelope(float[] x) {
        int frames = Math.max(0, (x.length - NFFT) / HOP + 1);
        float[] env = new float[frames];
        float[] win = new float[NFFT];
        for (int i = 0; i < NFFT; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT));
        final int BANDS = 24;
        int[] edge = new int[BANDS + 1];
        for (int b = 0; b <= BANDS; b++) {
            double hz = 40 * Math.pow(5000 / 40.0, b / (double) BANDS);
            edge[b] = Math.max(1, Math.min(NFFT / 2 - 1, (int) Math.round(hz * NFFT / SR)));
        }
        for (int b = 1; b <= BANDS; b++) if (edge[b] <= edge[b - 1]) edge[b] = edge[b - 1] + 1;
        float[] re = new float[NFFT], im = new float[NFFT];
        float[] prev = new float[BANDS], band = new float[BANDS];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < NFFT; i++) { re[i] = x[f * HOP + i] * win[i]; im[i] = 0; }
            fft(re, im);
            float flux = 0;
            for (int b = 0; b < BANDS; b++) {
                double e = 0;
                for (int k = edge[b]; k < Math.min(NFFT / 2, edge[b + 1]); k++) e += re[k] * re[k] + im[k] * im[k];
                band[b] = (float) Math.log1p(1e4 * e / Math.max(1, edge[b + 1] - edge[b]));
                float d = band[b] - prev[b];
                if (d > 0) flux += d;
            }
            System.arraycopy(band, 0, prev, 0, BANDS);
            env[f] = f == 0 ? 0 : flux;
        }
        // remove slow trend, half-wave rectify, normalise
        float[] out = new float[frames];
        int w = 16;
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            double m = 0;
            int a = Math.max(0, i - w), b = Math.min(frames - 1, i + w);
            for (int j = a; j <= b; j++) m += env[j];
            m /= (b - a + 1);
            out[i] = (float) Math.max(0, env[i] - m);
            sum += out[i] * out[i];
        }
        double sd = Math.sqrt(sum / Math.max(1, frames));
        if (sd > 0) for (int i = 0; i < frames; i++) out[i] /= sd;
        return out;
    }

    private static float[] pickOnsets(float[] env, float fps) {
        ArrayList<Float> o = new ArrayList<Float>();
        int gap = Math.round(fps * 0.1f);
        int last = -gap;
        for (int i = 2; i < env.length - 2; i++) {
            float v = env[i];
            if (v < 1.5f) continue;
            if (v >= env[i - 1] && v >= env[i + 1] && v >= env[i - 2] && v >= env[i + 2] && i - last >= gap) {
                o.add((i * HOP + NFFT * 0.7f) / SR);
                last = i;
            }
        }
        float[] a = new float[o.size()];
        for (int i = 0; i < a.length; i++) a[i] = o.get(i);
        return a;
    }

    /** Ellis 2007 dynamic-programming beat tracker. */
    private static float[] track(float[] env, double period, float fps) {
        int n = env.length;
        double[] score = new double[n];
        int[] back = new int[n];
        double alpha = 100;
        int lo = (int) Math.round(period / 2), hi = (int) Math.round(period * 2);
        for (int t = 0; t < n; t++) {
            double best = 0;
            int bi = -1;
            for (int tau = Math.max(0, t - hi); tau <= t - lo; tau++) {
                double d = Math.log((t - tau) / period);
                double sc = score[tau] - alpha * d * d;
                if (bi < 0 || sc > best) { best = sc; bi = tau; }
            }
            score[t] = env[t] + (bi >= 0 ? best : 0);
            back[t] = bi;
        }
        // start from the best-scoring frame in the last period
        int t = n - 1;
        double bs = -Double.MAX_VALUE;
        for (int i = Math.max(0, n - (int) Math.round(period)); i < n; i++) if (score[i] > bs) { bs = score[i]; t = i; }
        ArrayList<Float> beats = new ArrayList<Float>();
        while (t >= 0) {
            beats.add(0, (t * HOP + NFFT * 0.7f) / SR);
            t = back[t];
        }
        float[] a = new float[beats.size()];
        for (int i = 0; i < a.length; i++) a[i] = beats.get(i);
        return a;
    }

    /** In-place radix-2 complex FFT. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
