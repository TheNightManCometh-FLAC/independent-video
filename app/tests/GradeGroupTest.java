package com.christopher.strata;

import java.util.ArrayList;
import java.util.Arrays;

/** 3.14: look groups in video grade stacks (structure, moves, copies, persistence, format). */
public class GradeGroupTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    static int next = 100;
    static final GradeGroups.Ids IDS = new GradeGroups.Ids() { public int next() { return next++; } };

    static Layer adj(int id, int type, String name) { Layer l = new Layer(); l.id = id; l.type = type; l.name = name; return l; }

    static String names(ArrayList<Layer> g) {
        StringBuilder b = new StringBuilder();
        for (Layer l : g) b.append(b.length() == 0 ? "" : " ").append(l.name).append(l.parent != 0 ? "^" : "");
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        ArrayList<Layer> g = new ArrayList<Layer>();
        g.add(adj(1, Layer.BASIC, "wb"));
        ArrayList<Layer> look = new ArrayList<Layer>(Arrays.asList(adj(0, Layer.CURVES, "curve"), adj(0, Layer.HSL, "hsl")));
        Layer grp = GradeGroups.addLook(g, look, "Teal", IDS);
        check("A look is added on top: children, then its group", names(g).equals("wb curve^ hsl^ Teal") && grp.type == Layer.GROUP);
        check("The correction underneath is kept", g.get(0).id == 1);
        check("Children get new ids and point at the group", g.get(1).id != 0 && g.get(1).parent == grp.id && g.get(2).parent == grp.id);
        check("Strength starts at 100 %", grp.opacity == 1f);
        check("Normal structure needs no repair", !GradeGroups.normalize(g));

        g.add(adj(2, Layer.EFFECT, "grain"));
        check("Move the group earlier: the whole block moves", GradeGroups.move(g, grp, -1) && names(g).equals("curve^ hsl^ Teal wb grain"));
        check("Can't move the first block earlier", !GradeGroups.move(g, grp, -1));
        check("A layer moves past a whole group", GradeGroups.move(g, g.get(3), -1) && names(g).equals("wb curve^ hsl^ Teal grain"));
        check("Move the group later past one layer", GradeGroups.move(g, grp, 1) && names(g).equals("wb grain curve^ hsl^ Teal"));
        Layer hsl = GradeGroups.byId(g, g.get(3).id);
        check("A child moves within its group", GradeGroups.move(g, hsl, -1) && names(g).equals("wb grain hsl^ curve^ Teal"));
        check("A child can't leave its group", !GradeGroups.move(g, hsl, -1));

        Layer copy = GradeGroups.duplicate(g, grp, IDS);
        check("Duplicating a group copies its block after it", names(g).equals("wb grain hsl^ curve^ Teal hsl^ curve^ Teal") && copy != grp && copy.type == Layer.GROUP);
        check("The copy's children belong to the copy", g.get(5).parent == copy.id && g.get(6).parent == copy.id && g.get(2).parent == grp.id);
        GradeGroups.remove(g, copy);
        check("Deleting a group deletes its layers", names(g).equals("wb grain hsl^ curve^ Teal"));

        // repairs
        ArrayList<Layer> bad = new ArrayList<Layer>();
        Layer orphan = adj(10, Layer.BASIC, "orphan"); orphan.parent = 99;
        Layer gA = adj(20, Layer.GROUP, "A");
        Layer cA = adj(21, Layer.CURVES, "cA"); cA.parent = 20;
        Layer empty = adj(30, Layer.GROUP, "empty");
        bad.addAll(Arrays.asList(cA, orphan, gA, empty, adj(11, Layer.LUT, "lut")));
        check("Repair: orphan to top level, child moved to its group, empty group removed",
                GradeGroups.normalize(bad) && names(bad).equals("orphan cA^ A lut") && orphan.parent == 0);

        // paste / apply to many clips keeps the structure with new ids
        ArrayList<Layer> pasted = GradeGroups.copyWithNewIds(g, IDS);
        Layer pg = pasted.get(4);
        check("Pasted stack keeps its look group with fresh ids", names(pasted).equals(names(g)) && pg.type == Layer.GROUP && pg.id != grp.id
                && pasted.get(2).parent == pg.id && pasted.get(3).parent == pg.id);

        // ungroup
        GradeGroups.ungroup(g, grp);
        check("Ungroup keeps the layers at full strength", names(g).equals("wb grain hsl curve"));

        // project persistence and format
        VProject p = new VProject();
        VProject.Clip c = new VProject.Clip();
        c.id = 1; c.uri = "content://a"; c.kind = VProject.VIDEO; c.in = 0; c.out = 1000000; c.srcDur = 1000000; c.speed = 1;
        p.clips.add(c); p.nextId = 500;
        check("No groups: format unchanged", p.formatVersion() == 14);
        GradeGroups.addLook(c.grade, Arrays.asList(adj(0, Layer.BASIC, "lift")), "Warm", IDS);
        c.grade.get(c.grade.size() - 1).opacity = 0.4f;
        check("A look group bumps the video format to 17", p.formatVersion() == 17 && ProjectIO.VIDEO_VERSION >= 17);
        VProject r = VProject.fromJson(p.toJson());
        ProjectIO.sanitize(r, new ProjectIO.Loaded());
        ArrayList<Layer> rg = r.clips.get(0).grade;
        check("Group, child link and Strength survive save and reload", rg.size() == 2 && rg.get(1).type == Layer.GROUP
                && rg.get(0).parent == rg.get(1).id && Math.abs(rg.get(1).opacity - 0.4f) < 1e-6);
        VProject cp = p.copy();
        cp.clips.get(0).grade.get(1).opacity = 1;
        check("Undo snapshots copy groups deeply", Math.abs(p.clips.get(0).grade.get(1).opacity - 0.4f) < 1e-6);
        System.out.println(count + " passed, 0 failed");
    }
}
