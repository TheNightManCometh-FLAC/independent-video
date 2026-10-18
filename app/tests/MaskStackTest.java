package com.christopher.strata;

import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * MaskStack: the component model, flatten (the shader's Java twin), frame transforms and JSON.
 * With a folder argument it also writes parity scenes (raster, Java result, shader uniforms)
 * that tests/maskparity.py renders through common.glsl's maskAt and diffs.
 */
public class MaskStackTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
    static boolean near(float a, float b, float e) { return Math.abs(a - b) <= e; }

    static MaskStack.Comp lin(int op, float x0, float y0, float x1, float y1) {
        MaskStack.Comp c = new MaskStack.Comp();
        c.type = MaskStack.LINEAR; c.op = op; c.x0 = x0; c.y0 = y0; c.x1 = x1; c.y1 = y1;
        return c;
    }
    static MaskStack.Comp rad(int op, float cx, float cy, float rx, float ry, float ang, float fe) {
        MaskStack.Comp c = new MaskStack.Comp();
        c.type = MaskStack.RADIAL; c.op = op; c.x0 = cx; c.y0 = cy; c.x1 = rx; c.y1 = ry; c.angle = ang; c.feather = fe;
        return c;
    }
    static byte[] disc(int w, int h, float cx, float cy, float r) {
        byte[] b = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
            b[y * w + x] = (byte) Math.round(255 * Math.max(0, Math.min(1, (r - d) / 12 + 0.5)));
        }
        return b;
    }
    static float at(byte[] b, int w, int x, int y) { return (b[y * w + x] & 255) / 255f; }

    public static void main(String[] args) throws Exception {
        int W = 256, H = 192;
        byte[] r = disc(W, H, 70, 96, 50);
        ArrayList<MaskStack.Comp> none = new ArrayList<MaskStack.Comp>();
        byte[] f0 = MaskStack.flatten(r, W, H, MaskStack.SUB, 3, none);
        check("no shapes: the mask is exactly the painted raster (3.5 masks unchanged)", java.util.Arrays.equals(f0, r));

        // the Lightroom workflow: a linear gradient over the sky, minus a brush on the mountain
        ArrayList<MaskStack.Comp> sky = new ArrayList<MaskStack.Comp>();
        sky.add(lin(MaskStack.ADD, 0, 0, 0, H));               // full at the top, none at the bottom
        byte[] f1 = MaskStack.flatten(r, W, H, MaskStack.SUB, 1, sky);
        check("gradient minus brush: top of the frame away from the paint is ~full (" + at(f1, W, 200, 2) + ")", at(f1, W, 200, 2) > 0.98f);
        check("gradient minus brush: inside the painted disc it's cut to 0", at(f1, W, 70, 60) < 0.01f);
        check("gradient minus brush: bottom is empty", at(f1, W, 200, H - 1) < 0.01f);
        check("gradient at mid-height is 0.5", near(at(f1, W, 200, H / 2), 0.5f, 0.02f));
        byte[] f1b = MaskStack.flatten(r, W, H, MaskStack.SUB, 0, sky);
        check("order matters: the same brush below the gradient subtracts from nothing, the gradient wins", at(f1b, W, 70, 60) > 0.6f);

        // radial
        ArrayList<MaskStack.Comp> rr = new ArrayList<MaskStack.Comp>();
        rr.add(rad(MaskStack.ADD, 128, 96, 80, 40, 0, 0.5f));
        byte[] blank = new byte[W * H];
        byte[] f2 = MaskStack.flatten(blank, W, H, MaskStack.ADD, 0, rr);
        check("radial: full in the inner half (feather 0.5)", at(f2, W, 128 + 30, 96) > 0.99f);
        check("radial: none outside its edge", at(f2, W, 128 + 82, 96) < 0.01f && at(f2, W, 128, 96 + 42) < 0.01f);
        rr.get(0).angle = 90;
        byte[] f2r = MaskStack.flatten(blank, W, H, MaskStack.ADD, 0, rr);
        check("radial turned 90°: long axis is vertical", at(f2r, W, 128, 96 + 70) > 0.1f && at(f2r, W, 128 + 70, 96) < 0.01f);
        rr.get(0).angle = 0; rr.get(0).feather = 0;
        byte[] f2h = MaskStack.flatten(blank, W, H, MaskStack.ADD, 0, rr);
        check("feather 0: a hard edge (1 just inside, 0 just outside)", at(f2h, W, 128 + 78, 96) == 1f && at(f2h, W, 128 + 81, 96) == 0f);

        // ops, invert, amount
        MaskStack.Comp a = lin(MaskStack.ADD, 0, 0, W, 0);
        float v = a.value(W / 4f, 10);
        check("linear value follows smoothstep (0.844 at a quarter)", near(v, 1 - MaskStack.smoothstep(0, 1, 0.25f), 1e-5f));
        a.invert = true; a.amount = 0.5f;
        check("invert then amount: 0.5 x (1 - shape)", near(a.value(W / 4f, 10), 0.5f * (1 - (1 - MaskStack.smoothstep(0, 1, 0.25f))), 1e-5f));
        check("Add = max, Subtract = m(1-v), Overlap = m·v",
                MaskStack.combine(0.3f, 0.6f, MaskStack.ADD) == 0.6f && near(MaskStack.combine(0.8f, 0.25f, MaskStack.SUB), 0.6f, 1e-6f)
                        && near(MaskStack.combine(0.8f, 0.5f, MaskStack.INTERSECT), 0.4f, 1e-6f));

        // linking a mask moves the shapes into the new frame without changing what they cover
        ArrayList<MaskStack.Comp> t = new ArrayList<MaskStack.Comp>();
        t.add(rad(MaskStack.ADD, 100, 80, 60, 25, 30, 0.4f));
        t.add(lin(MaskStack.SUB, 20, 10, 180, 150));
        double ang = Math.toRadians(25), k = 1.7;
        double[] m = {k * Math.cos(ang), -k * Math.sin(ang), 40, k * Math.sin(ang), k * Math.cos(ang), -12};
        ArrayList<MaskStack.Comp> t2 = MaskStack.copyAll(t);
        MaskStack.transform(t2, m);
        float worst = 0;
        int mid = 0;
        for (int i = 0; i < 2000; i++) {
            float x = (float) (Math.random() * W), y = (float) (Math.random() * H);
            float[] q = MaskStack.apply(m, x, y);
            float a0 = MaskStack.eval(0f, MaskStack.ADD, 2, t, x - 0.5f, y - 0.5f);   // no paint: the shapes alone
            float a1 = MaskStack.eval(0f, MaskStack.ADD, 2, t2, q[0] - 0.5f, q[1] - 0.5f);
            worst = Math.max(worst, Math.abs(a0 - a1));
            if (a0 > 0.05f && a0 < 0.95f) mid++;
        }
        check("shapes carried through a rotate + scale + move frame change cover the same points (max diff " + worst + ", " + mid + " of 2000 samples in soft edges)", worst < 1e-3f && mid > 100);

        // JSON
        ArrayList<MaskStack.Comp> j = new ArrayList<MaskStack.Comp>();
        for (MaskStack.Comp c : t) j.add(MaskStack.Comp.fromJson(c.toJson()));
        check("JSON round trip", MaskStack.key(j, 1, 2).equals(MaskStack.key(t, 1, 2)));
        MaskStack.Comp bad = lin(7, Float.NaN, 0, 1, 1);
        check("a damaged shape is dropped", !bad.sanitize());
        MaskStack.Comp fix = rad(9, 10, 10, -5, 0, 0, 3);
        check("a fixable shape is repaired (op, radii, feather)", fix.sanitize() && fix.op == MaskStack.ADD && fix.x1 == 5 && fix.y1 == 0.5f && fix.feather == 1);

        // Layer integration: copy is deep, JSON keeps the stack
        Layer L = new Layer();
        L.comps.addAll(MaskStack.copyAll(t)); L.paintOp = MaskStack.SUB; L.paintAt = 1;
        Layer C = L.copy();
        C.comps.get(0).x0 = 999;
        check("Layer.copy deep-copies shapes (history can't be edited through the live layer)", L.comps.get(0).x0 == 100);
        Layer R = Layer.fromJson(L.toJson());
        check("Layer JSON keeps shapes, paint op and place", MaskStack.key(R.comps, R.paintOp, R.paintAt).equals(MaskStack.key(L.comps, L.paintOp, L.paintAt)));
        Layer plain = Layer.fromJson(new Layer().toJson());
        check("an old layer loads with no shapes, paint Add at the bottom", plain.comps.isEmpty() && plain.paintOp == MaskStack.ADD && plain.paintAt == 0);

        if (args.length > 0) scenes(args[0], W, H, r);
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** Parity scenes for the shader: raster.pgm, expect_N.pgm and uniforms_N.txt. */
    static void scenes(String dir, int W, int H, byte[] r) throws Exception {
        pgm(dir + "/raster.pgm", r, W, H);
        ArrayList<ArrayList<MaskStack.Comp>> sc = new ArrayList<ArrayList<MaskStack.Comp>>();
        int[][] paint = {{MaskStack.SUB, 1}, {MaskStack.ADD, 0}, {MaskStack.INTERSECT, 6}, {MaskStack.ADD, 2}};
        ArrayList<MaskStack.Comp> s0 = new ArrayList<MaskStack.Comp>();
        s0.add(lin(MaskStack.ADD, 0, 0, 0, H));
        s0.add(rad(MaskStack.ADD, 190, 140, 50, 28, -35, 0.6f));
        sc.add(s0);
        ArrayList<MaskStack.Comp> s1 = new ArrayList<MaskStack.Comp>();
        MaskStack.Comp i1 = rad(MaskStack.SUB, 128, 96, 90, 60, 15, 0.3f); i1.invert = true;
        s1.add(i1);
        MaskStack.Comp l1 = lin(MaskStack.ADD, 250, 10, 120, 150); l1.amount = 0.6f;
        s1.add(l1);
        sc.add(s1);
        ArrayList<MaskStack.Comp> s2 = new ArrayList<MaskStack.Comp>();
        for (int i = 0; i < 6; i++) s2.add(i % 2 == 0 ? rad(i % 3, 40 + i * 35, 60 + (i % 3) * 40, 30 + i * 4, 20, i * 20, i / 6f)
                : lin(i % 3, i * 30, 0, 256 - i * 20, 192));
        s2.get(0).op = MaskStack.ADD;
        sc.add(s2);
        ArrayList<MaskStack.Comp> s3 = new ArrayList<MaskStack.Comp>();
        s3.add(rad(MaskStack.ADD, 128, 96, 100, 70, 0, 0f));
        s3.add(lin(MaskStack.INTERSECT, 0, 192, 256, 0));
        sc.add(s3);
        for (int n = 0; n < sc.size(); n++) {
            ArrayList<MaskStack.Comp> cs = sc.get(n);
            pgm(dir + "/expect_" + n + ".pgm", MaskStack.flatten(r, W, H, paint[n][0], paint[n][1], cs), W, H);
            StringBuilder u = new StringBuilder();
            u.append("uMaskN=").append(cs.size()).append(" uPaintOp=").append(paint[n][0]).append(" uPaintAt=").append(paint[n][1]);
            for (int k = 0; k < cs.size(); k++) {
                MaskStack.Comp c = cs.get(k);
                double ag = Math.toRadians(c.angle);
                u.append(" uMA[").append(k).append("]=").append(c.type).append(',').append(c.op).append(',').append(c.invert ? 1 : 0).append(',').append(c.amount);
                u.append(" uMB[").append(k).append("]=").append(c.x0).append(',').append(c.y0).append(',').append(c.x1).append(',').append(c.y1);
                u.append(" uMC[").append(k).append("]=").append((float) Math.cos(ag)).append(',').append((float) Math.sin(ag)).append(',').append(c.feather).append(",0");
            }
            FileOutputStream o = new FileOutputStream(dir + "/uniforms_" + n + ".txt");
            o.write(u.toString().getBytes("UTF-8"));
            o.close();
        }
    }

    static void pgm(String path, byte[] b, int w, int h) throws Exception {
        FileOutputStream o = new FileOutputStream(path);
        o.write(("P5\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
        o.write(b);
        o.close();
    }
}
