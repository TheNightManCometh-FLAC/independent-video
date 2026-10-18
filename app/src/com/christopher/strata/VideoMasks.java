package com.christopher.strata;

import java.util.ArrayList;

/**
 * 3.15: masks on video effects and grades. A grade layer's mask covers the output frame (a canvas
 * mask in output px scaled to MaskPaint.size), so it stays put while the footage under it moves;
 * shapes and text on it can be keyframed and driven by the music instead.
 */
final class VideoMasks {
    private VideoMasks() {}

    /** Redraws every grade mask from its strokes (after loading; history copies share them). */
    static void build(VProject p) {
        for (ArrayList<Layer> g : p.gradeLists())
            for (Layer l : g)
                if (l.paint != null && l.mask == null) {
                    l.mask = MaskPaint.render(l.vmW, l.vmH, l.paint);
                    l.maskVer++;
                }
    }

    /** Gives l an empty (hide-all) or reveal-all mask if it has none. Call inside an edit. */
    static void ensure(Layer l, VProject p, boolean reveal) { ensure(l, p.outW(), p.outH(), reveal); }

    /** As above for a frame of w x h (a B-roll's own picture for its grades). */
    static void ensure(Layer l, float w, float h, boolean reveal) {
        if (l.mask != null && l.paint != null) return;
        int[] sz = MaskPaint.size(w, h);
        l.vmW = sz[0]; l.vmH = sz[1];
        l.paint = new ArrayList<MaskPaint.Op>();
        if (reveal) l.paint.add(new MaskPaint.Op(MaskPaint.REVEAL_ALL, null, 0, 0, 0, false));
        l.mask = MaskPaint.render(l.vmW, l.vmH, l.paint);
        l.maskVer++;
        l.comps = new ArrayList<MaskStack.Comp>();
        l.paintOp = MaskStack.ADD;
        l.paintAt = 0;
    }

    static void remove(Layer l) {
        l.mask = null;
        l.paint = null;
        l.comps = new ArrayList<MaskStack.Comp>();
        l.paintOp = MaskStack.ADD;
        l.paintAt = 0;
        l.maskVer++;
    }

    /** Adds a whole-mask op (reveal all, hide all, invert) to the paint, copy-on-write. */
    static void fill(Layer l, int kind) {
        if (l.paint == null || l.mask == null) return;
        ArrayList<MaskPaint.Op> ops = new ArrayList<MaskPaint.Op>(l.paint);
        if (kind == MaskPaint.REVEAL_ALL || kind == MaskPaint.HIDE_ALL) ops.clear();   // nothing before a fill shows
        ops.add(new MaskPaint.Op(kind, null, 0, 0, 0, false));
        l.paint = ops;
        l.mask = MaskPaint.render(l.vmW, l.vmH, ops);
        l.maskVer++;
    }

    /** Any grade layer with a mask (the project then needs format 18). */
    static boolean any(VProject p) {
        for (ArrayList<Layer> g : p.gradeLists()) for (Layer l : g) if (l.paint != null) return true;
        return false;
    }
}
