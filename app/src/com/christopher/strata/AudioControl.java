package com.christopher.strata;

import java.util.LinkedHashSet;

/** Audio control is independent of the audible mix and of the timeline's beat guide. */
final class AudioControl {
    static final int OFF = 0, GUIDE = -1, MAIN = -2, SELF = -3;

    static final class Source {
        String uri, name;
        long start, dur;
        VProject.Clip clip;
        VProject.AudioItem audio;
        VProject.TextItem overlay;
        long at(long t) {
            long local = Math.max(0, Math.min(dur, t - start));
            return audio != null ? audio.in + local : clip.srcAt(local);
        }
        long in() { return audio != null ? audio.in : clip.in; }
        float volume(VProject p, long t) {
            if (audio != null) return VProject.musicVol(audio, t) * VProject.audioSoundFade(audio, t);
            if (clip.mute || !clip.hasAudio || clip.curve != null) return 0;
            float weight=1;
            if(overlay==null){long[] active=p.at(t);if(active[1]>=0){float incoming=active[2]/1e6f;weight=p.clips.get((int)active[0]).id==clip.id?1-incoming:incoming;}}
            return clip.volume * weight * (overlay != null ? VProject.overlaySoundFade(overlay, t) : p.mainSoundGain(t));
        }
    }

    static Source resolve(VProject p, int ref, int owner, long t) {
        if (ref == OFF) return null;
        if (ref == SELF) ref = owner > 0 ? owner : MAIN;
        if (ref == GUIDE) ref = p.syncId;
        if (ref == MAIN) {
            long[] at = p.at(t);
            int i = (int) (at[1] >= 0 && at[2] >= 500000 ? at[1] : at[0]);
            if (i >= 0 && !p.clips.get(i).hasAudio && at[1] >= 0) i = (int) (i == at[0] ? at[1] : at[0]);
            if (i < 0) return null;
            return clip(p.clips.get(i), p.start(i), p.clips.get(i).dur(), null);
        }
        if (ref <= 0) return null;
        VProject.AudioItem a = p.audioById(ref);
        if (a != null) {
            Source s = new Source(); s.audio = a; s.uri = a.uri; s.name = a.name; s.start = a.start; s.dur = a.dur();
            return s;
        }
        VProject.Clip c = p.clipById(ref);
        if (c != null) return clip(c, p.start(p.clipIndex(ref)), c.dur(), null);
        for (VProject.TextItem ti : p.texts)
            if (ti.media != null && ti.media.id == ref) return clip(ti.media, ti.start, ti.dur, ti);
        return null;
    }

    private static Source clip(VProject.Clip c, long start, long dur, VProject.TextItem ti) {
        if (c.kind != VProject.VIDEO || !c.hasAudio) return null;
        Source s = new Source(); s.clip = c; s.overlay = ti; s.uri = c.uri; s.name = c.name; s.start = start; s.dur = dur;
        return s;
    }

    static Music music(VProject p, Source s) {
        if (s == null) return null;
        AudioFeatures fx = p.controlFx.get(s.uri);
        if (fx == null && s.uri.equals(p.songFxUri)) fx = p.songFx;
        float[] beats = beats(p, s.uri);
        if (fx == null && beats.length == 0) return null;
        Music m = p.controlMusic.get(s.uri);
        if (m == null || m.fx != fx || m.sourceBeats != beats) {
            m = new Music(fx, beats);
            p.controlMusic.put(s.uri, m);
        }
        return m;
    }

    static float[] beats(VProject p, String uri) {
        Beats.Result b = p.controlBeats.get(uri);
        if (b != null) return b.beats;
        return uri.equals(p.beatsUri) ? p.beats : VProject.NO_BEATS;
    }

    static float drive(VProject p, int ref, int owner, int feature, boolean adaptive, boolean post, long t) {
        Source s = resolve(p, ref, owner, t);
        if (s == null || t < s.start || t >= s.start + s.dur) return 0;
        Music m = music(p, s);
        if (m == null) return 0;
        int f = feature == 0 ? Music.BEAT : feature == 1 ? Music.BASS : Music.LEVEL;
        return m.follow(m.code(f, adaptive), s.at(t), VProject.RELEASE, 0) * (post ? s.volume(p, t) : 1);
    }

    static String name(VProject p, int ref, int owner) {
        if (ref == OFF) return "Off";
        if (ref == MAIN) return "Main video audio";
        if (ref == GUIDE) return p.syncItem() == null ? "Beat guide (not selected)" : "Beat guide: " + p.syncItem().name;
        if (ref == SELF && owner == 0) return "Main video audio";
        Source s = resolve(p, ref, owner, 0);
        return s == null ? "Source unavailable" : s.name;
    }

    /** Only analyze files actually read by an enabled reaction/link, plus the selected guide. */
    static LinkedHashSet<String> required(VProject p) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (p.syncItem() != null) out.add(p.syncItem().uri);
        for (VProject.Clip c : p.clips) {
            if (c.pulse > 0 || c.shake > 0 || c.flash > 0) collect(p, out, c.reactAudio, c.id);
            links(p, out, c.grade, c.id);
        }
        for (VProject.TextItem ti : p.texts) {
            int owner = ti.media != null ? ti.media.id : 0;
            if (ti.pulse > 0 || ti.shake > 0 || ti.flash > 0) collect(p, out, ti.reactAudio, owner);
            if (ti.media != null) links(p, out, ti.media.grade, owner);
        }
        links(p, out, p.grade, 0); links(p, out, p.pgrade, 0);
        return out;
    }

    private static void links(VProject p, LinkedHashSet<String> out, java.util.ArrayList<Layer> layers, int owner) {
        for (Layer l : layers) if (l.visible) for (Music.Link k : l.links)
            if (k.enabled) collect(p, out, k.audio, owner);
    }

    private static void collect(VProject p, LinkedHashSet<String> out, int ref, int owner) {
        if (ref == SELF) ref = owner > 0 ? owner : MAIN;
        if (ref == MAIN) {
            for (VProject.Clip c : p.clips) if (c.kind == VProject.VIDEO && c.hasAudio) out.add(c.uri);
        } else {
            Source s = resolve(p, ref, owner, 0);
            if (s != null) out.add(s.uri);
        }
    }
}
