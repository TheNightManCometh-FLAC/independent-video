package com.christopher.strata;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 3.15 masks on video effects: the strokes model (MaskPaint) and its JSON, shapes keyed over a
 * clip, music driving a mask shape, link targets following stack edits, and the format bump.
 * No Android UI runtime required (masks are redrawn from strokes on the phone only).
 */
public class VideoMaskTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static AudioFeatures features(float value) {
        AudioFeatures f = new AudioFeatures();
        f.frames = 2001;
        f.bass = new float[f.frames]; f.level = new float[f.frames];
        Arrays.fill(f.bass, value); Arrays.fill(f.level, value);
        f.bands = new byte[f.frames * AudioFeatures.BANDS];
        f.wave = new byte[AudioFeatures.WAVE_RATE * 20];
        return f;
    }

    public static void main(String[] args) throws Exception {
        VProject p = new VProject();
        VProject.Clip c = new VProject.Clip();
        c.id = 1; c.uri = "main"; c.name = "Main"; c.hasAudio = true; c.srcW = 1920; c.srcH = 1080; c.out = c.srcDur = 10000000;
        p.clips.add(c);
        p.nextId = 20;
        int fmt0 = p.formatVersion();

        // an effect with a mask: strokes, a fill and two shapes, one keyed
        Layer fx = new Layer();
        fx.id = 5; fx.type = Layer.EFFECT; fx.fx = Fx.GAUSS;
        fx.paint = new ArrayList<MaskPaint.Op>();
        fx.paint.add(new MaskPaint.Op(MaskPaint.REVEAL_ALL, null, 0, 0, 0, false));
        fx.paint.add(new MaskPaint.Op(MaskPaint.STROKE, new float[]{0.1f, 0.2f, 0.3f, 0.25f, 0.5f, 0.4f}, 0.05f, 0.6f, 0.8f, true));
        int[] sz = MaskPaint.size(p.outW(), p.outH());
        fx.vmW = sz[0]; fx.vmH = sz[1];
        check("video masks are at most 1920 px on the long side, in the frame's shape (" + sz[0] + "x" + sz[1] + ")",
                Math.max(sz[0], sz[1]) <= 1920 && Math.abs(sz[0] / (float) sz[1] - p.outW() / (float) p.outH()) < 0.01f);
        MaskStack.Comp t = MaskPanel.make("star", 400, 300, 200, null);
        t.keys.set(0, t.vec(), 1000);
        float[] v = t.vec(); v[MaskStack.K_X0] = 1400; v[MaskStack.K_ANG] = 180; v[MaskStack.K_REVEAL] = 0.25f;
        t.keys.set(4000000, v, 1000);
        fx.comps.add(t);
        MaskStack.Comp r = MaskPanel.make("rounded", 960, 540, 600, null);
        r.op = MaskStack.INTERSECT;
        fx.comps.add(r);
        fx.paintAt = 1; fx.paintOp = MaskStack.SUB;
        c.grade.add(fx);
        check("a project with an effect mask is saved as format 18 (older apps refuse it instead of dropping it)",
                p.formatVersion() == 18 && fmt0 < 18);

        // JSON keeps all of it
        Layer back = Layer.fromJson(fx.toJson());
        check("strokes survive JSON (" + back.paint.size() + " ops)", back.paint.size() == 2 && back.paint.get(1).erase
                && Math.abs(back.paint.get(1).pts[4] - 0.5f) < 1e-4 && back.paint.get(0).kind == MaskPaint.REVEAL_ALL);
        check("mask size survives JSON", back.vmW == fx.vmW && back.vmH == fx.vmH);
        check("shapes, keys, place and op survive JSON", MaskStack.key(back.comps, back.paintOp, back.paintAt).equals(MaskStack.key(fx.comps, fx.paintOp, fx.paintAt))
                && back.comps.get(0).keys.size() == 2);
        Layer copy = fx.copy();
        copy.paint.add(new MaskPaint.Op(MaskPaint.INVERT, null, 0, 0, 0, false));
        copy.comps.get(0).keys.list.get(1).v[MaskStack.K_X0] = 0;
        check("copies (history) own their stroke list and keys", fx.paint.size() == 2 && fx.comps.get(0).keys.list.get(1).v[MaskStack.K_X0] == 1400);
        Layer photo = Layer.fromJson(new Layer().toJson());
        check("a photo layer has no stroke list (its masks stay PNG files)", photo.paint == null);

        // damaged strokes are dropped, not fatal
        org.json.JSONArray bad = new org.json.JSONArray();
        org.json.JSONObject b1 = new org.json.JSONObject(); b1.put("k", 9); bad.put(b1);
        org.json.JSONObject b2 = new org.json.JSONObject(); b2.put("k", 0); org.json.JSONArray pa = new org.json.JSONArray(); pa.put(Double.NaN); pa.put(0.5); b2.put("p", pa); bad.put(b2);
        org.json.JSONObject b3 = new org.json.JSONObject(); b3.put("k", 0); org.json.JSONArray pb = new org.json.JSONArray(); pb.put(0.2); pb.put(0.5); b3.put("p", pb); b3.put("s", 99.0); bad.put(b3);
        ArrayList<MaskPaint.Op> got = MaskPaint.fromJson(bad);
        check("unknown op and NaN points dropped, a huge brush clamped", got.size() == 1 && got.get(0).size <= 2);

        // keys: evaluated per clip time by the compositor's path
        Layer at0 = MaskStack.at(fx, 0), at2 = MaskStack.at(fx, 2000000), at9 = MaskStack.at(fx, 9000000);
        check("at the first key the star is where it started", Math.abs(at0.comps.get(0).x0 - 400) < 1e-3);
        check("halfway it has travelled and turned (" + at2.comps.get(0).x0 + ", " + at2.comps.get(0).angle + "°)",
                at2.comps.get(0).x0 > 800 && at2.comps.get(0).x0 < 1000 && at2.comps.get(0).angle > 60 && at2.comps.get(0).angle < 120);
        check("after the last key it holds (reveal 25%)", Math.abs(at9.comps.get(0).reveal - 0.25f) < 1e-4);
        check("the unkeyed shape is untouched", at2.comps.get(1).x0 == 960);
        check("evaluating never changes the project", fx.comps.get(0).x0 == 400 && fx.comps.get(0).angle == 0);

        // music: Size follows the level
        p.controlFx.put("main", features(1));
        ArrayList<Music.Target> tg = Music.targets(fx);
        Music.Target size = null, mx = null, rev = null;
        for (Music.Target x : tg) {
            if (x.id == Music.T_MASK + Music.M_SIZE) size = x;
            if (x.id == Music.T_MASK + Music.M_X) mx = x;
            if (x.id == Music.T_MASK + 16 + Music.M_REVEAL) rev = x;
        }
        check("every shape offers Size, Move, Rotation, Reveal... as music targets", size != null && mx != null && rev != null && size.name.contains("Star"));
        Music.Link k = Music.link(Music.T_MASK + Music.M_SIZE, Music.LEVEL, Music.FOLLOW, 0.5f, .02f, 0, 1);
        k.audio = AudioControl.SELF;
        fx.links.add(k);
        Layer driven = Music.at(MaskStack.at(fx, 1000000), p, 1000000, c.id, 0, c.dur());
        float base = MaskStack.at(fx, 1000000).comps.get(0).x1;
        check("level 1 x amount 0.5 makes the star 50% bigger (" + base + " -> " + driven.comps.get(0).x1 + ")",
                Math.abs(driven.comps.get(0).x1 - base * 1.5f) < 0.5f);
        check("the music works on a private copy", fx.comps.get(0).x1 == t.x1);
        Music.Link mv = Music.link(Music.T_MASK + Music.M_X, Music.LEVEL, Music.FOLLOW, 100, .02f, 0, 1);
        mv.audio = AudioControl.SELF;
        fx.links.clear(); fx.links.add(mv);
        Layer moved = Music.at(fx.copy(), p, 0, c.id, 0, c.dur());
        check("Move across shifts the shape by the amount in mask px", Math.abs(moved.comps.get(0).x0 - 500) < 0.5f);

        // a reveal driven by the music stays in 0..1
        Music.Link rv = Music.link(Music.T_MASK + 16 + Music.M_REVEAL, Music.LEVEL, Music.FOLLOW, -3, .02f, 0, 1);
        rv.audio = AudioControl.SELF;
        fx.links.clear(); fx.links.add(rv);
        Layer rl = Music.at(fx.copy(), p, 0, c.id, 0, c.dur());
        check("reveal is clamped to 0..1", rl.comps.get(1).reveal == 0);

        // freehand and library shapes made the way the editors make them
        MaskStack.Comp heart = MaskPanel.make("heart", 100, 100, 200, null);
        check("library shapes keep their own proportions", heart.type == MaskStack.SHAPE && Math.abs(heart.x1 / heart.y1 - MaskShapes.aspect("heart")) < 0.01f);
        MaskStack.Comp el = MaskPanel.make("ellipse", 0, 0, 100, null);
        check("Ellipse is a polygon with " + MaskStack.ELLIPSE_SIDES + " sides (named Ellipse)", el.type == MaskStack.POLY && el.name(0).startsWith("Ellipse"));
        float err = 0;
        for (int i = 0; i < 360; i += 3) {
            double a = Math.toRadians(i);
            float d = el.sdf((float) (el.x1 * Math.cos(a)), (float) (el.y1 * Math.sin(a)));
            err = Math.max(err, Math.abs(d));
        }
        check("the 64-gon is within 0.2 px of the true ellipse at this size (" + err + ")", err < 0.2f);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
