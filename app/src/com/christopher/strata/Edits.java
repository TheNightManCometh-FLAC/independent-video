package com.christopher.strata;

/**
 * Three-point edits on the magnetic main track (3.7, audit package 6), pure Java so they're
 * desktop-tested (tests/EditsTest.java). A source range marked In/Out in the source viewer goes
 * in at the playhead:
 *  - insert: everything from the playhead on moves later by the new clip's length;
 *  - overwrite: the new clip covers that much of the timeline, nothing after it moves;
 *  - replace: the selected clip shows other footage, keeping its place, framing, grade,
 *    animation and transition (LumaFusion asks "keep the clip's timing or use the new length").
 * Connected titles and overlays follow their footage through VProject.syncAnchors as usual.
 */
final class Edits {
    private Edits() {}

    /**
     * Splits clip i at timeline time t: clip i keeps the part before t, a copy (new id, inserted
     * right after) gets the rest. Returns the copy. Both halves of a ramp keep the same curve.
     */
    // ---------------------------------------------------------------- 3.13: transitions for every cut
    /**
     * Resolve: "Apply transitions to all edits"; CapCut: Apply All. Sets every cut's transition.
     * Cut by cut, on a copy checked with the same rule as every other edit (TimelineLocks.violation),
     * so a cut whose change would move or alter a locked clip is left as it was. Returns
     * {changed, skipped}; p is modified in place.
     */
    static int[] transitionToAll(VProject p, int trans, long dur) {
        int changed = 0, skipped = 0;
        for (int i = 0; i + 1 < p.clips.size(); i++) {
            VProject.Clip c = p.clips.get(i);
            if (c.trans == trans && (trans == 0 || c.transDur == dur)) continue;
            if (c.timingLocked) { skipped++; continue; }
            VProject cand = p.copy();
            VProject.Clip k = cand.clips.get(i);
            k.trans = trans; k.transDur = dur;
            cand.syncAnchors();
            if (TimelineLocks.violation(p, cand) != null) { skipped++; continue; }
            c.trans = trans; c.transDur = dur;
            p.syncAnchors();
            changed++;
        }
        return new int[]{changed, skipped};
    }

    /**
     * The project's default transition on the cuts either side of a newly added main clip, where
     * those cuts are still plain cuts (a transition already there is kept) and the outgoing clip
     * isn't locked. Returns how many cuts were given it.
     */
    static int defaultTransition(VProject p, int clipId) {
        if (p.defTrans == 0) return 0;
        int i = p.clipIndex(clipId), n = 0;
        if (i < 0) return 0;
        if (i > 0) { VProject.Clip a = p.clips.get(i - 1); if (a.trans == 0 && !a.timingLocked) { a.trans = p.defTrans; a.transDur = p.defTransDur; n++; } }
        if (i + 1 < p.clips.size()) { VProject.Clip b = p.clips.get(i); if (b.trans == 0 && !b.timingLocked) { b.trans = p.defTrans; b.transDur = p.defTransDur; n++; } }
        return n;
    }

    static VProject.Clip split(VProject p, int i, long t) {
        VProject.Clip c = p.clips.get(i);
        VProject.Clip original = c.copy();
        long s = p.start(i);
        long at = p.srcTime(i, t);
        VProject.Clip b = c.copy();
        b.id = p.nextId++;
        AutomationEdits.shift(c.grade, original.grade, p, c.id, s, original.dur(), 0, true);
        AutomationEdits.shift(b.grade, original.grade, p, c.id, s, original.dur(), t - s, true);
        if (c.kind == VProject.IMAGE) {
            long d1 = t - s;
            b.in = 0; b.out = c.dur() - d1;
            c.out = d1;
        } else {
            b.in = at;
            c.out = at;
        }
        c.trans = 0;
        b.keys.shift(-(t - s));   // the second half's animation continues from where the cut is
        p.clips.add(i + 1, b);
        return b;
    }

    /** Split B-roll, titles and visualizers in their own lane; main footage never moves. */
    static VProject.TextItem splitOverlay(VProject p, VProject.TextItem a, long t) {
        long offset = t - a.start, frame = Timeline.frameTime(1, p.fps());
        if (offset < frame || offset > a.dur - frame) return null;
        VProject before = p.copy();
        VProject.TextItem old = before.textById(a.id), b = a.copy();
        b.id = p.nextId++;
        b.layer.id = b.id;
        b.start = t;
        b.dur = a.dur - offset;
        a.dur = offset;
        b.keys.shift(-offset);
        // Both halves retain the same original envelope, including cuts inside a fade.
        a.fadeSpan = b.fadeSpan = old.fadeSpan < 0 ? old.dur + old.fadeOffset : old.fadeSpan;
        b.fadeOffset = old.fadeOffset + offset;
        if (b.media != null) {
            b.media.id = p.nextId++;
            if (b.media.kind == VProject.VIDEO) b.media.in = old.media.srcAt(offset);
            VProject.syncOverlayOut(a); VProject.syncOverlayOut(b);
            AutomationEdits.shift(a.media.grade, old.media.grade, before, old.media.id, old.start, old.dur, 0, true);
            AutomationEdits.shift(b.media.grade, old.media.grade, before, old.media.id, old.start, old.dur, offset, true);
        }
        // Copies need their own connection at their new start, even when the main track did not move.
        if (b.link.on) p.pinAt(b.link, b.start);
        p.texts.add(p.texts.indexOf(a) + 1, b);
        return b;
    }

    static VProject.AudioItem splitAudio(VProject p, VProject.AudioItem a, long t) {
        long offset = t - a.start, frame = Timeline.frameTime(1, p.fps());
        if (offset < frame || offset > a.dur() - frame) return null;
        VProject.AudioItem b = a.copy();
        a.fadeSpan = b.fadeSpan = a.fadeSpan < 0 ? a.dur() + a.fadeOffset : a.fadeSpan;
        b.fadeOffset = a.fadeOffset + offset;
        b.id = p.nextId++; b.start = t; b.in = a.in + offset; a.out = b.in;
        b.vol.shift(-offset);
        if (b.link.on) p.pinAt(b.link, b.start);
        p.audio.add(p.audio.indexOf(a) + 1, b);
        return b;
    }

    /** Change speed without expanding a short cut or accumulating source rounding on each slider event. */
    static void overlaySpeed(VProject.TextItem item, float speed) {
        if(item.media==null||!Float.isFinite(speed)||speed<SpeedCurve.MIN-1e-6f||speed>SpeedCurve.MAX+1e-6f)throw new IllegalArgumentException("Speed must be between 0.1 and 10.");
        item.media.speed=speed;
        item.dur=item.media.dur();
    }

    /** Apply marked SOURCE In/Out to an existing clip, preserving its treatment and identity. */
    static void range(VProject p, VProject.Clip c, VProject.TextItem overlay, long in, long out) {
        VProject before = p.copy();
        VProject.Clip old = overlay == null ? before.clipById(c.id) : before.textById(overlay.id).media;
        long start = overlay == null ? before.start(before.clipIndex(c.id)) : overlay.start;
        long duration = overlay == null ? old.dur() : overlay.dur;
        long removed = old.localAt(in);
        c.in = in; c.out = out;
        c.keys = old.keys.copy(); c.keys.shift(-removed);
        AutomationEdits.shift(c.grade, old.grade, before, c.id, start, duration, removed, false);
        if (overlay != null) {
            overlay.start = Math.max(0, start + removed);
            overlay.dur = c.dur();
            VProject.TextItem original = before.textById(overlay.id);
            overlay.fadeOffset = original.fadeOffset + removed;
            overlay.fadeSpan = out != old.out ? overlay.dur + overlay.fadeOffset : original.fadeSpan < 0 ? original.dur + original.fadeOffset : original.fadeSpan;
            overlay.keys = before.textById(overlay.id).keys.copy();
            overlay.keys.shift(-removed);
            VProject.syncOverlayOut(overlay);
        }
    }

    /**
     * Makes sure a cut falls at t: returns the index of the clip that starts there (the clip
     * count when t is at or past the end). A point less than half a frame from an existing cut
     * uses that cut; anything else splits the clip under it. Inside a transition the cut goes
     * into the outgoing clip.
     */
    static int cutAt(VProject p, long t, long frame) {
        int n = p.clips.size();
        if (n == 0) return 0;
        long[] s = p.starts();
        long end = s[n - 1] + p.clips.get(n - 1).dur();
        if (t >= end - frame / 2) return n;
        if (t <= frame / 2) return 0;
        int i = n - 1;
        while (i > 0 && s[i] > t) i--;
        if (Math.abs(t - s[i]) <= frame / 2) return i;
        long ce = s[i] + p.clips.get(i).dur();
        if (i + 1 < n && Math.abs(t - s[i + 1]) <= frame / 2) return i + 1;
        if (Math.abs(t - ce) <= frame / 2) return i + 1;
        split(p, i, t);
        return i + 1;
    }

    /** Insert nc at t: everything from t on moves later by nc's length. Returns nc's index. */
    static int insert(VProject p, long t, VProject.Clip nc, long frame) {
        int at = cutAt(p, t, frame);
        if (at > 0) p.clips.get(at - 1).trans = 0;   // a transition can't cross the new clip's start
        nc.trans = 0;
        nc.id = p.nextId++;
        p.clips.add(at, nc);
        return at;
    }

    /**
     * Overwrite at t: nc covers [t, t + its length) and nothing after that moves (the track
     * gets longer only if nc runs past the end). Returns nc's index.
     */
    static int overwrite(VProject p, long t, VProject.Clip nc, long frame) {
        int a = cutAt(p, t, frame);
        int b = cutAt(p, t + nc.dur(), frame);
        for (int k = b - 1; k >= a; k--) p.clips.remove(k);
        return insert(p, t, nc, frame);
    }

    /**
     * Replace clip c's footage with src's (its file and marked range). keepTiming: the clip keeps
     * its length, taking that much footage from src's In (extending past Out, then back before
     * In, if the marks are shorter); otherwise it takes src's marked length. Everything else of
     * c stays: id, place, framing, speed, grade, keys, transition. Returns false if the source
     * is too short to keep the timing (it then uses all of it).
     */
    static boolean replace(VProject.Clip c, VProject.Clip src, boolean keepTiming) {
        long oldDur = c.dur();
        float sp = c.curve != null ? (float) Math.max(0.1, c.avgSpeed()) : c.speed;
        c.uri = src.uri; c.name = src.name; c.kind = src.kind;
        c.srcW = src.srcW; c.srcH = src.srcH; c.rotation = src.rotation;
        c.srcDur = src.srcDur; c.hasAudio = src.hasAudio; c.still = src.still;
        c.frameUs = -1;
        c.curve = null;           // a ramp belongs to its footage
        c.speed = sp;
        boolean fit = true;
        if (src.kind == VProject.IMAGE) {
            c.speed = 1;
            c.in = 0;
            c.out = keepTiming ? oldDur : src.out - src.in;
        } else if (!keepTiming) {
            c.in = src.in;
            c.out = src.out;
        } else {
            long need = (long) Math.ceil(oldDur * (double) c.speed);
            long in = src.in, out = in + need;
            if (out > src.srcDur) { out = src.srcDur; in = Math.max(0, out - need); }
            fit = out - in >= need;
            c.in = in;
            c.out = out;
        }
        return fit;
    }

    /** A main-track clip for src's file and the marked range [in, out). */
    static VProject.Clip fromSource(VProject.Clip probe, long in, long out) {
        VProject.Clip c = new VProject.Clip();
        c.kind = probe.kind; c.uri = probe.uri; c.name = probe.name;
        c.srcW = probe.srcW; c.srcH = probe.srcH; c.rotation = probe.rotation;
        c.srcDur = probe.srcDur; c.hasAudio = probe.hasAudio; c.still = probe.still;
        c.in = in;
        c.out = out;
        return c;
    }
}
