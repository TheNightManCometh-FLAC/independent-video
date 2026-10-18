package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Shared GL plumbing for the photo engine and the video compositor: program compilation from
 * assets, uniform/texture helpers, framebuffers, bitmap/LUT texture caches, and the per-layer
 * pass (photo, fill, text, every adjustment type) that both pipelines run. Must be used on one
 * GL thread. Uploads happen on texture unit 7 so they never disturb a pass's bindings.
 */
class GlBase {
    final Context ctx;
    final HashMap<String, Integer> progs = new HashMap<String, Integer>();
    final HashMap<String, Integer> locCache = new HashMap<String, Integer>();
    final FloatBuffer quad;
    final IdentityHashMap<Bitmap, int[]> texCache = new IdentityHashMap<Bitmap, int[]>();
    final HashMap<Integer, int[]> lutCache = new HashMap<Integer, int[]>();
    final HashMap<String, Integer> lut3Cache = new HashMap<String, Integer>();
    boolean halfFloat = true;
    int blankTex;
    int maxTex = 4096;
    // per-pass state for region renders
    float bsx = 1, bsy = 1, tlx, tly;
    /** Video frame time (s) for time-varying effects (film grain); 0 for photos. */
    float frameT;
    int midTex;
    // source for geometry / image-fractal effects: texture, the doc rect it covers, valid uv extent
    int srcTex;
    final float[] srcRect = new float[4];
    float srcSX = 1, srcSY = 1;
    static final String[] FX_PROGS = {"adj_fx", "fx_gen", "fx_remap"};

    GlBase(Context ctx) {
        this.ctx = ctx;
        float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
    }

    /** Call from onSurfaceCreated (or after making a new context current). */
    void initGl(String[] names) throws Exception {
        progs.clear(); locCache.clear(); texCache.clear(); lutCache.clear(); lut3Cache.clear();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4);
        int[] mt = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mt, 0);
        maxTex = Math.max(2048, mt[0]);
        blankTex = newTex();
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4));
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        String common = asset("shaders/common.glsl");
        for (String n : names) {
            String src = asset("shaders/" + n + ".frag");
            // #extension must precede every non-preprocessor token, so it goes before common
            String ext = "";
            if (src.startsWith("#extension")) {
                int nl = src.indexOf('\n');
                ext = src.substring(0, nl + 1);
                src = src.substring(nl + 1);
            }
            progs.put(n, program(ext + common + "\n" + src));
        }
    }

    /** 3D LUT as a (size*size) x size half-float atlas: x = r + b*size, y = g. */
    /** Grid size of the atlas lut3Tex uploaded for a LUT (it may be resampled to fit the GPU). */
    final HashMap<String, Integer> lut3Size = new HashMap<String, Integer>();

    int lut3Tex(Lut lut) {
        Integer t = lut3Cache.get(lut.name);
        if (t != null) return t;
        int fit = Lut.fitSize(lut.size, maxTex);
        if (fit < lut.size) lut = lut.resampled(fit);   // 65^3 or bigger on a 4096 px GPU: 64^3, never a failed upload
        lut3Size.put(lut.name, lut.size);
        int tex = newTex();
        if (halfFloat) {
            ShortBuffer sb = ByteBuffer.allocateDirect(lut.data.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(lut.data).position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, lut.size * lut.size, lut.size, 0,
                    GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, sb);
        } else {
            ByteBuffer bb = ByteBuffer.allocateDirect(lut.data.length).order(ByteOrder.nativeOrder());
            for (short h : lut.data) bb.put((byte) Math.max(0, Math.min(255, Math.round(Lut.fromHalf(h) * 255))));
            bb.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.size * lut.size, lut.size, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        lut3Cache.put(lut.name, tex);
        return tex;
    }

    static String adjProgram(int type) {
        switch (type) {
            case Layer.LUT: return "adj_lut";
            case Layer.EFFECT: return "adj_fx";
            case Layer.BASIC: return "adj_basic";
            case Layer.CURVES: return "adj_curves";
            case Layer.LEVELS: return "adj_levels";
            default: return "adj_hsl";
        }
    }

    /** Coverage of the clipping base for the pass being drawn (0 = not clipped). */
    int clipTex;
    /** Draw a pixel layer's coverage instead of compositing it (clipping bases). */
    boolean covOut;

    void common(int p, Layer l, float[] tile, float dw, float dh, float[] m0, float[] m1, float imgW, float imgH, float ppd) {
        if (clipTex != 0) {
            bind(p, "uClip", 6, clipTex);
            u1(p, "uClipOn", 1);
        } else u1(p, "uClipOn", 0);
        u1(p, "uCovOut", covOut ? 1 : 0);
        u4(p, "uTile", tile[0], tile[1], tile[2], tile[3]);
        u2(p, "uDocSize", dw, dh);
        u3(p, "uM0", m0[0], m0[1], m0[2]);
        u3(p, "uM1", m1[0], m1[1], m1[2]);
        u2(p, "uImgSize", imgW, imgH);
        u1(p, "uOpacity", l.opacity);
        u1(p, "uMode", l.mode);
        u2(p, "uBelowScale", bsx, bsy);
        u2(p, "uTexel", tlx, tly);
        u1(p, "uPxPerDoc", ppd);
        u1(p, "uFrameT", frameT);
        u1(p, "uRangeOn", l.rangeMode);
        u4(p, "uRangeL", l.rangeL[0], l.rangeL[1], l.rangeL[2], l.rangeL[3]);
        u4(p, "uRangeC", l.rangeC[0], l.rangeC[1], l.rangeC[2], l.rangeC[3]);
        u1(p, "uRangeF", l.rangeF);
        u1(p, "uRangeInv", l.rangeInv ? 1 : 0);
        if (l.mask != null) {
            bind(p, "uMask", 1, tex(l.mask, l.maskVer, true));
            u1(p, "uMaskOn", 1);
            maskRows(p, l.maskAffine(Math.round(imgW), Math.round(imgH)), l.mask.getWidth(), l.mask.getHeight());
            maskComps(p, l);
        } else {
            bind(p, "uMask", 1, blankTex);
            u1(p, "uMaskOn", 0);
            u1(p, "uMaskN", 0);
        }
    }

    private static final String[] MA = new String[MaskStack.MAX], MB = new String[MaskStack.MAX], MC = new String[MaskStack.MAX],
            MD = new String[MaskStack.MAX], ME = new String[MaskStack.MAX];
    private static final String[] SHAPE_TEX = {"uShape0", "uShape1", "uShape2", "uShape3"};
    static {
        for (int k = 0; k < MaskStack.MAX; k++) {
            MA[k] = "uMA[" + k + "]"; MB[k] = "uMB[" + k + "]"; MC[k] = "uMC[" + k + "]"; MD[k] = "uMD[" + k + "]"; ME[k] = "uME[" + k + "]";
        }
    }

    /**
     * 3.15: the live preview sets this so a text or shape whose distance texture isn't built yet
     * never stalls a frame (it is built in the background; MaskRaster.onReady re-renders). Export
     * and other renders leave it false and wait for exact textures.
     */
    boolean previewShapes;
    /** Distance-texture images bound since the last texture clean-up (kept alive by it). */
    final java.util.HashSet<Bitmap> shapeImages = new java.util.HashSet<Bitmap>();

    /** maskAt's shape components for l (common.glsl); l == null or no shapes = the raster alone. */
    void maskComps(int p, Layer l) {
        int n = l == null || l.mask == null ? 0 : Math.min(l.comps.size(), MaskStack.MAX);
        u1(p, "uMaskN", n);
        if (n == 0) return;
        u2(p, "uMaskPx", l.mask.getWidth(), l.mask.getHeight());
        u1(p, "uPaintAt", Math.min(l.paintAt, n));
        u1(p, "uPaintOp", l.paintOp);
        float[][] u = MaskStack.uniforms(l.comps, !previewShapes);
        boolean[] bound = new boolean[SHAPE_TEX.length];
        for (int k = 0; k < n; k++) {
            float[] o = u[k];
            u4(p, MA[k], o[0], o[1], o[2], o[3]);
            u4(p, MB[k], o[4], o[5], o[6], o[7]);
            u4(p, MC[k], o[8], o[9], o[10], o[11]);
            u4(p, MD[k], o[12], o[13], o[14], o[15]);
            u4(p, ME[k], o[16], o[17], o[18], o[19]);
            MaskStack.Comp c = l.comps.get(k);
            if (c.isRaster() && o[0] >= MaskStack.SHAPE && c.last != null) {
                int slot = (int) o[19];
                if (slot < 0 || slot >= SHAPE_TEX.length) continue;
                Bitmap img = TextRaster.image(c.last);
                shapeImages.add(img);
                bind(p, SHAPE_TEX[slot], 8 + slot, tex(img, 0, false));
                bound[slot] = true;
            }
        }
        for (int i = 0; i < bound.length; i++) if (!bound[i]) bind(p, SHAPE_TEX[i], 8 + i, blankTex);
    }

    /** uMR0/uMR1: img px -> mask uv, from an img px -> mask px map of a w x h mask. */
    void maskRows(int p, double[] m, int w, int h) {
        u3(p, "uMR0", (float) (m[0] / w), (float) (m[1] / w), (float) (m[2] / w));
        u3(p, "uMR1", (float) (m[3] / h), (float) (m[4] / h), (float) (m[5] / h));
    }

    void pass(Layer l, String prog, int below, int dstFbo, int vw, int vh, float[] tile, float dw, float dh,
              float[] m0, float[] m1, float imgW, float imgH, float ppd, Engine.Src s) {
        int p = progs.get(prog);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(p);
        bind(p, "uBelow", 0, below);
        common(p, l, tile, dw, dh, m0, m1, imgW, imgH, ppd);
        switch (l.type) {
            case Layer.FILL: {
                int c = l.fillColor;
                u4(p, "uFill", ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 1);
                bind(p, "uSrc", 2, blankTex);
                break;
            }
            case Layer.IMAGE:
            case Layer.TEXT: {
                float[] r0 = new float[3], r1 = new float[3];
                l.layerRows(r0, r1);
                u3(p, "uL0", r0[0], r0[1], r0[2]);
                u3(p, "uL1", r1[0], r1[1], r1[2]);
                u2(p, "uLSize", l.srcW, l.srcH);
                u4(p, "uSrcRect", s.x, s.y, s.w, s.h);
                u4(p, "uFill", 0, 0, 0, 0);
                bind(p, "uSrc", 2, s.tex);
                break;
            }
            case Layer.BASIC: {
                float[] b = l.basic;
                u4(p, "uB0", b[0], b[1], b[2], b[3]);
                u4(p, "uB1", b[4], b[5], b[6], b[7]);
                u4(p, "uB2", b[8], b[9], 0, 0);
                break;
            }
            case Layer.CURVES:
                bind(p, "uLut", 2, lutTex(l));
                u1(p, "uLutN", Curves.N);
                break;
            case Layer.LEVELS: {
                float[] v = l.levels;
                for (int c = 0; c < 4; c++) {
                    int lv = loc(p, "uLv[" + c + "]"), lo = loc(p, "uLo[" + c + "]");
                    if (lv >= 0) GLES20.glUniform4f(lv, v[c * 5], v[c * 5 + 1], v[c * 5 + 2], 0);
                    if (lo >= 0) GLES20.glUniform2f(lo, v[c * 5 + 3], v[c * 5 + 4]);
                }
                break;
            }
            case Layer.HSL: {
                int loc = loc(p, "uHsl[0]");
                if (loc >= 0) GLES20.glUniform3fv(loc, 8, l.hsl, 0);
                break;
            }
            case Layer.LUT: {
                Lut lut = Lut.get(l.lutFile);
                bind(p, "uLut3", 2, lut == null ? blankTex : lut3Tex(lut));
                Integer gs = lut == null ? null : lut3Size.get(lut.name);
                u1(p, "uLutSize", gs == null ? 0 : gs);
                if (lut != null) {
                    u3(p, "uDomMin", lut.dmin[0], lut.dmin[1], lut.dmin[2]);
                    u3(p, "uDomMax", lut.dmax[0], lut.dmax[1], lut.dmax[2]);
                }
                break;
            }
            case Layer.EFFECT: {
                float[] q = l.fxp;
                u1(p, "uFx", l.fx);
                u4(p, "uFxP0", q[0], q[1], q[2], q[3]);
                u4(p, "uFxP1", q[4], q[5], q[6], q[7]);
                for (int i = 0; i < 3; i++) {
                    int c = l.fxc[i];
                    u3(p, "uFxC" + i, ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
                }
                u1(p, "uLong", Math.max(imgW, imgH) * ppd);
                bind(p, "uMid", 3, Fx.get(l.fx).twoPass ? midTex : blankTex);
                if (Fx.needsSource(l)) {
                    bind(p, "uSrc", 4, srcTex);
                    u4(p, "uSrcRect", srcRect[0], srcRect[1], srcRect[2], srcRect[3]);
                    u2(p, "uSrcScale", srcSX, srcSY);
                }
                break;
            }
            case Layer.DETAIL: {
                float[] t = l.det;
                u4(p, "uDet0", t[0], t[1], t[2], t[3]);
                u4(p, "uDet1", t[4], t[5], 0, 0);
                if (prog.equals("detail_sharp")) bind(p, "uMid", 3, midTex);
                break;
            }
        }
        drawQuad(p);
    }


    /** Gives an FBO texture fresh mip levels and a trilinear filter, so a pass can read it with a bias. */
    void mipOn(int tex) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /**
     * Runs an effect layer reading buffer `cur`. Single-pass effects write `a`; blur-based ones
     * blur cur -> a (horizontal) -> b (vertical), then combine cur + b into a. Returns the index
     * of the buffer holding the result (always a).
     */
    int effect(Layer l, int[] tx, int[] fb, int cur, int a, int b, int vw, int vh, float[] tile, float dw, float dh,
               float[] m0, float[] m1, float imgW, float imgH, float ppd) {
        Fx.Def f = Fx.get(l.fx);
        if (f.twoPass) {
            float sigma = l.fxp[0] * Math.max(imgW, imgH) * ppd;
            mipOn(tx[cur]);
            blurPass(l, tx[cur], fb[a], true, 1, 0, sigma, vw, vh, tile, dw, dh, m0, m1, imgW, imgH, ppd);
            mipOn(tx[a]);
            blurPass(l, tx[a], fb[b], false, 0, 1, sigma, vw, vh, tile, dw, dh, m0, m1, imgW, imgH, ppd);
            midTex = tx[b];
        } else if (f.mipBelow) mipOn(tx[cur]);
        if (Fx.needsSource(l)) {   // whole-frame render: the layer below is the source
            mipOn(tx[cur]);
            srcTex = tx[cur];
            srcRect[0] = tile[0] * dw; srcRect[1] = tile[1] * dh; srcRect[2] = tile[2] * dw; srcRect[3] = tile[3] * dh;
            srcSX = bsx;
            srcSY = bsy;
        }
        pass(l, FX_PROGS[Fx.program(l)], tx[cur], fb[a], vw, vh, tile, dw, dh, m0, m1, imgW, imgH, ppd, null);
        return a;
    }

    private void blurPass(Layer l, int src, int dst, boolean first, float dx, float dy, float sigma, int vw, int vh,
                          float[] tile, float dw, float dh, float[] m0, float[] m1, float imgW, float imgH, float ppd) {
        int p = progs.get("fx_blur");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(p);
        common(p, l, tile, dw, dh, m0, m1, imgW, imgH, ppd);
        bind(p, "uSrc", 0, src);
        u2(p, "uDir", dx, dy);
        u1(p, "uSigma", sigma);
        u1(p, "uFirst", first ? 1 : 0);
        u1(p, "uThresh", first && l.fx == Fx.GLOW ? l.fxp[1] : -1);
        if (l.fx == Fx.TILT) {
            double a = Math.toRadians(l.fxp[4]);
            u4(p, "uTilt", l.fxp[1], l.fxp[2] / 2f, l.fxp[3], 1);
            u2(p, "uTiltN", (float) -Math.sin(a), (float) Math.cos(a));
        } else {
            u4(p, "uTilt", 0, 0, 0, 0);
            u2(p, "uTiltN", 0, 1);
        }
        drawQuad(p);
    }

    int lutTex(Layer l) {
        int[] e = lutCache.get(l.id);
        if (l.lut == null) l.rebuildLut();
        if (e == null) {
            e = new int[]{newTex(), -1};
            lutCache.put(l.id, e);
        }
        if (e[1] != l.lutVer || e[1] == -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, e[0]);
            ShortBuffer sb = ByteBuffer.allocateDirect(l.lut.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(l.lut).position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, Curves.N, 1, 0,
                    GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, sb);
            e[1] = l.lutVer;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        return e[0];
    }


    /** Returns {texture, framebuffer}. Half-float if requested and supported, else RGBA8. */
    int[] makeFbo(int w, int h, boolean wantFloat) {
        int t = newTex();
        boolean useF = wantFloat && halfFloat;
        if (useF) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null);
        else GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE && useF) {
            halfFloat = false;
            GLES20.glDeleteFramebuffers(1, f, 0);
            GLES20.glDeleteTextures(1, new int[]{t}, 0);
            return makeFbo(w, h, false);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        return new int[]{t, f[0]};
    }

    /**
     * Creates a texture bound on the scratch unit 7 (left active). Uploads happen on unit 7 so
     * they can never replace a texture a pass has already bound on units 0-3; callers restore
     * unit 0 when done.
     */
    int newTex() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Bitmap texture, uploaded once per (bitmap, version), mip-mapped. */
    int tex(Bitmap b, int ver, boolean mip) {
        int[] e = texCache.get(b);
        if (e != null && e[1] == ver) return e[0];
        if (e == null) {
            e = new int[]{newTex(), -1};
            texCache.put(b, e);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, e[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        if (mip) {
            GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        e[1] = ver;
        return e[0];
    }


    void drawQuad(int p) {
        int a = GLES20.glGetAttribLocation(p, "aPos");
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    int loc(int p, String n) {
        String k = p + ":" + n;
        Integer v = locCache.get(k);
        if (v == null) { v = GLES20.glGetUniformLocation(p, n); locCache.put(k, v); }
        return v;
    }

    void u1(int p, String n, float a) { int l = loc(p, n); if (l >= 0) GLES20.glUniform1f(l, a); }
    void u2(int p, String n, float a, float b) { int l = loc(p, n); if (l >= 0) GLES20.glUniform2f(l, a, b); }
    void u3(int p, String n, float a, float b, float c) { int l = loc(p, n); if (l >= 0) GLES20.glUniform3f(l, a, b, c); }
    void u4(int p, String n, float a, float b, float c, float d) { int l = loc(p, n); if (l >= 0) GLES20.glUniform4f(l, a, b, c, d); }

    void bind(int p, String n, int unit, int t) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
        int l = loc(p, n);
        if (l >= 0) GLES20.glUniform1i(l, unit);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private static final String VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\n"
            + "void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }\n";

    int program(String frag) throws Exception {
        int vs = shader(GLES20.GL_VERTEX_SHADER, VERT), fs = shader(GLES20.GL_FRAGMENT_SHADER, frag);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    int shader(int type, String src) throws Exception {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    String asset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        try {
            byte[] b = new byte[in.available()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, "UTF-8");
        } finally { in.close(); }
    }
}
