package com.christopher.strata;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The editing screen: top bar, GL canvas with tool overlay, tool panel and tab bar. */
final class Editor implements Engine.Host, Workspace {
    static final int T_LAYERS = 0, T_EDIT = 1, T_RETOUCH = 2, T_MASK = 3, T_CROP = 4, T_FX = 5, T_SELECT = 6;
    static final String[] MODES = {"Normal", "Darken", "Multiply", "Color Burn", "Linear Burn", "Lighten",
            "Screen", "Color Dodge", "Add (Linear Dodge)", "Overlay", "Soft Light", "Hard Light", "Vivid Light",
            "Linear Light", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity"};
    static final String[] BANDS = {"Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"};
    static final int[] BAND_COLORS = {0xffe8463c, 0xfff08c2c, 0xfff2d43a, 0xff52c25a, 0xff3fc9c4, 0xff3f7fe8, 0xff8a52e0, 0xffe04fb4};
    static final long HISTORY_BYTES = 320L << 20;

    final MainActivity act;
    final File dir;
    final Engine engine;
    final SourceCache sources;
    Doc doc;
    final ArrayList<Doc> undo = new ArrayList<Doc>(), redo = new ArrayList<Doc>();
    int selectedId = -1;
    boolean cropMode;
    int tab = T_LAYERS;
    boolean moveTool;
    /** Mask mode overlay: 0 off, 1 the painted mask, 2 the area the layer actually affects (mask x range x clip). */
    int maskView = 1;
    int curveChannel, levelsChannel, hslBand;
    int[] hist;
    private int rsVer;
    /** Bumped by every content edit (beginEdit); viewChanged() falls back to changed() if one wasn't rendered. */
    private int editGen, renderedGen;
    boolean dirty, exporting, closed;

    // retouch state
    int selStroke = -1;
    int retouchTool = CanvasView.HEAL;
    boolean newHeal = true;
    float retouchFeather = 0.35f;
    final ArrayList<float[]> removeStrokes = new ArrayList<float[]>();   // {rad, x0, y0, x1, y1, ...} img px
    // mask state
    private int maskTool = CanvasView.BRUSH, maskToolBeforeSample = CanvasView.BRUSH;
    private float refineRadius = 12;
    private boolean busy;

    final LinearLayout root;
    final GLSurfaceView gl;
    final CanvasView cv;
    private final ScrollView panelScroll;
    private final LinearLayout panel;
    private final LinearLayout tabs;
    private final TextView status;
    private View undoBtn, redoBtn;
    final AdjPanels adj;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService detailExec = Executors.newSingleThreadExecutor();
    private volatile int detailGen;

    Editor(MainActivity a, File dir, Doc d) {
        act = a;
        this.dir = dir;
        doc = d;
        sources = new SourceCache(dir);
        selectedId = d.layers.isEmpty() ? -1 : d.layers.get(d.layers.size() - 1).id;
        engine = new Engine(a, this);
        engine.sourceCache = sources;

        root = Ui.col(a);
        root.setBackgroundColor(Ui.BG);
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, Ui.dp(52)));

        FrameLayout canvasFrame = new FrameLayout(a);
        gl = new GLSurfaceView(a);
        gl.setEGLContextClientVersion(3);
        gl.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        gl.setPreserveEGLContextOnPause(true);
        engine.gl = new Engine.Redraw() {
            @Override public void postDelayedRender() {
                gl.postDelayed(new Runnable() { @Override public void run() { gl.requestRender(); } }, 140);
            }
        };
        gl.setRenderer(engine);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        MaskRaster.onReady = new Runnable() {   // 3.15: a text/shape mask texture is ready
            @Override public void run() { engine.shapesReady = true; gl.requestRender(); }
        };
        canvasFrame.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        cv = new CanvasView(a, this);
        canvasFrame.addView(cv, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(a, "", 13, Ui.TEXT);
        status.setBackground(Ui.round(0xe0101114, 10, Ui.LINE));
        status.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        status.setVisibility(View.GONE);
        canvasFrame.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // zoom readout: tap = Fit <-> 100 %, the same as double-tapping the photo
        zoomChip = Ui.text(a, "", 12, Ui.TEXT);
        zoomChip.setBackground(Ui.round(0xcc101114, 14, Ui.LINE));
        zoomChip.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
        zoomChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cv.toggleZoom(cv.getWidth() / 2f, cv.getHeight() / 2f); }
        });
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        zl.leftMargin = zl.bottomMargin = Ui.dp(8);
        canvasFrame.addView(zoomChip, zl);
        root.addView(canvasFrame, Ui.lp(-1, 0, 1f));

        View sep = new View(a);
        sep.setBackgroundColor(Ui.LINE);
        root.addView(sep, new LinearLayout.LayoutParams(-1, 1));
        root.addView(grip(), new LinearLayout.LayoutParams(-1, Ui.dp(22)));
        panelScroll = new ScrollView(a);
        panelScroll.setBackgroundColor(Ui.PANEL);
        panel = Ui.col(a);
        panel.setPadding(0, 0, 0, Ui.dp(16));
        panelScroll.addView(panel);
        try { panelSize = Math.max(0, Math.min(2, a.getSharedPreferences("ui", 0).getInt("panelSize", 1))); } catch (Exception ignored) {}
        root.addView(panelScroll, new LinearLayout.LayoutParams(-1, panelHeight(panelSize)));
        tabs = Ui.row(a);
        tabs.setBackgroundColor(Ui.BG);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, Ui.dp(52)));

        adj = new AdjPanels(new AdjPanels.Host() {
            @Override public android.app.Activity act() { return act; }
            @Override public Layer layer() { return adjLayer(); }
            @Override public void beginEdit() { Editor.this.beginEdit(); materializeBase(); }
            @Override public boolean canPickNeutral() { return true; }
            @Override public boolean canShowClipping() { return true; }
            @Override public boolean clipVis() { return clipVis; }
            @Override public void setClipVis(boolean on) { clipVis = on; engine.clipVis = on; gl.requestRender(); }
            @Override public void pickNeutral() { Editor.this.pickNeutral(); }
            @Override public void changed() { Editor.this.changed(); }
            @Override public void rebuild() { rebuildPanel(); }
            @Override public void pickLut() { act.pickLut(); }
            @Override public boolean canMask() { return pendingBase == null || selectedId != pendingBaseFor; }
            @Override public void mask(int how) { maskSelected(how); }
        });
        setTab(T_LAYERS);
        changed();
        dirty = false;
    }

    // ================================================================ state plumbing
    Layer selectedLayer() { return doc.byId(selectedId); }

    void requestRender() { gl.requestRender(); }

    /** A content edit: re-render, mark the project dirty, schedule autosave and the detail view. */
    void changed() {
        doc.syncRetouch();   // retouch strokes follow their photo when it moved
        renderedGen = editGen;
        Engine.RS s = new Engine.RS();
        s.ver = ++rsVer;
        s.doc = doc.copy();
        s.cropMode = cropMode;
        overlays(s);
        engine.rs = s;
        dirty = true;
        handler.removeCallbacks(autosaveRun);
        handler.postDelayed(autosaveRun, 4000);
        gl.requestRender();
        cv.invalidate();
        updateUndoButtons();
        scheduleDetail();
    }

    private void overlays(Engine.RS s) {
        Layer l = selectedLayer();
        boolean mm = tab == T_MASK && l != null;
        s.maskVisId = mm && maskView == 1 && l.mask != null ? l.id : -1;
        s.effVisId = mm && maskView == 2 && l.type != Layer.RETOUCH ? l.id : -1;
        s.selVis = tab == T_SELECT ? sel : null;
        s.selVer = selVer;
    }

    /**
     * Navigation only (tab, selected layer, overlay): no new document copy, no dirty flag, no
     * autosave. The image recomposites only if crop mode changed (it shows the whole rotated
     * image). An edit that skipped changed() still gets a full one.
     */
    void viewChanged() {
        Engine.RS cur = engine.rs;
        if (cur == null || editGen != renderedGen) { changed(); return; }
        Engine.RS s = new Engine.RS();
        boolean cropFlip = cur.cropMode != cropMode;
        s.ver = cropFlip ? ++rsVer : cur.ver;
        s.doc = cur.doc;
        s.cropMode = cropMode;
        overlays(s);
        engine.rs = s;
        gl.requestRender();
        cv.invalidate();
        updateUndoButtons();
        if (cropFlip) scheduleDetail();
    }

    void beginEdit() {
        editGen++;
        if (previewBase != null) return;   // inside a preview: Apply makes the one undo step
        doc.sel = sel;   // the snapshot remembers the selection too: Undo covers both
        undo.add(doc.copy());
        if (undo.size() > 60) undo.remove(0);
        redo.clear();
        trimHistory();
        updateUndoButtons();
    }

    /** Keeps the masks held only by history under HISTORY_BYTES by dropping the oldest steps. */
    private void trimHistory() {
        while (undo.size() > 1) {
            IdentityHashMap<Bitmap, Boolean> seen = new IdentityHashMap<Bitmap, Boolean>();
            for (Layer l : doc.layers) if (l.mask != null) seen.put(l.mask, true);
            long bytes = 0;
            if (sel != null) seen.put(sel, true);
            for (Doc d : undo) {
                for (Layer l : d.layers)
                    if (l.mask != null && seen.put(l.mask, true) == null) bytes += l.mask.getByteCount();
                if (d.sel != null && seen.put(d.sel, true) == null) bytes += d.sel.getByteCount();
            }
            if (bytes <= HISTORY_BYTES) break;
            undo.remove(0);
        }
    }

    /** Copy-on-write: history keeps the old mask bitmap, the live layer gets a fresh copy. */
    Bitmap beginMaskStroke() {
        Layer l = selectedLayer();
        if (l == null || l.mask == null) return null;
        beginEdit();
        l.mask = l.mask.copy(Bitmap.Config.ALPHA_8, true);
        l.maskVer++;
        return l.mask;
    }

    void maskChanged() {
        Layer l = selectedLayer();
        if (l != null) l.maskVer++;
        changed();
    }

    void cropChanged() { changed(); }

    void doUndo() {
        Crash.note("undo");
        if (previewBase != null) { cancelPreview(); return; }
        if (undo.isEmpty() || busy) return;
        doc.sel = sel;
        redo.add(doc.copy());
        doc = undo.remove(undo.size() - 1);
        afterHistory();
    }

    void doRedo() {
        Crash.note("redo");
        if (previewBase != null) return;
        if (redo.isEmpty() || busy) return;
        doc.sel = sel;
        undo.add(doc.copy());
        doc = redo.remove(redo.size() - 1);
        afterHistory();
    }

    private void afterHistory() {
        if (sel != doc.sel) { sel = doc.sel; selVer++; }
        if (doc.byId(selectedId) == null) selectedId = doc.layers.isEmpty() ? -1 : doc.layers.get(doc.layers.size() - 1).id;
        for (Layer l : doc.layers) l.maskSavedVer = -1;
        Layer r = activeRetouch();
        if (r == null || selStroke >= r.strokes.size()) selStroke = -1;
        if (cropMode) cv.fit();
        changed();
        rebuildPanel();
    }

    private void updateUndoButtons() {
        if (undoBtn != null) undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        if (redoBtn != null) redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); }

    // ================================================================ detail view
    private final Runnable detailRun = new Runnable() { @Override public void run() { startDetail(); } };

    void scheduleDetail() {
        handler.removeCallbacks(detailRun);
        handler.postDelayed(detailRun, 180);
    }

    /** When zoomed past the preview's resolution, renders the visible area at full resolution. */
    private void startDetail() {
        if (closed || exporting || cropMode || doc == null || cv.getWidth() == 0) return;
        float dw = doc.docW(false), dh = doc.docH(false);
        float ps = Math.min(1f, Engine.PREV / Math.max(dw, dh));
        float vs = cv.scale;
        if (vs <= ps * 1.15f) return;
        final float rr = Math.min(vs, 1f);
        float x0 = Math.max(0, -cv.offX / vs), y0 = Math.max(0, -cv.offY / vs);
        float x1 = Math.min(dw, (cv.getWidth() - cv.offX) / vs), y1 = Math.min(dh, (cv.getHeight() - cv.offY) / vs);
        if (x1 - x0 < 1 || y1 - y0 < 1) return;
        final float[] rect = {x0, y0, x1 - x0, y1 - y0};
        final int gen = ++detailGen;
        final Doc snap = doc.copy();
        final int ver = rsVer;
        detailExec.execute(new Runnable() {
            @Override public void run() {
                if (gen != detailGen || closed) return;
                // the same overlap renderDetail will render with (effects can reach far beyond 32 px):
                // sources must cover it, or the edges of a large blur read empty pixels
                float a = Engine.apronFor(snap, rr) / rr;
                final SourceCache.Prepared prep;
                try {
                    prep = sources.prepare(snap, false, new float[]{rect[0] - a, rect[1] - a, rect[2] + 2 * a, rect[3] + 2 * a}, rr);
                } catch (Throwable t) { return; }
                if (gen != detailGen || closed) { prep.recycle(); return; }
                gl.queueEvent(new Runnable() {
                    @Override public void run() {
                        if (gen != detailGen) { prep.recycle(); return; }
                        engine.renderDetail(snap, ver, rect, rr, prep);
                        gl.requestRender();
                    }
                });
            }
        });
    }

    // ================================================================ engine callbacks
    @Override
    public void onHistogram(final int[] h) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                hist = h;
                adj.setHist(h);
            }
        });
    }

    @Override
    public void onGlError(final String msg) {
        act.runOnUiThread(new Runnable() { @Override public void run() { showStatus(msg); } });
    }

    void showStatus(String s) {
        if (s == null) { status.setVisibility(View.GONE); return; }
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    private void setBusy(String s) {
        busy = s != null;
        showStatus(s);
    }

    // ================================================================ top bar + tabs
    private TextView titleView;
    private android.widget.ImageView compareBtn;
    private TextView zoomChip;
    /** Clipping warnings on the canvas (Adjust tab toggle; stays on until turned off). */
    private boolean clipVis;
    private boolean comparing;
    private static boolean splitHinted;

    /** Called by CanvasView whenever the view transform changes. */
    void zoomChanged() {
        if (zoomChip == null) return;
        String z = ViewMath.zoomLabel(cv.scale, cv.fitted);
        if (!z.contentEquals(zoomChip.getText())) zoomChip.setText(z);   // panning calls this every frame
    }

    // compare button: a hold shows the original after 200 ms; a quick tap toggles the split view
    private final Runnable holdCompare = new Runnable() {
        @Override public void run() {
            comparing = true;
            engine.compare = true;
            Ui.setIconColor(compareBtn, Ui.ACCENT);
            gl.requestRender();
            showStatus("Original");
        }
    };

    private void endCompareHold() {
        comparing = false;
        engine.compare = false;
        Ui.setIconColor(compareBtn, cv.splitFrac >= 0 ? Ui.ACCENT : Ui.TEXT);
        gl.requestRender();
        showStatus(null);
    }

    private final Runnable toggleSplit = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (cropMode) { toast("Before / after isn't available while cropping."); return; }
            boolean on = cv.splitFrac < 0;
            Crash.note("split " + on);
            cv.setSplit(on);
            Ui.setIconColor(compareBtn, on ? Ui.ACCENT : Ui.TEXT);
            if (on && !splitHinted) {
                splitHinted = true;
                toast("Before on the left, after on the right: drag the line. Tap \u25d0 again to close; hold it for the whole original.");
            }
        }
    };

    private View topBar() {
        LinearLayout b = Ui.row(act);
        b.setBackgroundColor(Ui.BG);
        b.setPadding(0, 0, Ui.dp(6), 0);
        b.addView(Ui.iconBtn(act, "back", new View.OnClickListener() {
            @Override public void onClick(View v) { if (!back()) act.closeWorkspace(); }
        }));
        titleView = Ui.text(act, Projects.name(dir), 15, Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(0, Ui.dp(12), Ui.dp(4), Ui.dp(12));
        titleView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { renameProject(); } });
        b.addView(titleView, Ui.lp(0, -2, 1f));
        undoBtn = Ui.iconBtn(act, "undo", new View.OnClickListener() { @Override public void onClick(View v) { doUndo(); } });
        redoBtn = Ui.iconBtn(act, "redo", new View.OnClickListener() { @Override public void onClick(View v) { doRedo(); } });
        b.addView(undoBtn);
        b.addView(redoBtn);
        // hold to see the original (like Lightroom / Snapseed); tap for a before/after split
        compareBtn = Ui.iconBtn(act, "compare", null);
        compareBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { comparing = false; handler.postDelayed(holdCompare, 200); }
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(holdCompare);
                    if (comparing) endCompareHold();
                    else if (a == MotionEvent.ACTION_UP) handler.post(toggleSplit);   // never rebuild inside a touch dispatch
                }
                return true;
            }
        });
        b.addView(compareBtn);
        TextView ex = Ui.text(act, "Export", 13, 0xff111111);
        ex.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        ex.setBackground(Ui.round(Ui.ACCENT, 8, 0));
        ex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { exportDialog(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(4);
        b.addView(ex, lp);
        b.addView(Ui.iconBtn(act, "more", new View.OnClickListener() { @Override public void onClick(View v) { overflow(); } }));
        updateUndoButtons();
        return b;
    }

    private void renameProject() {
        act.renameProject(dir, new Pickers.TextCb() { @Override public void text(String s) { titleView.setText(s); } });
    }

    private void overflow() {
        int adj = 0, px = 0;
        for (Layer l : doc.layers) if (l.isAdjustment()) adj++; else px++;
        new Sheet(act, Projects.name(dir) + "  \u00b7  " + Math.round(doc.cropW) + " \u00d7 " + Math.round(doc.cropH)
                + "  \u00b7  " + px + " layers, " + adj + " adjustments")
                .item("pencil", "Rename project", new Runnable() { @Override public void run() { renameProject(); } })
                .item("folder", "Versions…", "Save a named recovery point or restore a copy", new Runnable() { @Override public void run() { act.versions(dir); } })
                .item("fit", "Fit image to screen", new Runnable() { @Override public void run() { cv.fit(); } })
                .section("Looks")
                .item("star", "Apply a look\u2026", new Runnable() { @Override public void run() { applyLook(); } })
                .item("star_on", "Save adjustments as a look\u2026", new Runnable() { @Override public void run() { saveLook(); } })
                .item("copy", "Copy all adjustments", new Runnable() { @Override public void run() { copyAdjustments(null); } })
                .item("paste", Looks.hasClip() ? "Paste adjustments (" + Looks.clipLabel() + ")" : "Paste adjustments (nothing copied)",
                        new Runnable() { @Override public void run() { pasteAdjustments(); } })
                .show();
    }

    // ================================================================ looks and copy/paste
    /** Visible-or-not adjustment layers, bottom first. */
    private ArrayList<Layer> adjustmentLayers() {
        ArrayList<Layer> out = new ArrayList<Layer>();
        for (Layer l : doc.layers) if (Looks.lookable(l)) out.add(l);
        return out;
    }

    private void saveLook() {
        Crash.note("save look");
        LookUi.saveDialog(act, adjustmentLayers(), Projects.name(dir));
    }

    private void applyLook() {
        Crash.note("apply look");
        LookUi.pick(act, false, new LookUi.Apply() {
            @Override public void apply(ArrayList<Layer> layers, String name) { previewLook(layers, name); }
        });
    }

    /** one == null copies every adjustment layer; otherwise just that layer's settings. */
    private void copyAdjustments(Layer one) {
        ArrayList<Layer> ls = new ArrayList<Layer>();
        if (one != null) ls.add(one); else ls.addAll(adjustmentLayers());
        if (ls.isEmpty()) { toast("There are no adjustments to copy."); return; }
        Looks.copy(ls, Looks.describe(ls));
        int masked = 0;
        for (Layer l : ls) if (l.mask != null) masked++;
        toast("Copied " + Looks.describe(ls) + (masked > 0 ? " (masks stay here)" : "") + ". Paste in any photo or video.");
    }

    private void pasteAdjustments() {
        if (!Looks.hasClip()) { toast("Nothing copied yet: use Copy on a layer, a clip's grade, or Copy all adjustments."); return; }
        insertLayers(Looks.paste(), Looks.clipLabel());
    }

    /** Adds settings-only layers (bottom first) above the selected layer, as one undo step. */
    private void insertLayers(ArrayList<Layer> ls, String what) {
        if (ls.isEmpty()) return;
        if (previewBase != null) applyPreview();
        Crash.note("insert " + ls.size() + " layers");
        beginEdit();
        int at = insertIndex(), par = insertParent();
        for (Layer l : ls) {
            l.id = doc.nextId++;
            l.parent = par;
            l.rebuildLut();
            doc.layers.add(at++, l);
        }
        selectedId = ls.get(ls.size() - 1).id;
        changed();
        rebuildPanel();
        act.snack("Added " + what, "Undo", new Runnable() { @Override public void run() { doUndo(); } });
    }

    private TextView smallBtn(String s, View.OnClickListener l) {
        TextView t = Ui.text(act, s, 13, Ui.TEXT);
        t.setPadding(Ui.dp(9), Ui.dp(10), Ui.dp(9), Ui.dp(10));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void buildTabs() {
        tabs.removeAllViews();
        // Mask is not a tab: it is a mode of the selected layer, entered from its panel
        String[] names = {"Layers", "Adjust", "Effects", "Select", "Retouch", "Crop"};
        String[] icons = {"layers", "sliders", "fx", "select", "heal", "crop"};
        int[] ids = {T_LAYERS, T_EDIT, T_FX, T_SELECT, T_RETOUCH, T_CROP};
        int shown = tab == T_MASK ? maskReturn : tab;
        for (int i = 0; i < names.length; i++) {
            final int t = ids[i];
            tabs.addView(Ui.tab(act, icons[i], names[i], shown == t, new View.OnClickListener() { @Override public void onClick(View v) { tabTapped(t); } }),
                    Ui.lp(0, -1, 1f));
        }
    }

    /**
     * Tapping the current tab folds the inspector to one row and back; tapping another tab
     * opens it, so tools are never hidden behind a collapsed panel.
     */
    private void tabTapped(int t) {
        if (t == tab) { setPanelSize(panelSize == 0 ? 1 : 0); return; }
        if (panelSize == 0) setPanelSize(1);
        setTab(t);
    }

    // ================================================================ inspector size
    /** 0 compact (one summary row, the image gets the screen), 1 medium, 2 expanded. */
    private int panelSize = 1;
    /** The tab Mask mode returns to (and shows as selected). */
    private int maskReturn = T_LAYERS;

    private int panelHeight(int size) {
        int h = act.getResources().getDisplayMetrics().heightPixels;
        return size == 0 ? Ui.dp(52) : (int) (h * (size == 1 ? 0.36f : 0.62f));
    }

    void setPanelSize(int s) {
        s = Math.max(0, Math.min(2, s));
        if (s == panelSize) return;
        Crash.note("panel size " + s);
        panelSize = s;
        try { act.getSharedPreferences("ui", 0).edit().putInt("panelSize", s).apply(); } catch (Exception ignored) {}
        // one layout change, not an animation: every frame of a resize would recreate the GL surface
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panelScroll.getLayoutParams();
        lp.height = panelHeight(s);
        panelScroll.setLayoutParams(lp);
        if (grip != null) grip.invalidate();
        postRebuild();
    }

    private View grip;

    /** The handle above the inspector: drag to resize (snaps to the nearest size), tap to fold. */
    private View grip() {
        final float slop = android.view.ViewConfiguration.get(act).getScaledTouchSlop();
        View g = new View(act) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setColor(Ui.DIM);
                float w = Ui.dp(36), hh = Ui.dp(4), cx = getWidth() / 2f, cy = getHeight() / 2f;
                c.drawRoundRect(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2, hh / 2, hh / 2, p);
            }
        };
        g.setBackgroundColor(Ui.PANEL);
        g.setContentDescription("Resize tools panel");
        g.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            int h0;
            boolean active, dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        active = true; dragging = false; y0 = e.getRawY(); h0 = panelScroll.getHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (active && Math.abs(e.getRawY() - y0) > slop) dragging = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!active) return true;
                        active = false;
                        final int target;
                        if (!dragging) target = panelSize == 0 ? 1 : 0;
                        else {
                            float want = h0 + (y0 - e.getRawY());   // dragging up grows the panel
                            int best = panelSize;
                            float bd = Float.MAX_VALUE;
                            for (int s = 0; s < 3; s++) {
                                float d = Math.abs(panelHeight(s) - want);
                                if (d < bd) { bd = d; best = s; }
                            }
                            // a clear flick always moves at least one step
                            if (best == panelSize && Math.abs(y0 - e.getRawY()) > Ui.dp(48))
                                best = panelSize + (y0 > e.getRawY() ? 1 : -1);
                            target = best;
                        }
                        // posted: never relayout from inside a touch dispatch
                        handler.post(new Runnable() { @Override public void run() { if (!closed) setPanelSize(target); } });
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        active = false;
                        return true;
                }
                return true;
            }
        });
        grip = g;
        return g;
    }

    /** What the folded inspector shows: where you are, and a way back to the tools. */
    private void compactPanel() {
        Layer l = selectedLayer();
        String[] tabNames = {"Layers", "Adjust", "Retouch", "Mask", "Crop", "Effects", "Select"};
        LinearLayout row = Ui.row(act);
        row.setPadding(Ui.dp(14), 0, Ui.dp(8), 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(act, tabNames[tab] + (l != null && tab != T_CROP ? "  \u00b7  " + l.name : ""), 13, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, Ui.lp(0, -2, 1f));
        if (tab == T_MASK) row.addView(Ui.chip(act, "Done", false, new View.OnClickListener() { @Override public void onClick(View v) { setTab(maskReturn); } }));
        row.addView(Ui.chip(act, "Tools", false, new View.OnClickListener() { @Override public void onClick(View v) { setPanelSize(1); } }));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setPanelSize(1); } });
        panel.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(48)));
    }

    void setTab(int t) {
        Crash.note("tab " + t);
        if (previewBase != null && t != tab) applyPreview();
        if (t == T_MASK && tab != T_MASK) maskReturn = tab == T_CROP ? T_LAYERS : tab;
        if (t != T_FX) pendingFxMask = null;
        boolean wasCrop = cropMode;
        tab = t;
        cropMode = t == T_CROP;
        if (cropMode && cv.splitFrac >= 0) { cv.setSplit(false); Ui.setIconColor(compareBtn, Ui.TEXT); }
        if (t != T_LAYERS && t != T_EDIT && t != T_FX) moveTool = false;
        if (cropMode) engine_detailOff();
        buildTabs();
        rebuildPanel();
        if (wasCrop != cropMode) cv.post(new Runnable() { @Override public void run() { cv.fit(); } });
        viewChanged();
    }

    private void engine_detailOff() {
        gl.queueEvent(new Runnable() { @Override public void run() { engine.detailOff(); } });
    }

    void applyTool() {
        Layer l = selectedLayer();
        if (tab != T_EDIT) neutralPick = false;   // leaving Adjust cancels a pending pick
        if (neutralPick) cv.tool = CanvasView.SAMPLE;
        else if (tab == T_CROP) cv.tool = CanvasView.CROP;
        else if (tab == T_MASK) {
            if (maskTool == CanvasView.SAMPLE) cv.tool = CanvasView.SAMPLE;
            else if (maskTool == CanvasView.MASK_SHAPE) cv.tool = selShape() != null ? CanvasView.MASK_SHAPE : CanvasView.NAV;
            else if (maskTool == CanvasView.MASK_FREEHAND) cv.tool = l != null && l.mask != null ? CanvasView.MASK_FREEHAND : CanvasView.NAV;
            else cv.tool = (l != null && l.mask != null) ? maskTool : CanvasView.NAV;
        } else if (tab == T_RETOUCH) cv.tool = retouchTool;
        else if (tab == T_SELECT) cv.tool = selTool;
        else if ((tab == T_LAYERS || tab == T_EDIT) && moveTool && l != null && l.movable()) cv.tool = CanvasView.TRANSFORM;
        else if ((tab == T_FX || tab == T_EDIT) && fxPoint() != null) cv.tool = CanvasView.FX_POINT;
        else cv.tool = CanvasView.NAV;
        cv.invalidate();
    }

    private boolean rebuilding;
    private final Runnable rebuildPosted = new Runnable() { @Override public void run() { if (!closed) rebuildPanel(); } };

    /** Rebuild on the next loop turn (safe from touch listeners and view callbacks). */
    void postRebuild() {
        handler.removeCallbacks(rebuildPosted);
        handler.post(rebuildPosted);
    }

    void rebuildPanel() {
        if (rebuilding) { postRebuild(); return; }   // re-entered from a view callback during removal
        rebuilding = true;
        try { rebuildPanelNow(); } finally { rebuilding = false; }
    }

    private final InspectorState inspector=new InspectorState();
    private void section(String name){InspectorSections.mark(panel,name);}

    private void rebuildPanelNow() {
        Crash.note("panel " + tab);
        String key = tab + ":" + (tab == T_CROP ? -1 : selectedId) + ":" + panelSize;
        inspector.open(key,panelScroll.getScrollY());
        panel.removeAllViews();
        if (previewBase != null) previewBar();
        if (panelSize == 0) {
            compactPanel();
            applyTool();
            return;
        }
        switch (tab) {
            case T_LAYERS: layersPanel(); break;
            case T_EDIT: editPanel(); break;
            case T_RETOUCH: retouchPanel(); break;
            case T_MASK: maskPanel(); break;
            case T_CROP: cropPanel(); break;
            case T_FX: fxPanel(); break;
            case T_SELECT: selectPanel(); break;
        }
        applyTool();
        InspectorSections.organize(panel,panelScroll,inspector,new Runnable(){@Override public void run(){rebuildPanel();}});
        final int sy = inspector.scroll();
        panelScroll.post(new Runnable() { @Override public void run() { panelScroll.scrollTo(0, sy); } });
    }

    // ================================================================ Layers panel
    static final String[] TYPE_ICONS = {"image", "fill", "light", "curves", "levels", "hsl", "detail", "text", "heal", "lut", "fx", "folder"};
    private LinearLayout stack;

    private void layersPanel() {
        InspectorSections.markList(panel, "Stack", "Layers");
        LinearLayout head = Ui.row(act);
        head.setPadding(0, 0, Ui.dp(12), 0);
        head.addView(Ui.label(act, "Layers · " + doc.layers.size()), Ui.lp(0, -2, 1f));
        head.addView(Ui.chip(act, "plus", "Add", false, new View.OnClickListener() { @Override public void onClick(View v) { addSheet(); } }));
        panel.addView(head);
        stack = Ui.col(act);
        for (int i = doc.layers.size() - 1; i >= 0; i--) stack.addView(layerRow(doc.layers.get(i)));
        panel.addView(stack);
        final Layer l = selectedLayer();
        if (l == null) {
            panel.addView(hint("Tap Add for a photo, text, a fill or an adjustment layer."));
            return;
        }
        section("Properties");
        panel.addView(Ui.label(act, "Selected · " + l.name));
        if (l.type != Layer.RETOUCH && l.type != Layer.GROUP) {
            TextView mode = Ui.button(act, "Blend: " + MODES[l.mode], new View.OnClickListener() {
                @Override public void onClick(View v) { chooseMode(); }
            });
            mode.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(2));
            panel.addView(mode, mlp);
        }
        panel.addView(slider("Opacity", 0, 1, 1, l.opacity, 100, 0, "%", new Setter() {
            @Override public void set(float v) { selectedLayer().opacity = v; }
        }));
        ArrayList<View> acts = new ArrayList<View>();
        if (l.movable()) {
            acts.add(moveChip());
            if (l.type == Layer.IMAGE) acts.add(Ui.chip(act, "fit", "Fit", false, new View.OnClickListener() {
                @Override public void onClick(View v) { beginEdit(); placeFit(selectedLayer(), 1f); changed(); }
            }));
        }
        if (l.type == Layer.FILL) acts.add(Ui.chip(act, "fill", "Color", false, new View.OnClickListener() {
            @Override public void onClick(View v) { colorDialog(); }
        }));
        if (l.isAdjustment() || l.type == Layer.TEXT) acts.add(Ui.chip(act, "sliders", l.type == Layer.TEXT ? "Edit text" : "Adjust", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(T_EDIT); }
        }));
        acts.add(Ui.chip(act, "mask", l.mask != null ? "Edit mask" : "Mask", false, new View.OnClickListener() { @Override public void onClick(View v) { setTab(T_MASK); } }));
        final int li = doc.indexOf(l.id);
        if (li >= 0 && (l.clip || doc.canClip(li)))
            acts.add(Ui.chip(act, "down", l.clip ? "Clipped" : "Clip", l.clip, new View.OnClickListener() { @Override public void onClick(View v) { toggleClip(selectedLayer()); } }));
        acts.add(Ui.chip(act,"more","More layer actions",false,new View.OnClickListener(){@Override public void onClick(View v){layerMenu(l);}}));
        panel.addView(Ui.strip(act, acts.toArray(new View[0])));
        panel.addView(hint(moveTool ? "One finger moves the layer, two fingers scale and rotate it. Double-tap fits the view."
                : "Drag \u2261 to reorder. The eye hides a layer; \u22ee has rename, blend mode and more."));
    }

    private TextView moveChip() {
        return Ui.chip(act, "move", moveTool ? "Moving · done" : "Move / scale / rotate", moveTool, new View.OnClickListener() {
            @Override public void onClick(View v) { moveTool = !moveTool; rebuildPanel(); }
        });
    }

    // ================================================================ Effects tab
    private int fxCat = -1;                 // -2 favourites, -1 all, else Fx.CATS index
    private final java.util.HashMap<Integer, Bitmap> fxThumbs = new java.util.HashMap<Integer, Bitmap>();
    private final java.util.HashMap<Integer, ImageView> fxThumbViews = new java.util.HashMap<Integer, ImageView>();
    private int fxThumbVer = -1;
    private boolean fxThumbBusy;
    private final Runnable fxThumbRun = new Runnable() { @Override public void run() { requestFxThumbs(); } };

    private void fxPanel() {
        final Layer sel = selectedLayer();
        fxThumbViews.clear();
        if (sel != null && sel.type == Layer.EFFECT) {
            section("Settings");
            adj.build(panel);
            panel.addView(hint(fxPoint() != null ? "Drag the handle on the image to place the centre / line. Opacity and blend mode are on the Layers tab; Mask above paints where it shows."
                    : "Effects are layers: opacity and blend mode are on the Layers tab; Mask above paints where it shows."));
            View sep = new View(act);
            sep.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 1);
            slp.setMargins(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(2));
            panel.addView(sep, slp);
        }
        InspectorSections.markList(panel, "Browse", "Effect list");
        java.util.HashSet<String> fav = AdjPanels.favorites(act);
        java.util.ArrayList<View> cats = new java.util.ArrayList<View>();
        if (!fav.isEmpty()) cats.add(catChip("star_on", "Favorites", -2));
        cats.add(catChip(null, "All", -1));
        for (int i = 0; i < Fx.CATS.length; i++) cats.add(catChip(Fx.CAT_ICONS[i], Fx.CATS[i], i));
        if (fxCat == -2 && fav.isEmpty()) fxCat = -1;
        panel.addView(Ui.strip(act, cats.toArray(new View[0])));
        java.util.ArrayList<Fx.Def> list = new java.util.ArrayList<Fx.Def>();
        for (Fx.Def d : Fx.ALL) {
            if (fxCat == -2 ? fav.contains(String.valueOf(d.id)) : (fxCat < 0 || d.cat == fxCat)) list.add(d);
        }
        boolean browsing = previewBase != null && !previewIsLook;
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(act);
                row.setGravity(Gravity.TOP);
                row.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), 0);
                panel.addView(row);
            }
            row.addView(fxTile(list.get(i), sel != null && sel.type == Layer.EFFECT && sel.fx == list.get(i).id), Ui.lp(0, -2, 1f));
        }
        if (row != null) for (int k = list.size() % 3; k > 0 && k < 3; k++) row.addView(new View(act), Ui.lp(0, 1, 1f));
        panel.addView(hint(browsing ? "Tap other effects to compare. Adjust it above, then Apply to keep it as a layer, or Cancel. Hold \u25d0 at the top to see the image without it."
                : "Tap an effect to preview it on your image. Nothing changes until you tap Apply. Thumbnails show your image."));
        if (fxThumbVer != rsVer && previewBase == null) {   // thumbnails come from the image without the preview
            gl.removeCallbacks(fxThumbRun);
            gl.postDelayed(fxThumbRun, fxThumbs.isEmpty() ? 0 : 350);
        }
    }

    private TextView catChip(String icon, String label, final int cat) {
        View.OnClickListener l = new View.OnClickListener() { @Override public void onClick(View v) { fxCat = cat; rebuildPanel(); } };
        return icon == null ? Ui.chip(act, label, fxCat == cat, l) : Ui.chip(act, icon, label, fxCat == cat, l);
    }

    private View fxTile(final Fx.Def d, boolean current) {
        LinearLayout t = Ui.col(act);
        t.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(6));
        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(current ? Ui.ACCENT : Ui.PANEL2, 8, current ? Ui.ACCENT : Ui.LINE));
        iv.setPadding(current ? Ui.dp(2) : 0, current ? Ui.dp(2) : 0, current ? Ui.dp(2) : 0, current ? Ui.dp(2) : 0);
        iv.setClipToOutline(true);
        Bitmap b = fxThumbs.get(d.id);
        if (b != null) iv.setImageBitmap(b);
        else {
            iv.setImageDrawable(new IconDrawable(Fx.CAT_ICONS[d.cat], Ui.LINE));
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        fxThumbViews.put(d.id, iv);
        t.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(92)));
        TextView n = Ui.text(act, d.name, 11.5f, current ? Ui.ACCENT : Ui.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, Ui.dp(4), 0, 0);
        t.addView(n);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickEffect(d.id); } });
        return t;
    }

    /** An effect layer nobody has adjusted yet (so a tap on another tile swaps it). */
    private static boolean untouched(Layer l) {
        Layer ref = new Layer();
        Fx.defaults(ref, l.fx);
        if (l.mask != null || l.rangeMode != 0 || Math.abs(l.opacity - 1) > 1e-4 || l.mode != Fx.get(l.fx).defaultMode) return false;
        for (int i = 0; i < 8; i++) if (Math.abs(ref.fxp[i] - l.fxp[i]) > 1e-5) return false;
        for (int i = 0; i < 3; i++) if (ref.fxc[i] != l.fxc[i]) return false;
        return l.name.equals(Fx.get(l.fx).name);
    }

    // ================================================================ previews (try before applying)
    /**
     * The document as it was before the current preview, or null when nothing is being
     * previewed. A preview adds real layers so the full renderer (and the detail view) shows
     * them exactly, but nothing reaches the undo history or the saved project until Apply.
     */
    private Doc previewBase;
    private int previewSel;
    private String previewName;
    private boolean previewIsLook;
    private final ArrayList<Integer> previewIds = new ArrayList<Integer>();

    private void beginPreview(boolean look) {
        if (previewBase != null) return;
        previewBase = doc.copy();
        previewSel = selectedId;
        previewIsLook = look;
        previewIds.clear();
    }

    void applyPreview() {
        if (previewBase == null) return;
        Crash.note("apply preview " + previewName);
        undo.add(previewBase);
        if (undo.size() > 60) undo.remove(0);
        redo.clear();
        previewBase = null;
        previewIds.clear();
        trimHistory();
        changed();   // marks the project dirty so it's saved
        rebuildPanel();
    }

    void cancelPreview() {
        if (previewBase == null) return;
        Crash.note("cancel preview " + previewName);
        doc = previewBase;
        selectedId = previewSel;
        previewBase = null;
        previewIds.clear();
        changed();
        rebuildPanel();
    }

    /** Tile tap: preview the effect; while previewing one, swap it (fresh defaults). */
    private void pickEffect(int id) {
        Crash.note("effect " + id);
        Fx.Def d = Fx.get(id);
        if (previewBase != null && previewIsLook) cancelPreview();
        Layer l = previewBase != null && previewIds.size() == 1 ? doc.byId(previewIds.get(0)) : null;
        if (l == null) {
            beginPreview(false);
            l = doc.newLayer(Layer.EFFECT);
            insertLayer(l);
            previewIds.add(l.id);
            if (pendingFxMask != null) {   // "Effect inside selection"
                l.mask = pendingFxMask;
                clearShapes(l);
                l.maskVer++;
                l.maskSavedVer = -1;
                pendingFxMask = null;
            }
        }
        Fx.defaults(l, id);
        l.name = d.name;
        l.mode = d.defaultMode;
        l.opacity = 1;
        selectedId = l.id;
        previewName = d.name;
        moveTool = false;
        changed();
        rebuildPanel();
        panelScroll.post(new Runnable() { @Override public void run() { panelScroll.smoothScrollTo(0, 0); } });
    }

    /** A saved look, previewed above the selected layer; picking another look replaces it. */
    private void previewLook(ArrayList<Layer> ls, String name) {
        if (ls.isEmpty()) return;
        if (previewBase != null) cancelPreview();
        beginPreview(true);
        int at = insertIndex(), par = insertParent();
        for (Layer l : ls) {
            l.id = doc.nextId++;
            l.parent = par;
            l.rebuildLut();
            doc.layers.add(at++, l);
            previewIds.add(l.id);
        }
        selectedId = ls.get(ls.size() - 1).id;
        previewName = name;
        changed();
        rebuildPanel();
    }

    /** The bar shown on every panel while previewing: what it is, Cancel, Apply. */
    private void previewBar() {
        LinearLayout bar = Ui.row(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        bar.setBackgroundColor(0xff2a2620);
        TextView t = Ui.text(act, (previewIsLook ? "Previewing look \u00b7 " : "Previewing \u00b7 ") + previewName, 13, Ui.ACCENT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, Ui.lp(0, -2, 1f));
        if (previewIsLook) bar.addView(Ui.chip(act, "Try another", false, new View.OnClickListener() { @Override public void onClick(View v) { applyLook(); } }));
        bar.addView(Ui.chip(act, "Cancel", false, new View.OnClickListener() { @Override public void onClick(View v) { cancelPreview(); } }));
        TextView ap = Ui.chip(act, "Apply", true, new View.OnClickListener() { @Override public void onClick(View v) { applyPreview(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(6);
        bar.addView(ap, lp);
        panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void requestFxThumbs() {
        if (fxThumbBusy || closed || tab != T_FX) return;
        fxThumbBusy = true;
        final int ver = rsVer;
        final int[] ids = new int[Fx.ALL.length];
        for (int i = 0; i < ids.length; i++) ids[i] = Fx.ALL[i].id;
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                try {
                    engine.effectThumbs(ids, Ui.dp(92) > 300 ? 300 : Math.max(120, Ui.dp(92)), new Engine.ThumbOut() {
                        @Override public void thumb(final int fx, final Bitmap b) {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    Bitmap old = fxThumbs.put(fx, b);
                                    ImageView iv = fxThumbViews.get(fx);
                                    if (iv != null) { iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageBitmap(b); }
                                    if (old != null && old != b) old.recycle();
                                }
                            });
                        }
                    });
                } catch (Throwable ignored) {}
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        fxThumbBusy = false;
                        fxThumbVer = ver;
                        if (tab == T_FX && rsVer != ver) { gl.removeCallbacks(fxThumbRun); gl.postDelayed(fxThumbRun, 350); }
                    }
                });
                gl.requestRender();
            }
        });
    }

    // ---- on-canvas point for effects with a centre / axis / horizon
    float[] fxPoint() {
        Layer l = selectedLayer();
        if (l == null || l.type != Layer.EFFECT) return null;
        Fx.Def d = Fx.get(l.fx);
        if (d.cxParam < 0 && d.cyParam < 0) return null;
        if (l.fx == Fx.MIRROR) {   // only the axes the mirror uses
            int axis = Math.round(l.fxp[0]);
            return new float[]{axis == 1 ? -1 : l.fxp[2], axis == 0 ? -1 : l.fxp[3]};
        }
        return new float[]{d.cxParam < 0 ? -1 : l.fxp[d.cxParam], d.cyParam < 0 ? -1 : l.fxp[d.cyParam]};
    }

    /** Extra guide lines through the point (mirror axes): {vertical?, horizontal?}. */
    float[] fxGuide() {
        Layer l = selectedLayer();
        if (l == null || l.type != Layer.EFFECT || l.fx != Fx.MIRROR || Math.round(l.fxp[0]) != 2) return null;
        return new float[]{1, 1};
    }

    void fxPointBegin() { beginEdit(); }

    void fxPointMoved(float fx, float fy) {
        Layer l = selectedLayer();
        if (l == null || l.type != Layer.EFFECT) return;
        Fx.Def d = Fx.get(l.fx);
        if (l.fx == Fx.MIRROR) { l.fxp[2] = fx; l.fxp[3] = fy; }
        else {
            if (d.cxParam >= 0) l.fxp[d.cxParam] = fx;
            if (d.cyParam >= 0) l.fxp[d.cyParam] = fy;
        }
        changed();
    }

    void fxPointDone() { rebuildPanel(); }

    /** From an effect's panel: put a mask on it and switch to the matching mask tool. */
    private void maskSelected(int how) {
        Layer l = selectedLayer();
        if (l == null) return;
        if (how < 0) { setTab(T_MASK); return; }   // plain "Mask" button: the panel offers reveal/hide all
        if (l.mask == null) addMask(false);   // hide all: the brush or gradient reveals the effect
        if (how == 3 || how == 4) {   // 3.15: a shape or text on a hide-all mask shows the effect inside it
            gradCombine = 1;
            cv.erase = false;
            setTab(T_MASK);
            if (how == 3) shapeSheet(); else addShapeKind("text");
            return;
        }
        maskTool = how == 0 ? CanvasView.BRUSH : (how == 1 ? CanvasView.GRAD_LIN : CanvasView.GRAD_RAD);
        if (how != 0) gradCombine = 0;
        cv.erase = false;
        setTab(T_MASK);
        toast(how == 0 ? "Paint where the effect should show" : "Drag across the image to place the gradient");
    }

    private TextView addAdjChip(final int type) {
        return Ui.chip(act, TYPE_ICONS[type], Layer.TYPE_NAMES[type], false, new View.OnClickListener() {
            @Override public void onClick(View v) { addAdjustment(type); }
        });
    }

    /** Add-layer sheet: a grid like Photoshop Express's "+" (icons, not a long strip of chips). */
    private void addSheet() {
        Crash.note("add sheet");
        Sheet sh = new Sheet(act, "Add a layer above the selected one");
        sh.section("Content");
        sh.tile("image", "Photo", new Runnable() { @Override public void run() { act.pickImage(MainActivity.REQ_ADD_LAYER); } });
        sh.tile("text", "Text", new Runnable() { @Override public void run() { addText(); } });
        sh.tile("fill", "Fill", new Runnable() { @Override public void run() { addFill(); } });
        sh.tile("folder", "From Files", new Runnable() { @Override public void run() { act.pickImageFile(MainActivity.REQ_ADD_LAYER); } });
        sh.section("Effects & looks");
        sh.tile("fx", "Effect", new Runnable() { @Override public void run() { setTab(T_FX); } });
        sh.tile("star", "Look", new Runnable() { @Override public void run() { applyLook(); } });
        if (Looks.hasClip()) sh.tile("paste", "Paste", new Runnable() { @Override public void run() { pasteAdjustments(); } });
        sh.section("Adjustment");
        int[] types = {Layer.BASIC, Layer.CURVES, Layer.LEVELS, Layer.HSL, Layer.LUT, Layer.DETAIL};
        String[] labels = {"Light & Color", "Curves", "Levels", "HSL", "LUT", "Detail"};
        for (int i = 0; i < types.length; i++) {
            final int ty = types[i];
            sh.tile(TYPE_ICONS[ty], labels[i], new Runnable() { @Override public void run() { addAdjustment(ty); } });
        }
        sh.show();
    }

    /** Per-layer menu (the row's \u22ee). */
    private void layerMenu(final Layer l) {
        Crash.note("layer menu");
        selectedId = l.id;
        moveTool = false;
        selStroke = -1;
        changed();
        rebuildPanel();
        int i = doc.indexOf(l.id);
        Sheet sh = new Sheet(act, l.name + "  \u00b7  " + Layer.TYPE_NAMES[l.type]);
        sh.section("Layer");
        sh.item("pencil", "Rename", new Runnable() { @Override public void run() { rename(); } });
        sh.item("copy", "Duplicate", new Runnable() { @Override public void run() { duplicate(); } });
        if (l.type != Layer.RETOUCH && l.type != Layer.GROUP) sh.item("blend", "Blend mode", MODES[l.mode], new Runnable() { @Override public void run() { chooseMode(); } });
        sh.item(l.visible ? "eye_off" : "eye", l.visible ? "Hide" : "Show", new Runnable() { @Override public void run() { toggleVisible(l.id); } });
        sh.section("Arrange");
        if (i < doc.layers.size() - 1) sh.item("up", "Move up", new Runnable() { @Override public void run() { moveLayer(1); } });
        if (i > 0) sh.item("down", "Move down", new Runnable() { @Override public void run() { moveLayer(-1); } });
        if (l.movable()) sh.item("move", l.type == Layer.GROUP ? "Move / scale / rotate the group" : "Move / scale / rotate", new Runnable() { @Override public void run() { moveTool = true; setTab(T_LAYERS); } });
        if (i >= 0 && (l.clip || doc.canClip(i)))
            sh.item("down", l.clip ? "Release clipping" : "Clip to layer below", l.clip ? null : "Shows only where the layer below has pixels",
                    new Runnable() { @Override public void run() { toggleClip(l); } });
        if (l.type == Layer.GROUP) sh.item("layers", "Ungroup", "Keeps the layers, removes the group", new Runnable() { @Override public void run() { ungroup(l); } });
        else if (l.parent != 0) sh.item("up", "Move out of the group", new Runnable() { @Override public void run() { leaveGroup(l); } });
        else {
            sh.item("folder", "Put in a new group", new Runnable() { @Override public void run() { groupLayer(l, false); } });
            if (i > 0 && doc.layers.get(i - 1).parent == 0 && doc.layers.get(i - 1).type != Layer.GROUP && !doc.layers.get(i - 1).isBase)
                sh.item("folder", "Group with the layer below", new Runnable() { @Override public void run() { groupLayer(l, true); } });
            final Layer gb = groupBelow(l);
            if (gb != null) sh.item("down", "Move into " + gb.name, new Runnable() { @Override public void run() { enterGroup(l, gb); } });
        }
        sh.section("Settings");
        if (Looks.lookable(l)) sh.item("copy", "Copy settings", new Runnable() { @Override public void run() { copyAdjustments(l); } });
        if (Looks.hasClip()) sh.item("paste", "Paste above (" + Looks.clipLabel() + ")", new Runnable() { @Override public void run() { pasteAdjustments(); } });
        sh.section("Remove");
        sh.danger("trash", "Delete layer", new Runnable() { @Override public void run() { deleteLayer(); } });
        sh.show();
    }

    private void toggleVisible(int id) {
        beginEdit();
        Layer cur = doc.byId(id);
        if (cur != null) cur.visible = !cur.visible;
        changed();
        rebuildPanel();
    }

    private View layerRow(final Layer l) {
        final LinearLayout r = Ui.row(act);
        boolean sel = l.id == selectedId;
        r.setBackground(Ui.round(sel ? 0xff2a2620 : Ui.PANEL2, 8, sel ? Ui.ACCENT : 0));
        r.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        android.widget.ImageView eye = Ui.iconBtn(act, l.visible ? "eye" : "eye_off", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleVisible(l.id); }
        });
        Ui.setIconColor(eye, l.visible ? Ui.TEXT : Ui.DIM);
        r.addView(eye);
        View badge;
        if ((l.type == Layer.IMAGE || l.type == Layer.TEXT) && l.preview != null) {
            ImageView iv = new ImageView(act);
            if (l.thumb == null) {
                int ref = l.type == Layer.TEXT ? Math.max(l.preview.getWidth(), l.preview.getHeight())
                        : Math.min(l.preview.getWidth(), l.preview.getHeight());
                float k = Ui.dp(40) * 2f / Math.max(1, ref);
                k = Math.min(1f, k);
                l.thumb = Bitmap.createScaledBitmap(l.preview, Math.max(1, Math.round(l.preview.getWidth() * k)),
                        Math.max(1, Math.round(l.preview.getHeight() * k)), true);
            }
            iv.setImageBitmap(l.thumb);
            iv.setScaleType(l.type == Layer.TEXT ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.round(l.type == Layer.TEXT ? 0xff3a3d44 : Ui.PANEL, 4, Ui.LINE));
            iv.setClipToOutline(true);
            badge = iv;
        } else {
            ImageView iv = new ImageView(act);
            int bg = l.type == Layer.FILL ? l.fillColor : 0xff2f333a;
            boolean light = l.type == Layer.FILL && (0.299 * ((bg >> 16) & 255) + 0.587 * ((bg >> 8) & 255) + 0.114 * (bg & 255)) > 140;
            iv.setImageDrawable(new IconDrawable(TYPE_ICONS[l.type], l.type == Layer.FILL ? (light ? 0x99000000 : 0x99ffffff) : Ui.ACCENT));
            iv.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
            iv.setBackground(Ui.round(bg | 0xff000000, 4, Ui.LINE));
            badge = iv;
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
        blp.rightMargin = Ui.dp(10);
        r.addView(badge, blp);
        LinearLayout txt = Ui.col(act);
        TextView nm = Ui.text(act, l.name, 14, l.visible ? Ui.TEXT : Ui.DIM);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(nm);
        if (l.clip) nm.setText("\u21b3 " + l.name);
        String det = l.type == Layer.GROUP ? "Group \u00b7 " + doc.children(l.id).size() + " layers \u00b7 " + Math.round(l.opacity * 100) + "%" + (l.mask != null ? " \u00b7 mask" : "")
                : (l.type == Layer.RETOUCH ? l.strokes.size() + " strokes" : MODES[l.mode])
                + " · " + Math.round(l.opacity * 100) + "%" + (l.mask != null ? " · mask" : "")
                + (l.rangeMode != 0 ? " · range" : "");
        txt.addView(Ui.text(act, det, 11, Ui.DIM));
        r.addView(txt, Ui.lp(0, -2, 1f));
        r.addView(Ui.iconBtn(act, "more", new View.OnClickListener() { @Override public void onClick(View v) { layerMenu(l); } }));
        final android.widget.ImageView handle = Ui.iconBtn(act, "drag", null);
        Ui.setIconColor(handle, Ui.DIM);
        handle.setOnTouchListener(new DragReorder(r));
        r.addView(handle);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectedId != l.id) { selectedId = l.id; moveTool = false; selStroke = -1; viewChanged(); rebuildPanel(); }
            }
        });
        r.setTag(l.id);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        // a group's layers are indented under it; clipped layers a little more
        lp.setMargins(Ui.dp(12 + (l.parent != 0 ? 22 : 0) + (l.clip ? 14 : 0)), Ui.dp(3), Ui.dp(12), Ui.dp(3));
        r.setLayoutParams(lp);
        return r;
    }

    /**
     * Drag a layer row by its handle: the row follows the finger, the rows it passes slide out of
     * the way, and the drop moves the layer (one undo step). The list shows the top layer first,
     * so a row's list position k is layer index n-1-k.
     */
    private final class DragReorder implements View.OnTouchListener {
        private final View row;
        private float startY;
        private int from, to;
        private float pitch;
        private boolean active;

        DragReorder(View row) { this.row = row; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            int am = e.getActionMasked();
            if (am != MotionEvent.ACTION_DOWN && !active) return true;   // stray CANCEL from the rebuild below
            switch (am) {
                case MotionEvent.ACTION_DOWN:
                    active = true;
                    Crash.note("layer drag");
                    panelScroll.requestDisallowInterceptTouchEvent(true);
                    startY = e.getRawY();
                    from = to = stack.indexOfChild(row);
                    pitch = row.getHeight() + Ui.dp(6);
                    row.setTranslationZ(Ui.dp(8));
                    row.setAlpha(0.92f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - startY;
                    row.setTranslationY(dy);
                    int n = stack.getChildCount();
                    to = Math.max(0, Math.min(n - 1, from + Math.round(dy / pitch)));
                    for (int k = 0; k < n; k++) {
                        View o = stack.getChildAt(k);
                        if (o == row) continue;
                        float shift = 0;
                        if (to > from && k > from && k <= to) shift = -pitch;
                        if (to < from && k < from && k >= to) shift = pitch;
                        if (o.getTranslationY() != shift) o.animate().translationY(shift).setDuration(110).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    active = false;
                    panelScroll.requestDisallowInterceptTouchEvent(false);
                    for (int k = 0; k < stack.getChildCount(); k++) {
                        View o = stack.getChildAt(k);
                        o.animate().cancel();
                        o.setTranslationY(0);
                    }
                    row.setTranslationZ(0);
                    row.setAlpha(1f);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && to != from) {
                        int n = doc.layers.size();
                        int li = n - 1 - from, lj = n - 1 - to;
                        beginEdit();
                        Layer l = doc.layers.remove(li);
                        doc.layers.add(lj, l);
                        doc.settleAfterDrag(l);   // joins / leaves a group by where it was dropped; a group brings its layers
                        selectedId = l.id;
                        changed();
                    }
                    // Never tear the panel down from inside its own touch dispatch: removeAllViews()
                    // sends ACTION_CANCEL to the active touch target (this handle), which re-entered
                    // here and refilled the panel mid-removal, leaving null children (2.4 crash).
                    postRebuild();
                    return true;
                }
            }
            return false;
        }
    }

    /** The group a layer inserted at the selection belongs to (the selected layer's group). */
    private int insertParent() {
        Layer s = selectedLayer();
        return s != null && s.type != Layer.GROUP ? s.parent : 0;
    }

    private void insertLayer(Layer l) {
        l.parent = insertParent();
        doc.layers.add(insertIndex(), l);
    }

    private int insertIndex() {
        int i = doc.indexOf(selectedId);
        return i < 0 ? doc.layers.size() : i + 1;
    }

    void addAdjustment(int type) {
        beginEdit();
        Layer l = doc.newLayer(type);
        insertLayer(l);
        selectedId = l.id;
        setTab(T_EDIT);
    }

    private void addFill() {
        beginEdit();
        Layer l = doc.newLayer(Layer.FILL);
        l.fillColor = 0xff1c1c22;
        insertLayer(l);
        selectedId = l.id;
        changed();
        rebuildPanel();
        colorDialog();
    }

    /** Called by MainActivity once a picked photo is copied and decoded. */
    void addImageLayer(String srcName, int w, int h, Bitmap preview) {
        beginEdit();
        Layer l = doc.newLayer(Layer.IMAGE);
        l.src = srcName;
        l.srcW = w;
        l.srcH = h;
        l.preview = preview;
        l.name = "Photo " + l.id;
        placeFit(l, 0.8f);
        insertLayer(l);
        selectedId = l.id;
        moveTool = true;
        if (tab != T_LAYERS) setTab(T_LAYERS); else { changed(); rebuildPanel(); }
    }

    /** Centre the layer in the current crop, scaled to fit within frac of it. */
    void placeFit(Layer l, float frac) {
        l.cx = doc.cropCx;
        l.cy = doc.cropCy;
        l.rot = doc.angle();
        l.scale = Math.min(doc.cropW / l.srcW, doc.cropH / l.srcH) * frac;
    }

    private void moveLayer(int dir) {
        if (doc.indexOf(selectedId) < 0) return;
        beginEdit();
        doc.moveStep(selectedId, dir);   // groups move as a block; a layer at a group's edge steps out of it
        changed();
        rebuildPanel();
    }

    private void duplicate() {
        Layer l = selectedLayer();
        if (l == null) return;
        if (l.type == Layer.GROUP) { duplicateGroup(l); return; }
        beginEdit();
        Layer c = l.copy();
        c.id = doc.nextId++;
        c.isBase = false;
        c.name = l.name + " copy";
        if (c.mask != null) { c.mask = c.mask.copy(Bitmap.Config.ALPHA_8, true); c.maskSavedVer = -1; }
        c.lut = null;
        c.rebuildLut();
        doc.layers.add(doc.indexOf(l.id) + 1, c);
        selectedId = c.id;
        changed();
        rebuildPanel();
    }

    private void deleteLayer() {
        int i = doc.indexOf(selectedId);
        if (i < 0) return;
        beginEdit();
        Layer gone = doc.layers.get(i);
        if (gone.type == Layer.GROUP) {
            ArrayList<Layer> block = doc.groupBlock(gone.id);
            doc.layers.removeAll(block);
            i = Math.min(i, doc.layers.size()) - (block.size() - 1);
            toast("Deleted the group and its " + (block.size() - 1) + " layer" + (block.size() == 2 ? "" : "s") + ". Undo brings them back.");
        } else doc.layers.remove(i);
        doc.normalizeGroups();
        i = Math.max(0, Math.min(i, doc.layers.size()));
        selectedId = doc.layers.isEmpty() ? -1 : doc.layers.get(Math.max(0, i - 1)).id;
        moveTool = false;
        selStroke = -1;
        changed();
        rebuildPanel();
    }

    // ================================================================ groups and clipping
    /** Puts l (and, withBelow, the layer under it) into a new group. */
    private void groupLayer(Layer l, boolean withBelow) {
        if (l == null || l.type == Layer.GROUP || l.parent != 0) return;
        beginEdit();
        Layer g = doc.newLayer(Layer.GROUP);
        int n = 0;
        for (Layer k : doc.layers) if (k.type == Layer.GROUP) n++;
        g.name = "Group " + (n + 1);
        doc.layers.add(doc.indexOf(l.id) + 1, g);
        l.parent = g.id;
        if (withBelow) {
            int i = doc.indexOf(l.id);
            if (i > 0) {
                Layer b = doc.layers.get(i - 1);
                if (b.parent == 0 && b.type != Layer.GROUP && !b.isBase) b.parent = g.id;
            }
        }
        doc.normalizeGroups();
        selectedId = g.id;
        changed();
        rebuildPanel();
    }

    private void ungroup(Layer g) {
        if (g == null || g.type != Layer.GROUP) return;
        beginEdit();
        ArrayList<Layer> kids = doc.children(g.id);
        for (Layer k : kids) k.parent = 0;
        doc.layers.remove(g);
        doc.normalizeGroups();
        selectedId = kids.isEmpty() ? -1 : kids.get(kids.size() - 1).id;
        changed();
        rebuildPanel();
    }

    private void leaveGroup(Layer l) {
        if (l == null || l.parent == 0) return;
        beginEdit();
        Layer g = doc.byId(l.parent);
        l.parent = 0;
        doc.layers.remove(l);
        doc.layers.add(doc.indexOf(g.id) + 1, l);   // just above the group
        doc.normalizeGroups();
        changed();
        rebuildPanel();
    }

    /** A top-level layer directly above a group can step into it (as its top layer). */
    private Layer groupBelow(Layer l) {
        int i = doc.indexOf(l.id);
        if (l.parent != 0 || l.type == Layer.GROUP || i <= 0) return null;
        Layer b = doc.layers.get(i - 1);
        return b.type == Layer.GROUP ? b : null;
    }

    private void enterGroup(Layer l, Layer g) {
        beginEdit();
        l.parent = g.id;
        doc.layers.remove(l);
        doc.layers.add(doc.indexOf(g.id), l);   // directly under the group's layer = its top child
        doc.normalizeGroups();
        changed();
        rebuildPanel();
    }

    private void toggleClip(Layer l) {
        int i = doc.indexOf(l.id);
        if (i < 0) return;
        if (!l.clip && !doc.canClip(i)) { toast("Clipping needs a photo, text or fill layer directly below (clipped layers can stack)."); return; }
        beginEdit();
        l.clip = !l.clip;
        doc.normalizeGroups();
        changed();
        rebuildPanel();
    }

    private void duplicateGroup(Layer g) {
        beginEdit();
        ArrayList<Layer> block = doc.groupBlock(g.id);
        Layer ng = null;
        ArrayList<Layer> copies = new ArrayList<Layer>();
        for (Layer k : block) {
            Layer c = k.copy();
            c.id = doc.nextId++;
            c.isBase = false;
            if (c.mask != null) { c.mask = c.mask.copy(Bitmap.Config.ALPHA_8, true); c.maskSavedVer = -1; }
            c.lut = null;
            c.rebuildLut();
            if (k == g) { ng = c; c.name = g.name + " copy"; }
            copies.add(c);
        }
        for (Layer c : copies) if (c != ng) c.parent = ng.id;
        // a retouch layer copied with its photo works on the copy
        java.util.HashMap<Integer, Integer> ids = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < block.size(); i++) ids.put(block.get(i).id, copies.get(i).id);
        for (Layer c : copies) if (c.type == Layer.RETOUCH && ids.containsKey(c.target)) c.target = ids.get(c.target);
        doc.layers.addAll(doc.indexOf(g.id) + 1, copies);   // the whole copy right above the original
        doc.normalizeGroups();
        selectedId = ng.id;
        changed();
        rebuildPanel();
    }

    private void rename() {
        final Layer l = selectedLayer();
        if (l == null) return;
        final EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(l.name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Layer name").setView(e)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        Layer cur = doc.byId(l.id);
                        if (cur != null) cur.name = e.getText().toString().trim();
                        changed();
                        rebuildPanel();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseMode() {
        final Layer l = selectedLayer();
        if (l == null) return;
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Blend mode")
                .setSingleChoiceItems(MODES, l.mode, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        beginEdit();
                        Layer cur = doc.byId(l.id);
                        if (cur != null) cur.mode = which;
                        changed();
                        rebuildPanel();
                        d.dismiss();
                    }
                }).show();
    }

    /** Colour picker for fill and text layers. */
    private void colorDialog() {
        final Layer l = selectedLayer();
        if (l == null || (l.type != Layer.FILL && l.type != Layer.TEXT)) return;
        final float[] hsv = new float[3];
        Color.colorToHSV(l.fillColor, hsv);
        LinearLayout box = Ui.col(act);
        box.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        final View swatch = new View(act);
        swatch.setBackground(Ui.round(l.fillColor, 8, Ui.LINE));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(48));
        slp.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(6));
        box.addView(swatch, slp);
        final TextView hex = Ui.text(act, String.format(Locale.US, "#%06X", l.fillColor & 0xffffff), 13, Ui.DIM);
        hex.setPadding(Ui.dp(16), 0, 0, Ui.dp(4));
        box.addView(hex);
        final boolean[] began = {false};
        final Runnable apply = new Runnable() {
            @Override public void run() {
                int c = Color.HSVToColor(hsv);
                Layer cur = doc.byId(l.id);
                if (cur != null) {
                    cur.fillColor = c;
                    if (cur.type == Layer.TEXT) TextRender.refreshPreview(cur);
                }
                swatch.setBackground(Ui.round(c, 8, Ui.LINE));
                hex.setText(String.format(Locale.US, "#%06X", c & 0xffffff));
                changed();
            }
        };
        String[] names = {"Hue", "Saturation", "Brightness"};
        float[] max = {360, 1, 1};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Ui.Slider s = new Ui.Slider(act, names[i], 0, max[i], hsv[i], hsv[i], new Ui.Slider.Listener() {
                @Override public void begin() { if (!began[0]) { began[0] = true; beginEdit(); } }
                @Override public void change(float v) { hsv[k] = v; apply.run(); }
            });
            if (i == 0) s.display(1, 0, "°"); else s.display(100, 0, "%");
            box.addView(s);
        }
        TextView hexBtn = Ui.button(act, "Enter hex…", new View.OnClickListener() {
            @Override public void onClick(View v) {
                final EditText e = new EditText(act);
                e.setText(String.format(Locale.US, "%06X", Color.HSVToColor(hsv) & 0xffffff));
                new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Hex color").setView(e)
                        .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                try {
                                    int c = (int) Long.parseLong(e.getText().toString().replace("#", "").trim(), 16) | 0xff000000;
                                    if (!began[0]) { began[0] = true; beginEdit(); }
                                    Color.colorToHSV(c, hsv);
                                    apply.run();
                                } catch (Exception ignored) {}
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.setMargins(Ui.dp(16), Ui.dp(4), 0, 0);
        box.addView(hexBtn, hlp);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(l.type == Layer.TEXT ? "Text color" : "Fill color").setView(box)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { rebuildPanel(); }
                }).show();
    }

    // ================================================================ Text
    private void textDialog(String title, String initial, final TextCallback cb) {
        final EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(2);
        e.setText(initial);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(e)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString();
                        if (!s.trim().isEmpty()) cb.text(s);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    interface TextCallback { void text(String s); }

    private void addText() {
        textDialog("New text", "", new TextCallback() {
            @Override public void text(String s) {
                beginEdit();
                Layer l = doc.newLayer(Layer.TEXT);
                l.text = s;
                l.name = s.split("\n")[0];
                if (l.name.length() > 24) l.name = l.name.substring(0, 24) + "…";
                l.fillColor = 0xffffffff;
                l.textSize = Math.max(12, Math.round(Math.min(doc.cropW, doc.cropH) * 0.09f));
                l.cx = doc.cropCx;
                l.cy = doc.cropCy;
                l.rot = doc.angle();
                l.scale = 1;
                TextRender.refreshPreview(l);
                insertLayer(l);
                selectedId = l.id;
                setTab(T_EDIT);
            }
        });
    }

    private void editText() {
        final Layer l = selectedLayer();
        if (l == null || l.type != Layer.TEXT) return;
        textDialog("Edit text", l.text, new TextCallback() {
            @Override public void text(String s) {
                beginEdit();
                Layer cur = doc.byId(l.id);
                if (cur == null) return;
                cur.text = s;
                TextRender.refreshPreview(cur);
                changed();
                rebuildPanel();
            }
        });
    }

    private void fontDialog() {
        final Layer l = selectedLayer();
        if (l == null || l.type != Layer.TEXT) return;
        FontPicker.show(act, l.font, l.text, new Pickers.Pick() {
            @Override public void picked(String key) { setFont(key); }
            @Override public void importNew() { act.pickFont(); }
        });
    }

    @Override public void fontImported(String key) {
        if (pendingMaskFont) { pendingMaskFont = false; MaskPanel.setFont(maskHost, key); return; }
        setFont(key);
    }

    /** 3.15: the font being imported is for the selected text mask, not a text layer. */
    private boolean pendingMaskFont;

    void setFont(String key) {
        Layer l = selectedLayer();
        if (l == null || l.type != Layer.TEXT) return;
        beginEdit();
        l.font = key;
        TextRender.refreshPreview(l);
        changed();
        rebuildPanel();
    }

    private void textPanel() {
        section("Text");
        final Layer l = selectedLayer();
        LinearLayout head = Ui.row(act);
        head.setPadding(0, 0, Ui.dp(12), 0);
        head.addView(Ui.label(act, "Text"), Ui.lp(0, -2, 1f));
        panel.addView(head);
        TextView body = Ui.button(act, l.text.length() > 60 ? l.text.substring(0, 60) + "…" : l.text, new View.OnClickListener() {
            @Override public void onClick(View v) { editText(); }
        });
        body.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(4));
        panel.addView(body, blp);
        section("Typography");
        TextView fontBtn = Ui.button(act, "Font: " + TextRender.displayName(l.font), new View.OnClickListener() {
            @Override public void onClick(View v) { fontDialog(); }
        });
        fontBtn.setTypeface(TextRender.typeface(l.font, false, false));
        fontBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        panel.addView(fontBtn, blp);
        panel.addView(Ui.strip(act,
                Ui.chip(act, "Bold", l.bold, new View.OnClickListener() { @Override public void onClick(View v) { textToggle(0); } }),
                Ui.chip(act, "Italic", l.italic, new View.OnClickListener() { @Override public void onClick(View v) { textToggle(1); } }),
                Ui.chip(act, "Left", l.align == 0, new View.OnClickListener() { @Override public void onClick(View v) { textAlign(0); } }),
                Ui.chip(act, "Center", l.align == 1, new View.OnClickListener() { @Override public void onClick(View v) { textAlign(1); } }),
                Ui.chip(act, "Right", l.align == 2, new View.OnClickListener() { @Override public void onClick(View v) { textAlign(2); } }),
                Ui.chip(act, "Color", false, new View.OnClickListener() { @Override public void onClick(View v) { colorDialog(); } }),
                moveChip()));
        float maxSize = Math.max(doc.imgW, doc.imgH) * 0.6f;
        panel.addView(slider("Size", 4, maxSize, 200, l.textSize, 1, 0, " px", new Setter() {
            @Override public void set(float v) { Layer c = selectedLayer(); c.textSize = v; TextRender.refreshPreview(c); }
        }));
        panel.addView(slider("Tracking", -0.15f, 1f, 0, l.tracking, 1000, 0, "", new Setter() {
            @Override public void set(float v) { Layer c = selectedLayer(); c.tracking = v; TextRender.refreshPreview(c); }
        }));
        panel.addView(slider("Line spacing", 0.5f, 2.5f, 1, l.leading, 1, 2, "×", new Setter() {
            @Override public void set(float v) { Layer c = selectedLayer(); c.leading = v; TextRender.refreshPreview(c); }
        }));
        panel.addView(hint("Text stays vector: it is re-rendered at full resolution on export. Two fingers with Move on scale and rotate it; blend modes, masks and range work like any layer."));
    }

    private void textToggle(int which) {
        Layer l = selectedLayer();
        beginEdit();
        if (which == 0) l.bold = !l.bold; else l.italic = !l.italic;
        TextRender.refreshPreview(l);
        changed();
        rebuildPanel();
    }

    private void textAlign(int a) {
        Layer l = selectedLayer();
        beginEdit();
        l.align = a;
        TextRender.refreshPreview(l);
        changed();
        rebuildPanel();
    }

    // ================================================================ Edit panel
    interface Setter { void set(float v); }

    /** Slider bound to the selected layer, with one undo step per gesture. */
    private Ui.Slider slider(String name, float min, float max, float def, float val, float scale, int dec, String unit, final Setter s) {
        Ui.Slider sl = new Ui.Slider(act, name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { beginEdit(); }
            @Override public void change(float v) {
                if (selectedLayer() == null) return;
                s.set(v);
                changed();
            }
        });
        sl.display(scale, dec, unit);
        return sl;
    }

    // ---------------------------------------------------------------- Quick adjust (audit P1)
    /** The not-yet-created base correction Quick adjust shows for photo pendingBaseFor (null = none). */
    private Layer pendingBase;
    private int pendingBaseFor;
    /** The next canvas tap samples a neutral point for the base / selected Light & Color layer. */
    private boolean neutralPick;

    /** The Light & Color layer Quick adjust made for this photo, if any. */
    private Layer baseFor(Layer photo) {
        for (Layer k : doc.layers) if (k.type == Layer.BASIC && k.baseOf == photo.id) return k;
        return null;
    }

    /** What the adjustment controls edit: the selected layer, or the pending base correction. */
    Layer adjLayer() {
        Layer s = selectedLayer();
        if (pendingBase != null && (s == null || s.id == pendingBaseFor) && tab == T_EDIT) return pendingBase;
        return s;
    }

    /**
     * First change in Quick adjust: the base correction becomes a real layer directly above its
     * photo (same group), and is selected. Called right after the undo step is opened, so Undo
     * takes the whole correction away.
     */
    private void materializeBase() {
        if (pendingBase == null || adjLayer() != pendingBase) return;
        Layer photo = doc.byId(pendingBaseFor);
        Layer b = pendingBase;
        pendingBase = null;
        if (photo == null) return;
        b.parent = photo.parent;
        doc.layers.add(doc.indexOf(photo.id) + 1, b);
        selectedId = b.id;
    }

    /**
     * Quick adjust (Lightroom-style direct editing): with a photo selected (or nothing), the
     * Adjust tab shows Light & Color straight away. The first change creates a "Light & Color"
     * layer just above that photo, which keeps everything editable, maskable and reorderable.
     */
    private void quickAdjust(Layer photo) {
        Layer b = baseFor(photo);
        if (b != null) {
            selectedId = b.id;   // its base correction already exists: edit that
            pendingBase = null;
        } else if (pendingBase == null || pendingBaseFor != photo.id) {
            pendingBase = doc.newLayer(Layer.BASIC);
            pendingBase.name = "Light & Color";
            pendingBase.baseOf = photo.id;
            pendingBaseFor = photo.id;
            selectedId = photo.id;
        }
        panel.addView(Ui.label(act, "Quick adjust \u00b7 " + photo.name));
        adj.build(panel);
        panel.addView(Ui.label(act, "More"));
        panel.addView(Ui.strip(act, addAdjChip(Layer.CURVES), addAdjChip(Layer.LEVELS), addAdjChip(Layer.HSL), addAdjChip(Layer.LUT), addAdjChip(Layer.DETAIL)));
        panel.addView(hint(b != null ? "This photo's base correction: a Light & Color layer just above it in Layers (mask it, lower it, or add more corrections)."
                : "Your first change adds a Light & Color layer just above this photo. It stays editable in Layers."));
    }

    /** White balance: the next tap on the photo picks something that should be neutral. */
    void pickNeutral() {
        neutralPick = true;
        applyTool();
        toast("Touch the photo and drag the loupe onto something grey or white, then lift. Two fingers pan.");
    }

    private void editPanel() {
        final Layer l = selectedLayer();
        if (l != null && l.type == Layer.TEXT) { textPanel(); return; }
        if (l == null || l.type == Layer.IMAGE) {
            Layer photo = l != null ? l : targetPhoto();
            if (photo != null && photo.type == Layer.IMAGE) { quickAdjust(photo); return; }
        }
        if (l == null || !l.isAdjustment()) {
            panel.addView(adj.hist(-1));
            panel.addView(hint("Select an adjustment or text layer to edit it. Adjustments are layers: they recolor everything below them, can be masked, blended and reordered, and stay editable."));
            panel.addView(Ui.strip(act, addAdjChip(Layer.BASIC), addAdjChip(Layer.CURVES), addAdjChip(Layer.LEVELS),
                    addAdjChip(Layer.HSL), addAdjChip(Layer.LUT), addAdjChip(Layer.DETAIL)));
            return;
        }
        adj.build(panel);
    }

    /** A LUT file was imported: apply it to the selected LUT layer, or add a LUT layer. */
    @Override public void lutImported(String name) {
        Layer l = selectedLayer();
        beginEdit();
        if (l == null || l.type != Layer.LUT) {
            l = doc.newLayer(Layer.LUT);
            insertLayer(l);
            selectedId = l.id;
        }
        l.lutFile = name;
        l.name = Lut.displayName(name);
        tab = T_EDIT;
        buildTabs();
        changed();
        rebuildPanel();
    }

    // ================================================================ Retouch
    Layer activeRetouch() {
        Layer l = selectedLayer();
        return l != null && l.type == Layer.RETOUCH ? l : null;
    }

    Retouch.Stroke selectedStroke() {
        Layer r = activeRetouch();
        return r != null && selStroke >= 0 && selStroke < r.strokes.size() ? r.strokes.get(selStroke) : null;
    }

    /** The photo layer retouching and Remove work on. */
    Layer targetPhoto() {
        Layer s = selectedLayer();
        if (s != null && s.type == Layer.IMAGE) return s;
        int i = doc.indexOf(selectedId);
        Layer t = s != null && s.type == Layer.RETOUCH ? Doc.retouchTargetOf(doc, s)
                : Doc.retouchTarget(doc, i < 0 ? doc.layers.size() : i + 1);
        if (t == null) for (Layer l : doc.layers) if (l.type == Layer.IMAGE) return l;
        return t;
    }

    /** Selected retouch layer, or a new one right above the target photo. Call after beginEdit. */
    private Layer ensureRetouch() {
        Layer r = activeRetouch();
        if (r != null) return r;
        Layer t = targetPhoto();
        if (t == null) return null;
        r = doc.newLayer(Layer.RETOUCH);
        doc.layers.add(doc.indexOf(t.id) + 1, r);
        selectedId = r.id;
        return r;
    }

    Retouch.Sampler sampler(final Layer t) {
        final Bitmap pv = t.preview;
        if (pv == null) return null;
        final float[] r0 = new float[3], r1 = new float[3];
        t.layerRows(r0, r1);
        final float sx = pv.getWidth() / (float) t.srcW, sy = pv.getHeight() / (float) t.srcH;
        final int pw = pv.getWidth(), ph = pv.getHeight();
        return new Retouch.Sampler() {
            @Override public int rgb(float x, float y) {
                float lx = r0[0] * x + r0[1] * y + r0[2], ly = r1[0] * x + r1[1] * y + r1[2];
                if (lx < 0 || ly < 0) return -1;
                int px = (int) (lx * sx), py = (int) (ly * sy);
                if (px >= pw || py >= ph) return -1;
                int c = pv.getPixel(px, py);
                return (c >>> 24) < 128 ? -1 : c & 0xffffff;
            }
        };
    }

    void retouchStroke(float[] raw, float rad) {
        if (targetPhoto() == null) { toast("Retouching works on a photo layer. Add or select one first."); return; }
        beginEdit();
        Layer r = ensureRetouch();
        doc.syncRetouch();   // a new layer gets its target (and the target's placement) now
        Layer t = Doc.retouchTargetOf(doc, r);
        if (t == null) { doUndo(); toast("Move the retouch layer above a photo layer."); return; }
        float[] pts = Retouch.prepare(raw, rad);
        Retouch.Sampler s = sampler(t);
        float[] off = s != null ? Retouch.autoSource(pts, rad, s) : new float[]{rad * 2.5f, 0};
        r.strokes.add(new Retouch.Stroke(pts, rad, retouchFeather, off[0], off[1], newHeal));
        selStroke = r.strokes.size() - 1;
        changed();
        rebuildPanel();
    }

    void retouchTap(float x, float y, float rad) {
        Layer r = activeRetouch();
        if (r != null) for (int i = r.strokes.size() - 1; i >= 0; i--) {
            Retouch.Stroke s = r.strokes.get(i);
            if (s.distTo(x, y) <= s.rad) {
                selStroke = i;
                cv.invalidate();
                rebuildPanel();
                return;
            }
        }
        retouchStroke(new float[]{x, y}, rad);
    }

    void setStrokeOffset(float ox, float oy) {
        Layer r = activeRetouch();
        Retouch.Stroke s = selectedStroke();
        if (s == null) return;
        r.strokes.set(selStroke, s.withOffset(ox, oy));
        changed();
    }

    void addRemoveStroke(float[] pts, float rad) {
        float[] rs = new float[pts.length + 1];
        rs[0] = rad;
        System.arraycopy(pts, 0, rs, 1, pts.length);
        removeStrokes.add(rs);
        cv.invalidate();
        rebuildPanel();
    }

    private TextView retouchToolChip(String name, final int tool, final boolean heal) {
        boolean on = retouchTool == tool && (tool != CanvasView.HEAL || newHeal == heal);
        return Ui.chip(act, name, on, new View.OnClickListener() {
            @Override public void onClick(View v) {
                retouchTool = tool;
                if (tool == CanvasView.HEAL) newHeal = heal;
                rebuildPanel();
            }
        });
    }

    private void retouchPanel() {
        section("Brush");
        Layer t = targetPhoto();
        panel.addView(Ui.label(act, "Retouch"));
        panel.addView(Ui.strip(act,
                retouchToolChip("Heal", CanvasView.HEAL, true),
                retouchToolChip("Clone", CanvasView.HEAL, false),
                retouchToolChip("Remove", CanvasView.REMOVE, false),
                retouchToolChip("Pan", CanvasView.NAV, false)));
        if (t == null) { panel.addView(hint("Retouching needs a photo layer.")); return; }
        Ui.Slider size = new Ui.Slider(act, "Brush size", 6, 600, 60, cv.brushSize, new Ui.Slider.Listener() {
            @Override public void begin() {}
            @Override public void change(float v) { cv.brushSize = v; }
        });
        size.display(1, 0, " px");
        panel.addView(size);
        if (retouchTool == CanvasView.HEAL) {
            Ui.Slider fe = new Ui.Slider(act, "Feather", 0, 1, 0.35f, retouchFeather, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { retouchFeather = v; }
            });
            fe.display(100, 0, "%");
            panel.addView(fe);
            panel.addView(hint((newHeal ? "Heal blends the borrowed texture into the surrounding tone and color. "
                    : "Clone copies the source exactly. ")
                    + "Paint over a blemish, wire or spot, or just tap it. A source is chosen automatically; drag the amber handle to pick another. Tap an earlier stroke to select it."));
            final Retouch.Stroke s = selectedStroke();
            if (s != null) {
                section("Selected stroke");
                panel.addView(Ui.label(act, "Selected stroke"));
                panel.addView(Ui.strip(act,
                        Ui.chip(act, "Heal", s.heal, new View.OnClickListener() { @Override public void onClick(View v) { strokeHeal(true); } }),
                        Ui.chip(act, "Clone", !s.heal, new View.OnClickListener() { @Override public void onClick(View v) { strokeHeal(false); } }),
                        Ui.chip(act, "Auto source", false, new View.OnClickListener() { @Override public void onClick(View v) { strokeAutoSource(); } }),
                        Ui.chip(act, "Delete", false, new View.OnClickListener() { @Override public void onClick(View v) { strokeDelete(); } })));
            }
            Layer r = activeRetouch();
            if (r != null && !r.strokes.isEmpty() && s == null)
                panel.addView(hint(r.strokes.size() + " strokes on this layer. Hide the layer to compare, or lower its opacity to soften all of them."));
        } else if (retouchTool == CanvasView.REMOVE) {
            panel.addView(hint("Paint over whatever should disappear (generously, including its shadow), then Fill. Content-aware fill rebuilds the area from the rest of the photo and adds it as a new layer you can mask, lower or delete."));
            TextView fill = Ui.chip(act, "Fill", true, new View.OnClickListener() { @Override public void onClick(View v) { runFill(); } });
            if (removeStrokes.isEmpty()) fill.setAlpha(0.4f);
            panel.addView(Ui.strip(act, fill,
                    Ui.chip(act, "Clear", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { removeStrokes.clear(); cv.invalidate(); rebuildPanel(); }
                    })));
        }
        panel.addView(hint("Works on: " + t.name));
    }

    private void strokeHeal(boolean heal) {
        Layer r = activeRetouch();
        Retouch.Stroke s = selectedStroke();
        if (s == null || s.heal == heal) return;
        beginEdit();
        r.strokes.set(selStroke, s.withHeal(heal));
        changed();
        rebuildPanel();
    }

    private void strokeAutoSource() {
        Layer r = activeRetouch();
        Retouch.Stroke s = selectedStroke();
        if (s == null) return;
        Layer t = Doc.retouchTargetOf(doc, r);
        Retouch.Sampler sm = t == null ? null : sampler(t);
        if (sm == null) return;
        float[] off = Retouch.autoSource(s.pts, s.rad, sm);
        beginEdit();
        r.strokes.set(selStroke, s.withOffset(off[0], off[1]));
        changed();
    }

    private void strokeDelete() {
        Layer r = activeRetouch();
        if (selectedStroke() == null) return;
        beginEdit();
        r.strokes.remove(selStroke);
        selStroke = -1;
        changed();
        rebuildPanel();
    }

    /** Content-aware fill of the painted remove strokes, added as a new layer above the photo. */
    private void runFill() { runFill(null); }

    /** selHole != null: fill the selection (mask res, image space) instead of the remove strokes. */
    private void runFill(final Bitmap selHole) {
        if ((selHole == null && removeStrokes.isEmpty()) || busy) return;
        final Layer t = targetPhoto();
        if (t == null || t.src == null) { toast("Remove works on a photo layer."); return; }
        float[] hb = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        double holeArea = 0;
        final float hms = doc.maskScale();
        if (selHole != null) {
            byte[] hbts = alphaBytes(selHole);
            int[] bb = SelOps.bounds(hbts, selHole.getWidth(), selHole.getHeight());
            if (bb == null) { toast("The selection is empty."); return; }
            hb[0] = bb[0] / hms; hb[1] = bb[1] / hms; hb[2] = (bb[2] + 1) / hms; hb[3] = (bb[3] + 1) / hms;
            long sum = 0;
            for (byte v : hbts) sum += v & 255;
            holeArea = sum / 255.0 / (hms * hms);
        }
        for (float[] rs : selHole != null ? new ArrayList<float[]>() : removeStrokes) {
            float rad = rs[0];
            float[] pts = Arrays.copyOfRange(rs, 1, rs.length);
            float[] b = Retouch.bbox(pts);
            hb[0] = Math.min(hb[0], b[0] - rad); hb[1] = Math.min(hb[1], b[1] - rad);
            hb[2] = Math.max(hb[2], b[2] + rad); hb[3] = Math.max(hb[3], b[3] + rad);
            holeArea += Retouch.length(pts) * 2 * rad + Math.PI * rad * rad;
        }
        float m = Math.max(hb[2] - hb[0], hb[3] - hb[1]) * 0.75f + 32;
        final RectF lr = SourceCache.layerRect(t, new float[]{hb[0] - m, hb[1] - m, hb[2] + m, hb[3] + m});
        if (!lr.intersect(0, 0, t.srcW, t.srcH)) return;
        double holeL = holeArea / (t.scale * t.scale);
        double f = Math.min(1, Math.sqrt(6e6 / (lr.width() * (double) lr.height())));
        f = Math.min(f, Math.sqrt(400e3 / Math.max(1, holeL)));
        final float ff = (float) f;
        final ArrayList<float[]> strokes = selHole != null ? new ArrayList<float[]>() : new ArrayList<float[]>(removeStrokes);
        final Layer tc = t.copy();
        setBusy("Analyzing…");
        work.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Object[] reg = sources.regionBitmap(tc, lr, ff);
                    if (reg == null) throw new Exception("couldn't read the photo");
                    Bitmap src = (Bitmap) reg[0];
                    RectF rr = (RectF) reg[1];
                    final int w = Math.max(8, Math.round(lr.width() * ff)), h = Math.max(8, Math.round(lr.height() * ff));
                    Bitmap wb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Matrix mx = new Matrix();
                    mx.setScale(rr.width() / src.getWidth() * w / lr.width(), rr.height() / src.getHeight() * h / lr.height());
                    mx.postTranslate((rr.left - lr.left) * w / lr.width(), (rr.top - lr.top) * h / lr.height());
                    new Canvas(wb).drawBitmap(src, mx, new Paint(Paint.FILTER_BITMAP_FLAG));
                    if ((Boolean) reg[2]) src.recycle();
                    int[] px = new int[w * h];
                    wb.getPixels(px, 0, w, 0, 0, w, h);
                    wb.recycle();
                    // hole raster: image px -> photo layer px -> working px
                    Bitmap hm = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                    Canvas hc = new Canvas(hm);
                    float[] r0 = new float[3], r1 = new float[3];
                    tc.layerRows(r0, r1);
                    Matrix hx = new Matrix();
                    hx.setValues(new float[]{r0[0], r0[1], r0[2], r1[0], r1[1], r1[2], 0, 0, 1});
                    hx.postTranslate(-lr.left, -lr.top);
                    hx.postScale(w / lr.width(), h / lr.height());
                    hc.concat(hx);
                    Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    sp.setColor(0xff000000);
                    sp.setStrokeCap(Paint.Cap.ROUND);
                    sp.setStrokeJoin(Paint.Join.ROUND);
                    if (selHole != null) {
                        Matrix toImg = new Matrix();
                        toImg.setScale(1 / hms, 1 / hms);   // mask px -> image px, then the hole transform above
                        hc.drawBitmap(selHole, toImg, new Paint(Paint.FILTER_BITMAP_FLAG));
                    }
                    for (float[] rs : strokes) {
                        float rad = rs[0];
                        if (rs.length == 3) {
                            sp.setStyle(Paint.Style.FILL);
                            hc.drawCircle(rs[1], rs[2], rad, sp);
                        } else {
                            Path pth = new Path();
                            pth.moveTo(rs[1], rs[2]);
                            for (int i = 3; i + 1 < rs.length; i += 2) pth.lineTo(rs[i], rs[i + 1]);
                            sp.setStyle(Paint.Style.STROKE);
                            sp.setStrokeWidth(rad * 2);
                            hc.drawPath(pth, sp);
                        }
                    }
                    byte[] ab = alphaBytes(hm);
                    hm.recycle();
                    byte[] hole = new byte[w * h];
                    for (int i = 0; i < hole.length; i++) hole[i] = (byte) ((ab[i] & 255) > 8 ? 1 : 0);
                    int[] out = Inpaint.fill(px, hole, w, h, new Inpaint.Progress() {
                        @Override public void step(final float p) {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() { showStatus("Filling… " + Math.round(p * 100) + "%"); }
                            });
                        }
                        @Override public boolean cancelled() { return closed; }
                    });
                    if (out == null) throw new Exception(closed ? "cancelled" : "nothing to sample from");
                    // alpha: the hole plus a 2-3 px soft edge
                    byte[] al = new byte[w * h];
                    for (int i = 0; i < al.length; i++) al[i] = (byte) (hole[i] != 0 ? 255 : 0);
                    MaskOps.feather(al, w, h, 2);
                    int x0 = w, y0 = h, x1 = -1, y1 = -1;
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++) {
                            int a = Math.min(255, (al[y * w + x] & 255) * 2);
                            al[y * w + x] = (byte) a;
                            if (a > 0) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
                        }
                    if (x1 < 0) throw new Exception("empty area");
                    final int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
                    int[] argb = new int[cw * ch];
                    for (int y = 0; y < ch; y++)
                        for (int x = 0; x < cw; x++) {
                            int i = (y + y0) * w + x + x0;
                            argb[y * cw + x] = ((al[i] & 255) << 24) | (out[i] & 0xffffff);
                        }
                    Bitmap fb = Bitmap.createBitmap(argb, cw, ch, Bitmap.Config.ARGB_8888);
                    final String name = "src_fill_" + System.currentTimeMillis() + ".png";
                    FileOutputStream fo = new FileOutputStream(new File(dir, name));
                    try { fb.compress(Bitmap.CompressFormat.PNG, 100, fo); } finally { fo.close(); }
                    final Bitmap prev = Math.max(cw, ch) > MainActivity.LAYER_PREVIEW
                            ? Bitmap.createScaledBitmap(fb, Math.max(1, cw * MainActivity.LAYER_PREVIEW / Math.max(cw, ch)),
                            Math.max(1, ch * MainActivity.LAYER_PREVIEW / Math.max(cw, ch)), true) : fb;
                    final float lcx = lr.left + (x0 + cw / 2f) * lr.width() / w, lcy = lr.top + (y0 + ch / 2f) * lr.height() / h;
                    final float layerPerWork = lr.width() / w;
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setBusy(null);
                            if (!closed) addFillLayer(tc.id, name, cw, ch, prev, lcx, lcy, layerPerWork);
                        }
                    });
                    return;
                } catch (OutOfMemoryError oom) {
                    err = "not enough memory for an area this large";
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final String msg = "Fill failed: " + err;
                act.runOnUiThread(new Runnable() { @Override public void run() { setBusy(null); toast(msg); } });
            }
        });
    }

    private void addFillLayer(int targetId, String name, int w, int h, Bitmap prev, float lcx, float lcy, float layerPerWork) {
        Layer t = doc.byId(targetId);
        if (t == null) { toast("The photo layer was removed; fill discarded."); return; }
        beginEdit();
        Layer l = doc.newLayer(Layer.IMAGE);
        l.src = name;
        l.srcW = w;
        l.srcH = h;
        l.preview = prev;
        l.name = "Fill";
        float[] o = new float[2];
        t.layerToImg(lcx, lcy, o);
        l.cx = o[0];
        l.cy = o[1];
        l.scale = t.scale * layerPerWork;
        l.rot = t.rot;
        doc.layers.add(doc.indexOf(t.id) + 1, l);
        removeStrokes.clear();
        changed();
        rebuildPanel();
        toast("Filled. Added as layer \"Fill\" above " + t.name + ".");
    }

    // ================================================================ Mask panel
    // ---------------------------------------------------------------- mask stack (3.6, audit package 5)
    /** Selected stack entry of the masked layer maskSelFor: -1 = the painted part, k = shape k. */
    private int maskSel = -1, maskSelFor = -1;

    /** The selected shape of the selected layer's mask, or null. */
    MaskStack.Comp selShape() {
        Layer l = selectedLayer();
        if (l == null || l.mask == null || l.id != maskSelFor || maskSel < 0 || maskSel >= l.comps.size()) return null;
        return l.comps.get(maskSel);
    }

    /** A shape's handles moved (CanvasView): shapes are uniforms, so nothing is re-uploaded. */
    void shapeChanged() { changed(); }

    static void clearShapes(Layer l) {
        l.comps = new ArrayList<MaskStack.Comp>();
        l.paintOp = MaskStack.ADD;
        l.paintAt = 0;
    }

    /** The whole mask as you see it (paint and shapes), at the raster's size. */
    static byte[] flatMask(Layer l) {
        byte[] r = alphaBytes(l.mask);
        if (l.comps.isEmpty()) return r;
        return MaskStack.flatten(r, l.mask.getWidth(), l.mask.getHeight(), l.paintOp, l.paintAt, l.comps);
    }

    /**
     * A gradient drawn on the canvas (mask px a -> b) becomes an editable shape on top of the
     * stack (Lightroom adds new components on top). "New mask" clears the paint and the other
     * shapes first.
     */
    void addShape(int type, float ax, float ay, float bx, float by) {
        MaskStack.Comp c = new MaskStack.Comp();
        c.type = type;
        c.x0 = ax; c.y0 = ay;
        if (type == MaskStack.LINEAR) { c.x1 = bx; c.y1 = by; }
        else {
            float r = (float) Math.max(1, Math.hypot(bx - ax, by - ay));
            c.x1 = r; c.y1 = r;
            c.feather = 0.65f;   // the old raster radial's fall-off
        }
        addComp(c);
    }

    /**
     * 3.15: puts a new shape on top of the selected layer's mask (making a hide-all mask first if
     * there is none), combined the way the New mask / Add / Subtract / Overlap chips say. "New
     * mask" clears the paint and the other shapes first.
     */
    void addComp(MaskStack.Comp c) {
        Layer l = selectedLayer();
        if (l == null) return;
        if (l.mask == null) { addMask(false); if (gradCombine == 0) gradCombine = 1; }
        if (gradCombine != 0 && l.comps.size() >= MaskStack.MAX) {
            toast("A mask holds up to " + MaskStack.MAX + " shapes: delete one, or bake them into the paint.");
            return;
        }
        if (c.isRaster() && gradCombine != 0) {
            int r = 0;
            for (MaskStack.Comp x : l.comps) if (x.isRaster()) r++;
            if (r >= MaskStack.MAX_RASTER) {
                toast("A mask holds up to " + MaskStack.MAX_RASTER + " text and drawn shapes (rectangles, polygons, stars and gradients don't count).");
                return;
            }
        }
        Crash.note("add shape " + c.type + "/" + gradCombine);
        beginEdit();
        if (gradCombine == 0) {
            l.mask = Bitmap.createBitmap(l.mask.getWidth(), l.mask.getHeight(), Bitmap.Config.ALPHA_8);   // all hidden
            l.maskVer++;
            l.maskSavedVer = -1;
            clearShapes(l);
        }
        c.op = gradCombine == 2 ? MaskStack.SUB : gradCombine == 3 ? MaskStack.INTERSECT : MaskStack.ADD;
        c.sanitize();
        l.comps.add(c);
        maskSel = l.comps.size() - 1;
        maskSelFor = l.id;
        maskTool = CanvasView.MASK_SHAPE;
        if (gradCombine == 0) gradCombine = 1;   // the next shape adds to this mask
        changed();
        rebuildPanel();
    }

    /** 3.15: the Add a shape sheet; the shape appears in the middle of what you're looking at. */
    void shapeSheet() {
        MaskPanel.addSheet(act, new MaskPanel.Pick() { @Override public void kind(String k) { addShapeKind(k); } });
    }

    void addShapeKind(String kind) {
        Layer l = selectedLayer();
        if (l == null) return;
        if (kind.equals("freehand")) {
            if (l.mask == null) { addMask(false); gradCombine = 1; }
            maskTool = CanvasView.MASK_FREEHAND;
            rebuildPanel();
            toast("Draw around the area with one finger. Two fingers pan and zoom.");
            return;
        }
        if (kind.equals("text")) {
            Pickers.text(act, "Mask text", "", true, new Pickers.TextCb() {
                @Override public void text(String t) {
                    float[] v = cv.maskView();
                    addComp(MaskPanel.make("text", v[0], v[1], v[2], t));
                }
            });
            return;
        }
        float[] v = cv.maskView();
        addComp(MaskPanel.make(kind, v[0], v[1], v[2], null));
    }

    /** A freehand outline finished on the canvas (mask px). */
    void addFreehand(float[] pts, float tol) {
        float[] fh = MaskShapes.freehand(pts, tol);
        if (fh == null) { toast("Draw a closed outline around an area."); return; }
        MaskStack.Comp c = new MaskStack.Comp();
        c.type = MaskStack.SHAPE;
        c.feather = 0;
        c.x0 = fh[0]; c.y0 = fh[1]; c.x1 = fh[2]; c.y1 = fh[3];
        c.pts = new float[fh.length - 4];
        System.arraycopy(fh, 4, c.pts, 0, c.pts.length);
        addComp(c);
    }

    private void duplicateShape() {
        Layer l = selectedLayer();
        MaskStack.Comp c = selShape();
        if (l == null || c == null) return;
        if (l.comps.size() >= MaskStack.MAX) { toast("A mask holds up to " + MaskStack.MAX + " shapes."); return; }
        int r = 0;
        for (MaskStack.Comp x : l.comps) if (x.isRaster()) r++;
        if (c.isRaster() && r >= MaskStack.MAX_RASTER) { toast("A mask holds up to " + MaskStack.MAX_RASTER + " text and drawn shapes."); return; }
        beginEdit();
        MaskStack.Comp d = c.copy();
        float off = Math.max(8, Math.min(l.mask.getWidth(), l.mask.getHeight()) * 0.03f);
        d.moveBy(off, off);
        l.comps.add(maskSel + 1, d);
        if (l.paintAt > maskSel) l.paintAt++;
        maskSel = maskSel + 1;
        changed();
        rebuildPanel();
    }

    /** 3.15: the shared shape settings talk to the photo editor through this. */
    final MaskPanel.Host maskHost = new MaskPanel.Host() {
        @Override public android.app.Activity act() { return act; }
        @Override public MaskStack.Comp shape() { return selShape(); }
        @Override public int shapeIndex() { return maskSel; }
        @Override public void beginEdit() { Editor.this.beginEdit(); }
        @Override public void changed() { Editor.this.changed(); }
        @Override public void rebuild() { rebuildPanel(); }
        @Override public void move(int dir) { Layer l = selectedLayer(); if (l != null) moveEntry(l, maskSel, dir); }
        @Override public void delete() { Layer l = selectedLayer(); if (l != null) deleteShape(l, maskSel); }
        @Override public void duplicate() { duplicateShape(); }
        @Override public void extra(LinearLayout panel, MaskStack.Comp c) {}
        @Override public void importFont() { pendingMaskFont = true; act.pickFont(); }
    };

    /** The stack bottom first: -1 = the painted raster, k = shape k. */
    private static int[] stackOrder(Layer l) {
        int n = l.comps.size();
        int[] o = new int[n + 1];
        int j = 0;
        for (int k = 0; k <= n; k++) {
            if (k == Math.min(l.paintAt, n)) o[j++] = -1;
            if (k < n) o[j++] = k;
        }
        return o;
    }

    /** Moves stack entry (-1 paint, k shape) one place up (+1, applied later) or down. */
    private void moveEntry(Layer l, int entry, int dir) {
        int[] o = stackOrder(l);
        int p = -1;
        for (int i = 0; i < o.length; i++) if (o[i] == entry) p = i;
        int q = p + dir;
        if (p < 0 || q < 0 || q >= o.length) return;
        MaskStack.Comp moving = entry >= 0 ? l.comps.get(entry) : null;
        beginEdit();
        int t = o[p]; o[p] = o[q]; o[q] = t;
        ArrayList<MaskStack.Comp> nc = new ArrayList<MaskStack.Comp>();
        int pa = 0;
        for (int e : o) { if (e < 0) pa = nc.size(); else nc.add(l.comps.get(e)); }
        l.comps = nc;
        l.paintAt = pa;
        maskSel = moving == null ? -1 : nc.indexOf(moving);
        changed();
        rebuildPanel();
    }

    private void deleteShape(Layer l, int k) {
        if (k < 0 || k >= l.comps.size()) return;
        beginEdit();
        l.comps.remove(k);
        if (l.paintAt > k) l.paintAt--;
        if (l.comps.isEmpty()) clearShapes(l);
        maskSel = -1;
        maskTool = CanvasView.BRUSH;
        changed();
        rebuildPanel();
    }

    /** Shapes become pixels of the painted part (for tools that need pixels, or past 6 shapes). */
    private void bakeShapes() {
        Layer l = selectedLayer();
        if (l == null || l.mask == null || l.comps.isEmpty()) return;
        beginEdit();
        int w = l.mask.getWidth(), h = l.mask.getHeight();
        l.mask = alphaBitmap(flatMask(l), w, h);
        l.maskVer++;
        l.maskSavedVer = -1;
        clearShapes(l);
        maskSel = -1;
        maskTool = CanvasView.BRUSH;
        changed();
        rebuildPanel();
        toast("Shapes baked into the painted mask. Undo brings them back.");
    }

    /** The stack, top (applied last) first, like Lightroom's component list. Tap a row to edit it. */
    private void maskStack(final Layer l) {
        if (l.comps.isEmpty()) return;
        panel.addView(Ui.label(act, "Mask stack (top applies last)"));
        int[] o = stackOrder(l);
        for (int i = o.length - 1; i >= 0; i--) {
            final int e = o[i];
            boolean on = e < 0 ? maskTool == CanvasView.BRUSH : (maskTool == CanvasView.MASK_SHAPE && e == maskSel);
            String name = e < 0 ? "Painted" : l.comps.get(e).name(e);
            int op = e < 0 ? l.paintOp : l.comps.get(e).op;
            String tag = MaskStack.OP_NAMES[op] + (e >= 0 && l.comps.get(e).invert ? " \u00b7 inverted" : "");
            TextView row = Ui.text(act, name + "   \u00b7   " + tag, 14, on ? 0xff111111 : Ui.TEXT);
            row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));
            row.setBackground(Ui.round(on ? Ui.ACCENT : Ui.PANEL2, 8, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    maskSel = e;
                    maskSelFor = l.id;
                    maskTool = e >= 0 ? CanvasView.MASK_SHAPE : CanvasView.BRUSH;
                    inspector.focus(inspector.context,"Tools");
                    rebuildPanel();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(Ui.dp(12), Ui.dp(3), Ui.dp(12), Ui.dp(3));
            panel.addView(row, lp);
        }
        // the painted part's own settings when it's selected: its operation and place
        if (maskTool == CanvasView.BRUSH) {
            panel.addView(Ui.label(act, "Painted part"));
            panel.addView(opStrip(l.paintOp, new OpSet() { @Override public void set(int op) { Layer c = selectedLayer(); if (c != null) c.paintOp = op; } }));
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "up", "Up", false, new View.OnClickListener() { @Override public void onClick(View v) { moveEntry(l, -1, 1); } }),
                    Ui.chip(act, "down", "Down", false, new View.OnClickListener() { @Override public void onClick(View v) { moveEntry(l, -1, -1); } }),
                    Ui.chip(act, "Bake shapes into paint", false, new View.OnClickListener() { @Override public void onClick(View v) { bakeShapes(); } })));
            panel.addView(hint("The painted part sits in the stack like a shape: put it on top and set it to Subtract to brush away part of a gradient. Brush and Hide paint into it."));
        }
    }

    interface OpSet { void set(int op); }

    private View opStrip(int cur, final OpSet s) {
        View[] v = new View[3];
        for (int i = 0; i < 3; i++) {
            final int op = i;
            v[i] = Ui.chip(act, MaskStack.OP_NAMES[i], cur == i, new View.OnClickListener() {
                @Override public void onClick(View x) {
                    beginEdit();
                    s.set(op);
                    changed();
                    rebuildPanel();
                }
            });
        }
        return Ui.strip(act, v);
    }

    /** Settings of the selected shape (3.15: shared with the video editor). */
    private void shapePanel(final Layer l, final MaskStack.Comp c) {
        MaskPanel.header(panel, maskHost, c);
        MaskPanel.settings(panel, maskHost, c);
    }

    private void maskPanel() {
        final Layer l = selectedLayer();
        LinearLayout head = Ui.row(act);
        head.setPadding(0, 0, Ui.dp(12), 0);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(act, l == null ? "Mask" : "Mask · " + l.name), Ui.lp(0, -2, 1f));
        head.addView(Ui.chip(act, "Done", false, new View.OnClickListener() { @Override public void onClick(View v) { setTab(maskReturn); } }));
        panel.addView(head);
        if (l == null) { panel.addView(hint("Select a layer first.")); return; }
        section("Tools");
        panel.addView(maskViewStrip(l));
        if (l.mask == null) {
            panel.addView(hint("A mask decides where this layer shows. White reveals, black hides. Painting never destroys pixels."));
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "Add mask (reveal all)", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { addMask(true); }
                    }),
                    Ui.chip(act, "Add mask (hide all)", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { addMask(false); }
                    })));
        } else {
            if (l.id != maskSelFor) { maskSelFor = l.id; maskSel = -1; }
            if (maskTool == CanvasView.MASK_SHAPE && selShape() == null) maskTool = CanvasView.BRUSH;
            int t = maskTool;
            panel.addView(Ui.strip(act,
                    toolChip("Brush", CanvasView.BRUSH, t), toolChip("Linear", CanvasView.GRAD_LIN, t),
                    toolChip("Radial", CanvasView.GRAD_RAD, t),
                    Ui.chip(act, "mask", "Shape…", false, new View.OnClickListener() { @Override public void onClick(View v) { shapeSheet(); } }),
                    Ui.chip(act, "text", "Text…", false, new View.OnClickListener() { @Override public void onClick(View v) { addShapeKind("text"); } }),
                    toolChip("Draw", CanvasView.MASK_FREEHAND, t), toolChip("Pan", CanvasView.NAV, t)));
            if (t == CanvasView.MASK_FREEHAND)
                panel.addView(hint("Draw around an area with one finger: lift to close it. It becomes an editable shape you can move, size, feather and outline."));
            {
                String[] gn = {"New mask", "Add", "Subtract", "Overlap"};
                View[] gc = new View[4];
                for (int i = 0; i < 4; i++) {
                    final int q = i;
                    gc[i] = Ui.chip(act, gn[i], gradCombine == i, new View.OnClickListener() { @Override public void onClick(View v) { gradCombine = q; rebuildPanel(); } });
                }
                panel.addView(Ui.label(act, "New shapes and gradients"));
                panel.addView(Ui.strip(act, gc));
                if (t == CanvasView.GRAD_LIN || t == CanvasView.GRAD_RAD || t == CanvasView.MASK_FREEHAND)
                panel.addView(hint(gradCombine == 0 ? "The next shape starts a new mask (the painted part is cleared). It stays editable: drag its handles any time."
                        : gradCombine == 1 ? "Adds the shape on top of the mask (a sky gradient plus a radial on the subject). On a reveal-all mask it changes nothing: use New mask or Overlap."
                        : gradCombine == 2 ? "The shape hides part of what the mask shows." : "Keeps only where the mask and the shape overlap."));
            }
            section("Stack");maskStack(l);section("Tools");
            ArrayList<View> paint = new ArrayList<View>();
            paint.add(Ui.chip(act, "Reveal", !cv.erase, new View.OnClickListener() { @Override public void onClick(View v) { cv.erase = false; rebuildPanel(); } }));
            paint.add(Ui.chip(act, "Hide", cv.erase, new View.OnClickListener() { @Override public void onClick(View v) { cv.erase = true; rebuildPanel(); } }));
            if (l.maskK > 0 || doc.linkable(l))
                paint.add(Ui.chip(act, "link", l.maskK > 0 ? "Linked to layer" : "Fixed to canvas", l.maskK > 0,
                        new View.OnClickListener() { @Override public void onClick(View v) { toggleLink(); } }));
            panel.addView(Ui.strip(act, paint.toArray(new View[0])));
            if (t == CanvasView.MASK_SHAPE && selShape() != null) {
                shapePanel(l, selShape());
            } else if (t == CanvasView.BRUSH) {
                Ui.Slider size = new Ui.Slider(act, "Size", 4, 600, 60, cv.brushSize, new Ui.Slider.Listener() {
                    @Override public void begin() {}
                    @Override public void change(float v) { cv.brushSize = v; }
                });
                size.display(1, 0, " px");
                panel.addView(size);
                Ui.Slider hard = new Ui.Slider(act, "Hardness", 0, 1, 0.5f, cv.hardness, new Ui.Slider.Listener() {
                    @Override public void begin() {}
                    @Override public void change(float v) { cv.hardness = v; }
                });
                hard.display(100, 0, "%");
                panel.addView(hard);
                Ui.Slider flow = new Ui.Slider(act, "Flow", 0.05f, 1, 0.6f, cv.flow, new Ui.Slider.Listener() {
                    @Override public void begin() {}
                    @Override public void change(float v) { cv.flow = v; }
                });
                flow.display(100, 0, "%");
                panel.addView(flow);
                panel.addView(hint("Brush size is in screen pixels: zoom in for fine edges. Two fingers always pan and zoom."));
            } else if (t == CanvasView.GRAD_LIN || t == CanvasView.GRAD_RAD) {
                panel.addView(hint(t == CanvasView.GRAD_LIN
                        ? "Drag across the image: full effect where you start, fading to none where you stop. Then drag its handles to adjust it."
                        : "Drag out from the centre: full effect inside, fading to none at the edge. Then drag its handles to adjust it."));
            }
            boolean shapes = !l.comps.isEmpty();
            panel.addView(Ui.strip(act,
                    Ui.chip(act, shapes ? "Invert paint" : "Invert", false, new View.OnClickListener() { @Override public void onClick(View v) { invertMask(); } }),
                    Ui.chip(act, shapes ? "Paint: reveal all" : "Reveal all", false, new View.OnClickListener() { @Override public void onClick(View v) { fillMask(true); } }),
                    Ui.chip(act, shapes ? "Paint: hide all" : "Hide all", false, new View.OnClickListener() { @Override public void onClick(View v) { fillMask(false); } }),
                    Ui.chip(act, "Delete mask", false, new View.OnClickListener() { @Override public void onClick(View v) { deleteMask(); } })));
            section("Refine");
            panel.addView(Ui.label(act, "Refine"));
            Ui.Slider rad = new Ui.Slider(act, "Radius", 1, 64, 12, refineRadius, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { refineRadius = v; }
            });
            rad.display(1 / doc.maskScale(), 0, " px");   // radius is in canvas-mask px; shown as image px
            panel.addView(rad);
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "Feather", false, new View.OnClickListener() { @Override public void onClick(View v) { runMaskOp(false); } }),
                    Ui.chip(act, "Refine edge", false, new View.OnClickListener() { @Override public void onClick(View v) { runMaskOp(true); } })));
            panel.addView(hint("Refine edge snaps a rough mask edge to the real edge in the photo (hair, leaves) within the radius. Feather softens the whole mask."
                    + (l.comps.isEmpty() ? "" : " Both work on pixels, so the shapes are baked into the paint first (Undo brings them back).")));
        }
        if (l.type == Layer.RETOUCH) return;
        rangeSection(l);
    }

    /** Off / Painted / Affected: what the red overlay shows in mask mode. */
    private View maskViewStrip(Layer l) {
        String[] names = {"Overlay off", "Painted mask", "Affected area"};
        ArrayList<View> v = new ArrayList<View>();
        for (int i = 0; i < 3; i++) {
            final int q = i;
            if (i == 2 && l.type == Layer.RETOUCH) continue;   // retouch has no range or clipping
            v.add(Ui.chip(act, names[i], maskView == i, new View.OnClickListener() {
                @Override public void onClick(View x) { maskView = q; viewChanged(); rebuildPanel(); }
            }));
        }
        LinearLayout col = Ui.col(act);
        col.addView(Ui.strip(act, v.toArray(new View[0])));
        if (maskView == 2) col.addView(hint("Red = untouched. Affected area combines the painted mask, the Blend If range (tested on the image below this layer) and clipping."));
        return col;
    }

    private void rangeSection(final Layer l) {
        section("Blend range");
        panel.addView(Ui.label(act, "Range (Blend If)"));
        panel.addView(Ui.strip(act,
                rangeChip("Off", 0, l), rangeChip("Luminosity", 1, l), rangeChip("Color", 2, l),
                Ui.chip(act, "Invert", l.rangeInv, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); selectedLayer().rangeInv = !selectedLayer().rangeInv; changed(); rebuildPanel(); }
                })));
        if (l.rangeMode == 1) {
            String[] names = {"Darks fade in from", "Darks full at", "Lights full until", "Lights fade out at"};
            for (int i = 0; i < 4; i++) {
                final int k = i;
                panel.addView(slider(names[i], 0, 1, i < 2 ? 0 : 1, l.rangeL[i], 255, 0, "", new Setter() {
                    @Override public void set(float v) {
                        float[] r = selectedLayer().rangeL;
                        r[k] = v;
                        for (int j = k + 1; j < 4; j++) r[j] = Math.max(r[j], v);
                        for (int j = k - 1; j >= 0; j--) r[j] = Math.min(r[j], v);
                    }
                }));
            }
            panel.addView(hint("The layer applies only where the image below falls in this brightness range: e.g. darks 0/0 and lights 64/128 limits it to shadows. It updates live as you edit below it."));
        } else if (l.rangeMode == 2) {
            final int c = Color.rgb(Math.round(l.rangeC[0] * 255), Math.round(l.rangeC[1] * 255), Math.round(l.rangeC[2] * 255));
            TextView sw = Ui.chip(act, maskTool == CanvasView.SAMPLE ? "Drag on the image, lift to pick…" : "Pick color", maskTool == CanvasView.SAMPLE,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (maskTool != CanvasView.SAMPLE) { maskToolBeforeSample = maskTool; maskTool = CanvasView.SAMPLE; }
                            else maskTool = maskToolBeforeSample;
                            rebuildPanel();
                        }
                    });
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(c);
            dot.setStroke(Ui.dp(1), 0xffffffff);
            dot.setSize(Ui.dp(14), Ui.dp(14));
            sw.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            sw.setCompoundDrawablePadding(Ui.dp(6));
            panel.addView(Ui.strip(act, sw));
            panel.addView(slider("Tolerance", 0, 0.5f, 0.12f, l.rangeC[3], 200, 0, "", new Setter() {
                @Override public void set(float v) { selectedLayer().rangeC[3] = v; }
            }));
            panel.addView(slider("Softness", 0, 0.5f, 0.1f, l.rangeF, 200, 0, "", new Setter() {
                @Override public void set(float v) { selectedLayer().rangeF = v; }
            }));
            panel.addView(hint("The layer applies only to colors near the picked one. Pick from the photo, then widen Tolerance until the area is covered."));
        }
    }

    private TextView rangeChip(String name, final int mode, Layer l) {
        return Ui.chip(act, name, l.rangeMode == mode, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Layer cur = selectedLayer();
                if (cur == null || cur.rangeMode == mode) return;
                beginEdit();
                cur.rangeMode = mode;
                if (mode == 2 && maskTool != CanvasView.SAMPLE) { maskToolBeforeSample = maskTool; maskTool = CanvasView.SAMPLE; }
                if (mode != 2 && maskTool == CanvasView.SAMPLE) maskTool = maskToolBeforeSample;
                changed();
                rebuildPanel();
            }
        });
    }

    /**
     * Neutral pick: samples the composite just below the Light & Color layer (so its own white
     * balance doesn't feed back) and sets Temperature and Tint that make that spot grey.
     */
    private void sampleNeutral(final float ix, final float iy) {
        neutralPick = false;
        if (busy) return;
        beginEdit();
        materializeBase();
        Layer l = selectedLayer();
        if (l == null || l.type != Layer.BASIC) { applyTool(); toast("Pick neutral works on a Light & Color layer."); return; }
        final int id = l.id;
        final Doc snap = doc.copy();
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                float[] c0 = null;
                try { c0 = engine.sampleBelow(snap, id, ix, iy); } catch (Throwable ignored) {}
                final float[] c = c0;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        gl.requestRender();
                        if (closed) return;
                        Layer cur = doc.byId(id);
                        applyTool();
                        if (cur == null) return;
                        if (c == null) { toast("Nothing to sample there: tap inside the image."); changed(); rebuildPanel(); return; }
                        float[] wb = AdjPanels.neutralFor(c[0], c[1], c[2]);
                        cur.basic[0] = wb[0];
                        cur.basic[1] = wb[1];
                        changed();
                        rebuildPanel();
                        if (wb[2] > 0) toast("That colour cast is stronger than the sliders reach: corrected as far as they go.");
                    }
                });
            }
        });
    }

    /**
     * Colour pick for the colour range. Samples the image the range is tested on, the composite
     * just below this layer with every adjustment under it applied (5 x 5 px average), so the
     * colour you tap is the colour that gets selected. GL thread render, applied on the UI thread.
     */
    void sampleAt(final float ix, final float iy) {
        if (neutralPick) { sampleNeutral(ix, iy); return; }
        Layer l = selectedLayer();
        if (l == null || busy) return;
        final int id = l.id;
        final Doc snap = doc.copy();
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                float[] c0 = null;
                try { c0 = engine.sampleBelow(snap, id, ix, iy); } catch (Throwable ignored) {}
                final float[] c = c0;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        gl.requestRender();
                        if (closed) return;
                        Layer cur = doc.byId(id);
                        if (cur == null) return;
                        if (c == null) { toast("Nothing to sample there: tap inside the image."); return; }
                        beginEdit();
                        cur.rangeC[0] = c[0];
                        cur.rangeC[1] = c[1];
                        cur.rangeC[2] = c[2];
                        maskTool = maskToolBeforeSample;
                        changed();
                        rebuildPanel();
                    }
                });
            }
        });
    }

    private TextView toolChip(String name, final int tool, int cur) {
        return Ui.chip(act, name, tool == cur, new View.OnClickListener() {
            @Override public void onClick(View v) { maskTool = tool; rebuildPanel(); }
        });
    }

    private void addMask(boolean reveal) {
        Layer l = selectedLayer();
        if (l == null) return;
        beginEdit();
        l.mask = doc.newMaskFor(l, reveal);   // linked to the layer when it can move
        clearShapes(l);
        l.maskVer++;
        l.maskSavedVer = -1;
        cv.erase = reveal;
        if (maskTool == CanvasView.SAMPLE) maskTool = CanvasView.BRUSH;
        changed();
        rebuildPanel();
    }

    private void invertMask() {
        Bitmap m = beginMaskStroke();
        if (m == null) return;
        Bitmap inv = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.ALPHA_8);
        inv.eraseColor(0xff000000);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        new Canvas(inv).drawBitmap(m, 0, 0, p);
        selectedLayer().mask = inv;
        maskChanged();
    }

    private void fillMask(boolean reveal) {
        Bitmap m = beginMaskStroke();
        if (m == null) return;
        m.eraseColor(reveal ? 0xff000000 : 0);
        maskChanged();
    }

    private void deleteMask() {
        Layer l = selectedLayer();
        if (l == null || l.mask == null) return;
        beginEdit();
        l.mask = null;
        l.maskK = 0;
        clearShapes(l);
        l.maskVer++;
        changed();
        rebuildPanel();
    }

    /**
     * Linked (moves with the layer) <-> fixed to the canvas. Resamples the mask once into the
     * other frame; what you see doesn't change until the layer moves.
     */
    private void toggleLink() {
        Layer l = selectedLayer();
        if (l == null || l.mask == null || busy) return;
        boolean toLinked = l.maskK <= 0;
        if (toLinked && !doc.linkable(l)) return;
        beginEdit();
        double[] oldMap = l.maskAffine(doc.imgW, doc.imgH);
        if (toLinked) {
            byte[] canvas = alphaBytes(l.mask);
            l.maskK = doc.linkK(l);
            int[] sz = Doc.linkSize(l, l.maskK);
            l.mask = alphaBitmap(doc.canvasToLayerMask(canvas, l, sz[0], sz[1]), sz[0], sz[1]);
        } else {
            byte[] canvas = doc.layerMaskToCanvas(l);
            l.maskK = 0;
            l.mask = alphaBitmap(canvas, doc.maskW(), doc.maskH());
        }
        // shapes: old mask px -> image px -> new mask px
        MaskStack.transform(l.comps, MaskOps.then(MaskOps.invert(oldMap), l.maskAffine(doc.imgW, doc.imgH)));
        l.maskVer++;
        l.maskSavedVer = -1;
        changed();
        rebuildPanel();
        toast(toLinked ? "Mask linked: it moves, scales and rotates with " + l.name + "." : "Mask fixed to the canvas: it stays put when " + l.name + " moves.");
    }

    static byte[] alphaBytes(Bitmap a) {
        int w = a.getWidth(), h = a.getHeight(), rb = a.getRowBytes();
        ByteBuffer buf = ByteBuffer.allocate(rb * h);
        a.copyPixelsToBuffer(buf);
        byte[] raw = buf.array();
        if (rb == w) return raw;
        byte[] o = new byte[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(raw, y * rb, o, y * w, w);
        return o;
    }

    static Bitmap alphaBitmap(byte[] b, int w, int h) {
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int rb = a.getRowBytes();
        byte[] raw = b;
        if (rb != w) {
            raw = new byte[rb * h];
            for (int y = 0; y < h; y++) System.arraycopy(b, y * w, raw, y * rb, w);
        }
        a.copyPixelsFromBuffer(ByteBuffer.wrap(raw));
        return a;
    }

    /** Photo luminance at canvas-mask resolution, the guide for Refine edge. */
    private static byte[] guideBytes(Layer t, int w, int h, float ms) {
        return guideBytes(t, w, h, new double[]{ms, 0, 0, 0, ms, 0});
    }

    /** Photo luminance drawn through imgToMask (image px -> mask px), the guide for Refine edge. */
    private static byte[] guideBytes(Layer t, int w, int h, double[] imgToMask) {
        Bitmap g = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.setScale(t.srcW / (float) t.preview.getWidth(), t.srcH / (float) t.preview.getHeight());
        m.postTranslate(-t.srcW / 2f, -t.srcH / 2f);
        m.postScale(t.scale, t.scale);
        m.postRotate(t.rot);
        m.postTranslate(t.cx, t.cy);
        Matrix im = new Matrix();
        im.setValues(new float[]{(float) imgToMask[0], (float) imgToMask[1], (float) imgToMask[2],
                (float) imgToMask[3], (float) imgToMask[4], (float) imgToMask[5], 0, 0, 1});
        m.postConcat(im);
        new Canvas(g).drawBitmap(t.preview, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        byte[] out = new byte[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            g.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                out[y * w + x] = (byte) ((54 * ((c >> 16) & 255) + 183 * ((c >> 8) & 255) + 19 * (c & 255)) >> 8);
            }
        }
        g.recycle();
        return out;
    }

    private void runMaskOp(final boolean refine) {
        final Layer l = selectedLayer();
        if (l == null || l.mask == null || busy) return;
        final Layer t = targetPhoto();
        if (refine && (t == null || t.preview == null)) { toast("Refine edge needs a photo layer to follow."); return; }
        final Bitmap src = l.mask;
        final int id = l.id;
        final Layer flat = l.copy();   // shapes are baked into the result: feather and refine work on pixels
        final String stackKey = MaskStack.key(l.comps, l.paintOp, l.paintAt);
        // the slider is in canvas-mask px; a linked mask can have another px size
        final double[] imgToMask = l.maskAffine(doc.imgW, doc.imgH);
        final int r = Math.max(1, (int) Math.round(refineRadius / doc.maskScale() * MaskOps.scaleOf(imgToMask)));
        final Layer tc = t == null ? null : t.copy();
        setBusy(refine ? "Refining edge…" : "Feathering…");
        work.execute(new Runnable() {
            @Override public void run() {
                Bitmap nb = null;
                String err = null;
                try {
                    int w = src.getWidth(), h = src.getHeight();
                    byte[] m = flatMask(flat);
                    byte[] out;
                    if (refine) out = MaskOps.refine(guideBytes(tc, w, h, imgToMask), m, w, h, r, 0.0025f);
                    else { MaskOps.feather(m, w, h, r); out = m; }
                    nb = alphaBitmap(out, w, h);
                } catch (OutOfMemoryError e) {
                    err = "not enough memory";
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final Bitmap res = nb;
                final String er = err;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(null);
                        if (res == null) { toast("Mask operation failed: " + er); return; }
                        Layer cur = doc.byId(id);
                        if (cur == null || cur.mask != src || !stackKey.equals(MaskStack.key(cur.comps, cur.paintOp, cur.paintAt))) {
                            toast("The mask changed meanwhile; not applied.");
                            return;
                        }
                        beginEdit();
                        cur.mask = res;
                        clearShapes(cur);
                        cur.maskVer++;
                        cur.maskSavedVer = -1;
                        changed();
                    }
                });
            }
        });
    }

    // ================================================================ Select tab
    /** The selection: coverage at mask resolution in image space (like masks); null = nothing selected. */
    Bitmap sel;
    int selVer, selTool = CanvasView.SEL_RECT, selMode = SelOps.NEW, gradCombine;
    private float selAmount = 12;      // image px for feather / grow / shrink / smooth / refine
    private int wandTol = 32;
    private boolean wandContig = true;
    /** Set by "Effect inside selection": the next effect preview gets this as its mask. */
    private Bitmap pendingFxMask;

    /**
     * A selection change is a step in the one undo history (3.6; it had its own 8-step undo).
     * Selection bitmaps are replaced, never edited in place after this, so the snapshot keeps
     * the old one. It doesn't mark the project changed: selections aren't saved with it.
     */
    private void pushSelUndo() {
        if (previewBase != null) return;
        doc.sel = sel;
        undo.add(doc.copy());
        if (undo.size() > 60) undo.remove(0);
        redo.clear();
        trimHistory();
        updateUndoButtons();
    }

    /** Shows the new selection without recompositing the image (same doc version). */
    private void selRefresh() {
        selVer++;
        Engine.RS cur = engine.rs;
        if (cur != null) {
            Engine.RS s = new Engine.RS();
            s.ver = cur.ver; s.doc = cur.doc; s.cropMode = cur.cropMode;
            overlays(s);
            engine.rs = s;
        }
        gl.requestRender();
        cv.invalidate();
    }

    private void setSel(byte[] b) {
        pushSelUndo();
        sel = b == null || SelOps.empty(b) ? null : alphaBitmap(b, doc.maskW(), doc.maskH());
        selRefresh();
        rebuildPanel();
    }

    private byte[] selBytes() { return sel == null ? null : alphaBytes(sel); }

    private Bitmap selCopy() { return sel == null ? null : sel.copy(Bitmap.Config.ALPHA_8, true); }

    // ---- from the canvas
    Bitmap beginSelStroke() {
        pushSelUndo();
        // the brush builds up: New paints like Add (lifting your finger mustn't wipe the last stroke)
        sel = sel == null ? doc.newMask(false) : sel.copy(Bitmap.Config.ALPHA_8, true);
        return sel;
    }

    void selPainted() { selRefresh(); }

    void selStrokeDone() {
        if (sel != null && SelOps.empty(alphaBytes(sel))) sel = null;
        selRefresh();
        rebuildPanel();
    }

    void polyChanged() { cv.invalidate(); rebuildPanel(); }

    /** A closed shape (polygon in image px) combined into the selection by the current mode. */
    void selShape(final float[] imgPts) {
        if (imgPts.length < 6) return;
        final int w = doc.maskW(), h = doc.maskH(), mode = selMode;
        final float ms = doc.maskScale();
        final byte[] cur = selBytes();
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                    Path pa = new Path();
                    pa.moveTo(imgPts[0] * ms, imgPts[1] * ms);
                    for (int i = 2; i + 1 < imgPts.length; i += 2) pa.lineTo(imgPts[i] * ms, imgPts[i + 1] * ms);
                    pa.close();
                    Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    pp.setColor(0xff000000);
                    new Canvas(b).drawPath(pa, pp);
                    final byte[] out = SelOps.combine(cur, alphaBytes(b), mode);
                    b.recycle();
                    act.runOnUiThread(new Runnable() { @Override public void run() { if (!closed) setSel(out); } });
                } catch (Throwable e) {
                    toastUi("Selection failed: " + e.getMessage());
                }
            }
        });
    }

    /** Magic wand at an image point: similar colours of the photo layer, on a grid of at most 2048 px. */
    void selWand(final float ix, final float iy) {
        final Layer t = targetPhoto();
        if (t == null || t.preview == null) { toast("The wand needs a photo layer to sample."); return; }
        if (busy) return;
        final Layer tc = t.copy();
        final int w = doc.maskW(), h = doc.maskH(), mode = selMode, tol = wandTol;
        final boolean contig = wandContig;
        final float ms = doc.maskScale();
        final byte[] cur = selBytes();
        setBusy("Selecting\u2026");
        work.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                byte[] out = null;
                try {
                    float g = Math.min(1f, 2048f / Math.max(w, h));
                    int gw = Math.max(1, Math.round(w * g)), gh = Math.max(1, Math.round(h * g));
                    int[] px = colorGrid(tc, gw, gh, ms * g);
                    byte[] m = SelOps.wand(px, gw, gh, (int) (ix * ms * g), (int) (iy * ms * g), tol, contig);
                    if (gw != w || gh != h) m = SelOps.resize(m, gw, gh, w, h);
                    out = SelOps.combine(cur, m, mode);
                } catch (OutOfMemoryError e) { err = "not enough memory"; }
                catch (Throwable e) { err = e.getMessage(); }
                final byte[] res = out;
                final String er = err;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(null);
                        if (res == null) toast("Wand failed: " + er);
                        else if (!closed) setSel(res);
                    }
                });
            }
        });
    }

    /** The photo layer's preview drawn into image space at the given scale; outside the photo is transparent. */
    private static int[] colorGrid(Layer t, int w, int h, float k) {
        Bitmap g = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.setScale(t.srcW / (float) t.preview.getWidth(), t.srcH / (float) t.preview.getHeight());
        m.postTranslate(-t.srcW / 2f, -t.srcH / 2f);
        m.postScale(t.scale, t.scale);
        m.postRotate(t.rot);
        m.postTranslate(t.cx, t.cy);
        m.postScale(k, k);
        new Canvas(g).drawBitmap(t.preview, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[w * h];
        g.getPixels(px, 0, w, 0, 0, w, h);
        g.recycle();
        return px;
    }

    private void toastUi(final String s) { act.runOnUiThread(new Runnable() { @Override public void run() { toast(s); } }); }

    /** 0 feather, 1 grow, 2 shrink, 3 smooth, 4 refine edge (follows the photo's edges). */
    private void selOp(final int op) {
        if (sel == null || busy) return;
        final int w = sel.getWidth(), h = sel.getHeight();
        final float ms = doc.maskScale();
        final int r = Math.max(1, Math.round(selAmount * ms));
        final byte[] m = alphaBytes(sel);
        final Layer t = targetPhoto();
        if (op == 4 && (t == null || t.preview == null)) { toast("Refine edge needs a photo layer to follow."); return; }
        final Layer tc = t == null ? null : t.copy();
        setBusy(new String[]{"Feathering\u2026", "Growing\u2026", "Shrinking\u2026", "Smoothing\u2026", "Refining edge\u2026"}[op]);
        work.execute(new Runnable() {
            @Override public void run() {
                byte[] out = m;
                String err = null;
                try {
                    switch (op) {
                        case 0: MaskOps.feather(out, w, h, r); break;
                        case 1: SelOps.grow(out, w, h, r); break;
                        case 2: SelOps.grow(out, w, h, -r); break;
                        case 3: SelOps.smooth(out, w, h, r); break;
                        default: out = MaskOps.refine(guideBytes(tc, w, h, ms), m, w, h, r, 0.0025f); break;
                    }
                } catch (OutOfMemoryError e) { err = "not enough memory"; out = null; }
                catch (Throwable e) { err = e.getMessage(); out = null; }
                final byte[] res = out;
                final String er = err;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(null);
                        if (res == null) toast("Selection operation failed: " + er);
                        else if (!closed) setSel(res);
                    }
                });
            }
        });
    }

    private TextView selToolChip(String name, final int tool) {
        return Ui.chip(act, name, selTool == tool, new View.OnClickListener() {
            @Override public void onClick(View v) { selTool = tool; if (tool != CanvasView.SEL_POLY) cv.polyImg.clear(); applyTool(); rebuildPanel(); }
        });
    }

    private void selectPanel() {
        section("Tools");
        panel.addView(Ui.strip(act,
                selToolChip("Rectangle", CanvasView.SEL_RECT), selToolChip("Ellipse", CanvasView.SEL_ELL),
                selToolChip("Lasso", CanvasView.SEL_LASSO), selToolChip("Polygon", CanvasView.SEL_POLY),
                selToolChip("Brush", CanvasView.SEL_BRUSH), selToolChip("Wand", CanvasView.SEL_WAND),
                selToolChip("Pan", CanvasView.NAV)));
        View[] mc = new View[4];
        for (int i = 0; i < 4; i++) {
            final int q = i;
            mc[i] = Ui.chip(act, SelOps.MODE_NAMES[i], selMode == i, new View.OnClickListener() { @Override public void onClick(View v) { selMode = q; rebuildPanel(); } });
        }
        panel.addView(Ui.strip(act, mc));
        String tip;
        switch (selTool) {
            case CanvasView.SEL_RECT: tip = "Drag a rectangle."; break;
            case CanvasView.SEL_ELL: tip = "Drag an ellipse."; break;
            case CanvasView.SEL_LASSO: tip = "Draw around the area; lifting your finger closes the shape."; break;
            case CanvasView.SEL_POLY: tip = "Tap the corners; tap the first corner (orange) to close."; break;
            case CanvasView.SEL_BRUSH: tip = "Paint the selection. Subtract mode erases it."; break;
            case CanvasView.SEL_WAND: tip = "Tap a colour: similar colours of the photo are selected."; break;
            default: tip = "One finger pans, two fingers zoom."; break;
        }
        panel.addView(hint(tip + " Two fingers always pan and zoom. Red = not selected."));
        if (selTool == CanvasView.SEL_BRUSH) {
            Ui.Slider size = new Ui.Slider(act, "Brush size", 4, 600, 60, cv.brushSize, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { cv.brushSize = v; }
            });
            size.display(1, 0, " px");
            panel.addView(size);
            Ui.Slider hard = new Ui.Slider(act, "Hardness", 0, 1, 0.5f, cv.hardness, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { cv.hardness = v; }
            });
            hard.display(100, 0, "%");
            panel.addView(hard);
        } else if (selTool == CanvasView.SEL_WAND) {
            Ui.Slider tl = new Ui.Slider(act, "Tolerance", 0, 128, 32, wandTol, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { wandTol = Math.round(v); }
            });
            tl.display(1, 0, "");
            panel.addView(tl);
            panel.addView(Ui.strip(act, Ui.chip(act, wandContig ? "Connected area only" : "Everywhere in the photo", wandContig, new View.OnClickListener() {
                @Override public void onClick(View v) { wandContig = !wandContig; rebuildPanel(); }
            })));
        } else if (selTool == CanvasView.SEL_POLY && !cv.polyImg.isEmpty()) {
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "Close shape", false, new View.OnClickListener() { @Override public void onClick(View v) { cv.closePoly(); } }),
                    Ui.chip(act, "Undo corner", false, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            int n = cv.polyImg.size();
                            if (n >= 2) { cv.polyImg.remove(n - 1); cv.polyImg.remove(n - 2); }
                            polyChanged();
                        }
                    }),
                    Ui.chip(act, "Clear corners", false, new View.OnClickListener() { @Override public void onClick(View v) { cv.polyImg.clear(); polyChanged(); } })));
        }
        panel.addView(Ui.label(act, sel == null ? "Nothing selected" : "Selection"));
        ArrayList<View> base = new ArrayList<View>();
        base.add(Ui.chip(act, "All", false, new View.OnClickListener() {
            @Override public void onClick(View v) { byte[] b = new byte[doc.maskW() * doc.maskH()]; java.util.Arrays.fill(b, (byte) 255); setSel(b); }
        }));
        if (sel != null) {
            base.add(Ui.chip(act, "None", false, new View.OnClickListener() { @Override public void onClick(View v) { setSel(null); } }));
            base.add(Ui.chip(act, "Invert", false, new View.OnClickListener() { @Override public void onClick(View v) { byte[] b = selBytes(); SelOps.invert(b); setSel(b); } }));
        }
        panel.addView(Ui.strip(act, base.toArray(new View[0])));
        if (sel == null) {
            panel.addView(hint("Select part of the photo, then mask a layer with it, adjust or add an effect only inside it, cut it out as its own layer, or remove it with content-aware fill."));
            return;
        }
        section("Refine");
        Ui.Slider amt = new Ui.Slider(act, "Amount", 1, 200, 12, selAmount, new Ui.Slider.Listener() {
            @Override public void begin() {}
            @Override public void change(float v) { selAmount = v; }
        });
        amt.display(1, 0, " px");
        panel.addView(amt);
        panel.addView(Ui.strip(act,
                Ui.chip(act, "Feather", false, new View.OnClickListener() { @Override public void onClick(View v) { selOp(0); } }),
                Ui.chip(act, "Grow", false, new View.OnClickListener() { @Override public void onClick(View v) { selOp(1); } }),
                Ui.chip(act, "Shrink", false, new View.OnClickListener() { @Override public void onClick(View v) { selOp(2); } }),
                Ui.chip(act, "Smooth", false, new View.OnClickListener() { @Override public void onClick(View v) { selOp(3); } }),
                Ui.chip(act, "Refine edge", false, new View.OnClickListener() { @Override public void onClick(View v) { selOp(4); } })));
        section("Use selection");
        panel.addView(Ui.label(act, "Use the selection"));
        panel.addView(Ui.strip(act,
                Ui.chip(act, "mask", "Mask layer", false, new View.OnClickListener() { @Override public void onClick(View v) { maskFromSel(); } }),
                Ui.chip(act, "sliders", "Adjust inside", false, new View.OnClickListener() { @Override public void onClick(View v) { adjustInside(); } }),
                Ui.chip(act, "fx", "Effect inside", false, new View.OnClickListener() { @Override public void onClick(View v) { effectInside(); } })));
        panel.addView(Ui.strip(act,
                Ui.chip(act, "layers", "Cutout layer", false, new View.OnClickListener() { @Override public void onClick(View v) { cutoutFromSel(); } }),
                Ui.chip(act, "heal", "Remove (fill)", false, new View.OnClickListener() { @Override public void onClick(View v) { runFill(selCopy()); } })));
        Layer sl = selectedLayer();
        panel.addView(hint("Mask layer applies to: " + (sl == null ? "no layer selected" : sl.name)
                + ". Cutout and Remove work on the photo layer. Feather, Grow and the others use Amount."));
    }

    private void maskFromSel() {
        final Layer l = selectedLayer();
        if (l == null || sel == null) { toast("Select a layer on the Layers tab first."); return; }
        if (l.mask == null) { applySelMask(l.id, -1); return; }
        Sheet sh = new Sheet(act, l.name + " already has a mask");
        sh.item("mask", "Replace it with the selection", new Runnable() { @Override public void run() { applySelMask(l.id, -1); } });
        sh.item("plus", "Add the selection to it", new Runnable() { @Override public void run() { applySelMask(l.id, SelOps.ADD); } });
        sh.item("close", "Subtract the selection from it", new Runnable() { @Override public void run() { applySelMask(l.id, SelOps.SUBTRACT); } });
        sh.item("select", "Keep only the overlap", new Runnable() { @Override public void run() { applySelMask(l.id, SelOps.INTERSECT); } });
        sh.show();
    }

    private void applySelMask(int id, int mode) {
        Layer l = doc.byId(id);
        if (l == null || sel == null) return;
        beginEdit();
        byte[] s = selBytes();
        int w, h;
        if (l.mask == null || mode < 0) {
            // a fresh mask: linked when the layer can move (a placed photo, text, a retouch on one);
            // replacing a linked mask keeps its frame
            boolean keepLinked = l.mask != null && l.maskK > 0;
            if (!keepLinked) l.maskK = doc.linkable(l) ? doc.linkK(l) : 0;
            int[] sz = keepLinked ? new int[]{l.mask.getWidth(), l.mask.getHeight()}
                    : l.maskK > 0 ? Doc.linkSize(l, l.maskK) : new int[]{doc.maskW(), doc.maskH()};
            w = sz[0]; h = sz[1];
        } else { w = l.mask.getWidth(); h = l.mask.getHeight(); }
        byte[] sl = doc.canvasToLayerMask(s, l, w, h);   // the selection in this mask's frame
        // combining works on the whole mask as you see it, so shapes are baked into the paint first
        byte[] out = mode < 0 || l.mask == null ? sl : SelOps.combine(flatMask(l), sl, mode);
        l.mask = alphaBitmap(out, w, h);
        clearShapes(l);
        l.maskVer++;
        l.maskSavedVer = -1;
        changed();
        rebuildPanel();
        toast("Masked " + l.name + " with the selection.");
    }

    private void adjustInside() {
        if (sel == null) return;
        Sheet sh = new Sheet(act, "Adjust only inside the selection");
        int[] types = {Layer.BASIC, Layer.CURVES, Layer.LEVELS, Layer.HSL, Layer.LUT, Layer.DETAIL};
        String[] labels = {"Light & Color", "Curves", "Levels", "HSL", "LUT", "Detail"};
        for (int i = 0; i < types.length; i++) {
            final int ty = types[i];
            sh.tile(TYPE_ICONS[ty], labels[i], new Runnable() {
                @Override public void run() {
                    Bitmap mk = selCopy();
                    addAdjustment(ty);
                    Layer nl = selectedLayer();
                    if (nl != null && mk != null) { nl.mask = mk; clearShapes(nl); nl.maskVer++; nl.maskSavedVer = -1; changed(); rebuildPanel(); }
                }
            });
        }
        sh.show();
    }

    private void effectInside() {
        if (sel == null) return;
        Bitmap mk = selCopy();
        setTab(T_FX);
        pendingFxMask = mk;
        toast("Pick an effect: it previews inside the selection.");
    }

    /** The photo layer duplicated with the selection as its mask: a movable, editable cutout. */
    private void cutoutFromSel() {
        Layer t = targetPhoto();
        if (t == null || sel == null) { toast("Cutouts need a photo layer."); return; }
        selectedId = t.id;
        byte[] s = selBytes();
        duplicate();
        Layer c = selectedLayer();
        if (c == null || c.id == t.id) return;
        // the cutout's mask is linked to it, so moving or scaling the cutout carries its edge along
        c.maskK = doc.linkK(c);
        int[] sz = Doc.linkSize(c, c.maskK);
        byte[] m = doc.canvasToLayerMask(s, c, sz[0], sz[1]);
        if (t.mask != null) {
            Layer tm = t.copy();   // the photo's own mask, brought into the cutout's frame
            byte[] tf = flatMask(tm);   // shapes included
            byte[] own = tm.maskK > 0 ? doc.canvasToLayerMask(doc.layerMaskToCanvas(tf, tm.mask.getWidth(), tm.mask.getHeight(), tm), c, sz[0], sz[1])
                    : doc.canvasToLayerMask(tf, c, sz[0], sz[1]);
            m = SelOps.combine(own, m, SelOps.INTERSECT);
        }
        c.mask = alphaBitmap(m, sz[0], sz[1]);
        clearShapes(c);
        c.maskVer++;
        c.maskSavedVer = -1;
        c.name = t.name + " cutout";
        changed();
        rebuildPanel();
        toast("Cutout layer added above " + t.name + ". Move, scale and style it like any layer.");
    }

    // ================================================================ Crop panel
    private int aspectIdx;

    private void cropPanel() {
        panel.addView(Ui.label(act, "Aspect"));
        String[] names = {"Free", "Original", "1:1", "4:5", "5:4", "3:2", "2:3", "16:9", "9:16"};
        final float[] vals = {0, -1, 1, 0.8f, 1.25f, 1.5f, 2f / 3f, 16f / 9f, 9f / 16f};
        View[] chips = new View[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            chips[i] = Ui.chip(act, names[i], aspectIdx == i, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    aspectIdx = k;
                    float a2 = vals[k] < 0 ? originalAspect() : vals[k];
                    cv.cropAspect = a2;
                    if (a2 > 0) conformCrop(a2);
                    rebuildPanel();
                }
            });
        }
        panel.addView(Ui.strip(act, chips));
        Ui.Slider st = new Ui.Slider(act, "Straighten", -45, 45, 0, doc.straighten, new Ui.Slider.Listener() {
            @Override public void begin() { beginEdit(); }
            @Override public void change(float v) {
                float ow = doc.docW(true), oh = doc.docH(true);
                doc.straighten = v;
                cv.docResized(ow, oh, doc.docW(true), doc.docH(true));
                changed();
            }
        });
        st.display(1, 1, "°");
        panel.addView(st);
        panel.addView(Ui.strip(act,
                Ui.chip(act, "Rotate left", false, new View.OnClickListener() { @Override public void onClick(View v) { rotate90(-1); } }),
                Ui.chip(act, "Rotate right", false, new View.OnClickListener() { @Override public void onClick(View v) { rotate90(1); } }),
                Ui.chip(act, "Reset", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); doc.resetCrop(); cv.cropAspect = 0; aspectIdx = 0; cv.fit(); changed(); rebuildPanel(); }
                }),
                Ui.chip(act, "Done", true, new View.OnClickListener() { @Override public void onClick(View v) { setTab(T_LAYERS); } })));
        panel.addView(hint("Drag corners or edges, drag inside to move. The crop is non-destructive: the full image stays in the project."));
    }

    private float originalAspect() {
        return doc.rot90 % 2 == 0 ? doc.imgW / (float) doc.imgH : doc.imgH / (float) doc.imgW;
    }

    private void conformCrop(float aspect) {
        beginEdit();
        RectF r = cv.cropRectScreen();
        float w = r.width(), h = r.height(), cx = r.centerX(), cy = r.centerY();
        if (w / h > aspect) w = h * aspect; else h = w / aspect;
        cv.setCropFromScreen(new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
    }

    private void rotate90(int dir) {
        beginEdit();
        doc.rot90 = ((doc.rot90 + dir) % 4 + 4) % 4;
        float t = doc.cropW;
        doc.cropW = doc.cropH;
        doc.cropH = t;
        if (cv.cropAspect > 0) cv.cropAspect = 1f / cv.cropAspect;
        cv.fit();
        changed();
        rebuildPanel();
    }

    private TextView hint(String s) {
        TextView t = Ui.text(act, s, 12, Ui.DIM);
        t.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(4));
        return t;
    }

    // ================================================================ export
    private void exportDialog() {
        Crash.note("export dialog");
        if (previewBase != null) applyPreview();
        if (busy) return;
        if (cropMode) setTab(T_LAYERS);
        final int fw = Math.round(doc.cropW), fh = Math.round(doc.cropH);
        final int[] sizes = {0, 4096, 3000, 2048, 1080};
        final int[] sel = {0, 0, 1};
        final int[] qual = {100, 95, 90, 80};
        final LinearLayout box = Ui.col(act);
        box.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        final Runnable[] build = new Runnable[1];
        build[0] = new Runnable() {
            @Override public void run() {
                box.removeAllViews();
                box.addView(Ui.label(act, "Format"));
                box.addView(Ui.strip(act,
                        Ui.chip(act, "JPEG", sel[0] == 0, new View.OnClickListener() { @Override public void onClick(View v) { sel[0] = 0; build[0].run(); } }),
                        Ui.chip(act, "PNG (keeps transparency)", sel[0] == 1, new View.OnClickListener() { @Override public void onClick(View v) { sel[0] = 1; build[0].run(); } })));
                box.addView(Ui.label(act, "Size (long edge)"));
                ArrayList<View> sz = new ArrayList<View>();
                for (int i = 0; i < sizes.length; i++) {
                    if (i > 0 && sizes[i] >= Math.max(fw, fh)) continue;
                    final int k = i;
                    String n = i == 0 ? "Full " + fw + " × " + fh : sizes[i] + " px";
                    sz.add(Ui.chip(act, n, sel[1] == i, new View.OnClickListener() { @Override public void onClick(View v) { sel[1] = k; build[0].run(); } }));
                }
                box.addView(Ui.strip(act, sz.toArray(new View[0])));
                if (sel[0] == 0) {
                    box.addView(Ui.label(act, "JPEG quality"));
                    View[] q = new View[qual.length];
                    for (int i = 0; i < qual.length; i++) {
                        final int k = i;
                        q[i] = Ui.chip(act, String.valueOf(qual[i]), sel[2] == i, new View.OnClickListener() { @Override public void onClick(View v) { sel[2] = k; build[0].run(); } });
                    }
                    box.addView(Ui.strip(act, q));
                }
            }
        };
        build[0].run();
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Export to Gallery").setView(box)
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int ow = fw, oh = fh;
                        if (sel[1] > 0) {
                            float k = sizes[sel[1]] / (float) Math.max(fw, fh);
                            ow = Math.max(1, Math.round(fw * k));
                            oh = Math.max(1, Math.round(fh * k));
                        }
                        runExport(ow, oh, sel[0] == 1, qual[sel[2]]);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void runExport(final int ow, final int oh, final boolean png, final int quality) {
        if (exporting) return;
        exporting = true;
        busy = true;
        final Doc snap = doc.copy();
        showStatus("Preparing export…");
        Crash.note("export " + ow + "x" + oh + (png ? " png" : " jpeg q" + quality));
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                android.net.Uri saved = null;
                try {
                    String name = "IndependentVideo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    saved = exportStreaming(snap, ow, oh, png, quality, name);
                    msg = "Saved " + ow + " × " + oh + " to Pictures/Independent Video";
                    final java.util.ArrayList<String> low = new java.util.ArrayList<String>(lastDowngrades);
                    final android.net.Uri su = saved;
                    if (!low.isEmpty()) act.runOnUiThread(new Runnable() { @Override public void run() { downgradeNotice(su, low); } });
                } catch (Throwable t) {
                    Crash.note("export failed: " + t);
                    if (t instanceof OutOfMemoryError) msg = "Export failed: not enough memory for " + ow + " × " + oh + ". Try a smaller size.";
                    else msg = "Export failed: " + (t.getMessage() == null ? t.toString() : t.getMessage());
                }
                gl.requestRender();
                final String m = msg;
                final android.net.Uri u = saved;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        exporting = false;
                        busy = false;
                        showStatus(null);
                        if (u != null) act.exported(m, u, png ? "image/png" : "image/jpeg");
                        else toast(m);
                        scheduleDetail();
                    }
                });
            }
        }, "photo-export").start();
    }

    /** Filled by exportStreaming: sources read below full detail. */
    private final java.util.ArrayList<String> lastDowngrades = new java.util.ArrayList<String>();

    /** An export that couldn't use full detail everywhere says so, and lets you discard it. */
    private void downgradeNotice(final android.net.Uri u, java.util.ArrayList<String> low) {
        StringBuilder sb = new StringBuilder("Some layers couldn't be read at full detail, so they are softer than the rest:\n");
        for (String s : low) sb.append("\n\u2022 ").append(s);
        sb.append("\n\nExporting at a smaller size, or closing other apps first, usually avoids this.");
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Exported with lower detail")
                .setMessage(sb.toString())
                .setPositiveButton("Keep it", null)
                .setNegativeButton("Delete it", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { act.getContentResolver().delete(u, null, null); toast("Deleted."); } catch (Exception e) { toast("Couldn't delete: " + e.getMessage()); }
                    }
                }).show();
    }

    private void status(final String s) {
        act.runOnUiThread(new Runnable() { @Override public void run() { if (exporting) showStatus(s); } });
    }

    /**
     * Worker thread. Renders the export one band (row of tiles) at a time on the GL thread and
     * streams each band into the encoder, so memory is one or two bands, never the whole image:
     * at 200 MP that is ~130 MB instead of ~800 MB plus the encoder's own copy. While the GPU
     * renders band b the encoder works on band b-1. Leaving the app pauses the export; it
     * continues when the editor is back (the GL context is kept on pause).
     */
    private android.net.Uri exportStreaming(final Doc snap, final int ow, final int oh, final boolean png, int quality, String name) throws Exception {
        final Engine.ExportJob job = onGl(new GlCall<Engine.ExportJob>() {
            @Override public Engine.ExportJob call() throws Exception { return engine.beginExport(snap, ow, oh, !png); }
        });
        android.content.ContentResolver cr = act.getContentResolver();
        android.net.Uri uri = null;
        java.io.OutputStream os = null;
        ImageStream enc = null;
        Bitmap[] bands = new Bitmap[0];
        boolean ok = false;
        try {
            // two bands (render one while encoding the other) unless a band is very large
            long bandBytes = (long) ow * job.bandMax() * 4;
            int nb = job.bands > 1 && bandBytes <= (64L << 20) ? 2 : 1;
            bands = new Bitmap[nb];
            for (int i = 0; i < nb; i++) bands[i] = Bitmap.createBitmap(ow, job.bandMax(), Bitmap.Config.ARGB_8888);

            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name + (png ? ".png" : ".jpg"));
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Independent Video");
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("the gallery refused the file");
            java.io.OutputStream raw = cr.openOutputStream(uri);
            if (raw == null) throw new Exception("the gallery file couldn't be opened");
            os = new java.io.BufferedOutputStream(raw, 1 << 20);
            int threads = ImageStream.threadsFor();
            enc = png ? new PngStream(os, ow, oh, true, threads) : new JpegStream(os, ow, oh, quality, threads);

            int[] rowBuf = new int[ow * 16];
            for (int b = 0; b < job.bands; b++) {
                BandRender r = new BandRender(job, b, bands[b % nb]);
                r.start();
                if (nb == 2 && b > 0) encodeBand(enc, bands[(b - 1) % 2], job.bandH(b - 1), rowBuf, b - 1, job.bands);
                r.await();
                if (nb == 1) encodeBand(enc, bands[0], job.bandH(b), rowBuf, b, job.bands);
            }
            if (nb == 2) encodeBand(enc, bands[(job.bands - 1) % 2], job.bandH(job.bands - 1), rowBuf, job.bands - 1, job.bands);
            status("Finishing…");
            enc.finish();
            os.close();
            os = null;
            v.clear();
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            ok = true;
            lastDowngrades.clear();
            lastDowngrades.addAll(job.downgrades.values());
            return uri;
        } finally {
            if (enc != null) enc.shutdown();
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (!ok && uri != null) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            // GL events run in order: once endExport has run, no band render can still be
            // drawing into a band bitmap, so only then are they recycled
            boolean glDone = false;
            try {
                onGl(new GlCall<Object>() { @Override public Object call() { engine.endExport(job); return null; } });
                glDone = true;
            } catch (Exception ignored) {}
            if (glDone) for (Bitmap bm : bands) if (bm != null) bm.recycle();   // else the GC frees them
        }
    }

    private void encodeBand(ImageStream enc, Bitmap band, int rows, int[] buf, int b, int bands) throws Exception {
        status("Encoding part " + (b + 1) + " of " + bands + "…");
        int w = band.getWidth(), step = buf.length / w;
        for (int y = 0; y < rows; y += step) {
            if (closed) throw new Exception("the editor was closed");
            int n = Math.min(step, rows - y);
            band.getPixels(buf, 0, w, 0, y, w, n);   // unpremultiplied ARGB
            enc.rows(buf, 0, n);
        }
    }

    /** One band rendered by a GL-thread event; waits out pauses by re-queueing. */
    private final class BandRender {
        final Engine.ExportJob job;
        final int b;
        final Bitmap into;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile Throwable err;

        BandRender(Engine.ExportJob job, int b, Bitmap into) { this.job = job; this.b = b; this.into = into; }

        void start() {
            gl.queueEvent(new Runnable() {
                @Override public void run() {
                    try {
                        engine.renderBand(job, sources, b, into, new Engine.ExportProgress() {
                            @Override public void progress(float f, String msg) { status(msg); }
                        });
                    } catch (Throwable t) {
                        err = t;
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        void await() throws Exception {
            BandRender cur = this;
            while (true) {
                waitLatch(cur.done);
                if (!(cur.err instanceof Engine.NotCurrent)) break;
                status("Export paused — it continues when Independent Video is back on screen");
                Thread.sleep(300);
                cur = new BandRender(job, b, into);
                cur.start();
            }
            if (cur.err instanceof Exception) throw (Exception) cur.err;
            if (cur.err instanceof Error) throw (Error) cur.err;
        }
    }

    private interface GlCall<T> { T call() throws Exception; }

    /** Runs c on the GL thread and waits for it; retries while the context isn't current. */
    private <T> T onGl(final GlCall<T> c) throws Exception {
        while (true) {
            final Object[] res = new Object[2];
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            gl.queueEvent(new Runnable() {
                @Override public void run() {
                    try { res[0] = c.call(); } catch (Throwable t) { res[1] = t; } finally { done.countDown(); }
                }
            });
            waitLatch(done);
            if (res[1] instanceof Engine.NotCurrent) { Thread.sleep(300); continue; }
            if (res[1] instanceof Exception) throw (Exception) res[1];
            if (res[1] instanceof Error) throw (Error) res[1];
            @SuppressWarnings("unchecked") T t = (T) res[0];
            return t;
        }
    }

    private void waitLatch(java.util.concurrent.CountDownLatch l) throws Exception {
        while (!l.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
            if (closed) throw new Exception("the editor was closed");
        }
    }

    // ================================================================ persistence
    /** Writes doc.json, changed masks and (asynchronously) a thumbnail. Call on the UI thread. */
    @Override public void save() { save(true); }
    @Override public boolean saveForVersion() { applyPreview(); return save(false, false); }

    // Autosave: 4 s after the last change, so a crash loses seconds of work, not the session.
    private final Runnable autosaveRun = new Runnable() {
        @Override public void run() {
            if (closed || !dirty) return;
            if (busy || exporting || cv.touching || previewBase != null) { handler.postDelayed(this, 2000); return; }
            Crash.note("autosave");
            save(false, true);
        }
    };

    /** Mask file names: unique per save, so a save never overwrites a file doc.json still points to. */
    private long maskSeq = System.currentTimeMillis() % 1000000000L;
    /** Mask files doc.json.bak (the last cleanly loaded version) refers to: kept so the backup stays whole. */
    private HashSet<String> bakMasks;

    private HashSet<String> bakMasks() {
        if (bakMasks != null) return bakMasks;
        HashSet<String> out = new HashSet<String>();
        try {
            File bak = new File(dir, "doc.json.bak");
            if (bak.exists()) {
                org.json.JSONArray a = new JSONObject(Loader.readText(bak)).optJSONArray("layers");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (!o.optBoolean("hasMask")) continue;
                    out.add(o.has("maskFile") ? o.getString("maskFile") : "mask_" + o.optInt("id") + ".png");
                }
            }
        } catch (Exception ignored) {}
        bakMasks = out;
        return out;
    }

    /** Saves run one at a time, in order, off the UI thread (autosave) or inline (explicit saves). */
    private final java.util.concurrent.ExecutorService saveExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Mask files queued for writing: they count as existing until their save job has run. */
    private final java.util.Set<String> pendingMasks = java.util.Collections.synchronizedSet(new HashSet<String>());

    private void save(boolean thumb) { save(thumb, false); }

    /**
     * Saves the project. The snapshot (which masks changed, copies of them, the JSON text) is
     * taken here on the UI thread; PNG encoding, fsync and the rename happen in one job on
     * saveExec. Autosave runs async so painting never stalls on a big mask; an explicit save
     * (leaving, onPause) first waits for queued jobs, then saves inline, so it is on disk when
     * this returns.
     */
    private boolean save(boolean thumb, boolean async) {
        handler.removeCallbacks(autosaveRun);
        // a preview isn't part of the project until it's applied: save what was there before it
        final Doc doc = previewBase != null ? previewBase : this.doc;
        final HashSet<String> keep = new HashSet<String>(bakMasks());
        final ArrayList<Bitmap> bmps = new ArrayList<Bitmap>();
        final ArrayList<String> names = new ArrayList<String>();
        final ArrayList<Integer> ids = new ArrayList<Integer>();
        final ArrayList<Boolean> copies = new ArrayList<Boolean>();
        final String json;
        try {
            // 1. changed masks get new file names; the files the current doc.json names are untouched
            for (Layer l : doc.layers) {
                if (l.mask == null) continue;
                String n = l.maskFile;
                if (n == null || l.maskSavedVer != l.maskVer || !(pendingMasks.contains(n) || new File(dir, n).exists())) {
                    n = "mask_" + l.id + "_" + (++maskSeq) + ".png";
                    Bitmap b = l.mask;
                    boolean copy = false;
                    if (async) {   // painting continues while the job encodes: give it its own pixels
                        try { b = l.mask.copy(Bitmap.Config.ALPHA_8, false); copy = b != null; } catch (OutOfMemoryError e) { b = null; }
                        if (b == null) { for (Bitmap c : bmps) if (c != null) c.recycle(); return save(thumb, false); }
                    }
                    bmps.add(b); names.add(n); ids.add(l.id); copies.add(copy);
                    pendingMasks.add(n);
                    l.maskFile = n;
                    l.maskSavedVer = l.maskVer;
                    Layer live = this.doc.byId(l.id);   // saving the pre-preview doc: the live layer shares the bitmap
                    if (live != null && live != l && live.mask == l.mask) { live.maskFile = n; live.maskSavedVer = live.maskVer; }
                }
                keep.add(n);
            }
            json = doc.toJson().toString();
        } catch (Exception e) {
            toast("Couldn't save project: " + e.getMessage());
            return false;
        }
        dirty = false;
        final boolean[] saved = {false};
        Runnable job = new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < bmps.size(); i++) {
                        Loader.saveMask(bmps.get(i), new File(dir, names.get(i)));   // tmp + fsync + rename
                        pendingMasks.remove(names.get(i));
                    }
                    // 2. doc.json switches to the new set in one rename
                    Loader.writeText(new File(dir, "doc.json"), json);
                    saved[0] = true;
                    // 3. only now that doc.json no longer references them: an interrupted save keeps a whole project
                    File[] files = dir.listFiles();
                    if (files != null) for (File f : files) {
                        String n = f.getName();
                        if (n.startsWith("mask_") && !keep.contains(n) && !pendingMasks.contains(n)) f.delete();
                    }
                } catch (final Exception e) {
                    for (String n : names) pendingMasks.remove(n);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            toast("Couldn't save project: " + e.getMessage());
                            dirty = true;
                            for (int id : ids) { Layer l = Editor.this.doc.byId(id); if (l != null) l.maskSavedVer = -1; }
                        }
                    });
                } finally {
                    for (int i = 0; i < bmps.size(); i++) if (copies.get(i)) bmps.get(i).recycle();
                }
            }
        };
        if (async) saveExec.execute(job);
        else {
            try { saveExec.submit(new Runnable() { @Override public void run() {} }).get(); } catch (Exception ignored) {}
            job.run();
        }
        if (!thumb) return async || saved[0];
        final File thumbFile = new File(dir, "thumb.jpg");
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                try {
                    Bitmap b = engine.thumbnail(512);
                    if (b != null) { Loader.saveJpeg(b, thumbFile, 85); b.recycle(); }
                } catch (Exception ignored) {}
            }
        });
        return async || saved[0];
    }

    @Override public void onPause() {
        save();
        gl.onPause();
    }

    @Override public void onResume() { gl.onResume(); }

    @Override public View rootView() { return root; }

    @Override public boolean blocking() { return exporting || busy; }

    @Override public void mediaPicked(int req, android.content.Intent data) {}

    /** The thumbnail renders on the GL thread; run r on the UI thread once that queue has drained. */
    @Override public void flush(final Runnable r) {
        gl.queueEvent(new Runnable() { @Override public void run() { act.runOnUiThread(r); } });
    }

    @Override public void close() {
        gl.onPause();
        closed = true;
        handler.removeCallbacks(detailRun);
        detailExec.shutdownNow();
        work.shutdownNow();
        sources.close();
        saveExec.shutdown();   // queued saves still finish
    }

    /** Back: leave crop / move / a tool first, then close the editor. Returns true if handled. */
    @Override public boolean back() {
        if (busy) return true;
        if (previewBase != null) { cancelPreview(); return true; }
        if (tab == T_CROP) { setTab(T_LAYERS); return true; }
        if (moveTool) { moveTool = false; rebuildPanel(); return true; }
        if (maskTool == CanvasView.SAMPLE) { maskTool = maskToolBeforeSample; rebuildPanel(); return true; }
        if (selStroke >= 0) { selStroke = -1; cv.invalidate(); rebuildPanel(); return true; }
        return false;
    }
}
