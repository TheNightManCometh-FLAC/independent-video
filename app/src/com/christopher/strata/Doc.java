package com.christopher.strata;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The editable document: a stack of layers over an image space (imgW x imgH) plus a
 * non-destructive crop (centre, size and angle in image px). Index 0 is the bottom layer.
 */
final class Doc {
    static final int MASK_MAX = 4096;

    int imgW, imgH;
    float cropCx, cropCy, cropW, cropH;
    int rot90;            // quarter turns
    float straighten;     // degrees, -45..45
    ArrayList<Layer> layers = new ArrayList<Layer>();
    int nextId = 1;

    float angle() { return rot90 * 90f + straighten; }

    void resetCrop() {
        cropCx = imgW / 2f; cropCy = imgH / 2f; cropW = imgW; cropH = imgH; rot90 = 0; straighten = 0;
    }

    /**
     * The Select tab's selection at the moment this document was snapshotted into history
     * (3.6: one undo path for edits and selections). Not saved with the project.
     */
    Bitmap sel;

    Doc copy() {
        Doc d = new Doc();
        d.sel = sel;
        d.imgW = imgW; d.imgH = imgH;
        d.cropCx = cropCx; d.cropCy = cropCy; d.cropW = cropW; d.cropH = cropH;
        d.rot90 = rot90; d.straighten = straighten; d.nextId = nextId;
        for (Layer l : layers) d.layers.add(l.copy());
        return d;
    }

    Layer byId(int id) {
        for (Layer l : layers) if (l.id == id) return l;
        return null;
    }

    int indexOf(int id) {
        for (int i = 0; i < layers.size(); i++) if (layers.get(i).id == id) return i;
        return -1;
    }

    /** The nearest photo layer below index idx. */
    static Layer retouchTarget(Doc d, int idx) {
        for (int j = Math.min(idx, d.layers.size()) - 1; j >= 0; j--)
            if (d.layers.get(j).type == Layer.IMAGE) return d.layers.get(j);
        return null;
    }

    /** The photo a retouch layer works on: the one it was made for, else the nearest photo below it. */
    static Layer retouchTargetOf(Doc d, Layer r) {
        if (r.target != 0) {
            Layer t = d.byId(r.target);
            if (t != null && t.type == Layer.IMAGE) return t;
        }
        return retouchTarget(d, d.indexOf(r.id));
    }

    /**
     * Keeps every retouch layer attached to its photo. Strokes are stored in image px for the
     * photo's placement copied into the retouch layer (cx, cy, scale, rot, srcW, srcH). When the
     * photo has moved since, the strokes are carried along (same layer px, new image px), so heal
     * and clone work stays on the face it was painted on. A layer without a target adopts the
     * nearest photo below without moving anything (projects from before 3.1).
     */
    void syncRetouch() {
        for (Layer r : layers) {
            if (r.type != Layer.RETOUCH) continue;
            Layer t = retouchTargetOf(this, r);
            if (t == null) continue;
            boolean adopt = r.target != t.id;
            if (!adopt && (r.cx != t.cx || r.cy != t.cy || r.scale != t.scale || r.rot != t.rot) && !r.strokes.isEmpty()) {
                double[] m = MaskOps.then(placeInverse(r), placement(t));   // old image px -> layer px -> new image px
                float k = t.scale / r.scale;
                ArrayList<Retouch.Stroke> moved = new ArrayList<Retouch.Stroke>(r.strokes.size());
                for (Retouch.Stroke st : r.strokes) moved.add(st.transformed(m, k));
                r.strokes = moved;
            }
            r.target = t.id;
            r.cx = t.cx; r.cy = t.cy; r.scale = t.scale; r.rot = t.rot; r.srcW = t.srcW; r.srcH = t.srcH;
        }
    }

    /** Layer px (relative to the layer centre) -> image px. */
    static double[] placement(Layer l) {
        double t = Math.toRadians(l.rot), c = Math.cos(t) * l.scale, s = Math.sin(t) * l.scale;
        return new double[]{c, -s, l.cx, s, c, l.cy};
    }

    static double[] placeInverse(Layer l) { return MaskOps.invert(placement(l)); }

    /** Can this layer's mask be linked to a placement (it, or its retouch target, can move)? */
    boolean linkable(Layer l) {
        if (l.type == Layer.RETOUCH) {
            Layer t = retouchTargetOf(this, l);
            return t != null && t.movable();
        }
        return l.movable() && l.type != Layer.GROUP;
    }

    Layer newLayer(int type) {
        Layer l = new Layer();
        l.id = nextId++;
        l.type = type;
        l.name = Layer.TYPE_NAMES[type];
        if (type == Layer.CURVES) l.rebuildLut();
        return l;
    }

    // ---- groups and clipping ----
    /**
     * 1 base, 2 layer groups / clipping (an older app would turn a group into an adjustment and
     * drop the structure, so it refuses v2 instead).
     */
    int formatVersion() {
        // 5: shapes beyond the two gradients (rectangles, polygons, library and freehand shapes, text), more than 6
        for (Layer l : layers) {
            if (l.comps.size() > 6) return 5;
            for (MaskStack.Comp c : l.comps) if (c.type > MaskStack.RADIAL) return 5;
        }
        // 4: editable mask components (shapes, the painted raster's op and place)
        for (Layer l : layers) if (!l.comps.isEmpty()) return 4;
        // 3: masks saved under per-save file names, linked masks, retouch targets
        for (Layer l : layers) if (l.mask != null || l.maskK > 0 || l.target != 0) return 3;
        for (Layer l : layers) if (l.type == Layer.GROUP || l.parent != 0 || l.clip) return 2;
        return 1;
    }

    /** Children of group gid, bottom first. */
    ArrayList<Layer> children(int gid) {
        ArrayList<Layer> out = new ArrayList<Layer>();
        for (Layer l : layers) if (l.parent == gid) out.add(l);
        return out;
    }

    /**
     * Index of the layer that layer i is clipped to: the nearest unclipped layer below it in the
     * same group, if that layer has pixels of its own. -1 = no valid base (the clip is ignored).
     */
    int clipBase(int i) {
        Layer l = layers.get(i);
        if (!l.clip) return -1;
        for (int j = i - 1; j >= 0; j--) {
            Layer b = layers.get(j);
            if (b.parent != l.parent) return -1;
            if (b.clip) continue;
            return b.pixelLayer() ? j : -1;
        }
        return -1;
    }

    /** Could layer i be clipped (now or after a toggle)? */
    boolean canClip(int i) {
        Layer l = layers.get(i);
        if (l.type == Layer.GROUP || l.type == Layer.RETOUCH || i == 0) return false;
        boolean was = l.clip;
        l.clip = true;
        boolean ok = clipBase(i) >= 0;
        l.clip = was;
        return ok;
    }

    /**
     * Keeps every group's children directly below the group's layer, in their current order, and
     * drops references to groups that no longer exist (one level of grouping).
     */
    void normalizeGroups() {
        java.util.HashSet<Integer> groups = new java.util.HashSet<Integer>();
        for (Layer l : layers) if (l.type == Layer.GROUP) { groups.add(l.id); l.parent = 0; }
        for (Layer l : layers) if (l.parent != 0 && !groups.contains(l.parent)) l.parent = 0;
        ArrayList<Layer> top = new ArrayList<Layer>();   // top-down
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            if (l.parent != 0) continue;
            top.add(l);
            if (l.type == Layer.GROUP)
                for (int j = layers.size() - 1; j >= 0; j--) if (layers.get(j).parent == l.id) top.add(layers.get(j));
        }
        layers.clear();
        for (int i = top.size() - 1; i >= 0; i--) layers.add(top.get(i));
        // a clip whose base is gone (moved, deleted, other group) is released, as in Photoshop
        for (int i = 0; i < layers.size(); i++) if (layers.get(i).clip && clipBase(i) < 0) layers.get(i).clip = false;
    }

    /**
     * After layer x was dragged to a new place: it joins a group when dropped directly under the
     * group's layer or between two of its children, otherwise it's top level. A dragged group
     * brings its children.
     */
    void settleAfterDrag(Layer x) {
        if (x.type != Layer.GROUP) {
            int i = indexOf(x.id);
            Layer up = i + 1 < layers.size() ? layers.get(i + 1) : null, down = i > 0 ? layers.get(i - 1) : null;
            if (up != null && up.type == Layer.GROUP) x.parent = up.id;
            else if (up != null && up.parent != 0 && down != null && down.parent == up.parent) x.parent = up.parent;
            else x.parent = 0;
        }
        normalizeGroups();
    }

    /**
     * One step up (+1) or down (-1). A group moves as a block past the neighbouring layer or
     * block; a layer in a group moves among its siblings and steps out at the group's edge.
     */
    void moveStep(int id, int dir) {
        Layer x = byId(id);
        if (x == null) return;
        if (x.parent != 0) {
            ArrayList<Layer> sib = children(x.parent);
            int k = sib.indexOf(x), j = k + dir;
            if (j >= 0 && j < sib.size()) {
                int a = layers.indexOf(x), b = layers.indexOf(sib.get(j));
                layers.set(a, sib.get(j));
                layers.set(b, x);
            } else {
                Layer g = byId(x.parent);
                x.parent = 0;
                layers.remove(x);
                int gi = layers.indexOf(g);
                if (dir > 0) layers.add(gi + 1, x);   // just above the group
                else {
                    int bottom = gi;
                    while (bottom > 0 && layers.get(bottom - 1).parent == g.id) bottom--;
                    layers.add(bottom, x);              // just below the group's last child
                }
            }
            normalizeGroups();
            return;
        }
        // top level: swap whole units (a single layer, or a group with its children)
        ArrayList<ArrayList<Layer>> units = new ArrayList<ArrayList<Layer>>();   // bottom-up
        ArrayList<Layer> curU = new ArrayList<Layer>();
        for (Layer l : layers) {
            curU.add(l);
            if (l.parent == 0) { units.add(curU); curU = new ArrayList<Layer>(); }
        }
        if (!curU.isEmpty()) units.add(curU);
        int u = -1;
        for (int k = 0; k < units.size(); k++) if (units.get(k).contains(x)) u = k;
        int v = u + dir;
        if (u < 0 || v < 0 || v >= units.size()) return;
        ArrayList<Layer> t = units.get(u);
        units.set(u, units.get(v));
        units.set(v, t);
        layers.clear();
        for (ArrayList<Layer> un : units) layers.addAll(un);
    }

    /** All layers of the group (children and the group itself), as a list in stack order. */
    ArrayList<Layer> groupBlock(int gid) {
        ArrayList<Layer> out = children(gid);
        Layer g = byId(gid);
        if (g != null) out.add(g);
        return out;
    }

    // ---- geometry ----
    /** Document (output) size for the given mode. Crop mode shows the whole rotated image. */
    float docW(boolean cropMode) {
        if (!cropMode) return cropW;
        double t = Math.toRadians(angle());
        return (float) (Math.abs(imgW * Math.cos(t)) + Math.abs(imgH * Math.sin(t)));
    }

    float docH(boolean cropMode) {
        if (!cropMode) return cropH;
        double t = Math.toRadians(angle());
        return (float) (Math.abs(imgW * Math.sin(t)) + Math.abs(imgH * Math.cos(t)));
    }

    /** Doc px -> image px rows: img = C + R(angle) * (doc - D/2). */
    void docToImg(boolean cropMode, float[] m0, float[] m1) {
        double t = Math.toRadians(angle()), c = Math.cos(t), s = Math.sin(t);
        float w = docW(cropMode), h = docH(cropMode);
        double ccx = cropMode ? imgW / 2.0 : cropCx, ccy = cropMode ? imgH / 2.0 : cropCy;
        m0[0] = (float) c; m0[1] = (float) -s; m0[2] = (float) (ccx - c * w / 2 + s * h / 2);
        m1[0] = (float) s; m1[1] = (float) c; m1[2] = (float) (ccy - s * w / 2 - c * h / 2);
    }

    /** Image px -> doc px (inverse of docToImg). */
    void imgToDoc(boolean cropMode, float ix, float iy, float[] out) {
        double t = Math.toRadians(angle()), c = Math.cos(t), s = Math.sin(t);
        float w = docW(cropMode), h = docH(cropMode);
        double ccx = cropMode ? imgW / 2.0 : cropCx, ccy = cropMode ? imgH / 2.0 : cropCy;
        double dx = ix - ccx, dy = iy - ccy;
        out[0] = (float) (c * dx + s * dy + w / 2);
        out[1] = (float) (-s * dx + c * dy + h / 2);
    }

    int maskW() { return Math.max(1, Math.round(imgW * maskScale())); }
    int maskH() { return Math.max(1, Math.round(imgH * maskScale())); }
    float maskScale() { return Math.min(1f, MASK_MAX / (float) Math.max(imgW, imgH)); }

    Bitmap newMask(boolean reveal) {
        Bitmap m = Bitmap.createBitmap(maskW(), maskH(), Bitmap.Config.ALPHA_8);
        m.eraseColor(reveal ? 0xff000000 : 0);
        return m;
    }

    /** Mask px per layer px for a new linked mask: about canvas-mask resolution, at most MASK_MAX. */
    float linkK(Layer l) {
        float cap = MASK_MAX / (float) Math.max(1, Math.max(l.srcW, l.srcH));
        return Math.max(1e-4f, Math.min(cap, Math.max(1f, l.scale * maskScale())));
    }

    /** {w, h} of a linked mask covering layer l at k. */
    static int[] linkSize(Layer l, float k) {
        return new int[]{Math.max(1, Math.round(l.srcW * k)), Math.max(1, Math.round(l.srcH * k))};
    }

    /**
     * A new mask for l (reveal = white). Layers that can move get a linked mask (it moves with
     * them); the rest a canvas mask. Sets l.maskK; doesn't assign l.mask.
     */
    Bitmap newMaskFor(Layer l, boolean reveal) {
        if (!linkable(l)) { l.maskK = 0; return newMask(reveal); }
        l.maskK = linkK(l);
        int[] sz = linkSize(l, l.maskK);
        Bitmap m = Bitmap.createBitmap(sz[0], sz[1], Bitmap.Config.ALPHA_8);
        m.eraseColor(reveal ? 0xff000000 : 0);
        return m;
    }

    /** Canvas px of a canvas mask (maskW x maskH) -> image px. */
    double[] canvasMaskToImg() { return new double[]{imgW / (double) maskW(), 0, 0, 0, imgH / (double) maskH(), 0}; }

    /**
     * A mask plane (or selection) in canvas space (maskW x maskH) brought into l's mask frame
     * (dims w x h, link maskK as currently set on l).
     */
    byte[] canvasToLayerMask(byte[] canvas, Layer l, int w, int h) {
        if (l.maskK <= 0) return canvas;
        double[] imgToMask = l.maskAffine(imgW, imgH, w, h);
        double[] dstToSrc = MaskOps.then(MaskOps.invert(imgToMask), MaskOps.invert(canvasMaskToImg()));
        return MaskOps.warp(canvas, maskW(), maskH(), w, h, dstToSrc);
    }

    /** l's mask as a canvas-space plane (maskW x maskH); edges extend as the renderer extends them. */
    byte[] layerMaskToCanvas(Layer l) {
        return layerMaskToCanvas(Editor.alphaBytes(l.mask), l.mask.getWidth(), l.mask.getHeight(), l);
    }

    byte[] layerMaskToCanvas(byte[] m, int w, int h, Layer l) {
        if (l.maskK <= 0) return m;
        double[] dstToSrc = MaskOps.then(canvasMaskToImg(), l.maskAffine(imgW, imgH, w, h));
        return MaskOps.warp(m, w, h, maskW(), maskH(), dstToSrc);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", formatVersion());
        o.put("imgW", imgW); o.put("imgH", imgH);
        o.put("cropCx", (double) cropCx); o.put("cropCy", (double) cropCy);
        o.put("cropW", (double) cropW); o.put("cropH", (double) cropH);
        o.put("rot90", rot90); o.put("straighten", (double) straighten);
        o.put("nextId", nextId);
        JSONArray a = new JSONArray();
        for (Layer l : layers) a.put(l.toJson());
        o.put("layers", a);
        return o;
    }

    static Doc fromJson(JSONObject o) throws JSONException {
        Doc d = new Doc();
        d.imgW = o.getInt("imgW"); d.imgH = o.getInt("imgH");
        d.cropCx = (float) o.getDouble("cropCx"); d.cropCy = (float) o.getDouble("cropCy");
        d.cropW = (float) o.getDouble("cropW"); d.cropH = (float) o.getDouble("cropH");
        d.rot90 = o.optInt("rot90"); d.straighten = (float) o.optDouble("straighten", 0);
        d.nextId = o.optInt("nextId", 100);
        JSONArray a = o.getJSONArray("layers");
        for (int i = 0; i < a.length(); i++) d.layers.add(Layer.fromJson(a.getJSONObject(i)));
        return d;
    }
}
