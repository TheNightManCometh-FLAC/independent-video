package com.christopher.strata;

/**
 * 3.15: the visualizer overhaul. Eleven styles (3.14's five plus Mountain, Circle, LED meter,
 * Ridges, Waterfall and Shockwave), three-stop colour with five colour modes, a frequency range,
 * peak caps, mirroring, bass bounce and smoothing, and presets that set all of it at once.
 *
 * The shader (viz.frag) reads one 512 x 36 data texture per frame, built here in pure Java from
 * the song analysis: bars now, the waveform, falling peak caps, 32 rows of past spectra (ridges,
 * waterfall) and a level history (shockwave). Everything is a function of song time, so
 * scrubbing, preview and export give identical frames.
 */
final class Visuals {
    private Visuals() {}

    static final int ROWS = 36, W = 512;
    static final String[] COLOR_MODES = {"Classic", "By height", "Across", "Rainbow", "Loudness"};
    static final String[] STYLE_INFO = {
            "Spectrum bars from the bottom",
            "Bars mirrored about the middle",
            "Bars around a circle, lows at the top",
            "Oscilloscope: the waveform itself",
            "A ring that breathes with the bass, edged with the waveform",
            "A smooth filled spectrum with a bright edge",
            "A smooth spectrum around a circle (the NCS / Trap Nation look)",
            "Hi-fi meter: columns of lit cells with peak hold",
            "Unknown Pleasures: the last seconds of spectrum stacked in lines",
            "A scrolling spectrogram: time runs down, loud is hot",
            "Kicks become rings travelling outward from a pulsing centre"};

    /** Round styles start in a square box. */
    static boolean round(int type) {
        return type == VProject.Viz.RADIAL || type == VProject.Viz.RING || type == VProject.Viz.CIRCLE || type == VProject.Viz.SHOCK;
    }

    static boolean usesBars(int t) { return t != VProject.Viz.WAVE && t != VProject.Viz.RING && t != VProject.Viz.SHOCK; }
    static boolean usesHistory(int t) { return t == VProject.Viz.RIDGES || t == VProject.Viz.WATERFALL; }
    static boolean horizontal(int t) {
        return t == VProject.Viz.BARS || t == VProject.Viz.MIRROR || t == VProject.Viz.MOUNTAIN || t == VProject.Viz.LED
                || t == VProject.Viz.RIDGES || t == VProject.Viz.WATERFALL;
    }

    // ---------------------------------------------------------------- frequency ranges
    static final String[] RANGES = {"Full", "Bass", "Low end", "Vocals", "Highs"};
    static final float[][] RANGE_HZ = {{30, 12000}, {30, 160}, {30, 500}, {250, 4000}, {3000, 12000}};

    static int bandOfHz(float hz) {
        double b = AudioFeatures.BANDS * Math.log(hz / 30.0) / Math.log(12000 / 30.0);
        return (int) Math.max(0, Math.min(AudioFeatures.BANDS - 1, Math.round(b)));
    }

    static float hzOfBand(int b) { return (float) (30 * Math.pow(12000 / 30.0, b / (double) AudioFeatures.BANDS)); }

    static int rangeIndex(VProject.Viz z) {
        for (int i = 0; i < RANGES.length; i++) if (bandOfHz(RANGE_HZ[i][0]) == z.lo && bandOfHz(RANGE_HZ[i][1]) == z.hi) return i;
        return -1;
    }

    static void setRange(VProject.Viz z, int i) { z.lo = bandOfHz(RANGE_HZ[i][0]); z.hi = bandOfHz(RANGE_HZ[i][1]); }

    // ---------------------------------------------------------------- palettes (2 and 3 stops)
    static final String[] PALETTE_NAMES = {"Amber", "Ice", "Magenta", "Acid", "White", "Fire",
            "Sunset", "Vapor", "Toxic", "Ice fire", "Blood", "Gold", "Ocean", "Hi-fi"};
    static final int[][] PALETTES = {
            {0xffe9a23b, 0xfffff3d6}, {0xff2fb4ff, 0xffe6f7ff}, {0xffff3fa4, 0xffffd4ee},
            {0xff7cff6b, 0xffeaffe4}, {0xffffffff, 0xffffffff}, {0xffff5a36, 0xffffd65a},
            {0xff3b1d8f, 0xffff3d7f, 0xffffd36b}, {0xffff71ce, 0xff01cdfe, 0xffb967ff}, {0xff1b3a12, 0xff9dff00, 0xfff6ffb0},
            {0xff1e6bff, 0xffffffff, 0xffff3b1e}, {0xff2a0000, 0xffc4001a, 0xffff8a8a}, {0xff4a2c00, 0xffd9a400, 0xfffff2b0},
            {0xff002a4a, 0xff00a6c7, 0xffd8fff7}, {0xff22e04a, 0xffffd400, 0xffff2a14}};

    static boolean paletteIs(VProject.Viz z, int i) {
        int[] p = PALETTES[i];
        if (p.length == 2) return z.stops == 2 && z.c1 == p[0] && z.c2 == p[1];
        return z.stops == 3 && z.c1 == p[0] && z.c2 == p[1] && z.c3 == p[2];
    }

    static void setPalette(VProject.Viz z, int i) {
        int[] p = PALETTES[i];
        z.c1 = p[0]; z.c2 = p[1];
        if (p.length == 3) { z.c3 = p[2]; z.stops = 3; } else z.stops = 2;
    }

    // ---------------------------------------------------------------- presets
    static final class Preset {
        final String key, name, info;
        final int type;
        Preset(String key, String name, int type, String info) { this.key = key; this.name = name; this.type = type; this.info = info; }
    }

    static final Preset[] PRESETS = {
            new Preset("neon", "Neon bars", VProject.Viz.BARS, "Rounded bars, magenta to cyan, peak caps"),
            new Preset("monster", "Clean bars", VProject.Viz.BARS, "Flat white bars, tight gaps"),
            new Preset("ncs", "Trap circle", VProject.Viz.CIRCLE, "Smooth circle spectrum that bounces with the kick"),
            new Preset("sunburst", "Sunburst", VProject.Viz.RADIAL, "Rainbow radial bars, turning"),
            new Preset("pleasures", "Unknown Pleasures", VProject.Viz.RIDGES, "White ridgelines on black"),
            new Preset("heat", "Heat waterfall", VProject.Viz.WATERFALL, "Spectrogram in purple, red and gold"),
            new Preset("hifi", "Hi-fi meter", VProject.Viz.LED, "Green, amber, red cells with peak hold"),
            new Preset("scope", "Phosphor scope", VProject.Viz.WAVE, "Green oscilloscope with a soft glow"),
            new Preset("liquid", "Liquid mountain", VProject.Viz.MOUNTAIN, "Mirrored smooth spectrum in ocean blues"),
            new Preset("shock", "Bass shockwave", VProject.Viz.SHOCK, "Rings fired by every kick"),
            new Preset("vapor", "Vapor tunnel", VProject.Viz.RING, "Vaporwave ring with a zooming tunnel of echoes"),
            new Preset("firemirror", "Fire mirror", VProject.Viz.MIRROR, "Mirrored bars in flame colours"),
            new Preset("rainbowtunnel", "Rainbow spiral", VProject.Viz.RADIAL, "Radial bars spiralling back in rainbow echoes"),
            new Preset("ghost", "Ghost scope", VProject.Viz.WAVE, "Waveform leaving drifting ghosts")};

    /** Sets a preset's look on z (style, colour, shape, echoes); the box and timing stay. */
    static void apply(VProject.Viz z, String key) {
        VProject.Viz d = new VProject.Viz();   // start from the defaults so presets don't inherit leftovers
        d.auto = true;
        switch (key) {
            case "neon": d.type = VProject.Viz.BARS; d.c1 = 0xffff2bd6; d.c2 = 0xff28f0ff; d.colorMode = 1; d.glow = 0.85f; d.caps = true; d.round = 1; d.bars = 40; d.gap = 0.35f; break;
            case "monster": d.type = VProject.Viz.BARS; d.c1 = d.c2 = 0xffffffff; d.round = 0; d.bars = 63; d.gap = 0.22f; d.glow = 0.15f; d.sens = 1.1f; d.release = 0.1f; break;
            case "ncs": d.type = VProject.Viz.CIRCLE; d.c1 = 0xffffffff; d.c2 = 0xffff7ad9; d.inner = 0.45f; d.fill = 0.25f; d.bounce = 0.6f; d.glow = 0.7f; d.thick = 0.004f; d.smooth = 0.3f; d.bars = 48; break;
            case "sunburst": d.type = VProject.Viz.RADIAL; d.colorMode = 3; d.hueSpeed = 40; d.inner = 0.3f; d.bars = 64; d.gap = 0.4f; d.glow = 0.6f; break;
            case "pleasures": d.type = VProject.Viz.RIDGES; d.c1 = d.c2 = 0xffffffff; d.fill = 1; d.thick = 0.0025f; d.glow = 0.15f; d.ridges = 28; d.hist = 2.4f; d.amp = 1; d.bars = 48; d.smooth = 0.25f; break;
            case "heat": d.type = VProject.Viz.WATERFALL; d.colorMode = 4; d.stops = 3; d.c1 = 0xff1a0033; d.c2 = 0xffff2a3d; d.c3 = 0xffffef9a; d.hist = 3; d.bars = 64; d.glow = 0.4f; break;
            case "hifi": d.type = VProject.Viz.LED; d.colorMode = 1; setPalette(d, 13); d.caps = true; d.bars = 24; d.segs = 16; d.gap = 0.2f; d.round = 0.3f; d.glow = 0.3f; break;
            case "scope": d.type = VProject.Viz.WAVE; d.c1 = 0xff39ff88; d.c2 = 0xffc8ffe0; d.glow = 0.9f; d.thick = 0.004f; break;
            case "liquid": d.type = VProject.Viz.MOUNTAIN; d.mirrorX = true; setPalette(d, 12); d.colorMode = 1; d.fill = 0.6f; d.smooth = 0.35f; d.bars = 48; d.glow = 0.5f; break;
            case "shock": d.type = VProject.Viz.SHOCK; d.c1 = 0xff2f8cff; d.c2 = 0xffeaf4ff; d.inner = 0.15f; d.fill = 0.9f; d.hist = 1.6f; d.glow = 0.7f; break;
            case "vapor": d.type = VProject.Viz.RING; setPalette(d, 7); d.colorMode = 2; d.glow = 1; d.setTrail(1); break;
            case "firemirror": d.type = VProject.Viz.MIRROR; setPalette(d, 5); d.round = 1; d.bars = 48; d.glow = 0.6f; break;
            case "rainbowtunnel": d.type = VProject.Viz.RADIAL; d.colorMode = 3; d.inner = 0.35f; d.bars = 48; d.setTrail(2); break;
            case "ghost": d.type = VProject.Viz.WAVE; d.c1 = 0xffb967ff; d.c2 = 0xffffffff; d.glow = 0.6f; d.setTrail(3); d.tHue = 60; break;
            default: return;
        }
        VProject.Viz keep = z.copy();
        copyLook(d, z);
        z.echoes = keep.echoes;
        if (d.trailLen <= 0) { z.trailLen = 0; z.tZoom = z.tSpin = z.tPush = z.tHue = 0; }
    }

    /** Copies everything that makes the look (not the box, timing or keyframes, which live on the item). */
    static void copyLook(VProject.Viz from, VProject.Viz to) {
        VProject.Viz c = from.copy();
        to.type = c.type; to.bars = c.bars; to.gap = c.gap; to.sens = c.sens; to.release = c.release; to.glow = c.glow;
        to.thick = c.thick; to.inner = c.inner; to.span = c.span; to.c1 = c.c1; to.c2 = c.c2; to.auto = c.auto;
        to.trailLen = c.trailLen; to.tZoom = c.tZoom; to.tSpin = c.tSpin; to.tPush = c.tPush; to.tHue = c.tHue; to.echoGlow = c.echoGlow;
        to.c3 = c.c3; to.stops = c.stops; to.colorMode = c.colorMode; to.hueSpeed = c.hueSpeed; to.lo = c.lo; to.hi = c.hi;
        to.round = c.round; to.caps = c.caps; to.mirrorX = c.mirrorX; to.flip = c.flip; to.fill = c.fill; to.bounce = c.bounce;
        to.amp = c.amp; to.smooth = c.smooth; to.segs = c.segs; to.ridges = c.ridges; to.hist = c.hist;
    }

    static String presetOf(VProject.Viz z) {
        for (Preset p : PRESETS) {
            VProject.Viz t = z.copy();
            apply(t, p.key);
            if (sameLook(t, z)) return p.key;
        }
        return null;
    }

    private static boolean sameLook(VProject.Viz a, VProject.Viz b) {
        return a.type == b.type && a.c1 == b.c1 && a.c2 == b.c2 && a.stops == b.stops && (a.stops < 3 || a.c3 == b.c3) && a.colorMode == b.colorMode
                && a.bars == b.bars && a.round == b.round && a.caps == b.caps && a.mirrorX == b.mirrorX && a.fill == b.fill
                && a.trailLen == b.trailLen && a.inner == b.inner && a.glow == b.glow;
    }

    // ---------------------------------------------------------------- versioning and repair
    /** Uses anything a 3.14 app can't show (the project is then written as format 18). */
    static boolean isNew(VProject.Viz z) {
        return z.type > VProject.Viz.RING || z.stops != 2 || z.colorMode != 0 || z.lo != 0 || z.hi != 63 || z.round != 0.5f || z.caps
                || z.mirrorX || z.flip || z.bounce != 0 || z.smooth != 0 || z.fill != 0.6f || z.amp != 1 || z.segs != 16 || z.ridges != 24 || z.hist != 2f
                || z.c3 != 0xffff4d80 || z.hueSpeed != 30;
    }

    static boolean anyNew(VProject p) {
        for (VProject.TextItem t : p.texts) if (t.viz != null && isNew(t.viz)) return true;
        return false;
    }

    static void sanitize(VProject.Viz z) {
        z.type = Math.max(0, Math.min(VProject.Viz.TYPES.length - 1, z.type));
        z.stops = z.stops == 3 ? 3 : 2;
        z.colorMode = Math.max(0, Math.min(COLOR_MODES.length - 1, z.colorMode));
        z.lo = Math.max(0, Math.min(62, z.lo));
        z.hi = Math.max(z.lo + 1, Math.min(63, z.hi));
        z.round = fin(z.round, 0.5f, 0, 1);
        z.fill = fin(z.fill, 0.6f, 0, 1);
        z.bounce = fin(z.bounce, 0, 0, 2);
        z.amp = fin(z.amp, 1, 0.1f, 2);
        z.smooth = fin(z.smooth, 0, 0, 1);
        z.hist = fin(z.hist, 2, 0.3f, 8);
        z.hueSpeed = fin(z.hueSpeed, 30, -360, 360);
        z.segs = Math.max(3, Math.min(40, z.segs));
        z.ridges = Math.max(4, Math.min(32, z.ridges));
        z.bars = Math.max(4, Math.min(64, z.bars));
    }

    private static float fin(float v, float d, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = d;
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- the data texture
    /** Scratch for one row computation (not thread-safe: one per compositor). */
    static final class Work {
        final float[] bands = new float[AudioFeatures.BANDS], bars = new float[64], tmp = new float[64], tmp2 = new float[64], wave = new float[512];
    }

    /** Bar levels (0..1) at song time st into out[n], from the bands in the visualizer's range. */
    static void bars(AudioFeatures fx, long st, VProject.Viz z, float gain, int n, Work w, float[] out) {
        if (fx == null || st < 0) { java.util.Arrays.fill(out, 0, n, 0); return; }
        fx.bandsAt(st, z.release, w.bands);
        int lo = Math.max(0, Math.min(62, z.lo)), hi = Math.max(lo + 1, Math.min(63, z.hi)) + 1;
        int span = hi - lo;
        for (int i = 0; i < n; i++) {
            float v;
            if (n <= span) {
                int a = lo + i * span / n, b = Math.max(a + 1, lo + (i + 1) * span / n);
                v = 0;
                for (int j = a; j < b; j++) v = Math.max(v, w.bands[j]);
            } else {   // more bars than bands: interpolate (a narrow range spread over many bars)
                float x = lo + (i + 0.5f) * span / n - 0.5f;
                int a = (int) Math.floor(x);
                float t = x - a;
                float va = w.bands[Math.max(lo, Math.min(hi - 1, a))], vb = w.bands[Math.max(lo, Math.min(hi - 1, a + 1))];
                v = va + (vb - va) * t;
            }
            v += gain;   // auto level: a gain in dB (bands are log levels)
            out[i] = Math.max(0, Math.min(1, (v - 0.12f) / 0.88f * z.sens));
        }
        int r = Math.round(z.smooth * 3);
        for (int pass = 0; pass < r; pass++) {   // spatial smoothing: [1 2 1] passes
            for (int i = 0; i < n; i++) w.tmp[i] = (out[Math.max(0, i - 1)] + 2 * out[i] + out[Math.min(n - 1, i + 1)]) / 4;
            System.arraycopy(w.tmp, 0, out, 0, n);
        }
    }

    /** Peak caps: each bar's highest level in the last ~0.8 s, falling under gravity. */
    static final float CAP_G = 2.6f;   // units per s^2: a cap at the top falls in ~0.6 s

    /**
     * The whole data texture (W x ROWS bytes, row-major) at song time st. gain / wgain are the
     * auto-level gains (0 / 1 when off).
     */
    static void rows(AudioFeatures fx, long st, VProject.Viz z, float gain, float wgain, Work w, byte[] out) {
        java.util.Arrays.fill(out, (byte) 0);
        int n = Math.max(4, Math.min(64, z.bars));
        boolean live = fx != null && st >= 0;
        // row 0: bars now
        bars(fx, st, z, gain, n, w, w.bars);
        for (int i = 0; i < n; i++) out[i] = b(w.bars[i]);
        // row 1: waveform
        if (live) fx.waveAt(st, z.span, w.wave); else java.util.Arrays.fill(w.wave, 0);
        for (int i = 0; i < W; i++) out[W + i] = b(w.wave[i] * wgain * z.sens * 0.5f + 0.5f);
        // row 2: peak caps
        if (z.caps && live && (z.type == VProject.Viz.BARS || z.type == VProject.Viz.LED)) {
            float[] cap = new float[n];
            System.arraycopy(w.bars, 0, cap, 0, n);
            float dt = 1 / 30f;
            for (int k = 1; k <= 24; k++) {
                long tk = st - (long) (k * dt * 1e6);
                if (tk < 0) break;
                bars(fx, tk, z, gain, n, w, w.tmp2);
                float fall = CAP_G * (k * dt) * (k * dt);
                for (int i = 0; i < n; i++) cap[i] = Math.max(cap[i], w.tmp2[i] - fall);
            }
            for (int i = 0; i < n; i++) out[2 * W + i] = b(cap[i]);
        }
        // rows 3..34: the past (ridges, waterfall)
        if (usesHistory(z.type)) {
            float dt = z.hist / 31f;
            for (int k = 0; k < 32; k++) {
                long tk = st - (long) (k * dt * 1e6);
                bars(fx, tk, z, gain, n, w, w.tmp2);
                for (int i = 0; i < n; i++) out[(3 + k) * W + i] = b(w.tmp2[i]);
            }
        }
        // row 35: level over the last z.hist seconds (shockwave): the kick drives it
        if (z.type == VProject.Viz.SHOCK && live) {
            for (int i = 0; i < W; i++) {
                long tk = st - (long) (i / (float) (W - 1) * z.hist * 1e6);
                float v = tk < 0 ? 0 : fx.bassAt(tk, 0.06f) * z.sens;
                out[35 * W + i] = b(v);
            }
        }
    }

    private static byte b(float v) { return (byte) Math.round(Math.max(0, Math.min(1, v)) * 255); }
}
