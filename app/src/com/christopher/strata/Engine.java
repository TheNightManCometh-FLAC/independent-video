package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES 3 renderer. One routine, renderStack, draws the layer stack for any rectangle of the
 * document into any viewport: the whole document at preview size, a 2048 px export tile, or the
 * visible area at full resolution (detail view). Layers are full-screen passes ping-ponging
 * between three RGBA16F buffers; retouch strokes blend in place, scissored to each stroke.
 * Region renders carry an apron of APRON px on every side so neighbourhood filters (sharpen,
 * noise reduction) see real pixels at the edges; only the interior is kept.
 */
final class Engine extends GlBase implements GLSurfaceView.Renderer {
    static final int PREV = 3072;
    static final int TILE = 2048;
    static final int APRON = 32, APRON_MAX = 768;
    private int detApron = APRON;

    /**
     * Overlap a region render needs so neighbourhood effects see real pixels at its edges: the
     * reach of every visible effect layer (they stack), in render px, at least APRON.
     */
    static int apronFor(Doc d, float pxPerDoc) {
        float r = 0, px = 0;
        for (Layer l : d.layers) {
            if (!l.visible) continue;
            if (l.type == Layer.EFFECT) r += Fx.reach(l);
            // detail: NR's 5x5 taps (2 px out) then sharpening's radius, in image px
            else if (l.type == Layer.DETAIL) px += 3 + l.det[1] * 2.5f;
        }
        return Math.max(APRON, Math.min(APRON_MAX, apronNeeded(d, pxPerDoc)));
    }

    /** The overlap the visible layers actually need, uncapped (render px). */
    static int apronNeeded(Doc d, float pxPerDoc) {
        float r = 0, px = 0;
        for (Layer l : d.layers) {
            if (!l.visible) continue;
            if (l.type == Layer.EFFECT) r += Fx.reach(l);
            else if (l.type == Layer.DETAIL) px += 3 + l.det[1] * 2.5f;
        }
        return (int) Math.ceil((r * Math.max(d.imgW, d.imgH) + px) * pxPerDoc) + 4;
    }

    /**
     * Export tile size for an overlap: the default 2048 when it fits (with a 256 px core), else
     * the next size up to 4096 the GPU allows, so wide blurs see all the pixels they read.
     */
    static int tileFor(int needA, int maxTex) {
        int[] sizes = {TILE, 3072, 4096};
        for (int T : sizes) if (T <= maxTex && needA <= (T - 256) / 2) return T;
        return Math.max(Math.min(TILE, maxTex), Math.min(4096, maxTex));   // the largest allowed; A gets capped
    }

    interface Host {
        void onHistogram(int[] hist);
        void onGlError(String msg);
    }

    interface Redraw { void postDelayedRender(); }

    static final class RS {
        int ver;
        Doc doc;
        boolean cropMode;
        int maskVisId = -1;
        /** Selection shown as an overlay (Select tab), image space at mask resolution; null = none. */
        Bitmap selVis;
        int selVer;
        /** Layer whose affected area (mask x range x clip) is shown; -1 = none. */
        int effVisId = -1;
    }

    /** A placed layer's pixels for one render: texture + the layer-px rect it covers. */
    static final class Src {
        final int tex;
        final float x, y, w, h;
        Src(int tex, RectF r) { this.tex = tex; x = r.left; y = r.top; w = r.width(); h = r.height(); }
    }

    interface Sources { Src get(Layer l); }

    private final Host host;
    Redraw gl;
    volatile RS rs;
    volatile float viewOffX, viewOffY, viewScale = 1f;
    volatile boolean compare;
    /** Before/after split: screen x px of the divider (the original shows left of it); < 0 = off. */
    volatile float splitX = -1;
    /** Paint clipped highlights red and crushed shadows blue in the display. */
    volatile boolean clipVis;
    /** Sample loupe {centre x, y, radius, magnification, magnified point x, y} in screen px; null = off. */
    volatile float[] loupe;
    // the original (compare composite) kept for the split view
    private int beforeTex, beforeFbo, beforeW, beforeH, beforeVer = -1;

    private int sw = 1, sh = 1;
    private final int[] compTex = new int[3], compFbo = new int[3];
    private int compW, compH, result = -1;
    private final int[] detTex = new int[3], detFbo = new int[3];
    private int detBW, detBH, detRes = -1, detVer = -1;
    private float[] detRect;
    private float detRres;
    private int histTex, histFbo, histW, histH;
    private int lastVer = -1, lastEff = -1;
    // affected-area overlay: coverage of one layer at preview size, made during the preview composite
    private int effFor = -1, effTex, effFbo, effW, effH;
    private boolean effDone;
    private boolean lastCompare, histPending;
    private long lastHist;

    Engine(Context ctx, Host host) {
        super(ctx);
        this.host = host;
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        compW = compH = 0; histW = 0; result = -1; lastVer = -1; beforeW = beforeH = 0; beforeVer = -1; detBW = detBH = 0; detRes = -1; bndFbo = 0; effW = effH = 0; effDone = false;
        glGen++;
        srcSets[0] = srcSets[1] = null;   // their textures died with the old context
        for (int k = 0; k < covBuf.length; k++) { covBuf[k] = null; grpBuf[k] = null; }
        srcT = 0;
        try {
            initGl(new String[]{"layer_pixel", "adj_basic", "adj_curves", "adj_levels", "adj_hsl", "adj_lut", "detail_nr",
                    "detail_sharp", "retouch_bnd", "retouch", "display", "output", "adj_fx", "fx_blur", "fx_src", "fx_gen", "fx_remap", "group_mix", "coverage"});
        } catch (Exception e) {
            host.onGlError("Shader: " + e.getMessage());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        sw = w;
        sh = h;
    }

    /** 3.15: a text/shape mask texture finished building in the background: composite again. */
    volatile boolean shapesReady;

    @Override
    public void onDrawFrame(GL10 unused) {
        previewShapes = true;   // 3.15: the live preview never waits for a text/shape texture
        RS s = rs;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, sw, sh);
        GLES20.glClearColor(0.055f, 0.06f, 0.07f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (s == null || progs.isEmpty()) return;
        boolean cmp = compare;
        if (splitX >= 0 && !cmp && !s.cropMode && (beforeVer != s.ver || beforeW == 0)) {
            // split view: the original first, copied aside, then the edit renders into the same buffers
            compositePreview(s, true);
            copyBefore();
            beforeVer = s.ver;
            result = -1;
        }
        if (s.ver != lastVer || cmp != lastCompare || result < 0 || (!cmp && s.effVisId != lastEff) || shapesReady) {
            shapesReady = false;
            compositePreview(s, cmp);
            lastVer = s.ver;
            lastEff = cmp ? -1 : s.effVisId;
            lastCompare = cmp;
            if (!cmp) histPending = true;
        }
        if (histPending && SystemClock.uptimeMillis() - lastHist > 120) {
            histPending = false;
            lastHist = SystemClock.uptimeMillis();
            histogram();
        } else if (histPending && gl != null) gl.postDelayedRender();
        display(s);
    }

    // ------------------------------------------------------------------ preview
    private void compositePreview(RS s, boolean cmp) {
        Doc d = s.doc;
        float dw = d.docW(s.cropMode), dh = d.docH(s.cropMode);
        float ps = Math.min(1f, PREV / Math.max(dw, dh));
        int w = Math.max(1, Math.round(dw * ps)), h = Math.max(1, Math.round(dh * ps));
        if (w != compW || h != compH) allocComp(w, h);
        final HashSet<Bitmap> used = new HashSet<Bitmap>();
        Sources src = new Sources() {
            @Override public Src get(Layer l) {
                if (l.preview == null) return null;
                used.add(l.preview);
                return new Src(tex(l.preview, 0, true), new RectF(0, 0, l.srcW, l.srcH));
            }
        };
        for (Layer l : d.layers) if (l.mask != null) used.add(l.mask);
        if (s.selVis != null) used.add(s.selVis);   // keep its texture between composites
        effFor = cmp ? -1 : s.effVisId;
        effDone = false;
        if (effFor >= 0 && (effW != w || effH != h)) {
            if (effW > 0) {
                GLES20.glDeleteTextures(1, new int[]{effTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{effFbo}, 0);
            }
            int[] f = makeFbo(w, h, false);
            effTex = f[0]; effFbo = f[1]; effW = w; effH = h;
        }
        previewSources = true;
        shapeImages.clear();
        try {
            result = renderStack(d, s.cropMode, new float[]{0, 0, 1, 1}, w, h, w, h, compTex, compFbo, w / dw, src, cmp);
        } finally {
            previewSources = false;
            effFor = -1;
        }
        used.addAll(shapeImages);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compTex[result]);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        if (!cmp) gcTextures(used, d);
    }

    /** Copies the compare composite just rendered into the split view's texture (mipmapped like the composite). */
    private void copyBefore() {
        if (result < 0) return;
        if (beforeW != compW || beforeH != compH) {
            if (beforeW > 0) {
                GLES20.glDeleteTextures(1, new int[]{beforeTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{beforeFbo}, 0);
            }
            int[] f = makeFbo(compW, compH, true);
            beforeTex = f[0]; beforeFbo = f[1]; beforeW = compW; beforeH = compH;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, beforeTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        }
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, compFbo[result]);
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, beforeFbo);
        GLES30.glBlitFramebuffer(0, 0, compW, compH, 0, 0, compW, compH, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, beforeTex);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
    }

    private void display(RS s) {
        if (result < 0) return;
        Doc d = s.doc;
        int p = progs.get("display");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, sw, sh);
        GLES20.glUseProgram(p);
        bind(p, "uComp", 0, compTex[result]);
        u2(p, "uScreen", sw, sh);
        u2(p, "uViewOff", viewOffX, viewOffY);
        u1(p, "uViewScale", viewScale);
        u3(p, "uBg", 0.055f, 0.06f, 0.07f);
        u2(p, "uDocSize", d.docW(s.cropMode), d.docH(s.cropMode));
        boolean det = detRes >= 0 && detVer == lastVer && !compare && !s.cropMode && detRect != null
                && viewScale >= detRres * 0.6f;
        u1(p, "uDetOn", det ? 1 : 0);
        bind(p, "uDet", 2, det ? detTex[detRes] : blankTex);
        boolean split = splitX >= 0 && !compare && !s.cropMode && beforeW > 0 && beforeVer == lastVer;
        u1(p, "uSplit", split ? splitX : -1);
        bind(p, "uBefore", 4, split ? beforeTex : blankTex);
        u1(p, "uClipVis", clipVis ? 1 : 0);
        float[] lp = loupe;
        if (lp != null) {
            u4(p, "uLoupe", lp[0], lp[1], lp[2], lp[3]);
            u2(p, "uLoupeAt", lp[4], lp[5]);
        } else u4(p, "uLoupe", 0, 0, 0, 1);
        if (det) {
            u4(p, "uDetRect", detRect[0], detRect[1], detRect[2], detRect[3]);
            u4(p, "uDetMap", detApron / (float) detBW, detApron / (float) detBH, detRres / detBW, detRres / detBH);
        }
        Layer ml = s.maskVisId >= 0 ? d.byId(s.maskVisId) : null;
        Bitmap vis = null;
        int visVer = 0;
        double[] rows = null;
        if (s.selVis != null) {
            vis = s.selVis; visVer = s.selVer;
            rows = new double[]{vis.getWidth() / (double) d.imgW, 0, 0, 0, vis.getHeight() / (double) d.imgH, 0};
        } else if (ml != null && ml.mask != null) {
            vis = ml.mask; visVer = ml.maskVer;
            rows = ml.maskAffine(d.imgW, d.imgH);
        }
        boolean eff = s.selVis == null && s.effVisId >= 0 && effDone && lastEff == s.effVisId && !compare;
        bind(p, "uEff", 3, eff ? effTex : blankTex);
        if (eff) {
            bind(p, "uMask", 1, blankTex);
            u1(p, "uMaskOn", 0);
            u1(p, "uMaskN", 0);
            u1(p, "uMaskVis", 2);
        } else if (vis != null && !compare) {
            float[] m0 = new float[3], m1 = new float[3];
            d.docToImg(s.cropMode, m0, m1);
            u3(p, "uM0", m0[0], m0[1], m0[2]);
            u3(p, "uM1", m1[0], m1[1], m1[2]);
            u2(p, "uImgSize", d.imgW, d.imgH);
            bind(p, "uMask", 1, tex(vis, visVer, true));
            maskRows(p, rows, vis.getWidth(), vis.getHeight());
            maskComps(p, s.selVis != null ? null : ml);   // the overlay shows the whole mask stack
            u1(p, "uMaskOn", 1);
            u1(p, "uMaskVis", 1);
        } else {
            bind(p, "uMask", 1, blankTex);
            u1(p, "uMaskOn", 0);
            u1(p, "uMaskN", 0);
            u1(p, "uMaskVis", 0);
        }
        drawQuad(p);
    }

    private void histogram() {
        if (result < 0) return;
        int w = 256, h = Math.max(1, Math.round(256f * compH / compW));
        if (w != histW || h != histH) {
            if (histW > 0) {
                GLES20.glDeleteTextures(1, new int[]{histTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{histFbo}, 0);
            }
            int[] r = makeFbo(w, h, false);
            histTex = r[0]; histFbo = r[1]; histW = w; histH = h;
        }
        ByteBuffer px = readComp(compTex[result], histFbo, w, h, true);
        int[] hist = new int[1024];
        for (int i = 0; i < w * h; i++) {
            int r = px.get(i * 4) & 255, g = px.get(i * 4 + 1) & 255, b = px.get(i * 4 + 2) & 255;
            hist[r]++; hist[256 + g]++; hist[512 + b]++;
            hist[768 + Math.min(255, (int) (0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f))]++;
        }
        host.onHistogram(hist);
    }

    /** Thumbnail of the current preview composite, called on the GL thread. */
    Bitmap thumbnail(int maxSide) {
        if (result < 0 || compW == 0) return null;
        float k = Math.min(1f, maxSide / (float) Math.max(compW, compH));
        int w = Math.max(1, Math.round(compW * k)), h = Math.max(1, Math.round(compH * k));
        int[] f = makeFbo(w, h, false);
        ByteBuffer px = readComp(compTex[result], f[1], w, h, true);
        GLES20.glDeleteTextures(1, new int[]{f[0]}, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{f[1]}, 0);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        px.rewind();
        b.copyPixelsFromBuffer(px);
        return b;
    }

    /**
     * Live effect thumbnails: a centre square of the current composite, each effect at its
     * defaults (sizes scale with the thumbnail like they do with the image). GL thread; each
     * finished bitmap is handed to `out` with its effect id.
     */
    interface ThumbOut { void thumb(int fx, Bitmap b); }

    void effectThumbs(int[] ids, int size, ThumbOut out) {
        if (result < 0 || compW == 0 || progs.isEmpty()) return;
        int[][] f = {makeFbo(size, size, true), makeFbo(size, size, true), makeFbo(size, size, true), makeFbo(size, size, true)};
        int[] o = makeFbo(size, size, false);
        int[] tx = {f[0][0], f[1][0], f[2][0], f[3][0]}, fb = {f[0][1], f[1][1], f[2][1], f[3][1]};
        try {
            float sq = Math.min(compW, compH);
            float rw = sq / compW, rh = sq / compH;
            int p = progs.get("fx_src");
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
            GLES20.glViewport(0, 0, size, size);
            GLES20.glUseProgram(p);
            bind(p, "uComp", 0, compTex[result]);
            u4(p, "uRect", (1 - rw) / 2, (1 - rh) / 2, rw, rh);
            drawQuad(p);
            float[] full = {0, 0, 1, 1}, i0 = {1, 0, 0}, i1 = {0, 1, 0};
            bsx = bsy = 1;
            tlx = tly = 1f / size;
            for (int id : ids) {
                Layer l = new Layer();
                l.type = Layer.EFFECT;
                Fx.defaults(l, id);
                l.mode = Fx.get(id).defaultMode;
                int r = effect(l, tx, fb, 0, 1, 2, size, size, full, size, size, i0, i1, size, size, 1f);
                ByteBuffer px = readComp(tx[r], o[1], size, size, true);
                Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                px.rewind();
                b.copyPixelsFromBuffer(px);
                out.thumb(id, b);
            }
        } finally {
            for (int[] x : new int[][]{f[0], f[1], f[2], f[3], o}) {
                GLES20.glDeleteTextures(1, new int[]{x[0]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{x[1]}, 0);
            }
            lastVer = -1;   // the next frame recomposites (restores buffer scales)
        }
    }

    private ByteBuffer readComp(int srcTex, int fbo, int w, int h, boolean opaque) {
        int p = progs.get("output");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(p);
        bind(p, "uComp", 0, srcTex);
        u1(p, "uOpaque", opaque ? 1 : 0);
        u3(p, "uMatte", 0.5f, 0.5f, 0.5f);
        u1(p, "uDither", 0);
        u2(p, "uCompScale", 1, 1);
        drawQuad(p);
        ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        return px;
    }

    // ------------------------------------------------------------------ the layer stack
    /**
     * Renders d's layers for the doc rect `tile` (fractions of the doc: x, y, w, h) into a vw x vh
     * viewport of buffers sized bw x bh. pxPerDoc = viewport px per doc px. Returns the index of
     * the buffer holding the result.
     */
    private int renderStack(Doc d, boolean crop, float[] tile, int vw, int vh, int bw, int bh,
                            int[] tx, int[] fb, float pxPerDoc, Sources src, boolean cmp) {
        return renderStack(d, crop, tile, vw, vh, bw, bh, tx, fb, pxPerDoc, src, cmp, d.layers.size(), 0);
    }

    /** Layers [0, upTo) only; depth counts nested source renders for geometry effects. */
    private int renderStack(Doc d, boolean crop, float[] tile, int vw, int vh, int bw, int bh,
                            int[] tx, int[] fb, float pxPerDoc, Sources src, boolean cmp, int upTo, int depth) {
        float dw = d.docW(crop), dh = d.docH(crop);
        float[] m0 = new float[3], m1 = new float[3];
        d.docToImg(crop, m0, m1);
        bsx = vw / (float) bw; bsy = vh / (float) bh; tlx = 1f / bw; tly = 1f / bh;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int cur = 0;
        int openGroup = 0, covFor = -1;
        for (int i = 0; i < upTo; i++) {
            Layer l = d.layers.get(i);
            clipTex = 0;
            if (l.type == Layer.GROUP) {
                // the group's layer sits above its children: close the group here
                if (openGroup == l.id) {
                    if (depth == 0 && l.id == effFor && grpBuf[depth] != null)
                        effPass(l, grpBuf[depth][0], vw, vh, tile, dw, dh, m0, m1, d, pxPerDoc);
                    cur = closeGroup(l, cur, depth, vw, vh, tile, dw, dh, m0, m1, d, pxPerDoc, tx, fb);
                    openGroup = 0;
                }
                continue;
            }
            if (l.parent != 0) {
                Layer g = d.byId(l.parent);
                if (g == null || !g.visible) continue;   // a hidden group hides everything in it
            }
            if (!l.visible) continue;
            if (cmp && !(i == 0 || l.isBase)) continue;
            if (l.parent != 0 && openGroup != l.parent) {
                // first visible layer of a group: keep the image below it for the group's opacity / mask
                snapshot(cur, depth, vw, vh, bw, bh, fb);
                openGroup = l.parent;
            }
            if (l.clip) {
                int bi = d.clipBase(i);
                if (bi >= 0) {
                    Layer base = d.layers.get(bi);
                    if (!base.visible) continue;   // a hidden base hides what's clipped to it
                    if (covFor != bi) {
                        if (!coverage(base, cur, depth, vw, vh, bw, bh, tile, dw, dh, m0, m1, d, pxPerDoc, src, tx)) continue;
                        covFor = bi;
                    }
                    clipTex = covBuf[depth][0];
                }
            }
            if (depth == 0 && l.id == effFor) effPass(l, tx[cur], vw, vh, tile, dw, dh, m0, m1, d, pxPerDoc);
            int a = (cur + 1) % 3, b = (cur + 2) % 3;
            switch (l.type) {
                case Layer.IMAGE:
                case Layer.TEXT: {
                    Src s = src.get(l);
                    if (s == null) continue;
                    pass(l, "layer_pixel", tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc, s);
                    cur = a;
                    break;
                }
                case Layer.FILL:
                    pass(l, "layer_pixel", tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc, null);
                    cur = a;
                    break;
                case Layer.DETAIL:
                    pass(l, "detail_nr", tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc, null);
                    midTex = tx[a];
                    pass(l, "detail_sharp", tx[cur], fb[b], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc, null);
                    cur = b;
                    break;
                case Layer.EFFECT:
                    if (Fx.needsSource(l) && !wholeFrame(tile))
                        cur = remapRegion(d, crop, i, l, tile, vw, vh, bw, bh, tx, fb, cur, a, pxPerDoc, src, cmp, depth);
                    else cur = effect(l, tx, fb, cur, a, b, vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc);
                    break;
                case Layer.RETOUCH: {
                    Layer t = Doc.retouchTargetOf(d, l);
                    if (t == null || l.strokes.isEmpty()) continue;
                    Src s = src.get(t);
                    if (s == null) continue;
                    retouch(l, t, s, fb[cur], vw, vh, tile, dw, dh, m0, m1, d, crop, pxPerDoc);
                    break;
                }
                default:
                    pass(l, adjProgram(l.type), tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, pxPerDoc, null);
                    cur = a;
            }
        }
        clipTex = 0;
        return cur;
    }

    // ------------------------------------------------------------------ clipping and groups
    /** Per nesting depth (geometry effects re-render the stack below them): {tex, fbo, w, h}. */
    private final int[][] covBuf = new int[3][], grpBuf = new int[3][];

    private int[] depthBuf(int[][] set, int depth, int w, int h, boolean hdr) {
        int[] b = set[depth];
        // exact size: shaders sample these with uBelowScale (vw / bw), which assumes the ping-pong size
        if (b == null || b[2] != w || b[3] != h) {
            if (b != null) {
                GLES20.glDeleteTextures(1, new int[]{b[0]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{b[1]}, 0);
            }
            int[] f = makeFbo(w, h, hdr);
            b = new int[]{f[0], f[1], w, h};
            set[depth] = b;
        }
        return b;
    }

    /** Renders a clipping base's coverage (alpha x mask) into this depth's coverage buffer. */
    private boolean coverage(Layer base, int cur, int depth, int vw, int vh, int bw, int bh, float[] tile, float dw, float dh,
                             float[] m0, float[] m1, Doc d, float ppd, Sources src, int[] tx) {
        Src s = null;
        if (base.type != Layer.FILL) {
            s = src.get(base);
            if (s == null) return false;
        }
        int[] cb = depthBuf(covBuf, depth, bw, bh, false);
        int keep = clipTex;
        clipTex = 0;
        covOut = true;
        pass(base, "layer_pixel", tx[cur], cb[1], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, ppd, s);
        covOut = false;
        clipTex = keep;
        return true;
    }

    /** The affected-area overlay: layer l's mask x range x clip, tested on `below`, into the eff buffer. */
    private void effPass(Layer l, int below, int vw, int vh, float[] tile, float dw, float dh,
                         float[] m0, float[] m1, Doc d, float ppd) {
        if (effW != vw || effH != vh) return;
        int p = progs.get("coverage");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, effFbo);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(p);
        bind(p, "uBelow", 0, below);
        common(p, l, tile, dw, dh, m0, m1, d.imgW, d.imgH, ppd);
        drawQuad(p);
        effDone = true;
    }

    /**
     * Average colour (rgb 0..1) of the image a layer's Blend If range is tested on (the composite
     * just below it; for a group, below its first layer) around image point (ix, iy): 5 x 5
     * preview px. Null where that image is transparent. GL thread; the next frame recomposites.
     */
    float[] sampleBelow(Doc d, int id, float ix, float iy) {
        if (progs.isEmpty()) return null;
        int i = d.indexOf(id);
        if (i < 0) return null;
        Layer l = d.layers.get(i);
        int upTo = i;
        if (l.type == Layer.GROUP) while (upTo > 0 && d.layers.get(upTo - 1).parent == l.id) upTo--;
        float dw = d.docW(false), dh = d.docH(false);
        float ps = Math.min(1f, PREV / Math.max(dw, dh));
        int w = Math.max(1, Math.round(dw * ps)), h = Math.max(1, Math.round(dh * ps));
        if (w != compW || h != compH) allocComp(w, h);
        Sources src = new Sources() {
            @Override public Src get(Layer x) {
                return x.preview == null ? null : new Src(tex(x.preview, 0, true), new RectF(0, 0, x.srcW, x.srcH));
            }
        };
        int[] o = makeFbo(5, 5, false);
        try {
            previewSources = true;
            int r;
            try {
                r = renderStack(d, false, new float[]{0, 0, 1, 1}, w, h, w, h, compTex, compFbo, w / dw, src, false, upTo, 0);
            } finally { previewSources = false; }
            float[] dp = new float[2];
            d.imgToDoc(false, ix, iy, dp);
            float px = dp[0] / dw * w, py = dp[1] / dh * h;
            int p = progs.get("fx_src");
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, o[1]);
            GLES20.glViewport(0, 0, 5, 5);
            GLES20.glUseProgram(p);
            bind(p, "uComp", 0, compTex[r]);
            u4(p, "uRect", (float) (Math.floor(px) - 2) / w, (float) (Math.floor(py) - 2) / h, 5f / w, 5f / h);
            drawQuad(p);
            ByteBuffer px5 = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, 5, 5, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px5);
            float sr = 0, sg = 0, sb = 0, sa = 0;
            for (int k = 0; k < 25; k++) {
                float a = (px5.get(k * 4 + 3) & 255) / 255f;
                sr += (px5.get(k * 4) & 255) * a; sg += (px5.get(k * 4 + 1) & 255) * a; sb += (px5.get(k * 4 + 2) & 255) * a;
                sa += a;
            }
            if (sa < 2.5f) return null;   // mostly transparent there
            return new float[]{sr / sa / 255f, sg / sa / 255f, sb / sa / 255f};
        } finally {
            GLES20.glDeleteTextures(1, new int[]{o[0]}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{o[1]}, 0);
            lastVer = -1;
        }
    }

    /** Copies the current composite (the image below a group) into this depth's group buffer. */
    private void snapshot(int cur, int depth, int vw, int vh, int bw, int bh, int[] fb) {
        int[] gb = depthBuf(grpBuf, depth, bw, bh, true);
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fb[cur]);
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, gb[1]);
        GLES30.glBlitFramebuffer(0, 0, vw, vh, 0, 0, vw, vh, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Pass-through group: mix(image before the group, image after its layers, opacity x mask). */
    private int closeGroup(Layer g, int cur, int depth, int vw, int vh, float[] tile, float dw, float dh,
                           float[] m0, float[] m1, Doc d, float ppd, int[] tx, int[] fb) {
        int[] gb = grpBuf[depth];
        if (gb == null) return cur;
        int a = (cur + 1) % 3;
        int p = progs.get("group_mix");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[a]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(p);
        bind(p, "uBelow", 0, gb[0]);
        bind(p, "uTop", 2, tx[cur]);
        common(p, g, tile, dw, dh, m0, m1, d.imgW, d.imgH, ppd);
        drawQuad(p);
        return a;
    }


    /**
     * Heal/clone strokes, blended onto the current buffer one chunk at a time, scissored to the
     * stroke. Healing first measures the boundary differences into a 32 x 1 buffer.
     */
    // ------------------------------------------------------------------ geometry effects in regions
    /** Set by the editor: where export / detail renders get source pixels for other regions. */
    SourceCache sourceCache;
    /** True while compositing the preview, whose sources (preview bitmaps) cover any region. */
    private boolean previewSources;
    private final int[][] srcSets = new int[2][];
    private int srcT;

    private static boolean wholeFrame(float[] t) {
        return t[0] <= 1e-4f && t[1] <= 1e-4f && t[0] + t[2] >= 1 - 1e-4f && t[1] + t[3] >= 1 - 1e-4f;
    }

    private int[] srcSet(int depth, int T) {
        if (srcT != T) freeSrcSets();
        if (srcSets[depth] == null) {
            int[] s = new int[6];
            for (int i = 0; i < 3; i++) { int[] f = makeFbo(T, T, true); s[i] = f[0]; s[3 + i] = f[1]; }
            srcSets[depth] = s;
            srcT = T;
        }
        return srcSets[depth];
    }

    void freeSrcSets() {
        for (int k = 0; k < srcSets.length; k++) {
            if (srcSets[k] == null) continue;
            for (int i = 0; i < 3; i++) {
                GLES20.glDeleteTextures(1, new int[]{srcSets[k][i]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{srcSets[k][3 + i]}, 0);
            }
            srcSets[k] = null;
        }
    }

    /**
     * A geometry effect (or image-filled fractal) inside a region render (export tile, detail
     * view). The pixels it fetches can lie anywhere in the image, so: work out the source bounds
     * of the region from the Java twin of the shader's map (Remap.bounds), render the layers
     * below this one for just that area at the resolution it's needed, then run the effect with
     * that render as its source. A region whose source would need more than one buffer at full
     * resolution is split 2x2 (up to 3 times); what still doesn't fit is rendered at a lower
     * resolution, which is what the map's minification needs anyway (Tile, little planet).
     * Chaotic maps (image-filled fractals) read a whole-image source.
     */
    private int remapRegion(Doc d, boolean crop, int i, Layer l, float[] tile, int vw, int vh, int bw, int bh,
                            int[] tx, int[] fb, int cur, int a, float ppd, Sources src, boolean cmp, int depth) {
        if (depth >= srcSets.length) return cur;   // nested deeper than we keep buffers for: leave as is
        float dw = d.docW(crop), dh = d.docH(crop);
        float[] m0 = new float[3], m1 = new float[3];
        d.docToImg(crop, m0, m1);
        int T = Math.min(TILE, maxTex);
        int[] set = srcSet(depth, T);
        int[] stx = {set[0], set[1], set[2]}, sfb = {set[3], set[4], set[5]};
        double ox = tile[0] * dw, oy = tile[1] * dh, ow = tile[2] * dw, oh = tile[3] * dh;
        boolean chaotic = Fx.chaotic(l);
        java.util.ArrayList<double[]> work = new java.util.ArrayList<double[]>(), jobs = new java.util.ArrayList<double[]>();
        work.add(new double[]{ox, oy, ow, oh, 0});
        while (!work.isEmpty()) {
            double[] r = work.remove(work.size() - 1);
            double[] bb = chaotic ? new double[]{0, 0, dw, dh} : Remap.bounds(l.fx, l.fxp, dw, dh, r[0], r[1], r[2], r[3]);
            double need = Math.max(bb[2], bb[3]) * ppd;
            if (need <= T - 8 || chaotic || r[4] >= 3) {
                jobs.add(new double[]{r[0], r[1], r[2], r[3], bb[0], bb[1], bb[2], bb[3]});
            } else {
                double hw = r[2] / 2, hh = r[3] / 2;
                for (int k = 0; k < 4; k++) work.add(new double[]{r[0] + (k % 2) * hw, r[1] + (k / 2) * hh, hw, hh, r[4] + 1});
            }
        }
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        try {
            for (double[] j : jobs) {
                double sp = Math.min(ppd, (T - 8) / Math.max(j[6], j[7]));
                int sw = Math.max(1, Math.min(T, (int) Math.ceil(j[6] * sp))), sh = Math.max(1, Math.min(T, (int) Math.ceil(j[7] * sp)));
                float[] stile = {(float) (j[4] / dw), (float) (j[5] / dh), (float) (sw / sp / dw), (float) (sh / sp / dh)};
                ArrayList<Integer> made = new ArrayList<Integer>();
                Sources s2 = src;
                if (!previewSources && sourceCache != null) {
                    s2 = upload(sourceCache.prepare(d, crop, new float[]{(float) j[4], (float) j[5], (float) (sw / sp), (float) (sh / sp)}, (float) sp), made);
                }
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                int keepClip = clipTex;   // the nested render resets it; this effect may be clipped
                int r = renderStack(d, crop, stile, sw, sh, T, T, stx, sfb, (float) sp, s2, cmp, i, depth + 1);
                clipTex = keepClip;
                freeMade(made);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                // the nested render changed the buffer-scale globals: restore this region's
                bsx = vw / (float) bw; bsy = vh / (float) bh; tlx = 1f / bw; tly = 1f / bh;
                mipOn(stx[r]);
                srcTex = stx[r];
                srcRect[0] = stile[0] * dw; srcRect[1] = stile[1] * dh; srcRect[2] = stile[2] * dw; srcRect[3] = stile[3] * dh;
                srcSX = sw / (float) T;
                srcSY = sh / (float) T;
                int x0 = (int) Math.round((j[0] - ox) * ppd), y0 = (int) Math.round((j[1] - oy) * ppd);
                int x1 = (int) Math.round((j[0] + j[2] - ox) * ppd), y1 = (int) Math.round((j[1] + j[3] - oy) * ppd);
                if (x1 >= vw - 1) x1 = vw;
                if (y1 >= vh - 1) y1 = vh;
                if (x1 <= x0 || y1 <= y0) continue;
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[a]);
                GLES20.glScissor(x0, y0, x1 - x0, y1 - y0);
                pass(l, FX_PROGS[Fx.program(l)], tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, d.imgW, d.imgH, ppd, null);
            }
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }
        return a;
    }

    private void retouch(Layer l, Layer t, Src s, int fbo, int vw, int vh, float[] tile, float dw, float dh,
                         float[] m0, float[] m1, Doc d, boolean crop, float ppd) {
        if (bndFbo == 0) {
            int[] f = makeFbo(32, 1, true);
            bndTex = f[0];
            bndFbo = f[1];
            bndEnc = !halfFloat;
        }
        int pb = progs.get("retouch_bnd"), p = progs.get("retouch");
        float[] r0 = new float[3], r1 = new float[3];
        t.layerRows(r0, r1);
        float[] o = new float[2];
        for (Retouch.Stroke st : l.strokes) {
            float[] box = st.box();
            float minx = 1e9f, miny = 1e9f, maxx = -1e9f, maxy = -1e9f;
            for (int k = 0; k < 4; k++) {
                d.imgToDoc(crop, (k & 1) == 0 ? box[0] : box[2], (k & 2) == 0 ? box[1] : box[3], o);
                float vx = (o[0] / dw - tile[0]) / tile[2] * vw, vy = (o[1] / dh - tile[1]) / tile[3] * vh;
                minx = Math.min(minx, vx); maxx = Math.max(maxx, vx);
                miny = Math.min(miny, vy); maxy = Math.max(maxy, vy);
            }
            int sx0 = Math.max(0, (int) Math.floor(minx) - 1), sy0 = Math.max(0, (int) Math.floor(miny) - 1);
            int sx1 = Math.min(vw, (int) Math.ceil(maxx) + 1), sy1 = Math.min(vh, (int) Math.ceil(maxy) + 1);
            if (sx1 <= sx0 || sy1 <= sy0) continue;
            for (float[][] ch : st.chunks()) {
                int nb = ch[1].length / 2;
                if (st.heal && nb > 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bndFbo);
                    GLES20.glViewport(0, 0, 32, 1);
                    GLES20.glUseProgram(pb);
                    u3(pb, "uL0", r0[0], r0[1], r0[2]);
                    u3(pb, "uL1", r1[0], r1[1], r1[2]);
                    u4(pb, "uSrcRect", s.x, s.y, s.w, s.h);
                    bind(pb, "uSrc", 2, s.tex);
                    u1(pb, "uRad", st.rad);
                    u2(pb, "uOff", st.offX, st.offY);
                    u1(pb, "uDiffEnc", bndEnc ? 1 : 0);
                    int lb = loc(pb, "uBnd[0]");
                    if (lb >= 0) GLES20.glUniform2fv(lb, nb, ch[1], 0);
                    drawQuad(pb);
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glViewport(0, 0, vw, vh);
                GLES20.glUseProgram(p);
                common(p, l, tile, dw, dh, m0, m1, d.imgW, d.imgH, ppd);
                u3(p, "uL0", r0[0], r0[1], r0[2]);
                u3(p, "uL1", r1[0], r1[1], r1[2]);
                u4(p, "uSrcRect", s.x, s.y, s.w, s.h);
                bind(p, "uSrc", 2, s.tex);
                bind(p, "uDiff", 3, bndTex);
                u1(p, "uDiffEnc", bndEnc ? 1 : 0);
                u1(p, "uRad", st.rad);
                u1(p, "uFeather", st.feather);
                u2(p, "uOff", st.offX, st.offY);
                u1(p, "uHeal", st.heal && nb > 0 ? 1 : 0);
                int lp = loc(p, "uPts[0]"), lb = loc(p, "uBnd[0]");
                if (lp >= 0) GLES20.glUniform2fv(lp, ch[0].length / 2, ch[0], 0);
                if (lb >= 0 && nb > 0) GLES20.glUniform2fv(lb, nb, ch[1], 0);
                u1(p, "uNPts", ch[0].length / 2);
                u1(p, "uNBnd", nb);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(sx0, sy0, sx1 - sx0, sy1 - sy0);
                drawQuad(p);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
        }
    }

    private int bndTex, bndFbo;
    private boolean bndEnc;

    // ------------------------------------------------------------------ region renders
    /** Uploads prepared bitmaps; returns the Sources and fills `made` with textures to delete. */
    private Sources upload(final SourceCache.Prepared prep, final ArrayList<Integer> made) {
        final HashMap<Integer, Src> m = new HashMap<Integer, Src>();
        for (Map.Entry<Integer, Bitmap> e : prep.bmp.entrySet()) {
            Bitmap b = e.getValue();
            int t;
            if (prep.owned.contains(b)) {
                t = newTex();
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                made.add(t);
            } else t = tex(b, 0, true);
            m.put(e.getKey(), new Src(t, prep.rect.get(e.getKey())));
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        prep.recycle();
        return new Sources() {
            @Override public Src get(Layer l) { return m.get(l.id); }
        };
    }

    private void freeMade(ArrayList<Integer> made) {
        for (int t : made) {
            GLES20.glDeleteTextures(1, new int[]{t}, 0);
        }
        made.clear();
    }

    /** Full-resolution render of the visible doc rect (x, y, w, h, doc px) at rres px per doc px. GL thread. */
    void renderDetail(Doc d, int ver, float[] rect, float rres, SourceCache.Prepared prep) {
        previewShapes = false;   // 3.15: full-resolution renders wait for exact text/shape textures
        if (progs.isEmpty()) { prep.recycle(); return; }
        int A = apronFor(d, rres);
        int vw = (int) Math.ceil(rect[2] * rres) + 2 * A, vh = (int) Math.ceil(rect[3] * rres) + 2 * A;
        if (vw > maxTex || vh > maxTex) { prep.recycle(); return; }
        if (vw > detBW || vh > detBH) allocDet(Math.max(vw, detBW), Math.max(vh, detBH));
        float dw = d.docW(false), dh = d.docH(false);
        float[] tile = {(rect[0] - A / rres) / dw, (rect[1] - A / rres) / dh, (vw / rres) / dw, (vh / rres) / dh};
        ArrayList<Integer> made = new ArrayList<Integer>();
        Sources s = upload(prep, made);
        detRes = renderStack(d, false, tile, vw, vh, detBW, detBH, detTex, detFbo, rres, s, false);
        freeMade(made);
        detVer = ver;
        detApron = A;
        detRect = rect;
        detRres = rres;
    }

    void detailOff() { detRes = -1; detVer = -1; }

    interface ExportProgress { void progress(float f, String msg); }

    /** Thrown when the GL context isn't current (app paused): the caller waits and retries the band. */
    static final class NotCurrent extends Exception {
        NotCurrent() { super("paused"); }
    }

    /**
     * A full-resolution export in progress. The output is rendered one band (a row of tiles) per
     * call, each call a separate GL-thread event, so the GL thread can pause between bands and
     * only one band of pixels (outW x band height) exists at a time.
     */
    static final class ExportJob {
        final Doc d;
        final int outW, outH, T, A, C, bands, nx;
        final boolean opaque;
        final float k;
        final int gen;
        /** Sources that couldn't be read at full detail during this export (shown to the user afterwards). */
        final java.util.LinkedHashMap<Integer, String> downgrades = new java.util.LinkedHashMap<Integer, String>();
        int[] fa, fb, fc, fo;
        ByteBuffer px;

        ExportJob(Doc d, int outW, int outH, boolean opaque, int T, int A, int gen) {
            this.d = d; this.outW = outW; this.outH = outH; this.opaque = opaque;
            this.T = T; this.A = A; this.gen = gen;
            C = T - 2 * A;
            k = outW / d.docW(false);
            bands = (outH + C - 1) / C;
            nx = (outW + C - 1) / C;
        }

        int bandY(int b) { return b * C; }
        int bandH(int b) { return Math.min(C, outH - b * C); }
        /** Rows a band bitmap needs. */
        int bandMax() { return Math.min(C, outH); }
    }

    /** Counts GL contexts: an export started on one context can't continue on another. */
    private int glGen;

    private static boolean contextCurrent() {
        // GLSurfaceView releases the context (eglMakeCurrent NO_CONTEXT) while paused, even when it
        // preserves it; GL calls then silently do nothing. Compare handles, not wrapper objects.
        android.opengl.EGLContext c = android.opengl.EGL14.eglGetCurrentContext();
        return c != null && !c.equals(android.opengl.EGL14.EGL_NO_CONTEXT);
    }

    /** GL thread. Allocates the tile buffers. */
    ExportJob beginExport(Doc d, int outW, int outH, boolean opaque) throws Exception {
        if (!contextCurrent()) throw new NotCurrent();
        if (progs.isEmpty()) throw new Exception("the renderer isn't ready");
        float k = outW / d.docW(false);
        int need = Math.max(APRON, apronNeeded(d, k));
        int T = Math.min(maxTex, tileFor(need, maxTex));
        int[] fa = null, fb = null, fc = null, fo = null;
        while (true) {   // bigger tiles for wide effects; step down if the GPU can't allocate them
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}
            fa = makeFbo(T, T, true); fb = makeFbo(T, T, true); fc = makeFbo(T, T, true); fo = makeFbo(T, T, false);
            if (GLES20.glGetError() != GLES20.GL_OUT_OF_MEMORY || T <= TILE) break;
            for (int[] f : new int[][]{fa, fb, fc, fo}) { GLES20.glDeleteTextures(1, new int[]{f[0]}, 0); GLES20.glDeleteFramebuffers(1, new int[]{f[1]}, 0); }
            T = T > 3072 ? 3072 : TILE;
        }
        int A = Math.min(need, (T - 256) / 2);
        ExportJob j = new ExportJob(d, outW, outH, opaque, T, A, glGen);
        j.fa = fa; j.fb = fb; j.fc = fc; j.fo = fo;
        if (A < need)   // say so instead of silently cutting the effect's reach at tile edges
            j.downgrades.put(-1, String.format(java.util.Locale.US,
                    "Very wide blur/glow: it reads %d px around each pixel but export tiles overlap %d px here, so faint seams are possible. A smaller radius or output size avoids it.", need, A));
        j.px = ByteBuffer.allocateDirect(j.C * j.C * 4).order(ByteOrder.nativeOrder());
        return j;
    }

    /** GL thread. Renders band b of the job into rows 0..bandH(b)-1 of `band` (outW wide). */
    void renderBand(ExportJob j, SourceCache sc, int b, Bitmap band, ExportProgress pr) throws Exception {
        previewShapes = false;   // 3.15: full-resolution renders wait for exact text/shape textures
        if (!contextCurrent()) throw new NotCurrent();
        if (j.gen != glGen || j.fa == null) throw new Exception("the app was closed during export (graphics reset); please export again");
        Doc d = j.d;
        float dw = d.docW(false), dh = d.docH(false);
        int T = j.T, A = j.A, C = j.C, outW = j.outW, outH = j.outH;
        int[] tx = {j.fa[0], j.fb[0], j.fc[0]}, fbo = {j.fa[1], j.fb[1], j.fc[1]};
        Canvas cv = new Canvas(band);
        Paint copy = new Paint();
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        ArrayList<Integer> made = new ArrayList<Integer>();
        int y0 = j.bandY(b), th = j.bandH(b);
        try {
            for (int tx0 = 0; tx0 < j.nx; tx0++) {
                int x0 = tx0 * C;
                int tw = Math.min(C, outW - x0);
                int vw = tw + 2 * A, vh = th + 2 * A;
                float[] tile = {(x0 - A) / (float) outW, (y0 - A) / (float) outH, vw / (float) outW, vh / (float) outH};
                int done = b * j.nx + tx0, all = j.bands * j.nx;
                pr.progress(done / (float) all, "Rendering tile " + (done + 1) + " of " + all);
                SourceCache.Prepared prep = sc.prepare(d, false,
                        new float[]{tile[0] * dw, tile[1] * dh, tile[2] * dw, tile[3] * dh}, j.k);
                j.downgrades.putAll(prep.downgrades);
                Sources s = upload(prep, made);
                int cur = renderStack(d, false, tile, vw, vh, T, T, tx, fbo, j.k, s, false);
                freeMade(made);
                int p = progs.get("output");
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, j.fo[1]);
                GLES20.glViewport(0, 0, vw, vh);
                GLES20.glUseProgram(p);
                bind(p, "uComp", 0, tx[cur]);
                u1(p, "uOpaque", j.opaque ? 1 : 0);
                u3(p, "uMatte", 1, 1, 1);
                u1(p, "uDither", 1);
                u2(p, "uCompScale", vw / (float) T, vh / (float) T);
                drawQuad(p);
                j.px.rewind();
                GLES20.glReadPixels(A, A, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, j.px);
                Bitmap tb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                j.px.rewind();
                tb.copyPixelsFromBuffer(j.px);
                cv.drawBitmap(tb, x0, 0, copy);
                tb.recycle();
            }
            int err = GLES20.glGetError();
            if (err == GLES20.GL_OUT_OF_MEMORY) throw new Exception("the GPU ran out of memory");
        } finally {
            freeMade(made);
            bsx = bsy = 1;
            lastVer = -1;   // previews drawn between bands must recomposite
        }
    }

    /** GL thread. Frees the job's buffers (harmless if the context was lost meanwhile). */
    void endExport(ExportJob j) {
        if (j == null || j.fa == null) return;
        if (j.gen == glGen && contextCurrent()) {
            for (int[] f : new int[][]{j.fa, j.fb, j.fc, j.fo}) {
                GLES20.glDeleteTextures(1, new int[]{f[0]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{f[1]}, 0);
            }
            freeSrcSets();
        }
        j.fa = j.fb = j.fc = j.fo = null;
        j.px = null;
        bsx = bsy = 1;
        lastVer = -1;
    }

    // ------------------------------------------------------------------ GL helpers
    private void allocComp(int w, int h) {
        for (int i = 0; i < 3; i++) {
            if (compW > 0) {
                GLES20.glDeleteTextures(1, new int[]{compTex[i]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{compFbo[i]}, 0);
            }
            int[] f = makeFbo(w, h, true);
            compTex[i] = f[0];
            compFbo[i] = f[1];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compTex[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            // a mip-filtered texture with no mip levels is "incomplete" and samples as black
            GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        }
        compW = w;
        compH = h;
        result = -1;
    }

    private void allocDet(int w, int h) {
        for (int i = 0; i < 3; i++) {
            if (detBW > 0) {
                GLES20.glDeleteTextures(1, new int[]{detTex[i]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{detFbo[i]}, 0);
            }
            int[] f = makeFbo(w, h, true);
            detTex[i] = f[0];
            detFbo[i] = f[1];
        }
        detBW = w;
        detBH = h;
        detRes = -1;
    }

    private void gcTextures(HashSet<Bitmap> used, Doc d) {
        Iterator<Map.Entry<Bitmap, int[]>> it = texCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Bitmap, int[]> e = it.next();
            if (!used.contains(e.getKey())) {
                GLES20.glDeleteTextures(1, new int[]{e.getValue()[0]}, 0);
                it.remove();
            }
        }
        HashSet<Integer> ids = new HashSet<Integer>();
        for (Layer l : d.layers) ids.add(l.id);
        Iterator<Map.Entry<Integer, int[]>> lt = lutCache.entrySet().iterator();
        while (lt.hasNext()) {
            Map.Entry<Integer, int[]> e = lt.next();
            if (!ids.contains(e.getKey())) {
                GLES20.glDeleteTextures(1, new int[]{e.getValue()[0]}, 0);
                lt.remove();
            }
        }
    }

}
