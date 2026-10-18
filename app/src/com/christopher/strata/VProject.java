package com.christopher.strata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * A video project. Times are microseconds. The main track (clips) is magnetic: clip i+1 starts
 * where clip i ends, minus the transition overlap. Text and music items sit at absolute times.
 * Media is referenced by content URI (persisted read permission), not copied.
 */
final class VProject {
    static final int VIDEO = 0, IMAGE = 1;
    /** Stored in projects by index: only append (3.14 added 5..18; shader branches in transition.frag). */
    static final String[] TRANS = {"Cut", "Dissolve", "Dip to black", "Dip to white", "Wipe \u2192",
            "Wipe \u2190", "Wipe \u2193", "Wipe \u2191", "Push \u2190", "Push \u2192", "Push \u2191", "Push \u2193",
            "Zoom in", "Zoom out", "Spin", "Whip", "Flash", "Luma fade", "Iris"};
    static final String[] TRANS_GROUPS = {"Basic", "Wipe", "Push", "Motion", "Light"};
    static final int[][] TRANS_GROUP_TYPES = {{0, 1, 2, 3}, {4, 5, 6, 7}, {8, 9, 10, 11}, {12, 13, 14, 15}, {16, 17, 18}};
    /** The first transition type a 3.13 app can't draw. */
    static final int TRANS_314 = 5;

    boolean usesNewTransitions() {
        if (defTrans >= TRANS_314) return true;
        for (Clip c : clips) if (c.trans >= TRANS_314) return true;
        return false;
    }
    static final int[][] RES = {{1280, 720}, {1920, 1080}, {3840, 2160}};
    static final String[] RES_NAMES = {"720p", "1080p", "4K"};
    static final float[] ASPECTS = {16f / 9f, 9f / 16f, 1f, 4f / 5f};
    static final String[] ASPECT_NAMES = {"16:9", "9:16", "1:1", "4:5"};
    static final int[] FPS = {24, 25, 30, 60};

    static final class Clip {
        int id, kind;
        String uri, name;
        int srcW, srcH, rotation;   // displayed size (rotation applied)
        long srcDur;                // us
        boolean hasAudio;
        long in, out;               // source us
        float speed = 1f, volume = 1f;
        boolean mute;
        /** 3.15: constant speed changes the pitch too (tape), instead of keeping it (time-stretch). */
        boolean tape;
        /** 3.15: equalizer on this clip's sound (null = none). */
        AudioEq eq;
        boolean timingLocked;
        int fit = 1;                // 0 fit, 1 fill
        float scale = 1f, panX, panY, rot;
        int trans;                  // transition into the next clip
        long transDur = 500000;
        float opacity = 1f;
        Keys keys = new Keys();     // {scale, panX, panY, rot, opacity}, times relative to the clip's start
        int reactSrc = 2;           // feature: 0 beat, 1 bass, 2 level
        int reactAudio = AudioControl.SELF;
        boolean reactPost;
        boolean reactAdapt;  // Bass/Level measured against the last ~1.3 s (Music.relative)
        float pulse, shake, flash;  // zoom punch, camera shake, brightness flash amounts
        ArrayList<Layer> grade = new ArrayList<Layer>();
        transient android.graphics.Bitmap still;   // image clips
        /**
         * Speed curve (main-track video clips); null = the constant speed. The ramp belongs to
         * the footage: its 0..1 covers source time curveIn..curveOut, and in/out only window it.
         * Trimming crops the ramp (trimming back out restores it), a split leaves both halves on
         * the same ramp, and outside its range the curve holds its end speeds. A slip moves the
         * ramp with the clip (same length, same shape, different footage under it).
         */
        SpeedCurve curve;
        long curveIn, curveOut;

        /** Puts a new curve on exactly this clip's footage (presets, Custom). */
        void setCurve(SpeedCurve c) { curve = c; curveIn = in; curveOut = out; }

        private double cspan() { return Math.max(1, curveOut - curveIn); }
        /** Position on the curve (0..1 over its own range; outside = beyond its ends) of source time src. */
        double curveU(long src) { return cu(src); }
        /** Source time of curve position u. */
        long curveSrc(double u) { return curveIn + Math.round(u * cspan()); }
        private double cu(long src) { return (src - curveIn) / cspan(); }
        /** Freeze frame: an IMAGE clip showing the frame of the video uri at this source time (-1 = a photo). */
        long frameUs = -1;

        long dur() {
            if (curve == null) return Math.max(1, (long) ((out - in) / speed));
            return Math.max(1, Math.round((curve.integ(cu(out)) - curve.integ(cu(in))) * cspan()));
        }

        /** Source time shown local us after the clip starts (also before / after the clip). */
        long srcAt(long local) {
            if (curve == null) return in + (long) (local * speed);
            double I = curve.integ(cu(in)) + local / cspan();
            return curveIn + Math.round(curve.uAtIntegral(I) * cspan());
        }

        /** Local time (from the clip's start) at which source time src is shown. */
        long localAt(long src) {
            if (curve == null) return Math.round((src - in) / (double) speed);
            return Math.round((curve.integ(cu(src)) - curve.integ(cu(in))) * cspan());
        }

        /** Playback speed at source time src (the curve's, or the constant one). */
        float speedAtSrc(long src) {
            if (curve == null) return speed;
            return curve.speedAt(cu(src));
        }

        /** Footage per playback time on average (= speed without a curve). */
        double avgSpeed() { return (out - in) / (double) dur(); }

        Clip copy() {
            Clip c = new Clip();
            c.id = id; c.kind = kind; c.uri = uri; c.name = name; c.srcW = srcW; c.srcH = srcH; c.rotation = rotation;
            c.srcDur = srcDur; c.hasAudio = hasAudio; c.in = in; c.out = out; c.speed = speed; c.volume = volume;
            c.mute = mute; c.fit = fit; c.scale = scale; c.panX = panX; c.panY = panY; c.rot = rot;
            c.timingLocked=timingLocked;
            c.trans = trans; c.transDur = transDur; c.still = still;
            c.curve = curve == null ? null : curve.copy(); c.curveIn = curveIn; c.curveOut = curveOut; c.frameUs = frameUs;
            c.tape = tape; c.eq = eq == null ? null : eq.copy();
            c.opacity = opacity; c.keys = keys.copy(); c.reactAudio = reactAudio; c.reactPost = reactPost; c.reactSrc = reactSrc; c.reactAdapt = reactAdapt; c.pulse = pulse; c.shake = shake; c.flash = flash;
            for (Layer l : grade) c.grade.add(l.copy());
            return c;
        }
    }

    /** Music visualizer settings (an overlay item with viz != null). */
    static final class Viz {
        static final String[] TYPES = {"Bars", "Mirror", "Radial", "Wave", "Ring",
                "Mountain", "Circle", "LED meter", "Ridges", "Waterfall", "Shockwave"};   // 3.15: 5..10
        static final int BARS = 0, MIRROR = 1, RADIAL = 2, WAVE = 3, RING = 4, MOUNTAIN = 5, CIRCLE = 6, LED = 7, RIDGES = 8, WATERFALL = 9, SHOCK = 10;
        // ---- 3.15 options (defaults reproduce the 3.14 look)
        /** Third colour stop (used when stops == 3), colour mode (Visuals.COLOR_MODES), rainbow turn speed (deg/s). */
        int c3 = 0xffff4d80, stops = 2, colorMode;
        float hueSpeed = 30;
        /** Frequency range as band indexes 0..63 (30 Hz .. 12 kHz, log spaced). */
        int lo = 0, hi = 63;
        /** Bars: corner roundness (0.5 = 3.14's), falling peak caps, lows in the middle, grow from the top. */
        float round = 0.5f;
        boolean caps, mirrorX, flip;
        /** Mountain / circle / ridges fill, scale with the bass, LED cells, ridge count, peak height, spatial smoothing. */
        float fill = 0.6f, bounce, amp = 1, smooth;
        int segs = 16, ridges = 24;
        /** Ridges / waterfall / shockwave: seconds of history shown. */
        float hist = 2f;
        int type;
        int bars = 32;
        float gap = 0.3f, sens = 1f, release = 0.15f, glow = 0.5f, thick = 0.006f, inner = 0.35f, span = 0.05f;
        int c1 = 0xffe9a23b, c2 = 0xfffff3d6;
        /** Auto level (cava's autosens): quiet passages are brought up to fill the box. */
        boolean auto = true;
        /**
         * Echoes (After Effects' Echo with a transform per echo): `echoes` copies spread over the
         * last trailLen seconds (0 = off). By age: tZoom log zoom per second (+ bigger, - smaller),
         * tSpin degrees per second, tPush extra log zoom per second at full adaptive bass, tHue
         * degrees of hue turn per second. echoGlow: screen the echoes together instead of stacking.
         */
        float trailLen, tZoom, tSpin, tPush, tHue;
        int echoes = 8;
        boolean echoGlow;
        static final String[] TRAILS = {"Off", "Tunnel", "Spiral", "Ghost", "Implode"};
        static final float[][] TRAIL_PRESETS = {   // span s, zoom, spin, push, hue
                {0, 0, 0, 0, 0}, {0.5f, 0.9f, 0, 1.5f, 30}, {0.6f, 0.6f, 90, 1f, 60}, {0.25f, 0, 0, 0, 0}, {0.5f, -0.8f, -40, -1f, 0}};

        /** Intensity of echo e (0 = the current frame): 0.75 for the first, halving every n/4 echoes. */
        float echoIntensity(int e) { return e == 0 ? 1 : (float) (0.75 * Math.pow(0.5, e * 4.0 / Math.max(1, echoes))); }

        int trailStyle() {
            for (int i = 0; i < TRAIL_PRESETS.length; i++) {
                float[] q = TRAIL_PRESETS[i];
                if (i == 0 ? trailLen <= 0 : (trailLen == q[0] && tZoom == q[1] && tSpin == q[2] && tPush == q[3] && tHue == q[4])) return i;
            }
            return -1;   // custom
        }

        void setTrail(int style) {
            float[] q = TRAIL_PRESETS[style];
            trailLen = q[0]; tZoom = q[1]; tSpin = q[2]; tPush = q[3]; tHue = q[4];
        }

        Viz copy() {
            Viz v = new Viz();
            v.auto = auto;
            v.trailLen = trailLen; v.tZoom = tZoom; v.tSpin = tSpin; v.tPush = tPush; v.tHue = tHue;
            v.echoes = echoes; v.echoGlow = echoGlow;
            v.type = type; v.bars = bars; v.gap = gap; v.sens = sens; v.release = release; v.glow = glow;
            v.thick = thick; v.inner = inner; v.span = span; v.c1 = c1; v.c2 = c2;
            v.c3 = c3; v.stops = stops; v.colorMode = colorMode; v.hueSpeed = hueSpeed; v.lo = lo; v.hi = hi;
            v.round = round; v.caps = caps; v.mirrorX = mirrorX; v.flip = flip; v.fill = fill; v.bounce = bounce;
            v.amp = amp; v.smooth = smooth; v.segs = segs; v.ridges = ridges; v.hist = hist;
            return v;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type); o.put("bars", bars); o.put("gap", (double) gap); o.put("sens", (double) sens);
            o.put("release", (double) release); o.put("glow", (double) glow); o.put("thick", (double) thick);
            o.put("inner", (double) inner); o.put("span", (double) span); o.put("c1", c1); o.put("c2", c2);
            if (auto) o.put("auto", true);
            if (Visuals.isNew(this)) {   // 3.15 options, only when they differ from the 3.14 look
                JSONObject n = new JSONObject();
                n.put("c3", c3); n.put("stops", stops); n.put("cm", colorMode); n.put("hue", (double) hueSpeed);
                n.put("lo", lo); n.put("hi", hi); n.put("round", (double) round);
                if (caps) n.put("caps", true);
                if (mirrorX) n.put("mx", true);
                if (flip) n.put("flip", true);
                n.put("fill", (double) fill); n.put("bounce", (double) bounce); n.put("amp", (double) amp); n.put("smooth", (double) smooth);
                n.put("segs", segs); n.put("ridges", ridges); n.put("hist", (double) hist);
                o.put("v315", n);
            }
            if (trailLen > 0) {
                JSONObject r = new JSONObject();
                r.put("len", (double) trailLen); r.put("zoom", (double) tZoom); r.put("spin", (double) tSpin);
                r.put("push", (double) tPush); r.put("hue", (double) tHue); r.put("n", echoes);
                if (echoGlow) r.put("glow", true);
                o.put("echo", r);
            }
            return o;
        }

        static Viz fromJson(JSONObject o) {
            Viz v = new Viz();
            v.type = o.optInt("type"); v.bars = o.optInt("bars", 32); v.gap = (float) o.optDouble("gap", 0.3);
            v.sens = (float) o.optDouble("sens", 1); v.release = (float) o.optDouble("release", 0.15);
            v.glow = (float) o.optDouble("glow", 0.5); v.thick = (float) o.optDouble("thick", 0.006);
            v.inner = (float) o.optDouble("inner", 0.35); v.span = (float) o.optDouble("span", 0.05);
            v.c1 = o.optInt("c1", 0xffe9a23b); v.c2 = o.optInt("c2", 0xfffff3d6);
            v.auto = o.optBoolean("auto", false);   // before 3.4.2 bars used the whole song's level
            JSONObject n = o.optJSONObject("v315");
            if (n != null) {
                v.c3 = n.optInt("c3", v.c3); v.stops = n.optInt("stops", 2) == 3 ? 3 : 2;
                v.colorMode = n.optInt("cm", 0); v.hueSpeed = (float) n.optDouble("hue", 30);
                v.lo = n.optInt("lo", 0); v.hi = n.optInt("hi", 63); v.round = (float) n.optDouble("round", 0.5);
                v.caps = n.optBoolean("caps"); v.mirrorX = n.optBoolean("mx"); v.flip = n.optBoolean("flip");
                v.fill = (float) n.optDouble("fill", 0.6); v.bounce = (float) n.optDouble("bounce", 0);
                v.amp = (float) n.optDouble("amp", 1); v.smooth = (float) n.optDouble("smooth", 0);
                v.segs = n.optInt("segs", 16); v.ridges = n.optInt("ridges", 24); v.hist = (float) n.optDouble("hist", 2);
            }
            Visuals.sanitize(v);
            JSONObject r = o.optJSONObject("echo");
            if (r != null) {
                v.trailLen = (float) r.optDouble("len", 0); v.tZoom = (float) r.optDouble("zoom", 0);
                v.tSpin = (float) r.optDouble("spin", 0); v.tPush = (float) r.optDouble("push", 0); v.tHue = (float) r.optDouble("hue", 0);
                v.echoes = r.optInt("n", 8); v.echoGlow = r.optBoolean("glow", false);
            }
            return v;
        }
    }

    /**
     * Connection to the main track (titles, B-roll and sound effects that should follow the
     * picture). The item is pinned to one source frame of a clip: src (source us) of clip `clip`,
     * plus `off` timeline us after that point (non-zero only for items past the end of the main
     * track, which hang off the last clip's end). Trims, speed changes, reorders and deletions on
     * the main track move the item with its frame; trimming the frame itself away holds the item
     * at the nearest edge of what is left.
     */
    static final class Anchor {
        boolean on;      // the user wants this item connected
        int clip;        // 0 = not resolved yet (pin to whatever is under the item)
        long src, off;

        Anchor copy() { Anchor a = new Anchor(); a.on = on; a.clip = clip; a.src = src; a.off = off; return a; }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("clip", clip); o.put("src", src); if (off != 0) o.put("off", off);
            return o;
        }

        static Anchor fromJson(JSONObject o) {
            Anchor a = new Anchor();
            if (o == null) return a;
            a.on = true; a.clip = o.optInt("clip"); a.src = o.optLong("src"); a.off = o.optLong("off");
            return a;
        }
    }

    /** An overlay on the overlay track: a title (TEXT layer) or a visualizer (viz != null). */
    static final class TextItem {
        int id;
        boolean timingLocked;
        Layer layer;          // placed in output px; for a visualizer srcW x srcH is its box
        long start, dur = 3000000, fadeIn = 300000, fadeOut = 300000;
        long fadeOffset, fadeSpan = -1;
        Keys keys = new Keys();   // {cx, cy, scale, rot, opacity}, times relative to start
        int reactSrc = 2;
        int reactAudio = AudioControl.GUIDE;
        boolean reactPost;
        boolean reactAdapt;
        float pulse, shake, flash;
        /** 0 keep main, 1 replace main during this item, 2 mix both. */
        int audioMode;
        Viz viz;

        /**
         * Media overlay (picture-in-picture, B-roll, logo): the source as a clip (uri, kind, size,
         * in point, speed, volume/mute). Placement, keyframes, blend and fades come from the item
         * and its layer like any overlay. Null for text and visualizers.
         */
        Clip media;
        /** Part of the caption track (imported from / exported to SRT, styled together). */
        boolean caption;
        Anchor link = new Anchor();

        TextItem copy() {
            TextItem t = new TextItem();
            t.link = link.copy();
            t.timingLocked=timingLocked;
            t.caption = caption;
            t.media = media == null ? null : media.copy();
            t.id = id; t.layer = layer.copy(); t.start = start; t.dur = dur; t.fadeIn = fadeIn; t.fadeOut = fadeOut; t.fadeOffset = fadeOffset; t.fadeSpan = fadeSpan;
            t.keys = keys.copy(); t.reactAudio = reactAudio; t.reactPost = reactPost; t.audioMode = audioMode; t.flash = flash; t.reactSrc = reactSrc; t.reactAdapt = reactAdapt; t.pulse = pulse; t.shake = shake;
            t.viz = viz == null ? null : viz.copy();
            return t;
        }
    }

    static final class AudioItem {
        int id;
        boolean timingLocked;
        String uri, name;
        long srcDur, start, in, out;
        float volume = 1f;
        long fadeIn, fadeOut;
        long fadeOffset, fadeSpan = -1;
        /** Volume automation: keys {gain} at times relative to the item's start. Empty = use volume. */
        Keys vol = new Keys();
        /** 3.15: equalizer (null = none). */
        AudioEq eq;
        Anchor link = new Anchor();

        long dur() { return Math.max(0, out - in); }

        AudioItem copy() {
            AudioItem a = new AudioItem();
            a.link = link.copy();
            a.timingLocked=timingLocked;
            a.id = id; a.uri = uri; a.name = name; a.srcDur = srcDur; a.start = start; a.in = in; a.out = out;
            a.volume = volume; a.fadeIn = fadeIn; a.fadeOut = fadeOut; a.fadeOffset = fadeOffset; a.fadeSpan = fadeSpan;
            a.vol = vol.copy();
            a.eq = eq == null ? null : eq.copy();
            return a;
        }
    }

    int resIdx = 1, aspectIdx = 0, fpsIdx = 2;
    /** Music ducking depth in dB under clip/overlay sound (0 = off). */
    float duckDb;
    /** 3.15: equalizer on the whole mix (null = none). */
    AudioEq masterEq;
    int bg = 0xff000000;
    ArrayList<Clip> clips = new ArrayList<Clip>();
    ArrayList<TextItem> texts = new ArrayList<TextItem>();
    ArrayList<AudioItem> audio = new ArrayList<AudioItem>();
    /** "Over everything": runs on the finished frame, titles and graphics included (the pre-3.5.2 whole-video grade). */
    ArrayList<Layer> grade = new ArrayList<Layer>();
    /** "All footage": runs on the main track and video overlays, under titles, captions, logos and visualizers. */
    ArrayList<Layer> pgrade = new ArrayList<Layer>();

    /** Footage (graded by the All footage look): a video overlay (B-roll, picture-in-picture). */
    static boolean isFootage(TextItem t) { return t.media != null && t.media.kind == VIDEO; }
    float[] beats = new float[0];   // seconds, on the own time axis of the song at beatsUri
    float bpm;
    /** The song the beats were detected in (they apply to any item playing that file). */
    String beatsUri;
    /**
     * Music sync: the audio item whose song drives beat ticks and beat snapping, clip/overlay
     * reactions, music links and visualizers. 0 = off (the default; nothing follows the music).
     */
    int syncId;

    /** The audio item music sync follows, or null when sync is off. */
    AudioItem syncItem() { return syncId == 0 ? null : audioById(syncId); }

    /** Turns sync off if its track is gone. Returns true if it changed. */
    boolean validateSync() {
        if (syncId != 0 && audioById(syncId) == null) { syncId = 0; return true; }
        return false;
    }

    /** Detected beats, if they belong to the sync track's song. */
    float[] syncBeats() {
        AudioItem m = syncItem();
        return m != null && m.uri.equals(beatsUri) ? beats : NO_BEATS;
    }
    static final float[] NO_BEATS = new float[0];
    int nextId = 1;
    /** Analysis of the sync track's song, shared by reference between copies; null until ready. */
    transient AudioFeatures songFx;
    transient String songFxUri;
    transient java.util.concurrent.ConcurrentHashMap<String, AudioFeatures> controlFx = new java.util.concurrent.ConcurrentHashMap<String, AudioFeatures>();
    transient java.util.concurrent.ConcurrentHashMap<String, Beats.Result> controlBeats = new java.util.concurrent.ConcurrentHashMap<String, Beats.Result>();
    transient java.util.concurrent.ConcurrentHashMap<String, Music> controlMusic = new java.util.concurrent.ConcurrentHashMap<String, Music>();
    /** Music-link sources for songFx + beats (derived arrays, prefix sums), shared by copies. */
    private transient Music musicCache;

    /** Null until the song analysis is ready. */
    synchronized Music music() {
        if (songFx == null) return null;
        float[] b = syncBeats();
        if (musicCache == null || musicCache.fx != songFx || musicSrcBeats != b) {
            musicCache = new Music(songFx, b);
            musicSrcBeats = b;
        }
        return musicCache;
    }
    private transient float[] musicSrcBeats;
    /** Tests: drop the derived analysis (as a fresh export would have it). */
    synchronized void musicCacheReset() { musicCache = null; }

    // ---------------------------------------------------------------- format
    /** Output size: the resolution's short side kept, long side by aspect (even numbers). */
    int outW() { return size()[0]; }
    int outH() { return size()[1]; }
    int fps() { return FPS[fpsIdx]; }

    int[] size() {
        int s = RES[resIdx][1];   // 720 / 1080 / 2160
        float a = ASPECTS[aspectIdx];
        int w, h;
        if (a >= 1) { h = s; w = Math.round(s * a); } else { w = s; h = Math.round(s / a); }
        return new int[]{w & ~1, h & ~1};
    }

    // ---------------------------------------------------------------- timeline
    long[] durs() {
        long[] d = new long[clips.size()];
        for (int i = 0; i < d.length; i++) d[i] = clips.get(i).dur();
        return d;
    }

    long[] transDurs() {
        long[] t = new long[clips.size()];
        for (int i = 0; i < t.length; i++) t[i] = clips.get(i).trans == 0 ? 0 : clips.get(i).transDur;
        return t;
    }

    long[] starts() { return Timeline.starts(durs(), transDurs()); }

    long start(int i) { return starts()[i]; }

    /** 0: the video ends with its last picture (main track, titles, overlays, visualizers); 1: with the music too. */
    int lengthMode;
    static final String[] LENGTH = {"Ends with the picture", "Ends with the music"};

    /**
     * Project end: the latest of the main track and every title, overlay and visualizer (an end
     * title or B-roll past the last clip is part of the video). Music is cut at that point unless
     * the length follows the music, or there is nothing visual at all.
     */
    long duration() {
        long d = Timeline.end(durs(), transDurs());
        for (TextItem t : texts) d = Math.max(d, t.start + t.dur);
        if (lengthMode == 1 || d <= 0) for (AudioItem a : audio) d = Math.max(d, a.start + a.dur());
        return d;
    }

    /** {clipA, clipB or -1, progress * 1e6} at t. */
    long[] at(long t) { return Timeline.at(durs(), transDurs(), t); }

    /** Source time (us) of clip i at timeline time t. */
    long srcTime(int i, long t) {
        Clip c = clips.get(i);
        long s = c.srcAt(t - start(i));
        return Math.max(c.in, Math.min(c.out - 1, s));
    }

    /** Output px -> source px rows for a clip's placement (fit/fill, zoom, rotation, pan). */
    static void rows(Clip c, float ow, float oh, float[] r0, float[] r1) {
        rows(c, new float[]{c.scale, c.panX, c.panY, c.rot}, ow, oh, r0, r1);
    }

    /** Same, with an evaluated transform xf = {scale, panX, panY, rot, ...}. */
    static void rows(Clip c, float[] xf, float ow, float oh, float[] r0, float[] r1) {
        float base = c.fit == 0 ? Math.min(ow / c.srcW, oh / c.srcH) : Math.max(ow / c.srcW, oh / c.srcH);
        double k = base * Math.max(0.01f, xf[0]), t = Math.toRadians(xf[3]), co = Math.cos(t), si = Math.sin(t);
        double cx = ow / 2.0 + xf[1] * ow, cy = oh / 2.0 + xf[2] * oh;
        r0[0] = (float) (co / k); r0[1] = (float) (si / k); r0[2] = (float) ((-co * cx - si * cy) / k + c.srcW / 2.0);
        r1[0] = (float) (-si / k); r1[1] = (float) (co / k); r1[2] = (float) ((si * cx - co * cy) / k + c.srcH / 2.0);
    }

    /** Snap targets for editing: clip boundaries, text and audio edges, beats on the timeline. */
    long[] snapTargets() {
        ArrayList<Long> s = new ArrayList<Long>();
        long[] st = starts();
        for (int i = 0; i < clips.size(); i++) { s.add(st[i]); s.add(st[i] + clips.get(i).dur()); }
        for (TextItem t : texts) { s.add(t.start); s.add(t.start + t.dur); }
        for (AudioItem a : audio) { s.add(a.start); s.add(a.start + a.dur()); }
        for (long b : beatTimes()) s.add(b);
        long[] a = new long[s.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.get(i);
        return a;
    }

    /** Beats placed on the timeline (only with music sync on; they belong to the sync track). */
    long[] beatTimes() {
        float[] bs = syncBeats();
        if (bs.length == 0) return new long[0];
        AudioItem m = syncItem();
        ArrayList<Long> out = new ArrayList<Long>();
        for (float b : bs) {
            long src = (long) (b * 1e6);
            if (src < m.in || src > m.out) continue;
            out.add(m.start + src - m.in);
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    Clip clipById(int id) { for (Clip c : clips) if (c.id == id) return c; return null; }
    int clipIndex(int id) { for (int i = 0; i < clips.size(); i++) if (clips.get(i).id == id) return i; return -1; }
    TextItem textById(int id) { for (TextItem t : texts) if (t.id == id) return t; return null; }
    AudioItem audioById(int id) { for (AudioItem a : audio) if (a.id == id) return a; return null; }

    // ---------------------------------------------------------------- 3.13: markers, default transition
    /** Timeline markers (LumaFusion: note + colour tag + jump; tap-along for rhythm). Positions on the timeline. */
    static final int[] MARKER_COLORS = {0xffe9a23b, 0xffe5484d, 0xff46a758, 0xff3e8ef7, 0xffa972e8, 0xffd9d9de};
    static final String[] MARKER_COLOR_NAMES = {"Amber", "Red", "Green", "Blue", "Purple", "White"};

    static final class Marker {
        long t;
        int color;
        String note = "";
        Marker copy() { Marker m = new Marker(); m.t = t; m.color = color; m.note = note; return m; }
    }

    final ArrayList<Marker> markers = new ArrayList<Marker>();
    /** Transition given to new cuts (index into TRANS; 0 = plain cut) and its length. */
    int defTrans = 0;
    long defTransDur = 500000;

    /** Adds a marker at t (kept sorted). A marker already within tol of t is returned instead of a duplicate. */
    Marker addMarker(long t, long tol) {
        Marker near = markerNear(t, tol);
        if (near != null) return near;
        Marker m = new Marker();
        m.t = Math.max(0, t);
        int i = 0;
        while (i < markers.size() && markers.get(i).t <= m.t) i++;
        markers.add(i, m);
        return m;
    }

    Marker markerNear(long t, long tol) {
        Marker best = null;
        for (Marker m : markers) if (Math.abs(m.t - t) <= tol && (best == null || Math.abs(m.t - t) < Math.abs(best.t - t))) best = m;
        return best;
    }

    /** The next marker strictly after t (dir > 0) or strictly before it (dir < 0), beyond tol; -1 if none. */
    long markerStep(long t, int dir, long tol) {
        if (dir > 0) { for (Marker m : markers) if (m.t > t + tol) return m.t; return -1; }
        for (int i = markers.size() - 1; i >= 0; i--) if (markers.get(i).t < t - tol) return markers.get(i).t;
        return -1;
    }

    /** Every grade stack in the project (clips, overlays, all footage, everything). */
    java.util.List<ArrayList<Layer>> gradeLists() {
        java.util.ArrayList<ArrayList<Layer>> o = new java.util.ArrayList<ArrayList<Layer>>();
        for (Clip c : clips) o.add(c.grade);
        for (TextItem t : texts) if (t.media != null) o.add(t.media.grade);
        o.add(pgrade); o.add(grade);
        return o;
    }

    boolean anyGradeGroups() {
        for (ArrayList<Layer> g : gradeLists()) if (GradeGroups.hasGroups(g)) return true;
        return false;
    }

    long[] markerTimes() {
        long[] o = new long[markers.size()];
        for (int i = 0; i < o.length; i++) o[i] = markers.get(i).t;
        return o;
    }

    void sortMarkers() {
        java.util.Collections.sort(markers, new java.util.Comparator<Marker>() {
            @Override public int compare(Marker a, Marker b) { return a.t < b.t ? -1 : a.t > b.t ? 1 : 0; }
        });
    }

    VProject copy() {
        VProject p = new VProject();
        p.resIdx = resIdx; p.aspectIdx = aspectIdx; p.fpsIdx = fpsIdx; p.bg = bg; p.nextId = nextId;
        for (Marker m : markers) p.markers.add(m.copy());
        p.defTrans = defTrans; p.defTransDur = defTransDur;
        p.duckDb = duckDb;
        p.masterEq = masterEq == null ? null : masterEq.copy();
        p.lengthMode = lengthMode;
        for (Clip c : clips) p.clips.add(c.copy());
        for (TextItem t : texts) p.texts.add(t.copy());
        for (AudioItem a : audio) p.audio.add(a.copy());
        for (Layer l : grade) p.grade.add(l.copy());
        for (Layer l : pgrade) p.pgrade.add(l.copy());
        p.beats = beats;
        p.bpm = bpm;
        p.beatsUri = beatsUri;
        p.syncId = syncId;
        p.songFx = songFx;
        p.songFxUri = songFxUri;
        p.controlFx = controlFx; p.controlBeats = controlBeats; p.controlMusic = controlMusic;
        p.musicCache = musicCache;
        p.musicSrcBeats = musicSrcBeats;
        p.mainKey = mainKey;
        return p;
    }

    // ---------------------------------------------------------------- persistence
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        // format 2 = has media overlays: an older app (which would drop them) refuses the file
        o.put("type", "video"); o.put("v", formatVersion());
        if (duckDb > 0) o.put("duck", (double) duckDb);
        if (masterEq != null) o.put("meq", masterEq.toJson());
        if (lengthMode != 0) o.put("len", lengthMode);
        o.put("res", resIdx); o.put("aspect", aspectIdx); o.put("fps", fpsIdx); o.put("bg", bg); o.put("nextId", nextId);
        JSONArray ca = new JSONArray();
        for (Clip c : clips) ca.put(clipJson(c));
        o.put("clips", ca);
        JSONArray ta = new JSONArray();
        for (TextItem t : texts) {
            JSONObject j = new JSONObject();
            j.put("id", t.id); j.put("layer", t.layer.toJson()); j.put("start", t.start); j.put("dur", t.dur);
            if(t.timingLocked)j.put("timingLocked",true);
            j.put("fadeIn", t.fadeIn); j.put("fadeOut", t.fadeOut); j.put("fadeOffset", t.fadeOffset); j.put("fadeSpan", t.fadeSpan);
            j.put("keys", keysJson(t.keys)); j.put("control", t.reactAudio); j.put("post", t.reactPost); j.put("audioMode", t.audioMode); j.put("flash", (double) t.flash); j.put("react", t.reactSrc); if (t.reactAdapt) j.put("rad", true); j.put("pulse", (double) t.pulse); j.put("shake", (double) t.shake);
            if (t.viz != null) j.put("viz", t.viz.toJson());
            if (t.media != null) j.put("media", clipJson(t.media));
            if (t.caption) j.put("cap", true);
            if (t.link.on) j.put("link", t.link.toJson());
            ta.put(j);
        }
        o.put("texts", ta);
        return finishJson(o);
    }

    static JSONObject clipJson(Clip c) throws JSONException {
            JSONObject j = new JSONObject();
            j.put("id", c.id); j.put("kind", c.kind); j.put("uri", c.uri); j.put("name", c.name);
            if(c.timingLocked)j.put("timingLocked",true);
            j.put("srcW", c.srcW); j.put("srcH", c.srcH); j.put("rotation", c.rotation); j.put("srcDur", c.srcDur);
            j.put("hasAudio", c.hasAudio); j.put("in", c.in); j.put("out", c.out); j.put("speed", (double) c.speed);
            j.put("volume", (double) c.volume); j.put("mute", c.mute); j.put("fit", c.fit); j.put("scale", (double) c.scale);
            j.put("panX", (double) c.panX); j.put("panY", (double) c.panY); j.put("rot", (double) c.rot);
            j.put("trans", c.trans); j.put("transDur", c.transDur);
            j.put("opacity", (double) c.opacity); j.put("keys", keysJson(c.keys));
            j.put("control", c.reactAudio); j.put("post", c.reactPost); j.put("react", c.reactSrc); if (c.reactAdapt) j.put("rad", true); j.put("pulse", (double) c.pulse); j.put("shake", (double) c.shake); j.put("flash", (double) c.flash);
            j.put("grade", layers(c.grade));
            if (c.curve != null) { j.put("curve", c.curve.toJson()); j.put("cin", c.curveIn); j.put("cout", c.curveOut); }
            if (c.tape) j.put("tape", true);
            if (c.eq != null) j.put("eq", c.eq.toJson());
            if (c.frameUs >= 0) j.put("frame", c.frameUs);
            return j;
    }

    private JSONObject finishJson(JSONObject o) throws JSONException {
        JSONArray aa = new JSONArray();
        for (AudioItem a : audio) {
            JSONObject j = new JSONObject();
            j.put("id", a.id); j.put("uri", a.uri); j.put("name", a.name); j.put("srcDur", a.srcDur);
            if(a.timingLocked)j.put("timingLocked",true);
            j.put("start", a.start); j.put("in", a.in); j.put("out", a.out); j.put("volume", (double) a.volume);
            j.put("fadeIn", a.fadeIn); j.put("fadeOut", a.fadeOut); j.put("fadeOffset", a.fadeOffset); j.put("fadeSpan", a.fadeSpan);
            if (!a.vol.isEmpty()) j.put("volKeys", keysJson(a.vol));
            if (a.eq != null) j.put("eq", a.eq.toJson());
            if (a.link.on) j.put("link", a.link.toJson());
            aa.put(j);
        }
        o.put("audio", aa);
        o.put("grade", layers(grade));
        if (!pgrade.isEmpty()) o.put("pgrade", layers(pgrade));
        JSONArray b = new JSONArray();
        for (float x : beats) b.put((double) x);
        o.put("beats", b);
        o.put("bpm", (double) bpm);
        if (beatsUri != null) o.put("beatsUri", beatsUri);
        o.put("sync", syncId);   // always written: a file without it is from before 3.4.1 (see readRest)
        if (!markers.isEmpty()) {
            JSONArray ma = new JSONArray();
            for (Marker m : markers) {
                JSONObject j = new JSONObject();
                j.put("t", m.t); if (m.color != 0) j.put("c", m.color); if (m.note.length() > 0) j.put("n", m.note);
                ma.put(j);
            }
            o.put("markers", ma);
        }
        if (defTrans != 0) { o.put("defTrans", defTrans); o.put("defTransDur", defTransDur); }
        return o;
    }

    private static JSONArray layers(ArrayList<Layer> ls) throws JSONException {
        JSONArray a = new JSONArray();
        for (Layer l : ls) a.put(l.toJson());
        return a;
    }

    static JSONArray keysJson(Keys k) throws JSONException {
        JSONArray a = new JSONArray();
        for (Keys.Key x : k.list) {
            JSONObject o = new JSONObject();
            o.put("t", x.t);
            o.put("e", x.ease);
            if (x.mask != Keys.ALL) o.put("m", x.mask);
            if (x.ease == Keys.BEZIER && x.bez != null) {
                JSONArray b = new JSONArray();
                for (float f : x.bez) b.put((double) f);
                o.put("b", b);
            }
            JSONArray v = new JSONArray();
            for (float f : x.v) v.put((double) f);
            o.put("v", v);
            a.put(o);
        }
        return a;
    }

    static Keys readKeys(JSONArray a) throws JSONException {
        Keys k = new Keys();
        if (a == null) return k;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Keys.Key x = new Keys.Key();
            x.t = o.getLong("t");
            x.ease = o.optInt("e", Keys.SMOOTH);
            x.mask = o.optInt("m", Keys.ALL);
            JSONArray bz = o.optJSONArray("b");
            if (bz != null && bz.length() == 4) { x.bez = new float[4]; for (int q = 0; q < 4; q++) x.bez[q] = (float) bz.getDouble(q); }
            JSONArray v = o.getJSONArray("v");
            x.v = new float[v.length()];
            for (int j = 0; j < x.v.length; j++) x.v[j] = (float) v.getDouble(j);
            k.list.add(x);
        }
        return k;
    }

    private static void readLayers(JSONArray a, ArrayList<Layer> into) throws JSONException {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) into.add(Layer.fromJson(a.getJSONObject(i)));
    }

    static VProject fromJson(JSONObject o) throws JSONException {
        VProject p = new VProject();
        p.resIdx = o.optInt("res", 1); p.aspectIdx = o.optInt("aspect", 0); p.fpsIdx = o.optInt("fps", 2);
        p.bg = o.optInt("bg", 0xff000000); p.nextId = o.optInt("nextId", 1);
        JSONArray ca = o.optJSONArray("clips");
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            p.clips.add(clipFrom(ca.getJSONObject(i)));
        }
        readTexts(o, p);
        return readRest(o, p);
    }

    static Clip clipFrom(JSONObject j) throws JSONException {
            Clip c = new Clip();
            c.timingLocked=j.optBoolean("timingLocked");
            c.id = j.getInt("id"); c.kind = j.optInt("kind"); c.uri = j.getString("uri"); c.name = j.optString("name", "Clip");
            c.srcW = j.getInt("srcW"); c.srcH = j.getInt("srcH"); c.rotation = j.optInt("rotation");
            c.srcDur = j.getLong("srcDur"); c.hasAudio = j.optBoolean("hasAudio"); c.in = j.getLong("in"); c.out = j.getLong("out");
            c.speed = (float) j.optDouble("speed", 1); c.volume = (float) j.optDouble("volume", 1); c.mute = j.optBoolean("mute");
            c.fit = j.optInt("fit", 1); c.scale = (float) j.optDouble("scale", 1);
            c.panX = (float) j.optDouble("panX"); c.panY = (float) j.optDouble("panY"); c.rot = (float) j.optDouble("rot");
            c.trans = j.optInt("trans"); c.transDur = j.optLong("transDur", 500000);
            c.opacity = (float) j.optDouble("opacity", 1); c.keys = readKeys(j.optJSONArray("keys"));
            c.reactAudio = j.optInt("control", AudioControl.GUIDE); c.reactPost = j.optBoolean("post", false); c.reactSrc = j.optInt("react"); c.reactAdapt = j.optBoolean("rad", false); c.pulse = (float) j.optDouble("pulse", 0); c.shake = (float) j.optDouble("shake", 0);
            c.flash = (float) j.optDouble("flash", 0);
            readLayers(j.optJSONArray("grade"), c.grade);
            c.curve = SpeedCurve.fromJson(j.optJSONArray("curve"));
            // 3.5 curves spanned exactly the clip's footage
            c.curveIn = j.has("cin") ? j.optLong("cin") : c.in;
            c.curveOut = j.has("cout") ? j.optLong("cout") : c.out;
            c.frameUs = j.optLong("frame", -1);
            c.tape = j.optBoolean("tape");
            c.eq = AudioEq.fromJson(j.optJSONObject("eq"));
            return c;
    }

    private static void readTexts(JSONObject o, VProject p) throws JSONException {
        JSONArray ta = o.optJSONArray("texts");
        if (ta != null) for (int i = 0; i < ta.length(); i++) {
            JSONObject j = ta.getJSONObject(i);
            TextItem t = new TextItem();
            t.timingLocked=j.optBoolean("timingLocked");
            t.id = j.getInt("id"); t.layer = Layer.fromJson(j.getJSONObject("layer"));
            t.start = j.getLong("start"); t.dur = j.getLong("dur");
            t.fadeIn = j.optLong("fadeIn", 300000); t.fadeOut = j.optLong("fadeOut", 300000); t.fadeOffset = j.optLong("fadeOffset"); t.fadeSpan = j.optLong("fadeSpan", -1);
            t.keys = readKeys(j.optJSONArray("keys")); t.reactSrc = j.optInt("react");
            t.reactAudio = j.optInt("control", AudioControl.GUIDE); t.reactPost = j.optBoolean("post", false);
            t.flash = (float) j.optDouble("flash", 0); t.audioMode = Math.max(0, Math.min(2, j.optInt("audioMode", 0)));
            t.reactAdapt = j.optBoolean("rad", false);
            t.pulse = (float) j.optDouble("pulse", 0); t.shake = (float) j.optDouble("shake", 0);
            JSONObject vz = j.optJSONObject("viz");
            if (vz != null) t.viz = Viz.fromJson(vz);
            JSONObject md = j.optJSONObject("media");
            if (md != null) { t.media = clipFrom(md); if (!j.has("audioMode")) t.audioMode = t.media.mute ? 0 : 2; }
            t.caption = j.optBoolean("cap");
            t.link = Anchor.fromJson(j.optJSONObject("link"));
            p.texts.add(t);
        }
    }

    private static VProject readRest(JSONObject o, VProject p) throws JSONException {
        JSONArray aa = o.optJSONArray("audio");
        if (aa != null) for (int i = 0; i < aa.length(); i++) {
            JSONObject j = aa.getJSONObject(i);
            AudioItem a = new AudioItem();
            a.timingLocked=j.optBoolean("timingLocked");
            a.id = j.getInt("id"); a.uri = j.getString("uri"); a.name = j.optString("name", "Audio");
            a.srcDur = j.getLong("srcDur"); a.start = j.getLong("start"); a.in = j.getLong("in"); a.out = j.getLong("out");
            a.volume = (float) j.optDouble("volume", 1); a.fadeIn = j.optLong("fadeIn"); a.fadeOut = j.optLong("fadeOut"); a.fadeOffset = j.optLong("fadeOffset"); a.fadeSpan = j.optLong("fadeSpan", -1);
            a.vol = readKeys(j.optJSONArray("volKeys"));
            a.eq = AudioEq.fromJson(j.optJSONObject("eq"));
            a.link = Anchor.fromJson(j.optJSONObject("link"));
            p.audio.add(a);
        }
        readLayers(o.optJSONArray("grade"), p.grade);
        readLayers(o.optJSONArray("pgrade"), p.pgrade);
        JSONArray b = o.optJSONArray("beats");
        if (b != null) {
            p.beats = new float[b.length()];
            for (int i = 0; i < p.beats.length; i++) p.beats[i] = (float) b.getDouble(i);
        }
        p.bpm = (float) o.optDouble("bpm", 0);
        p.duckDb = (float) o.optDouble("duck", 0);
        p.masterEq = AudioEq.fromJson(o.optJSONObject("meq"));
        p.lengthMode = Math.max(0, Math.min(1, o.optInt("len", 0)));
        JSONArray ma = o.optJSONArray("markers");
        if (ma != null) for (int i = 0; i < ma.length(); i++) {
            JSONObject j = ma.optJSONObject(i);
            if (j == null) continue;
            Marker m = new Marker();
            m.t = j.optLong("t", -1); m.color = j.optInt("c", 0); m.note = j.optString("n", "");
            p.markers.add(m);
        }
        p.defTrans = o.optInt("defTrans", 0);
        p.defTransDur = o.optLong("defTransDur", 500000);
        String bu = o.optString("beatsUri", "");
        if (o.has("sync")) {
            p.syncId = o.optInt("sync");
            p.beatsUri = bu.length() > 0 ? bu : null;
        } else {
            // before 3.4.1 everything followed the first music item, and its beats were detected
            // automatically. Keep sync only where something in the project actually moves with
            // the music; projects that merely had auto-detected beats open with sync off (their
            // beats are kept, so turning sync back on for that song needs no re-detect).
            AudioItem first = p.audio.isEmpty() ? null : p.audio.get(0);
            p.beatsUri = first != null && p.beats.length > 0 ? first.uri : null;
            p.syncId = first != null && p.usesMusic() ? first.id : 0;
        }
        return p;
    }

    /** Fade gain (0..1) for an item [start, start+dur) at t with given fade lengths. */
    static float fade(long t, long start, long dur, long fin, long fout) {
        float g = 1;
        if (fin > 0 && t < start + fin) g = Math.min(g, (t - start) / (float) fin);
        if (fout > 0 && t > start + dur - fout) g = Math.min(g, (start + dur - t) / (float) fout);
        return Math.max(0, Math.min(1, g));
    }

    // ---------------------------------------------------------------- animation and music drive
    static final String[] REACT = {"Beat", "Bass", "Level"};
    static final float BEAT_DECAY = 0.22f, RELEASE = 0.12f;

    static float[] clipVals(Clip c) { return new float[]{c.scale, c.panX, c.panY, c.rot, c.opacity}; }
    static void setClipVals(Clip c, float[] v) { c.scale = v[0]; c.panX = v[1]; c.panY = v[2]; c.rot = v[3]; c.opacity = v[4]; }
    static float[] textVals(TextItem t) { Layer l = t.layer; return new float[]{l.cx, l.cy, l.scale, l.rot, l.opacity}; }
    static void setTextVals(TextItem t, float[] v) { Layer l = t.layer; l.cx = v[0]; l.cy = v[1]; l.scale = v[2]; l.rot = v[3]; l.opacity = v[4]; }

    /** Beats on the timeline, sorted (computed per call; there are a few hundred at most). */
    private long[] beatCache;
    private long beatKey = Long.MIN_VALUE;

    /** 0..1 music drive at timeline time t from the sync track (0 with sync off). */
    float drive(int src, long t) { return drive(src, t, false); }

    /** adapt: Bass/Level relative to the last ~1.3 s (MilkDrop-style), so quiet passages react too. */
    float drive(int src, long t, boolean adapt) {
        AudioItem m = syncItem();
        if (m == null) return 0;
        if (adapt && src != 0) {
            Music mu = music();
            if (mu == null || t < m.start || t >= m.start + m.dur()) return 0;
            return mu.follow(mu.code(src == 1 ? Music.BASS : Music.LEVEL, true), m.in + (t - m.start), RELEASE, 0);
        }
        if (src == 0) {
            long key = ((m.start * 31 + m.in * 7 + m.out) * 31 + m.id) * 31 + syncBeats().length;
            if (beatCache == null || beatKey != key) { beatCache = beatTimes(); beatKey = key; }
            long[] b = beatCache;
            int lo = 0, hi = b.length - 1, at = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (b[mid] <= t) { at = mid; lo = mid + 1; } else hi = mid - 1; }
            if (at < 0) return 0;
            return (float) Math.exp(-(t - b[at]) / (BEAT_DECAY * 1e6));
        }
        AudioFeatures fx = songFx;
        if (fx == null || t < m.start || t >= m.start + m.dur()) return 0;
        long s = m.in + (t - m.start);
        return src == 1 ? fx.bassAt(s, RELEASE) : fx.levelAt(s, RELEASE);
    }

    /** Song time for timeline time t, or -1 outside the sync track (or with sync off). */
    long songTime(long t) {
        AudioItem m = syncItem();
        if (m == null) return -1;
        if (t < m.start || t >= m.start + m.dur()) return -1;
        return m.in + (t - m.start);
    }

    /** Smooth pseudo-random wobble in -1..1, distinct per seed. */
    static float wobble(long t, int seed, int axis) {
        double s = t / 1e6, k = seed * 1.618 + axis * 4.1;
        return (float) (0.6 * Math.sin(s * 23.0 + k) + 0.4 * Math.sin(s * 37.7 + k * 2.3));
    }

    /** Clip i's transform at timeline time t: {scale, panX, panY, rot, opacity, flash}. */
    float[] clipXf(int i, long t) {
        Clip c = clips.get(i);
        float[] v = clipVals(c);
        c.keys.eval(t - start(i), v);
        float[] o = {v[0], v[1], v[2], v[3], v[4], 0};
        if (c.pulse > 0 || c.shake > 0 || c.flash > 0) {
            float d = AudioControl.drive(this, c.reactAudio, c.id, c.reactSrc, c.reactAdapt, c.reactPost, t);
            o[0] *= 1 + c.pulse * d;
            o[1] += c.shake * d * wobble(t, c.id, 0);
            o[2] += c.shake * d * wobble(t, c.id, 1);
            o[5] = c.flash * d;
        }
        return o;
    }

    /** Format 14 preserves edit-stable automation phase and split fade envelopes. */
    int formatVersion() {
        if (uses316Audio()) return 19;   // 3.16: equalizers, tape pitch, 0.1x-10x speeds, shaped speed curves
        if (VideoMasks.any(this) || Visuals.anyNew(this)) return 18;   // 3.15: masks on effects and grades, new visualizer styles
        if (anyGradeGroups() || usesNewTransitions()) return 17;   // 3.14: look groups (Strength), new transitions
        if (!markers.isEmpty() || defTrans != 0) return 16;   // 3.13: markers, default transition
        return TimelineLocks.anyLocked(this) ? 15 : 14;
    }

    /** 3.16: equalizers, tape pitch, constant speeds outside 0.25x-4x, shaped speed curves. */
    boolean uses316Audio() {
        if (masterEq != null) return true;
        for (AudioItem a : audio) if (a.eq != null) return true;
        java.util.ArrayList<Clip> all = new java.util.ArrayList<Clip>(clips);
        for (TextItem t : texts) if (t.media != null) all.add(t.media);
        for (Clip c : all) {
            if (c.eq != null || c.tape || c.speed < 0.249f || c.speed > 4.001f) return true;
            if (c.curve != null && !c.curve.legacyForm()) return true;
        }
        return false;
    }

    /** Move one selected effect to the composed footage while preserving its source and range. */
    boolean moveGradeToFootage(int owner, Layer layer, long start, long duration) {
        ArrayList<Layer> from = null;
        Clip clip = clipById(owner);
        if (clip != null) from = clip.grade;
        else for (TextItem ti : texts) if (ti.media != null && ti.media.id == owner) { from = ti.media.grade; break; }
        if (from == null || !from.remove(layer)) return false;
        for (Music.Link k : layer.links) {
            if (k.audio == AudioControl.SELF) k.audio = owner;
            k.endUs = start + (k.endUs < 0 ? duration : k.endUs);
            k.startUs += start;
            for (Keys.Key key : k.amountKeys.list) key.t += start;
        }
        pgrade.add(layer);
        return true;
    }

    boolean usesAdaptive() {
        for (Clip c : clips) {
            if (c.reactAdapt && c.reactSrc != 0 && (c.pulse > 0 || c.shake > 0 || c.flash > 0)) return true;
            if (Music.anyAdaptive(c.grade)) return true;
        }
        for (TextItem t : texts) {
            if (t.reactAdapt && t.reactSrc != 0 && (t.pulse > 0 || t.shake > 0)) return true;
            if (t.viz != null && t.viz.auto) return true;
        }
        for (TextItem t : texts) if (t.media != null && Music.anyAdaptive(t.media.grade)) return true;
        return Music.anyAdaptive(grade) || Music.anyAdaptive(pgrade);
    }

    /** Shared by preview and export: replace the main sound only inside an active B-roll item. */
    float mainSoundGain(long t) {
        float gain = 1;
        for (TextItem ti : texts) {
            if (ti.audioMode != 1 || ti.media == null || ti.media.kind != VIDEO || !ti.media.hasAudio
                    || ti.media.mute || !ti.layer.visible || t < ti.start || t >= ti.start + ti.dur) continue;
            gain = Math.min(gain, 1 - overlaySoundFade(ti, t));
        }
        return gain;
    }

    static float overlaySoundFade(TextItem ti, long t) {
        return fade(t, ti.start - ti.fadeOffset, ti.fadeSpan < 0 ? ti.dur + ti.fadeOffset : ti.fadeSpan,
                Math.max(5000, ti.fadeIn), Math.max(5000, ti.fadeOut));
    }

    static float itemFade(TextItem ti, long t) {
        return fade(t, ti.start - ti.fadeOffset, ti.fadeSpan < 0 ? ti.dur + ti.fadeOffset : ti.fadeSpan, ti.fadeIn, ti.fadeOut);
    }

    static float audioFade(AudioItem a, long t) {
        return fade(t, a.start - a.fadeOffset, a.fadeSpan < 0 ? a.dur() + a.fadeOffset : a.fadeSpan, a.fadeIn, a.fadeOut);
    }

    static float audioSoundFade(AudioItem a, long t) {
        return fade(t, a.start - a.fadeOffset, a.fadeSpan < 0 ? a.dur() + a.fadeOffset : a.fadeSpan,
                Math.max(5000, a.fadeIn), Math.max(5000, a.fadeOut));
    }

    /** Music item gain at timeline time t before fades and ducking: its keys, else its volume. */
    static float musicVol(AudioItem a, long t) {
        if (a.vol.isEmpty()) return a.volume;
        float[] v = {a.volume};
        a.vol.eval(t - a.start, v);
        return Math.max(0, v[0]);
    }

    // ---------------------------------------------------------------- media overlays
    static boolean isMedia(TextItem t) { return t.media != null; }

    /** Source time (us) of a media overlay at timeline time t. */
    static long overlaySrc(TextItem ti, long t) {
        Clip m = ti.media;
        long s = m.in + (long) ((t - ti.start) * m.speed);
        return Math.max(m.in, Math.min(Math.max(m.in, m.srcDur - 1), s));
    }

    /** Longest a media overlay can run from its in point (a still can run any length). */
    static long overlayMaxDur(TextItem ti) {
        Clip m = ti.media;
        if (m == null || m.kind == IMAGE) return 3600000000L;
        return Math.max(100000, (long) ((m.srcDur - m.in) / Math.max(0.01f, m.speed)));
    }

    /** Keeps a video overlay's out point consistent with its duration (export audio reads it). */
    static void syncOverlayOut(TextItem ti) {
        if (ti.media == null) return;
        ti.dur = Math.min(ti.dur, overlayMaxDur(ti));
        ti.media.out = ti.media.kind == IMAGE ? ti.dur : Math.min(ti.media.srcDur, ti.media.in + (long) (ti.dur * ti.media.speed));
    }

    /** Overlay transform at timeline time t: {cx, cy, scale, rot, opacity}. */
    float[] textXf(TextItem ti, long t) {
        float[] v = textVals(ti);
        ti.keys.eval(t - ti.start, v);
        if (ti.pulse > 0 || ti.shake > 0) {
            float d = AudioControl.drive(this, ti.reactAudio, ti.media == null ? 0 : ti.media.id, ti.reactSrc, ti.reactAdapt, ti.reactPost, t);
            float m = Math.min(outW(), outH());
            v[2] *= 1 + ti.pulse * d;
            v[0] += ti.shake * d * m * wobble(t, ti.id, 0);
            v[1] += ti.shake * d * m * wobble(t, ti.id, 1);
        }
        return v;
    }

    /** Largest scale an overlay reaches (keys and pulse), so text can be rendered sharp enough. */
    static float maxScale(TextItem ti) {
        float s = ti.layer.scale;
        for (Keys.Key k : ti.keys.list) s = Math.max(s, k.v[2]);
        return s * (1 + ti.pulse);
    }

    /** True if anything in the project reads the song analysis. */
    boolean usesSongFx() {
        for (TextItem t : texts) if (t.viz != null || ((t.pulse > 0 || t.shake > 0) && t.reactSrc != 0)) return true;
        for (Clip c : clips) if ((c.pulse > 0 || c.shake > 0 || c.flash > 0) && c.reactSrc != 0) return true;
        for (Clip c : clips) if (Music.hasLinks(c.grade)) return true;
        for (TextItem t : texts) if (t.media != null && Music.hasLinks(t.media.grade)) return true;
        return Music.hasLinks(grade) || Music.hasLinks(pgrade);
    }

    private static boolean newKeys(Keys k) {
        for (Keys.Key x : k.list) if (x.mask != Keys.ALL || x.ease == Keys.BEZIER) return true;
        return false;
    }

    /** True if anything in the project moves with the music (beat reactions included). */
    boolean usesMusic() {
        if (usesSongFx()) return true;
        for (TextItem t : texts) if (t.pulse > 0 || t.shake > 0) return true;
        for (Clip c : clips) if (c.pulse > 0 || c.shake > 0 || c.flash > 0) return true;
        return false;
    }

    // ---------------------------------------------------------------- source files (relinking)
    static final int SRC_VIDEO = 0, SRC_IMAGE = 1, SRC_AUDIO = 2;
    static final String[] SRC_KIND = {"video", "photo", "audio"};

    /** A file the project uses: what it is, how many items use it and how much of it the edit needs. */
    static final class Source {
        String uri, name;
        int kind, uses;
        long needUs;   // the latest source time any item reaches (0 for photos)
    }

    /** Every file the project uses, in timeline order of first use. */
    java.util.LinkedHashMap<String, Source> sources() {
        java.util.LinkedHashMap<String, Source> m = new java.util.LinkedHashMap<String, Source>();
        for (Clip c : clips) {
            if (c.frameUs >= 0) addSource(m, c.uri, c.name, SRC_VIDEO, c.frameUs + 1);   // a freeze frame needs its video
            else addSource(m, c.uri, c.name, c.kind == IMAGE ? SRC_IMAGE : SRC_VIDEO, c.kind == IMAGE ? 0 : c.out);
        }
        for (TextItem t : texts) if (t.media != null) {
            Clip c = t.media;
            addSource(m, c.uri, c.name, c.kind == IMAGE ? SRC_IMAGE : SRC_VIDEO, c.kind == IMAGE ? 0 : c.out);
        }
        for (AudioItem a : audio) addSource(m, a.uri, a.name, SRC_AUDIO, a.out);
        return m;
    }

    private static void addSource(java.util.LinkedHashMap<String, Source> m, String uri, String name, int kind, long need) {
        Source s = m.get(uri);
        if (s == null) { s = new Source(); s.uri = uri; s.name = name; s.kind = kind; m.put(uri, s); }
        s.uses++;
        s.needUs = Math.max(s.needUs, need);
    }

    /** Facts about a replacement file (from Media.probeVideo / probeAudio / the decoded photo). */
    static final class Probe {
        String uri, name;
        int kind, srcW, srcH, rotation;
        long srcDur;
        boolean hasAudio;
    }

    /**
     * Points every item that uses oldUri at the replacement, keeping in and out points, speed,
     * placement, keys and grades. Times past the end of a shorter replacement are pulled in
     * (counted in clamped[0]). Beats and the song analysis follow the file. Returns the number
     * of items changed; 0 if the replacement is a different kind of file (a photo for a video).
     */
    int relink(String oldUri, Probe n, int[] clamped) {
        Source s = sources().get(oldUri);
        if (s == null || s.kind != n.kind) return 0;
        int changed = 0;
        for (Clip c : clips) if (oldUri.equals(c.uri)) { relinkClip(c, n, clamped); changed++; }
        for (TextItem t : texts) if (t.media != null && oldUri.equals(t.media.uri)) {
            relinkClip(t.media, n, clamped);
            t.layer.srcW = t.media.srcW;
            t.layer.srcH = t.media.srcH;
            long d = t.dur;
            syncOverlayOut(t);
            if (t.dur < d) clamped[0]++;
            changed++;
        }
        for (AudioItem a : audio) if (oldUri.equals(a.uri)) {
            a.uri = n.uri; a.name = n.name; a.srcDur = n.srcDur;
            if (a.out > n.srcDur) {
                long len = a.out - a.in;
                a.out = n.srcDur;
                a.in = Math.max(0, Math.min(a.in, a.out - Math.max(1000, Math.min(len, a.out))));
                clamped[0]++;
            }
            changed++;
        }
        if (oldUri.equals(beatsUri)) beatsUri = n.uri;   // the same song under a new address: its beats still hold
        if (oldUri.equals(songFxUri)) { songFx = null; songFxUri = null; }
        return changed;
    }

    private static void relinkClip(Clip c, Probe n, int[] clamped) {
        c.uri = n.uri; c.name = n.name;
        c.srcW = n.srcW; c.srcH = n.srcH; c.rotation = n.rotation;
        c.still = null;
        if (c.kind == IMAGE) return;
        c.srcDur = n.srcDur; c.hasAudio = n.hasAudio;
        if (c.out > n.srcDur) {
            c.out = n.srcDur;
            c.in = Math.max(0, Math.min(c.in, c.out - 1000));
            clamped[0]++;
        }
    }

    /** Name match for "find in a folder": case and surrounding spaces ignored. */
    static String nameKey(String name) { return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT); }

    // ---------------------------------------------------------------- connected items
    /** Changes whenever anything moves clips in time (order, trims, speed, transitions). */
    long mainKey() {
        long k = 1469598103934665603L;
        for (Clip c : clips) {
            long[] f = {c.id, c.in, c.out, Float.floatToIntBits(c.speed), c.trans == 0 ? 0 : c.transDur, c.curve == null ? 0 : c.curve.hash(), c.curveIn, c.curveOut};
            for (long x : f) { k ^= x; k *= 1099511628211L; }
        }
        return k;
    }
    /** mainKey() when anchors were last brought up to date (copies carry it). */
    transient long mainKey = Long.MIN_VALUE;

    /** Timeline time of an anchor's frame (+ its offset), or Long.MIN_VALUE if its clip is gone. */
    long anchorTime(Anchor a) {
        int i = clipIndex(a.clip);
        if (i < 0) return Long.MIN_VALUE;
        Clip c = clips.get(i);
        long src = Math.max(c.in, Math.min(c.out, a.src));
        return start(i) + c.localAt(src) + a.off;
    }

    /** Pins a connected item to the frame under timeline time t (its start). */
    void pinAt(Anchor a, long t) {
        if (clips.isEmpty()) { a.clip = 0; a.src = 0; a.off = 0; return; }
        long[] st = starts();
        int i = Timeline.clipAt(durs(), transDurs(), t);
        a.off = 0;
        if (i < 0) {
            // past the end (or, with nothing to the left, before 0): hang off the nearest end
            int last = clips.size() - 1;
            Clip c = clips.get(last);
            long end = st[last] + c.dur();
            if (t >= end) { a.clip = c.id; a.src = c.out; a.off = t - end; return; }
            c = clips.get(0);
            a.clip = c.id; a.src = c.in; a.off = t - st[0];
            return;
        }
        Clip c = clips.get(i);
        a.clip = c.id;
        a.src = c.srcAt(t - st[i]);
    }

    /**
     * If the frame an item is pinned to now lies outside its clip (the clip was split, or the
     * frame belongs to a copy of the same media), moves the pin to the clip that shows it.
     */
    private void rebind(Anchor a) {
        Clip c = clipById(a.clip);
        if (c != null && a.src >= c.in && a.src <= c.out) return;
        int from = c == null ? -1 : clipIndex(c.id);
        String uri = c == null ? null : c.uri;
        if (uri == null) return;
        int best = -1, bd = Integer.MAX_VALUE;
        for (int i = 0; i < clips.size(); i++) {
            Clip d = clips.get(i);
            if (d == c || !uri.equals(d.uri) || a.src < d.in || a.src > d.out) continue;
            int dist = Math.abs(i - from);
            if (dist < bd) { bd = dist; best = i; }
        }
        if (best >= 0) a.clip = clips.get(best).id;
    }

    /**
     * Brings connected items and the main track into agreement after an edit. If the main track
     * moved, connected items follow their frames; otherwise an item whose start was edited is
     * re-pinned where it now sits. Idempotent; the editor calls it on every change.
     */
    void syncAnchors() {
        long k = mainKey();
        boolean mainMoved = k != mainKey;
        mainKey = k;
        for (TextItem t : texts) t.start = sync(t.link, t.start, mainMoved);
        for (AudioItem a : audio) a.start = sync(a.link, a.start, mainMoved);
    }

    private long sync(Anchor a, long start, boolean mainMoved) {
        if (!a.on) return start;
        if (a.clip == 0 || clipById(a.clip) == null && !mainMoved) { pinAt(a, start); return start; }
        if (mainMoved) {
            rebind(a);
            long t = anchorTime(a);
            if (t == Long.MIN_VALUE) { pinAt(a, start); return start; }   // its clip was deleted: stay, pin to what is there now
            return Math.max(0, t);
        }
        if (anchorTime(a) != start) pinAt(a, start);
        return start;
    }

    /** Connects or disconnects an item at its current place. */
    void connect(Anchor a, long start, boolean on) {
        a.on = on;
        if (on) pinAt(a, start);
        else { a.clip = 0; a.src = 0; a.off = 0; }
    }
}
