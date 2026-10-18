package com.christopher.strata;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Music links: a grade layer's settings driven by the song (the project's music sync track).
 *
 * A link ties one setting of a layer (a target: exposure, a hue, an effect parameter...) to one
 * music source, in one of three modes:
 *   Follow  the setting moves by amount x the source's level, falling back over Smoothing.
 *   Step    every hit (a beat, or the source crossing Threshold) advances the setting by amount,
 *           gliding over Smoothing. Hue steps wrap around the colour wheel.
 *   Drift   the setting keeps moving at amount per second while the source is active: loud
 *           passages turn the hue fast, quiet ones barely.
 *
 * Everything is a function of song time (prefix sums, sorted event lists, look-back holds), so
 * scrubbing, the preview and the export give identical frames. Pure Java apart from Layer.
 */
final class Music {
    static final int BEAT = 0, BASS = 1, MIDS = 2, HIGHS = 3, LEVEL = 4, HITS = 5, BRIGHT = 6;
    static final String[] SOURCES = {"Beat", "Bass", "Mids", "Highs", "Level", "Hits", "Brightness"};
    static final String[] SOURCE_INFO = {
            "Detected beats: a punch on every beat",
            "Kick and bass (35-150 Hz)",
            "Vocals, snares, keys (250 Hz - 2 kHz)",
            "Hats, cymbals, air (4-12 kHz)",
            "Overall loudness",
            "Sudden changes anywhere in the spectrum: drums, stabs, cuts",
            "How bright the sound is (spectral centre): dark bass passages low, bright hats high"};
    static final int FOLLOW = 0, STEP = 1, DRIFT = 2;
    static final String[] MODES = {"Follow", "Step", "Drift"};
    static final float BEAT_ENV = 0.22f;   // decay of the beat envelope used by Drift and Step

    /** One link. Amount is in the target's own units (degrees, EV, effect units). */
    static final class Link {
        int target;
        int src = BASS;
        int mode = FOLLOW;
        float amount;
        float smooth = 0.15f;   // s: fall time (Follow), glide time (Step), averaging (Brightness)
        float thresh;           // 0..1 of the source; Step: what counts as a hit
        int every = 1;          // Step: hits per step
        /** Adaptive: measured against the last ~1.3 s instead of the whole song (MilkDrop-style). */
        boolean adapt;
        int audio = AudioControl.SELF;
        boolean enabled = true, postFader;
        long startUs, endUs = -1;
        long phaseUs = -1;
        String phaseUri;
        float fadeIn, fadeOut;
        boolean local = true;
        Keys amountKeys = new Keys();

        Link copy() {
            Link l = new Link();
            l.target = target; l.src = src; l.mode = mode; l.amount = amount; l.smooth = smooth; l.thresh = thresh; l.every = every;
            l.adapt = adapt; l.audio = audio; l.enabled = enabled; l.postFader = postFader;
            l.startUs = startUs; l.endUs = endUs; l.fadeIn = fadeIn; l.fadeOut = fadeOut; l.local = local; l.amountKeys = amountKeys.copy();
            l.phaseUs = phaseUs; l.phaseUri = phaseUri;
            return l;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", target); o.put("s", src); o.put("m", mode); o.put("a", (double) amount);
            o.put("sm", (double) smooth); o.put("th", (double) thresh); o.put("ev", every);
            if (adapt) o.put("ad", true);
            o.put("audio", audio); o.put("on", enabled); o.put("post", postFader);
            o.put("from", startUs); o.put("to", endUs); o.put("fi", (double) fadeIn); o.put("fo", (double) fadeOut); o.put("local", local);
            if (phaseUri != null) { o.put("phaseUs", phaseUs); o.put("phaseUri", phaseUri); }
            if (!amountKeys.isEmpty()) o.put("amountKeys", VProject.keysJson(amountKeys));
            return o;
        }

        static Link fromJson(JSONObject o) {
            Link l = new Link();
            l.target = o.optInt("t"); l.src = clampI(o.optInt("s", BASS), 0, SOURCES.length - 1);
            l.mode = clampI(o.optInt("m"), 0, MODES.length - 1);
            l.amount = fin((float) o.optDouble("a", 0), 0);
            l.smooth = Math.max(0.02f, Math.min(2f, fin((float) o.optDouble("sm", 0.15), 0.15f)));
            l.thresh = Math.max(0, Math.min(0.95f, fin((float) o.optDouble("th", 0), 0)));
            l.every = clampI(o.optInt("ev", 1), 1, 16);
            l.audio = o.optInt("audio", AudioControl.GUIDE); l.enabled = o.optBoolean("on", true); l.postFader = o.optBoolean("post", false);
            l.startUs = o.optLong("from", 0); l.endUs = Math.max(-1, o.optLong("to", -1));
            l.phaseUs = o.optLong("phaseUs", -1); l.phaseUri = o.optString("phaseUri", null);
            l.fadeIn = Math.max(0, Math.min(10, fin((float) o.optDouble("fi", 0), 0))); l.fadeOut = Math.max(0, Math.min(10, fin((float) o.optDouble("fo", 0), 0)));
            l.local = o.optBoolean("local", false);
            try { l.amountKeys = VProject.readKeys(o.optJSONArray("amountKeys")); } catch (JSONException ignored) {}
            l.adapt = o.optBoolean("ad", false);   // links from before 3.4.2 measured against the whole song
            return l;
        }
    }

    static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static float fin(float v, float d) { return Float.isNaN(v) || Float.isInfinite(v) ? d : v; }

    // ================================================================ targets
    /** A setting a link can drive. */
    static final class Target {
        final int id;
        final String name;
        final float min, max, scale;   // value range; display = value x scale
        final int dec;
        final String unit;
        final boolean cyclic;          // wraps (hue) instead of clamping

        Target(int id, String name, float min, float max, float scale, int dec, String unit, boolean cyclic) {
            this.id = id; this.name = name; this.min = min; this.max = max; this.scale = scale; this.dec = dec; this.unit = unit; this.cyclic = cyclic;
        }
    }

    static final int T_OPACITY = 0, T_BASIC = 10, T_HSL_HUE = 30, T_HSL_SAT = 31, T_HSL_LUM = 32, T_FX = 100;
    static final String[] BASIC = {"Temperature", "Tint", "Exposure", "Contrast", "Highlights", "Shadows", "Whites", "Blacks", "Vibrance", "Saturation"};

    /** Everything on this layer a link can drive. */
    static ArrayList<Target> targets(Layer l) {
        ArrayList<Target> out = new ArrayList<Target>();
        switch (l.type) {
            case Layer.BASIC:
                for (int i = 0; i < BASIC.length; i++)
                    out.add(i == 2 ? new Target(T_BASIC + i, BASIC[i], -5, 5, 1, 2, " EV", false)
                            : new Target(T_BASIC + i, BASIC[i], -1, 1, 100, 0, "", false));
                break;
            case Layer.HSL:
                out.add(new Target(T_HSL_HUE, "Hue (every colour)", -1, 1, 30, 0, "°", false));
                out.add(new Target(T_HSL_SAT, "Saturation (every colour)", -1, 1, 100, 0, "", false));
                out.add(new Target(T_HSL_LUM, "Luminance (every colour)", -1, 1, 100, 0, "", false));
                break;
            case Layer.EFFECT: {
                Fx.Def d = Fx.get(l.fx);
                for (int i = 0; i < d.params.length; i++) {
                    if (d.isChoice(i)) continue;
                    Fx.P p = d.params[i];
                    boolean cyc = (l.fx == Fx.HUESHIFT && i == 0) || (l.fx == Fx.COLORIZE && i == 0)
                            || (p.unit.equals("°") && Math.abs(p.max - p.min - 360) < 0.1f);   // a full turn wraps
                    out.add(new Target(T_FX + i, p.label, p.min, p.max, p.scale, p.dec, p.unit, cyc));
                }
                break;
            }
            default: break;
        }
        out.add(new Target(T_OPACITY, "Opacity", 0, 1, 100, 0, "%", false));
        // 3.15: the mask's shapes and text move with the music too
        if (l.mask != null || l.paint != null) {
            float mw = l.mask != null ? l.mask.getWidth() : Math.max(8, l.vmW), mh = l.mask != null ? l.mask.getHeight() : Math.max(8, l.vmH);
            for (int k = 0; k < Math.min(l.comps.size(), MaskStack.MAX); k++) {
                MaskStack.Comp c = l.comps.get(k);
                String n = c.name(k) + " · ";
                int b = T_MASK + k * 16;
                out.add(new Target(b + M_SIZE, n + "Size", -0.95f, 4, 100, 0, "%", false));
                out.add(new Target(b + M_X, n + "Move across", -mw, mw, 100 / mw, 0, "%", false));
                out.add(new Target(b + M_Y, n + "Move down", -mh, mh, 100 / mh, 0, "%", false));
                out.add(new Target(b + M_ROT, n + "Rotation", -36000, 36000, 1, 0, "°", false));
                out.add(new Target(b + M_AMOUNT, n + "Amount", 0, 1, 100, 0, "%", false));
                if (c.type == MaskStack.RADIAL || c.isBox())
                    out.add(new Target(b + M_FEATHER, n + (c.isBox() ? "Soft edge" : "Feather"), 0, 1, 100, 0, "%", false));
                if (c.isBox()) {
                    out.add(new Target(b + M_GROW, n + "Grow", -1, 1, 100, 0, "%", false));
                    out.add(new Target(b + M_OUTLINE, n + "Outline", 0, 1, 100, 0, "%", false));
                    out.add(new Target(b + M_REVEAL, n + "Reveal", 0, 1, 100, 0, "%", false));
                }
            }
        }
        return out;
    }

    /** 3.15 mask targets: T_MASK + 16 x shape index + one of these. */
    static final int T_MASK = 1000, M_SIZE = 0, M_X = 1, M_Y = 2, M_ROT = 3, M_AMOUNT = 4, M_FEATHER = 5, M_GROW = 6, M_OUTLINE = 7, M_REVEAL = 8;

    private static MaskStack.Comp maskComp(Layer l, int id) {
        if (id < T_MASK) return null;
        int k = (id - T_MASK) / 16;
        return k < l.comps.size() ? l.comps.get(k) : null;
    }

    static Target target(Layer l, int id) {
        for (Target t : targets(l)) if (t.id == id) return t;
        return null;
    }

    static float get(Layer l, int id) {
        if (id == T_OPACITY) return l.opacity;
        if (id >= T_BASIC && id < T_BASIC + 10) return l.basic[id - T_BASIC];
        if (id >= T_HSL_HUE && id <= T_HSL_LUM) return 0;   // offsets added to all 8 bands
        if (id >= T_FX && id < T_FX + 8) return l.fxp[id - T_FX];
        MaskStack.Comp c = maskComp(l, id);
        if (c != null) switch ((id - T_MASK) % 16) {
            case M_ROT: return c.angle;
            case M_AMOUNT: return c.amount;
            case M_FEATHER: return c.feather;
            case M_GROW: return c.grow;
            case M_OUTLINE: return c.outline;
            case M_REVEAL: return c.reveal;
            default: return 0;   // size and moves are offsets from where the shape is
        }
        return 0;
    }

    /** Adds delta to target id of l (a private copy), wrapping or clamping to its range. */
    static void add(Layer l, Target t, float delta) {
        int id = t.id;
        if (id >= T_HSL_HUE && id <= T_HSL_LUM) {
            int k = id - T_HSL_HUE;
            for (int b = 0; b < 8; b++) l.hsl[b * 3 + k] = Math.max(-1, Math.min(1, l.hsl[b * 3 + k] + delta));
            return;
        }
        MaskStack.Comp mc = maskComp(l, id);
        if (mc != null) {   // the layer is a private copy (at()), and so are its shapes
            int ch = (id - T_MASK) % 16;
            if (ch == M_SIZE) { mc.scaleBy(1 + Math.max(t.min, Math.min(t.max, delta))); return; }
            if (ch == M_X) { mc.moveBy(Math.max(t.min, Math.min(t.max, delta)), 0); return; }
            if (ch == M_Y) { mc.moveBy(0, Math.max(t.min, Math.min(t.max, delta))); return; }
            if (ch == M_ROT) { mc.rotateBy(delta); return; }
        }
        float v = get(l, id) + delta;
        if (t.cyclic) {
            float span = t.max - t.min;
            v = t.min + (float) (((v - t.min) % span + span) % span);
        } else v = Math.max(t.min, Math.min(t.max, v));
        if (id == T_OPACITY) l.opacity = v;
        else if (id >= T_BASIC && id < T_BASIC + 10) l.basic[id - T_BASIC] = v;
        else if (id >= T_FX && id < T_FX + 8) l.fxp[id - T_FX] = v;
        else if (mc != null) switch ((id - T_MASK) % 16) {
            case M_AMOUNT: mc.amount = v; break;
            case M_FEATHER: mc.feather = v; break;
            case M_GROW: mc.grow = v; break;
            case M_OUTLINE: mc.outline = v; break;
            case M_REVEAL: mc.reveal = v; break;
            default: break;
        }
    }

    // ================================================================ evaluating
    /**
     * The layer as it is at timeline time t: itself when it has no links (or no song), else a
     * copy with every link's offset added. Song time comes from the project's sync track (none
     * with music sync off, so the layer is left at rest);
     * before the song every link is at rest, after it Step and Drift hold their last value.
     */
    static Layer at(Layer l, VProject p, long t) {
        return at(l, p, t, 0, 0, p.duration());
    }

    /** Shared deterministic evaluator: the target owns its range and can choose its own source. */
    static Layer at(Layer l, VProject p, long t, int owner, long scopeStart, long scopeDur) {
        if (l.links == null || l.links.isEmpty()) return l;
        Layer c = null;
        for (Link k : l.links) {
            Target tg = target(l, k.target);
            if (!k.enabled || tg == null) continue;
            // An explicit range may extend past a cut: its fade must not restart at the cut.
            long end = k.endUs < 0 ? scopeDur : k.endUs;
            long rel = t - scopeStart;
            if (rel < k.startUs || rel >= end || end <= k.startUs) continue;
            AudioControl.Source a = AudioControl.resolve(p, k.audio, owner, t);
            if (a == null || t < a.start) continue;
            Music m = AudioControl.music(p, a);
            if (m == null || (k.src != BEAT && m.fx == null)) continue;
            long st = a.at(t);
            long from = k.local ? Math.max(a.in(), a.at(scopeStart + k.startUs)) : a.in();
            if (a.uri.equals(k.phaseUri) && k.phaseUs >= 0) from = k.phaseUs;
            float amount = amountAt(k, rel);
            float d;
            switch (k.mode) {
                case STEP: d = amount * m.steps(m.code(k.src, k.adapt), k.thresh, k.every, from, st, k.smooth); break;
                case DRIFT: d = amount * (float) m.integral(m.code(k.src, k.adapt), k.thresh, from, st); break;
                default: d = t < a.start + a.dur ? amount * m.follow(m.code(k.src, k.adapt), st, k.smooth, k.thresh) : 0;
            }
            d *= VProject.fade(t, scopeStart + k.startUs, end - k.startUs, (long) (k.fadeIn * 1e6), (long) (k.fadeOut * 1e6));
            if (k.postFader) d *= t < a.start + a.dur ? a.volume(p, t) : 0;
            if (c == null) c = l.copy();
            add(c, tg, d);
        }
        return c == null ? l : c;
    }

    static float amountAt(Link k, long localUs) {
        float[] value = {k.amount};
        k.amountKeys.eval(localUs, value);
        return value[0];
    }

    static boolean anyAdaptive(ArrayList<Layer> ls) {
        for (Layer l : ls) if (l.links != null) for (Link k : l.links) if (k.adapt && canAdapt(k.src)) return true;
        return false;
    }

    static boolean hasLinks(ArrayList<Layer> ls) {
        for (Layer l : ls) if (l.links != null && !l.links.isEmpty()) return true;
        return false;
    }

    // ================================================================ the song, per source
    final AudioFeatures fx;
    final float[] sourceBeats;
    final float[] beats;   // seconds, song time, sorted
    /** Source arrays: 0..N-1 whole-song, N..2N-1 adaptive (see code()). */
    private final float[][] arr = new float[SOURCES.length * 2][];
    static final int ADAPT = SOURCES.length;
    /** Whole-song level vs. relative to the last ADAPT_S seconds; Beat and Brightness have no adaptive form. */
    static boolean canAdapt(int src) { return src != BEAT && src != BRIGHT; }
    int code(int src, boolean adapt) { return adapt && canAdapt(src) ? src + ADAPT : src; }
    static final float ADAPT_S = 1.3f;   // projectM's history is 80 frames (~1.3 s at 60 fps)
    static final float ADAPT_FULL_DB = 8f;   // this far above the recent average reads as full level
    private final HashMap<Long, double[]> cum = new HashMap<Long, double[]>();
    private final HashMap<Long, float[]> events = new HashMap<Long, float[]>();

    Music(AudioFeatures fx, float[] beats) {
        this.fx = fx;
        sourceBeats = beats == null ? VProject.NO_BEATS : beats;
        float[] b = beats == null ? new float[0] : beats.clone();
        java.util.Arrays.sort(b);
        this.beats = b;
    }

    int frames() { return fx != null ? fx.frames : beats.length == 0 ? 1 : (int) ((beats[beats.length - 1] + 2) * AudioFeatures.FPS); }

    /** Per-frame (100 fps) level 0..1 of a source. */
    synchronized float[] arr(int src) {
        if (arr[src] != null) return arr[src];
        float[] a;
        if (fx == null && src != BEAT) return new float[frames()];
        if (src >= ADAPT) {
            int s0 = src - ADAPT;
            switch (s0) {
                case BASS: a = relative(fx.bass, 30); break;
                case LEVEL: a = relative(fx.level, 36); break;
                case MIDS: a = relative(fx.rangeRaw(250, 2000), 50); break;
                case HIGHS: a = relative(fx.rangeRaw(4000, 12000), 50); break;
                case HITS: a = relativeRatio(fx.fluxRaw()); break;
                default: a = arr(s0);
            }
            arr[src] = a;
            return a;
        }
        switch (src) {
            case BEAT: {
                a = new float[frames()];
                int bi = -1;
                for (int f = 0; f < a.length; f++) {
                    double s = f / (double) AudioFeatures.FPS;
                    while (bi + 1 < beats.length && beats[bi + 1] <= s) bi++;
                    a[f] = bi < 0 ? 0 : (float) Math.exp(-(s - beats[bi]) / BEAT_ENV);
                }
                break;
            }
            case BASS: a = fx.bass; break;
            case LEVEL: a = fx.level; break;
            case MIDS: a = fx.range(250, 2000); break;
            case HIGHS: a = fx.range(4000, 12000); break;
            case HITS: a = fx.flux(); break;
            default: a = fx.brightness(); break;
        }
        arr[src] = a;
        return a;
    }

    /**
     * Relative level: how far each frame is above the average of the ADAPT_S seconds before it,
     * in dB (a holds levels linear in dB, rangeDb per unit), full at ADAPT_FULL_DB. This is what
     * makes MilkDrop react to any song: a quiet piano note and a loud chorus hit both stand out
     * against what came just before. Frames at the song's noise floor read 0.
     */
    static float[] relative(float[] a, float rangeDb) {
        int n = a.length, W = Math.max(1, (int) (ADAPT_S * AudioFeatures.FPS));
        double[] c = new double[n + 1];
        for (int f = 0; f < n; f++) c[f + 1] = c[f] + a[f];
        float[] o = new float[n];
        for (int f = 0; f < n; f++) {
            if (a[f] <= 0.0001f) continue;
            int f0 = Math.max(0, f - W);
            double mean = f == 0 ? a[f] : (c[f] - c[f0]) / (f - f0);
            float accent = Math.max(0, (float) ((a[f] - mean) * rangeDb / ADAPT_FULL_DB));
            // Keep a continuous level component. Compressed/steady audio must not be gated
            // out by a percentile floor above its peak; use Hits for transient-only control.
            o[f] = Math.min(1, Math.max(a[f] * 0.65f, accent));
        }
        return o;
    }

    /** Relative form for a linear quantity (flux): ratio to the recent average, full at 4x. */
    static float[] relativeRatio(float[] a) {
        int n = a.length, W = Math.max(1, (int) (ADAPT_S * AudioFeatures.FPS));
        double[] c = new double[n + 1];
        double tot = 0;
        for (int f = 0; f < n; f++) { c[f + 1] = c[f] + a[f]; tot += a[f]; }
        double eps = Math.max(1e-6, tot / Math.max(1, n) * 0.05);   // ignore ratios of near-silence
        float[] o = new float[n];
        for (int f = 1; f < n; f++) {
            int f0 = Math.max(0, f - W);
            double mean = (c[f] - c[f0]) / (f - f0);
            o[f] = Math.max(0, Math.min(1, (float) ((a[f] / (mean + eps) - 1) / 3)));
        }
        return o;
    }

    static float gate(float v, float th) { return th <= 0 ? v : Math.max(0, (v - th) / (1 - th)); }

    /** Follow: the source's level at song time us, held and released over smooth seconds, past the threshold. */
    float follow(int src, long us, float smooth, float th) {
        if (src == BEAT) {
            int i = lastIndex(beats, us / 1e6);
            if (i < 0) return 0;
            return gate((float) Math.exp(-(us / 1e6 - beats[i]) / Math.max(0.02f, smooth)), th);
        }
        float[] a = arr(src);
        int f0 = (int) Math.floor(us * AudioFeatures.FPS / 1e6);
        if (src == BRIGHT) {   // a colour, not a hit: average over the window instead of a peak hold
            int K = Math.max(1, (int) (smooth * AudioFeatures.FPS));
            double s = 0, w = 0;
            for (int k = 0; k < K * 3 && f0 - k >= 0; k++) {
                if (f0 - k >= a.length) continue;
                double wk = Math.exp(-k / (double) K);
                s += a[f0 - k] * wk; w += wk;
            }
            return gate(w > 0 ? (float) (s / w) : 0, th);
        }
        int K = Math.min(400, (int) (AudioFeatures.FPS * smooth * 4) + 1);
        float m = 0;
        for (int k = 0; k < K; k++) {
            int f = f0 - k;
            if (f < 0) break;
            if (f >= a.length) continue;
            m = Math.max(m, a[f] * (float) Math.exp(-k / (AudioFeatures.FPS * Math.max(0.02f, smooth))));
        }
        return gate(m, th);
    }

    /** Drift: seconds of (gated) drive from song time fromUs to us. */
    double integral(int src, float th, long fromUs, long us) {
        double[] c = cumulative(src, th);
        return at(c, us) - at(c, fromUs);
    }

    private static double at(double[] c, long us) {
        double x = us * AudioFeatures.FPS / 1e6;
        if (x <= 0) return 0;
        int i = (int) Math.floor(x);
        if (i >= c.length - 1) return c[c.length - 1];
        return c[i] + (c[i + 1] - c[i]) * (x - i);
    }

    private synchronized double[] cumulative(int src, float th) {
        long key = src * 1000L + Math.round(th * 100);   // src is a code: adaptive sources get their own entries
        double[] c = cum.get(key);
        if (c != null) return c;
        float[] a = arr(src);
        c = new double[a.length + 1];
        for (int f = 0; f < a.length; f++) c[f + 1] = c[f] + gate(a[f], th) / AudioFeatures.FPS;
        cum.put(key, c);
        return c;
    }

    /**
     * Step: how many steps have happened between song times fromUs and us (one per `every` hits),
     * the last one gliding in over glide seconds. Hits are the beats, or for another source the
     * moments it rises through the threshold (at least 0.1 s apart).
     */
    float steps(int src, float th, int every, long fromUs, long us, float glide) {
        float[] ev = events(src, th);
        double s = us / 1e6, s0 = fromUs / 1e6;
        int first = lastIndex(ev, s0 - 1e-9) + 1;   // events at or after the song's in point
        int last = lastIndex(ev, s);
        int n = last - first + 1;
        if (n <= 0) return 0;
        every = Math.max(1, every);
        int steps = n / every;
        if (steps == 0) return 0;
        int stepEvent = first + steps * every - 1;   // the event that made the latest step
        float g = glide <= 0.02f ? 1 : (float) Math.min(1, (s - ev[stepEvent]) / glide);
        // smoothstep glide, so a hue doesn't lurch at the start or end of a move
        g = g * g * (3 - 2 * g);
        return steps - 1 + g;
    }

    synchronized float[] events(int src, float th) {
        if (src == BEAT) return beats;
        long key = src * 1000L + Math.round(th * 100);
        float[] e = events.get(key);
        if (e != null) return e;
        float[] a = arr(src);
        float t = Math.max(0.05f, th <= 0 ? 0.5f : th);   // a hit = rising through the threshold (default half way)
        float rearm = t * 0.8f;                            // and falling back below 80 % of it before the next
        ArrayList<Float> out = new ArrayList<Float>();
        boolean armed = true;
        int lastF = -1000;
        for (int f = 0; f < a.length; f++) {
            if (armed && a[f] >= t && f - lastF >= 10) {
                out.add(f / (float) AudioFeatures.FPS);
                lastF = f;
                armed = false;
            } else if (!armed && a[f] < rearm) armed = true;
        }
        e = new float[out.size()];
        for (int i = 0; i < e.length; i++) e[i] = out.get(i);
        events.put(key, e);
        return e;
    }

    /** Index of the last element <= x, or -1. */
    static int lastIndex(float[] a, double x) {
        int lo = 0, hi = a.length - 1, at = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= x) { at = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return at;
    }

    // ================================================================ ready-made reactive grades
    /** A preset: a layer to add (type, effect id, base settings) and its links. */
    static final class Preset {
        final String name, blurb;
        final int type, fx;
        final float[][] params;   // {index, value} for basic[] (BASIC) or fxp[] (EFFECT); index -1 = layer opacity
        final Link[] links;

        Preset(String name, String blurb, int type, int fx, float[][] params, Link... links) {
            this.name = name; this.blurb = blurb; this.type = type; this.fx = fx; this.params = params; this.links = links;
        }
    }

    static Link link(int target, int src, int mode, float amount, float smooth, float thresh, int every) {
        Link l = new Link();
        l.target = target; l.src = src; l.mode = mode; l.amount = amount; l.smooth = smooth; l.thresh = thresh; l.every = every;
        return l;
    }

    static final Preset[] PRESETS = {
            new Preset("Color cycle on the beat", "The whole picture turns 45° around the colour wheel on every beat",
                    Layer.EFFECT, Fx.HUESHIFT, new float[0][], link(T_FX, BEAT, STEP, 45, 0.12f, 0, 1)),
            new Preset("Color jump every bar", "A new colour scheme every 4 beats, with a quick slide between them",
                    Layer.EFFECT, Fx.HUESHIFT, new float[0][], link(T_FX, BEAT, STEP, 90, 0.25f, 0, 4)),
            new Preset("Bass hue swing", "Kicks and bass swing the hues; they settle back between hits",
                    Layer.EFFECT, Fx.HUESHIFT, new float[0][], link(T_FX, BASS, FOLLOW, 70, 0.2f, 0.25f, 1)),
            new Preset("Color from the sound", "Dark, bassy passages lean one way round the wheel, bright ones the other",
                    Layer.EFFECT, Fx.HUESHIFT, new float[0][], link(T_FX, BRIGHT, FOLLOW, 180, 0.5f, 0, 1)),
            new Preset("Hue drift with energy", "Colours keep turning: fast when the track is loud, slow when it's quiet",
                    Layer.EFFECT, Fx.HUESHIFT, new float[0][], link(T_FX, LEVEL, DRIFT, 30, 0.15f, 0.2f, 1)),
            new Preset("Kick flash", "Exposure punches up on every beat",
                    Layer.BASIC, 0, new float[0][], link(T_BASIC + 2, BEAT, FOLLOW, 0.7f, 0.15f, 0, 1)),
            new Preset("Bass pump", "Brighter, punchier and more saturated with the bass",
                    Layer.BASIC, 0, new float[0][], link(T_BASIC + 2, BASS, FOLLOW, 0.35f, 0.18f, 0.2f, 1),
                    link(T_BASIC + 3, BASS, FOLLOW, 0.25f, 0.18f, 0.2f, 1), link(T_BASIC + 9, BASS, FOLLOW, 0.35f, 0.18f, 0.2f, 1)),
            new Preset("Warm on the drop", "The picture warms up with the bass and cools in quiet parts",
                    Layer.BASIC, 0, new float[][]{{0, -0.15f}}, link(T_BASIC, BASS, FOLLOW, 0.5f, 0.6f, 0.3f, 1)),
            new Preset("Hi-hat shimmer", "Highlights bloom with the hats and cymbals",
                    Layer.EFFECT, Fx.GLOW, new float[][]{{2, 0.1f}}, link(T_FX + 2, HIGHS, FOLLOW, 1.2f, 0.12f, 0.3f, 1)),
            new Preset("Hit RGB split", "Red and blue fringes tear out on drum hits",
                    Layer.EFFECT, Fx.CHROMA, new float[][]{{0, 0}}, link(T_FX, HITS, FOLLOW, 0.008f, 0.1f, 0.35f, 1)),
            new Preset("Breathing vignette", "The edges darken with the track's loudness",
                    Layer.EFFECT, Fx.VIGNETTE, new float[][]{{0, -0.15f}}, link(T_FX, LEVEL, FOLLOW, -0.6f, 0.3f, 0.2f, 1)),
            new Preset("Snare pixel glitch", "The picture breaks into blocks on snares and stabs, clean in between",
                    Layer.EFFECT, Fx.PIXELATE, new float[][]{{0, 0.03f}, {-1, 0}}, link(T_OPACITY, MIDS, FOLLOW, 1, 0.08f, 0.6f, 1)),
            new Preset("Bass zoom blur", "Radial streaks burst outwards with the kick",
                    Layer.EFFECT, Fx.ZOOM, new float[][]{{0, 0}}, link(T_FX, BASS, FOLLOW, 0.25f, 0.15f, 0.35f, 1)),
            new Preset("Spinning kaleidoscope", "Kaleidoscope that turns with the music's energy",
                    Layer.EFFECT, Fx.KALEIDO, new float[0][], link(T_FX + 1, LEVEL, DRIFT, 45, 0.15f, 0.15f, 1)),
            new Preset("Bass twirl", "The centre twists with the bass and springs back",
                    Layer.EFFECT, Fx.TWIRL, new float[][]{{0, 0}}, link(T_FX, BASS, FOLLOW, 160, 0.2f, 0.3f, 1)),
            new Preset("Ripple flow", "Ripples travel faster when the track gets busy",
                    Layer.EFFECT, Fx.WAVE, new float[][]{{0, 0.006f}}, link(T_FX + 2, LEVEL, DRIFT, 360, 0.15f, 0.1f, 1),
                    link(T_FX, HITS, FOLLOW, 0.02f, 0.15f, 0.4f, 1)),
    };
}
