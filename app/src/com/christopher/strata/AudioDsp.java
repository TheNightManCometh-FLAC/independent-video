package com.christopher.strata;

/**
 * Audio helpers on interleaved 16-bit stereo: band-limited resampling to the project rate and WSOLA
 * time stretching (speed change without pitch change). Pure Java.
 */
final class AudioDsp {
    static final int RATE = 48000;

    /** Any channel count -> stereo; any rate -> RATE, band-limited (see Resampler). */
    static short[] toStereo48k(short[] in, int channels, int rate) {
        Resampler r = new Resampler(channels, rate);
        short[] a = r.process(in, in.length / Math.max(1, channels));
        short[] b = r.flush();
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Streaming band-limited resampler: interleaved 16-bit at any rate and channel count in,
     * stereo at RATE out, chunk by chunk with no seams.
     *
     * Windowed sinc (Kaiser, beta 8.6: about -85 dB stopband), 16 zero crossings each side at
     * the lower of the two rates, cutoff at 95 % of the lower Nyquist. Downsampling widens the
     * kernel so everything above the new Nyquist is removed instead of folding back (3.2's linear
     * interpolation turned a 30 kHz component of 96 kHz audio into an audible 18 kHz tone).
     * Every output frame is computed at its exact input position from an absolute counter, so
     * the output length tracks input * RATE / rate exactly whatever the chunk sizes; flush()
     * returns the last few frames at the end of a stream.
     *
     * Channels: mono goes to both sides; 3+ channels are downmixed with the usual -3 dB centre
     * and surrounds (Android order: FL FR FC LFE BL BR SL SR), LFE dropped.
     * Already 48 kHz stereo passes straight through.
     */
    static final class Resampler {
        static final int ZC = 16, L = 512;
        private static float[] kernel;        // g(u), u = 0..ZC in steps of 1/L (output-rate units)
        private final int ch, rate;
        private final boolean bypass, same;
        private final double step, scale;     // input frames per output frame; min(1, RATE/rate)
        private final double reach;           // kernel half width in input frames
        private float[] bl = new float[4096], br = new float[4096];
        private long base;                    // absolute input index of bl[0]
        private int count;
        private double pos;                   // absolute input position of the next output frame

        Resampler(int channels, int rate) {
            ch = Math.max(1, channels);
            this.rate = Math.max(1, rate);
            same = this.rate == RATE;
            bypass = same && ch == 2;
            step = this.rate / (double) RATE;
            scale = Math.min(1.0, RATE / (double) this.rate);
            reach = ZC / scale;
            synchronized (Resampler.class) { if (kernel == null) kernel = buildKernel(); }
        }

        private static float[] buildKernel() {
            float[] g = new float[ZC * L + 2];
            double beta = 8.6, i0b = bessel0(beta), fc = 0.95;
            for (int i = 0; i < g.length; i++) {
                double u = i / (double) L;
                if (u >= ZC) { g[i] = 0; continue; }
                double x = Math.PI * fc * u;
                double sinc = u == 0 ? 1 : Math.sin(x) / x;
                double w = bessel0(beta * Math.sqrt(1 - (u / ZC) * (u / ZC))) / i0b;
                g[i] = (float) (fc * sinc * w);
            }
            return g;
        }

        private static double bessel0(double x) {
            double s = 1, t = 1;
            for (int k = 1; k < 40; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; }
            return s;
        }

        /** Interleaved input (frames * channels) -> interleaved stereo at RATE. */
        short[] process(short[] in, int frames) {
            if (frames <= 0) return new short[0];
            if (bypass) {
                short[] o = new short[frames * 2];
                System.arraycopy(in, 0, o, 0, frames * 2);
                return o;
            }
            append(in, frames);
            if (same) return drain(base + count, false);   // channel mapping only
            if (phases == null) buildPhases();
            return drain(base + count - R, false);   // outputs whose window (floor(p) - R + 1 .. floor(p) + R) is all here
        }

        /** The remaining output at the end of the stream (the input is treated as silent after it). */
        short[] flush() {
            if (bypass) return new short[0];
            long total = base + count;
            if (same) return drain(total, true);
            long outEnd = (long) Math.ceil(total / step);   // outputs whose position is < total
            if (phases == null) buildPhases();
            int extra = 2 * R + 2;
            ensure(count + extra);
            for (int i = 0; i < extra; i++) { bl[count + i] = 0; br[count + i] = 0; }
            count += extra;
            short[] o = produce((int) Math.max(0, outEnd - outIndex));
            count = 0;   // the stream is over
            return o;
        }

        private void append(short[] in, int frames) {
            ensure(count + frames);
            for (int i = 0; i < frames; i++) {
                float l, r;
                int k = i * ch;
                if (ch == 1) { l = r = in[k]; }
                else if (ch == 2) { l = in[k]; r = in[k + 1]; }
                else {
                    float c = ch > 2 ? in[k + 2] : 0;
                    float bl0 = ch > 4 ? in[k + 4] : 0, br0 = ch > 5 ? in[k + 5] : 0;
                    float sl = ch > 6 ? in[k + 6] : 0, sr = ch > 7 ? in[k + 7] : 0;
                    l = (in[k] + 0.7071f * (c + bl0 + sl)) * 0.6f;
                    r = (in[k + 1] + 0.7071f * (c + br0 + sr)) * 0.6f;
                }
                bl[count + i] = l;
                br[count + i] = r;
            }
            count += frames;
        }

        private void ensure(int n) {
            if (n <= bl.length) return;
            int m = Math.max(n, bl.length * 2);
            float[] a = new float[m], b = new float[m];
            System.arraycopy(bl, 0, a, 0, count);
            System.arraycopy(br, 0, b, 0, count);
            bl = a; br = b;
        }

        /** Outputs whose kernel ends at or before input index lastNeeded (or all, for same-rate mapping). */
        private short[] drain(long lastNeeded, boolean all) {
            if (same) {
                int n = (int) (lastNeeded - base);
                short[] o = new short[n * 2];
                for (int i = 0; i < n; i++) { o[i * 2] = clip(bl[i]); o[i * 2 + 1] = clip(br[i]); }
                base += n; count -= n;
                return o;
            }
            int n = 0;
            long oi = outIndex;
            while (Math.floor(oi * step) <= lastNeeded) { n++; oi++; }
            return produce(n);
        }

        /**
         * Weights per fractional phase: phases[q][j] for an output at input position i0 + q/P,
         * applied to input frames i0 - R + 1 + j (j = 0 .. 2R-1). P = 4096 phases keep the
         * position error under 1/8192 frame (about -70 dB at 18 kHz) with a plain multiply-add
         * inner loop.
         */
        private float[][] phases;
        private int R;
        private static final int P = 4096;

        private void buildPhases() {
            R = (int) Math.ceil(reach) + 1;
            phases = new float[P + 1][2 * R];
            float[] g = kernel;
            for (int q = 0; q <= P; q++) {
                double frac = q / (double) P;
                for (int j = 0; j < 2 * R; j++) {
                    double d = Math.abs(frac - (j - R + 1));   // distance in input frames
                    double t = d * scale * L;
                    int ti = (int) t;
                    double w = ti >= g.length - 1 ? 0 : g[ti] + (g[ti + 1] - g[ti]) * (t - ti);
                    phases[q][j] = (float) (w * scale);
                }
            }
        }

        private short[] produce(int n) {
            if (phases == null) buildPhases();
            short[] o = new short[n * 2];
            int twoR = 2 * R;
            for (int j = 0; j < n; j++) {
                double p = pos;
                long i0 = (long) Math.floor(p);
                float[] w = phases[(int) Math.round((p - i0) * P)];
                int first = (int) (i0 - R + 1 - base);
                float sl = 0, sr = 0;
                if (first >= 0 && first + twoR <= count) {
                    for (int k = 0; k < twoR; k++) { float wk = w[k]; sl += bl[first + k] * wk; sr += br[first + k] * wk; }
                } else {
                    for (int k = 0; k < twoR; k++) {
                        int idx = first + k;
                        if (idx < 0 || idx >= count) continue;   // before the stream / past the end: silence
                        sl += bl[idx] * w[k]; sr += br[idx] * w[k];
                    }
                }
                o[j * 2] = clip(sl);
                o[j * 2 + 1] = clip(sr);
                // exact positions from an output counter: no accumulated rounding over hours
                outIndex++;
                pos = outIndex * step;
            }
            // drop input no future output can reach
            long keepFrom = (long) Math.floor(pos) - R;
            int drop = (int) Math.max(0, Math.min(count, keepFrom - base));
            if (drop > 0) {
                System.arraycopy(bl, drop, bl, 0, count - drop);
                System.arraycopy(br, drop, br, 0, count - drop);
                base += drop; count -= drop;
            }
            return o;
        }

        private long outIndex;

        private static short clip(float v) { return (short) Math.max(-32768, Math.min(32767, Math.round(v))); }
    }

    /**
     * WSOLA: output hop Hs = N/2 with Hann windows (which sum to 1 at that overlap); each next
     * input frame is taken near k*Hs*speed, shifted within +-DELTA to the position whose waveform
     * best continues the previous frame (cross-correlation on a decimated mono mix).
     * Returns stereo interleaved, length ~= input / speed.
     */
    /**
     * 3.15 tape speed: plays interleaved stereo at speed by resampling (pitch follows, like a tape
     * or record sped up), cubic Hermite between samples. Output length = input / speed.
     */
    static short[] varispeed(short[] in, float speed) {
        int frames = in.length / 2;
        if (frames < 2 || speed <= 0) return in;
        int outF = (int) Math.max(1, Math.floor((frames - 1) / speed));
        short[] out = new short[outF * 2];
        for (int i = 0; i < outF; i++) {
            double p = i * (double) speed;
            int k = (int) p;
            float t = (float) (p - k);
            for (int ch = 0; ch < 2; ch++) {
                float y0 = in[2 * Math.max(0, k - 1) + ch], y1 = in[2 * k + ch], y2 = in[2 * Math.min(frames - 1, k + 1) + ch], y3 = in[2 * Math.min(frames - 1, k + 2) + ch];
                float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2 * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
                float v = ((c3 * t + c2) * t + c1) * t + y1;
                out[2 * i + ch] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            }
        }
        return out;
    }

    static short[] stretch(short[] in, float speed) {
        if (Math.abs(speed - 1f) < 0.001f || in.length < 4096) return in.clone();
        final int N = 1536, Hs = N / 2, DELTA = 480;
        int frames = in.length / 2;
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) mono[i] = (in[i * 2] + in[i * 2 + 1]) * 0.5f;
        int outFrames = (int) (frames / speed);
        float[] acc = new float[(outFrames + N) * 2];
        float[] win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        int prev = 0;
        for (int k = 0; ; k++) {
            int o = k * Hs;
            if (o >= outFrames) break;
            int nominal = (int) ((long) k * Hs * speed);
            int pos;
            if (k == 0) pos = 0;
            else {
                int tmpl = prev + Hs;   // natural continuation of the previous frame
                int lo = Math.max(0, nominal - DELTA), hi = Math.min(frames - N - 1, nominal + DELTA);
                if (hi < lo || tmpl + N >= frames) pos = Math.max(0, Math.min(frames - N - 1, nominal));
                else {
                    double best = -Double.MAX_VALUE;
                    int bp = lo;
                    for (int c = lo; c <= hi; c += 2) {
                        double s = 0;
                        for (int j = 0; j < N; j += 4) s += mono[c + j] * mono[tmpl + j];
                        if (s > best) { best = s; bp = c; }
                    }
                    pos = bp;
                }
            }
            if (pos < 0 || pos + N > frames) break;
            for (int j = 0; j < N; j++) {
                float w = win[j];
                acc[(o + j) * 2] += in[(pos + j) * 2] * w;
                acc[(o + j) * 2 + 1] += in[(pos + j) * 2 + 1] * w;
            }
            prev = pos;
        }
        short[] out = new short[outFrames * 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(acc[i])));
        return out;
    }
}
