package com.christopher.strata;

import java.util.ArrayList;
import java.util.List;

/**
 * 3.14: pass-through groups in a video grade stack, used for looks with a Strength control.
 * Same structure as the photo editor's groups (Layer.GROUP, children carry `parent`, groups are one
 * level deep): in a grade list (applied first to last) a group's children sit directly before the
 * group layer, which closes the group by mixing the image from before its first child with the
 * result, by the group's opacity (the look's Strength). Pure list logic, so it is desktop-tested.
 */
final class GradeGroups {
    interface Ids { int next(); }

    static Layer byId(List<Layer> g, int id) {
        for (Layer l : g) if (l.id == id) return l;
        return null;
    }

    static boolean hasGroups(List<Layer> g) {
        for (Layer l : g) if (l.type == Layer.GROUP) return true;
        return false;
    }

    /**
     * Keeps the structure valid: a child whose group is missing becomes top-level; children are
     * moved (in order) to sit directly before their group; a group with no children is removed;
     * nested parents are flattened to top level. Returns true if anything changed.
     */
    static boolean normalize(ArrayList<Layer> g) {
        boolean changed = false;
        for (Layer l : g) {
            if (l.type == Layer.GROUP && l.parent != 0) { l.parent = 0; changed = true; }
            if (l.parent != 0) {
                Layer p = byId(g, l.parent);
                if (p == null || p.type != Layer.GROUP) { l.parent = 0; changed = true; }
            }
        }
        ArrayList<Layer> out = new ArrayList<Layer>();
        for (Layer l : g) {
            if (l.parent != 0) continue;   // placed with its group
            if (l.type == Layer.GROUP) {
                int n = 0;
                for (Layer c : g) if (c.parent == l.id) { out.add(c); n++; }
                if (n == 0) { changed = true; continue; }
            }
            out.add(l);
        }
        if (!changed) for (int i = 0; i < out.size(); i++) if (out.get(i) != g.get(i)) { changed = true; break; }
        if (changed) { g.clear(); g.addAll(out); }
        return changed;
    }

    /** Appends layers as a look: children then the group layer named name at Strength 1. */
    static Layer addLook(ArrayList<Layer> g, List<Layer> layers, String name, Ids ids) {
        Layer grp = new Layer();
        grp.id = ids.next();
        grp.type = Layer.GROUP;
        grp.name = name == null || name.isEmpty() ? "Look" : name;
        grp.opacity = 1;
        for (Layer l : layers) {
            Layer c = l.copy();
            c.id = ids.next();
            c.parent = grp.id;
            c.clip = false;
            c.rebuildLut();
            g.add(c);
        }
        g.add(grp);
        return grp;
    }

    /** Index range [from, to] of the block l belongs to as a unit: a group with its children, or l alone. */
    static int[] block(List<Layer> g, Layer l) {
        int i = g.indexOf(l);
        if (i < 0) return null;
        if (l.type != Layer.GROUP) return new int[]{i, i};
        int from = i;
        while (from > 0 && g.get(from - 1).parent == l.id) from--;
        return new int[]{from, i};
    }

    /**
     * Earlier (dir < 0) or later (dir > 0) by one step. A group or top-level layer moves past the
     * neighbouring top-level block; a child moves within its group only. Returns false if it can't.
     */
    static boolean move(ArrayList<Layer> g, Layer l, int dir) {
        if (l.parent != 0) {
            int i = g.indexOf(l), j = i + (dir < 0 ? -1 : 1);
            if (j < 0 || j >= g.size() || g.get(j).parent != l.parent) return false;
            g.set(i, g.get(j)); g.set(j, l);
            return true;
        }
        int[] b = block(g, l);
        if (b == null) return false;
        if (dir < 0) {
            if (b[0] == 0) return false;
            int[] nb = block(g, topOf(g, g.get(b[0] - 1)));
            ArrayList<Layer> mine = new ArrayList<Layer>(g.subList(b[0], b[1] + 1));
            g.subList(b[0], b[1] + 1).clear();
            g.addAll(nb[0], mine);
        } else {
            if (b[1] >= g.size() - 1) return false;
            int[] nb = block(g, topOf(g, g.get(b[1] + 1)));
            ArrayList<Layer> next = new ArrayList<Layer>(g.subList(nb[0], nb[1] + 1));
            g.subList(nb[0], nb[1] + 1).clear();
            g.addAll(b[0], next);
        }
        return true;
    }

    /** The top-level layer whose block contains l (its group for a child). */
    private static Layer topOf(List<Layer> g, Layer l) {
        if (l.parent == 0) return l;
        Layer p = byId(g, l.parent);
        return p == null ? l : p;
    }

    /** Removes l, and a group's children with it. */
    static void remove(ArrayList<Layer> g, Layer l) {
        int[] b = block(g, l);
        if (b == null) return;
        g.subList(b[0], b[1] + 1).clear();
        normalize(g);
    }

    /** Copies l's block right after it with new ids (children re-parented to the copy). Returns the copy of l. */
    static Layer duplicate(ArrayList<Layer> g, Layer l, Ids ids) {
        int[] b = block(g, l);
        if (b == null) return null;
        ArrayList<Layer> copies = new ArrayList<Layer>();
        int newGroup = l.type == Layer.GROUP ? ids.next() : 0;
        Layer top = null;
        for (int i = b[0]; i <= b[1]; i++) {
            Layer c = g.get(i).copy();
            if (c.type == Layer.GROUP) { c.id = newGroup; top = c; }
            else { c.id = ids.next(); if (newGroup != 0) c.parent = newGroup; if (top == null && newGroup == 0) top = c; }
            c.rebuildLut();
            copies.add(c);
        }
        g.addAll(b[1] + 1, copies);
        return top;
    }

    /** Dissolves a group: its layers stay, at full strength, as ordinary layers. */
    static void ungroup(ArrayList<Layer> g, Layer grp) {
        if (grp.type != Layer.GROUP) return;
        for (Layer c : g) if (c.parent == grp.id) c.parent = 0;
        g.remove(grp);
    }

    /** Fresh copies of a list with new ids, keeping its group structure (paste, apply to many clips). */
    static ArrayList<Layer> copyWithNewIds(List<Layer> src, Ids ids) {
        ArrayList<Layer> out = new ArrayList<Layer>();
        java.util.HashMap<Integer, Integer> map = new java.util.HashMap<Integer, Integer>();
        for (Layer l : src) if (l.type == Layer.GROUP) map.put(l.id, ids.next());
        for (Layer l : src) {
            Layer c = l.copy();
            c.id = l.type == Layer.GROUP ? map.get(l.id) : ids.next();
            if (c.parent != 0) { Integer np = map.get(c.parent); c.parent = np == null ? 0 : np; }
            c.rebuildLut();
            out.add(c);
        }
        normalize(out);
        return out;
    }
}
