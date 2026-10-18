package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * Video frame compositor, shared by the preview and the exporter (each owns one instance on its
 * own GL thread). For a time t: render the playing clip (and the incoming one during a
 * transition) into its own pair of buffers, run that clip's grade layers, mix the transition,
 * draw active text items, then the project-wide grade. Result: straight colour + alpha in one
 * of five RGBA16F buffers; the caller composites it over the background (display/output pass).
 */
final class VComp extends GlBase {
    static final String[] PROGS = {"layer_pixel", "adj_basic", "adj_curves", "adj_levels", "adj_hsl", "adj_lut",
            "video_src", "video_src_tex", "transition", "video_out", "video_display", "viz", "adj_fx", "fx_blur", "fx_gen", "fx_remap", "echo", "group_mix"};

    static final class Frame {
        int tex;
        boolean oes;
        final float[] st = new float[16];
    }

    static final class TextSrc {
        Bitmap bmp;
        RectF rect;
    }

    interface Frames {
        /** The clip's current frame texture, or null if none is ready. */
        Frame frame(VProject.Clip c);
        /** Bitmap of a text item and the layer-px rect it covers. */
        TextSrc text(VProject.TextItem t);
    }

    final int[] tx = new int[5], fb = new int[5];
    int bw, bh;
    int soloId; // preview-only; exporter leaves this zero
    private int vizTex, vizFb, vizW, vizH, dataTex;
    private final java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(Visuals.W * Visuals.ROWS);
    private final byte[] dataBytes = new byte[Visuals.W * Visuals.ROWS];
    private final Visuals.Work vizWork = new Visuals.Work();
    /** Largest visualizer buffer side: preview passes 2048, export 4096. */
    int vizCap = 2048;
    /** Echo accumulation buffer (premultiplied, half-float), grown like vizFb. */
    private int echoTex, echoFb, echoW, echoH;
    private static final float[] I0 = {1, 0, 0}, I1 = {0, 1, 0}, FULL = {0, 0, 1, 1};

    VComp(Context ctx) { super(ctx); }

    void init() throws Exception {
        initGl(PROGS);
        bw = bh = 0;
        vizW = vizH = 0;
        dataTex = newTex();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, Visuals.W, Visuals.ROWS, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    void ensure(int w, int h) {
        if (w <= bw && h <= bh) return;
        int nw = Math.max(w, bw), nh = Math.max(h, bh);
        for (int i = 0; i < 5; i++) {
            if (bw > 0) {
                GLES20.glDeleteTextures(1, new int[]{tx[i]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{fb[i]}, 0);
            }
            int[] f = makeFbo(nw, nh, true);
            tx[i] = f[0];
            fb[i] = f[1];
        }
        bw = nw;
        bh = nh;
    }

    private void clear(int i, int vw, int vh) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[i]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    /** Renders frame t at vw x vh (any size with the output's aspect). Returns the buffer index. */
    int render(VProject p, long t, int vw, int vh, Frames fr) {
        ensure(vw, vh);
        float ow = p.outW(), oh = p.outH();
        bsx = vw / (float) bw; bsy = vh / (float) bh; tlx = 1f / bw; tly = 1f / bh;
        frameT = (t % 3600000000L) / 1e6f;   // wrapped so the hash keeps its precision
        long[] a = p.at(t);
        int res;
        if (a[0] < 0 || (soloId > 0 && p.clipById(soloId) == null)) {
            clear(0, vw, vh);
            res = 0;
        } else {
            int mainIndex = soloId > 0 ? p.clipIndex(soloId) : (int) a[0];
            int ra = clip(p, mainIndex, t, vw, vh, fr, 0, 1);
            if (a[1] >= 0 && soloId == 0) {
                int rb = clip(p, (int) a[1], t, vw, vh, fr, 2, 3);
                int prog = progs.get("transition");
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[4]);
                GLES20.glViewport(0, 0, vw, vh);
                GLES20.glUseProgram(prog);
                bind(prog, "uA", 0, tx[ra]);
                bind(prog, "uB", 1, tx[rb]);
                u1(prog, "uP", a[2] / 1e6f);
                VProject.Clip c = p.clips.get((int) a[0]);
                u1(prog, "uType", Math.max(1, c.trans));
                u2(prog, "uBelowScale", bsx, bsy);
                u2(prog, "uRes", vw, vh);   // 3.14: pixel aspect for spin and iris
                drawQuad(prog);
                res = 4;
            } else res = ra;
        }
        float ppd = vw / ow;
        if (!anyVisible(p.pgrade)) {
            // no footage look: every overlay in list order, as before
            for (VProject.TextItem ti : p.texts) res = item(p, ti, t, res, vw, vh, ow, oh, ppd, fr);
        } else {
            // footage first (main track, then video overlays: B-roll, picture-in-picture), the
            // footage look on that, then the graphics on top (titles, captions, logos, photos,
            // visualizers), so a look never recolours a logo or a caption
            for (VProject.TextItem ti : p.texts) if (VProject.isFootage(ti)) res = item(p, ti, t, res, vw, vh, ow, oh, ppd, fr);
            res = gradeComp(p.pgrade, p, t, res, vw, vh, ow, oh, ppd);
            for (VProject.TextItem ti : p.texts) if (!VProject.isFootage(ti)) res = item(p, ti, t, res, vw, vh, ow, oh, ppd, fr);
        }
        return gradeComp(p.grade, p, t, res, vw, vh, ow, oh, ppd);   // "over everything" stage
    }

    static boolean anyVisible(java.util.ArrayList<Layer> g) {
        for (Layer l : g) if (l.visible) return true;
        return false;
    }

    /** Draws one overlay item active at t over composition buffer res; returns the new result buffer. */
    private int item(VProject p, VProject.TextItem ti, long t, int res, int vw, int vh, float ow, float oh, float ppd, Frames fr) {
        if (soloId > 0 && (ti.media == null || ti.media.id != soloId)) return res;
        if (t < ti.start || t >= ti.start + ti.dur || !ti.layer.visible) return res;
        Layer l = ti.layer;
        float[] keep = VProject.textVals(ti);
        float[] xf = p.textXf(ti, t);
        VProject.setTextVals(ti, xf);
        l.opacity = xf[4] * VProject.itemFade(ti, t);
        Engine.Src src = null;
        if (ti.media != null) src = media(p, ti, xf, ppd, fr, t);
        else if (ti.viz != null) src = viz(p, ti, t, ppd);
        else {
            TextSrc s = fr.text(ti);
            if (s != null && s.bmp != null) src = new Engine.Src(tex(s.bmp, 0, true), s.rect);
        }
        if (src != null && l.opacity > 0.001f) {
            int dst = res == 4 ? 3 : 4;
            pass(l, "layer_pixel", tx[res], fb[dst], vw, vh, FULL, ow, oh, I0, I1, ow, oh, ppd, src);
            res = dst;
        }
        VProject.setTextVals(ti, keep);
        return res;
    }

    /** Runs a whole-video grade stack on the composition (buffers 0-2 are free once clips are composed). */
    private int gradeComp(java.util.ArrayList<Layer> g, VProject p, long t, int res, int vw, int vh, float ow, float oh, float ppd) {
        int openGroup = 0;
        for (Layer l0 : g) {
            if (l0.type == Layer.GROUP) {   // 3.14: a look group closes: mix by its Strength
                if (openGroup == l0.id) {
                    int dst = res == 4 ? 3 : 4;
                    groupMix(l0, tx[res], fb[dst], vw, vh, ow, oh, ppd, false);
                    res = dst; openGroup = 0;
                }
                continue;
            }
            if (!inOpenGroup(g, l0)) continue;
            if (!l0.visible) continue;
            if (l0.parent != 0 && openGroup != l0.parent) { snapshot(fb[res], vw, vh, false); openGroup = l0.parent; }
            Layer l = Music.at(MaskStack.at(l0, t), p, t);   // keyed mask shapes, then music links, at this frame
            int dst = res == 4 ? 3 : 4;
            if (l.type == Layer.EFFECT) {
                int scratch = res == 2 ? 0 : 2;
                res = effect(l, tx, fb, res, dst, scratch, vw, vh, FULL, ow, oh, I0, I1, ow, oh, ppd);
                continue;
            }
            pass(l, adjProgram(l.type), tx[res], fb[dst], vw, vh, FULL, ow, oh, I0, I1, ow, oh, ppd, null);
            res = dst;
        }
        return res;
    }

    /** One clip: source placement, then its grade layers, ping-ponging b0/b1. */
    private int clip(VProject p, int i, long t, int vw, int vh, Frames fr, int b0, int b1) {
        VProject.Clip c = p.clips.get(i);
        Frame f = fr.frame(c);
        if (f == null) { clear(b0, vw, vh); return b0; }
        float ow = p.outW(), oh = p.outH();
        float[] r0 = new float[3], r1 = new float[3];
        float[] xf = p.clipXf(i, t);
        VProject.rows(c, xf, ow, oh, r0, r1);
        int prog = progs.get(f.oes ? "video_src" : "video_src_tex");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[b0]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(prog);
        u1(prog, "uClipA", Math.max(0, Math.min(1, xf[4])));
        u1(prog, "uFlash", Math.max(0, Math.min(1, xf[5])));
        u3(prog, "uR0", r0[0], r0[1], r0[2]);
        u3(prog, "uR1", r1[0], r1[1], r1[2]);
        u2(prog, "uSrcSize", c.srcW, c.srcH);
        u2(prog, "uOutSize", ow, oh);
        if (f.oes) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, f.tex);
            int l = loc(prog, "uTexO");
            if (l >= 0) GLES20.glUniform1i(l, 0);
            int m = loc(prog, "uST");
            if (m >= 0) GLES20.glUniformMatrix4fv(m, 1, false, f.st, 0);
        } else bind(prog, "uTexI", 0, f.tex);
        drawQuad(prog);
        int cur = b0;
        float ppd = vw / ow;
        int openGroup = 0;
        for (Layer l0 : c.grade) {
            if (l0.type == Layer.GROUP) {
                if (openGroup == l0.id) {
                    int dst = cur == b0 ? b1 : b0;
                    groupMix(l0, tx[cur], fb[dst], vw, vh, ow, oh, ppd, false);
                    cur = dst; openGroup = 0;
                }
                continue;
            }
            if (!inOpenGroup(c.grade, l0)) continue;
            if (!l0.visible) continue;
            if (l0.parent != 0 && openGroup != l0.parent) { snapshot(fb[cur], vw, vh, false); openGroup = l0.parent; }
            Layer l = Music.at(MaskStack.at(l0, t - p.start(i)), p, t, c.id, p.start(i), c.dur());
            int dst = cur == b0 ? b1 : b0;
            if (l.type == Layer.EFFECT) {   // buffer 4 is only used after both clips are done
                cur = effect(l, tx, fb, cur, dst, 4, vw, vh, FULL, ow, oh, I0, I1, ow, oh, ppd);
                continue;
            }
            pass(l, adjProgram(l.type), tx[cur], fb[dst], vw, vh, FULL, ow, oh, I0, I1, ow, oh, ppd, null);
            cur = dst;
        }
        return cur;
    }

    // ---------------------------------------------------------------- media overlays
    private int ovlTex, ovlFb, ovlW, ovlH;
    /** Two more overlay-size buffers for grading B-roll / picture-in-picture before it is placed. */
    private final int[] ovlTx = new int[3], ovlFbs = new int[3];
    /** Largest side a video overlay is converted at: preview 1280, export 2160. */
    int ovlCap = 1280;

    /**
     * A media overlay's pixels as a layer source. Stills are bitmap textures already. A video
     * frame is an external (OES) texture, which the layer shader can't sample, so it is first
     * drawn 1:1 through video_src into a 2D buffer at about the size it will appear (no bigger
     * than the source or ovlCap), then placed like any layer: move, scale, rotate, blend,
     * opacity, keyframes and fades all apply. Overlays are drawn one at a time, so one buffer
     * serves them all.
     */
    private Engine.Src media(VProject p, VProject.TextItem ti, float[] xf, float ppd, Frames fr, long t) {
        VProject.Clip m = ti.media;
        Frame f = fr.frame(m);
        if (f == null) return null;
        Layer l = ti.layer;
        boolean graded = anyVisible(m.grade);
        if (!f.oes && !graded && ti.flash <= 0) return new Engine.Src(f.tex, new RectF(0, 0, l.srcW, l.srcH));
        float shown = Math.max(l.srcW, l.srcH) * Math.max(0.05f, xf[2]) * ppd;
        float k = Math.min(1f, Math.min(ovlCap, Math.max(16f, shown * 1.15f)) / Math.max(m.srcW, m.srcH));
        int cw = Math.max(2, Math.round(m.srcW * k)), ch = Math.max(2, Math.round(m.srcH * k));
        if (cw > ovlW || ch > ovlH) {
            if (ovlW > 0) {
                GLES20.glDeleteTextures(1, new int[]{ovlTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{ovlFb}, 0);
            }
            ovlW = Math.max(cw, ovlW);
            ovlH = Math.max(ch, ovlH);
            int[] b = makeFbo(ovlW, ovlH, true);
            ovlTex = b[0];
            ovlFb = b[1];
            for (int i = 1; i < 3; i++) {
                if (ovlTx[i] != 0) { GLES20.glDeleteTextures(1, new int[]{ovlTx[i]}, 0); GLES20.glDeleteFramebuffers(1, new int[]{ovlFbs[i]}, 0); ovlTx[i] = 0; }
            }
        }
        int prog = progs.get(f.oes ? "video_src" : "video_src_tex");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ovlFb);
        GLES20.glViewport(0, 0, cw, ch);
        GLES20.glUseProgram(prog);
        u1(prog, "uClipA", 1);
        u1(prog, "uFlash", ti.flash * AudioControl.drive(p, ti.reactAudio, m.id, ti.reactSrc, ti.reactAdapt, ti.reactPost, t));
        u3(prog, "uR0", m.srcW / (float) cw, 0, 0);   // buffer px -> source px, no rotation
        u3(prog, "uR1", 0, m.srcH / (float) ch, 0);
        u2(prog, "uSrcSize", m.srcW, m.srcH);
        u2(prog, "uOutSize", cw, ch);
        if (f.oes) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, f.tex);
            int lo = loc(prog, "uTexO");
            if (lo >= 0) GLES20.glUniform1i(lo, 0);
            int ms = loc(prog, "uST");
            if (ms >= 0) GLES20.glUniformMatrix4fv(ms, 1, false, f.st, 0);
        } else bind(prog, "uTexI", 0, f.tex);
        drawQuad(prog);
        int resTex = ovlTex;
        if (graded) resTex = gradeOverlay(ti, p, t, cw, ch);
        // the source covers (cw, ch) of the buffer; scale the rect so that region maps to the layer
        return new Engine.Src(resTex, new RectF(0, 0, l.srcW * ovlW / (float) cw, l.srcH * ovlH / (float) ch));
    }

    /**
     * The overlay's own grade (B-roll corrected on its own, like a main-track clip): runs its
     * layers on the overlay buffer at the overlay's own size, before it is placed. Effect sizes
     * are relative to the overlay, as a clip's are to the frame.
     */
    private int gradeOverlay(VProject.TextItem ti, VProject p, long t, int cw, int ch) {
        VProject.Clip m = ti.media;
        for (int i = 1; i < 3; i++) if (ovlTx[i] == 0) { int[] b = makeFbo(ovlW, ovlH, true); ovlTx[i] = b[0]; ovlFbs[i] = b[1]; }
        ovlTx[0] = ovlTex; ovlFbs[0] = ovlFb;
        float kx = bsx, ky = bsy, lx = tlx, ly = tly;   // composition sampling scale, restored after
        bsx = cw / (float) ovlW; bsy = ch / (float) ovlH; tlx = 1f / ovlW; tly = 1f / ovlH;
        int cur = 0;
        try {
            int openGroup = 0;
            for (Layer l0 : m.grade) {
                if (l0.type == Layer.GROUP) {
                    if (openGroup == l0.id) {
                        int dst = cur == 0 ? 1 : 0;
                        groupMix(l0, ovlTx[cur], ovlFbs[dst], cw, ch, cw, ch, 1f, true);
                        cur = dst; openGroup = 0;
                    }
                    continue;
                }
                if (!inOpenGroup(m.grade, l0)) continue;
                if (!l0.visible) continue;
                if (l0.parent != 0 && openGroup != l0.parent) { snapshot(ovlFbs[cur], cw, ch, true); openGroup = l0.parent; }
                Layer l = Music.at(MaskStack.at(l0, t - ti.start), p, t, m.id, ti.start, ti.dur);
                int dst = cur == 0 ? 1 : 0;
                if (l.type == Layer.EFFECT) {
                    int scratch = 3 - cur - dst;
                    cur = effect(l, ovlTx, ovlFbs, cur, dst, scratch, cw, ch, FULL, cw, ch, I0, I1, cw, ch, 1f);
                    continue;
                }
                pass(l, adjProgram(l.type), ovlTx[cur], ovlFbs[dst], cw, ch, FULL, cw, ch, I0, I1, cw, ch, 1f, null);
                cur = dst;
            }
        } finally {
            bsx = kx; bsy = ky; tlx = lx; tly = ly;
        }
        return ovlTx[cur];
    }

    /**
     * Draws a visualizer into its own buffer at the size it will appear (box * scale * view px per
     * output px) and returns it as a layer source covering the box. Bars and wave data come from
     * the song analysis at this frame; with no song (or outside it) everything rests at zero.
     */
    private Engine.Src viz(VProject p, VProject.TextItem ti, long t, float ppd) {
        VProject.Viz z = ti.viz;
        Layer l = ti.layer;
        float k = Math.max(0.05f, l.scale) * ppd;
        int w = Math.max(8, Math.min(vizCap, Math.round(l.srcW * k)));
        int h = Math.max(8, Math.min(vizCap, Math.round(l.srcH * k)));
        if (z.trailLen > 0 && z.echoes > 0) return echoes(p, ti, t, k, w, h);
        drawViz(p, ti, t, k, w, h);
        return new Engine.Src(vizTex, new RectF(0, 0, l.srcW * vizW / (float) w, l.srcH * vizH / (float) h));
    }

    /**
     * Echoes (After Effects' Echo, with a transform per echo like Trapcode Echospace): the
     * visualizer as it was 1..n steps of trailLen/n ago, each zoomed, turned and hue-shifted by
     * its age and faded, drawn oldest first under the current frame. Every echo is evaluated at
     * its own song time, so a frame depends only on t: scrubbing, playback and export agree.
     */
    private Engine.Src echoes(VProject p, VProject.TextItem ti, long t, float k, int w, int h) {
        VProject.Viz z = ti.viz;
        Layer l = ti.layer;
        if (w > echoW || h > echoH) {
            if (echoW > 0) {
                GLES20.glDeleteTextures(1, new int[]{echoTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{echoFb}, 0);
            }
            echoW = Math.max(w, echoW);
            echoH = Math.max(h, echoH);
            int[] f = makeFbo(echoW, echoH, true);
            echoTex = f[0];
            echoFb = f[1];
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, echoFb);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int n = z.echoes;
        long step = (long) (z.trailLen * 1e6f / n);
        int prog = progs.get("echo");
        for (int e = n; e >= 0; e--) {
            long te = t - e * step;
            if (te < ti.start) continue;   // no echoes from before the visualizer began
            drawViz(p, ti, te, k, w, h);
            float age = e * step / 1e6f;
            float bass = z.tPush != 0 && e > 0 ? p.drive(1, te, true) : 0;   // adaptive bass at the echo's moment
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, echoFb);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(prog);
            bind(prog, "uCur", 0, vizTex);
            u2(prog, "uCurScale", w / (float) vizW, h / (float) vizH);
            u1(prog, "uZoom", (float) Math.exp((z.tZoom + z.tPush * bass) * age));
            u1(prog, "uRot", (float) Math.toRadians(z.tSpin * age));
            u1(prog, "uHue", (float) Math.toRadians(z.tHue * age));
            u1(prog, "uI", z.echoIntensity(e));
            u1(prog, "uAspect", w / (float) h);
            GLES20.glEnable(GLES20.GL_BLEND);
            if (z.echoGlow) GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR);   // screen
            else GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);             // over
            drawQuad(prog);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        return new Engine.Src(echoTex, new RectF(0, 0, l.srcW * echoW / (float) w, l.srcH * echoH / (float) h));
    }

    /** Draws the visualizer at time t into vizFb's (w, h) corner, premultiplied. */
    private void drawViz(VProject p, VProject.TextItem ti, long t, float k, int w, int h) {
        VProject.Viz z = ti.viz;
        if (w > vizW || h > vizH) {
            if (vizW > 0) {
                GLES20.glDeleteTextures(1, new int[]{vizTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{vizFb}, 0);
            }
            vizW = Math.max(w, vizW);
            vizH = Math.max(h, vizH);
            int[] f = makeFbo(vizW, vizH, false);
            vizTex = f[0];
            vizFb = f[1];
        }
        // ---- data (Visuals.rows): bars, waveform, peak caps, past spectra, level history
        AudioFeatures fx = p.songFx;
        long st = p.songTime(t);
        int n = Math.max(4, Math.min(64, z.bars));
        boolean live = fx != null && st >= 0;
        float gain = live && z.auto ? fx.autoGain(st) : 0, wgain = live && z.auto ? fx.autoWaveGain(st) : 1;
        Visuals.rows(fx, st, z, gain, wgain, vizWork, dataBytes);
        data.clear();
        data.put(dataBytes);
        data.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dataTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, Visuals.W, Visuals.ROWS, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        // ---- draw
        int prog = progs.get("viz");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, vizFb);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        bind(prog, "uData", 0, dataTex);
        u1(prog, "uType", z.type);
        u1(prog, "uBars", n);
        u1(prog, "uGap", z.gap);
        u3(prog, "uC1", ((z.c1 >> 16) & 255) / 255f, ((z.c1 >> 8) & 255) / 255f, (z.c1 & 255) / 255f);
        u3(prog, "uC2", ((z.c2 >> 16) & 255) / 255f, ((z.c2 >> 8) & 255) / 255f, (z.c2 & 255) / 255f);
        u1(prog, "uThick", Math.max(1.5f, z.thick * Math.min(p.outW(), p.outH()) * k));   // thickness scales with the box
        u1(prog, "uGlow", z.glow);
        u1(prog, "uInner", z.inner);
        u1(prog, "uLevel", fx != null && st >= 0 ? fx.bassAt(st, Math.max(0.05f, z.release)) * Math.min(1.5f, z.sens) : 0);
        u2(prog, "uBoxPx", w, h);
        // 3.15 options
        u3(prog, "uC3", ((z.c3 >> 16) & 255) / 255f, ((z.c3 >> 8) & 255) / 255f, (z.c3 & 255) / 255f);
        u1(prog, "uStops", z.stops);
        u1(prog, "uColorMode", z.colorMode);
        u1(prog, "uHueOff", (float) ((t / 1e6 * z.hueSpeed / 360.0) % 1.0));
        u1(prog, "uRound", z.round);
        u1(prog, "uCaps", z.caps ? 1 : 0);
        u1(prog, "uMirrorX", z.mirrorX ? 1 : 0);
        u1(prog, "uFlip", z.flip ? 1 : 0);
        u1(prog, "uFill", z.fill);
        u1(prog, "uBounce", z.bounce);
        u1(prog, "uSegs", z.segs);
        u1(prog, "uRidges", z.ridges);
        u1(prog, "uAmp", z.amp);
        drawQuad(prog);
    }

    /** Preview: letterbox the result into the view rect of a w x h surface. */
    void display(int res, int vw, int vh, int sw, int sh, float[] view, int bg) {
        int p = progs.get("video_display");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, sw, sh);
        GLES20.glUseProgram(p);
        bind(p, "uComp", 0, tx[res]);
        u2(p, "uCompScale", vw / (float) bw, vh / (float) bh);
        u2(p, "uScreen", sw, sh);
        u4(p, "uView", view[0], view[1], view[2], view[3]);
        u3(p, "uBg", ((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f);
        u3(p, "uPanel", 0.055f, 0.06f, 0.07f);
        drawQuad(p);
    }

    /** 3.13 Save frame: an RGBA8 texture + framebuffer of w x h. Returns {tex, fbo}. */
    int[] makeOutputTarget(int w, int h) {
        int[] t = new int[1], f = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, f, 0); GLES20.glDeleteTextures(1, t, 0);
            throw new IllegalStateException("frame target incomplete (" + st + ") at " + w + "x" + h);
        }
        return new int[]{t[0], f[0]};
    }

    void deleteTarget(int[] tf) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{tf[1]}, 0);
        GLES20.glDeleteTextures(1, new int[]{tf[0]}, 0);
    }

    /** The export output pass into framebuffer fbo instead of the window (rows come out bottom-up). */
    void outputTo(int fbo, int res, int w, int h, int bg) {
        int p = progs.get("video_out");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(p);
        bind(p, "uComp", 0, tx[res]);
        u2(p, "uCompScale", w / (float) bw, h / (float) bh);
        u3(p, "uBg", ((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f);
        drawQuad(p);
    }

    // ---------------------------------------------------------------- 3.14: look groups
    /** The image before a group's first layer: one for composition-size buffers, one for overlay buffers. */
    private int gsTex, gsFb, gsW, gsH, gosTex, gosFb, gosW, gosH;

    /** A child of a hidden (bypassed) or missing group is skipped with it. */
    private static boolean inOpenGroup(java.util.ArrayList<Layer> g, Layer l) {
        if (l.parent == 0) return true;
        Layer grp = GradeGroups.byId(g, l.parent);
        return grp != null && grp.visible;
    }

    /** Copies the current buffer (vw x vh of it) into the group snapshot buffer of the same size class. */
    private void snapshot(int srcFb, int vw, int vh, boolean ovl) {
        int w = ovl ? ovlW : bw, h = ovl ? ovlH : bh;
        if (ovl) {
            if (gosW != w || gosH != h) {
                if (gosW > 0) { GLES20.glDeleteTextures(1, new int[]{gosTex}, 0); GLES20.glDeleteFramebuffers(1, new int[]{gosFb}, 0); }
                int[] b = makeFbo(w, h, true); gosTex = b[0]; gosFb = b[1]; gosW = w; gosH = h;
            }
        } else if (gsW != w || gsH != h) {
            if (gsW > 0) { GLES20.glDeleteTextures(1, new int[]{gsTex}, 0); GLES20.glDeleteFramebuffers(1, new int[]{gsFb}, 0); }
            int[] b = makeFbo(w, h, true); gsTex = b[0]; gsFb = b[1]; gsW = w; gsH = h;
        }
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFb);
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, ovl ? gosFb : gsFb);
        GLES30.glBlitFramebuffer(0, 0, vw, vh, 0, 0, vw, vh, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Closes a group: mix(image before it, image after its layers, Strength), as group_mix does for photos. */
    private void groupMix(Layer g, int topTex, int dstFb, int vw, int vh, float ow, float oh, float ppd, boolean ovl) {
        int p = progs.get("group_mix");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFb);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(p);
        bind(p, "uBelow", 0, ovl ? gosTex : gsTex);
        bind(p, "uTop", 2, topTex);
        common(p, g, FULL, ow, oh, I0, I1, ow, oh, ppd);
        drawQuad(p);
    }

    /** Drops the ping-pong buffers; the next render allocates them at its own size again. */
    void releaseBuffers() {
        if (gsW > 0) { GLES20.glDeleteTextures(1, new int[]{gsTex}, 0); GLES20.glDeleteFramebuffers(1, new int[]{gsFb}, 0); gsW = gsH = 0; }
        if (bw == 0) return;
        for (int i = 0; i < 5; i++) {
            GLES20.glDeleteTextures(1, new int[]{tx[i]}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{fb[i]}, 0);
        }
        bw = bh = 0;
    }

    /** Export: the result into the current window surface (encoder input), w x h. */
    void output(int res, int w, int h, int bg) {
        int p = progs.get("video_out");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(p);
        bind(p, "uComp", 0, tx[res]);
        u2(p, "uCompScale", w / (float) bw, h / (float) bh);
        u3(p, "uBg", ((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f);
        drawQuad(p);
    }

    int newOesTex() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        return t[0];
    }

    /** Bitmap texture through the shared cache (stills, text). */
    int bitmapTex(Bitmap b) { return tex(b, 0, true); }

    /** Drop cached bitmap textures not in use (call occasionally). */
    void forget(Bitmap b) {
        int[] e = texCache.remove(b);
        if (e != null) GLES20.glDeleteTextures(1, new int[]{e[0]}, 0);
    }
}
