package com.christopher.strata;

/** 3.13: a transition on every cut, and the project default for new cuts. */
public class TransitionTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    static VProject project(int n) {
        VProject p = new VProject();
        for (int i = 0; i < n; i++) {
            VProject.Clip c = new VProject.Clip();
            c.id = i + 1; c.name = "c" + (i + 1); c.uri = "content://" + i; c.kind = VProject.VIDEO;
            c.in = 0; c.out = 4000000; c.srcDur = 10000000; c.speed = 1;
            p.clips.add(c);
        }
        p.nextId = n + 1;
        return p;
    }

    public static void main(String[] args) {
        VProject p = project(4);
        long before = p.duration();
        int[] r = Edits.transitionToAll(p, 1, 500000);
        check("Every cut gets the dissolve", r[0] == 3 && r[1] == 0 && p.clips.get(0).trans == 1 && p.clips.get(2).trans == 1);
        check("The last clip has no cut after it", p.clips.get(3).trans == 0);
        check("Overlaps shorten the video by three transitions", p.duration() == before - 3 * 500000);
        int[] again = Edits.transitionToAll(p, 1, 500000);
        check("Applying the same again changes nothing", again[0] == 0 && again[1] == 0);
        int[] back = Edits.transitionToAll(p, 0, 500000);
        check("Plain cuts everywhere restores the length", back[0] == 3 && p.duration() == before);

        // a locked first clip: its own cut is protected, later cuts don't move it
        VProject q = project(4);
        TimelineLocks.set(q, 1, true);
        VProject q0 = q.copy();
        int[] rq = Edits.transitionToAll(q, 2, 400000);
        check("Locked clip's own transition is left alone", q.clips.get(0).trans == 0 && rq[1] >= 1);
        check("Cuts after it still change", q.clips.get(1).trans == 2 && q.clips.get(2).trans == 2 && rq[0] == 2);
        check("The result passes the central lock check", TimelineLocks.violation(q0, q) == null);

        // a locked last clip: any earlier overlap would move it, so every cut is skipped
        VProject z = project(3);
        TimelineLocks.set(z, 3, true);
        long zStart = z.starts()[2];
        int[] rz = Edits.transitionToAll(z, 1, 500000);
        check("Cuts that would move a locked clip are skipped", rz[0] == 0 && rz[1] == 2 && z.starts()[2] == zStart);

        // default for new cuts
        VProject d = project(2);
        VProject.Clip nc = new VProject.Clip();
        nc.id = d.nextId++; nc.uri = "content://new"; nc.kind = VProject.VIDEO; nc.in = 0; nc.out = 3000000; nc.srcDur = 3000000; nc.speed = 1;
        check("No default: adding a clip leaves plain cuts", Edits.defaultTransition(d, 1) == 0);
        d.defTrans = 1; d.defTransDur = 300000;
        int at = Edits.insert(d, 4000000, nc, Timeline.frame(30));
        int n = Edits.defaultTransition(d, nc.id);
        check("Inserted between two clips: both new cuts get the default", n == 2 && d.clips.get(at - 1).trans == 1 && d.clips.get(at).trans == 1 && d.clips.get(at).transDur == 300000);
        VProject e = project(2);
        e.defTrans = 2; e.defTransDur = 600000;
        e.clips.get(0).trans = 3; e.clips.get(0).transDur = 900000;
        VProject.Clip tail = new VProject.Clip();
        tail.id = e.nextId++; tail.uri = "content://t"; tail.kind = VProject.VIDEO; tail.in = 0; tail.out = 2000000; tail.srcDur = 2000000; tail.speed = 1;
        e.clips.add(tail);
        Edits.defaultTransition(e, tail.id);
        check("Appended clip: the cut before it gets the default", e.clips.get(1).trans == 2 && e.clips.get(1).transDur == 600000);
        check("An existing transition is kept", e.clips.get(0).trans == 3 && e.clips.get(0).transDur == 900000);
        check("A clip with nothing after it gets no outgoing transition", e.clips.get(2).trans == 0);
        System.out.println(count + " passed, 0 failed");
    }
}
