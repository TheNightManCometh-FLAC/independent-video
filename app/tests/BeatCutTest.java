package com.christopher.strata;

/** 3.14: new transition types (format), and rolling a cut onto the nearest beat. */
public class BeatCutTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    static VProject project() {
        VProject p = new VProject();
        for (int i = 0; i < 3; i++) {
            VProject.Clip c = new VProject.Clip();
            c.id = i + 1; c.name = "c" + i; c.uri = "content://v" + i; c.kind = VProject.VIDEO;
            c.in = 1000000; c.out = 5000000; c.srcDur = 10000000; c.speed = 1;
            p.clips.add(c);
        }
        VProject.AudioItem a = new VProject.AudioItem();
        a.id = 10; a.uri = "content://song"; a.name = "song"; a.start = 0; a.in = 0; a.out = 60000000; a.srcDur = 60000000;
        p.audio.add(a);
        p.syncId = 10; p.beatsUri = a.uri;
        p.beats = new float[]{0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 3.62f, 4.5f, 5.0f};
        p.nextId = 20;
        return p;
    }

    public static void main(String[] args) {
        check("19 transition types, old ids unchanged", VProject.TRANS.length == 19 && VProject.TRANS[1].equals("Dissolve") && VProject.TRANS[4].startsWith("Wipe"));
        int n = 0; for (int[] g : VProject.TRANS_GROUP_TYPES) n += g.length;
        check("Every type is in exactly one picker group", n == VProject.TRANS.length);
        VProject f = project();
        check("Old transitions keep the old format", f.formatVersion() == 14);
        f.clips.get(0).trans = 14;
        check("A 3.14 transition bumps the format to 17 (3.13 would draw it as a wipe)", f.formatVersion() == 17);
        VProject d = project(); d.defTrans = 16;
        check("A 3.14 default transition bumps it too", d.formatVersion() == 17);

        // a plain cut at 4.0 s; nearest beat 3.62 s
        VProject p = project();
        long cut = p.start(1);
        check("Setup: the first cut is at 4 s", cut == 4000000);
        VProject before = p.copy();
        long at = VideoEditor.centerOnBeat(before, p, 0);
        long F = Timeline.frame(p.fps());
        check("A plain cut moves onto the nearest beat (3.62 s)", Math.abs(at - 3620000) <= 1 && Math.abs(p.start(1) - 3620000) <= F);
        check("Roll keeps the total length", p.duration() == before.duration());

        // a 0.5 s dissolve: its middle should land on the beat
        VProject q = project();
        q.clips.get(0).trans = 1; q.clips.get(0).transDur = 500000;
        VProject qb = q.copy();
        long mid0 = q.start(0) + q.clips.get(0).dur() - 250000;
        long qat = VideoEditor.centerOnBeat(qb, q, 0);
        long mid1 = q.start(0) + q.clips.get(0).dur() - 250000;
        check("A dissolve's middle lands on the beat", qat >= 0 && Math.abs(mid1 - qat) <= F && Math.abs(qat - mid0) < Math.abs(3000000 - mid0) + 1);

        // no beats: nothing happens
        VProject r = project(); r.syncId = 0;
        VProject rb = r.copy();
        check("Sync off: no beats, no change", VideoEditor.centerOnBeat(rb, r, 0) == -1 && r.start(1) == rb.start(1));

        // a beat the media can't reach: the first clip has no footage before In 1 s... make the
        // incoming clip start at its very first frame so the cut can't move earlier
        VProject s = project();
        s.clips.get(1).in = 0; s.clips.get(1).out = 4000000;
        s.beats = new float[]{3.0f};
        VProject sb = s.copy();
        long sat = VideoEditor.centerOnBeat(sb, s, 0);
        check("Out of reach: reported, not faked", sat == -1 || Math.abs(s.start(1) - sat) <= F);
        System.out.println(count + " passed, 0 failed");
    }
}
