package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import java.util.HashMap;
import java.util.Locale;

/**
 * Timeline with a fixed centre playhead: the tracks scroll under it. Rows: ruler (with beat
 * ticks), overlay lanes, main video track, text lanes, audio lanes. Overlapping items get their
 * own lanes (packed automatically; up to LANES per group). Drag to scrub (flings), pinch to zoom,
 * tap to select. The selected item shows edge handles: drag them to trim, drag a text or music
 * item's body to move it. With a transition selected, drag the cut to roll the edit. Edits snap
 * to clip edges, beats and the playhead, and land on whole frames.
 */
final class TimelineView extends View {
    static final int SEL_NONE = 0, SEL_CLIP = 1, SEL_TEXT = 2, SEL_AUDIO = 3, SEL_VIZ = 4, SEL_MEDIA = 5;
    /** Display lanes per group. Past 3 the tracks scroll vertically, so every item stays reachable. */
    static final int LANES = 16;

    final VideoEditor ed;
    float pxPerSec;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final OverScroller scroller;
    private VelocityTracker vt;
    private float lastX, lastY, downX, downY, pinchDist;
    private boolean multi, moved;
    private int drag;              // 0 scrub, 1 left handle, 2 right handle, 3 body, 4 roll the cut, 5 slip
    private boolean begun;         // the drag has started editing (an undo step exists)
    private long dragT0;
    /** Display lane per item id, per group, and lanes in use (recomputed on edits, frozen during a drag). */
    private final HashMap<Integer, Integer> lane = new HashMap<Integer, Integer>();
    private int nO = 1, nT = 1, nA = 1;
    private long[] snapCache;
    private final float rulerH, vH, gap;
    /** Lane heights: full size for a single lane, compact once a group needs more than one. */
    private float oH, tH, aH;
    /** Vertical scroll of the track area (below the ruler) when the tracks are taller than the view. */
    private float vy;
    private boolean vScroll;   // this drag scrolls vertically
    float contentH() { return rulerH + oH * nO + vH + tH * nT + aH * nA + gap * 5; }
    float maxVy() { return Math.max(0, contentH() - getHeight()); }
    /** Screen y -> track y (the ruler doesn't scroll). */
    private float ty(float y) { return y <= rulerH ? y : y + vy; }

    TimelineView(Context c, VideoEditor ed) {
        super(c);
        this.ed = ed;
        pxPerSec = Ui.dp(60);
        scroller = new OverScroller(c);
        rulerH = Ui.dp(22);
        oH = Ui.dp(34);
        vH = Ui.dp(54);
        tH = Ui.dp(26);
        aH = Ui.dp(38);
        gap = Ui.dp(5);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        // as tall as the tracks, up to what leaves the viewer a usable height (VideoEditor decides)
        int want = (int) contentH(), cap = ed.timelineMaxH();
        int minH = minH();   // the main track always fits
        setMeasuredDimension(MeasureSpec.getSize(ws), Math.max(minH, Math.min(want, cap > 0 ? cap : want)));
    }

    /** Ruler plus the main track: the timeline never gets shorter than this. */
    int minH() { return (int) (rulerH + vH + gap * 2); }

    float pxPerUs() { return pxPerSec / 1e6f; }
    float cx() { return getWidth() / 2f; }
    float xAt(long t) { return cx() + (t - ed.time) * pxPerUs(); }
    long tAt(float x) { return ed.time + (long) ((x - cx()) / pxPerUs()); }

    /** Overlay row (picture-in-picture, B-roll, logos) sits above the main track, like V2 over V1. */
    float oTop() { return rulerH + gap; }
    float vTop() { return oTop() + oH * nO + gap; }
    float tTop() { return vTop() + vH + gap; }
    float aTop() { return tTop() + tH * nT + gap; }
    private int laneOf(int id) { Integer k = lane.get(id); return k == null ? 0 : k; }
    float oY(VProject.TextItem t) { return oTop() + laneOf(t.id) * oH; }
    float tY(VProject.TextItem t) { return tTop() + laneOf(t.id) * tH; }
    float aY(VProject.AudioItem a) { return aTop() + laneOf(a.id) * aH; }

    /** Re-packs the lanes after an edit (not while dragging, so the item under the finger stays on its row). */
    void relayout() {
        if (drag != 0) return;
        VProject pr = ed.proj;
        lane.clear();
        int o0 = nO, t0 = nT, a0 = nA;
        nO = pack(pr, true);
        nT = pack(pr, false);
        int n = pr.audio.size();
        long[] s = new long[n], e = new long[n];
        for (int i = 0; i < n; i++) { s[i] = pr.audio.get(i).start; e[i] = s[i] + pr.audio.get(i).dur(); }
        int[] l = Timeline.lanes(s, e, LANES);
        for (int i = 0; i < n; i++) lane.put(pr.audio.get(i).id, l[i]);
        nA = l[n];
        oH = Ui.dp(nO > 1 ? 26 : 34);
        tH = Ui.dp(nT > 1 ? 22 : 26);
        aH = Ui.dp(nA > 1 ? 28 : 38);
        if (o0 != nO || t0 != nT || a0 != nA) requestLayout();
        vy = Math.max(0, Math.min(vy, maxVy()));
    }

    private int pack(VProject pr, boolean media) {
        java.util.ArrayList<VProject.TextItem> g = new java.util.ArrayList<VProject.TextItem>();
        for (VProject.TextItem t : pr.texts) if ((t.media != null) == media) g.add(t);
        long[] s = new long[g.size()], e = new long[g.size()];
        for (int i = 0; i < s.length; i++) { s[i] = g.get(i).start; e[i] = s[i] + g.get(i).dur; }
        int[] l = Timeline.lanes(s, e, LANES);
        for (int i = 0; i < s.length; i++) lane.put(g.get(i).id, l[i]);
        return l[s.length];
    }

    // ---------------------------------------------------------------- drawing
    @Override
    protected void onDraw(Canvas c) {
        VProject pr = ed.proj;
        int w = getWidth();
        c.drawColor(Ui.BG);
        p.setTextSize(Ui.dp(10));
        // ruler
        int fps = pr.fps();
        long t0 = tAt(0), t1 = tAt(w);
        p.setStyle(Paint.Style.FILL);
        // zoomed in: ticks every 1, 2, 5, 10 or half a second of frames, labelled m:ss:ff
        int[] fsteps = {1, 2, 5, 10, fps / 2};
        int kf = 0;
        for (int f : fsteps) if (f * pxPerSec / fps >= Ui.dp(64)) { kf = f; break; }
        if (kf > 0) {
            for (long k = Math.max(0, Timeline.frameIndex(t0, fps) / kf); ; k++) {
                long t = Timeline.frameTime(k * kf, fps);
                if (t > t1) break;
                float x = xAt(t);
                p.setColor(Ui.LINE);
                c.drawRect(x, rulerH - Ui.dp(7), x + Ui.dp(1), rulerH, p);
                p.setColor(Ui.DIM);
                c.drawText(Timeline.timecode(t, fps), x + Ui.dp(3), rulerH - Ui.dp(9), p);
            }
            // single-frame ticks when they're far enough apart to read
            if (pxPerSec / fps >= Ui.dp(6)) {
                p.setColor(0x30ffffff);
                for (long k = Math.max(0, Timeline.frameIndex(t0, fps)); ; k++) {
                    long t = Timeline.frameTime(k, fps);
                    if (t > t1) break;
                    float x = xAt(t);
                    c.drawRect(x, rulerH - Ui.dp(3), x + Ui.dp(1), rulerH, p);
                }
            }
        } else {
            double[] steps = {1, 2, 5, 10, 15, 30, 60, 120, 300};
            double step = steps[steps.length - 1];
            for (double s : steps) if (s * pxPerSec >= Ui.dp(64)) { step = s; break; }
            long st = (long) (step * 1e6);
            for (long t = Math.max(0, (t0 / st) * st); t <= t1; t += st) {
                float x = xAt(t);
                p.setColor(Ui.LINE);
                c.drawRect(x, rulerH - Ui.dp(7), x + Ui.dp(1), rulerH, p);
                p.setColor(Ui.DIM);
                c.drawText(fmt(t, false), x + Ui.dp(3), rulerH - Ui.dp(9), p);
            }
        }
        // beats
        long[] beats = pr.beatTimes();
        p.setColor(0xaae9a23b);
        for (long b : beats) {
            float x = xAt(b);
            if (x < -2 || x > w + 2) continue;
            c.drawRect(x - Ui.dp(0.75f), rulerH - Ui.dp(5), x + Ui.dp(0.75f), getHeight(), p);
        }
        // 3.13: markers, a coloured flag on the ruler and a faint line through the tracks
        for (VProject.Marker m : pr.markers) {
            float x = xAt(m.t);
            if (x < -Ui.dp(8) || x > w + Ui.dp(8)) continue;
            int col = VProject.MARKER_COLORS[m.color];
            p.setColor((col & 0x00ffffff) | 0x55000000);
            c.drawRect(x - Ui.dp(0.5f), rulerH, x + Ui.dp(0.5f), getHeight(), p);
            p.setColor(col);
            path.reset();
            path.moveTo(x - Ui.dp(5), 0); path.lineTo(x + Ui.dp(5), 0); path.lineTo(x + Ui.dp(5), Ui.dp(9));
            path.lineTo(x, Ui.dp(14)); path.lineTo(x - Ui.dp(5), Ui.dp(9)); path.close();
            c.drawPath(path, p);
        }
        // tracks scroll under the fixed ruler
        c.save();
        c.clipRect(0, rulerH, w, getHeight());
        c.translate(0, -vy);
        // lane guides (a faint band per extra lane)
        p.setColor(0x08ffffff);
        for (int k = 1; k < nO; k++) c.drawRect(0, oTop() + k * oH, w, oTop() + k * oH + Ui.dp(1), p);
        for (int k = 1; k < nT; k++) c.drawRect(0, tTop() + k * tH, w, tTop() + k * tH + Ui.dp(1), p);
        for (int k = 1; k < nA; k++) c.drawRect(0, aTop() + k * aH, w, aTop() + k * aH + Ui.dp(1), p);
        // video track
        long[] starts = pr.starts();
        float top = vTop();
        for (int i = 0; i < pr.clips.size(); i++) {
            VProject.Clip cl = pr.clips.get(i);
            float x0 = xAt(starts[i]), x1 = xAt(starts[i] + cl.dur());
            if (x1 < 0 || x0 > w) continue;
            drawClip(c, cl, x0, x1, top);
        }
        // "+" tile after the last clip (the CapCut / LumaFusion way to add more)
        RectF add = addTile();
        if (add.right > 0 && add.left < w) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xff23262c);
            c.drawRoundRect(add, Ui.dp(6), Ui.dp(6), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1));
            p.setColor(0x40ffffff);
            c.drawRoundRect(add, Ui.dp(6), Ui.dp(6), p);
            p.setStyle(Paint.Style.FILL);
            int s = Ui.dp(22);
            plusIcon.setBounds((int) (add.centerX() - s / 2), (int) (add.centerY() - s / 2), (int) (add.centerX() + s / 2), (int) (add.centerY() + s / 2));
            plusIcon.draw(c);
        }
        // transitions
        long[] durs = pr.durs(), tr = pr.transDurs();
        for (int i = 0; i < pr.clips.size() - 1; i++) {
            long T = Timeline.trans(durs, tr, i);
            float x = xAt(starts[i + 1] + T / 2);
            if (x < -20 || x > w + 20) continue;
            boolean sel = ed.selKind == SEL_CLIP && ed.selId == pr.clips.get(i).id && ed.transFocus;
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? Ui.ACCENT : 0xffe7e7e9);
            float r = Ui.dp(T > 0 ? 7 : 5), cy = top + vH / 2;
            path.reset();
            path.moveTo(x, cy - r); path.lineTo(x + r, cy); path.lineTo(x, cy + r); path.lineTo(x - r, cy); path.close();
            c.drawPath(path, p);
            if (T > 0) {
                p.setColor(0x55000000);
                c.drawRect(xAt(starts[i + 1]), top, xAt(starts[i + 1] + T), top + Ui.dp(4), p);
            }
            if (sel) {   // roll grip: drag the cut
                p.setColor(Ui.ACCENT);
                c.drawRoundRect(new RectF(x - Ui.dp(2), top - Ui.dp(3), x + Ui.dp(2), top + vH + Ui.dp(3)), Ui.dp(2), Ui.dp(2), p);
                float ay = top + Ui.dp(9), aw = Ui.dp(5);
                path.reset();
                path.moveTo(x - Ui.dp(4), ay - aw); path.lineTo(x - Ui.dp(4) - aw, ay); path.lineTo(x - Ui.dp(4), ay + aw); path.close();
                path.moveTo(x + Ui.dp(4), ay - aw); path.lineTo(x + Ui.dp(4) + aw, ay); path.lineTo(x + Ui.dp(4), ay + aw); path.close();
                c.drawPath(path, p);
            }
        }
        // overlay row
        top = oTop();
        boolean anyMedia = false;
        for (VProject.TextItem ti : pr.texts) {
            if (ti.media == null) continue;
            anyMedia = true;
            float x0 = xAt(ti.start), x1 = xAt(ti.start + ti.dur);
            if (x1 < 0 || x0 > w) continue;
            drawOverlayItem(c, ti, x0, x1, oY(ti), ed.itemSelected(SEL_MEDIA,ti.id));
            if (ti.link.on) connector(c, x0, oY(ti) + oH, vTop());
        }
        if (!anyMedia) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x0fffffff);
            c.drawRoundRect(new RectF(0, top, w, top + oH), Ui.dp(4), Ui.dp(4), p);
            p.setColor(Ui.DIM);
            p.setTextSize(Ui.dp(11));
            String s = "+ Overlay: picture-in-picture, B-roll, logo";
            c.drawText(s, cx() - p.measureText(s) / 2, top + oH / 2 + Ui.dp(4), p);
        }
        // the selected overlay is drawn last so its handles stay on top of neighbours
        if (ed.selKind == SEL_MEDIA && !ed.selectingMany) {
            VProject.TextItem s = pr.textById(ed.selId);
            if (s != null && s.media != null) {
                float x0 = xAt(s.start), x1 = xAt(s.start + s.dur), y = oY(s);
                drawOverlayItem(c, s, x0, x1, y, true);
                if(!s.timingLocked)handles(c, x0, x1, y, oH);
                keyMarks(c, s.keys, s.start, y + oH);
            }
        }
        // text track
        top = tTop();
        for (VProject.TextItem ti : pr.texts) {
            if (ti.media != null) continue;
            float x0 = xAt(ti.start), x1 = xAt(ti.start + ti.dur);
            if (x1 < 0 || x0 > w) continue;
            boolean viz = ti.viz != null;
            boolean sel = ed.itemSelected(viz?SEL_VIZ:SEL_TEXT,ti.id);
            top = tY(ti);
            if (ti.link.on) connector(c, x0, top, vTop() + vH);
            p.setStyle(Paint.Style.FILL);
            p.setColor(viz ? (sel ? 0xff3b2350 : 0xff2a2233) : (sel ? 0xff4a3a1c : 0xff2c2a26));
            RectF r = new RectF(x0, top, x1, top + tH);
            c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
            p.setColor(sel ? Ui.ACCENT : (viz ? 0xffc6a6e8 : 0xffc9b48a));
            p.setTextSize(Ui.dp(11));
            c.save();
            c.clipRect(r);
            String label = viz ? "≋  " + VProject.Viz.TYPES[ti.viz.type] : (ti.caption ? "CC  " : "T  ") + ti.layer.text.replace('\n', ' ');
            if(ti.timingLocked)label="Locked · "+label;
            if (!ti.keys.isEmpty()) label += "  ◆";
            if (ti.pulse > 0 || ti.shake > 0 || ti.flash > 0) label += "  ♫";
            c.drawText(label, x0 + Ui.dp(6), top + tH / 2 + Ui.dp(4), p);
            c.restore();
            if (sel && !ed.selectingMany && !ti.timingLocked) {
                handles(c, x0, x1, top, tH);
                keyMarks(c, ti.keys, ti.start, top + tH);
            }
        }
        // music track
        top = aTop();
        for (VProject.AudioItem ai : pr.audio) {
            float x0 = xAt(ai.start), x1 = xAt(ai.start + ai.dur());
            if (x1 < 0 || x0 > w) continue;
            boolean sel = ed.itemSelected(SEL_AUDIO,ai.id);
            top = aY(ai);
            if (ai.link.on) connector(c, x0, top, vTop() + vH);
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? 0xff1d3a33 : 0xff19262a);
            RectF r = new RectF(x0, top, x1, top + aH);
            c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
            float[] pk = ed.peaksFor(ai.uri);
            if (pk != null) {
                p.setColor(sel ? 0xff6fd3b8 : 0xff4f9e8a);
                float mid = top + aH / 2;
                float xa = Math.max(0, x0), xb = Math.min(w, x1);
                for (float x = xa; x < xb; x += Ui.dp(2)) {
                    long srcUs = ai.in + (long) ((x - x0) / pxPerUs());
                    int k = (int) (srcUs / 10000);
                    if (k < 0 || k >= pk.length) continue;
                    // max over the span one bar covers
                    int k2 = Math.min(pk.length - 1, (int) ((srcUs + Ui.dp(2) / pxPerUs()) / 10000));
                    float m = 0;
                    for (int j = k; j <= k2; j++) m = Math.max(m, pk[j]);
                    float hh = Math.max(Ui.dp(0.5f), m * aH * 0.45f);
                    c.drawRect(x, mid - hh, x + Ui.dp(1.2f), mid + hh, p);
                }
            }
            p.setColor(sel ? Ui.ACCENT : Ui.DIM);
            p.setTextSize(Ui.dp(10));
            c.save();
            c.clipRect(r);
            c.drawText((ai.timingLocked?"Locked · ":"")+(ai.id == pr.syncId ? "♪ Guide · " : "♪ ") + ai.name, x0 + Ui.dp(6), top + Ui.dp(12), p);
            // gain line (volume keys x ducking x fades): top of the item = 100%, bottom = silent
            float[] dk = ed.previewDuck();
            if (!ai.vol.isEmpty() || dk != null) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(1.5f));
                p.setColor(sel ? 0xffffe08a : 0xaaffe08a);
                path.reset();
                boolean first = true;
                float xa = Math.max(0, x0), xb = Math.min(w, x1);
                for (float x = xa; x <= xb; x += Ui.dp(2)) {
                    long t = tAt(x);
                    float g = VProject.musicVol(ai, t) * Duck.at(dk, t) * VProject.fade(t, ai.start, ai.dur(), ai.fadeIn, ai.fadeOut);
                    float y = top + aH - Ui.dp(3) - Math.min(1f, g) * (aH - Ui.dp(16));
                    if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
                }
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
            }
            c.restore();
            if (sel && !ed.selectingMany && !ai.timingLocked) {
                handles(c, x0, x1, top, aH);
                keyMarks(c, ai.vol, ai.start, top + aH);
            }
        }
        c.restore();
        // scroll bar, so tracks hidden below (or above) are never a secret
        float mv = maxVy();
        if (mv > 0) {
            float viewH = getHeight() - rulerH, bh = Math.max(Ui.dp(24), viewH * viewH / (viewH + mv));
            float by = rulerH + (viewH - bh) * (vy / mv);
            p.setColor(0x66ffffff);
            c.drawRoundRect(new RectF(w - Ui.dp(4), by, w - Ui.dp(1), by + bh), Ui.dp(2), Ui.dp(2), p);
        }
        // playhead
        p.setColor(0xffffffff);
        c.drawRect(cx() - Ui.dp(1), rulerH - Ui.dp(4), cx() + Ui.dp(1), getHeight(), p);
        path.reset();
        path.moveTo(cx() - Ui.dp(6), 0); path.lineTo(cx() + Ui.dp(6), 0); path.lineTo(cx(), Ui.dp(8)); path.close();
        c.drawPath(path, p);
    }

    private void drawClip(Canvas c, VProject.Clip cl, float x0, float x1, float top) {
        boolean sel = ed.itemSelected(SEL_CLIP,cl.id);
        RectF r = new RectF(x0 + Ui.dp(1), top, x1 - Ui.dp(1), top + vH);
        c.save();
        c.clipRect(r);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff23262c);
        c.drawRect(r, p);
        float tw = vH * 16 / 9f;
        float start = Math.max(x0, -tw);
        start = x0 + (float) Math.floor((start - x0) / tw) * tw;
        Rect src = new Rect();
        RectF dst = new RectF();
        for (float x = start; x < Math.min(x1, getWidth()); x += tw) {
            long srcUs = cl.srcAt((long) ((x - x0 + tw / 2) / pxPerUs()));
            Bitmap b = cl.kind == VProject.IMAGE ? cl.still : ed.thumbFor(cl.uri, srcUs);
            if (b == null || b.isRecycled()) continue;
            float bw = b.getWidth(), bh = b.getHeight();
            float k = Math.max(tw / bw, vH / bh);
            float sw = tw / k, sh = vH / k;
            src.set((int) ((bw - sw) / 2), (int) ((bh - sh) / 2), (int) ((bw + sw) / 2), (int) ((bh + sh) / 2));
            dst.set(x, top, x + tw, top + vH);
            c.drawBitmap(b, src, dst, null);
        }
        if (cl.kind == VProject.VIDEO && cl.hasAudio) {
            final VProject.Clip k = cl;
            boolean silent = cl.mute || cl.curve != null;   // a ramped clip's own sound is muted
            footageWave(c, cl.uri, x0, x1, r.bottom - Ui.dp(16), vH * 0.3f, cl.dur(), silent ? 0 : cl.volume,
                    new Waveform.SrcMap() { public long src(long t) { return k.srcAt(t); } });
        }
        p.setColor(0x99000000);
        c.drawRect(r.left, r.bottom - Ui.dp(16), r.right, r.bottom, p);
        p.setColor(0xffe7e7e9);
        p.setTextSize(Ui.dp(10));
        String label = (cl.kind == VProject.IMAGE ? "▣ " : "") + fmtDur(cl.dur()) + (cl.keys.isEmpty() ? "" : "  ◆")
                + (cl.pulse > 0 || cl.shake > 0 || cl.flash > 0 || Music.hasLinks(cl.grade) ? "  ♫" : "")
                + (cl.curve != null ? "  curve" : Math.abs(cl.speed - 1) > 0.01f ? String.format(Locale.US, "  %.2fx", cl.speed) : "")
                + (cl.mute || !cl.hasAudio ? "" : "  ♪") + (cl.grade.isEmpty() ? "" : "  ◐");
        if(cl.timingLocked)label="Locked · "+label;
        c.drawText(label, r.left + Ui.dp(5), r.bottom - Ui.dp(4), p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(sel ? 2 : 1));
        p.setColor(sel ? Ui.ACCENT : 0x40ffffff);
        c.drawRoundRect(r, Ui.dp(3), Ui.dp(3), p);
        p.setStyle(Paint.Style.FILL);
        if (sel && !ed.transFocus && !ed.selectingMany && !cl.timingLocked) {
            handles(c, x0, x1, top, vH);
            keyMarks(c, cl.keys, ed.proj.start(ed.proj.clipIndex(cl.id)), top + vH);
            if (ed.slip && cl.kind == VProject.VIDEO) {
                p.setColor(Ui.ACCENT);
                p.setTextSize(Ui.dp(10));
                c.drawText("\u2194 slip", r.left + Ui.dp(10), r.top + Ui.dp(13), p);
            }
        }
    }

    private void drawOverlayItem(Canvas c, VProject.TextItem ti, float x0, float x1, float top, boolean sel) {
        VProject.Clip m = ti.media;
        RectF r = new RectF(x0, top, x1, top + oH);
        p.setStyle(Paint.Style.FILL);
        p.setColor(sel ? 0xff23324a : 0xff1c2430);
        c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
        c.save();
        c.clipRect(r);
        float tw = oH * 16 / 9f;
        Rect src = new Rect();
        RectF dst = new RectF();
        float from = Math.max(x0, x0 + (float) Math.floor((Math.max(-tw, x0) - x0) / tw) * tw);
        for (float x = from; x < Math.min(x1, getWidth()); x += tw) {
            long t = ti.start + (long) ((x - x0 + tw / 2) / pxPerUs());
            Bitmap b = m.kind == VProject.IMAGE ? m.still : ed.thumbFor(m.uri, VProject.overlaySrc(ti, t));
            if (b == null || b.isRecycled()) continue;
            float bw = b.getWidth(), bh = b.getHeight(), k = Math.max(tw / bw, oH / bh), sw = tw / k, sh = oH / k;
            src.set((int) ((bw - sw) / 2), (int) ((bh - sh) / 2), (int) ((bw + sw) / 2), (int) ((bh + sh) / 2));
            dst.set(x, top, x + tw, top + oH);
            p.setAlpha(170);
            c.drawBitmap(b, src, dst, p);
            p.setAlpha(255);
            if (m.kind == VProject.IMAGE) break;   // a still needs one picture
        }
        if (m.kind == VProject.VIDEO && m.hasAudio) {
            final VProject.TextItem k = ti;
            footageWave(c, m.uri, x0, x1, r.bottom - Ui.dp(14), oH * 0.32f, ti.dur, m.mute ? 0 : m.volume,
                    new Waveform.SrcMap() { public long src(long t) { return VProject.overlaySrc(k, k.start + t); } });
        }
        p.setColor(0x99000000);
        c.drawRect(r.left, r.bottom - Ui.dp(14), r.right, r.bottom, p);
        p.setColor(sel ? Ui.ACCENT : 0xff9fc2ea);
        p.setTextSize(Ui.dp(10));
        String label = (m.kind == VProject.IMAGE ? "\u25a3 " : "\u25b6 ") + m.name
                + (m.kind == VProject.VIDEO && !m.mute && m.hasAudio ? "  \u266a" : "") + (ti.keys.isEmpty() ? "" : "  \u25c6") + (ti.pulse>0||ti.shake>0||ti.flash>0||Music.hasLinks(m.grade)?"  ♫":"");
        if(ti.timingLocked)label="Locked · "+label;
        c.drawText(label, r.left + Ui.dp(5), r.bottom - Ui.dp(3.5f), p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(sel ? 2 : 1));
        p.setColor(sel ? Ui.ACCENT : 0x40ffffff);
        c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * 3.13: footage sound under the thumbnails, from the bottom edge up, scaled by the clip's volume
     * (flat line when muted). Only the visible part is computed, one column per 2 dp.
     */
    private void footageWave(Canvas c, String uri, float x0, float x1, float bottom, float h, long dur, float gain, Waveform.SrcMap map) {
        float[] pk = ed.peaksFor(uri);
        if (pk == null) { ed.requestPeaks(uri); return; }
        float xa = Math.max(0, x0), xb = Math.min(getWidth(), x1);
        if (xb <= xa) return;
        float colW = Ui.dp(2);
        int n = (int) Math.ceil((xb - xa) / colW);
        long la = (long) ((xa - x0) / pxPerUs()), lb = Math.min(dur, (long) ((xb - x0) / pxPerUs()));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66000000);
        c.drawRect(xa, bottom - h, xb, bottom, p);
        p.setColor(gain > 0 ? 0xcc8fe3cf : 0x66a0a4ab);
        if (gain <= 0) { c.drawRect(xa, bottom - Ui.dp(1.5f), xb, bottom - Ui.dp(0.5f), p); return; }
        float[] col = Waveform.columns(pk, map, la, lb, n, gain);
        for (int i = 0; i < n; i++) {
            float hh = Math.max(Ui.dp(0.5f), col[i] * (h - Ui.dp(1)));
            float x = xa + i * colW;
            c.drawRect(x, bottom - hh, Math.min(xb, x + colW - Ui.dp(0.6f)), bottom, p);
        }
    }

    /** Line from a connected item's start (edge y0) to the main track (edge y1), with a dot where it joins. */
    private void connector(Canvas c, float x, float y0, float y1) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xccd8dbe2);
        float a = Math.min(y0, y1), b = Math.max(y0, y1);
        c.drawRect(x, a, x + Ui.dp(1.5f), b, p);
        c.drawCircle(x + Ui.dp(0.75f), y1, Ui.dp(2.5f), p);
    }

    /** Small diamonds under the selected item at its keyframes. */
    private void keyMarks(Canvas c, Keys k, long start, float y) {
        if (k.isEmpty()) return;
        p.setStyle(Paint.Style.FILL);
        float r = Ui.dp(4.5f);
        long tol = ed.frameTol();
        for (Keys.Key key : k.list) {
            if (key.t < 0) continue;   // pre-roll key left by a split or head trim: before the item, not shown
            float x = xAt(start + key.t);
            if (x < -r || x > getWidth() + r) continue;
            boolean here = Math.abs(start + key.t - ed.time) <= tol;
            p.setColor(here ? 0xffffffff : Ui.ACCENT);
            path.reset();
            path.moveTo(x, y - 2 * r); path.lineTo(x + r, y - r); path.lineTo(x, y); path.lineTo(x - r, y - r); path.close();
            c.drawPath(path, p);
        }
    }

    private final IconDrawable plusIcon = new IconDrawable("plus", Ui.ACCENT);

    /** Screen rect of the add tile at the end of the main track. */
    private RectF addTile() {
        VProject pr = ed.proj;
        long end = pr.clips.isEmpty() ? 0 : pr.starts()[pr.clips.size() - 1] + pr.clips.get(pr.clips.size() - 1).dur();
        float x = xAt(end) + Ui.dp(pr.clips.isEmpty() ? 0 : 6);
        return new RectF(x, vTop(), x + Ui.dp(48), vTop() + vH);
    }

    private void handles(Canvas c, float x0, float x1, float top, float h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.ACCENT);
        float hw = Ui.dp(9);
        c.drawRoundRect(new RectF(x0, top, x0 + hw, top + h), Ui.dp(2), Ui.dp(2), p);
        c.drawRoundRect(new RectF(x1 - hw, top, x1, top + h), Ui.dp(2), Ui.dp(2), p);
        p.setColor(0xff111111);
        c.drawRect(x0 + hw / 2 - Ui.dp(0.75f), top + h * 0.35f, x0 + hw / 2 + Ui.dp(0.75f), top + h * 0.65f, p);
        c.drawRect(x1 - hw / 2 - Ui.dp(0.75f), top + h * 0.35f, x1 - hw / 2 + Ui.dp(0.75f), top + h * 0.65f, p);
    }

    static String fmt(long us, boolean tenths) {
        long ms = us / 1000;
        long m = ms / 60000, s = (ms / 1000) % 60, d = (ms / 100) % 10;
        return tenths ? String.format(Locale.US, "%d:%02d.%d", m, s, d) : String.format(Locale.US, "%d:%02d", m, s);
    }

    static String fmtDur(long us) {
        double s = us / 1e6;
        return s < 60 ? String.format(Locale.US, "%.1fs", s) : String.format(Locale.US, "%d:%04.1f", (int) (s / 60), s % 60);
    }

    /** Scrolls the tracks so the selected item's lane is in view. */
    void revealSelection() {
        float[] sp = selSpan();
        if (sp == null || getHeight() <= 0) return;
        float top = sp[2] - vy, bot = sp[3] - vy, h = getHeight();
        if (top < rulerH) vy -= rulerH - top + Ui.dp(4);
        else if (bot > h) vy += bot - h + Ui.dp(4);
        vy = Math.max(0, Math.min(vy, maxVy()));
        invalidate();
    }

    /** Frame the whole selected range so both trim edges are reachable on a phone. */
    void fitSelection() {
        long start, duration;
        if(ed.selectingMany){if(ed.batchIds.isEmpty())return;long[] range=TimelineBatch.span(ed.proj,ed.batchIds);start=range[0];duration=range[1]-range[0];}
        else if(ed.selClip()!=null){start=ed.proj.start(ed.proj.clipIndex(ed.selId));duration=ed.selClip().dur();}
        else if(ed.selOverlay()!=null){start=ed.selOverlay().start;duration=ed.selOverlay().dur;}
        else if(ed.selAudio()!=null){start=ed.selAudio().start;duration=ed.selAudio().dur();}
        else return;
        pxPerSec=Math.max(Ui.dp(4),Math.min(Ui.dp(1200),Math.max(Ui.dp(80),getWidth()-Ui.dp(80))*1e6f/Math.max(1,duration)));
        ed.scrubTo(start+duration/2);revealSelection();invalidate();
    }

    // ---------------------------------------------------------------- input
    /** Selected item's span on screen, for handle hit tests. */
    private float[] selSpan() {
        if(ed.selectingMany||TimelineLocks.locked(ed.proj,ed.selId))return null;
        VProject pr = ed.proj;
        if (ed.selKind == SEL_CLIP) {
            int i = pr.clipIndex(ed.selId);
            if (i < 0 || ed.transFocus) return null;
            long s = pr.start(i);
            return new float[]{xAt(s), xAt(s + pr.clips.get(i).dur()), vTop(), vTop() + vH};
        }
        if (ed.selKind == SEL_TEXT || ed.selKind == SEL_VIZ) {
            VProject.TextItem t = pr.textById(ed.selId);
            return t == null ? null : new float[]{xAt(t.start), xAt(t.start + t.dur), tY(t), tY(t) + tH};
        }
        if (ed.selKind == SEL_MEDIA) {
            VProject.TextItem t = pr.textById(ed.selId);
            return t == null ? null : new float[]{xAt(t.start), xAt(t.start + t.dur), oY(t), oY(t) + oH};
        }
        if (ed.selKind == SEL_AUDIO) {
            VProject.AudioItem a = pr.audioById(ed.selId);
            return a == null ? null : new float[]{xAt(a.start), xAt(a.start + a.dur()), aY(a), aY(a) + aH};
        }
        return null;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            ed.scrubTo(scroller.getCurrX() * 1000L);
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                scroller.forceFinished(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                vScroll = false;
                multi = false;
                moved = false;
                drag = 0;
                begun = false;
                float[] sp = selSpan();
                float hit = Ui.dp(20);
                float yy = ty(e.getY());
                if(ed.selectingMany&&ed.moveItems&&batchBodyHit(e.getX(),yy))drag=6;
                if (sp != null && e.getY() > rulerH && yy >= sp[2] - Ui.dp(6) && yy <= sp[3] + Ui.dp(6)) {
                    float left = Math.abs(e.getX() - sp[0]), right = Math.abs(e.getX() - sp[1]);
                    if (Math.min(left, right) < hit) drag = left <= right ? 1 : 2;
                    else if (e.getX() > sp[0] && e.getX() < sp[1])
                        drag = ed.selKind != SEL_CLIP ? (ed.moveItems ? 3 : 0) : ed.slip && ed.selClip() != null && ed.selClip().kind == VProject.VIDEO ? 5 : 0;
                }
                if (drag == 0 && !ed.selectingMany && !TimelineLocks.locked(ed.proj,ed.selId) && ed.selKind == SEL_CLIP && ed.transFocus && yy >= vTop() - Ui.dp(8) && yy <= vTop() + vH + Ui.dp(8)) {
                    float cx = cutX();
                    if (!Float.isNaN(cx) && Math.abs(e.getX() - cx) < hit) drag = 4;
                }
                if (drag != 0) {
                    dragT0 = tAt(e.getX());
                    snapCache = ed.snapTargets();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    multi = true;
                    pinchDist = Math.abs(e.getX(1) - e.getX(0));
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (multi && e.getPointerCount() >= 2) {
                    float d = Math.max(1, Math.abs(e.getX(1) - e.getX(0)));
                    pxPerSec = Math.max(Ui.dp(4), Math.min(Ui.dp(1200), pxPerSec * d / Math.max(1, pinchDist)));
                    pinchDist = d;
                    invalidate();
                    return true;
                }
                if (multi) return true;
                // a mostly vertical drag on the tracks scrolls them (when they don't all fit)
                if (drag == 0 && !moved && !vScroll && maxVy() > 0 && Math.abs(e.getY() - downY) > Ui.dp(8)
                        && Math.abs(e.getY() - downY) > Math.abs(e.getX() - downX) * 1.2f) vScroll = true;
                if (vScroll) {
                    vy = Math.max(0, Math.min(maxVy(), vy - (e.getY() - lastY)));
                    lastY = e.getY();
                    invalidate();
                    return true;
                }
                lastY = e.getY();
                if (Math.abs(e.getX() - downX) > Ui.dp(6)) moved = true;
                if (drag == 0) {
                    ed.scrubTo(ed.time - (long) ((e.getX() - lastX) / pxPerUs()));
                } else if (moved) {
                    if (!begun) {   // a tap on a handle is still a tap: the undo step starts with the first move
                        begun = true;
                        ed.pause();
                        ed.applyPreview();   // drags read the undo top as their "before" state
                        ed.beginEdit();
                    }
                    long t = tAt(e.getX());
                    ed.dragEdit(drag, (long) ((e.getX() - downX) / pxPerUs()), snapCache, (long) (Ui.dp(10) / pxPerUs()));
                }
                lastX = e.getX();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!multi && !moved && !vScroll) tap(e.getX(), ty(e.getY()));
                else if (drag == 0 && !multi && !vScroll) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getXVelocity();
                    if (Math.abs(v) > Ui.dp(300)) {
                        long dur = ed.proj.duration();
                        scroller.fling((int) (ed.time / 1000), 0, (int) (-v / pxPerUs() / 1000), 0, 0, (int) (dur / 1000), 0, 0);
                        postInvalidateOnAnimation();
                    }
                }
                boolean edited = drag != 0 && begun;
                drag = 0;
                if (edited) ed.dragDone();
                vt.recycle();
                vt = null;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                boolean edited = drag != 0 && begun;
                boolean batchDrag=drag==6;
                drag = 0;
                if (edited) {if(batchDrag)ed.cancelBatchDrag();else ed.dragDone();}
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
            }
        }
        return true;
    }

    private boolean batchBodyHit(float x,float y) {
        long at=tAt(x);
        for(VProject.TextItem t:ed.proj.texts)if(ed.batchIds.contains(t.id)&&at>=t.start&&at<t.start+t.dur){float top=t.media!=null?oY(t):tY(t),height=t.media!=null?oH:tH;if(y>=top&&y<=top+height)return true;}
        for(VProject.AudioItem a:ed.proj.audio)if(ed.batchIds.contains(a.id)&&at>=a.start&&at<a.start+a.dur()&&y>=aY(a)&&y<=aY(a)+aH)return true;
        return false;
    }

    /** Screen x of the selected transition's cut, NaN if none. */
    private float cutX() {
        VProject pr = ed.proj;
        int i = pr.clipIndex(ed.selId);
        if (i < 0 || i >= pr.clips.size() - 1) return Float.NaN;
        long[] st = pr.starts();
        return xAt(st[i + 1] + Timeline.trans(pr.durs(), pr.transDurs(), i) / 2);
    }

    private void tap(float x, float y) {
        VProject pr = ed.proj;
        long t = tAt(x);
        if (y <= rulerH) {
            VProject.Marker m = pr.markerNear(t, (long) (Ui.dp(10) / pxPerUs()));
            if (m != null) { ed.pause(); ed.scrubTo(m.t); ed.markerSheet(m); return; }
            ed.scrubTo(Timeline.quant(Math.max(0,t),pr.fps())); return;
        }
        if (y >= oTop() && y <= oTop() + oH * nO) {
            // topmost overlay under the finger in that lane; an empty spot offers to add one
            int ln = Math.min(nO - 1, (int) ((y - oTop()) / oH));
            for (int k = pr.texts.size() - 1; k >= 0; k--) {
                VProject.TextItem ti = pr.texts.get(k);
                if (ti.media != null && laneOf(ti.id) == ln && t >= ti.start && t < ti.start + ti.dur) { ed.timelineSelect(SEL_MEDIA, ti.id, false); ed.scrubTo(Timeline.quant(t,pr.fps())); return; }
            }
            ed.timelineSelect(SEL_NONE, -1, false);
            if(!ed.selectingMany)ed.overlaySheet();
            return;
        }
        if (y >= vTop() && y <= vTop() + vH) {
            if (addTile().contains(x, y)) { if(!ed.selectingMany)ed.addMedia(); return; }
            long[] starts = pr.starts(), durs = pr.durs(), tr = pr.transDurs();
            // transition diamonds first
            for (int i = 0; i < pr.clips.size() - 1; i++) {
                float dx = xAt(starts[i + 1] + Timeline.trans(durs, tr, i) / 2);
                if (Math.abs(x - dx) < Ui.dp(14)) { ed.timelineSelect(SEL_CLIP, pr.clips.get(i).id, true); return; }
            }
            int i = Timeline.clipAt(durs, tr, t);
            if (i >= 0) { ed.timelineSelect(SEL_CLIP, pr.clips.get(i).id, false); ed.scrubTo(Timeline.quant(t,pr.fps())); return; }
        } else if (y >= tTop() && y <= tTop() + tH * nT) {
            int ln = Math.min(nT - 1, (int) ((y - tTop()) / tH));
            for (int k = pr.texts.size() - 1; k >= 0; k--) {
                VProject.TextItem ti = pr.texts.get(k);
                if (ti.media != null || laneOf(ti.id) != ln) continue;
                if (t >= ti.start && t < ti.start + ti.dur) { ed.timelineSelect(ti.viz != null ? SEL_VIZ : SEL_TEXT, ti.id, false); ed.scrubTo(Timeline.quant(t,pr.fps())); return; }
            }
        } else if (y >= aTop() && y <= aTop() + aH * nA) {
            int ln = Math.min(nA - 1, (int) ((y - aTop()) / aH));
            for (VProject.AudioItem a : pr.audio)
                if (laneOf(a.id) == ln && t >= a.start && t < a.start + a.dur()) { ed.timelineSelect(SEL_AUDIO, a.id, false); ed.scrubTo(Timeline.quant(t,pr.fps())); return; }
        }
        ed.timelineSelect(SEL_NONE, -1, false);
    }
}
