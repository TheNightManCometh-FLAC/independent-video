package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;

/**
 * Transparent view over the GL canvas: owns the view transform (pan/zoom), routes one-finger
 * input to the active tool and draws tool overlays. Two fingers always navigate, except in
 * Transform, where they scale and rotate the selected layer.
 */
final class CanvasView extends View {
    static final int NAV = 0, BRUSH = 1, GRAD_LIN = 2, GRAD_RAD = 3, TRANSFORM = 4, CROP = 5, HEAL = 6, REMOVE = 7, SAMPLE = 8, FX_POINT = 9;
    static final int SEL_RECT = 10, SEL_ELL = 11, SEL_LASSO = 12, SEL_POLY = 13, SEL_BRUSH = 14, SEL_WAND = 15;
    /** Editing the selected mask shape's handles (3.6). */
    static final int MASK_SHAPE = 16;
    /** 3.15: draw a freehand outline that becomes a mask shape. */
    static final int MASK_FREEHAND = 17;

    static boolean isSelTool(int t) { return t >= SEL_RECT && t <= SEL_WAND; }

    /** Polygon lasso points so far, in image px (they stay put while you pan and zoom). */
    final ArrayList<Float> polyImg = new ArrayList<Float>();
    private boolean selDrag, strokeErase;
    private float strokeFlow = 1f;
    private final ArrayList<Float> lassoPts = new ArrayList<Float>();   // screen px

    final Editor ed;
    int tool = NAV;
    boolean erase;
    float brushSize = 60f, hardness = 0.5f, flow = 0.6f;   // size in screen px
    float offX, offY, scale = 1f;
    /** True while the view is at its fitted zoom; a resize then refits instead of keeping the zoom. */
    boolean fitted = true;
    float cropAspect = 0f;   // 0 = free
    /** Before/after divider as a fraction of the view width (fixed on screen while you pan); < 0 = off. */
    float splitFrac = -1f;
    private boolean splitDrag;
    // sample loupe (Pick neutral, colour range): drag to aim, lift to pick
    private boolean loupeOn;
    private float loupeCx, loupeCy, loupeK;
    private ValueAnimator zoomAnim;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean multi, stroking, gradActive, dragSrc, painting;
    private float lastX, lastY, downX, downY, curX, curY;
    private float pinchDist, pinchAng, pinchMx, pinchMy;
    private boolean fxDrag;
    private long lastTapTime;
    private Canvas maskCanvas;
    private float residual;
    private int cropHandle = -1;
    private final RectF cropStart = new RectF();
    private boolean showCursor;
    private float srcStartX, srcStartY;
    private final ArrayList<Float> pathPts = new ArrayList<Float>();   // screen px, current heal/remove stroke

    CanvasView(Context c, Editor ed) {
        super(c);
        this.ed = ed;
    }

    // ---------------------------------------------------------------- view transform
    /** The zoom at which the whole document fits with a margin. */
    float fitScale() {
        Doc d = ed.doc;
        float dw = d.docW(ed.cropMode), dh = d.docH(ed.cropMode);
        float m = Ui.dp(ed.cropMode ? 28 : 12);
        return Math.min((getWidth() - 2 * m) / dw, (getHeight() - 2 * m) / dh);
    }

    void fit() {
        Doc d = ed.doc;
        float dw = d.docW(ed.cropMode), dh = d.docH(ed.cropMode);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        scale = fitScale();
        offX = (w - dw * scale) / 2;
        offY = (h - dh * scale) / 2;
        fitted = true;
        push();
    }

    /** Keep the document centre where it is when the doc size changes (straighten in crop mode). */
    void docResized(float ow, float oh, float nw, float nh) {
        offX += (ow - nw) / 2 * scale;
        offY += (oh - nh) / 2 * scale;
        push();
    }

    void stopZoomAnim() {
        ValueAnimator a = zoomAnim;
        zoomAnim = null;
        if (a != null) a.cancel();
    }

    /**
     * Double-tap and the zoom readout: from fit to 100 % (one image px per screen px) keeping the
     * tapped point under the finger, from any other zoom back to fit. Animated over 200 ms: the
     * doc point at the view centre moves linearly while the scale changes geometrically.
     */
    void toggleZoom(float sx, float sy) {
        if (ed.doc == null || getWidth() == 0 || getHeight() == 0) return;
        stopZoomAnim();
        Doc d = ed.doc;
        final float dw = d.docW(ed.cropMode), dh = d.docH(ed.cropMode), w = getWidth(), h = getHeight();
        final float fs = fitScale();
        float t = ViewMath.zoomTarget(fs, scale, fitted);
        final boolean toFit = t < 0;
        final float s0 = scale, s1 = toFit ? fs : t;
        final float ox1 = toFit ? (w - dw * s1) / 2 : ViewMath.clampOff(ViewMath.anchor(offX, s0, s1, sx), dw * s1, w);
        final float oy1 = toFit ? (h - dh * s1) / 2 : ViewMath.clampOff(ViewMath.anchor(offY, s0, s1, sy), dh * s1, h);
        final float c0x = (w / 2 - offX) / s0, c0y = (h / 2 - offY) / s0, c1x = (w / 2 - ox1) / s1, c1y = (h / 2 - oy1) / s1;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(200);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                if (zoomAnim != a || ed.doc == null) return;
                float f = (Float) va.getAnimatedValue();
                if (f >= 1f) {
                    zoomAnim = null;
                    if (toFit) { fit(); return; }
                    scale = s1; offX = ox1; offY = oy1;
                    fitted = false;
                    push();
                    return;
                }
                scale = (float) (s0 * Math.pow(s1 / s0, f));
                offX = w / 2 - (c0x + (c1x - c0x) * f) * scale;
                offY = h / 2 - (c0y + (c1y - c0y) * f) * scale;
                fitted = false;
                push();
            }
        });
        zoomAnim = a;
        a.start();
    }

    void setSplit(boolean on) {
        splitFrac = on ? 0.5f : -1f;
        splitDrag = false;
        push();
    }

    void push() {
        ed.engine.splitX = splitFrac < 0 ? -1f : splitFrac * getWidth();
        ed.zoomChanged();
        ed.engine.viewOffX = offX;
        ed.engine.viewOffY = offY;
        ed.engine.viewScale = scale;
        ed.requestRender();
        ed.scheduleDetail();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (ed.doc == null) return;
        stopZoomAnim();
        // the inspector resized the canvas: keep a zoomed view's centre point and zoom
        if (fitted || ow <= 0 || oh <= 0) { fit(); return; }
        offX += (w - ow) / 2f;
        offY += (h - oh) / 2f;
        push();
    }

    float docX(float sx) { return (sx - offX) / scale; }
    float docY(float sy) { return (sy - offY) / scale; }

    /** Screen px -> image px. */
    float[] toImg(float sx, float sy) {
        float[] m0 = new float[3], m1 = new float[3];
        ed.doc.docToImg(ed.cropMode, m0, m1);
        float dx = docX(sx), dy = docY(sy);
        return new float[]{m0[0] * dx + m0[1] * dy + m0[2], m1[0] * dx + m1[1] * dy + m1[2]};
    }

    /** Image px -> screen px. */
    float[] toScreen(float ix, float iy) {
        float[] o = new float[2];
        ed.doc.imgToDoc(ed.cropMode, ix, iy, o);
        o[0] = offX + o[0] * scale;
        o[1] = offY + o[1] * scale;
        return o;
    }

    /** A screen-px drag expressed in image px. */
    float[] deltaImg(float sdx, float sdy) {
        double t = Math.toRadians(ed.doc.angle()), c = Math.cos(t), s = Math.sin(t);
        float ddx = sdx / scale, ddy = sdy / scale;
        return new float[]{(float) (c * ddx - s * ddy), (float) (s * ddx + c * ddy)};
    }

    /** A finger is on the canvas (autosave waits for strokes and drags to finish). */
    volatile boolean touching;

    /** Brush radius in image px (doc px = image px). */
    float brushRadImg() { return brushSize / 2f / scale; }

    // ---------------------------------------------------------------- input
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (ed.doc == null) return false;
        int n = e.getPointerCount();
        int am = e.getActionMasked();
        touching = am != MotionEvent.ACTION_UP && am != MotionEvent.ACTION_CANCEL;
        if (am == MotionEvent.ACTION_DOWN) stopZoomAnim();
        // the before/after divider takes a touch that starts on it, ahead of every tool
        if (splitFrac >= 0 && (splitDrag || (am == MotionEvent.ACTION_DOWN && Math.abs(e.getX() - splitFrac * getWidth()) < Ui.dp(22)))) {
            if (am == MotionEvent.ACTION_DOWN) splitDrag = true;
            else if (am == MotionEvent.ACTION_MOVE) {
                splitFrac = Math.max(0.02f, Math.min(0.98f, e.getX() / Math.max(1, getWidth())));
                push();
            } else if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) splitDrag = false;
            return true;
        }
        switch (am) {
            case MotionEvent.ACTION_DOWN:
                multi = false;
                downX = lastX = curX = e.getX();
                downY = lastY = curY = e.getY();
                showCursor = true;
                oneDown(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!multi) {
                    oneEnd(true);
                    multi = true;
                }
                startPinch(e, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                curX = e.getX();
                curY = e.getY();
                if (multi && n >= 2) movePinch(e);
                else if (!multi) oneMove(e.getX(), e.getY());
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (n - 1 >= 2) startPinch(e, e.getActionIndex());
                break;
            case MotionEvent.ACTION_UP:
                if (!multi) {
                    boolean tap = Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) < Ui.dp(10);
                    oneEnd(false);
                    long now = e.getEventTime();
                    if (tap && (tool == NAV || tool == TRANSFORM)) {
                        if (now - lastTapTime < 300) { toggleZoom(e.getX(), e.getY()); lastTapTime = 0; } else lastTapTime = now;
                    }
                }
                multi = false;
                showCursor = false;
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                oneEnd(true);
                multi = false;
                showCursor = false;
                invalidate();
                break;
        }
        return true;
    }

    private void startPinch(MotionEvent e, int skip) {
        int a = -1, b = -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            if (a < 0) a = i; else if (b < 0) b = i;
        }
        if (a < 0 || b < 0) return;
        float dx = e.getX(b) - e.getX(a), dy = e.getY(b) - e.getY(a);
        pinchDist = (float) Math.max(1, Math.sqrt(dx * dx + dy * dy));
        pinchAng = (float) Math.atan2(dy, dx);
        pinchMx = (e.getX(a) + e.getX(b)) / 2;
        pinchMy = (e.getY(a) + e.getY(b)) / 2;
        if (tool == TRANSFORM && skip < 0) ed.beginEdit();
    }

    private void movePinch(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
        float dist = (float) Math.max(1, Math.sqrt(dx * dx + dy * dy));
        float ang = (float) Math.atan2(dy, dx);
        float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
        float k = dist / pinchDist;
        Layer sel = ed.selectedLayer();
        if (tool == TRANSFORM && sel != null && sel.type == Layer.GROUP) {
            float[] pv = toImg(pinchMx, pinchMy);
            transformGroup(sel, k, (float) Math.toDegrees(ang - pinchAng), pv[0], pv[1]);
            moveLayerBy(sel, mx - pinchMx, my - pinchMy);
            ed.changed();
        } else if (tool == TRANSFORM && sel != null && sel.movable()) {
            sel.scale = Math.max(0.01f, Math.min(50f, sel.scale * k));
            sel.rot += (float) Math.toDegrees(ang - pinchAng);
            moveLayerBy(sel, mx - pinchMx, my - pinchMy);
            ed.changed();
        } else {
            float ns = Math.max(0.01f, Math.min(64f, scale * k));
            k = ns / scale;
            offX = pinchMx - (pinchMx - offX) * k + (mx - pinchMx);
            offY = pinchMy - (pinchMy - offY) * k + (my - pinchMy);
            scale = ns;
            fitted = false;
            push();
        }
        pinchDist = dist;
        pinchAng = ang;
        pinchMx = mx;
        pinchMy = my;
        invalidate();
    }

    private void oneDown(float x, float y) {
        switch (tool) {
            case BRUSH: {
                Layer l = ed.selectedLayer();
                if (l == null || l.mask == null) return;
                Bitmap m = ed.beginMaskStroke();
                maskCanvas = new Canvas(m);
                stroking = true;
                strokeErase = erase;
                strokeFlow = flow;
                residual = 0;
                paintDab(x, y);
                ed.maskChanged();
                break;
            }
            case SEL_BRUSH: {
                Bitmap m = ed.beginSelStroke();
                if (m == null) return;
                maskCanvas = new Canvas(m);
                stroking = true;
                strokeErase = ed.selMode == SelOps.SUBTRACT;
                strokeFlow = 1f;
                residual = 0;
                paintDab(x, y);
                ed.selPainted();
                break;
            }
            case SEL_RECT:
            case SEL_ELL:
                selDrag = true;
                break;
            case SEL_LASSO:
                selDrag = true;
                lassoPts.clear();
                lassoPts.add(x);
                lassoPts.add(y);
                break;
            case GRAD_LIN:
            case GRAD_RAD:
                gradActive = ed.selectedLayer() != null && ed.selectedLayer().mask != null;
                break;
            case TRANSFORM:
                if (ed.selectedLayer() != null && ed.selectedLayer().movable()) ed.beginEdit();
                break;
            case FX_POINT: {
                float[] h = fxHandle();
                fxDrag = h != null && (h[0] < 0 ? Math.abs(y - h[1]) < Ui.dp(36)
                        : (h[1] < 0 ? Math.abs(x - h[0]) < Ui.dp(36) : Math.hypot(x - h[0], y - h[1]) < Ui.dp(48)));
                if (fxDrag) ed.fxPointBegin();
                break;
            }
            case CROP:
                cropHandle = hitCrop(x, y);
                cropStart.set(cropRectScreen());
                if (cropHandle >= 0) ed.beginEdit();
                break;
            case HEAL: {
                Retouch.Stroke s = ed.selectedStroke();
                if (s != null) {
                    float[] h = srcHandle(s);
                    if (Math.hypot(x - h[0], y - h[1]) < Math.max(Ui.dp(28), s.rad * scale * 0.8f)) {
                        dragSrc = true;
                        srcStartX = s.offX;
                        srcStartY = s.offY;
                        ed.beginEdit();
                        break;
                    }
                }
                startPath(x, y);
                break;
            }
            case REMOVE:
                startPath(x, y);
                break;
            case SAMPLE:
                loupeAt(x, y);
                break;
            case MASK_SHAPE:
                grabShape(x, y);
                break;
            case MASK_FREEHAND:
                freeDrawing = ed.selectedLayer() != null && ed.selectedLayer().mask != null;
                freePts.clear();
                freePts.add(x);
                freePts.add(y);
                break;
        }
        invalidate();
    }

    // ---------------------------------------------------------------- mask shapes (3.15: MaskGizmo)
    private final MaskGizmo gizmo = new MaskGizmo();
    private final MaskGizmo.Map gizmoMap = new MaskGizmo.Map() {
        @Override public float[] toScreen(float mx, float my) { return maskToScreen(mx, my); }
        @Override public float[] toMask(float sx, float sy) { return CanvasView.this.toMask(sx, sy); }
    };
    private int shapeHandle = -1;
    private boolean shapeMoved;
    private final ArrayList<Float> freePts = new ArrayList<Float>();   // screen px, freehand outline being drawn
    private boolean freeDrawing;

    /** Mask px -> screen px for the selected layer's mask. */
    float[] maskToScreen(float mx, float my) {
        double[] inv = MaskOps.invert(maskMap());
        return toScreen((float) (inv[0] * mx + inv[1] * my + inv[2]), (float) (inv[3] * mx + inv[4] * my + inv[5]));
    }

    /** Where a new shape goes: the middle of the view in mask px, and a size ~45% of the view's short side. */
    float[] maskView() {
        float[] c = toMask(getWidth() / 2f, getHeight() / 2f);
        float k = (float) (MaskOps.scaleOf(maskMap()) / scale);
        return new float[]{c[0], c[1], Math.max(8, Math.min(getWidth(), getHeight()) * 0.45f * k)};
    }

    private void grabShape(float x, float y) {
        shapeMoved = false;
        shapeHandle = gizmo.grab(ed.selShape(), x, y, gizmoMap) ? gizmo.handle : -1;
    }

    private void dragShape(float x, float y) {
        MaskStack.Comp c = ed.selShape();
        if (c == null) return;
        if (!shapeMoved) { ed.beginEdit(); shapeMoved = true; }   // the undo step starts on the first move
        c = ed.selShape();   // beginEdit may have swapped the live document
        if (gizmo.drag(c, x, y, gizmoMap)) ed.shapeChanged();
    }

    /** The selected shape on the photo: Lightroom-style guide lines, ellipses or a box with handles. */
    private void drawShape(Canvas cv) {
        gizmo.draw(cv, ed.selShape(), gizmoMap, getWidth(), getHeight());
    }

    /** Shows the loupe magnifying screen point (x, y), placed above the finger. */
    private void loupeAt(float x, float y) {
        float r = Ui.dp(60);
        float[] c = ViewMath.loupeCentre(x, y, r, Ui.dp(36), getWidth(), getHeight());
        loupeOn = true;
        loupeCx = c[0];
        loupeCy = c[1];
        loupeK = ViewMath.loupeMag(scale);
        ed.engine.loupe = new float[]{c[0], c[1], r, loupeK, x, y};
        ed.requestRender();
    }

    private void startPath(float x, float y) {
        painting = true;
        pathPts.clear();
        pathPts.add(x);
        pathPts.add(y);
    }

    private void oneMove(float x, float y) {
        switch (tool) {
            case NAV:
                pan(x, y);
                break;
            case SAMPLE:
                loupeAt(x, y);   // one finger aims the loupe; two fingers pan and zoom
                break;
            case MASK_SHAPE:
                if (shapeHandle < 0) pan(x, y); else dragShape(x, y);
                break;
            case MASK_FREEHAND: {
                if (!freeDrawing) { pan(x, y); break; }
                float px = freePts.get(freePts.size() - 2), py = freePts.get(freePts.size() - 1);
                if (Math.hypot(x - px, y - py) >= 2) { freePts.add(x); freePts.add(y); }
                break;
            }
            case FX_POINT:
                if (!fxDrag) { pan(x, y); break; }
                float dw = ed.doc.docW(ed.cropMode), dh = ed.doc.docH(ed.cropMode);
                ed.fxPointMoved(Math.max(0, Math.min(1, docX(x) / dw)), Math.max(0, Math.min(1, docY(y) / dh)));
                break;
            case BRUSH:
            case SEL_BRUSH:
                if (!stroking) { pan(x, y); return; }
                strokeTo(lastX, lastY, x, y);
                if (tool == BRUSH) ed.maskChanged(); else ed.selPainted();
                break;
            case SEL_RECT:
            case SEL_ELL:
                break;   // the shape follows curX/curY
            case SEL_LASSO: {
                float px = lassoPts.get(lassoPts.size() - 2), py = lassoPts.get(lassoPts.size() - 1);
                if (Math.hypot(x - px, y - py) >= 2) { lassoPts.add(x); lassoPts.add(y); }
                break;
            }
            case SEL_POLY:
            case SEL_WAND:
                pan(x, y);
                break;
            case TRANSFORM: {
                Layer l = ed.selectedLayer();
                if (l != null && l.movable()) {
                    moveLayerBy(l, x - lastX, y - lastY);
                    ed.changed();
                } else pan(x, y);
                break;
            }
            case CROP:
                if (cropHandle < 0) pan(x, y);
                else dragCrop(x - downX, y - downY);
                break;
            case HEAL:
            case REMOVE:
                if (dragSrc) {
                    float[] dl = deltaImg(x - downX, y - downY);
                    ed.setStrokeOffset(srcStartX + dl[0], srcStartY + dl[1]);
                } else if (painting) {
                    float px = pathPts.get(pathPts.size() - 2), py = pathPts.get(pathPts.size() - 1);
                    if (Math.hypot(x - px, y - py) >= 3) { pathPts.add(x); pathPts.add(y); }
                }
                break;
        }
        invalidate();
    }

    private void pan(float x, float y) {
        offX += x - lastX;
        fitted = false;
        offY += y - lastY;
        push();
    }

    private void oneEnd(boolean cancelled) {
        if (stroking) {
            stroking = false;
            maskCanvas = null;
            if (tool == SEL_BRUSH) ed.selStrokeDone(); else ed.maskChanged();
        }
        boolean tapped = Math.abs(curX - downX) + Math.abs(curY - downY) < Ui.dp(10);
        if (selDrag) {
            selDrag = false;
            if (!cancelled) {
                if ((tool == SEL_RECT || tool == SEL_ELL) && !tapped) ed.selShape(shapeImg(downX, downY, curX, curY, tool == SEL_ELL));
                else if (tool == SEL_LASSO && lassoPts.size() >= 6) {
                    float[] pts = new float[lassoPts.size()];
                    for (int i = 0; i < pts.length / 2; i++) {
                        float[] ip = toImg(lassoPts.get(i * 2), lassoPts.get(i * 2 + 1));
                        pts[i * 2] = ip[0];
                        pts[i * 2 + 1] = ip[1];
                    }
                    ed.selShape(pts);
                }
            }
            lassoPts.clear();
        }
        if (!cancelled && tapped && tool == SEL_POLY) {
            // tap adds a corner; tapping near the first corner closes the shape
            if (polyImg.size() >= 6) {
                float[] f = toScreen(polyImg.get(0), polyImg.get(1));
                if (Math.hypot(downX - f[0], downY - f[1]) < Ui.dp(26)) { closePoly(); return; }
            }
            float[] ip = toImg(downX, downY);
            polyImg.add(ip[0]);
            polyImg.add(ip[1]);
            ed.polyChanged();
        }
        if (!cancelled && tapped && tool == SEL_WAND) {
            float[] ip = toImg(downX, downY);
            ed.selWand(ip[0], ip[1]);
        }
        if (gradActive) {
            gradActive = false;
            float d = Math.abs(curX - downX) + Math.abs(curY - downY);
            if (!cancelled && d > Ui.dp(12)) applyGradient();
        }
        if (tool == CROP && cropHandle >= 0) { cropHandle = -1; ed.cropChanged(); }
        if (freeDrawing) {   // 3.15: the outline closes itself and becomes a shape
            freeDrawing = false;
            if (!cancelled && freePts.size() >= 16) {
                float[] pts = new float[freePts.size()];
                for (int i = 0; i + 1 < pts.length; i += 2) {
                    float[] m = toMask(freePts.get(i), freePts.get(i + 1));
                    pts[i] = m[0]; pts[i + 1] = m[1];
                }
                float tol = (float) (1.5 * MaskOps.scaleOf(maskMap()) / scale);   // ~1.5 screen px
                ed.addFreehand(pts, Math.max(0.25f, tol));
            }
            freePts.clear();
        }
        shapeHandle = -1;
        gizmo.release();
        if (dragSrc) dragSrc = false;
        if (fxDrag) { fxDrag = false; ed.fxPointDone(); }
        if (painting) {
            painting = false;
            if (!cancelled) {
                boolean tap = Math.abs(curX - downX) + Math.abs(curY - downY) < Ui.dp(10);
                float[] pts = new float[tap ? 2 : pathPts.size()];
                if (tap) {
                    float[] ip = toImg(downX, downY);
                    pts[0] = ip[0];
                    pts[1] = ip[1];
                } else {
                    for (int i = 0; i < pathPts.size() / 2; i++) {
                        float[] ip = toImg(pathPts.get(i * 2), pathPts.get(i * 2 + 1));
                        pts[i * 2] = ip[0];
                        pts[i * 2 + 1] = ip[1];
                    }
                }
                if (tool == HEAL) {
                    if (tap) ed.retouchTap(pts[0], pts[1], brushRadImg());
                    else ed.retouchStroke(pts, brushRadImg());
                } else if (tool == REMOVE) ed.addRemoveStroke(pts, brushRadImg());
            }
            pathPts.clear();
        }
        if (loupeOn) {
            loupeOn = false;
            ed.engine.loupe = null;
            ed.requestRender();
            // lifting picks the point under the loupe's centre (a second finger cancels)
            if (tool == SAMPLE && !cancelled) {
                float[] ip = toImg(curX, curY);
                ed.sampleAt(ip[0], ip[1]);
            }
        }
    }

    private void moveLayerBy(Layer l, float sdx, float sdy) {
        float[] dl = deltaImg(sdx, sdy);
        if (l.type == Layer.GROUP) {
            for (Layer k : ed.doc.children(l.id)) if (k.movable()) { k.cx += dl[0]; k.cy += dl[1]; }
            return;
        }
        l.cx += dl[0];
        l.cy += dl[1];
    }

    /** Scale k and rotate da degrees about image point (px, py): every movable layer of the group. */
    private void transformGroup(Layer g, float k, float da, float px, float py) {
        double t = Math.toRadians(da), co = Math.cos(t), si = Math.sin(t);
        for (Layer c : ed.doc.children(g.id)) {
            if (!c.movable()) continue;
            double vx = c.cx - px, vy = c.cy - py;
            c.cx = px + (float) ((vx * co - vy * si) * k);
            c.cy = py + (float) ((vx * si + vy * co) * k);
            c.scale = Math.max(0.01f, Math.min(50f, c.scale * k));
            c.rot += da;
        }
    }

    // ---------------------------------------------------------------- selection shapes
    /** Finishes the polygon lasso (needs 3+ corners). */
    void closePoly() {
        if (polyImg.size() < 6) return;
        float[] pts = new float[polyImg.size()];
        for (int i = 0; i < pts.length; i++) pts[i] = polyImg.get(i);
        polyImg.clear();
        ed.polyChanged();
        ed.selShape(pts);
    }

    /**
     * A rectangle or ellipse dragged on screen, as a polygon in image px. Built on screen and
     * mapped point by point, so it stays a screen-aligned rectangle / true ellipse whatever the
     * crop rotation.
     */
    private float[] shapeImg(float x0, float y0, float x1, float y1, boolean ellipse) {
        float l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1);
        float[] scr;
        if (!ellipse) scr = new float[]{l, t, r, t, r, b, l, b};
        else {
            int n = 96;
            scr = new float[n * 2];
            float cx = (l + r) / 2, cy = (t + b) / 2, rx = (r - l) / 2, ry = (b - t) / 2;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n;
                scr[i * 2] = cx + (float) (Math.cos(a) * rx);
                scr[i * 2 + 1] = cy + (float) (Math.sin(a) * ry);
            }
        }
        float[] img = new float[scr.length];
        for (int i = 0; i < scr.length / 2; i++) {
            float[] ip = toImg(scr[i * 2], scr[i * 2 + 1]);
            img[i * 2] = ip[0];
            img[i * 2 + 1] = ip[1];
        }
        return img;
    }

    private void drawSelection(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1.5f));
        if (selDrag && (tool == SEL_RECT || tool == SEL_ELL)) {
            float l = Math.min(downX, curX), r = Math.max(downX, curX), t = Math.min(downY, curY), b = Math.max(downY, curY);
            for (int k = 0; k < 2; k++) {
                p.setColor(k == 0 ? 0xcc000000 : 0xffffffff);
                p.setPathEffect(k == 0 ? null : new android.graphics.DashPathEffect(new float[]{Ui.dp(5), Ui.dp(4)}, 0));
                if (tool == SEL_RECT) c.drawRect(l, t, r, b, p); else c.drawOval(l, t, r, b, p);
            }
        }
        if (selDrag && tool == SEL_LASSO && lassoPts.size() >= 4) {
            android.graphics.Path pa = new android.graphics.Path();
            pa.moveTo(lassoPts.get(0), lassoPts.get(1));
            for (int i = 2; i + 1 < lassoPts.size(); i += 2) pa.lineTo(lassoPts.get(i), lassoPts.get(i + 1));
            for (int k = 0; k < 2; k++) {
                p.setColor(k == 0 ? 0xcc000000 : 0xffffffff);
                p.setPathEffect(k == 0 ? null : new android.graphics.DashPathEffect(new float[]{Ui.dp(5), Ui.dp(4)}, 0));
                c.drawPath(pa, p);
            }
        }
        if (tool == SEL_POLY && polyImg.size() >= 2) {
            android.graphics.Path pa = new android.graphics.Path();
            for (int i = 0; i + 1 < polyImg.size(); i += 2) {
                float[] s = toScreen(polyImg.get(i), polyImg.get(i + 1));
                if (i == 0) pa.moveTo(s[0], s[1]); else pa.lineTo(s[0], s[1]);
            }
            for (int k = 0; k < 2; k++) {
                p.setColor(k == 0 ? 0xcc000000 : 0xffffffff);
                p.setPathEffect(k == 0 ? null : new android.graphics.DashPathEffect(new float[]{Ui.dp(5), Ui.dp(4)}, 0));
                c.drawPath(pa, p);
            }
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i + 1 < polyImg.size(); i += 2) {
                float[] s = toScreen(polyImg.get(i), polyImg.get(i + 1));
                p.setColor(i == 0 ? Ui.ACCENT : 0xffffffff);
                c.drawCircle(s[0], s[1], Ui.dp(i == 0 && polyImg.size() >= 6 ? 7 : 4), p);
            }
        }
        p.setPathEffect(null);
        p.setStyle(Paint.Style.STROKE);
    }

    // ---------------------------------------------------------------- mask brush
    /**
     * Image px -> px of the bitmap being painted: the selected layer's mask (canvas or linked
     * to the layer) or, for the selection brush, the canvas-space selection.
     */
    private double[] maskMap() {
        Doc d = ed.doc;
        Layer l = ed.selectedLayer();
        if (tool != SEL_BRUSH && l != null && l.mask != null) return l.maskAffine(d.imgW, d.imgH);
        return new double[]{d.maskW() / (double) d.imgW, 0, 0, 0, d.maskH() / (double) d.imgH, 0};
    }

    private float[] toMask(float sx, float sy) {
        float[] ip = toImg(sx, sy);
        double[] m = maskMap();
        return new float[]{(float) (m[0] * ip[0] + m[1] * ip[1] + m[2]), (float) (m[3] * ip[0] + m[4] * ip[1] + m[5])};
    }

    private float maskRadius() { return Math.max(0.5f, (float) (brushSize / 2f / scale * MaskOps.scaleOf(maskMap()))); }

    private void strokeTo(float x0, float y0, float x1, float y1) {
        float[] a = toMask(x0, y0), b = toMask(x1, y1);
        float r = maskRadius();
        float spacing = Math.max(0.5f, r * 0.12f);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float t = spacing - residual;
        while (t <= len) {
            float f = len == 0 ? 0 : t / len;
            dabAt(a[0] + dx * f, a[1] + dy * f, r);
            t += spacing;
        }
        residual = len - (t - spacing);
    }

    private void paintDab(float sx, float sy) {
        float[] m = toMask(sx, sy);
        dabAt(m[0], m[1], maskRadius());
    }

    private void dabAt(float x, float y, float r) {
        if (maskCanvas == null) return;
        float h = Math.max(0.01f, Math.min(0.99f, hardness));
        dab.setShader(new RadialGradient(x, y, r, new int[]{0xff000000, 0xff000000, 0x00000000},
                new float[]{0, h, 1}, Shader.TileMode.CLAMP));
        dab.setAlpha(Math.max(1, Math.round(strokeFlow * strokeFlow * 255)));
        dab.setXfermode(strokeErase ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
        maskCanvas.drawCircle(x, y, r, dab);
    }

    /** A drawn gradient becomes an editable mask shape (3.6; it used to be painted into the raster). */
    private void applyGradient() {
        float[] a = toMask(downX, downY), b = toMask(curX, curY);
        ed.addShape(tool == GRAD_LIN ? MaskStack.LINEAR : MaskStack.RADIAL, a[0], a[1], b[0], b[1]);
    }

    // ---------------------------------------------------------------- crop
    RectF cropRectScreen() {
        Doc d = ed.doc;
        float[] c = new float[2];
        d.imgToDoc(true, d.cropCx, d.cropCy, c);
        float hw = d.cropW / 2, hh = d.cropH / 2;
        return new RectF(offX + (c[0] - hw) * scale, offY + (c[1] - hh) * scale,
                offX + (c[0] + hw) * scale, offY + (c[1] + hh) * scale);
    }

    /** 0..3 corners (tl, tr, br, bl), 4..7 edges (t, r, b, l), 8 inside, -1 none. */
    private int hitCrop(float x, float y) {
        RectF r = cropRectScreen();
        float t = Ui.dp(30);
        float[][] corners = {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
        for (int i = 0; i < 4; i++)
            if (Math.abs(x - corners[i][0]) < t && Math.abs(y - corners[i][1]) < t) return i;
        if (Math.abs(y - r.top) < t && x > r.left && x < r.right) return 4;
        if (Math.abs(x - r.right) < t && y > r.top && y < r.bottom) return 5;
        if (Math.abs(y - r.bottom) < t && x > r.left && x < r.right) return 6;
        if (Math.abs(x - r.left) < t && y > r.top && y < r.bottom) return 7;
        if (r.contains(x, y)) return 8;
        return -1;
    }

    private void dragCrop(float dx, float dy) {
        RectF r = new RectF(cropStart);
        int h = cropHandle;
        if (h == 8) r.offset(dx, dy);
        else {
            if (h == 0 || h == 3 || h == 7) r.left += dx;
            if (h == 1 || h == 2 || h == 5) r.right += dx;
            if (h == 0 || h == 1 || h == 4) r.top += dy;
            if (h == 2 || h == 3 || h == 6) r.bottom += dy;
            float minS = Ui.dp(40);
            if (r.width() < minS) { if (h == 0 || h == 3 || h == 7) r.left = r.right - minS; else r.right = r.left + minS; }
            if (r.height() < minS) { if (h == 0 || h == 1 || h == 4) r.top = r.bottom - minS; else r.bottom = r.top + minS; }
            if (cropAspect > 0) {
                if (h <= 3) {
                    float w = r.width(), hh = r.height();
                    if (w / hh > cropAspect) w = hh * cropAspect; else hh = w / cropAspect;
                    if (h == 0) { r.left = r.right - w; r.top = r.bottom - hh; }
                    if (h == 1) { r.right = r.left + w; r.top = r.bottom - hh; }
                    if (h == 2) { r.right = r.left + w; r.bottom = r.top + hh; }
                    if (h == 3) { r.left = r.right - w; r.bottom = r.top + hh; }
                } else if (h == 4 || h == 6) {
                    float w = r.height() * cropAspect, cx = r.centerX();
                    r.left = cx - w / 2; r.right = cx + w / 2;
                } else {
                    float hh = r.width() / cropAspect, cy = r.centerY();
                    r.top = cy - hh / 2; r.bottom = cy + hh / 2;
                }
            }
        }
        setCropFromScreen(r);
    }

    void setCropFromScreen(RectF r) {
        Doc d = ed.doc;
        float[] m0 = new float[3], m1 = new float[3];
        d.docToImg(true, m0, m1);
        float cdx = docX(r.centerX()), cdy = docY(r.centerY());
        d.cropCx = m0[0] * cdx + m0[1] * cdy + m0[2];
        d.cropCy = m1[0] * cdx + m1[1] * cdy + m1[2];
        d.cropW = Math.max(8, r.width() / scale);
        d.cropH = Math.max(8, r.height() / scale);
        ed.changed();
    }

    // ---------------------------------------------------------------- overlays
    private float[] srcHandle(Retouch.Stroke s) {
        float[] c = s.centroid();
        return toScreen(c[0] + s.offX, c[1] + s.offY);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (ed.doc == null) return;
        if (tool == CROP) drawCrop(c);
        if (tool == TRANSFORM) drawTransform(c);
        if (tool == FX_POINT) drawFxHandle(c);
        if (ed.tab == Editor.T_RETOUCH) drawRetouch(c);
        if (isSelTool(tool)) drawSelection(c);
        if ((tool == BRUSH || tool == SEL_BRUSH || tool == HEAL || tool == REMOVE) && showCursor && !multi && !dragSrc) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1.5f));
            p.setColor(0xaa000000);
            c.drawCircle(curX, curY, brushSize / 2 + 1, p);
            p.setColor(0xddffffff);
            c.drawCircle(curX, curY, brushSize / 2, p);
            if (tool == BRUSH || tool == SEL_BRUSH) {
                p.setColor(0x88ffffff);
                c.drawCircle(curX, curY, brushSize / 2 * hardness, p);
            }
        }
        if (tool == MASK_SHAPE) drawShape(c);
        if (tool == MASK_FREEHAND && freeDrawing) gizmo.drawLasso(c, freePts);
        if (splitFrac >= 0) drawSplit(c);
        if (loupeOn) drawLoupe(c);
        if (gradActive) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(2));
            p.setColor(Ui.ACCENT);
            if (tool == GRAD_LIN) {
                c.drawLine(downX, downY, curX, curY, p);
                float dx = curX - downX, dy = curY - downY, len = (float) Math.max(1, Math.hypot(dx, dy));
                float nx = -dy / len * Ui.dp(400), ny = dx / len * Ui.dp(400);
                p.setColor(0xccffffff);
                c.drawLine(downX - nx, downY - ny, downX + nx, downY + ny, p);
                p.setColor(0x66ffffff);
                c.drawLine(curX - nx, curY - ny, curX + nx, curY + ny, p);
            } else {
                float r = (float) Math.hypot(curX - downX, curY - downY);
                c.drawCircle(downX, downY, r, p);
                p.setColor(0xccffffff);
                c.drawCircle(downX, downY, r * 0.35f, p);
            }
        }
    }

    /** The before/after divider: a line with a round grip, labels on each side. */
    private void drawSplit(Canvas c) {
        float x = splitFrac * getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66000000);
        c.drawRect(x - Ui.dp(1.5f), 0, x + Ui.dp(1.5f), h, p);
        p.setColor(0xffffffff);
        c.drawRect(x - Ui.dp(0.75f), 0, x + Ui.dp(0.75f), h, p);
        float ky = h / 2, kr = Ui.dp(16), a = Ui.dp(5);
        p.setColor(0xe0101114);
        c.drawCircle(x, ky, kr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1.5f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xffffffff);
        c.drawCircle(x, ky, kr, p);
        path.reset();
        path.moveTo(x - Ui.dp(4), ky - a); path.lineTo(x - Ui.dp(9), ky); path.lineTo(x - Ui.dp(4), ky + a);
        path.moveTo(x + Ui.dp(4), ky - a); path.lineTo(x + Ui.dp(9), ky); path.lineTo(x + Ui.dp(4), ky + a);
        c.drawPath(path, p);
        pill(c, "Before", x - Ui.dp(8), true);
        pill(c, "After", x + Ui.dp(8), false);
    }

    /** A small label at the top of the canvas, ending (left) or starting (right) at x; skipped if it doesn't fit. */
    private void pill(Canvas c, String s, float x, boolean left) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(Ui.dp(12));
        float tw = p.measureText(s), pw = tw + Ui.dp(16), y0 = Ui.dp(10), ph = Ui.dp(24);
        float x0 = left ? x - pw : x;
        if (x0 < Ui.dp(4) || x0 + pw > getWidth() - Ui.dp(4)) return;
        p.setColor(0xcc101114);
        c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, ph / 2, ph / 2, p);
        p.setColor(0xffffffff);
        c.drawText(s, x0 + Ui.dp(8), y0 + ph / 2 - (p.ascent() + p.descent()) / 2, p);
    }

    /** Ring around the loupe and the 5 x 5 preview px the pick averages, at the loupe's magnification. */
    private void drawLoupe(Canvas c) {
        float r = Ui.dp(60);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(4));
        p.setColor(0x88000000);
        c.drawCircle(loupeCx, loupeCy, r + Ui.dp(1), p);
        p.setStrokeWidth(Ui.dp(2));
        p.setColor(0xffffffff);
        c.drawCircle(loupeCx, loupeCy, r, p);
        Doc d = ed.doc;
        float ps = Math.min(1f, Engine.PREV / Math.max(d.docW(false), d.docH(false)));
        float half = Math.max(Ui.dp(3), 2.5f / ps * scale * loupeK);
        p.setStrokeWidth(Ui.dp(2.5f));
        p.setColor(0xaa000000);
        c.drawRect(loupeCx - half, loupeCy - half, loupeCx + half, loupeCy + half, p);
        p.setStrokeWidth(Ui.dp(1.2f));
        p.setColor(0xffffffff);
        c.drawRect(loupeCx - half, loupeCy - half, loupeCx + half, loupeCy + half, p);
    }

    /** Screen position of the selected effect's point: {x, y}; -1 for an axis the effect doesn't use. */
    private float[] fxHandle() {
        float[] f = ed.fxPoint();
        if (f == null) return null;
        float dw = ed.doc.docW(ed.cropMode), dh = ed.doc.docH(ed.cropMode);
        return new float[]{f[0] < 0 ? -1 : offX + f[0] * dw * scale, f[1] < 0 ? -1 : offY + f[1] * dh * scale};
    }

    private void drawFxHandle(Canvas c) {
        float[] h = fxHandle();
        if (h == null) return;
        float dw = ed.doc.docW(ed.cropMode) * scale, dh = ed.doc.docH(ed.cropMode) * scale;
        p.setStyle(Paint.Style.STROKE);
        for (int pass = 0; pass < 2; pass++) {
            p.setStrokeWidth(Ui.dp(pass == 0 ? 4 : 2));
            p.setColor(pass == 0 ? 0x88000000 : (fxDrag ? Ui.ACCENT : 0xffffffff));
            if (h[0] < 0) c.drawLine(offX, h[1], offX + dw, h[1], p);            // a horizon / horizontal line
            else if (h[1] < 0) c.drawLine(h[0], offY, h[0], offY + dh, p);
            else {
                c.drawCircle(h[0], h[1], Ui.dp(16), p);
                c.drawLine(h[0] - Ui.dp(26), h[1], h[0] - Ui.dp(8), h[1], p);
                c.drawLine(h[0] + Ui.dp(8), h[1], h[0] + Ui.dp(26), h[1], p);
                c.drawLine(h[0], h[1] - Ui.dp(26), h[0], h[1] - Ui.dp(8), p);
                c.drawLine(h[0], h[1] + Ui.dp(8), h[0], h[1] + Ui.dp(26), p);
            }
        }
        float[] guide = ed.fxGuide();   // mirror: draw the axis lines through the point
        if (guide != null) {
            p.setStrokeWidth(Ui.dp(1));
            p.setColor(0x99ffffff);
            if (guide[0] > 0 && h[0] >= 0) c.drawLine(h[0], offY, h[0], offY + dh, p);
            if (guide[1] > 0 && h[1] >= 0) c.drawLine(offX, h[1], offX + dw, h[1], p);
        }
    }

    private void strokePath(float[] pts, float ox, float oy) {
        path.reset();
        for (int i = 0; i < pts.length / 2; i++) {
            float[] s = toScreen(pts[i * 2] + ox, pts[i * 2 + 1] + oy);
            if (i == 0) path.moveTo(s[0], s[1]); else path.lineTo(s[0], s[1]);
        }
        if (pts.length == 2) path.lineTo(toScreen(pts[0] + ox, pts[1] + oy)[0] + 0.01f, toScreen(pts[0] + ox, pts[1] + oy)[1]);
    }

    private void drawRetouch(Canvas c) {
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        // pending remove (fill) strokes
        for (float[] rs : ed.removeStrokes) {
            float[] pts = new float[rs.length - 1];
            System.arraycopy(rs, 1, pts, 0, pts.length);
            strokePath(pts, 0, 0);
            p.setStrokeWidth(rs[0] * 2 * scale);
            p.setColor(0x77ff3b30);
            c.drawPath(path, p);
        }
        Layer rl = ed.activeRetouch();
        Retouch.Stroke sel = ed.selectedStroke();
        if (rl != null) {
            for (Retouch.Stroke s : rl.strokes) {
                if (s == sel) continue;
                strokePath(s.pts, 0, 0);
                p.setStrokeWidth(Math.max(Ui.dp(1), s.rad * 2 * scale));
                p.setColor(0x22ffffff);
                c.drawPath(path, p);
            }
        }
        if (sel != null) {
            float w = Math.max(Ui.dp(2), sel.rad * 2 * scale);
            strokePath(sel.pts, sel.offX, sel.offY);
            p.setStrokeWidth(w);
            p.setColor(0x44e9a23b);
            c.drawPath(path, p);
            strokePath(sel.pts, 0, 0);
            p.setColor(0x55ffffff);
            c.drawPath(path, p);
            float[] a = sel.centroid();
            float[] da = toScreen(a[0], a[1]), sa = srcHandle(sel);
            p.setStrokeWidth(Ui.dp(1.5f));
            p.setColor(0xddffffff);
            c.drawLine(da[0], da[1], sa[0], sa[1], p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.ACCENT);
            c.drawCircle(sa[0], sa[1], Ui.dp(9), p);
            p.setColor(0xff111111);
            c.drawCircle(sa[0], sa[1], Ui.dp(4), p);
            p.setStyle(Paint.Style.STROKE);
        }
        if (painting && pathPts.size() >= 2) {
            path.reset();
            path.moveTo(pathPts.get(0), pathPts.get(1));
            for (int i = 1; i < pathPts.size() / 2; i++) path.lineTo(pathPts.get(i * 2), pathPts.get(i * 2 + 1));
            if (pathPts.size() == 2) path.lineTo(pathPts.get(0) + 0.01f, pathPts.get(1));
            p.setStrokeWidth(brushSize);
            p.setColor(tool == REMOVE ? 0x77ff3b30 : 0x55ffffff);
            c.drawPath(path, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawCrop(Canvas c) {
        RectF r = cropRectScreen();
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        path.addRect(r, Path.Direction.CW);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xb0000000);
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1));
        p.setColor(0x66ffffff);
        for (int i = 1; i < 3; i++) {
            c.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, p);
            c.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, p);
        }
        p.setColor(0xffffffff);
        p.setStrokeWidth(Ui.dp(1.5f));
        c.drawRect(r, p);
        p.setStrokeWidth(Ui.dp(4));
        float L = Ui.dp(18);
        float[][] k = {{r.left, r.top, 1, 1}, {r.right, r.top, -1, 1}, {r.right, r.bottom, -1, -1}, {r.left, r.bottom, 1, -1}};
        for (float[] q : k) {
            c.drawLine(q[0], q[1], q[0] + L * q[2], q[1], p);
            c.drawLine(q[0], q[1], q[0], q[1] + L * q[3], p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(Ui.dp(12));
        p.setColor(0xccffffff);
        c.drawText(Math.round(ed.doc.cropW) + " × " + Math.round(ed.doc.cropH), r.left + Ui.dp(6), r.bottom - Ui.dp(8), p);
    }

    private void drawTransform(Canvas c) {
        Layer sel = ed.selectedLayer();
        if (sel == null || !sel.movable()) return;
        if (sel.type == Layer.GROUP) {   // outline every layer the group moves
            for (Layer k : ed.doc.children(sel.id)) if (k.movable() && k.srcW > 0) drawTransformOf(c, k);
            return;
        }
        drawTransformOf(c, sel);
    }

    private void drawTransformOf(Canvas c, Layer l) {
        float[][] corner = {{0, 0}, {l.srcW, 0}, {l.srcW, l.srcH}, {0, l.srcH}};
        path.reset();
        float[] o = new float[2];
        for (int i = 0; i < 4; i++) {
            l.layerToImg(corner[i][0], corner[i][1], o);
            float[] s = toScreen(o[0], o[1]);
            if (i == 0) path.moveTo(s[0], s[1]); else path.lineTo(s[0], s[1]);
        }
        path.close();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1.5f));
        p.setColor(Ui.ACCENT);
        c.drawPath(path, p);
    }
}
