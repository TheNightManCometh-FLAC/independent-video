package com.christopher.strata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 3.15: a parametric equalizer for a clip's sound, a B-roll, a music / sound track, or the
 * whole mix. Six bands by default (low cut, low shelf, two bells, high shelf, high cut), each a
 * biquad from the RBJ Audio EQ Cookbook at 48 kHz; cuts are 12 dB/oct (one Butterworth biquad)
 * or 24 dB/oct (two). Export runs the biquads on the samples; the live preview approximates the
 * same curve with the platform's multiband EQ (bands() samples response()). Pure Java.
 */
final class AudioEq {
    static final int LOW_CUT = 0, LOW_SHELF = 1, BELL = 2, HIGH_SHELF = 3, HIGH_CUT = 4, NOTCH = 5;
    static final String[] TYPES = {"Low cut", "Low shelf", "Bell", "High shelf", "High cut", "Notch"};
    static final float FS = 48000f, F_MIN = 20, F_MAX = 20000, G_MAX = 18;

    static final class Band {
        int type;
        float freq, gain, q = 0.9f;
        boolean on = true, steep;
        Band(int type, float freq, float gain, float q) { this.type = type; this.freq = freq; this.gain = gain; this.q = q; }
        Band copy() { Band b = new Band(type, freq, gain, q); b.on = on; b.steep = steep; return b; }
        boolean hasGain() { return type == LOW_SHELF || type == BELL || type == HIGH_SHELF; }
        boolean hasQ() { return type == BELL || type == NOTCH || type == LOW_SHELF || type == HIGH_SHELF; }
    }

    boolean on = true;
    Band[] bands = defaults();

    static Band[] defaults() {
        Band lc = new Band(LOW_CUT, 30, 0, 0.707f); lc.on = false;
        Band hc = new Band(HIGH_CUT, 18000, 0, 0.707f); hc.on = false;
        return new Band[]{lc, new Band(LOW_SHELF, 100, 0, 0.7f), new Band(BELL, 400, 0, 1f),
                new Band(BELL, 2500, 0, 1f), new Band(HIGH_SHELF, 8000, 0, 0.7f), hc};
    }

    AudioEq copy() {
        AudioEq e = new AudioEq();
        e.on = on;
        e.bands = new Band[bands.length];
        for (int i = 0; i < bands.length; i++) e.bands[i] = bands[i].copy();
        return e;
    }

    /** Does nothing to the sound (off, or every band flat or off). */
    boolean flat() {
        if (!on) return true;
        for (Band b : bands) {
            if (!b.on) continue;
            if (b.hasGain() && Math.abs(b.gain) < 0.05f) continue;
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- coefficients (RBJ cookbook)
    /** Normalised biquads {b0, b1, b2, a1, a2} for band b (one, or two for a steep cut). */
    static double[][] coeffs(Band b) {
        double f = Math.max(F_MIN, Math.min(F_MAX, b.freq));
        double w = 2 * Math.PI * f / FS, cw = Math.cos(w), sw = Math.sin(w);
        double q = Math.max(0.1, Math.min(18, b.q));
        double A = Math.pow(10, b.gain / 40.0);
        double b0, b1, b2, a0, a1, a2;
        switch (b.type) {
            case LOW_CUT: case HIGH_CUT: {
                double qq = b.steep ? 0.5412 : 0.7071;   // two Butterworth stages for 24 dB/oct
                double[][] out = new double[b.steep ? 2 : 1][];
                for (int k = 0; k < out.length; k++) {
                    double qk = b.steep ? (k == 0 ? 0.5412 : 1.3066) : qq;
                    double al = sw / (2 * qk);
                    if (b.type == LOW_CUT) { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; }
                    else { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; }
                    a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al;
                    out[k] = new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
                }
                return out;
            }
            case LOW_SHELF: case HIGH_SHELF: {
                double S = Math.max(0.1, Math.min(1, q));   // shelf slope (1 = steepest without a bump)
                double al = sw / 2 * Math.sqrt((A + 1 / A) * (1 / S - 1) + 2), sa = 2 * Math.sqrt(A) * al;
                if (b.type == LOW_SHELF) {
                    b0 = A * ((A + 1) - (A - 1) * cw + sa); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - sa);
                    a0 = (A + 1) + (A - 1) * cw + sa; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - sa;
                } else {
                    b0 = A * ((A + 1) + (A - 1) * cw + sa); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - sa);
                    a0 = (A + 1) - (A - 1) * cw + sa; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - sa;
                }
                break;
            }
            case NOTCH: {
                double al = sw / (2 * q);
                b0 = 1; b1 = -2 * cw; b2 = 1; a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al;
                break;
            }
            default: {   // bell (peaking)
                double al = sw / (2 * q);
                b0 = 1 + al * A; b1 = -2 * cw; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cw; a2 = 1 - al / A;
            }
        }
        return new double[][]{{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0}};
    }

    /** Magnitude response in dB at frequency f (Hz), all active bands. */
    double response(double f) {
        if (!on) return 0;
        double w = 2 * Math.PI * f / FS;
        double db = 0;
        for (Band b : bands) {
            if (!b.on || (b.hasGain() && Math.abs(b.gain) < 0.05f && b.type != NOTCH)) continue;
            for (double[] c : coeffs(b)) {
                // |H(e^jw)| for b0 + b1 z^-1 + b2 z^-2 over 1 + a1 z^-1 + a2 z^-2
                double cr1 = Math.cos(w), ci1 = -Math.sin(w), cr2 = Math.cos(2 * w), ci2 = -Math.sin(2 * w);
                double nr = c[0] + c[1] * cr1 + c[2] * cr2, ni = c[1] * ci1 + c[2] * ci2;
                double dr = 1 + c[3] * cr1 + c[4] * cr2, di = c[3] * ci1 + c[4] * ci2;
                db += 10 * Math.log10((nr * nr + ni * ni) / Math.max(1e-30, dr * dr + di * di));
            }
        }
        return db;
    }

    // ---------------------------------------------------------------- processing
    /** A running filter for interleaved stereo 16-bit or float samples (keeps its state between blocks). */
    static final class Chain {
        final double[][] c;
        final double[] z;   // 2 states x 2 channels per biquad
        Chain(AudioEq eq) {
            java.util.ArrayList<double[]> l = new java.util.ArrayList<double[]>();
            if (eq != null && !eq.flat())
                for (Band b : eq.bands) {
                    if (!b.on || (b.hasGain() && Math.abs(b.gain) < 0.05f)) continue;
                    for (double[] k : coeffs(b)) l.add(k);
                }
            c = l.toArray(new double[0][]);
            z = new double[c.length * 4];
        }
        boolean empty() { return c.length == 0; }

        /** Filters n stereo frames of interleaved floats in place (any scale). */
        void process(float[] s, int off, int n) {
            for (int k = 0; k < c.length; k++) {
                double b0 = c[k][0], b1 = c[k][1], b2 = c[k][2], a1 = c[k][3], a2 = c[k][4];
                int zi = k * 4;
                double l1 = z[zi], l2 = z[zi + 1], r1 = z[zi + 2], r2 = z[zi + 3];
                for (int i = 0; i < n; i++) {
                    int p = off + 2 * i;
                    double xl = s[p], yl = b0 * xl + l1;   // transposed direct form II
                    l1 = b1 * xl - a1 * yl + l2; l2 = b2 * xl - a2 * yl;
                    double xr = s[p + 1], yr = b0 * xr + r1;
                    r1 = b1 * xr - a1 * yr + r2; r2 = b2 * xr - a2 * yr;
                    s[p] = (float) yl; s[p + 1] = (float) yr;
                }
                if (Math.abs(l1) < 1e-25) l1 = 0;   // no denormals in silence
                if (Math.abs(l2) < 1e-25) l2 = 0;
                if (Math.abs(r1) < 1e-25) r1 = 0;
                if (Math.abs(r2) < 1e-25) r2 = 0;
                z[zi] = l1; z[zi + 1] = l2; z[zi + 2] = r1; z[zi + 3] = r2;
            }
        }

        /** Filters little-endian 16-bit stereo bytes in place (n frames), clamped to 16 bits. */
        void processPcm(byte[] b, int n) {
            float[] f = new float[n * 2];
            for (int i = 0; i < n * 2; i++) f[i] = (short) ((b[2 * i] & 255) | (b[2 * i + 1] << 8));
            process(f, 0, n);
            for (int i = 0; i < n * 2; i++) {
                int v = Math.round(Math.max(-32768, Math.min(32767, f[i])));
                b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
            }
        }
    }

    // ---------------------------------------------------------------- the preview's multiband EQ
    /** Band edges for the preview EQ: 24 log-spaced bands from 20 Hz to 20 kHz (upper cutoffs). */
    static final int PREVIEW_BANDS = 24;

    static float previewCutoff(int i) { return (float) (20 * Math.pow(1000, (i + 1) / (double) PREVIEW_BANDS)); }

    /**
     * Gains (dB, -24..24) for the preview's bands: the curve sampled at each band's centre, item
     * EQ plus the master EQ (either may be null).
     */
    static float[] previewGains(AudioEq item, AudioEq master) {
        float[] g = new float[PREVIEW_BANDS];
        for (int i = 0; i < PREVIEW_BANDS; i++) {
            double c = 20 * Math.pow(1000, (i + 0.5) / PREVIEW_BANDS);
            double db = (item == null ? 0 : item.response(c)) + (master == null ? 0 : master.response(c));
            g[i] = (float) Math.max(-24, Math.min(24, db));
        }
        return g;
    }

    // ---------------------------------------------------------------- presets
    static final String[] PRESETS = {"Flat", "Bass boost", "Less bass", "Warm", "Bright", "Voice", "De-mud", "Radio", "Telephone", "Lo-fi", "Next room", "Air"};

    static AudioEq preset(int i) {
        AudioEq e = new AudioEq();
        Band[] b = e.bands;
        switch (i) {
            case 1: b[1].gain = 6; b[1].freq = 90; break;
            case 2: b[0].on = true; b[0].freq = 80; b[1].gain = -6; b[1].freq = 150; break;
            case 3: b[1].gain = 3; b[1].freq = 200; b[4].gain = -3; b[4].freq = 7000; break;
            case 4: b[4].gain = 5; b[4].freq = 6000; b[3].gain = 2; b[3].freq = 3500; break;
            case 5: b[0].on = true; b[0].freq = 90; b[2].gain = -3; b[2].freq = 300; b[3].gain = 4; b[3].freq = 3000; b[3].q = 0.8f; break;
            case 6: b[2].gain = -5; b[2].freq = 350; b[2].q = 1.2f; break;
            case 7: b[0].on = true; b[0].freq = 200; b[5].on = true; b[5].freq = 6000; b[3].gain = 5; b[3].freq = 1800; break;
            case 8: b[0].on = true; b[0].freq = 400; b[0].steep = true; b[5].on = true; b[5].freq = 3200; b[5].steep = true; b[3].gain = 6; b[3].freq = 1500; break;
            case 9: b[0].on = true; b[0].freq = 120; b[5].on = true; b[5].freq = 4500; b[2].gain = 3; b[2].freq = 900; break;
            case 10: b[5].on = true; b[5].freq = 700; b[5].steep = true; b[1].gain = 3; break;
            case 11: b[4].gain = 4; b[4].freq = 12000; break;
            default: break;
        }
        return e;
    }

    int presetIndex() {
        for (int i = 0; i < PRESETS.length; i++) if (preset(i).same(this)) return i;
        return -1;
    }

    boolean same(AudioEq o) {
        if (o == null || o.bands.length != bands.length || o.on != on) return false;
        for (int i = 0; i < bands.length; i++) {
            Band a = bands[i], b = o.bands[i];
            if (a.type != b.type || a.on != b.on || a.steep != b.steep || Math.abs(a.freq - b.freq) > 0.5f || Math.abs(a.gain - b.gain) > 0.01f || Math.abs(a.q - b.q) > 0.001f) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- JSON
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (!on) o.put("off", true);
        JSONArray a = new JSONArray();
        for (Band b : bands) {
            JSONObject j = new JSONObject();
            j.put("t", b.type); j.put("f", (double) b.freq); j.put("g", (double) b.gain); j.put("q", (double) b.q);
            if (!b.on) j.put("off", true);
            if (b.steep) j.put("st", true);
            a.put(j);
        }
        o.put("b", a);
        return o;
    }

    static AudioEq fromJson(JSONObject o) {
        if (o == null) return null;
        AudioEq e = new AudioEq();
        e.on = !o.optBoolean("off");
        JSONArray a = o.optJSONArray("b");
        if (a == null || a.length() == 0 || a.length() > 12) return e;
        java.util.ArrayList<Band> l = new java.util.ArrayList<Band>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Band b = new Band(Math.max(0, Math.min(NOTCH, j.optInt("t", BELL))), fin(j.optDouble("f", 1000), 1000, F_MIN, F_MAX),
                    fin(j.optDouble("g", 0), 0, -G_MAX, G_MAX), fin(j.optDouble("q", 0.9), 0.9f, 0.1f, 18));
            b.on = !j.optBoolean("off");
            b.steep = j.optBoolean("st");
            l.add(b);
        }
        if (!l.isEmpty()) e.bands = l.toArray(new Band[0]);
        return e;
    }

    private static float fin(double v, float d, float lo, float hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return d;
        return (float) Math.max(lo, Math.min(hi, v));
    }
}
