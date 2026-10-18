package com.christopher.strata;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;

/**
 * CPU side of region renders (export tiles and the zoomed detail view): decides, per layer, which
 * pixels a region needs and at what resolution, and produces bitmaps for them. Photos decode the
 * exact rectangle with BitmapRegionDecoder (downsampled by a power of two when the output is
 * smaller); formats it can't read fall back to one cached large decode; text renders the rect.
 */
final class SourceCache {
    /** Bitmaps for one region render. owned bitmaps are recycled after upload. */
    static final class Prepared {
        final HashMap<Integer, Bitmap> bmp = new HashMap<Integer, Bitmap>();
        final HashMap<Integer, RectF> rect = new HashMap<Integer, RectF>();
        final HashSet<Bitmap> owned = new HashSet<Bitmap>();
        /** Layers that got less detail than asked for, and why (layer id -> description). Empty = full quality. */
        final java.util.LinkedHashMap<Integer, String> downgrades = new java.util.LinkedHashMap<Integer, String>();

        void recycle() {
            for (Bitmap b : owned) b.recycle();
            owned.clear();
        }
    }

    private static final class Region {
        BitmapRegionDecoder dec;
        Loader.Info info;
    }

    private final File dir;
    private final HashMap<String, Region> regions = new HashMap<String, Region>();
    private final HashMap<String, Bitmap> big = new HashMap<String, Bitmap>();
    private final HashMap<String, Float> bigRes = new HashMap<String, Float>();
    int maxTex = 8192;

    SourceCache(File dir) { this.dir = dir; }

    synchronized void close() {
        for (Region r : regions.values()) if (r != null && r.dec != null) r.dec.recycle();
        regions.clear();
        big.clear();
        bigRes.clear();
    }

    /**
     * Sources for rendering the doc rect (x, y, w, h in doc px) at k output px per doc px.
     * Includes the target photo of every visible retouch layer, widened to cover the strokes'
     * sources and healing boundaries.
     */
    synchronized Prepared prepare(Doc d, boolean crop, float[] docRect, float k) {
        Prepared p = new Prepared();
        float[] m0 = new float[3], m1 = new float[3];
        d.docToImg(crop, m0, m1);
        float[] imgBox = boxOf(new float[]{docRect[0], docRect[1], docRect[0] + docRect[2], docRect[1] + docRect[3]}, m0, m1);
        HashMap<Integer, Layer> need = new HashMap<Integer, Layer>();
        HashMap<Integer, RectF> extra = new HashMap<Integer, RectF>();
        for (int i = 0; i < d.layers.size(); i++) {
            Layer l = d.layers.get(i);
            if (!l.visible) continue;
            if (l.type == Layer.RETOUCH) {
                Layer t = Doc.retouchTargetOf(d, l);
                if (t == null) continue;
                need.put(t.id, t);
                for (Retouch.Stroke s : l.strokes) {
                    float[] b = s.box();
                    if (b[2] < imgBox[0] || b[0] > imgBox[2] || b[3] < imgBox[1] || b[1] > imgBox[3]) continue;
                    RectF dst = layerRect(t, b);
                    RectF src = layerRect(t, new float[]{b[0] + s.offX, b[1] + s.offY, b[2] + s.offX, b[3] + s.offY});
                    RectF e = extra.get(t.id);
                    if (e == null) { e = new RectF(dst); extra.put(t.id, e); } else e.union(dst);
                    e.union(src);
                }
            } else if (l.isPlaced()) need.put(l.id, l);
        }
        for (Layer l : need.values()) {
            RectF lr = layerRect(l, imgBox);
            lr.inset(-4, -4);
            RectF e = extra.get(l.id);
            if (e != null) lr.union(e);
            if (!lr.intersect(0, 0, l.srcW, l.srcH)) continue;
            float res = k * l.scale;   // texture px per layer px
            try {
                if (l.type == Layer.TEXT) {
                    Bitmap b = TextRender.render(l, lr, Math.min(res, 4f));
                    p.bmp.put(l.id, b); p.rect.put(l.id, lr); p.owned.add(b);
                } else if (l.type == Layer.IMAGE) {
                    getImage(l, lr, Math.min(1f, res), p);
                }
            } catch (OutOfMemoryError oom) {
                // try half the detail once, then the preview; either way it is reported, never silent
                big.clear(); bigRes.clear();
                try {
                    if (l.type == Layer.IMAGE) {
                        getImage(l, lr, Math.min(1f, res) / 2, p);
                        p.downgrades.put(l.id, l.name + ": half resolution (not enough memory)");
                    }
                } catch (OutOfMemoryError oom2) {
                    if (l.preview != null) {
                        p.bmp.put(l.id, l.preview); p.rect.put(l.id, new RectF(0, 0, l.srcW, l.srcH));
                        p.downgrades.put(l.id, l.name + ": preview copy, " + l.preview.getWidth() + " px wide instead of " + l.srcW + " (not enough memory)");
                    }
                }
            }
        }
        return p;
    }

    /** One photo region at need texture px per layer px: {Bitmap, RectF covered, Boolean owned}, or null. */
    synchronized Object[] regionBitmap(Layer l, RectF lr, float need) {
        Prepared p = new Prepared();
        getImage(l, lr, Math.min(1f, need), p);
        Bitmap b = p.bmp.get(l.id);
        if (b == null) return null;
        return new Object[]{b, p.rect.get(l.id), p.owned.contains(b)};
    }

    private void getImage(Layer l, RectF lr, float need, Prepared p) {
        if (l.preview != null && l.preview.getWidth() / (float) l.srcW >= need * 0.97f) {
            p.bmp.put(l.id, l.preview);
            p.rect.put(l.id, new RectF(0, 0, l.srcW, l.srcH));
            return;
        }
        if (l.src == null) return;
        File f = new File(dir, l.src);
        Region r = region(f, l);
        if (r != null) {
            int s = 1;
            while (s * 2 <= 1f / need) s *= 2;
            Rect rr = new Rect((int) Math.floor(lr.left), (int) Math.floor(lr.top), (int) Math.ceil(lr.right), (int) Math.ceil(lr.bottom));
            rr.intersect(0, 0, l.srcW, l.srcH);
            Bitmap b;
            synchronized (r) { b = Loader.decodeRegion(r.dec, r.info, rr, s); }
            if (b != null) {
                p.bmp.put(l.id, b);
                p.rect.put(l.id, new RectF(rr));
                p.owned.add(b);
                return;
            }
        }
        // whole-image fallback (HEIF without region support, DNG): one cached decode per file
        Bitmap c = big.get(l.src);
        Float cr = bigRes.get(l.src);
        if (c == null || cr == null || cr < need * 0.97f) {
            float aspect = Math.min(l.srcW, l.srcH) / (float) Math.max(l.srcW, l.srcH);
            int cap = (int) Math.min(maxTex, Math.sqrt(48e6 / aspect));
            int side = (int) Math.min(cap, Math.ceil(Math.max(l.srcW, l.srcH) * need * 1.02f) + 2);
            try {
                c = Loader.decode(f, side);
                big.clear(); bigRes.clear();   // keep only one large decode alive
                big.put(l.src, c);
                bigRes.put(l.src, c.getWidth() / (float) l.srcW);
            } catch (Exception e) {
                c = l.preview;
                if (c != null) p.downgrades.put(l.id, l.name + ": preview copy, " + c.getWidth() + " px wide (the file couldn't be decoded again)");
            }
        }
        if (c != null && c != l.preview && c.getWidth() / (float) l.srcW < need * 0.97f && !p.downgrades.containsKey(l.id))
            p.downgrades.put(l.id, l.name + ": decoded at " + c.getWidth() + " px wide of " + l.srcW
                    + " (this format can't be read in pieces, and a whole decode is capped near 48 MP)");
        if (c != null) {
            p.bmp.put(l.id, c);
            p.rect.put(l.id, new RectF(0, 0, l.srcW, l.srcH));
        }
    }

    private Region region(File f, Layer l) {
        String key = f.getName();
        if (regions.containsKey(key)) return regions.get(key);
        Region r = null;
        try {
            Loader.Info info = Loader.probe(f);
            BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(f.getPath(), false);
            // a raw file can "succeed" by exposing its small embedded preview: insist on full size
            boolean swap = info.orientation >= 5;
            int ow = swap ? dec.getHeight() : dec.getWidth(), oh = swap ? dec.getWidth() : dec.getHeight();
            if (ow == l.srcW && oh == l.srcH) {
                info.rawW = dec.getWidth();
                info.rawH = dec.getHeight();
                r = new Region();
                r.dec = dec;
                r.info = info;
            } else dec.recycle();
        } catch (Throwable ignored) {}
        regions.put(key, r);
        return r;
    }

    /** Bounding box, in image px, of a doc-px box mapped through the rows. */
    static float[] boxOf(float[] docBox, float[] m0, float[] m1) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? docBox[0] : docBox[2], y = (i & 2) == 0 ? docBox[1] : docBox[3];
            float ix = m0[0] * x + m0[1] * y + m0[2], iy = m1[0] * x + m1[1] * y + m1[2];
            b[0] = Math.min(b[0], ix); b[1] = Math.min(b[1], iy);
            b[2] = Math.max(b[2], ix); b[3] = Math.max(b[3], iy);
        }
        return b;
    }

    /** Layer-px bounding rect of an image-px box. */
    static RectF layerRect(Layer l, float[] imgBox) {
        float[] r0 = new float[3], r1 = new float[3];
        l.layerRows(r0, r1);
        float[] b = boxOf(imgBox, r0, r1);
        return new RectF(b[0], b[1], b[2], b[3]);
    }
}
