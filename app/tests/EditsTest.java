package com.christopher.strata;

/** Edits: three-point insert / overwrite / replace on the main track, with connected items. */
public class EditsTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static VProject.Clip clip(VProject p, String uri, long in, long out, long srcDur) {
        VProject.Clip c = new VProject.Clip();
        c.kind = VProject.VIDEO; c.uri = uri; c.name = uri; c.in = in; c.out = out; c.srcDur = srcDur;
        c.srcW = 1920; c.srcH = 1080;
        c.id = p.nextId++;
        return c;
    }

    static VProject proj() {
        VProject p = new VProject();
        p.fpsIdx = 2;   // 30 fps
        p.nextId = 1;
        p.clips.add(clip(p, "A", 0, 4000000, 10000000));
        p.clips.add(clip(p, "B", 1000000, 4000000, 10000000));
        p.clips.add(clip(p, "C", 0, 5000000, 5000000));
        return p;
    }

    static String layout(VProject p) {
        StringBuilder b = new StringBuilder();
        long[] s = p.starts();
        for (int i = 0; i < p.clips.size(); i++) {
            VProject.Clip c = p.clips.get(i);
            b.append(c.uri).append('[').append(c.in / 1000).append('-').append(c.out / 1000).append("]@").append(s[i] / 1000).append(' ');
        }
        return b.toString().trim();
    }

    public static void main(String[] a) {
        long F = Timeline.frame(30);

        // insert in the middle of A: A splits, the new clip goes between, B and C move 2 s later
        VProject p = proj();
        VProject.Clip src = new VProject.Clip(); src.kind = VProject.VIDEO; src.uri = "S"; src.srcDur = 8000000;
        VProject.Clip n = Edits.fromSource(src, 3000000, 5000000);
        long before = p.duration();
        int at = Edits.insert(p, 1500000, n, F);
        check("insert at 1.5 s: " + layout(p), layout(p).equals("A[0-1500]@0 S[3000-5000]@1500 A[1500-4000]@3500 B[1000-4000]@6000 C[0-5000]@9000") && at == 1);
        check("insert makes the track exactly 2 s longer", p.duration() == before + 2000000);

        // insert on a cut and at the end
        p = proj();
        Edits.insert(p, 4000000, Edits.fromSource(src, 0, 1000000), F);
        check("insert on the A|B cut: nothing is split (" + layout(p) + ")", layout(p).equals("A[0-4000]@0 S[0-1000]@4000 B[1000-4000]@5000 C[0-5000]@8000"));
        p = proj();
        Edits.insert(p, 4000000 + F / 3, Edits.fromSource(src, 0, 1000000), F);
        check("insert a third of a frame after a cut uses the cut, no 1-frame sliver", p.clips.size() == 4 && p.clips.get(1).uri.equals("S"));
        p = proj();
        Edits.insert(p, p.duration() + 500000, Edits.fromSource(src, 0, 1000000), F);
        check("insert past the end appends", p.clips.get(3).uri.equals("S") && p.start(3) == 12000000);

        // overwrite
        p = proj();
        before = p.duration();
        Edits.overwrite(p, 3000000, Edits.fromSource(src, 0, 2000000), F);
        check("overwrite 2 s at 3 s: " + layout(p), layout(p).equals("A[0-3000]@0 S[0-2000]@3000 B[2000-4000]@5000 C[0-5000]@7000"));
        check("overwrite keeps the length and everything after the range in place", p.duration() == before && p.start(3) == 7000000);
        p = proj();
        Edits.overwrite(p, 3000000, Edits.fromSource(src, 0, 5000000), F);
        check("overwrite spanning a whole clip removes it: " + layout(p), layout(p).equals("A[0-3000]@0 S[0-5000]@3000 C[1000-5000]@8000"));
        p = proj();
        Edits.overwrite(p, 10000000, Edits.fromSource(src, 0, 4000000), F);
        check("overwrite running past the end lengthens the track to its end", p.duration() == 14000000 && p.clips.size() == 4);

        // a speed-changed clip split by an insert keeps its source mapping
        p = proj();
        p.clips.get(1).speed = 2f;   // B plays 3 s of footage in 1.5 s
        Edits.insert(p, 4000000 + 750000, Edits.fromSource(src, 0, 1000000), F);
        check("insert inside a 2x clip splits at the right footage (1.5 s in): " + layout(p),
                p.clips.get(1).out == 2500000 && p.clips.get(3).in == 2500000 && p.clips.get(3).speed == 2f);

        // connected title follows its footage on insert, stays put on an overwrite after it
        p = proj();
        VProject.TextItem t = new VProject.TextItem();
        t.id = p.nextId++;
        t.start = 9500000;   // half a second into C
        p.texts.add(t);
        p.syncAnchors();
        p.connect(t.link, t.start, true);
        Edits.insert(p, 1000000, Edits.fromSource(src, 0, 2000000), F);
        p.syncAnchors();
        check("a title connected to C moves 2 s later with it (" + t.start / 1000 + " ms)", t.start == 11500000);

        // replace
        p = proj();
        VProject.Clip b = p.clips.get(1);
        Keys.Key k0 = new Keys.Key(); k0.t = 0; k0.v = new float[]{1, 0, 0, 0, 1}; b.keys.list.add(k0);
        b.trans = 1; b.scale = 1.3f; b.grade.add(new Layer());
        int id = b.id;
        long cStart = p.start(2);
        VProject.Clip r = Edits.fromSource(src, 2000000, 2500000);   // only 0.5 s marked
        boolean fit = Edits.replace(b, r, true);
        check("replace keeping timing: same id, length 3 s, footage from In (" + b.in / 1000 + "-" + b.out / 1000 + ")",
                fit && b.id == id && b.dur() == 3000000 && b.in == 2000000 && b.uri.equals("S"));
        check("replace keeps framing, grade, keys and transition", b.scale == 1.3f && b.grade.size() == 1 && b.keys.list.size() == 1 && b.trans == 1);
        check("C keeps its place after a timing-preserving replace", p.start(2) == cStart);
        r = Edits.fromSource(src, 7000000, 7500000);
        b = proj().clips.get(1);
        fit = Edits.replace(b, r, true);
        check("replace near the end of a short source slides In back to fit (" + b.in / 1000 + "-" + b.out / 1000 + ")", fit && b.out == 8000000 && b.in == 5000000);
        b = proj().clips.get(1);
        VProject.Clip tiny = new VProject.Clip(); tiny.kind = VProject.VIDEO; tiny.uri = "T"; tiny.srcDur = 1000000;
        fit = Edits.replace(b, Edits.fromSource(tiny, 0, 1000000), true);
        check("a source shorter than the clip: reported, uses all of it", !fit && b.in == 0 && b.out == 1000000);
        b = proj().clips.get(1);
        Edits.replace(b, Edits.fromSource(src, 2000000, 2500000), false);
        check("replace with the marked length", b.dur() == 500000);
        b = proj().clips.get(1);
        b.speed = 2f;
        Edits.replace(b, Edits.fromSource(src, 0, 100000), true);
        check("replace on a 2x clip takes twice the footage to keep 1.5 s", b.dur() == 1500000 && b.out - b.in == 3000000);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
