package com.christopher.strata;

import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * 3.15 mask shapes: rectangle, polygon/star, library shapes and freehand outlines through
 * MaskStack (the shader's Java twin) and MaskRaster's distance textures. With a folder argument
 * it writes a contact sheet (sheet.pgm) of every shape and setting, and parity scenes for
 * tests/maskparity.py (raster.pgm, expect_N.pgm, uniforms_N.txt, sdf_N_S.pgm textures).
 */
public class MaskShapesTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static MaskStack.Comp box(int type, float cx, float cy, float hx, float hy) {
        MaskStack.Comp c = new MaskStack.Comp();
        c.type = type; c.x0 = cx; c.y0 = cy; c.x1 = hx; c.y1 = hy; c.feather = 0;
        return c;
    }

    static float val(MaskStack.Comp c, float x, float y) { return c.value(x, y); }

    public static void main(String[] args) throws Exception {
        // rectangle
        MaskStack.Comp r = box(MaskStack.RECT, 100, 100, 60, 30);
        check("rect: inside is 1", val(r, 100, 100) == 1f && val(r, 155, 125) == 1f);
        check("rect: outside is 0", val(r, 162, 100) == 0f && val(r, 100, 132) == 0f);
        check("rect: edge is half (AA centred on the outline)", Math.abs(val(r, 160, 100) - 0.5f) < 0.02f);
        r.round = 1;   // full roundness: the corner radius is the short half size
        check("rect round 1: the corner is cut", val(r, 157, 127) == 0f && val(r, 100, 128) == 1f);
        r.round = 0; r.angle = 90;
        check("rect rotated 90°: tall", val(r, 100, 155) == 1f && val(r, 155, 100) == 0f);
        r.angle = 0; r.feather = 1;   // soft edge 30 px wide centred on the outline
        check("rect feather: 0.5 at the edge, ramps over the short half size", Math.abs(val(r, 160, 100) - 0.5f) < 0.02f && val(r, 145, 100) > 0.97f && val(r, 175, 100) < 0.03f);
        r.feather = 0; r.grow = 0.5f;
        check("rect grow 0.5: edge pushed out by 15 px", val(r, 172, 100) == 1f && val(r, 178, 100) == 0f);
        r.grow = 0; r.outline = 0.4f;
        check("rect outline: only a 12 px band around the edge", val(r, 100, 100) == 0f && val(r, 160, 100) == 1f && val(r, 150, 100) == 0f);
        r.outline = 0; r.reveal = 0.5f;
        check("reveal 0.5 left to right: left half shown, right hidden", val(r, 60, 100) == 1f && val(r, 140, 100) == 0f);
        r.revealMode = 4;
        check("reveal from the centre: middle shown, corners hidden", val(r, 100, 100) == 1f && val(r, 158, 128) == 0f);

        // polygon and star
        MaskStack.Comp p = box(MaskStack.POLY, 100, 100, 50, 50);
        p.sides = 4; p.inner = MaskStack.polyInner(4);
        check("4 sides: a diamond (point up)", val(p, 100, 52) == 1f && val(p, 140, 60) == 0f && val(p, 100, 100) == 1f);
        p.sides = 5; p.inner = 0.38f;
        check("star: the points are in, between the points is out", val(p, 100, 54) == 1f && val(p, 100 + 45 * (float) Math.sin(Math.PI / 5), 100 - 45 * (float) Math.cos(Math.PI / 5)) == 0f && val(p, 100, 100) == 1f);
        float worst = 0;
        p.inner = MaskStack.polyInner(6); p.sides = 6;
        for (int i = 0; i < 3000; i++) {   // the polygon's distance is exact: check against the half-plane form
            float x = (float) (Math.random() * 200), y = (float) (Math.random() * 200);
            float d = p.sdf(x - 100, y - 100);
            double best = -1e9;
            for (int k = 0; k < 6; k++) {
                double a = -Math.PI / 2 + (k + 0.5) * Math.PI / 3, apo = 50 * Math.cos(Math.PI / 6);
                best = Math.max(best, (x - 100) * Math.cos(a) + (y - 100) * Math.sin(a) - apo);
            }
            if (best < 0) worst = Math.max(worst, (float) Math.abs(d - best));
        }
        check("hexagon distance inside matches the half-plane distance (max err " + worst + " px)", worst < 0.05f);

        // library shapes and their distance textures
        for (String id : MaskShapes.IDS) {
            MaskStack.Comp s = box(MaskStack.SHAPE, 100, 100, 50 * Math.min(1, MaskShapes.aspect(id)), 50 / Math.max(1, MaskShapes.aspect(id)));
            s.shape = id;
            long t0 = System.nanoTime();
            MaskRaster.Sdf f = MaskRaster.get(s);
            long ms = (System.nanoTime() - t0) / 1000000;
            boolean ok = f != null && f.w > 0;
            int in = 0;
            if (ok) for (int y = 0; y < 200; y += 2) for (int x = 0; x < 200; x += 2) if (val(s, x, y) > 0.5f) in++;
            check("shape " + id + ": texture " + (ok ? f.w + "x" + f.h : "-") + " in " + ms + " ms, covers " + in + " samples", ok && in > 400 && in < 10000);
        }
        // freehand: a circle drawn by hand ~ a circle
        float[] pts = new float[200];
        for (int i = 0; i < 100; i++) { double t = i * Math.PI * 2 / 100; pts[2 * i] = (float) (300 + 80 * Math.cos(t)); pts[2 * i + 1] = (float) (200 + 80 * Math.sin(t)); }
        float[] fh = MaskShapes.freehand(pts, 0.5f);
        MaskStack.Comp f = box(MaskStack.SHAPE, fh[0], fh[1], fh[2], fh[3]);
        f.pts = new float[fh.length - 4];
        System.arraycopy(fh, 4, f.pts, 0, f.pts.length);
        check("freehand: centre and radius kept", Math.abs(fh[0] - 300) < 1 && Math.abs(fh[2] - 80) < 1);
        float edgeErr = 0;
        for (int i = 0; i < 360; i += 5) {
            double t = Math.toRadians(i);
            float d = f.sdf((float) (80 * Math.cos(t)), (float) (80 * Math.sin(t)));
            edgeErr = Math.max(edgeErr, Math.abs(d));
        }
        check("freehand circle: the distance is ~0 on the drawn line (max " + edgeErr + " px)", edgeErr < 1.0f);
        float far = f.sdf(0, 0);
        check("freehand circle: deep inside is negative (" + far + " px, clamped by the margin)", far < -20);

        // JSON keeps every new field
        p.round = 0.3f; p.grow = -0.2f; p.outline = 0.1f; p.reveal = 0.7f; p.revealMode = 3;
        MaskStack.Comp t = box(MaskStack.TEXT, 50, 60, 70, 20);
        t.text = "Hello\nworld"; t.font = "asset:Inter.ttf"; t.weight = 800; t.italic = true; t.tracking = 0.05f; t.leading = 1.2f; t.align = 0;
        t.keys.set(0, t.vec(), 1000);
        float[] v2 = t.vec(); v2[MaskStack.K_ANG] = 90; v2[MaskStack.K_X0] = 150;
        t.keys.set(2000000, v2, 1000);
        ArrayList<MaskStack.Comp> all = new ArrayList<MaskStack.Comp>();
        all.add(p); all.add(t); all.add(f); all.add(r);
        ArrayList<MaskStack.Comp> back = new ArrayList<MaskStack.Comp>();
        for (MaskStack.Comp c : all) back.add(MaskStack.Comp.fromJson(c.toJson()));
        check("JSON round trip of polygon, text (with keys), freehand and rect", MaskStack.key(back, 0, 0).equals(MaskStack.key(all, 0, 0)));
        check("text fields kept", back.get(1).text.equals("Hello\nworld") && back.get(1).weight == 800 && back.get(1).italic && back.get(1).align == 0);
        for (MaskStack.Comp c : back) check("a loaded " + MaskStack.TYPE_NAMES[c.type] + " is valid", c.sanitize());

        // keyframes
        MaskStack.Comp mid = t.at(1000000);
        check("keyframes: halfway (smooth ease) is between the keys", mid.angle > 30 && mid.angle < 60 && mid.x0 > 70 && mid.x0 < 130);
        check("keyframes: after the last key it holds", t.at(5000000).angle == 90);
        check("keyframes: the base shape isn't changed by evaluating", t.angle == 0);
        check("unanimated shape returns itself", r.at(123) == r);
        Layer L = new Layer();
        L.mask = null;
        L.comps.add(t);
        check("a keyed shape makes at() return a private copy at that time", MaskStack.at(L, 1000000) != L && MaskStack.at(L, 1000000).comps.get(0).angle > 30 && L.comps.get(0).angle == 0);

        // whole-shape edits
        MaskStack.Comp lin = new MaskStack.Comp();
        lin.x0 = 0; lin.y0 = 0; lin.x1 = 100; lin.y1 = 0;
        lin.rotateBy(90);
        check("linear rotate 90 about its middle", Math.abs(lin.x0 - 50) < 1e-3 && Math.abs(lin.y0 + 50) < 1e-3 && Math.abs(lin.x1 - 50) < 1e-3 && Math.abs(lin.y1 - 50) < 1e-3);
        MaskStack.Comp bx = box(MaskStack.RECT, 0, 0, 10, 20);
        bx.scaleBy(2); bx.moveBy(5, 6);
        check("box scale and move", bx.x1 == 20 && bx.y1 == 40 && bx.x0 == 5 && bx.y0 == 6);

        // the uniform packing hands out raster slots in order and drops shapes past the budget
        ArrayList<MaskStack.Comp> many = new ArrayList<MaskStack.Comp>();
        for (int i = 0; i < 6; i++) { MaskStack.Comp s = box(MaskStack.SHAPE, 50, 50, 20, 20); s.shape = MaskShapes.IDS[i]; many.add(s); }
        float[][] u = MaskStack.uniforms(many);
        check("raster slots 0-3, then disabled", u[0][19] == 0 && u[3][19] == 3 && u[4][3] == 0 && u[5][3] == 0);

        if (args.length > 0) { sheet(args[0]); scenes(args[0]); }
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** Every library shape and a grid of settings, 128 px cells. */
    static void sheet(String dir) throws Exception {
        int C = 128, cols = 8;
        ArrayList<MaskStack.Comp> cells = new ArrayList<MaskStack.Comp>();
        for (String id : MaskShapes.IDS) {
            float a = MaskShapes.aspect(id);
            MaskStack.Comp s = box(MaskStack.SHAPE, 0, 0, 54 * Math.min(1, a), 54 / Math.max(1, a));
            s.shape = id; cells.add(s);
        }
        int[] sides = {3, 4, 5, 6, 8};
        for (int n : sides) { MaskStack.Comp q = box(MaskStack.POLY, 0, 0, 54, 54); q.sides = n; q.inner = MaskStack.polyInner(n); cells.add(q); }
        float[] inn = {0.38f, 0.5f, 0.7f};
        int[] pn = {5, 6, 12};
        for (int i = 0; i < 3; i++) { MaskStack.Comp q = box(MaskStack.POLY, 0, 0, 54, 54); q.sides = pn[i]; q.inner = inn[i]; q.round = i == 1 ? 0.4f : 0; cells.add(q); }
        float[] rr = {0, 0.3f, 1};
        for (float x : rr) { MaskStack.Comp q = box(MaskStack.RECT, 0, 0, 56, 36); q.round = x; cells.add(q); }
        MaskStack.Comp fe = box(MaskStack.SHAPE, 0, 0, 54, 50); fe.shape = "heart"; fe.feather = 0.6f; cells.add(fe);
        MaskStack.Comp ol = box(MaskStack.SHAPE, 0, 0, 54, 50); ol.shape = "heart"; ol.outline = 0.12f; cells.add(ol);
        MaskStack.Comp gr = box(MaskStack.SHAPE, 0, 0, 40, 40); gr.shape = "bolt"; gr.grow = 0.3f; cells.add(gr);
        MaskStack.Comp rv = box(MaskStack.POLY, 0, 0, 54, 54); rv.inner = 0.45f; rv.reveal = 0.6f; cells.add(rv);
        MaskStack.Comp rt = box(MaskStack.SHAPE, 0, 0, 54, 44); rt.shape = "arrow"; rt.angle = -30; cells.add(rt);
        int rows = (cells.size() + cols - 1) / cols;
        int W = cols * C, H = rows * C;
        byte[] img = new byte[W * H];
        for (int k = 0; k < cells.size(); k++) {
            MaskStack.Comp c = cells.get(k);
            int ox = (k % cols) * C, oy = (k / cols) * C;
            c.x0 = ox + C / 2f; c.y0 = oy + C / 2f;
            for (int y = 0; y < C; y++) for (int x = 0; x < C; x++) {
                float v = c.value(ox + x + 0.5f, oy + y + 0.5f);
                int g = (int) (v * 225 + 30 * (((x / 8 + y / 8) & 1) == 0 ? 1 : 0.6));
                img[(oy + y) * W + ox + x] = (byte) Math.min(255, g);
            }
        }
        pgm(dir + "/sheet.pgm", img, W, H);
    }

    /** Shader parity scenes: same format as MaskStackTest's, plus the distance textures. */
    static void scenes(String dir) throws Exception {
        int W = 256, H = 192;
        byte[] r = disc(W, H, 70, 96, 50);
        pgm(dir + "/raster.pgm", r, W, H);
        ArrayList<ArrayList<MaskStack.Comp>> sc = new ArrayList<ArrayList<MaskStack.Comp>>();
        int[][] paint = {{MaskStack.ADD, 0}, {MaskStack.SUB, 3}, {MaskStack.INTERSECT, 8}, {MaskStack.ADD, 1}};
        ArrayList<MaskStack.Comp> s0 = new ArrayList<MaskStack.Comp>();
        MaskStack.Comp a = box(MaskStack.RECT, 150, 90, 70, 40); a.round = 0.4f; a.angle = 20; a.feather = 0.3f; s0.add(a);
        MaskStack.Comp b = box(MaskStack.POLY, 200, 140, 45, 40); b.sides = 5; b.inner = 0.4f; b.round = 0.2f; b.op = MaskStack.ADD; s0.add(b);
        sc.add(s0);
        ArrayList<MaskStack.Comp> s1 = new ArrayList<MaskStack.Comp>();
        MaskStack.Comp h = box(MaskStack.SHAPE, 128, 96, 80, 72); h.shape = "heart"; h.feather = 0.2f; s1.add(h);
        MaskStack.Comp g = box(MaskStack.SHAPE, 190, 60, 40, 50); g.shape = "bolt"; g.outline = 0.15f; g.angle = -15; s1.add(g);
        MaskStack.Comp q = box(MaskStack.POLY, 60, 150, 40, 30); q.sides = 6; q.inner = MaskStack.polyInner(6); q.invert = true; q.amount = 0.7f; q.op = MaskStack.SUB; s1.add(q);
        sc.add(s1);
        ArrayList<MaskStack.Comp> s2 = new ArrayList<MaskStack.Comp>();
        MaskStack.Comp gr = box(MaskStack.SHAPE, 128, 96, 100, 80); gr.shape = "gear"; gr.grow = -0.1f; s2.add(gr);
        MaskStack.Comp rv = box(MaskStack.RECT, 128, 96, 110, 80); rv.reveal = 0.4f; rv.revealMode = 2; rv.op = MaskStack.INTERSECT; s2.add(rv);
        MaskStack.Comp ln = new MaskStack.Comp(); ln.x0 = 0; ln.y0 = 0; ln.x1 = 256; ln.y1 = 192; ln.op = MaskStack.ADD; s2.add(ln);
        MaskStack.Comp cl = box(MaskStack.SHAPE, 60, 60, 50, 35); cl.shape = "cloud"; cl.reveal = 0.7f; cl.revealMode = 4; s2.add(cl);
        MaskStack.Comp rd = new MaskStack.Comp(); rd.type = MaskStack.RADIAL; rd.x0 = 200; rd.y0 = 150; rd.x1 = 40; rd.y1 = 30; rd.feather = 0.5f; s2.add(rd);
        sc.add(s2);
        ArrayList<MaskStack.Comp> s3 = new ArrayList<MaskStack.Comp>();
        for (int i = 0; i < 8; i++) {
            MaskStack.Comp c = box(i % 3 == 0 ? MaskStack.SHAPE : i % 3 == 1 ? MaskStack.POLY : MaskStack.RECT, 30 + i * 28, 40 + (i % 3) * 50, 26, 22);
            if (c.type == MaskStack.SHAPE) c.shape = MaskShapes.IDS[i];
            c.sides = 3 + i; c.inner = i % 2 == 0 ? 0.5f : 1f; c.angle = i * 13; c.op = i % 3; c.feather = i / 10f;
            s3.add(c);
        }
        s3.get(0).op = MaskStack.ADD;
        sc.add(s3);
        for (int n = 0; n < sc.size(); n++) {
            ArrayList<MaskStack.Comp> cs = sc.get(n);
            paint[n][1] = Math.min(paint[n][1], cs.size());   // the app clamps the paint's place to the stack (GlBase)
            pgm(dir + "/expect_" + n + ".pgm", MaskStack.flatten(r, W, H, paint[n][0], paint[n][1], cs), W, H);
            float[][] u = MaskStack.uniforms(cs);
            StringBuilder s = new StringBuilder();
            s.append("uMaskN=").append(cs.size()).append(" uPaintOp=").append(paint[n][0]).append(" uPaintAt=").append(paint[n][1]);
            String[] nm = {"uMA", "uMB", "uMC", "uMD", "uME"};
            for (int k = 0; k < u.length; k++) {
                for (int j = 0; j < 5; j++) {
                    s.append(' ').append(nm[j]).append('[').append(k).append("]=");
                    for (int i = 0; i < 4; i++) s.append(i > 0 ? "," : "").append(u[k][j * 4 + i]);
                }
                if (cs.get(k).isRaster() && u[k][3] > 0) {
                    MaskRaster.Sdf f = MaskRaster.get(cs.get(k));
                    pgm(dir + "/sdf_" + n + "_" + (int) u[k][19] + ".pgm", f.a, f.w, f.h);
                }
            }
            FileOutputStream o = new FileOutputStream(dir + "/uniforms_" + n + ".txt");
            o.write(s.toString().getBytes("UTF-8"));
            o.close();
        }
    }

    static byte[] disc(int w, int h, float cx, float cy, float r) {
        byte[] b = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
            b[y * w + x] = (byte) Math.round(255 * Math.max(0, Math.min(1, (r - d) / 12 + 0.5)));
        }
        return b;
    }

    static void pgm(String path, byte[] b, int w, int h) throws Exception {
        FileOutputStream o = new FileOutputStream(path);
        o.write(("P5\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
        o.write(b);
        o.close();
    }
}
