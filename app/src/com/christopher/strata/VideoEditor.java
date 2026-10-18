package com.christopher.strata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Video editing screen. Preview playback uses one MediaPlayer per playing clip (decoding into a
 * SurfaceTexture the GL compositor grades) and per music item; the playing clip's own position
 * is the master clock, so picture and timeline stay locked and other players are nudged to it.
 * Export doesn't use any of this: Exporter decodes frame-accurately on its own.
 */
final class VideoEditor implements Workspace {
    static final int T_CLIPS = 0, T_GRADE = 1, T_TEXT = 2, T_AUDIO = 3, T_FORMAT = 4, T_VIZ = 5;
    /** 3.13: every Photoshop mode the shared shader has (Editor.MODES indices), grouped as Photoshop groups them. */
    static final String[] BLEND_GROUPS = {"Normal", "Darken", "Lighten", "Contrast", "Difference", "Colour"};
    static final int[][] BLEND_GROUP_MODES = {{0}, {1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12, 13}, {14, 15}, {16, 17, 18, 19}};

    interface ModeSet { void set(int mode); }

    /** "Blend: name" chip opening the grouped sheet of all 20 modes. */
    private TextView blendChip(final int current, final ModeSet set) {
        return Ui.chip(act, "blend", "Blend: " + Editor.MODES[Math.max(0, Math.min(Editor.MODES.length - 1, current))], current != 0,
                new View.OnClickListener() { @Override public void onClick(View v) { blendSheet(current, set); } });
    }

    private void blendSheet(int current, final ModeSet set) {
        Sheet sh = new Sheet(act, "Blend mode");
        for (int g = 0; g < BLEND_GROUPS.length; g++) {
            sh.section(BLEND_GROUPS[g]);
            for (final int m : BLEND_GROUP_MODES[g])
                sh.item(m == current ? "check" : "blend", Editor.MODES[m], new Runnable() {
                    @Override public void run() { beginEdit(); set.set(m); changed(); rebuildPanel(); }
                });
        }
        sh.show();
    }
    static final int REQ_MEDIA = 20, REQ_AUDIO = 21, REQ_OVERLAY = 22, REQ_SRT = 23, REQ_RELINK = 24, REQ_RELINK_DIR = 25, REQ_SOURCE = 26, REQ_REPLACE = 27;
    /** What the next overlay pick becomes: 0 picture-in-picture, 1 full-frame B-roll, 2 logo / still. */
    private int overlayKind;

    final MainActivity act;
    final File dir;
    VProject proj;
    final ArrayList<VProject> undo = new ArrayList<VProject>(), redo = new ArrayList<VProject>();
    volatile long time;
    boolean playing;
    private long playT0, playSys0;
    int selKind, selId = -1;
    boolean transFocus;
    /** Clip body drags slip the contents (in and out move together) instead of scrubbing. */
    boolean slip;
    int tab = T_CLIPS;
    /** What the Grade tab edits: 0 the selected clip, 1 over everything, 2 all footage, 3 the selected B-roll. */
    int gradeTarget;
    static final int G_CLIP = 0, G_FINAL = 1, G_FOOTAGE = 2, G_BROLL = 3;
    int gradeSelId = -1;
    boolean dirty, closed, exporting;
    private Exporter exporter;

    final LinearLayout root;
    final GLSurfaceView gl;
    final Preview renderer;
    final Overlay overlay;
    final TimelineView timeline;
    private final ScrollView panelScroll;
    private final LinearLayout panel, tabs;
    private final TextView status, timeLabel;
    private final android.widget.ImageView playBtn;
    private View undoBtn, redoBtn;
    private TextView titleView, loopBtn;
    private TextView snapBtn;
    /** 3.13: full-screen viewer (everything but the viewer and transport hidden) and viewer guides. */
    boolean fullView;
    int guideMode;
    private View topBarView, sepView;
    private long lastViewTap; private float lastViewTapX, lastViewTapY; private long viewTapTime; private boolean viewTapWasPlaying;
    boolean snapOn, snapEdges, snapBeats, snapMarkers;
    final AdjPanels adj;

    final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<Integer, Player>();
    private final ThumbCache thumbs = new ThumbCache(ThumbCache.DEFAULT_BUDGET);
    private final HashSet<String> thumbJobs = new HashSet<String>();
    /** Videos whose first fill pass finished: only then do lookups ask for refills. */
    private final java.util.Set<String> thumbsFilled = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** "uri\ntime" refills queued on bg2 (UI thread only). */
    private final HashSet<String> refills = new HashSet<String>();
    private final long[] wantT = new long[1];
    private final ConcurrentHashMap<String, float[]> peaks = new ConcurrentHashMap<String, float[]>();
    private final HashSet<String> peakJobs = new HashSet<String>();
    private final ConcurrentHashMap<String, AudioFeatures> fxCache = new ConcurrentHashMap<String, AudioFeatures>();
    private final HashSet<String> fxJobs = new HashSet<String>();
    private final java.util.HashMap<String, String> fxErrors = new java.util.HashMap<String, String>();
    private final ExecutorService audioBg = Executors.newSingleThreadExecutor();
    private final ArrayList<View> automationMeters = new ArrayList<View>();
    private final HashSet<Music.Link> expandedLinks = new HashSet<Music.Link>();
    private long loopFrom = -1, loopTo = -1;
    private final Runnable rebuildRun = new Runnable() { @Override public void run() { rebuildPanel(); } };
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService bg2 = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelBg = new AtomicBoolean();
    private final Handler h = new Handler(Looper.getMainLooper());

    VideoEditor(MainActivity a, File dir, VProject p) {
        act = a;
        this.dir = dir;
        proj = p;
        android.content.SharedPreferences options=a.getSharedPreferences("ui",0);
        snapOn=options.getBoolean("snapOn",true);snapEdges=options.getBoolean("snapEdges",true);snapBeats=options.getBoolean("snapBeats",true);snapMarkers=options.getBoolean("snapMarkers",true);
        root = Ui.col(a);
        root.setBackgroundColor(Ui.BG);
        topBarView = topBar();
        root.addView(topBarView, new LinearLayout.LayoutParams(-1, Ui.dp(52)));
        guideMode = options.getInt("guides", 0);

        FrameLayout pv = new FrameLayout(a);
        gl = new GLSurfaceView(a);
        gl.setEGLContextClientVersion(3);
        gl.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        gl.setPreserveEGLContextOnPause(true);
        renderer = new Preview();
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        MaskRaster.onReady = new Runnable() {   // 3.15: a text/shape mask texture is ready: draw again
            @Override public void run() { gl.requestRender(); }
        };
        pv.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        overlay = new Overlay(a);
        pv.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(a, "", 13, Ui.TEXT);
        status.setBackground(Ui.round(0xe0101114, 10, Ui.LINE));
        status.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        status.setVisibility(View.GONE);
        pv.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        twoUp = new TwoUp(a);
        pv.addView(twoUp.view, new FrameLayout.LayoutParams(-1, -1));
        // 0.9: a bigger viewer without leaving the editor. One corner button hides every toolbar
        // (the viewer takes their space; transport and timeline stay), a pill brings them back.
        hideBarsBtn = Ui.iconBtn(a, "eye_off", new View.OnClickListener() { @Override public void onClick(View v) { setBarsHidden(true); } });
        hideBarsBtn.setBackground(Ui.round(0x8c000000, 24, 0));
        hideBarsBtn.setContentDescription("Hide toolbars: make the viewer as big as possible");
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.TOP | Gravity.END);
        hlp.setMargins(0, Ui.dp(8), Ui.dp(8), 0);
        hideBarsBtn.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        pv.addView(hideBarsBtn, hlp);
        showBarsBtn = Ui.chip(a, "eye", "Show toolbars", false, new View.OnClickListener() { @Override public void onClick(View v) { setBarsHidden(false); } });
        showBarsBtn.setTextSize(15);
        showBarsBtn.setBackground(Ui.round(0xd0101114, 22, 0x55ffffff));
        showBarsBtn.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(16), Ui.dp(11));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        slp.setMargins(0, Ui.dp(8), Ui.dp(8), 0);
        showBarsBtn.setVisibility(View.GONE);
        pv.addView(showBarsBtn, slp);
        root.addView(pv, Ui.lp(-1, 0, 1f));
        gripView = viewerGrip();
        root.addView(gripView, new LinearLayout.LayoutParams(-1, Ui.dp(GRIP_DP)));

        LinearLayout transport = Ui.row(a);
        transport.setPadding(Ui.dp(8), 0, Ui.dp(12), 0);
        playBtn = Ui.iconBtn(a, "play", new View.OnClickListener() {
            @Override public void onClick(View v) { if (playing) pause(); else play(); }
        });
        transport.addView(playBtn);
        timeLabel = Ui.text(a, "", 12, Ui.DIM);
        timeLabel.setSingleLine(true);timeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        timeLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        transport.addView(timeLabel, Ui.lp(0, -2, 1f));
        snapBtn=chip(snapOn?"Snap on":"Snap off",snapOn,new View.OnClickListener(){public void onClick(View v){snapOn=!snapOn;saveSnapOptions();}});
        snapBtn.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){snapOptions();return true;}});
        snapBtn.setContentDescription("Timeline snapping. Tap to toggle; hold for snap targets.");transport.addView(snapBtn);
        View markBtn = Ui.iconBtn(a, "marker", new View.OnClickListener() { @Override public void onClick(View v) { addMarkerHere(); } });
        markBtn.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){markersSheet();return true;}});
        markBtn.setContentDescription("Add a marker at the playhead (works while playing: tap along). Hold for the marker list.");
        transport.addView(markBtn);
        View fullBtn = Ui.iconBtn(a, "fullscreen", new View.OnClickListener() { @Override public void onClick(View v) { toggleFull(); } });
        fullBtn.setContentDescription("Full-screen viewer (or double-tap the picture, or turn the phone sideways)");
        transport.addView(fullBtn);
        loopBtn = chip("Stop loop", false, new View.OnClickListener(){@Override public void onClick(View v){loopFrom=loopTo=-1;updateTime();rebuildPanel();}});
        loopBtn.setVisibility(View.GONE); transport.addView(loopBtn);
        transport.addView(Ui.iconBtn(a, "start", new View.OnClickListener() { @Override public void onClick(View v) { scrubTo(0); } }));
        transport.addView(Ui.iconBtn(a, "frame_back", new View.OnClickListener() { @Override public void onClick(View v) { stepFrame(-1); } }));
        transport.addView(Ui.iconBtn(a, "frame_fwd", new View.OnClickListener() { @Override public void onClick(View v) { stepFrame(1); } }));
        root.addView(transport, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        transportView = transport;
        clipBarScroll = new android.widget.HorizontalScrollView(a);
        clipBarScroll.setHorizontalScrollBarEnabled(false);
        clipBarScroll.setBackgroundColor(Ui.PANEL);
        clipBar = Ui.row(a);
        clipBarScroll.addView(clipBar);
        clipBarScroll.setVisibility(View.GONE);
        root.addView(clipBarScroll, new LinearLayout.LayoutParams(-1, Ui.dp(54)));

        timeline = new TimelineView(a, this);
        root.addView(timeline);
        View sep = new View(a);
        sepView = sep;
        sep.setBackgroundColor(Ui.LINE);
        root.addView(sep, new LinearLayout.LayoutParams(-1, 1));
        panelScroll = new ScrollView(a);
        panelScroll.setBackgroundColor(Ui.PANEL);
        panel = Ui.col(a);
        panel.setPadding(0, 0, 0, Ui.dp(16));
        panelScroll.addView(panel);
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int ph = (int) (screenH * PANEL_DEFAULT);
        float savedPanel = options.getFloat("viewerPanel", -1f), savedTl = options.getFloat("viewerTimeline", 0f);
        if (savedPanel >= 0) ph = Math.max(Ui.dp(PANEL_MIN_DP), (int) (screenH * savedPanel));   // the size the user last dragged to
        if (savedTl > 0) timelineCap = (int) (screenH * savedTl);
        panelH = ph;
        root.addView(panelScroll, new LinearLayout.LayoutParams(-1, ph));
        // the timeline's height cap depends on the real window height (keyboard, split screen, insets)
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b - t != ob - ot) timeline.post(new Runnable() { @Override public void run() { timeline.requestLayout(); } });
            }
        });
        tabs = Ui.row(a);
        tabs.setBackgroundColor(Ui.BG);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, Ui.dp(50)));

        adj = new AdjPanels(new AdjPanels.Host() {
            @Override public Activity act() { return act; }
            @Override public Layer layer() { return gradeLayer(); }
            @Override public void beginEdit() { VideoEditor.this.beginEdit(); }
            @Override public void changed() { VideoEditor.this.changed(); }
            @Override public void rebuild() { rebuildPanel(); }
            @Override public void pickLut() { act.pickLut(); }
            @Override public boolean canMask() { return gradeLayer() != null && gradeLayer().type != Layer.GROUP; }
            @Override public boolean canPickNeutral() { return false; }
            @Override public boolean canShowClipping() { return false; }
            @Override public boolean clipVis() { return false; }
            @Override public void setClipVis(boolean on) {}
            @Override public void pickNeutral() {}
            @Override public void mask(int how) { maskStart(how); }
        });
        adj.showHistogram = false;

        VideoMasks.build(proj);   // 3.15: effect masks are saved as strokes; draw them once
        for (VProject.Clip c : proj.clips) loadClipAssets(c);
        for (VProject.TextItem t : proj.texts) if (t.media != null) loadClipAssets(t.media);
        if (proj.duckDb > 0) requestSidechainPeaks();
        for (VProject.TextItem t : proj.texts) {
            if (t.media != null) {
                // repair 3.2 projects: restore the overlay's real pixel size (its scale was never touched)
                if (t.media.srcW > 0 && t.media.srcH > 0) { t.layer.srcW = t.media.srcW; t.layer.srcH = t.media.srcH; }
                t.layer.type = Layer.IMAGE;
            } else if (t.viz == null) TextRender.refreshPreview(t.layer);
        }
        for (VProject.AudioItem au : proj.audio) requestPeaks(au.uri);
        // a project whose files moved says so on open, with the fix, instead of showing black clips
        h.post(new Runnable() {
            @Override public void run() { checkMissing(new Runnable() { @Override public void run() { if (!missing.isEmpty()) missingSheet(); } }); }
        });
        if (!proj.clips.isEmpty()) selKind = 0;
        setTab(T_CLIPS);
        changed();
        dirty = false;
        allowRotation();   // 0.9: sideways = full-screen viewer (the rest of the app stays upright)
    }

    // ================================================================ state
    void changed() {
        automationVersion++;
        VideoMasks.build(proj);   // 3.15: grade masks are strokes; draw any that came in without a picture
        for (ArrayList<Layer> g : proj.gradeLists()) GradeGroups.normalize(g);   // 3.14: look groups stay well-formed
        proj.syncAnchors();   // connected items follow main-track edits; moved items re-pin
        if(timingBase!=null){
            String error=TimelineLocks.violation(timingBase,proj);
            if(error!=null){
                proj=timingBase.copy();
                clipBarFor=-2;
                if(!pendingAccepted&&pendingUndo!=null&&!undo.isEmpty()&&undo.get(undo.size()-1)==pendingUndo){undo.remove(undo.size()-1);redo.clear();if(pendingRedo!=null)redo.addAll(pendingRedo);pendingUndo=null;}
                warnTiming(error);h.removeCallbacks(rebuildRun);h.post(rebuildRun);syncPlayers();
            }else pendingAccepted=true;
        }
        pruneSelection();
        time = Math.max(0, Math.min(time, proj.duration()));
        timeline.relayout();
        duckVer = -1;   // timing or levels may have moved: rebuild the preview ducking curve
        applyKeys();
        proj.validateSync();   // its track was deleted: sync is off, nothing is left following a ghost
        ensureSongFx();
        renderer.snap = proj.copy();
        timingBase=proj.copy();
        renderer.time = time;
        dirty = true;
        h.removeCallbacks(autosaveRun);
        h.postDelayed(autosaveRun, 4000);
        gl.requestRender();
        timeline.invalidate();
        overlay.invalidate();
        updateTime();
        updateUndo();
        refreshClipBar();
    }

    /** After editing a keyframeable value (position, zoom, rotation, opacity) of the selected item. */
    void changedXf() {
        captureKeys();
        changed();
    }

    long frameTol() { return 500000L / proj.fps(); }

    /** Animated items: show their values at the playhead (the fields are the edit surface). */
    void applyKeys() {
        long[] st = proj.starts();
        float[] v = new float[5];
        for (int i = 0; i < proj.clips.size(); i++) {
            VProject.Clip c = proj.clips.get(i);
            if (c.keys.isEmpty()) continue;
            v = VProject.clipVals(c);
            c.keys.eval(time - st[i], v);
            VProject.setClipVals(c, v);
        }
        for (VProject.TextItem t : proj.texts) {
            if (t.keys.isEmpty()) continue;
            v = VProject.textVals(t);
            t.keys.eval(time - t.start, v);
            VProject.setTextVals(t, v);
        }
        // 3.15: keyframed mask shapes on effects (keys are relative to their stack's start)
        for (int i = 0; i < proj.clips.size(); i++) applyMaskKeys(proj.clips.get(i).grade, time - st[i]);
        for (VProject.TextItem t : proj.texts) if (t.media != null) applyMaskKeys(t.media.grade, time - t.start);
        applyMaskKeys(proj.pgrade, time);
        applyMaskKeys(proj.grade, time);
    }

    private static void applyMaskKeys(ArrayList<Layer> g, long lt) {
        for (Layer l : g)
            for (MaskStack.Comp c : l.comps)
                if (!c.keys.isEmpty()) { float[] v = c.vec(); c.keys.eval(lt, v); c.setVec(v); }
    }

    /** If the selected item is animated and its values were just changed, record a key at the playhead. */
    private void captureKeys() {
        VProject.Clip c = selClip();
        if (c != null && !c.keys.isEmpty()) {
            long lt = time - proj.start(proj.clipIndex(c.id));
            c.keys.capture(lt, VProject.clipVals(c), frameTol());   // only the animated properties that changed
        }
        VProject.TextItem t = selOverlay();
        if (t != null && !t.keys.isEmpty()) {
            long lt = time - t.start;
            t.keys.capture(lt, VProject.textVals(t), frameTol());
        }
    }

    /** One recoverable analysis job per URI; failures persist visibly until Retry. */
    private void ensureSongFx() {
        for (String uri : AudioControl.required(proj)) requestControlAnalysis(uri);
        VProject.AudioItem guide = proj.syncItem();
        proj.songFx = guide == null ? null : fxCache.get(guide.uri);
        proj.songFxUri = proj.songFx == null ? null : guide.uri;
        proj.controlFx.putAll(fxCache);
    }

    private void requestControlAnalysis(final String uri) {
        if (closed || fxCache.containsKey(uri) || fxJobs.contains(uri) || fxErrors.containsKey(uri)) return;
        fxJobs.add(uri);
        audioBg.execute(new Runnable() {
            @Override public void run() {
                AudioFeatures found = null; Beats.Result beats = null; String error = null;
                try {
                    found = Media.songFeatures(act, uri, cancelBg);
                    if (found == null) throw new Exception("No readable audio in this file");
                    if (!cancelBg.get()) beats = Beats.fromFeatures(found);
                } catch (Throwable e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
                final AudioFeatures f = found; final Beats.Result b = beats; final String e = error;
                h.post(new Runnable() {
                    @Override public void run() {
                        fxJobs.remove(uri);
                        if (closed) return;
                        if (e != null || f == null || b == null) fxErrors.put(uri, e == null ? "Analysis cancelled" : e);
                        else {
                            fxCache.put(uri, f); proj.controlFx.put(uri, f); proj.controlBeats.put(uri, b);
                            VProject.AudioItem guide = proj.syncItem();
                            if (guide != null && uri.equals(guide.uri)) {
                                proj.beats = b.beats; proj.bpm = b.bpm; proj.beatsUri = uri;
                                for (VProject q : undo) fillBeats(q, uri, b);
                                for (VProject q : redo) fillBeats(q, uri, b);
                            }
                        }
                        ensureSongFx(); renderer.snap = proj.copy(); gl.requestRender();
                        timeline.invalidate(); rebuildPanel();
                    }
                });
            }
        });
    }

    void beginEdit() {
        if (previewBase != null) return;   // inside a preview: Apply makes the one undo step
        pendingUndo=proj.copy();pendingRedo=new ArrayList<VProject>(redo);pendingAccepted=false;
        timingBase=pendingUndo;
        undo.add(pendingUndo);
        if (undo.size() > 80) undo.remove(0);
        redo.clear();
        updateUndo();
    }

    private void doUndo() {
        Crash.note("video undo");
        if (previewBase != null) { cancelPreview(); return; }
        if (undo.isEmpty() || exporting) return;
        redo.add(proj.copy());
        proj = undo.remove(undo.size() - 1);
        afterHistory();
    }

    private void doRedo() {
        Crash.note("video redo");
        if (previewBase != null) return;
        if (redo.isEmpty() || exporting) return;
        undo.add(proj.copy());
        proj = redo.remove(redo.size() - 1);
        afterHistory();
    }

    private void afterHistory() {
        timingBase=null;pendingUndo=null;pendingRedo=null;
        loopFrom=loopTo=-1;renderer.soloId=0;
        pause();
        time = Math.min(time, proj.duration());
        changed();
        syncPlayers();
        rebuildPanel();
        clipBarFor = -2;
        refreshClipBar();
    }

    private void updateUndo() {
        if (undoBtn != null) undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        if (redoBtn != null) redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void updateTime() {
        if(loopBtn!=null)loopBtn.setVisibility(loopFrom>=0?View.VISIBLE:View.GONE);
        String times=Timeline.timecode(time, proj.fps()) + " / " + Timeline.timecode(proj.duration(), proj.fps());
        timeLabel.setText(times);
        timeLabel.setContentDescription(times+", "+proj.outW()+" by "+proj.outH()+", "+proj.fps()+" frames per second");
    }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); }

    void showStatus(String s) {
        if (s == null) { status.setVisibility(View.GONE); return; }
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    VProject.Clip selClip() { return selKind == TimelineView.SEL_CLIP ? proj.clipById(selId) : null; }
    VProject.TextItem selText() { return selKind == TimelineView.SEL_TEXT ? proj.textById(selId) : null; }
    VProject.TextItem selViz() { return selKind == TimelineView.SEL_VIZ ? proj.textById(selId) : null; }
    VProject.TextItem selMedia() { return selKind == TimelineView.SEL_MEDIA ? proj.textById(selId) : null; }
    VProject.TextItem selOverlay() {
        VProject.TextItem t = selText();
        if (t == null) t = selViz();
        return t != null ? t : selMedia();
    }
    VProject.AudioItem selAudio() { return selKind == TimelineView.SEL_AUDIO ? proj.audioById(selId) : null; }

    private int panelH;

    /**
     * Tallest the timeline may be: whatever leaves the viewer at least ~30 % of the window (and
     * 160 dp); taller tracks scroll inside it. Before the first layout, the display height stands in.
     */
    int timelineMaxH() {
        int H = root.getHeight() > 0 ? root.getHeight() : act.getResources().getDisplayMetrics().heightPixels;
        int cap = H - chromeH() - Math.max(Ui.dp(160), (int) (H * 0.30f));
        if (timelineCap > 0 && !barsHidden) cap = Math.min(cap, timelineCap);   // the user dragged the viewer bigger
        return cap;
    }

    /** Heights of everything below/above the viewer except the timeline (only what is shown). */
    private int chromeH() {
        int h = Ui.dp(40) + 1;   // transport + separator
        if (topBarView == null || topBarView.getVisibility() == View.VISIBLE) h += Ui.dp(52);
        if (tabs == null || tabs.getVisibility() == View.VISIBLE) h += Ui.dp(50);
        if (panelScroll == null || panelScroll.getVisibility() == View.VISIBLE) h += panelH;
        if (gripView != null && gripView.getVisibility() == View.VISIBLE) h += Ui.dp(GRIP_DP);
        if (clipBarScroll != null && clipBarScroll.getVisibility() == View.VISIBLE) h += Ui.dp(54);
        return h;
    }

    // ================================================================ 0.9: viewer size, hidden toolbars, landscape
    static final float PANEL_DEFAULT = 0.27f;
    static final int PANEL_MIN_DP = 96, GRIP_DP = 18, MIN_VIEWER_DP = 120;
    private int timelineCap;              // 0 = the timeline takes its natural height
    private View gripView, transportView;
    private android.widget.ImageView hideBarsBtn;
    private TextView showBarsBtn;
    boolean barsHidden;
    private android.view.OrientationEventListener orient;
    private boolean rearmRotation;

    /**
     * Pure: the inspector panel and timeline heights after the viewer's bottom edge was dragged by
     * dy px (down = bigger viewer). A bigger viewer takes from the panel first (down to its
     * minimum), then from the timeline (down to its main track). A smaller one gives back to a
     * capped timeline first, then to the panel. The viewer itself never drops below minViewer.
     * Returns {panel, timeline}.
     */
    static int[] split(int panel0, int tl0, int tlNatural, int tlMin, int panelMin, int avail, int minViewer, float dy) {
        int p = panel0, t = tl0, d = Math.round(dy);
        if (d > 0) {
            int fromP = Math.min(d, Math.max(0, p - panelMin)); p -= fromP; d -= fromP;
            int fromT = Math.min(d, Math.max(0, t - tlMin)); t -= fromT;
        } else if (d < 0) {
            int give = -d;
            int toT = Math.min(give, Math.max(0, tlNatural - t)); t += toT; give -= toT;
            p += give;
        }
        int room = avail - minViewer;   // what panel + timeline may use together
        if (p + t > room) p = Math.max(panelMin, room - t);
        if (p + t > room) t = Math.max(tlMin, room - p);
        return new int[]{p, t};
    }

    /** The bar under the viewer: drag to resize, double-tap to go back to the standard split. */
    private View viewerGrip() {
        final View g = new View(act) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                p.setColor(isPressed() ? Ui.ACCENT : 0x66ffffff);
                float w = Ui.dp(44), hh = Ui.dp(4), cx = getWidth() / 2f, cy = getHeight() / 2f;
                c.drawRoundRect(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2, hh / 2, hh / 2, p);
            }
        };
        g.setBackgroundColor(Ui.BG);
        g.setContentDescription("Viewer size. Drag up or down to resize; double-tap to reset.");
        g.setOnTouchListener(new View.OnTouchListener() {
            float y0; int panel0, tl0; boolean active; long lastUp;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        active = true; y0 = e.getRawY(); panel0 = panelH; tl0 = timeline.getHeight();
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        v.setPressed(true); v.invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!active) return false;
                        int H = root.getHeight();
                        int others = chromeH() - panelH;   // fixed rows other than the panel
                        int tlMin = timeline.minH();
                        int[] r = split(panel0, tl0, (int) timeline.contentH(), tlMin, Ui.dp(PANEL_MIN_DP), H - others, Ui.dp(MIN_VIEWER_DP), e.getRawY() - y0);
                        setPanelH(r[0]);
                        timelineCap = r[1] >= (int) timeline.contentH() ? 0 : r[1];
                        timeline.requestLayout();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!active) return false;
                        active = false; v.setPressed(false); v.invalidate();
                        long now = android.os.SystemClock.uptimeMillis();
                        boolean tap = Math.abs(e.getRawY() - y0) < Ui.dp(6);
                        if (tap && now - lastUp < 320) { lastUp = 0; resetViewerSize(); return true; }
                        if (tap) lastUp = now;
                        saveViewerSize();
                        return true;
                    }
                }
                return false;
            }
        });
        return g;
    }

    private void setPanelH(int h) {
        panelH = h;
        android.view.ViewGroup.LayoutParams lp = panelScroll.getLayoutParams();
        lp.height = h;
        panelScroll.setLayoutParams(lp);
    }

    private void saveViewerSize() {
        float H = act.getResources().getDisplayMetrics().heightPixels;
        act.getSharedPreferences("ui", 0).edit().putFloat("viewerPanel", panelH / H).putFloat("viewerTimeline", timelineCap / H).apply();
    }

    private void resetViewerSize() {
        setPanelH((int) (act.getResources().getDisplayMetrics().heightPixels * PANEL_DEFAULT));
        timelineCap = 0;
        timeline.requestLayout();
        act.getSharedPreferences("ui", 0).edit().remove("viewerPanel").remove("viewerTimeline").apply();
        toast("Viewer back to the standard size");
    }

    void setBarsHidden(boolean on) {
        barsHidden = on;
        applyChrome();
        if (on) toast("Toolbars hidden. Tap Show toolbars, or Back, to bring them back.");
    }

    /** One place decides which rows show: normal, toolbars hidden, or full-screen viewer. */
    private void applyChrome() {
        boolean bare = fullView || barsHidden;
        int v = bare ? View.GONE : View.VISIBLE;
        topBarView.setVisibility(v); tabs.setVisibility(v); sepView.setVisibility(v); panelScroll.setVisibility(v);
        clipBarScroll.setVisibility(v);
        gripView.setVisibility(v);
        timeline.setVisibility(fullView ? View.GONE : View.VISIBLE);
        hideBarsBtn.setVisibility(bare ? View.GONE : View.VISIBLE);
        showBarsBtn.setVisibility(barsHidden && !fullView ? View.VISIBLE : View.GONE);
        timeline.requestLayout();
        overlay.invalidate();
        gl.requestRender();
    }

    boolean landscape() { return act.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE; }

    /** Turning the phone sideways shows the picture full screen; upright again is the whole editor. */
    void orientationChanged(boolean land) {
        if (land) { setFullView(true); }
        else { if (fullView) setFullView(false); }
    }

    /** Full-screen button / double-tap. Leaving full screen while sideways turns the app upright
     *  (YouTube's behaviour); rotation comes back once the phone is held upright again. */
    void toggleFull() {
        if (fullView && landscape()) leaveLandscape();
        else setFullView(!fullView);
    }

    private void leaveLandscape() {
        act.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        rearmRotation = true;
        if (orient != null && orient.canDetectOrientation()) orient.enable();
    }

    private void allowRotation() {
        // FULL_USER follows the phone's auto-rotate setting: with rotation locked, nothing turns
        act.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        if (orient == null) orient = new android.view.OrientationEventListener(act) {
            @Override public void onOrientationChanged(int deg) {
                if (!rearmRotation || deg == ORIENTATION_UNKNOWN) return;
                if (deg < 25 || deg > 335) { rearmRotation = false; disable(); if (!closed) allowRotation(); }
            }
        };
    }

    /** Edge-to-edge picture while sideways: status and navigation bars swipe in when needed. */
    private void immersive(boolean on) {
        android.view.Window w = act.getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            } else c.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
        } else {
            View d = w.getDecorView();
            d.setSystemUiVisibility(on ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) : 0);
        }
    }

    // ================================================================ 3.7: clip toolbar, source viewer, two-up
    private TwoUp twoUp;
    private android.widget.HorizontalScrollView clipBarScroll;
    private LinearLayout clipBar;
    private SourceViewer sourceViewer;
    private int clipBarFor = -2;
    private String clipBarKind = "";
    boolean moveItems;
    private long automationVersion;
    boolean selectingMany;
    final java.util.LinkedHashSet<Integer> batchIds=new java.util.LinkedHashSet<Integer>();
    private VProject timingBase, pendingUndo;
    private ArrayList<VProject> pendingRedo;
    private boolean pendingAccepted;
    private long lockWarningAt;

    /**
     * The actions for the selected main-track clip, above the timeline whatever tab is open
     * (CapCut / LumaFusion keep clip actions next to the timeline).
     */
    private void refreshClipBar() {
        if (clipBar == null) return;
        final VProject.Clip c = selClip();
        final VProject.TextItem item = selOverlay();
        boolean selected = c != null || item != null || selAudio() != null;
        clipBarScroll.setVisibility(fullView || barsHidden ? View.GONE : View.VISIBLE);
        String kindKey = selKind + ":" + (c == null ? "item" : c.kind + ":" + (c.frameUs >= 0)) + ":" + selMuted();
        if (selId == clipBarFor && kindKey.equals(clipBarKind)) return;
        clipBarFor = selId; clipBarKind = kindKey; clipBar.removeAllViews();
        barBtn("plus","Add",new Runnable(){@Override public void run(){addSheet();}});
        if(selectingMany){
            barBtn("check","Done",new Runnable(){public void run(){endBatch();}});
            barBtn("pulse",batchIds.size()+" selected",new Runnable(){public void run(){batchOptions();}});
            barBtn("move",moveItems?"Done move":"Move",new Runnable(){public void run(){batchMoveMode();}});
            barBtn("copy","Duplicate",new Runnable(){public void run(){runBatch(1,0);}});
            barBtn("trash","Delete",new Runnable(){public void run(){confirmBatchDelete();}});
            return;
        }
        if(!selected){barBtn("pulse","Select",new Runnable(){public void run(){startBatch();}});return;}
        if(moveItems)barBtn("move","Done move",new Runnable(){@Override public void run(){moveItems=false;clipBarFor=-2;refreshClipBar();}});
        for(final BarAction a:barActions())barBtn(a.icon,a.label,new Runnable(){public void run(){runBarAction(a.id);}});
    }

    /**
     * 3.16: the bar does things to the selected item instead of opening the inspector section the
     * tabs below already open (3.13's "Effects" just jumped to the Effects tab, so every button was
     * a second way to reach a panel). Settings stay in the tabs; the bar holds the edits you make
     * over and over while cutting: split, delete, duplicate, freeze, keyframe, mute, copy / paste
     * the look, fades to the playhead.
     */
    static final class BarAction {
        final String id, icon, label;
        BarAction(String id, String icon, String label) { this.id = id; this.icon = icon; this.label = label; }
    }

    /**
     * Pure: the bar for a selection kind. clipKind: VProject.VIDEO / IMAGE, -1 if not a main clip;
     * freeze: a freeze frame; sound: the item has sound of its own to mute; muted: it is muted now.
     */
    static java.util.List<BarAction> barActionsFor(int selKind, int clipKind, boolean freeze, boolean sound, boolean muted) {
        java.util.ArrayList<BarAction> b = new java.util.ArrayList<BarAction>();
        if (selKind == TimelineView.SEL_NONE) return b;
        if (selKind == TimelineView.SEL_TEXT) b.add(new BarAction("edittext", "pencil", "Edit text"));
        b.add(new BarAction("split", "split", "Split"));
        b.add(new BarAction("delete", "trash", "Delete"));
        b.add(new BarAction("duplicate", "copy", "Duplicate"));
        if (selKind == TimelineView.SEL_CLIP && clipKind == VProject.VIDEO && !freeze) b.add(new BarAction("freeze", "freeze", "Freeze"));
        if (selKind != TimelineView.SEL_AUDIO) b.add(new BarAction("key", "keyframe", "Key \u25c6"));
        if (sound && (selKind == TimelineView.SEL_CLIP || selKind == TimelineView.SEL_MEDIA))
            b.add(new BarAction("mute", "mute", muted ? "Unmute" : "Mute"));
        if (selKind == TimelineView.SEL_CLIP || selKind == TimelineView.SEL_MEDIA) {
            b.add(new BarAction("copyfx", "fx", "Copy look"));
            b.add(new BarAction("pastefx", "paste", "Paste look"));
        }
        if (selKind == TimelineView.SEL_VIZ) b.add(new BarAction("vizpreset", "bars", "Preset\u2026"));
        if (selKind != TimelineView.SEL_CLIP) {
            b.add(new BarAction("fadein", "fade_in", "Fade in to here"));
            b.add(new BarAction("fadeout", "fade_out", "Fade out from here"));
        }
        b.add(new BarAction("trim", "mark_in", "Trim\u2026"));
        b.add(new BarAction("more", "more", "More"));
        return b;
    }

    private boolean selMuted() {
        VProject.Clip c = selClip();
        if (c != null) return c.mute;
        VProject.TextItem m = selMedia();
        return m != null && m.media != null && m.media.mute;
    }

    private java.util.List<BarAction> barActions() {
        VProject.Clip c = selClip();
        VProject.TextItem m = selMedia();
        boolean sound = c != null ? c.kind == VProject.VIDEO && c.hasAudio && c.frameUs < 0
                : m != null && m.media != null && m.media.kind == VProject.VIDEO && m.media.hasAudio;
        return barActionsFor(selKind, c == null ? -1 : c.kind, c != null && c.frameUs >= 0, sound, selMuted());
    }

    private void runBarAction(String id) {
        if (id.equals("split")) { split(); return; }
        if (id.equals("delete")) { deleteSelected(); return; }
        if (id.equals("duplicate")) { duplicateSelected(); return; }
        if (id.equals("freeze")) { freezeFrame(); return; }
        if (id.equals("trim")) { timingSheet(); return; }
        if (id.equals("more")) { itemMenu(); return; }
        if (id.equals("edittext")) { editSelectedText(); return; }
        if (id.equals("vizpreset")) { vizPresetSheet(false); return; }
        if (id.equals("key")) { barKey(); return; }
        if (id.equals("mute")) {
            VProject.Clip c = selClip();
            VProject.TextItem m = selMedia();
            VProject.Clip f = c != null ? c : m != null ? m.media : null;
            if (f == null) return;
            beginEdit(); f.mute = !f.mute; changed(); syncPlayers();
            clipBarFor = -2; refreshClipBar(); rebuildPanel();
            toast(f.mute ? "Muted." : "Sound on.");
            return;
        }
        if (id.equals("copyfx") || id.equals("pastefx")) {
            if (previewBase != null) applyPreview();
            gradeTarget = selKind == TimelineView.SEL_MEDIA ? G_BROLL : G_CLIP;
            gradeSelId = -1;
            if (id.equals("copyfx")) copyGrade(); else pasteGrade(false, true);
            rebuildPanel();
            return;
        }
        if (id.equals("fadein") || id.equals("fadeout")) { barFade(id.equals("fadein")); return; }
    }

    /** Keyframes everything at the playhead, or removes the key that's already there. */
    private void barKey() {
        final Keys k;
        final long start;
        VProject.Clip c = selClip();
        VProject.TextItem t = c == null ? selOverlay() : null;
        if (c != null) { k = c.keys; start = proj.start(proj.clipIndex(c.id)); }
        else if (t != null) { k = t.keys; start = t.start; }
        else return;
        if (!keyTimeValid()) return;
        pause();
        long lt = time - start, tol = frameTol();
        int at = k.near(lt, tol);
        beginEdit();
        if (at >= 0) { k.remove(at); toast("Key removed."); }
        else {
            k.set(lt, c != null ? VProject.clipVals(c) : VProject.textVals(t), tol);
            toast(k.size() == 1 ? "Keyed. Move the playhead, change position, size or anything else, and it animates between keys." : "Keyed (" + k.size() + " keys).");
        }
        changed(); timeline.invalidate(); rebuildPanel();
    }

    /** Fade from the item's start to the playhead, or from the playhead to its end. */
    private void barFade(boolean in) {
        VProject.AudioItem a = selAudio();
        VProject.TextItem t = a == null ? selOverlay() : null;
        long start, dur;
        if (a != null) { start = a.start; dur = a.dur(); }
        else if (t != null) { start = t.start; dur = t.dur; }
        else return;
        long d = in ? time - start : start + dur - time;
        if (d <= 0 || d > dur) { toast("Move the playhead over the item first."); return; }
        beginEdit();
        if (a != null) { if (in) a.fadeIn = d; else a.fadeOut = d; a.fadeOffset = 0; a.fadeSpan = -1; }
        else { if (in) t.fadeIn = d; else t.fadeOut = d; t.fadeOffset = 0; t.fadeSpan = -1; }
        changed(); syncPlayers(); timeline.invalidate(); rebuildPanel();
        toast(String.format(Locale.US, "Fade %s: %.2f s", in ? "in" : "out", d / 1e6));
    }

    /** Switches to tab t with its inspector showing the named section (scrolled to the top). */
    private void openSection(int t, String name) {
        if (previewBase != null && t != tab) applyPreview();
        String ctx = t + ":" + selKind + ":" + selId + ":" + (t == T_GRADE ? gradeTarget + ":" + gradeSelId : "");
        inspector.focus(ctx, name);
        if (t != tab) setTab(t); else rebuildPanel();
    }

    private void warnTiming(String message) {
        long now=System.currentTimeMillis();if(now-lockWarningAt>1200){toast(message);lockWarningAt=now;}
    }
    boolean timingEditable() {
        if(!TimelineLocks.locked(proj,selId))return true;
        warnTiming("This item's timing is locked. Use More → Unlock timing.");return false;
    }
    boolean itemSelected(int kind,int id) { return selectingMany?batchIds.contains(id):selKind==kind&&selId==id; }
    private void pruneSelection() {
        for(java.util.Iterator<Integer> it=batchIds.iterator();it.hasNext();)if(TimelineBatch.kind(proj,it.next())==TimelineView.SEL_NONE)it.remove();
        if(TimelineBatch.kind(proj,selId)==TimelineView.SEL_NONE){selKind=TimelineView.SEL_NONE;selId=-1;}
    }
    private void refreshBatch() {
        pruneSelection();
        if(selectingMany){selId=batchIds.isEmpty()?-1:batchIds.iterator().next();selKind=TimelineBatch.kind(proj,selId);transFocus=false;}
        clipBarFor=-2;refreshClipBar();timeline.invalidate();overlay.invalidate();rebuildPanel();
    }
    private void startBatch() {
        pause();applyPreview();renderer.soloId=0;selectingMany=true;moveItems=false;slip=false;batchIds.clear();
        if(TimelineBatch.kind(proj,selId)!=TimelineView.SEL_NONE)batchIds.add(selId);refreshBatch();
    }
    private void endBatch() { selectingMany=false;moveItems=false;batchIds.clear();refreshBatch(); }
    void timelineSelect(int kind,int id,boolean transition) {
        if(!selectingMany){select(kind,id,transition);return;}
        if(kind!=TimelineView.SEL_NONE){if(!batchIds.remove(id))batchIds.add(id);refreshBatch();}
    }
    private void toggleTimingLock() {
        if(TimelineBatch.kind(proj,selId)==TimelineView.SEL_NONE)return;
        beginEdit();TimelineLocks.set(proj,selId,!TimelineLocks.locked(proj,selId));changed();clipBarFor=-2;refreshClipBar();rebuildPanel();
    }
    private void batchLock(boolean locked) {
        boolean change=false;for(int id:batchIds)if(TimelineLocks.locked(proj,id)!=locked)change=true;
        if(!change)return;beginEdit();for(int id:batchIds)TimelineLocks.set(proj,id,locked);if(locked)moveItems=false;changed();refreshBatch();
    }
    private void batchOptions() {
        new Sheet(act,batchIds.size()+" selected")
            .item("pulse","Select all timeline items",new Runnable(){public void run(){batchIds.clear();batchIds.addAll(TimelineBatch.all(proj));refreshBatch();}})
            .item("close","Clear selection",new Runnable(){public void run(){batchIds.clear();moveItems=false;refreshBatch();}})
            .item("fit","Fit selection",new Runnable(){public void run(){timeline.fitSelection();}})
            .section("Protection")
            .item("lock","Lock timing of selection",new Runnable(){public void run(){batchLock(true);}})
            .item("lock","Unlock timing of selection",new Runnable(){public void run(){batchLock(false);}}).show();
    }
    private void batchMoveMode() {
        if(batchIds.isEmpty()){toast("Select items in the timeline first.");return;}
        for(int id:batchIds)if(TimelineLocks.locked(proj,id)){warnTiming("Unlock timing of the selected items before moving them.");return;}
        if(TimelineBatch.hasMain(proj,batchIds)){
            new Sheet(act,"Move selection on the magnetic track")
                .item("back","Move earlier by one clip","Selected main clips must be adjacent; selected graphics and audio move by the same offset",new Runnable(){public void run(){runBatch(2,-1);}})
                .item("next","Move later by one clip",new Runnable(){public void run(){runBatch(2,1);}}).show();return;
        }
        moveItems=!moveItems;clipBarFor=-2;refreshClipBar();rebuildPanel();timeline.invalidate();
    }
    private void commitBatch(TimelineBatch.Result result) {
        beginEdit();proj=result.project;batchIds.clear();batchIds.addAll(result.ids);changed();syncPlayers();refreshBatch();
    }
    private void runBatch(int command,long value) {
        pause();applyPreview();
        try{
            TimelineBatch.Result result=command==0?TimelineBatch.delete(proj,batchIds):command==1?TimelineBatch.duplicate(proj,batchIds):TimelineBatch.reorder(proj,batchIds,(int)value);
            commitBatch(result);
        }catch(IllegalArgumentException e){toast(e.getMessage());}
    }
    private void confirmBatchDelete() {
        if(batchIds.isEmpty()){toast("Select items first.");return;}
        if(batchIds.size()==1){runBatch(0,0);return;}
        new AlertDialog.Builder(act).setTitle("Delete "+batchIds.size()+" selected items?")
            .setMessage("Main-track deletion ripples later footage. Connected items follow surviving footage; items whose parent clip is removed are re-pinned. Timing locks protect the whole edit. Undo restores the selected items.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete selection",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){runBatch(0,0);}}).show();
    }
    private void batchPosition() {
        final long start=TimelineBatch.span(proj,batchIds)[0];
        exactTimeDialog("Selection start",start,new TimeSet(){public void set(long value){
            pause();applyPreview();commitBatch(TimelineBatch.move(proj,batchIds,value-start,new long[0],0));
        }});
    }
    void cancelBatchDrag() {
        if(pendingUndo==null||undo.isEmpty()||undo.get(undo.size()-1)!=pendingUndo)return;
        proj=undo.remove(undo.size()-1);redo.clear();if(pendingRedo!=null)redo.addAll(pendingRedo);
        timingBase=null;pendingUndo=null;pendingRedo=null;changed();syncPlayers();refreshBatch();
    }
    private void batchPanel() {
        panel.addView(Ui.label(act,batchIds.size()+" timeline items selected"));
        panel.addView(hint(moveItems?"Drag a selected item to move the whole selection. Relative spacing and source ranges stay unchanged.":"Tap timeline items to add or remove them. Finish selection to edit an individual item's properties."));
        panel.addView(Ui.strip(act,chip("Select all",false,new View.OnClickListener(){public void onClick(View v){batchIds.clear();batchIds.addAll(TimelineBatch.all(proj));refreshBatch();}}),chip("Clear",false,new View.OnClickListener(){public void onClick(View v){batchIds.clear();moveItems=false;refreshBatch();}}),chip("Done",true,new View.OnClickListener(){public void onClick(View v){endBatch();}})));
        if(batchIds.isEmpty())return;
        long[] range=TimelineBatch.span(proj,batchIds);
        panel.addView(hint("Timeline range "+Timeline.timecode(range[0],proj.fps())+" → "+Timeline.timecode(range[1],proj.fps())));
        int locked=0;for(int id:batchIds)if(TimelineLocks.locked(proj,id))locked++;
        panel.addView(hint(locked+" of "+batchIds.size()+" items have timing locked."));
        panel.addView(Ui.strip(act,chip("Move",moveItems,new View.OnClickListener(){public void onClick(View v){batchMoveMode();}}),chip("Duplicate after",false,new View.OnClickListener(){public void onClick(View v){runBatch(1,0);}}),chip("Fit selection",false,new View.OnClickListener(){public void onClick(View v){timeline.fitSelection();}})));
        if(!TimelineBatch.hasMain(proj,batchIds))panel.addView(Ui.chip(act,"sliders","Exact selection start…",false,new View.OnClickListener(){public void onClick(View v){batchPosition();}}));
        else panel.addView(hint("Main clips use magnetic order. Move earlier/later keeps an adjacent block together; graphics/audio in the selection follow the same offset."));
        panel.addView(Ui.strip(act,chip("Lock timing",false,new View.OnClickListener(){public void onClick(View v){batchLock(true);}}),chip("Unlock timing",false,new View.OnClickListener(){public void onClick(View v){batchLock(false);}})));
    }

    private void itemMenu() {
        Sheet menu=new Sheet(act,selClip()!=null?selClip().name:selMedia()!=null?selMedia().media.name:selAudio()!=null?selAudio().name:"Selected overlay");
        menu.section("Navigate")
            .item("frame_back","Go to start",new Runnable(){@Override public void run(){scrubTo(selectedStart());}})
            .item("frame_fwd","Go to last frame",new Runnable(){@Override public void run(){scrubTo(Math.max(selectedStart(),selectedEnd()-Timeline.frame(proj.fps())));}})
            .item("fit","Fit selection in timeline",new Runnable(){@Override public void run(){timeline.fitSelection();}});
        menu.item("sliders","Exact source & timeline times…",new Runnable(){public void run(){timingSheet();}});
        menu.section("Arrange");
        menu.item("pulse","Select several items",new Runnable(){public void run(){startBatch();}})
            .item("lock",TimelineLocks.locked(proj,selId)?"Unlock timing":"Lock timing","Protect placement, source range, speed and deletion; color and levels stay editable",new Runnable(){public void run(){toggleTimingLock();}});
        if(selClip()!=null) {
            menu.item("back","Move earlier",new Runnable(){@Override public void run(){moveClip(-1);}})
                .item("next","Move later",new Runnable(){@Override public void run(){moveClip(1);}});
            if(selClip().kind==VProject.VIDEO)menu.item("film","Source viewer",new Runnable(){@Override public void run(){openClipSource();}})
                .item("freeze","Freeze frame",new Runnable(){@Override public void run(){freezeFrame();}});
            menu.item("replace","Replace footage",new Runnable(){@Override public void run(){pickMedia(REQ_REPLACE,new String[]{"video/*","image/*"});}});
        } else {
            menu.item("move",moveItems?"Finish moving":"Move with timeline drag",new Runnable(){@Override public void run(){moveItems=!moveItems;clipBarFor=-2;refreshClipBar();toast(moveItems?"Drag the selected item's body to move it. More → Finish moving returns to scrubbing.":"Timeline drag now scrubs.");}})
                .item("sliders","Exact timeline position…",new Runnable(){@Override public void run(){exactPlacement();}});
        }
        menu.item("copy","Duplicate",new Runnable(){@Override public void run(){duplicateSelected();}});
        menu.section("Remove").danger("trash","Delete selected item",new Runnable(){@Override public void run(){deleteSelected();}}).show();
    }
    private void duplicateSelected() {
        pause();applyPreview();
        try{
            TimelineBatch.Result result=TimelineBatch.duplicate(proj,java.util.Collections.singleton(selId));
            beginEdit();proj=result.project;int id=result.ids.iterator().next();select(TimelineBatch.kind(proj,id),id,false);changed();syncPlayers();scrubTo(selectedStart());
        }catch(IllegalArgumentException e){toast(e.getMessage());}
    }
    private void deleteSelected() {
        if(!timingEditable())return;
        pause();applyPreview();
        try{
            TimelineBatch.Result result=TimelineBatch.delete(proj,java.util.Collections.singleton(selId));
            beginEdit();proj=result.project;select(TimelineView.SEL_NONE,-1,false);changed();syncPlayers();
        }catch(IllegalArgumentException e){toast(e.getMessage());}
    }

    private long selectedStart() {
        return selClip() != null ? proj.start(proj.clipIndex(selId)) : selOverlay() != null ? selOverlay().start : selAudio() != null ? selAudio().start : 0;
    }
    private long selectedEnd() {
        return selectedStart() + (selClip() != null ? selClip().dur() : selOverlay() != null ? selOverlay().dur : selAudio() != null ? selAudio().dur() : 0);
    }
    private void trimHere(boolean head) {
        if(!timingEditable())return;
        long start = selectedStart(), end = selectedEnd(), f = Timeline.frame(proj.fps());
        long at = ClipRange.playheadBoundary(time,proj.fps(),head);
        if (at < start || at > end || (head ? end - at : at - start) < f-1) {
            toast("Place the playhead inside the selected item, leaving at least one frame."); return;
        }
        pause(); applyPreview(); beginEdit();
        dragEdit(head ? 1 : 2, at - (head ? start : end), new long[0], 0);
        time = head ? selectedStart() : Math.max(selectedStart(), selectedEnd() - Timeline.frame(proj.fps()));
        changed(); dragDone();
    }
    private void openTrim() {
        if(!timingEditable())return;
        final VProject.TextItem overlayItem = selMedia();
        final VProject.Clip c = selClip() != null ? selClip() : overlayItem == null ? null : overlayItem.media;
        if (c == null || c.kind != VProject.VIDEO || c.frameUs>=0) { exactDuration(); return; }
        pause(); applyPreview();
        final int id = c.id;
        long local = Math.max(0, Math.min(c.dur(), time - selectedStart()));
        sourceViewer = new SourceViewer(act, c.copy(), proj.fps(), c.in, c.out, c.srcAt(local), false, true, new SourceViewer.Action() {
            @Override public void edit(int what, VProject.Clip probe, long in, long out) {
                VProject.Clip target = overlayItem == null ? proj.clipById(id) : proj.textById(overlayItem.id).media;
                VProject.Clip trial = target.copy(); trial.in = in; trial.out = out;
                if (trial.dur() < Timeline.frame(proj.fps()) - 1) { toast("That range is shorter than one output frame at this speed."); return; }
                beginEdit(); Edits.range(proj, target, overlayItem, in, out);
                time = Math.max(0, Math.min(selectedStart(), proj.duration()));
                changed(); syncPlayers(); rebuildPanel(); timeline.fitSelection();
            }
        });
        sourceViewer.show();
    }

    private VProject.Clip selectedSource() { return selClip()!=null?selClip():selMedia()!=null?selMedia().media:null; }

    private void timingSheet() {
        pause();
        final VProject.Clip source=selectedSource();
        Sheet sheet=new Sheet(act,"Trim and exact times");
        sheet.section("Timeline · "+Timeline.timecode(selectedStart(),proj.fps())+" → "+Timeline.timecode(selectedEnd(),proj.fps()));
        if(selClip()==null)sheet.item("move","Timeline start…",Timeline.timecode(selectedStart(),proj.fps()),new Runnable(){public void run(){exactPlacement();}});
        sheet.item("sliders","Output duration…",Timeline.timecode(selectedEnd()-selectedStart(),proj.fps()),new Runnable(){public void run(){exactDuration();}});
        if(source!=null&&source.kind==VProject.VIDEO&&source.frameUs<0){
            sheet.section("Source file · Out is exclusive");
            sheet.item("mark_in","Source In / Out…",Timeline.timecode(source.in,proj.fps())+" → "+Timeline.timecode(source.out,proj.fps()),new Runnable(){public void run(){exactSourceRange();}});
            sheet.item("play","Preview and trim source…",new Runnable(){public void run(){openTrim();}});
        }
        sheet.section("Trim at playhead · "+Timeline.timecode(time,proj.fps()));
        sheet.item("mark_in","Trim start to playhead",new Runnable(){public void run(){trimHere(true);}})
            .item("mark_out","Trim end after this frame","Keeps the frame under the playhead; Out stays exclusive",new Runnable(){public void run(){trimHere(false);}}).show();
    }

    private void exactSourceRange() {
        if(!timingEditable())return;
        pause();applyPreview();
        final VProject.TextItem item=selMedia();final VProject.Clip source=selectedSource();
        if(source==null||source.kind!=VProject.VIDEO||source.frameUs>=0)return;
        RangeInput.show(act,source.copy(),proj.fps(),source.in,source.out,item==null
                ?"This changes the clip's duration. Later main clips and connected items follow the magnetic timeline."
                :"B-roll stays at its timeline start. Its duration changes; main footage stays in place.",new RangeInput.Result(){public void apply(long in,long out){
            if(source.in==in&&source.out==out)return;
            beginEdit();Edits.range(proj,source,item,in,out);changed();syncPlayers();scrubTo(selectedStart());timeline.fitSelection();rebuildPanel();
        }});
    }

    long[] snapTargets() { return TimelineSnapping.targets(proj,snapOn,snapEdges,snapBeats,snapMarkers,selectingMany?batchIds:java.util.Collections.singleton(selId),time); }
    private void saveSnapOptions() {
        act.getSharedPreferences("ui",0).edit().putBoolean("snapOn",snapOn).putBoolean("snapEdges",snapEdges).putBoolean("snapBeats",snapBeats).putBoolean("snapMarkers",snapMarkers).apply();
        snapBtn.setText(snapOn?"Snap on":"Snap off");snapBtn.setTextColor(snapOn?Ui.ACCENT:Ui.DIM);snapBtn.setBackground(Ui.round(snapOn?0xff352c1e:Ui.PANEL2,8,snapOn?Ui.ACCENT:Ui.LINE));timeline.invalidate();
    }
    // ---------------------------------------------------------------- 3.14: saved speed curves
    private void saveSpeedCurve() {
        final VProject.Clip k = selClip();
        if (k == null || k.curve == null) return;
        final SpeedCurve sc = k.curve.copy();
        Pickers.text(act, "Name this speed curve", "My ramp", false, new Pickers.TextCb() {
            @Override public void text(String s) {
                try { Presets.save(Presets.SPEED, s, sc.toJson()); toast("Saved \u201c" + Presets.cleanName(s) + "\u201d. It works on any clip: points are relative to the clip's footage."); }
                catch (Exception e) { toast("Couldn't save: " + e.getMessage()); }
                rebuildPanel();
            }
        });
    }

    private void manageSpeedCurves() {
        Sheet sh = new Sheet(act, "Saved speed curves");
        for (final Presets.Preset pr : Presets.list(Presets.SPEED)) {
            sh.item("pencil", "Rename \u201c" + pr.name + "\u201d", new Runnable() { @Override public void run() {
                Pickers.text(act, "Rename speed curve", pr.name, false, new Pickers.TextCb() {
                    @Override public void text(String s) { try { Presets.rename(pr, s); } catch (Exception e) { toast("Couldn't rename: " + e.getMessage()); } rebuildPanel(); }
                });
            } });
            sh.danger("trash", "Delete \u201c" + pr.name + "\u201d", new Runnable() { @Override public void run() { Presets.delete(pr); rebuildPanel(); } });
        }
        sh.show();
    }

    private void setTransition(int k) {
        VProject.Clip c = selClip();
        if (c == null || c.trans == k) return;
        beginEdit(); c.trans = k; changed(); syncPlayers(); rebuildPanel();
    }

    /** Transition picker: grouped tiles with a preview of each (rendered at build time from transition.frag). */
    private void transitionSheet() {
        VProject.Clip c = selClip();
        if (c == null) return;
        Sheet sh = new Sheet(act, "Transition to next clip");
        for (int g = 0; g < VProject.TRANS_GROUPS.length; g++) {
            sh.section(VProject.TRANS_GROUPS[g]);
            for (final int k : VProject.TRANS_GROUP_TYPES[g]) {
                sh.tile(k == c.trans ? "check" : k == 0 ? "split" : "fx", VProject.TRANS[k], new Runnable() { @Override public void run() { setTransition(k); } });
                if (k > 0) {
                    Bitmap b = transitionThumb(k);
                    if (b != null) Sheet.setThumb(sh.lastTileDisc(), b);
                }
            }
        }
        sh.show();
    }

    private final android.util.SparseArray<Bitmap> transThumbs = new android.util.SparseArray<Bitmap>();

    private Bitmap transitionThumb(int k) {
        Bitmap b = transThumbs.get(k);
        if (b != null) return b;
        try {
            java.io.InputStream in = act.getAssets().open("transitions/t" + k + ".jpg");
            try { b = android.graphics.BitmapFactory.decodeStream(in); } finally { in.close(); }
        } catch (Exception e) { return null; }
        if (b != null) transThumbs.put(k, b);
        return b;
    }

    /** Rolls the cut after clip i so the transition's middle (or the cut itself) lands on the nearest beat. */
    private void centerCutOnBeat(int i) {
        pause(); applyPreview();
        VProject before = proj.copy();
        beginEdit();
        long at = centerOnBeat(before, proj, i);
        changed(); syncPlayers(); rebuildPanel();
        if (at < 0) { toast("No beat within reach of this cut."); return; }
        scrubTo(at);
        act.snack("Cut moved onto the beat at " + Timeline.timecode(at, proj.fps()), "Undo", new Runnable() { @Override public void run() { doUndo(); } });
    }

    /**
     * Pure: rolls cut i (clip i -> i+1) so the middle of its transition (the cut, for a plain cut) sits
     * on the nearest beat the roll can reach. Returns that beat's time, or -1 (no beats, or none
     * reachable). Uses roll(), so media limits and connected items behave as a dragged roll.
     */
    static long centerOnBeat(VProject before, VProject proj, int i) {
        if (i < 0 || i >= proj.clips.size() - 1) return -1;
        long[] beats = proj.beatTimes();
        if (beats.length == 0) return -1;
        VProject.Clip a = before.clips.get(i);
        long tr = a.trans == 0 ? 0 : Math.min(a.transDur, a.dur());
        long cut = before.start(i) + a.dur();
        long mid = cut - tr / 2;
        long best = -1;
        for (long b : beats) if (best < 0 || Math.abs(b - mid) < Math.abs(best - mid)) best = b;
        if (best < 0) return -1;
        roll(before, proj, i, best - mid, new long[0], 0);
        long newMid = proj.start(i) + proj.clips.get(i).dur() - tr / 2;
        return Math.abs(newMid - best) <= Timeline.frame(proj.fps()) ? best : -1;
    }

    /** The selected clip's transition and length on every cut, as one undo step. */
    private void transitionToAll() {
        VProject.Clip c = selClip();
        if (c == null) return;
        pause(); applyPreview(); beginEdit();
        int[] r = Edits.transitionToAll(proj, c.trans, c.transDur);
        changed(); syncPlayers(); rebuildPanel();
        String what = c.trans == 0 ? "Plain cuts" : VProject.TRANS[c.trans];
        String msg = r[0] == 0 && r[1] == 0 ? "Every cut already uses " + what.toLowerCase(Locale.US) + "."
                : what + " on " + r[0] + (r[0] == 1 ? " cut" : " cuts") + (r[1] > 0 ? "; " + r[1] + " left as they were (timing locked)" : "");
        act.snack(msg, "Undo", new Runnable() { @Override public void run() { doUndo(); } });
    }

    // ================================================================ 3.13: viewer
    void setFullView(boolean on) {
        fullView = on;
        applyChrome();
        immersive(on && landscape());
    }

    private void guidesSheet() {
        Sheet sh = new Sheet(act, "Viewer guides");
        for (int i = 0; i < ViewGuides.COUNT; i++) {
            final int m = i;
            if (!ViewGuides.available(m, proj.outW(), proj.outH())) continue;
            sh.item(m == guideMode ? "check" : "guides", ViewGuides.NAMES[m],
                    m == ViewGuides.SAFE ? "SMPTE ST 2046-1: action 93 %, title 90 %" : m == ViewGuides.SOCIAL ? "Where Reels / TikTok / Shorts put their own text and buttons; check in the app" : null,
                    new Runnable() { @Override public void run() { setGuides(m); } });
        }
        sh.show();
    }

    void setGuides(int m) {
        guideMode = ViewGuides.available(m, proj.outW(), proj.outH()) ? m : 0;
        act.getSharedPreferences("ui", 0).edit().putInt("guides", guideMode).apply();
        overlay.invalidate();
    }

    /**
     * Save frame (Premiere mobile "Save frame to Photos"; Resolve "Export current frame"): the frame
     * under the playhead at the project's output size, rendered by the preview compositor, written
     * to Pictures/Emulsion as PNG. Video comes from the decoded frame at full size; photos and titles
     * from the preview's sources.
     */
    private void saveFrame() {
        if (proj.clips.isEmpty() && proj.texts.isEmpty()) { toast("Nothing to save yet."); return; }
        pause(); applyPreview();
        final int W = proj.outW(), H = proj.outH();
        final long at = time;
        final String name = Projects.name(dir) + " " + Timeline.timecode(at, proj.fps()).replace(':', '-');
        showStatus("Saving frame\u2026");
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null; String err = null;
                try { bmp = renderer.grab(W, H, at); } catch (Throwable t) { err = t.getMessage() == null ? t.toString() : t.getMessage(); }
                final Bitmap b = bmp; final String e = err;
                if (b == null) { h.post(new Runnable() { @Override public void run() { showStatus(null); toast("Couldn't save the frame: " + e); } }); return; }
                bg2.execute(new Runnable() {
                    @Override public void run() {
                        Uri uri = null; String fail = null;
                        try { uri = writeFramePng(b, name); } catch (Throwable t) { fail = t.getMessage(); }
                        b.recycle();
                        final Uri u = uri; final String f = fail;
                        h.post(new Runnable() {
                            @Override public void run() {
                                showStatus(null);
                                if (u == null) { toast("Couldn't save the frame: " + f); return; }
                                act.snack("Frame saved to Pictures/Independent Video", "Open in photo editor", new Runnable() {
                                    @Override public void run() { act.closeWorkspaceThenOpenPhoto(u); }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    private Uri writeFramePng(Bitmap b, String name) throws Exception {
        android.content.ContentResolver cr = act.getContentResolver();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name + ".png");
        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
        v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Independent Video");
        v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("Pictures refused the file");
        try {
            java.io.OutputStream o = cr.openOutputStream(uri);
            if (o == null) throw new Exception("can't write");
            try { if (!b.compress(Bitmap.CompressFormat.PNG, 100, o)) throw new Exception("PNG encode failed"); } finally { o.close(); }
            v.clear();
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        } catch (Exception e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ================================================================ 3.13: markers
    /** Adds a marker on the frame under the playhead; while playing, each tap marks where you are (tap along to a song). */
    void addMarkerHere() {
        long f = Timeline.frame(proj.fps());
        long t = Timeline.quant(time, proj.fps());
        if (proj.markerNear(t, f / 2) != null) { markerSheet(proj.markerNear(t, f / 2)); return; }
        beginEdit();
        proj.addMarker(t, f / 2);
        changed();
        timeline.invalidate();
        if (!playing) act.snack("Marker at " + Timeline.timecode(t, proj.fps()), "Undo", new Runnable() { @Override public void run() { doUndo(); } });
    }

    void jumpMarker(int dir) {
        long t = proj.markerStep(time, dir, Timeline.frame(proj.fps()) / 2);
        if (t < 0) { toast(dir > 0 ? "No marker after the playhead." : "No marker before the playhead."); return; }
        pause(); scrubTo(t);
    }

    /** All markers: add, previous / next, and the list (tap one to go there and edit it). */
    void markersSheet() {
        final long f = Timeline.frame(proj.fps());
        Sheet sh = new Sheet(act, proj.markers.isEmpty() ? "Markers" : "Markers \u00b7 " + proj.markers.size());
        sh.item("marker", "Add marker at playhead", "Also while playing: tap the marker button on the beat", new Runnable() { @Override public void run() { addMarkerHere(); } });
        if (!proj.markers.isEmpty()) {
            sh.item("frame_back", "Previous marker", new Runnable() { @Override public void run() { jumpMarker(-1); } });
            sh.item("frame_fwd", "Next marker", new Runnable() { @Override public void run() { jumpMarker(1); } });
            sh.section("On the timeline");
            for (final VProject.Marker m : proj.markers) {
                String label = Timeline.timecode(m.t, proj.fps()) + (m.note.length() > 0 ? " \u00b7 " + m.note : "");
                sh.item("marker", label, VProject.MARKER_COLOR_NAMES[m.color], new Runnable() { @Override public void run() { pause(); scrubTo(m.t); markerSheet(m); } });
            }
            sh.section("Remove");
            sh.danger("trash", "Delete all markers", new Runnable() { @Override public void run() {
                beginEdit(); proj.markers.clear(); changed(); timeline.invalidate();
                act.snack("Markers deleted", "Undo", new Runnable() { @Override public void run() { doUndo(); } });
            } });
        }
        sh.show();
    }

    /** One marker: note, colour, delete. Edits find it again by position (undo replaces the project). */
    void markerSheet(final VProject.Marker m) {
        final long at = m.t;
        Sheet sh = new Sheet(act, "Marker \u00b7 " + Timeline.timecode(at, proj.fps()) + (m.note.length() > 0 ? " \u00b7 " + m.note : ""));
        sh.item("pencil", m.note.length() > 0 ? "Edit note" : "Add a note", new Runnable() { @Override public void run() {
            Pickers.text(act, "Marker note", m.note, false, new Pickers.TextCb() {
                @Override public void text(String s) {
                    VProject.Marker k = proj.markerNear(at, 0); if (k == null) return;
                    beginEdit(); k.note = s.length() > 200 ? s.substring(0, 200) : s; changed(); timeline.invalidate();
                }
            });
        } });
        sh.section("Colour");
        for (int i = 0; i < VProject.MARKER_COLORS.length; i++) {
            final int q = i;
            sh.item(i == m.color ? "check" : "marker", VProject.MARKER_COLOR_NAMES[i], new Runnable() { @Override public void run() {
                VProject.Marker k = proj.markerNear(at, 0); if (k == null) return;
                beginEdit(); k.color = q; changed(); timeline.invalidate();
            } });
        }
        sh.section("Remove");
        sh.danger("trash", "Delete marker", new Runnable() { @Override public void run() {
            VProject.Marker k = proj.markerNear(at, 0); if (k == null) return;
            beginEdit(); proj.markers.remove(k); changed(); timeline.invalidate();
        } });
        sh.show();
    }

    private void snapOptions() {
        new Sheet(act,"Timeline snapping · frames stay aligned")
            .item("check",snapOn?"Magnets: on":"Magnets: off","Snap to zero and the playhead, plus enabled targets",new Runnable(){public void run(){snapOn=!snapOn;saveSnapOptions();snapOptions();}})
            .item("film",snapEdges?"Item edges: on":"Item edges: off",new Runnable(){public void run(){snapEdges=!snapEdges;saveSnapOptions();snapOptions();}})
            .item("music",snapBeats?"Music beats: on":"Music beats: off","Uses the enabled beat guide; the selected item's own edges are excluded",new Runnable(){public void run(){snapBeats=!snapBeats;saveSnapOptions();snapOptions();}})
            .item("marker",snapMarkers?"Markers: on":"Markers: off","The markers on the ruler",new Runnable(){public void run(){snapMarkers=!snapMarkers;saveSnapOptions();snapOptions();}}).show();
    }
    private void exactPlacement() { exactTimeDialog("Timeline position", selectedStart(), new TimeSet() {
        @Override public void set(long value) {
            if(!timingEditable())throw new IllegalArgumentException("Unlock timing before changing placement.");
            pause(); applyPreview(); beginEdit();
            if (selOverlay() != null) selOverlay().start = value;
            else if (selAudio() != null) selAudio().start = value;
            changed(); scrubTo(value); dragDone();
        }
    }); }
    private void exactDuration() { exactTimeDialog("Item duration", selectedEnd() - selectedStart(), new TimeSet() {
        @Override public void set(long value) {
            if(!timingEditable())throw new IllegalArgumentException("Unlock timing before changing duration.");
            if (value < Timeline.frame(proj.fps()) - 1) throw new IllegalArgumentException("Leave at least one frame.");
            VProject.Clip source=selectedSource();
            long max=86400000000L;
            if(selAudio()!=null)max=selAudio().srcDur-selAudio().in;
            else if(selMedia()!=null)max=VProject.overlayMaxDur(selMedia());
            else if(source!=null){VProject.Clip trial=source.copy();trial.out=source.kind==VProject.IMAGE?600000000L:source.srcDur;max=trial.dur();}
            if(value>max+1)throw new IllegalArgumentException("The source allows up to "+Timeline.timecode(max,proj.fps())+" from this In point.");
            pause(); applyPreview(); beginEdit();
            dragEdit(2, value - (selectedEnd() - selectedStart()), new long[0], 0); dragDone();
        }
    }); }
    interface TimeSet { void set(long value); }
    private void exactTimeDialog(String title, long initial, final TimeSet callback) {
        final android.widget.EditText input = new android.widget.EditText(act);
        input.setSingleLine(true); input.setText(Timeline.timecode(initial, proj.fps()));
        input.setSelectAllOnFocus(true); input.setHint("Seconds, m:ss.sss or m:ss:ff");
        final AlertDialog dialog = new AlertDialog.Builder(act).setTitle(title).setView(input)
                .setMessage("Enter seconds, m:ss.sss or m:ss:ff (frames).")
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface ignored) {
            dialog.getButton(-1).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                try { long value = EditTime.parse(input.getText().toString(), proj.fps()); callback.set(value); dialog.dismiss(); }
                catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
            }});
        }}); dialog.show();
    }
    private void barBtn(String icon, String label, final Runnable r) {
        LinearLayout t = Ui.tab(act, icon, label, false, new View.OnClickListener() {
            @Override public void onClick(View v) { Crash.note("clip bar " + v.getTag()); r.run(); }
        });
        t.setTag(label);
        clipBar.addView(t, new LinearLayout.LayoutParams(Ui.dp(56), -1));
    }

    /** The selected clip's own file in the source viewer, with its current In and Out. */
    private void openClipSource() {
        VProject.Clip c = selClip();
        if (c == null || c.kind != VProject.VIDEO) { toast("The source viewer works on video clips."); return; }
        showSource(Edits.fromSource(c, c.in, c.out), c.in, c.out, c.in);
    }

    private void showSource(VProject.Clip probe, long in, long out, long at) {
        pause();
        if (previewBase != null) applyPreview();
        Crash.note("source viewer");
        sourceViewer = new SourceViewer(act, probe, proj.fps(), in, out, at, true, new SourceViewer.Action() {
            @Override public void edit(int what, VProject.Clip p, long i, long o) { sourceEdit(what, p, i, o); }
        });
        sourceViewer.show();
    }

    /** A picked file for the source viewer (or straight in, for a photo). */
    private void openSourceFile(final Uri u, final boolean replace) {
        showStatus("Reading media…");
        bg.execute(new Runnable() {
            @Override public void run() {
                final VProject.Clip c = new VProject.Clip();
                c.uri = u.toString();
                c.name = act.displayName(u, "Clip");
                String type = act.getContentResolver().getType(u);
                String err = null;
                try {
                    if (type != null && type.startsWith("image/")) {
                        Bitmap b = Media.decodeImage(act.getContentResolver(), u, 2048);
                        c.kind = VProject.IMAGE;
                        c.still = b;
                        c.srcW = b.getWidth();
                        c.srcH = b.getHeight();
                        c.srcDur = Long.MAX_VALUE / 4;
                    } else Media.probeVideo(act, u, c);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String er = err;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (closed) return;
                        if (er != null) { toast("Can't read " + c.name + ": " + er); return; }
                        if (c.kind == VProject.IMAGE) {
                            // a photo has no In/Out: replace keeps the clip's length, a new one is 3 s
                            sourceEdit(replace ? SourceViewer.REPLACE_KEEP : SourceViewer.INSERT, c, 0, 3000000);
                            return;
                        }
                        showSource(c, 0, c.srcDur, 0);
                    }
                });
            }
        });
    }

    /** Applies the source viewer's edit as one undo step. */
    private void sourceEdit(int what, VProject.Clip probe, long in, long out) {
        if (closed) return;
        if (previewBase != null) applyPreview();
        pause();
        long F = Timeline.frame(proj.fps());
        VProject.Clip nc = Edits.fromSource(probe, in, out);
        if (what == SourceViewer.REPLACE_KEEP || what == SourceViewer.REPLACE_MARKED) {
            VProject.Clip c = selKind == TimelineView.SEL_CLIP ? selClip() : null;
            if (c == null) { toast("Select a clip on the main track to replace it."); return; }
            Crash.note("replace " + what);
            beginEdit();
            boolean fit = Edits.replace(c, nc, what == SourceViewer.REPLACE_KEEP);
            releaseAllPlayers();   // its player was opened on the old file
            loadClipAssets(c);
            clipBarFor = -2;
            changed();
            syncPlayers();
            rebuildPanel();
            toast(fit ? "Replaced the footage of " + c.name + "." : "The new footage is shorter than the clip, so all of it is used.");
            return;
        }
        Crash.note("source edit " + what);
        beginEdit();
        long t = what == SourceViewer.APPEND ? Long.MAX_VALUE / 4 : Timeline.quant(time, proj.fps());
        int at = what == SourceViewer.OVERWRITE ? Edits.overwrite(proj, t, nc, F) : Edits.insert(proj, t, nc, F);
        Edits.defaultTransition(proj, nc.id);
        loadClipAssets(nc);
        changed();
        select(TimelineView.SEL_CLIP, nc.id, false);
        scrubTo(proj.start(at) + nc.dur());   // the playhead lands after the new clip, ready for the next edit
        syncPlayers();
        String[] verbs = {"Inserted", "Overwrote with", "Appended"};
        toast(verbs[what] + " " + TimelineView.fmtDur(nc.dur()) + " of " + nc.name + ".");
    }

    /** The frame on one side of an edit for the two-up view: clip i's first or last frame. */
    private TwoUp.Side side(int i, boolean last, String what) {
        if (i < 0 || i >= proj.clips.size()) return null;
        VProject.Clip c = proj.clips.get(i);
        TwoUp.Side s = new TwoUp.Side();
        int fps = proj.fps();
        long F = Timeline.frame(fps);
        if (c.kind == VProject.IMAGE) {
            s.still = c.still;
            s.label = what + " \u00b7 " + c.name;
            return s;
        }
        long src = last ? Math.max(c.in, Math.min(c.out - 1, c.srcAt(Math.max(0, c.dur() - F)))) : c.in;
        s.uri = c.uri;
        s.us = src;
        s.label = what + " " + Timeline.timecode(src, fps);
        return s;
    }

    /** Two-up while trimming, rolling or slipping a main-track clip. */
    private void twoUpFor(int kind) {
        if (selKind != TimelineView.SEL_CLIP) return;
        int i = proj.clipIndex(selId);
        if (i < 0) return;
        if (kind == 1) twoUp.show(side(i - 1, true, "Out"), side(i, false, "In"));
        else if (kind == 2 || kind == 4) twoUp.show(side(i, true, "Out"), side(i + 1, false, "In"));
        else if (kind == 5) twoUp.show(side(i, false, "First"), side(i, true, "Last"));
    }

    void select(int kind, int id, boolean trans) {
        if(selectingMany){selectingMany=false;batchIds.clear();clipBarFor=-2;}
        if (previewBase != null && (kind != selKind || id != selId)) applyPreview();   // the preview belongs to the old target
        renderer.soloId = 0;
        if(kind!=selKind||id!=selId){moveItems=false;slip=false;}
        selKind = kind;
        selId = id;
        transFocus = trans;
        gradeSelId = -1;
        if (kind == TimelineView.SEL_TEXT && tab != T_TEXT) tab = T_TEXT;
        else if (kind == TimelineView.SEL_VIZ && tab != T_VIZ) tab = T_VIZ;
        else if (kind == TimelineView.SEL_AUDIO && tab != T_AUDIO) tab = T_AUDIO;
        else if (kind == TimelineView.SEL_MEDIA && tab != T_CLIPS && tab != T_GRADE && tab != T_AUDIO) tab = T_CLIPS;
        if (tab == T_GRADE && (gradeTarget == G_CLIP || gradeTarget == G_BROLL)) gradeTarget = kind == TimelineView.SEL_MEDIA ? G_BROLL : G_CLIP;
        else if (kind == TimelineView.SEL_CLIP && tab != T_CLIPS && tab != T_GRADE && tab != T_AUDIO) tab = T_CLIPS;
        buildTabs();
        timeline.revealSelection();
        rebuildPanel();
        refreshClipBar();
        timeline.invalidate();
        overlay.invalidate();
    }

    // ================================================================ thumbnails, waveforms, beats
    /** UI thread (timeline drawing). Nearest cached frame; re-fetches frames the budget dropped. */
    Bitmap thumbFor(final String uri, long us) {
        Bitmap b = thumbs.get(uri, us, wantT);
        final long t = wantT[0];
        if (t >= 0 && thumbsFilled.contains(uri) && !closed && refills.size() < 64 && refills.add(uri + "\n" + t)) {
            final int hpx = Ui.dp(54);
            bg2.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (cancelBg.get() || thumbs.has(uri, t)) return;
                        Bitmap f = Media.thumb(act, Uri.parse(uri), t, hpx);
                        if (f != null) thumbs.put(uri, t, f);
                        else thumbs.giveUp(uri, t);
                        timeline.postInvalidate();
                    } finally {
                        h.post(new Runnable() { @Override public void run() { refills.remove(uri + "\n" + t); } });
                    }
                }
            });
        }
        return b;
    }

    float[] peaksFor(String uri) { return peaks.get(uri); }

    private void loadClipAssets(final VProject.Clip c) {
        if (c.kind == VProject.IMAGE) {
            if (c.still == null) bg.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final Bitmap b = c.frameUs >= 0 ? Media.frame(act, Uri.parse(c.uri), c.frameUs, 2048)
                                : Media.decodeImage(act.getContentResolver(), Uri.parse(c.uri), 2048);
                        if (b == null) throw new java.io.IOException("unreadable");
                        h.post(new Runnable() {
                            @Override public void run() {
                                // the same picture: same file and (for freeze frames) the same moment of it
                                for (VProject.Clip k : proj.clips) if (k.kind == VProject.IMAGE && k.uri.equals(c.uri) && k.frameUs == c.frameUs) k.still = b;
                                for (VProject.TextItem t : proj.texts) if (t.media != null && t.media.kind == VProject.IMAGE && t.media.uri.equals(c.uri) && c.frameUs < 0) t.media.still = b;
                                if (c.still == null) c.still = b;
                                changed();
                            }
                        });
                    } catch (Exception e) {
                        h.post(new Runnable() { @Override public void run() { toast("Can't open " + c.name + " (moved or deleted?)"); } });
                    }
                }
            });
            return;
        }
        if (thumbJobs.contains(c.uri)) return;
        thumbJobs.add(c.uri);
        final long dur = c.srcDur;
        final long step = Math.max(1000000L, dur / 60);
        thumbs.setSpacing(c.uri, step);
        bg2.execute(new Runnable() {
            @Override public void run() {
                int hpx = Ui.dp(54);
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(act, Uri.parse(c.uri));   // opened once for the whole pass
                    // coarse pass first so the whole clip gets pictures quickly, then fill in
                    for (int pass = 0; pass < 2 && !cancelBg.get(); pass++) {
                        long s = pass == 0 ? step * 4 : step;
                        for (long t = pass == 0 ? 0 : step; t < dur && !cancelBg.get(); t += s) {
                            if (pass == 1 && thumbs.has(c.uri, t)) continue;
                            Bitmap b = null;
                            try { b = r.getScaledFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, hpx * 3, hpx); }
                            catch (Exception ignored) {}
                            if (b != null) thumbs.put(c.uri, t, b);
                            timeline.postInvalidate();
                        }
                    }
                } catch (Exception ignored) {
                    // unreadable file: the clip shows its placeholder colour
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                    thumbsFilled.add(c.uri);
                }
            }
        });
    }

    void requestPeaks(final String uri) {
        if (uri == null || peakJobs.contains(uri)) return;
        peakJobs.add(uri);
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    Object[] r = Media.analyze(act, Uri.parse(uri), false, cancelBg);
                    if (r != null) peaks.put(uri, (float[]) r[0]);
                    duckVer = -1;   // the ducking curve can now include this file
                    timeline.postInvalidate();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Detects the beats of the sync track's song. asEdit: the user asked (Re-detect), so it is an
     * undo step. Otherwise it is the analysis that follows picking a sync track: the result is
     * filled into the current project and into undo/redo states synced to the same song, so it
     * isn't an extra step and undoing other edits doesn't lose it.
     */
    private void detectBeats(final boolean asEdit) {
        VProject.AudioItem sm = proj.syncItem();
        if (sm == null) { toast("Choose a track under Beat guide / snapping first."); return; }
        final String uri = sm.uri;
        showStatus("Finding the beat…");
        bg.execute(new Runnable() {
            @Override public void run() {
                String msg;
                Beats.Result res = null;
                try {
                    Object[] r = Media.analyze(act, Uri.parse(uri), true, cancelBg);
                    if (r == null) throw new Exception("no audio track");
                    peaks.put(uri, (float[]) r[0]);
                    res = Beats.analyze((float[]) r[1]);
                    msg = res.beats.length == 0 ? "No steady beat found." : String.format(Locale.US, "%d beats at %.1f BPM", res.beats.length, res.bpm);
                } catch (Throwable t) {
                    msg = "Beat detection failed: " + t.getMessage();
                }
                final Beats.Result rr = res;
                final String m = msg;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (closed) return;
                        VProject.AudioItem now = proj.syncItem();
                        if (now == null || !uri.equals(now.uri)) return;   // sync was changed meanwhile
                        if (rr != null && rr.beats.length > 0) {
                            if (asEdit) beginEdit();
                            else {
                                for (VProject q : undo) fillBeats(q, uri, rr);
                                for (VProject q : redo) fillBeats(q, uri, rr);
                            }
                            proj.controlBeats.put(uri,rr);
                            proj.beats = rr.beats;
                            proj.bpm = rr.bpm;
                            proj.beatsUri = uri;
                            changed();
                            rebuildPanel();
                        }
                        toast(m);
                    }
                });
            }
        });
    }

    interface BSet { void set(boolean on); }

    /** Adaptive / Whole song: what a music level is measured against. */
    private View responseChips(final boolean adapt, final BSet set) {
        LinearLayout c = Ui.col(act);
        c.addView(Ui.strip(act,
                chip("Adaptive", adapt, new View.OnClickListener() { @Override public void onClick(View v) { if (adapt) return; beginEdit(); set.set(true); changed(); rebuildPanel(); } }),
                chip("Level follower", !adapt, new View.OnClickListener() { @Override public void onClick(View v) { if (!adapt) return; beginEdit(); set.set(false); changed(); rebuildPanel(); } })));
        c.addView(hint(adapt ? "Adaptive: continuous level with extra emphasis on changes; use Hits for transient-only control."
                : "Level follower: follows the source level continuously, relative to the whole recording."));
        return c;
    }

    private static void fillBeats(VProject q, String uri, Beats.Result r) {
        VProject.AudioItem m = q.syncItem();
        if (m == null || !uri.equals(m.uri) || uri.equals(q.beatsUri)) return;
        q.beats = r.beats; q.bpm = r.bpm; q.beatsUri = uri;
    }

    /** Music sync: Off, or the audio item whose song drives the edit. */
    private void setSync(int id) {
        if (proj.syncId == id || (id != 0 && proj.audioById(id) == null)) return;
        beginEdit();
        proj.syncId = id;
        changed();
        rebuildPanel();
        VProject.AudioItem m = proj.syncItem();
        if (m != null && proj.controlBeats.containsKey(m.uri)) {
            Beats.Result b = proj.controlBeats.get(m.uri); proj.beats = b.beats; proj.bpm = b.bpm; proj.beatsUri = m.uri; changed(); rebuildPanel();
        }
    }

    /** Off, then one chip per audio item; the selected one drives music sync. */
    private View syncChips() {
        View[] v = new View[proj.audio.size() + 1];
        v[0] = chip("Off", proj.syncId == 0, new View.OnClickListener() { @Override public void onClick(View x) { setSync(0); } });
        for (int i = 0; i < proj.audio.size(); i++) {
            final VProject.AudioItem a = proj.audio.get(i);
            v[i + 1] = chip("\u266a " + syncName(a), proj.syncId == a.id, new View.OnClickListener() { @Override public void onClick(View x) { setSync(a.id); } });
        }
        return Ui.strip(act, v);
    }

    private String syncName(VProject.AudioItem a) {
        String n = a.name == null || a.name.length() == 0 ? "Audio" : a.name;
        int same = 0;
        for (VProject.AudioItem b : proj.audio) if (n.equals(b.name)) same++;
        if (n.length() > 24) n = n.substring(0, 23) + "\u2026";
        if (same > 1) n += " @ " + TimelineView.fmt(a.start, false);
        return n;
    }

    /**
     * For panels whose settings follow the music: says why nothing moves and offers the tracks
     * right there. Returns true if sync is on (and the caller can show its normal hints).
     */
    private boolean syncNeeded(String what) {
        if (proj.syncItem() != null) return true;
        if (proj.audio.isEmpty()) { panel.addView(hint(what + " needs a beat guide: add audio on the Audio tab and select it under Beat guide / snapping.")); return false; }
        panel.addView(hint("Music sync is off, so " + what.toLowerCase(Locale.US) + " stays still. Pick a beat-guide track:"));
        panel.addView(syncChips());
        return false;
    }

    // ================================================================ playback
    final class Player {
        final int id;
        final boolean video;
        MediaPlayer mp;
        android.media.audiofx.LoudnessEnhancer boost;
        int boostMb;
        SurfaceTexture st;
        Surface surface;
        volatile boolean attached, frameAvail, hasFrame, released;
        volatile int oes;
        final VComp.Frame frame = new VComp.Frame();
        boolean prepared, seeking, playing, failed, startAfter;
        long pending = -1, lastTarget = Long.MIN_VALUE, startedAt;
        float speed = 1;
        /** 3.15: pitch follows speed (tape); the preview's equalizer and what it was last set to. */
        boolean tape;
        android.media.audiofx.DynamicsProcessing dp;
        String eqKey = "";

        Player(int id, boolean video) { this.id = id; this.video = video; frame.oes = true; }
    }

    private Player ensure(int id, String uri, boolean video) {
        Player pl = players.get(id);
        if (pl != null) return pl;
        final Player np = new Player(id, video);
        try {
            np.mp = new MediaPlayer();
            if (video) {
                np.st = new SurfaceTexture(false);
                np.st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                    @Override public void onFrameAvailable(SurfaceTexture s) { np.frameAvail = true; gl.requestRender(); }
                });
                np.surface = new Surface(np.st);
                np.mp.setSurface(np.surface);
            }
            np.mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(video ? AudioAttributes.CONTENT_TYPE_MOVIE : AudioAttributes.CONTENT_TYPE_MUSIC).build());
            np.mp.setDataSource(act, Uri.parse(uri));
            np.mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) { np.prepared = true; syncPlayers(); }
            });
            np.mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override public void onSeekComplete(MediaPlayer m) { seekDone(np); }
            });
            np.mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) { np.failed = true; return true; }
            });
            np.mp.prepareAsync();
        } catch (Exception e) {
            np.failed = true;
        }
        players.put(id, np);
        return np;
    }

    private void releasePlayer(final Player pl) {
        players.remove(pl.id);
        pl.released = true;
        if (pl.boost != null) { try { pl.boost.release(); } catch (Throwable ignored) {} pl.boost = null; }
        if (pl.dp != null) { try { pl.dp.release(); } catch (Throwable ignored) {} pl.dp = null; }
        try { pl.mp.release(); } catch (Exception ignored) {}
        if (pl.surface != null) pl.surface.release();
        if (pl.st != null) pl.st.release();
        if (pl.attached) {
            final int t = pl.oes;
            gl.queueEvent(new Runnable() { @Override public void run() { GLES20.glDeleteTextures(1, new int[]{t}, 0); } });
        }
    }

    void releaseAllPlayers() {
        for (Player pl : new ArrayList<Player>(players.values())) releasePlayer(pl);
    }

    private void seek(Player pl, long ms) {
        ms = Math.max(0, ms);
        pl.lastTarget = ms;
        if (pl.seeking) { pl.pending = ms; return; }
        pl.seeking = true;
        try { pl.mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST); } catch (Exception e) { pl.seeking = false; }
    }

    private void seekDone(Player pl) {
        pl.seeking = false;
        if (pl.released) return;
        if (pl.pending >= 0) {
            long m = pl.pending;
            pl.pending = -1;
            seek(pl, m);
            return;
        }
        if (pl.startAfter && playing) {
            pl.startAfter = false;
            try {
                pl.mp.setPlaybackParams(new PlaybackParams().setSpeed(pl.speed).setPitch(pl.tape ? pl.speed : 1f));   // starts playback on most builds…
                if (!pl.mp.isPlaying()) pl.mp.start();                               // …and from Prepared state on all
                pl.playing = true;
                pl.startedAt = SystemClock.uptimeMillis();
            } catch (Exception e) { pl.failed = true; }
        }
    }

    /**
     * Gain above 100% (clip/music volume goes to 200%): MediaPlayer.setVolume stops at 1.0, so the
     * extra goes through a LoudnessEnhancer on the player's audio session, matching the export's gain.
     */
    private void boost(Player pl, float gain) {
        int mb = gain > 1.01f ? Math.round(2000f * (float) Math.log10(gain)) : 0;   // millibels
        if (mb == pl.boostMb) return;
        pl.boostMb = mb;
        try {
            if (mb > 0 && pl.boost == null) pl.boost = new android.media.audiofx.LoudnessEnhancer(pl.mp.getAudioSessionId());
            if (pl.boost != null) {
                pl.boost.setTargetGain(mb);
                pl.boost.setEnabled(mb > 0);
            }
        } catch (Throwable t) { pl.boost = null; }   // some devices refuse audio effects: plain volume then
    }

    /** Drive one player toward source position ms. */
    private void drive(Player pl, long ms, float speed, float gain, boolean master) { drive(pl, ms, speed, gain, master, false, null); }

    private boolean speedQuietWarned;

    private void drive(Player pl, long ms, float speed, float gain, boolean master, boolean tape, AudioEq eq) {
        if (!pl.prepared || pl.failed || pl.released) return;
        // 3.16: beyond 0.25x-4x MediaPlayer can't follow and the re-seeks below would chop the sound
        // into stutters, so the preview is quiet there (the export plays it at the exact speed)
        boolean outside = speed < 0.249f || speed > 4.001f;
        if (outside && playing && !speedQuietWarned) { speedQuietWarned = true; h.post(new Runnable() { @Override public void run() { toast("Sound below 0.25\u00d7 or above 4\u00d7 is silent in the preview; the export has it."); } }); }
        float g = outside ? 0 : Math.max(0, Math.min(1, gain));
        try { pl.mp.setVolume(g, g); } catch (Exception ignored) {}
        boost(pl, outside ? 0 : gain);
        previewEq(pl, eq);
        // MediaPlayer plays 0.25x-4x reliably; beyond that the drift check below re-seeks it
        float ps = Math.max(0.25f, Math.min(4f, speed));
        boolean tapeChanged = tape != pl.tape;
        pl.tape = tape;
        if (pl.playing && (Math.abs(ps - pl.speed) > 0.02f * pl.speed || tapeChanged)) {   // along a speed curve
            try { pl.mp.setPlaybackParams(new PlaybackParams().setSpeed(ps).setPitch(tape ? ps : 1f)); } catch (Exception ignored) {}
        }
        pl.speed = ps;
        if (playing) {
            if (!pl.playing) {
                if (!pl.seeking && !pl.startAfter) { pl.startAfter = true; seek(pl, ms); }
            } else if (!master && !pl.seeking && SystemClock.uptimeMillis() - pl.startedAt > 500) {
                long pos = pl.mp.getCurrentPosition();
                if (Math.abs(pos - ms) > 100 * Math.max(1, speed)) seek(pl, ms + 30);
            }
        } else {
            if (pl.playing) { try { pl.mp.pause(); } catch (Exception ignored) {} pl.playing = false; }
            pl.startAfter = false;
            if (pl.lastTarget != ms) seek(pl, ms);
        }
    }

    /**
     * 3.15: the preview's equalizer, the item's EQ plus the master EQ sampled into the platform's
     * multiband EQ (DynamicsProcessing on the player's session). Export runs the exact biquads.
     */
    private void previewEq(Player pl, AudioEq eq) {
        AudioEq master = proj.masterEq;
        boolean none = (eq == null || eq.flat()) && (master == null || master.flat());
        float[] gains = none ? null : AudioEq.previewGains(eq, master);
        StringBuilder k = new StringBuilder();
        if (gains != null) for (float f : gains) k.append(Math.round(f * 10)).append(',');
        String key = k.toString();
        if (key.equals(pl.eqKey)) return;
        pl.eqKey = key;
        try {
            if (gains == null) { if (pl.dp != null) pl.dp.setEnabled(false); return; }
            if (pl.dp == null) {
                android.media.audiofx.DynamicsProcessing.Config cfg = new android.media.audiofx.DynamicsProcessing.Config.Builder(
                        android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, AudioEq.PREVIEW_BANDS, false, 0, false, 0, false).build();
                pl.dp = new android.media.audiofx.DynamicsProcessing(0, pl.mp.getAudioSessionId(), cfg);
            }
            for (int i = 0; i < AudioEq.PREVIEW_BANDS; i++)
                pl.dp.setPreEqBandAllChannelsTo(i, new android.media.audiofx.DynamicsProcessing.EqBand(true, AudioEq.previewCutoff(i), gains[i]));
            pl.dp.setEnabled(true);
        } catch (Throwable e) {
            // no multiband EQ on this phone: the preview plays unequalized, export still applies it
            if (!eqWarned) { eqWarned = true; h.post(new Runnable() { @Override public void run() { toast("This phone can't preview the equalizer; the export still applies it."); } }); }
        }
    }

    private boolean eqWarned;

    private void preroll(Player pl, long ms) {
        if (!pl.prepared || pl.failed || pl.playing || pl.startAfter) return;
        if (pl.lastTarget != ms) seek(pl, ms);
    }

    /** Creates, positions, starts, pauses and releases players for the current time. */
    void syncPlayers() {
        if (closed) return;
        VProject p = proj;
        long t = time;
        HashSet<Integer> want = new HashSet<Integer>();
        long[] a = p.at(t);
        for (int k = 0; k < 2; k++) {
            int idx = (int) a[k];
            if (idx < 0) continue;
            VProject.Clip c = p.clips.get(idx);
            if (c.kind != VProject.VIDEO) continue;
            want.add(c.id);
            float w = a[1] < 0 ? 1 : (k == 0 ? 1 - a[2] / 1e6f : a[2] / 1e6f);
            float gain = (c.mute || !c.hasAudio || c.curve != null) ? 0 : c.volume * w * p.mainSoundGain(t);   // a ramp's sound is muted
            long src = p.srcTime(idx, t);
            drive(ensure(c.id, c.uri, true), src / 1000, c.speedAtSrc(src), gain, k == 0, c.tape && c.curve == null, c.eq);
        }
        if (playing) {
            int next = (int) (a[1] >= 0 ? a[1] : a[0]) + 1;
            if (a[0] < 0) {
                long[] st = p.starts();
                next = -1;
                for (int i = 0; i < st.length; i++) if (st[i] > t) { next = i; break; }
            }
            if (next >= 0 && next < p.clips.size()) {
                VProject.Clip c = p.clips.get(next);
                if (c.kind == VProject.VIDEO && p.start(next) - t < 1500000) {
                    want.add(c.id);
                    preroll(ensure(c.id, c.uri, true), c.in / 1000);
                }
            }
        }
        for (VProject.TextItem ti : p.texts) {
            VProject.Clip m = ti.media;
            if (m == null || m.kind != VProject.VIDEO || !ti.layer.visible) continue;
            if (t >= ti.start && t < ti.start + ti.dur) {
                want.add(m.id);
                float g = (m.mute || !m.hasAudio) ? 0 : m.volume * VProject.overlaySoundFade(ti, t);
                drive(ensure(m.id, m.uri, true), VProject.overlaySrc(ti, t) / 1000, m.speed, g, false, m.tape, m.eq);
            } else if (playing && ti.start > t && ti.start - t < 1500000) {
                want.add(m.id);
                preroll(ensure(m.id, m.uri, true), m.in / 1000);
            }
        }
        for (VProject.AudioItem au : p.audio) {
            if (t >= au.start && t < au.start + au.dur()) {
                want.add(au.id);
                float g = VProject.musicVol(au, t) * Duck.at(previewDuck(), t) * VProject.audioSoundFade(au, t);
                drive(ensure(au.id, au.uri, false), (au.in + t - au.start) / 1000, 1, g, false, false, au.eq);
            } else if (playing && au.start > t && au.start - t < 1500000) {
                want.add(au.id);
                preroll(ensure(au.id, au.uri, false), au.in / 1000);
            }
        }
        for (Player pl : new ArrayList<Player>(players.values())) if (!want.contains(pl.id)) releasePlayer(pl);
    }

    private final Runnable tickRun = new Runnable() { @Override public void run() { tick(); } };

    private void tick() {
        if (!playing || closed) return;
        long now = SystemClock.elapsedRealtimeNanos();
        long t = playT0 + (now - playSys0) / 1000;
        // the playing clip is the master clock
        long[] a = proj.at(t);
        if (a[0] >= 0) {
            VProject.Clip c = proj.clips.get((int) a[0]);
            Player pl = players.get(c.id);
            if (c.kind == VProject.VIDEO && pl != null && pl.playing && !pl.seeking
                    && SystemClock.uptimeMillis() - pl.startedAt > 150) {
                long pos = pl.mp.getCurrentPosition() * 1000L;
                long tv = proj.start((int) a[0]) + c.localAt(pos);
                if (Math.abs(tv - t) > 40000 && tv >= proj.start((int) a[0])) {
                    t = tv;
                    playT0 = t;
                    playSys0 = now;
                }
            }
        }
        if (loopFrom >= 0 && loopTo > loopFrom && t >= loopTo) {
            t = loopFrom; playT0 = t; playSys0 = now;
        }
        long dur = proj.duration();
        if (t >= dur) {
            time = dur;
            pause();
            renderer.time = time;
            changedTimeOnly();
            return;
        }
        time = t;
        syncPlayers();
        renderer.time = t;
        changedTimeOnly();
        h.postDelayed(tickRun, 16);
    }

    private void changedTimeOnly() {
        applyKeys();
        for (View meter : automationMeters) meter.invalidate();
        if (!playing && (selClip() != null || selOverlay() != null || selAudio() != null || (tab == T_GRADE && selVShape() != null && !selVShape().keys.isEmpty()))) {   // slider values and key state follow the playhead
            h.removeCallbacks(rebuildRun);
            h.postDelayed(rebuildRun, 120);
        }
        timeline.invalidate();
        overlay.invalidate();
        updateTime();
        gl.requestRender();
    }

    void play() {
        if (proj.duration() <= 0) return;
        if (time >= proj.duration() - 50000) time = 0;
        playing = true;
        playT0 = time;
        playSys0 = SystemClock.elapsedRealtimeNanos();
        playBtn.setImageDrawable(new IconDrawable("pause", Ui.TEXT));
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        syncPlayers();
        h.removeCallbacks(tickRun);
        h.post(tickRun);
    }

    void pause() {
        if (!playing) return;
        playing = false;
        h.removeCallbacks(tickRun);
        playBtn.setImageDrawable(new IconDrawable("play", Ui.TEXT));
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        for (Player pl : players.values()) {
            if (pl.playing) { try { pl.mp.pause(); } catch (Exception ignored) {} pl.playing = false; }
            pl.startAfter = false;
        }
        syncPlayers();
        if (selClip() != null || selOverlay() != null || selAudio() != null) { h.removeCallbacks(rebuildRun); h.postDelayed(rebuildRun, 60); }
    }

    void scrubTo(long t) {
        if (playing) pause();
        time = Math.max(0, Math.min(proj.duration(), t));
        renderer.time = time;
        syncPlayers();
        changedTimeOnly();
    }

    // ================================================================ timeline edits
    /**
     * kind: 1 left handle, 2 right handle, 3 body (move), 4 roll the selected cut, 5 slip the
     * selected clip. dt = drag distance in us. Every edit lands on a whole frame of the project
     * rate (after snapping), and nothing gets shorter than one frame.
     */
    void dragEdit(int kind, long dt, long[] snaps, long tol) {
        if (undo.isEmpty()) return;
        VProject before = undo.get(undo.size() - 1);
        if(kind==6){
            try{proj=TimelineBatch.move(before,batchIds,dt,snaps,tol).project;changed();}
            catch(IllegalArgumentException e){warnTiming(e.getMessage());}return;
        }
        if(!timingEditable())return;
        final int fps = proj.fps();
        final long F = Timeline.frame(fps);
        long dq = Timeline.quant(dt, fps);
        if (selKind == TimelineView.SEL_CLIP) {
            int i = proj.clipIndex(selId);
            if (i < 0) return;
            VProject.Clip c = proj.clips.get(i), o = before.clipById(selId);
            if (o == null) return;
            if (kind == 4) { rollEdit(before, i, dq, snaps, tol); changed(); twoUpFor(4); return; }
            final double sp = o.avgSpeed();
            long minSrc = Math.max(1, (long) Math.ceil(F * sp));
            long maxOut = c.kind == VProject.IMAGE ? 600000000L : c.srcDur;
            if (kind == 1) trimHead(c, o, dq, minSrc);
            else if (kind == 2) {
                long start = proj.start(i);
                long end = start + o.dur() + dt;
                end = Timeline.quant(Timeline.snap(end, snaps, tol), fps);
                trimTail(c, o, end - start, maxOut, minSrc);
            } else if (kind == 5 && c.kind == VProject.VIDEO) slip(c, o, dq);
            if (kind == 1) AutomationEdits.shift(c.grade, o.grade, before, o.id, before.start(i), o.dur(), o.dur() - c.dur(), false);
            if (kind == 1 || kind == 2 || kind == 5) twoUpFor(kind);
        } else if (selKind == TimelineView.SEL_TEXT || selKind == TimelineView.SEL_VIZ) {
            VProject.TextItem t = proj.textById(selId), o = before.textById(selId);
            if (t == null || o == null) return;
            if (kind == 3) {
                t.start = moveTo(o.start, o.dur, dt, snaps, tol, fps);
            } else if (kind == 1) {
                long s = Math.max(0, Math.min(o.start + o.dur - F, Timeline.quant(Timeline.snap(o.start + dt, snaps, tol), fps)));
                t.start = s;
                t.dur = o.start + o.dur - s;
                t.keys = o.keys.copy();          // trimming the head keeps the animation where it was in time
                t.keys.shift(o.start - s);
            } else if (kind == 2) {
                long e = Timeline.quant(Timeline.snap(o.start + o.dur + dt, snaps, tol), fps);
                t.dur = Math.max(F, e - o.start);
            }
        } else if (selKind == TimelineView.SEL_MEDIA) {
            VProject.TextItem t = proj.textById(selId), o = before.textById(selId);
            if (t == null || o == null || o.media == null) return;
            boolean video = o.media.kind == VProject.VIDEO;
            float sp = o.media.speed;
            if (kind == 3) {
                t.start = moveTo(o.start, o.dur, dt, snaps, tol, fps);
            } else if (kind == 1) {
                // head trim: a video overlay also moves its in point, so the picture stays put in time
                long lo = video ? o.start - (long) (o.media.in / sp) : 0;
                long s = Math.max(Math.max(0, lo), Math.min(o.start + o.dur - F, Timeline.quant(Timeline.snap(o.start + dt, snaps, tol), fps)));
                t.start = s;
                t.dur = o.start + o.dur - s;
                if (video) t.media.in = Math.max(0, o.media.in + Math.round((s - o.start) * (double) sp));
                t.keys = o.keys.copy();
                t.keys.shift(o.start - s);
            } else if (kind == 2) {
                long e = Timeline.quant(Timeline.snap(o.start + o.dur + dt, snaps, tol), fps);
                t.dur = Math.max(F, Math.min(VProject.overlayMaxDur(o), e - o.start));
            }
            if (kind == 1) AutomationEdits.shift(t.media.grade, o.media.grade, before, o.media.id, o.start, o.dur, t.start - o.start, false);
            VProject.syncOverlayOut(t);
        } else if (selKind == TimelineView.SEL_AUDIO) {
            VProject.AudioItem au = proj.audioById(selId), o = before.audioById(selId);
            if (au == null || o == null) return;
            if (kind == 3) au.start = moveTo(o.start, o.dur(), dt, snaps, tol, fps);
            else if (kind == 1) {
                // head trim: in point and start move together (never past 0), and the volume keys
                // move back by the same amount so a dip stays on the word it was drawn for
                long d = Math.max(Math.max(-o.in, -o.start), Math.min(o.out - o.in - F, dq));
                au.in = o.in + d;
                au.start = o.start + d;
                au.vol = o.vol.copy();
                au.vol.shift(-d);
            } else if (kind == 2) {
                long e = Timeline.quant(Timeline.snap(o.start + (o.out - o.in) + dt, snaps, tol), fps);
                au.out = Math.max(o.in + F, Math.min(o.srcDur, o.in + (e - o.start)));
            }
        }
        if (kind == 1 || kind == 2) {
            VProject.TextItem ti = selOverlay(), old = before.textById(selId);
            if (ti != null && old != null) {
                ti.fadeOffset = old.fadeOffset + (kind == 1 ? ti.start - old.start : 0);
                ti.fadeSpan = kind == 2 ? ti.dur + ti.fadeOffset : old.fadeSpan < 0 ? old.dur + old.fadeOffset : old.fadeSpan;
            }
            VProject.AudioItem au = selAudio(), original = before.audioById(selId);
            if (au != null && original != null) {
                au.fadeOffset = original.fadeOffset + (kind == 1 ? au.start - original.start : 0);
                au.fadeSpan = kind == 2 ? au.dur() + au.fadeOffset : original.fadeSpan < 0 ? original.dur() + original.fadeOffset : original.fadeSpan;
            }
        }
        changed();
    }

    /**
     * Head trim by dq of playback (negative = extend): the new first frame is the footage that
     * showed dq into the clip, through its speed or ramp, and the clip's animation keeps its
     * timing on the frames that remain (keys shift by the playback time removed).
     */
    static void trimHead(VProject.Clip c, VProject.Clip o, long dq, long minSrc) {
        long lost;
        if (c.kind == VProject.IMAGE) {
            c.in = 0;
            c.out = Math.max(minSrc, o.out - dq);
            lost = o.dur() - c.dur();
        } else {
            c.in = Math.max(0, Math.min(o.out - minSrc, o.srcAt(dq)));
            lost = o.localAt(c.in);
        }
        c.keys = o.keys.copy();
        c.keys.shift(-lost);
    }

    /** Tail trim to newLen of playback: the ramp or speed decides how much footage that is. */
    static void trimTail(VProject.Clip c, VProject.Clip o, long newLen, long maxOut, long minSrc) {
        long want = c.kind == VProject.IMAGE ? o.in + newLen : o.srcAt(newLen);
        c.out = Math.max(o.in + minSrc, Math.min(maxOut, want));
    }

    /** Slip: same length and place, the contents slide (drag right = earlier). A ramp slides with the clip. */
    static void slip(VProject.Clip c, VProject.Clip o, long dq) {
        long len = o.out - o.in;
        c.in = Math.max(0, Math.min(Math.max(0, c.srcDur - len), o.in - Math.round(dq * o.avgSpeed())));
        c.out = c.in + len;
        if (o.curve != null) { long d = c.in - o.in; c.curveIn = o.curveIn + d; c.curveOut = o.curveOut + d; }
    }

    /** New start for an item of length dur moved by dt: snap its start, else its end; then onto a frame. */
    static long moveTo(long start, long dur, long dt, long[] snaps, long tol, int fps) {
        long s = Timeline.snap(start + dt, snaps, tol);
        long e = Timeline.snap(start + dur + dt, snaps, tol);
        long ns = s != start + dt ? s : e != start + dur + dt ? e - dur : start + dt;
        return Math.max(0, Timeline.quant(ns, fps));
    }

    /**
     * Roll the cut after clip i by dt: clip i gets longer and clip i+1 shorter by the same time
     * (or the reverse), so the cut moves and nothing after it does. Limited by the media each side
     * has (clip i can't run past its source end, clip i+1 can't start before its source start)
     * and by one frame left on each. Pure; works on proj from `before`.
     */
    void rollEdit(VProject before, int i, long dt, long[] snaps, long tol) {
        roll(before, proj, i, dt, snaps, tol);
    }

    static void roll(VProject before, VProject proj, int i, long dt, long[] snaps, long tol) {
        if (i < 0 || i >= proj.clips.size() - 1) return;
        int fps = proj.fps();
        long F = Timeline.frame(fps);
        VProject.Clip a = proj.clips.get(i), b = proj.clips.get(i + 1);
        VProject.Clip oa = before.clipById(a.id), ob = before.clipById(b.id);
        if (oa == null || ob == null) return;
        // snap the cut itself (the start of b on the timeline)
        long cut = before.start(i) + oa.dur();
        long nc = Timeline.quant(Timeline.snap(cut + dt, snaps, tol), fps);
        long d = nc - cut;
        // limits and new edges through each clip's own time map (exact for speeds and ramps)
        long aMax = oa.kind == VProject.IMAGE ? 600000000L : oa.srcDur;
        long hi = Math.min(oa.localAt(aMax) - oa.dur(), ob.dur() - F);
        long lo = -(oa.dur() - F);
        if (ob.kind == VProject.VIDEO) lo = Math.max(lo, ob.localAt(0));
        d = Math.max(lo, Math.min(hi, d));
        if (hi < lo) d = 0;
        a.out = oa.kind == VProject.IMAGE ? oa.out + d : Math.min(aMax, oa.srcAt(oa.dur() + d));
        long lostB;
        if (ob.kind == VProject.IMAGE) { b.in = 0; b.out = ob.out - d; lostB = d; }
        else { b.in = Math.max(0, ob.srcAt(d)); lostB = ob.localAt(b.in); }
        // the incoming clip's animation stays on its frames (its head moved)
        b.keys = ob.keys.copy();
        b.keys.shift(-lostB);
        AutomationEdits.shift(b.grade, ob.grade, before, ob.id, before.start(i + 1), ob.dur(), lostB, false);
    }

    /** Moves the selected clip's or item's edges by whole frames (the nudge buttons). */
    void nudge(int kind, int frames) {
        if (selKind == TimelineView.SEL_NONE) return;
        pause();
        applyPreview();
        beginEdit();
        long dt = Timeline.frameTime(frames, proj.fps());
        dragEdit(kind, dt, new long[0], 0);
        dragDone();
    }

    /** Playhead one frame back or forward, landing on frame boundaries. */
    void stepFrame(int dir) {
        int fps = proj.fps();
        long n = Timeline.frameIndex(time, fps) + dir;
        scrubTo(Timeline.frameTime(Math.max(0, n), fps));
    }

    void dragDone() {
        twoUp.hide();
        timeline.relayout();
        syncPlayers();
        rebuildPanel();
    }

    // ================================================================ missing media (relink)
    /** Files the project uses that can't be opened (moved, renamed, deleted, access revoked). */
    private java.util.LinkedHashMap<String, VProject.Source> missing = new java.util.LinkedHashMap<String, VProject.Source>();
    /** The missing file a picked replacement is for. */
    private String relinkUri;

    /** Tries to open every source file (off the UI thread), then runs after with `missing` up to date. */
    private void checkMissing(final Runnable after) {
        final java.util.LinkedHashMap<String, VProject.Source> all = proj.sources();
        bg.execute(new Runnable() {
            @Override public void run() {
                final java.util.LinkedHashMap<String, VProject.Source> miss = new java.util.LinkedHashMap<String, VProject.Source>();
                for (VProject.Source s : all.values()) if (!readable(s.uri)) miss.put(s.uri, s);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (closed) return;
                        boolean changedSet = !miss.keySet().equals(missing.keySet());
                        missing = miss;
                        if (changedSet) rebuildPanel();
                        if (after != null) after.run();
                    }
                });
            }
        });
    }

    private boolean readable(String uri) {
        try {
            android.os.ParcelFileDescriptor f = act.getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
            if (f == null) return false;
            f.close();
            return true;
        } catch (Exception e) { return false; }
    }

    /** Premiere's Link Media, phone-sized: replace each missing file, or find them all in a folder by name. */
    private void missingSheet() {
        if (missing.isEmpty()) { toast("Every file is where it should be."); return; }
        int n = missing.size();
        Sheet sh = new Sheet(act, n == 1 ? "1 file can't be found" : n + " files can't be found");
        sh.section("Moved, renamed, deleted or no longer shared with Independent Video. Point the project at the file again: trims, keyframes and grades stay.");
        for (final VProject.Source s : missing.values()) {
            String icon = s.kind == VProject.SRC_AUDIO ? "music" : s.kind == VProject.SRC_IMAGE ? "image" : "video";
            sh.item(icon, "Replace \u201c" + s.name + "\u201d", VProject.SRC_KIND[s.kind] + " \u00b7 used by " + s.uses + (s.uses == 1 ? " item" : " items"),
                    new Runnable() { @Override public void run() { pickRelink(s); } });
        }
        sh.item("folder", "Find in a folder\u2026", "Relinks every missing file found there with the same name", new Runnable() {
            @Override public void run() { pickRelinkDir(); }
        });
        sh.item("search", "Check again", new Runnable() {
            @Override public void run() { checkMissing(new Runnable() { @Override public void run() { missingSheet(); } }); }
        });
        sh.show();
    }

    private void pickRelink(VProject.Source s) {
        relinkUri = s.uri;
        pickMedia(REQ_RELINK, new String[]{s.kind == VProject.SRC_AUDIO ? "audio/*" : s.kind == VProject.SRC_IMAGE ? "image/*" : "video/*"});
    }

    private void pickRelinkDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { act.startActivityForResult(i, REQ_RELINK_DIR); } catch (Exception e) { toast("No folder picker available"); }
    }

    /** Facts about a replacement file. Runs off the UI thread; throws if it isn't the right kind. */
    private VProject.Probe probe(Uri u, int kind, String fallbackName) throws Exception {
        VProject.Probe n = new VProject.Probe();
        n.uri = u.toString();
        n.kind = kind;
        n.name = act.displayName(u, fallbackName);
        if (kind == VProject.SRC_VIDEO) {
            VProject.Clip c = new VProject.Clip();
            Media.probeVideo(act, u, c);
            n.srcW = c.srcW; n.srcH = c.srcH; n.rotation = c.rotation; n.srcDur = c.srcDur; n.hasAudio = c.hasAudio;
        } else if (kind == VProject.SRC_IMAGE) {
            Bitmap b = Media.decodeImage(act.getContentResolver(), u, 2048);   // the size photo clips are placed at
            if (b == null) throw new Exception("not a photo this phone can read");
            n.srcW = b.getWidth(); n.srcH = b.getHeight();
            b.recycle();
        } else n.srcDur = Media.probeAudio(act, u);
        return n;
    }

    private void relinkTo(final String oldUri, final Uri u) {
        final VProject.Source s = proj.sources().get(oldUri);
        if (s == null) return;
        showStatus("Reading " + s.name + "\u2026");
        bg.execute(new Runnable() {
            @Override public void run() {
                VProject.Probe n = null;
                String err = null;
                try { n = probe(u, s.kind, s.name); } catch (Exception e) { err = e.getMessage(); }
                final VProject.Probe fn = n;
                final String fe = err;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (closed) return;
                        if (fn == null) { toast("That isn't a " + VProject.SRC_KIND[s.kind] + " Independent Video can read" + (fe != null ? " (" + fe + ")" : "") + "."); return; }
                        beginEdit();
                        int[] cl = {0};
                        int k = applyRelink(oldUri, fn, cl);
                        changed();
                        syncPlayers();
                        if (k > 0) toast("Relinked \u201c" + s.name + "\u201d (" + k + (k == 1 ? " item" : " items") + ")"
                                + (cl[0] > 0 ? ". The new file is shorter: " + cl[0] + " trimmed to fit." : "."));
                        if (!missing.isEmpty()) missingSheet();
                    }
                });
            }
        });
    }

    /** Applies one relink and refreshes everything cached under the old address. */
    private int applyRelink(String oldUri, VProject.Probe n, int[] clamped) {
        int k = proj.relink(oldUri, n, clamped);
        if (k == 0) return 0;
        thumbs.forget(oldUri);
        thumbJobs.remove(oldUri);
        peaks.remove(oldUri);
        fxCache.remove(oldUri); fxErrors.remove(oldUri);
        proj.controlFx.remove(oldUri); proj.controlBeats.remove(oldUri); proj.controlMusic.remove(oldUri);
        missing.remove(oldUri);
        releaseAllPlayers();   // players hold the old address
        for (VProject.Clip c : proj.clips) if (c.uri.equals(n.uri)) loadClipAssets(c);
        for (VProject.TextItem t : proj.texts) if (t.media != null && t.media.uri.equals(n.uri)) loadClipAssets(t.media);
        if (n.kind == VProject.SRC_AUDIO) requestPeaks(n.uri);
        return k;
    }

    /**
     * Find in a folder: walks the picked folder (and its subfolders) for files named like the
     * missing ones, then relinks every match in one undo step.
     */
    private void findInFolder(final Uri tree) {
        final java.util.LinkedHashMap<String, VProject.Source> want = new java.util.LinkedHashMap<String, VProject.Source>(missing);
        if (want.isEmpty()) return;
        showStatus("Looking for " + want.size() + (want.size() == 1 ? " file\u2026" : " files\u2026"));
        bg.execute(new Runnable() {
            @Override public void run() {
                java.util.HashMap<String, VProject.Source> byName = new java.util.HashMap<String, VProject.Source>();
                for (VProject.Source s : want.values()) byName.put(VProject.nameKey(s.name), s);
                java.util.HashMap<String, Uri> found = new java.util.HashMap<String, Uri>();
                try { walk(tree, byName, found); } catch (Exception ignored) {}
                final java.util.ArrayList<String[]> fails = new java.util.ArrayList<String[]>();
                final java.util.LinkedHashMap<String, VProject.Probe> probes = new java.util.LinkedHashMap<String, VProject.Probe>();
                for (java.util.Map.Entry<String, Uri> e : found.entrySet()) {
                    VProject.Source s = byName.get(e.getKey());
                    try { probes.put(s.uri, probe(e.getValue(), s.kind, s.name)); }
                    catch (Exception x) { fails.add(new String[]{s.name, String.valueOf(x.getMessage())}); }
                }
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (closed) return;
                        int done = 0, cl0 = 0;
                        if (!probes.isEmpty()) {
                            beginEdit();
                            int[] cl = {0};
                            for (java.util.Map.Entry<String, VProject.Probe> e : probes.entrySet()) if (applyRelink(e.getKey(), e.getValue(), cl) > 0) done++;
                            cl0 = cl[0];
                            changed();
                            syncPlayers();
                        }
                        String msg = done == 0 ? "None of the missing files are in that folder." : "Relinked " + done + " of " + want.size() + ".";
                        if (cl0 > 0) msg += " " + cl0 + " trimmed to fit shorter files.";
                        if (!fails.isEmpty()) msg += " Couldn't read: " + fails.get(0)[0] + (fails.size() > 1 ? " and " + (fails.size() - 1) + " more" : "") + ".";
                        toast(msg);
                        if (!missing.isEmpty()) missingSheet();
                    }
                });
            }
        });
    }

    /** Breadth-first walk of a picked folder tree (up to 6 levels, 20 000 entries). */
    private void walk(Uri tree, java.util.HashMap<String, VProject.Source> byName, java.util.HashMap<String, Uri> found) {
        android.content.ContentResolver cr = act.getContentResolver();
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<Object[]>();
        q.add(new Object[]{android.provider.DocumentsContract.getTreeDocumentId(tree), 0});
        int seen = 0;
        while (!q.isEmpty() && seen < 20000 && found.size() < byName.size() && !cancelBg.get()) {
            Object[] d = q.poll();
            Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, (String) d[0]);
            android.database.Cursor c = null;
            try {
                c = cr.query(kids, new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                while (c != null && c.moveToNext()) {
                    seen++;
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if ((Integer) d[1] < 6) q.add(new Object[]{id, (Integer) d[1] + 1});
                        continue;
                    }
                    String key = VProject.nameKey(name);
                    if (byName.containsKey(key) && !found.containsKey(key))
                        found.put(key, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
        }
    }

    // ================================================================ media in
    void addMedia() { pickMedia(REQ_MEDIA, new String[]{"video/*", "image/*"}); }

    private void pickMedia(int req, String[] types) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(types.length == 1 ? types[0] : "*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, req == REQ_MEDIA);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { act.startActivityForResult(i, req); } catch (Exception e) { toast("No file picker available"); }
    }

    @Override
    public void mediaPicked(int req, Intent data) {
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            try { act.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        }
        if (req == REQ_SRT && !uris.isEmpty()) { importSrt(uris.get(0)); return; }
        if (req == REQ_RELINK && !uris.isEmpty() && relinkUri != null) { relinkTo(relinkUri, uris.get(0)); return; }
        if (req == REQ_RELINK_DIR && !uris.isEmpty()) { findInFolder(uris.get(0)); return; }
        if ((req == REQ_SOURCE || req == REQ_REPLACE) && !uris.isEmpty()) { openSourceFile(uris.get(0), req == REQ_REPLACE); return; }
        if (req == REQ_MEDIA) importClips(uris);
        else if (req == REQ_OVERLAY && !uris.isEmpty()) importOverlay(uris.get(0));
        else if (req == REQ_AUDIO && !uris.isEmpty()) importAudio(uris.get(0));
    }

    private void importClips(final ArrayList<Uri> uris) {
        showStatus("Reading media…");
        bg.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<VProject.Clip> got = new ArrayList<VProject.Clip>();
                String err = null;
                for (Uri u : uris) {
                    VProject.Clip c = new VProject.Clip();
                    c.uri = u.toString();
                    c.name = act.displayName(u, "Clip");
                    String type = act.getContentResolver().getType(u);
                    try {
                        if (type != null && type.startsWith("image/")) {
                            Bitmap b = Media.decodeImage(act.getContentResolver(), u, 2048);
                            c.kind = VProject.IMAGE;
                            c.still = b;
                            c.srcW = b.getWidth();
                            c.srcH = b.getHeight();
                            c.srcDur = Long.MAX_VALUE / 4;
                            c.in = 0;
                            c.out = 3000000;
                        } else {
                            Media.probeVideo(act, u, c);
                            c.in = 0;
                            c.out = c.srcDur;
                        }
                        got.add(c);
                    } catch (Exception e) {
                        err = c.name + ": " + e.getMessage();
                    }
                }
                final String er = err;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (er != null) toast("Skipped " + er);
                        if (got.isEmpty()) return;
                        beginEdit();
                        int at = proj.clips.size();
                        VProject.Clip s = selClip();
                        if (s != null) at = proj.clipIndex(s.id) + 1;
                        for (VProject.Clip c : got) {
                            c.id = proj.nextId++;
                            proj.clips.add(at++, c);
                            loadClipAssets(c);
                        }
                        for (VProject.Clip c : got) Edits.defaultTransition(proj, c.id);
                        select(TimelineView.SEL_CLIP, got.get(got.size() - 1).id, false);
                        changed();
                        syncPlayers();
                    }
                });
            }
        });
    }

    private void importAudio(final Uri u) {
        showStatus("Reading audio…");
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    final long d = Media.probeAudio(act, u);
                    final String name = act.displayName(u, "Music");
                    h.post(new Runnable() {
                        @Override public void run() {
                            showStatus(null);
                            beginEdit();
                            VProject.AudioItem a = new VProject.AudioItem();
                            a.id = proj.nextId++;
                            a.uri = u.toString();
                            a.name = name;
                            a.srcDur = d;
                            a.in = 0;
                            a.out = d;
                            a.start = proj.audio.isEmpty() ? 0 : time;
                            proj.audio.add(a);   // music sync stays as it is: off unless the user picks a track
                            requestPeaks(a.uri);
                            select(TimelineView.SEL_AUDIO, a.id, false);
                            changed();
                        }
                    });
                } catch (final Exception e) {
                    h.post(new Runnable() { @Override public void run() { showStatus(null); toast("Can't read that audio: " + e.getMessage()); } });
                }
            }
        });
    }

    // ================================================================ top bar, tabs
    private View topBar() {
        LinearLayout b = Ui.row(act);
        b.setBackgroundColor(Ui.BG);
        b.setPadding(0, 0, Ui.dp(6), 0);
        b.addView(Ui.iconBtn(act, "back", new View.OnClickListener() { @Override public void onClick(View v) { if (!back()) act.closeWorkspace(); } }));
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
        TextView ex = Ui.text(act, "Export", 13, 0xff111111);
        ex.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        ex.setBackground(Ui.round(Ui.ACCENT, 8, 0));
        ex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { exportDialog(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(4);
        b.addView(ex, lp);
        b.addView(Ui.iconBtn(act, "more", new View.OnClickListener() { @Override public void onClick(View v) { overflow(); } }));
        return b;
    }

    private void renameProject() {
        act.renameProject(dir, new Pickers.TextCb() { @Override public void text(String s) { titleView.setText(s); } });
    }

    private void overflow() {
        new Sheet(act, Projects.name(dir))
                .section("Project")
                .item("pencil", "Rename project", new Runnable() { @Override public void run() { renameProject(); } })
                .item("folder", "Versions…", "Save a named recovery point or restore a copy",new Runnable(){public void run(){act.versions(dir);}})
                .item("aspect", "Project settings", "Canvas, resolution, frame rate and duration",new Runnable(){@Override public void run(){setTab(T_FORMAT);}})
                .section("Media")
                .item("folder", "Check source files", "Find and relink missing videos, images or audio",new Runnable(){@Override public void run(){checkMissing(new Runnable(){@Override public void run(){if(missing.isEmpty())toast("All source files are available.");else missingSheet();}});}})
                .section("View")
                .item("guides", "Guides: " + ViewGuides.NAMES[guideMode], "Thirds, title/action safe, social app UI", new Runnable(){public void run(){guidesSheet();}})
                .item("fullscreen", fullView ? "Leave full screen" : "Full-screen viewer", "Or double-tap the picture, or turn the phone sideways", new Runnable(){public void run(){toggleFull();}})
                .item("eye_off", barsHidden ? "Show toolbars" : "Hide toolbars", "Bigger viewer; the timeline and play controls stay", new Runnable(){public void run(){setBarsHidden(!barsHidden);}})
                .item("fit", "Standard viewer size", "Undo a dragged viewer size (or double-tap the bar under the picture)", new Runnable(){public void run(){resetViewerSize();}})
                .item("camera", "Save this frame\u2026", "PNG at " + proj.outW() + " \u00d7 " + proj.outH() + ", optionally opened in the photo editor", new Runnable(){public void run(){saveFrame();}})
                .section("Timeline")
                .item("sliders", "Snapping…",new Runnable(){public void run(){snapOptions();}})
                .item("marker", "Markers…", proj.markers.isEmpty() ? "Tap the marker button to add one, also while playing" : proj.markers.size() + (proj.markers.size() == 1 ? " marker" : " markers"),
                        new Runnable(){public void run(){markersSheet();}}).show();
    }

    private void addSheet() {
        new Sheet(act,"Add to the timeline")
                .section("Footage")
                .item("film","Main video or photo",new Runnable(){@Override public void run(){addMedia();}})
                .item("film","Choose a source range…","Mark In/Out before inserting footage",new Runnable(){@Override public void run(){pickMedia(REQ_SOURCE,new String[]{"video/*","image/*"});}})
                .item("layers","B-roll, picture-in-picture or logo",new Runnable(){@Override public void run(){overlaySheet();}})
                .section("Sound & graphics")
                .item("music","Music or audio",new Runnable(){@Override public void run(){pickMedia(REQ_AUDIO,new String[]{"audio/*"});}})
                .item("text","Title",new Runnable(){@Override public void run(){addText();}})
                .item("text","Captions…",new Runnable(){@Override public void run(){captionSheet();}})
                .item("bars","Visualizer",new Runnable(){@Override public void run(){setTab(T_VIZ);}}).show();
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView t = Ui.text(act, s, 13, Ui.TEXT);
        t.setPadding(Ui.dp(9), Ui.dp(10), Ui.dp(9), Ui.dp(10));
        t.setOnClickListener(l);
        return t;
    }

    private void buildTabs() {
        tabs.removeAllViews();
        String[] names = {"Edit", "Effects", "Titles", "Visuals", "Audio", "Project"};
        String[] icons = {"film", "grade", "text", "bars", "music", "aspect"};
        int[] ids = {T_CLIPS, T_GRADE, T_TEXT, T_VIZ, T_AUDIO, T_FORMAT};
        for (int i = 0; i < names.length; i++) {
            final int t = ids[i];
            tabs.addView(Ui.tab(act, icons[i], names[i], tab == t, new View.OnClickListener() { @Override public void onClick(View v) { setTab(t); } }),
                    Ui.lp(0, -1, 1f));
        }
    }

    void setTab(int t) {
        Crash.note("video tab " + t);
        if (previewBase != null && t != tab) applyPreview();
        tab = t;
        buildTabs();
        rebuildPanel();
        refreshClipBar();
        overlay.invalidate();
    }

    private final InspectorState inspector = new InspectorState();
    private boolean rebuilding;

    void rebuildPanel() {
        if (rebuilding) { h.removeCallbacks(rebuildRun); h.post(rebuildRun); return; }   // re-entered during removal
        rebuilding = true;
        try { rebuildPanelNow(); } finally { rebuilding = false; }
    }

    private void rebuildPanelNow() {
        Crash.note("video panel " + tab);
        inspector.open(inspectorContext(),panelScroll.getScrollY());
        panel.removeAllViews();
        automationMeters.clear();
        if (previewBase != null) previewBar();
        if (!missing.isEmpty()) {
            final int n = missing.size();
            panel.addView(Ui.strip(act, Ui.chip(act, "info", (n == 1 ? "1 file can't be found" : n + " files can't be found") + " \u00b7 Fix", true,
                    new View.OnClickListener() { @Override public void onClick(View v) { missingSheet(); } })));
        }
        if(selectingMany){batchPanel();panelScroll.post(new Runnable(){public void run(){panelScroll.scrollTo(0,0);}});return;}
        if(TimelineLocks.locked(proj,selId)){
            panel.addView(Ui.chip(act,"lock","Timing locked · Unlock",true,new View.OnClickListener(){public void onClick(View v){toggleTimingLock();}}));
            panel.addView(hint("Placement, source range, speed and deletion are protected. Color, picture transforms and sound levels remain editable."));
        }
        switch (tab) {
            case T_CLIPS: clipsPanel(); break;
            case T_GRADE: gradePanel(); break;
            case T_TEXT: textPanel(); break;
            case T_AUDIO: audioPanel(); break;
            case T_FORMAT: formatPanel(); break;
            case T_VIZ: vizPanel(); break;
        }
        InspectorSections.organize(panel,panelScroll,inspector,new Runnable(){@Override public void run(){rebuildPanel();}});
        final int y=inspector.scroll();
        panelScroll.post(new Runnable() { @Override public void run() { panelScroll.scrollTo(0, y); } });
    }

    private String inspectorContext() { return tab+":"+selKind+":"+selId+":"+(tab==T_GRADE?gradeTarget+":"+gradeSelId:""); }
    private void section(String name) { InspectorSections.mark(panel,name); }

    private TextView hint(String s) { return AdjPanels.hint(act, s); }

    interface FSet { void set(float v); }

    private Ui.Slider slider(String name, float min, float max, float def, float val, float scale, int dec, String unit, final FSet s) {
        Ui.Slider sl = new Ui.Slider(act, name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { beginEdit(); }
            @Override public void change(float v) { s.set(v); changed(); }
        });
        sl.display(scale, dec, unit);
        return sl;
    }

    private TextView chip(String s, boolean on, View.OnClickListener l) { return Ui.chip(act, s, on, l); }

    /** The selected title / overlay / visualizer / audio item's connection, or null. */
    private VProject.Anchor selLink() {
        VProject.TextItem t = selOverlay();
        if (t != null) return t.link;
        VProject.AudioItem a = selAudio();
        return a == null ? null : a.link;
    }

    private long selStart() {
        VProject.TextItem t = selOverlay();
        if (t != null) return t.start;
        VProject.AudioItem a = selAudio();
        return a == null ? 0 : a.start;
    }

    /** "Connected to clip" / "Fixed in time" toggle for the selected item. */
    private TextView linkChip() {
        VProject.Anchor a = selLink();
        boolean on = a != null && a.on;
        return Ui.chip(act, "link", on ? "Connected to clip" : "Fixed in time", on, new View.OnClickListener() {
            @Override public void onClick(View v) {
                VProject.Anchor k = selLink();
                if (k == null) return;
                beginEdit();
                proj.connect(k, selStart(), !k.on);
                changed();
                rebuildPanel();
                toast(k.on ? "Connected: it moves when the clip under it is trimmed, moved or deleted."
                        : "Fixed: it stays at this time whatever happens on the main track.");
            }
        });
    }

    /** Frame nudges for the selected item's edges (kind 1 head, 2 tail; 3 move, 5 slip). */
    private View nudgeRow(boolean move) {
        ArrayList<View> v = new ArrayList<View>();
        v.add(chip("\u2039 In", false, nudgeL(1, -1)));
        v.add(chip("In \u203a", false, nudgeL(1, 1)));
        v.add(chip("\u2039 Out", false, nudgeL(2, -1)));
        v.add(chip("Out \u203a", false, nudgeL(2, 1)));
        if (move) {
            v.add(chip("\u2039 Move", false, nudgeL(3, -1)));
            v.add(chip("Move \u203a", false, nudgeL(3, 1)));
        }
        return Ui.strip(act, v.toArray(new View[0]));
    }

    private View.OnClickListener nudgeL(final int kind, final int dir) {
        return new View.OnClickListener() { @Override public void onClick(View v) { nudge(kind, dir); } };
    }

    /** A slider for a keyframeable value: edits record a key when the item is animated. */
    private Ui.Slider sliderXf(String name, float min, float max, float def, float val, float scale, int dec, String unit, final FSet s) {
        Ui.Slider sl = new Ui.Slider(act, name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { if(time<selectedStart()||time>=selectedEnd())scrubTo(Math.max(selectedStart(),Math.min(time,selectedEnd()-Timeline.frame(proj.fps()))));beginEdit(); }
            @Override public void change(float v) { s.set(v); changedXf(); }
        });
        sl.display(scale, dec, unit);
        return sl;
    }

    // ================================================================ keyframes + music reaction (shared)
    /** Keyframe property groups (channel indices into clipVals / textVals). */
    private static final String[] KEY_GROUPS = {"Position", "Zoom", "Rotation", "Opacity"};
    private static int[] keyChannels(boolean clip, int group) {
        switch (group) {
            case 0: return clip ? new int[]{1, 2} : new int[]{0, 1};
            case 1: return clip ? new int[]{0} : new int[]{2};
            case 2: return new int[]{3};
            default: return new int[]{4};
        }
    }

    /**
     * Keyframes, per property (Alight Motion / LumaFusion 5): each property animates on its own
     * keys. "Animate" chips key one property at the playhead (or remove its key there); editing an
     * animated property keys just that property. Eases per key: Linear, Smooth, Hold, the Bezier
     * presets, or a custom curve.
     */
    private void keyRow(final boolean clip) {
        final Keys k;
        final long start;
        if (clip) {
            VProject.Clip c = selClip();
            k = c.keys;
            start = proj.start(proj.clipIndex(c.id));
        } else {
            VProject.TextItem t = selOverlay();
            k = t.keys;
            start = t.start;
        }
        final long lt = time - start, tol = frameTol();
        final int at = k.near(lt, tol);
        panel.addView(Ui.label(act, "Keyframes" + (k.isEmpty() ? "" : " \u00b7 " + k.size())));
        panel.addView(Ui.strip(act,
                chip("\u25c0", false, new View.OnClickListener() { @Override public void onClick(View v) { long previous=k.prev(time-start,tol);if (previous != Long.MIN_VALUE && previous >= 0) scrubTo(start + previous); } }),
                chip(at >= 0 ? "\u25c6 On key" : "\u25c7 Key all", at >= 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (k.near(time-start,tol) >= 0 || !keyTimeValid()) return;
                        pause();
                        beginEdit();
                        if (clip) k.set(time-start, VProject.clipVals(selClip()), tol);
                        else k.set(time-start, VProject.textVals(selOverlay()), tol);
                        changed();
                        rebuildPanel();
                    }
                }),
                chip("\u25b6", false, new View.OnClickListener() { @Override public void onClick(View v) { long next=k.next(time-start,tol);if (next != Long.MAX_VALUE) scrubTo(start + next); } }),
                chip("Remove key", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { int current=k.near(time-start,tol);if (current < 0) return;pause(); beginEdit(); k.remove(current); changed(); rebuildPanel(); }
                }),
                chip("Clear all", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { if (k.isEmpty()) return; beginEdit(); k.list.clear(); changed(); rebuildPanel(); }
                })));
        // per-property: ◆ keyed here, ◇ animated elsewhere, plain = not animated
        View[] g = new View[KEY_GROUPS.length];
        for (int gi = 0; gi < g.length; gi++) {
            final int[] ch = keyChannels(clip, gi);
            boolean here = at >= 0 && k.list.get(at).has(ch[0]);
            boolean anim = k.animated(ch[0]);
            g[gi] = chip((here ? "\u25c6 " : anim ? "\u25c7 " : "") + KEY_GROUPS[gi], here, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if(!keyTimeValid())return;
                    pause();beginEdit();
                    long local=time-start;int i = k.near(local, tol);
                    if (i >= 0 && k.list.get(i).has(ch[0])) {
                        for (int j : ch) { i = k.near(local, tol); k.unsetChannel(i, j); }   // unkey it here
                    } else {
                        float[] current=clip?VProject.clipVals(selClip()):VProject.textVals(selOverlay());
                        for (int j : ch) k.setChannel(local, j, current[j], tol, current);
                    }
                    changed();
                    rebuildPanel();
                }
            });
        }
        panel.addView(hint("Animate (tap to key at the playhead, again to remove that key):"));
        panel.addView(Ui.strip(act, g));
        if (at >= 0 && at < k.size() - 1) {
            final Keys.Key key = k.list.get(at);
            int presetOn = -1;
            if (key.ease == Keys.BEZIER && key.bez != null)
                for (int i = 0; i < Keys.EASE_BEZ.length; i++) if (java.util.Arrays.equals(key.bez, Keys.EASE_BEZ[i])) presetOn = i;
            View[] e = new View[3 + Keys.EASE_PRESETS.length + 1];
            for (int i = 0; i < 3; i++) {
                final int q = i;
                e[i] = chip(Keys.EASE[i], key.ease == i, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); Keys.Key kk = curKey(clip, at); if (kk != null) { kk.ease = q; kk.bez = null; } changed(); rebuildPanel(); }
                });
            }
            for (int i = 0; i < Keys.EASE_PRESETS.length; i++) {
                final int q = i;
                e[3 + i] = chip(Keys.EASE_PRESETS[i], presetOn == i, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); Keys.Key kk = curKey(clip, at); if (kk != null) { kk.ease = Keys.BEZIER; kk.bez = Keys.EASE_BEZ[q].clone(); } changed(); rebuildPanel(); }
                });
            }
            e[e.length - 1] = chip("Curve\u2026", key.ease == Keys.BEZIER && presetOn < 0, new View.OnClickListener() {
                @Override public void onClick(View v) { easeEditor(clip, at); }
            });
            panel.addView(hint("Motion from this key to the next:"));
            panel.addView(Ui.strip(act, e));
        }
        if (k.isEmpty())
            panel.addView(hint("Tap a property under Animate to key it here, move the playhead, then change it: each property gets its own keys. \u25c7 Key all keys everything at once. \u25c0 \u25b6 jump between keys."));
    }

    private Keys.Key curKey(boolean clip, int at) {
        Keys k = clip ? (selClip() == null ? null : selClip().keys) : (selOverlay() == null ? null : selOverlay().keys);
        return k == null || at < 0 || at >= k.size() ? null : k.list.get(at);
    }

    /** Custom Bezier ease for the segment starting at key `at`; Cancel restores the old ease. */
    private void easeEditor(final boolean clip, final int at) {
        Keys.Key key = curKey(clip, at);
        if (key == null) return;
        final int oldEase = key.ease;
        final float[] oldBez = key.bez == null ? null : key.bez.clone();
        beginEdit();
        // start from the shape it has now: Linear as a straight curve, anything else as ease in-out
        if (key.bez == null) key.bez = oldEase == Keys.LINEAR ? new float[]{0.25f, 0.25f, 0.75f, 0.75f} : new float[]{0.42f, 0, 0.58f, 1};
        key.ease = Keys.BEZIER;
        final float[] work = key.bez.clone();
        EaseCurveView cv = new EaseCurveView(act, work, new EaseCurveView.Listener() {
            @Override public void changed(float[] b) { Keys.Key kk = curKey(clip, at); if (kk != null) { kk.ease = Keys.BEZIER; kk.bez = b.clone(); } changedLight(); }
        });
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), 0);
        box.addView(cv);
        box.addView(hint("Drag the two handles. Steep = fast, flat = slow; past the top or bottom = overshoot."));
        new AlertDialog.Builder(act)
                .setTitle("Ease to the next key")
                .setView(box)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { changed(); rebuildPanel(); } })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Keys.Key kk = curKey(clip, at);
                        if (kk != null) { kk.ease = oldEase; kk.bez = oldBez; }
                        changed(); rebuildPanel();
                    }
                })
                .show();
        changedLight();
    }

    /** Re-renders after a live edit (curve drag) without rebuilding the panel. */
    private void changedLight() { changed(); }

    // Audio routing is deliberately separate from audible sound and beat-guide selection.
    interface ISet { void set(int value); }
    private int defaultControlSource() {
        int owner=gradeOwner();
        if(owner>0&&AudioControl.resolve(proj,AudioControl.SELF,owner,time)!=null)return AudioControl.SELF;
        return proj.syncItem()!=null?proj.syncItem().id:AudioControl.MAIN;
    }
    private int gradeOwner() {
        if (gradeTarget == G_CLIP && selClip() != null) return selClip().id;
        if (gradeTarget == G_BROLL && selMedia() != null) return selMedia().media.id;
        return 0;
    }
    private long gradeStart() {
        if (gradeTarget == G_CLIP && selClip() != null) return proj.start(proj.clipIndex(selClip().id));
        if (gradeTarget == G_BROLL && selMedia() != null) return selMedia().start;
        return 0;
    }
    private long gradeDuration() {
        if (gradeTarget == G_CLIP && selClip() != null) return selClip().dur();
        if (gradeTarget == G_BROLL && selMedia() != null) return selMedia().dur;
        return proj.duration();
    }
    private void controlSourceSheet(final int owner, int current, final ISet set) {
        Sheet sh = new Sheet(act, "Audio that controls this effect").searchable("Search audio sources");
        sh.item("close", "Off", "Bypass the audio control", new Runnable() { @Override public void run() { set.set(AudioControl.OFF); } });
        if (owner > 0 && AudioControl.resolve(proj, AudioControl.SELF, owner, time) != null)
            sh.item("video", "This video's sound", "Independent of other effects and the audible mix", new Runnable() { @Override public void run() { set.set(AudioControl.SELF); } });
        sh.item("film", "Main video audio", "Follows the main footage, including trims and speed", new Runnable() { @Override public void run() { set.set(AudioControl.MAIN); } });
        if (proj.syncItem() != null) sh.item("music", "Follow beat guide: " + proj.syncItem().name, "Only this choice follows changes to the timeline beat guide", new Runnable() { @Override public void run() { set.set(AudioControl.GUIDE); } });
        sh.section("Audio tracks");
        for (final VProject.AudioItem a : proj.audio) sh.item("music", a.name, "Audio track at " + TimelineView.fmt(a.start, false), new Runnable() { @Override public void run() { set.set(a.id); } });
        sh.section("Individual videos");
        for (final VProject.Clip c : proj.clips) if (c.hasAudio && c.kind == VProject.VIDEO)
            sh.item("video", c.name, "Main-track clip", new Runnable() { @Override public void run() { set.set(c.id); } });
        for (final VProject.TextItem ti : proj.texts) if (ti.media != null && ti.media.hasAudio && ti.media.kind == VProject.VIDEO)
            sh.item("layers", ti.media.name, "B-roll / picture-in-picture", new Runnable() { @Override public void run() { set.set(ti.media.id); } });
        sh.show();
    }
    private View controlSourceRow(final int owner, final int ref, final ISet set) {
        AudioControl.Source source = AudioControl.resolve(proj, ref, owner, time);
        ArrayList<View> buttons = new ArrayList<View>();
        buttons.add(Ui.chip(act, "music", "Source: " + AudioControl.name(proj, ref, owner), ref != 0, new View.OnClickListener() {
            @Override public void onClick(View v) { controlSourceSheet(owner, ref, set); }
        }));
        if (source != null && fxErrors.containsKey(source.uri)) {
            final String uri = source.uri;
            buttons.add(chip("Retry analysis", false, new View.OnClickListener() {
                @Override public void onClick(View v) { fxErrors.remove(uri); requestControlAnalysis(uri); rebuildPanel(); }
            }));
        }
        return Ui.strip(act, buttons.toArray(new View[0]));
    }
    private void openAudioEffects(boolean clip) {
        applyPreview();
        gradeTarget = clip ? G_CLIP : G_BROLL;
        gradeSelId = -1; setTab(T_GRADE); panelScroll.scrollTo(0, 0); musicPresetSheet();
    }
    private boolean mainCovered() {
        if (selClip() == null) return false;
        for (VProject.TextItem ti : proj.texts) if (ti.media != null && ti.layer.visible && time >= ti.start && time < ti.start + ti.dur) {
            float[] x = proj.textXf(ti, time);
            if (Math.abs(x[3]) < 0.01f && x[4] * VProject.itemFade(ti, time) > 0.99f
                    && ti.layer.srcW * x[2] >= proj.outW() && ti.layer.srcH * x[2] >= proj.outH()
                    && Math.abs(x[0] - proj.outW()/2f) < 1 && Math.abs(x[1] - proj.outH()/2f) < 1) return true;
        }
        return false;
    }
    private View targetPreviewRow() {
        final int owner = gradeOwner();
        return Ui.strip(act,
            chip(renderer.soloId == owner && owner > 0 ? "Exit solo" : "Solo this target", renderer.soloId == owner && owner > 0, new View.OnClickListener() {
                @Override public void onClick(View v) { if (owner <= 0) return; renderer.soloId = renderer.soloId == owner ? 0 : owner; gl.requestRender(); rebuildPanel(); }
            }),
            chip("Move effect to all footage", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Layer selected=gradeLayer(); if(selected==null||owner<=0)return;
                    long start=gradeStart(),duration=gradeDuration();beginEdit();
                    if(proj.moveGradeToFootage(owner,selected,start,duration)){renderer.soloId=0;gradeTarget=G_FOOTAGE;gradeSelId=selected.id;changed();rebuildPanel();}
                }
            }));
    }

    /** A live, cheap meter: the same evaluator/time mapping used by preview and export. */
    private final class ControlMeter extends View {
        final int ref, owner, feature; final boolean adaptive, post;
        final Layer layer; final Music.Link link; final long start, duration;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ControlMeter(int ref, int owner, int feature, boolean adaptive, boolean post, Layer layer, Music.Link link, long start, long duration) {
            super(act); this.ref=ref;this.owner=owner;this.feature=feature;this.adaptive=adaptive;this.post=post;this.layer=layer;this.link=link;this.start=start;this.duration=duration;
            automationMeters.add(this);
        }
        @Override protected void onMeasure(int w, int hh) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(84)); }
        @Override protected void onDraw(Canvas canvas) {
            AudioControl.Source source = AudioControl.resolve(proj, ref, owner, time);
            String status; float input=0, drive=0;
            if (ref == 0) status="Off — choose an audio source";
            else if (source == null) status="No audio source here — choose another video or track";
            else if (fxErrors.containsKey(source.uri)) status="Analysis failed — tap Retry analysis";
            else {
                Music music=AudioControl.music(proj,source);
                if (music == null || (music.fx==null && feature!=Music.BEAT)) status=fxJobs.contains(source.uri)?"Analysing audio…":"Waiting for audio analysis";
                else if (time<source.start || time>=source.start+source.dur) status="Outside the source's timeline range";
                else if (feature==Music.BEAT && music.beats.length==0) status="No confident beat grid — use Bass, Level or Hits";
                else {
                    input=music.follow(music.code(feature,adaptive),source.at(time),link==null?VProject.RELEASE:link.smooth,0);
                    drive=Music.gate(input,link==null?0:link.thresh)*(post?source.volume(proj,time):1);
                    status=String.format(Locale.US,"Input %.0f%%   Drive %.0f%%",input*100,drive*100);
                    if(link!=null){
                        Music.Target target=Music.target(layer,link.target);
                        Layer evaluated=Music.at(layer,proj,time,owner,start,duration);
                        if(target!=null){float value=Music.get(evaluated,target.id);if(target.id>=Music.T_HSL_HUE&&target.id<=Music.T_HSL_LUM)value=evaluated.hsl[target.id-Music.T_HSL_HUE]-layer.hsl[target.id-Music.T_HSL_HUE];status+="   "+String.format(Locale.US,"%."+target.dec+"f",value*target.scale)+target.unit;if(!target.cyclic&&(value<=target.min||value>=target.max))status+=" (limit)";}
                        long end=link.endUs<0?duration:link.endUs;
                        if(!link.enabled)status="Bypassed";
                        else if(time<start+link.startUs||time>=start+end)status="Outside automation range";
                        else if(drive==0)status+=" — below threshold / silence";
                    }
                }
            }
            float left=Ui.dp(16),width=Math.max(1,getWidth()-Ui.dp(32));
            paint.setTextSize(Ui.dp(11));paint.setColor(Ui.DIM);
            String first=status,second="";
            while(paint.measureText(first)>width&&first.lastIndexOf(' ')>0)first=first.substring(0,first.lastIndexOf(' '));
            if(first.length()<status.length())second=status.substring(first.length()).trim();
            while(paint.measureText(second)>width&&second.length()>1)second=second.substring(0,second.length()-1);
            canvas.drawText(first,left,Ui.dp(18),paint);canvas.drawText(second,left,Ui.dp(33),paint);
            paint.setColor(Ui.LINE);canvas.drawRect(left,Ui.dp(45),left+width,Ui.dp(53),paint);canvas.drawRect(left,Ui.dp(63),left+width,Ui.dp(71),paint);
            paint.setColor(0xff738393);canvas.drawRect(left,Ui.dp(45),left+width*Math.min(1,input),Ui.dp(53),paint);
            paint.setColor(Ui.ACCENT);canvas.drawRect(left,Ui.dp(63),left+width*Math.min(1,drive),Ui.dp(71),paint);
        }
    }

    /** Sampled result curve plus amount keys: tap/drag to inspect a precise point. */
    private final class AutomationCurveView extends View {
        final Layer layer; final Music.Link link; final int owner; final long start, duration;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float[] samples = new float[65];
        long sampledVersion = Long.MIN_VALUE;
        AutomationCurveView(android.content.Context c, Layer l, Music.Link k, int owner, long start, long duration) {
            super(c); layer=l;link=k;this.owner=owner;this.start=start;this.duration=duration;automationMeters.add(this);
            setContentDescription("Automation result curve. Drag to scrub the target; diamonds show amount keys.");
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w),Ui.dp(100)); }
        @Override protected void onDraw(Canvas c) {
            Music.Target target = Music.target(layer,link.target); if(target==null)return;
            if(sampledVersion!=automationVersion){
                for(int i=0;i<samples.length;i++){
                    long rel=Math.min(Math.max(0,duration-1),duration*i/(samples.length-1));
                    Layer value=Music.at(layer,proj,start+rel,owner,start,duration);
                    float v=Music.get(value,link.target);
                    if(link.target>=Music.T_HSL_HUE&&link.target<=Music.T_HSL_LUM)v=value.hsl[link.target-Music.T_HSL_HUE];
                    samples[i]=Math.max(0,Math.min(1,(v-target.min)/Math.max(.0001f,target.max-target.min)));
                }
                sampledVersion=automationVersion;
            }
            float left=Ui.dp(16),right=getWidth()-left,top=Ui.dp(25),bottom=Ui.dp(78),width=right-left;
            p.setTextSize(Ui.dp(11));p.setColor(Ui.DIM);c.drawText("Result · "+target.name+"    ◆ "+link.amountKeys.size()+" amount keys",left,Ui.dp(15),p);
            p.setColor(Ui.PANEL);c.drawRect(left,top,right,bottom,p);
            p.setColor(0x224fc7c1);
            float rs=left+width*Math.max(0,link.startUs)/(float)Math.max(1,duration);
            float re=left+width*Math.min(duration,link.endUs<0?duration:link.endUs)/(float)Math.max(1,duration);
            if(re>rs)c.drawRect(rs,top,re,bottom,p);
            p.setStrokeWidth(Ui.dp(1.5f));p.setColor(Ui.ACCENT);
            for(int i=1;i<samples.length;i++)c.drawLine(left+width*(i-1)/(samples.length-1),bottom-samples[i-1]*(bottom-top),left+width*i/(samples.length-1),bottom-samples[i]*(bottom-top),p);
            for(Keys.Key key:link.amountKeys.list)if(key.t>=0&&key.t<duration){float x=left+width*key.t/(float)Math.max(1,duration);c.drawCircle(x,bottom+Ui.dp(7),Ui.dp(3),p);}
            if(time>=start&&time<=start+duration){p.setColor(Ui.TEXT);float x=left+width*(time-start)/(float)Math.max(1,duration);c.drawLine(x,top,x,bottom,p);}
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)getParent().requestDisallowInterceptTouchEvent(true);
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN||e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){
                float f=Math.max(0,Math.min(1,(e.getX()-Ui.dp(16))/Math.max(1,getWidth()-Ui.dp(32))));
                scrubTo(start+Math.min(Math.max(0,duration-1),Timeline.quant((long)(duration*f),proj.fps())));return true;
            }return true;
        }
    }

    private void reactRow(final boolean clip) {
        panel.addView(Ui.label(act, "Audio reaction · " + (clip ? "this clip" : "this overlay")));
        final int owner = clip ? selClip().id : selMedia() == null ? 0 : selMedia().media.id;
        final int audio = clip ? selClip().reactAudio : selOverlay().reactAudio;
        final boolean post = clip ? selClip().reactPost : selOverlay().reactPost;
        panel.addView(controlSourceRow(owner, audio, new ISet() { @Override public void set(int value) { beginEdit(); if (clip) selClip().reactAudio=value; else selOverlay().reactAudio=value; changed(); rebuildPanel(); } }));
        panel.addView(Ui.strip(act,
            chip(post ? "Scale with volume & fades" : "Ignore volume & fades", post, new View.OnClickListener() { @Override public void onClick(View v) { beginEdit(); if(clip)selClip().reactPost=!post;else selOverlay().reactPost=!post;changed();rebuildPanel(); } }),
            chip("More audio effects…", false, new View.OnClickListener() { @Override public void onClick(View v) { if(clip || selMedia()!=null)openAudioEffects(clip); else toast("Select video footage, then use Effects → Audio-driven effects."); } })));
        if (clip && mainCovered()) panel.addView(hint("This clip is covered by B-roll. Its reaction runs underneath; select the B-roll to react its picture."));
        final int src = clip ? selClip().reactSrc : selOverlay().reactSrc;
        View[] sv = new View[VProject.REACT.length];
        for (int i = 0; i < sv.length; i++) {
            final int q = i;
            sv[i] = chip(VProject.REACT[i], src == i, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    beginEdit();
                    if (clip) selClip().reactSrc = q; else selOverlay().reactSrc = q;
                    changed();
                    rebuildPanel();
                }
            });
        }
        panel.addView(Ui.strip(act, sv));
        if (src != 0) panel.addView(responseChips(clip ? selClip().reactAdapt : selOverlay().reactAdapt, new BSet() {
            @Override public void set(boolean on) { if (clip) selClip().reactAdapt = on; else selOverlay().reactAdapt = on; }
        }));
        if (clip) {
            VProject.Clip c = selClip();
            panel.addView(slider("Zoom punch", 0, 0.5f, 0, c.pulse, 100, 0, "%", new FSet() { @Override public void set(float v) { selClip().pulse = v; } }));
            panel.addView(slider("Shake", 0, 0.05f, 0, c.shake, 1000, 0, "", new FSet() { @Override public void set(float v) { selClip().shake = v; } }));
            panel.addView(slider("Flash", 0, 1, 0, c.flash, 100, 0, "%", new FSet() { @Override public void set(float v) { selClip().flash = v; } }));
        } else {
            VProject.TextItem t = selOverlay();
            panel.addView(slider("Pulse", 0, 0.6f, 0, t.pulse, 100, 0, "%", new FSet() { @Override public void set(float v) { selOverlay().pulse = v; } }));
            panel.addView(slider("Shake", 0, 0.05f, 0, t.shake, 1000, 0, "", new FSet() { @Override public void set(float v) { selOverlay().shake = v; } }));
            if(t.media!=null) panel.addView(slider("Flash",0,1,0,t.flash,100,0,"%",new FSet(){@Override public void set(float v){selMedia().flash=v;}}));
        }
        panel.addView(new ControlMeter(audio, owner, src==0?Music.BEAT:src==1?Music.BASS:Music.LEVEL, clip?selClip().reactAdapt:selOverlay().reactAdapt, post, null, null, 0, proj.duration()));
        if ((clip ? selClip().pulse+selClip().shake+selClip().flash : selOverlay().pulse+selOverlay().shake+selOverlay().flash)==0) panel.addView(hint("Raise a reaction amount above zero to enable it and analyse its source."));
        panel.addView(hint("Beat uses this source's detected grid. Bass follows low frequencies; Level follows loudness. Source routing does not change which sound you hear."));
    }

    // ================================================================ Edit tab
    private void clipsPanel() {
        if (selMedia() != null) { overlayPanel(); return; }
        final VProject.Clip c = selClip();
        if (c == null) {
            panel.addView(hint(proj.clips.isEmpty()
                    ? "Add videos and photos. They play one after another; drag the timeline to scrub, pinch it to zoom, tap a clip to edit it."
                    : "Tap a clip in the timeline to trim, split, change speed, frame it or set its transition."));
            return;
        }
        final int idx = proj.clipIndex(c.id);
        panel.addView(Ui.label(act, (c.kind == VProject.IMAGE ? "Photo · " : "Clip · ") + c.name));
        section("Timing");
        if (c.kind == VProject.IMAGE) {
            panel.addView(slider("Duration", 0.3f, 20, 3, (c.out - c.in) / 1e6f, 1, 1, " s", new FSet() {
                @Override public void set(float v) { VProject.Clip k = selClip(); k.in = 0; k.out = (long) (v * 1e6); }
            }));
        } else {
            speedSection(c);
            panel.addView(Ui.strip(act, chip(slip ? "Slip: on" : "Slip", slip, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    slip = !slip;
                    timeline.invalidate();
                    rebuildPanel();
                    if (slip) toast("Drag the clip to slide its contents: same length, same place, different moment of the video.");
                }
            }), chip("\u2039 Slip", false, nudgeL(5, -1)), chip("Slip \u203a", false, nudgeL(5, 1))));
        }

        section("Timing");
        panel.addView(nudgeRow(false));
        section("Picture");
        panel.addView(Ui.label(act, "Framing"));
        panel.addView(Ui.strip(act,
                chip("Fill", c.fit == 1, new View.OnClickListener() { @Override public void onClick(View v) { setFit(1); } }),
                chip("Fit", c.fit == 0, new View.OnClickListener() { @Override public void onClick(View v) { setFit(0); } }),
                chip("Reset", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        beginEdit();
                        VProject.Clip k = selClip();
                        k.scale = 1; k.panX = 0; k.panY = 0; k.rot = 0; k.opacity = 1;
                        changedXf();
                        rebuildPanel();
                    }
                })));
        panel.addView(sliderXf("Zoom", 0.25f, 4, 1, c.scale, 100, 0, "%", new FSet() { @Override public void set(float v) { selClip().scale = v; } }));
        panel.addView(sliderXf("Rotation", -180, 180, 0, c.rot, 1, 1, "°", new FSet() { @Override public void set(float v) { selClip().rot = v; } }));
        panel.addView(sliderXf("Opacity", 0, 1, 1, c.opacity, 100, 0, "%", new FSet() { @Override public void set(float v) { selClip().opacity = v; } }));
        panel.addView(hint("On the preview: drag to reposition, two fingers to zoom and rotate."));
        section("Motion");
        keyRow(true);
        reactRow(true);
        if (idx < proj.clips.size() - 1) {
            section("Transition");
            panel.addView(Ui.label(act, "Transition to next clip"));
            // 3.14: Cut and Dissolve stay one tap away; the rest are in a picker with previews
            panel.addView(Ui.strip(act,
                    chip("Cut", c.trans == 0, new View.OnClickListener() { @Override public void onClick(View v) { setTransition(0); } }),
                    chip("Dissolve", c.trans == 1, new View.OnClickListener() { @Override public void onClick(View v) { setTransition(1); } }),
                    Ui.chip(act, "fx", c.trans > 1 ? VProject.TRANS[c.trans] + "\u2026" : "More transitions\u2026", c.trans > 1,
                            new View.OnClickListener() { @Override public void onClick(View v) { transitionSheet(); } })));
            final int ci = idx;
            if (proj.beatTimes().length > 0)
                panel.addView(Ui.strip(act, Ui.chip(act, "music", c.trans == 0 ? "Put this cut on the nearest beat" : "Center on the nearest beat", false,
                        new View.OnClickListener() { @Override public void onClick(View v) { centerCutOnBeat(ci); } })));
            if (transFocus) panel.addView(hint("Drag the cut on the timeline to roll it: this clip gets longer and the next one shorter (or the reverse), and nothing after them moves."));
            if (c.trans != 0)
                panel.addView(slider("Duration", 0.1f, 3, 0.5f, c.transDur / 1e6f, 1, 2, " s", new FSet() {
                    @Override public void set(float v) { selClip().transDur = (long) (v * 1e6); }
                }));
            final boolean isDefault = proj.defTrans == c.trans && (c.trans == 0 || proj.defTransDur == c.transDur);
            panel.addView(Ui.strip(act,
                    chip("Apply to all cuts", false, new View.OnClickListener() { @Override public void onClick(View v) { transitionToAll(); } }),
                    chip(isDefault ? (c.trans == 0 ? "New cuts: plain" : "Used for new cuts") : "Use for new cuts", isDefault && c.trans != 0, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            VProject.Clip k = selClip(); if (k == null) return;
                            beginEdit(); proj.defTrans = k.trans; proj.defTransDur = k.trans == 0 ? 500000 : k.transDur; changed(); rebuildPanel();
                        }
                    })));
            panel.addView(hint(proj.defTrans == 0 ? "New clips join with a plain cut. \u201cUse for new cuts\u201d makes this transition the default for clips you add."
                    : String.format(Locale.US, "Clips you add join with %s (%.2f s). Pick Cut and \u201cUse for new cuts\u201d to go back to plain cuts.",
                        VProject.TRANS[proj.defTrans], proj.defTransDur / 1e6)));
        }
        section("Timing");
        if (c.kind == VProject.VIDEO)
            panel.addView(hint(String.format(Locale.US, "Source %d×%d, using %s of %s. Drag the clip's edges in the timeline to trim.",
                    c.srcW, c.srcH, TimelineView.fmtDur(c.out - c.in), TimelineView.fmtDur(c.srcDur))));
    }

    private void setFit(int f) {
        beginEdit();
        selClip().fit = f;
        changed();
        rebuildPanel();
    }

    private void split() {
        if(!timingEditable())return;
        long cut = Timeline.quant(time, proj.fps()), f = Timeline.frameTime(1, proj.fps());
        if (cut - selectedStart() < f || selectedEnd() - cut < f) {
            toast("Move the playhead inside the selected item, leaving a frame on each side."); return;
        }
        pause(); applyPreview(); beginEdit();
        if (selClip() != null) {
            VProject.Clip b = splitAt(selClip(), cut);
            select(TimelineView.SEL_CLIP, b.id, false);
        } else if (selOverlay() != null) {
            VProject.TextItem b = Edits.splitOverlay(proj, selOverlay(), cut);
            if (b != null) select(selKind, b.id, false);
        } else if (selAudio() != null) {
            VProject.AudioItem b = Edits.splitAudio(proj, selAudio(), cut);
            if (b != null) select(TimelineView.SEL_AUDIO, b.id, false);
        }
        time = cut; changed(); syncPlayers(); rebuildPanel();
    }

    /** Cuts clip c at timeline time t (inside it): c keeps the first part, the returned copy the rest. */
    private VProject.Clip splitAt(VProject.Clip c, long t) {
        return Edits.split(proj, proj.clipIndex(c.id), t);
    }

    /**
     * Freeze frame (LumaFusion's Freeze, CapCut's Freeze): the frame under the playhead held as
     * a still for 2 s, inserted there. Inside the clip it splits it; at an edge it goes before or
     * after. The still keeps the clip's framing and grade.
     */
    private void freezeFrame() {
        if(!timingEditable())return;
        VProject.Clip c = selClip();
        if (c == null || c.kind != VProject.VIDEO) return;
        int i = proj.clipIndex(c.id);
        long s = proj.start(i), F = 1000000L / proj.fps();
        if (time < s || time > s + c.dur()) { toast("Move the playhead over the clip to freeze that frame."); return; }
        beginEdit();
        long src = proj.srcTime(i, Math.min(time, s + c.dur() - 1));
        VProject.Clip fz = c.copy();
        fz.id = proj.nextId++;
        fz.kind = VProject.IMAGE;
        fz.name = c.name + " (freeze)";
        fz.frameUs = src;
        fz.curve = null;
        fz.speed = 1;
        fz.srcDur = Long.MAX_VALUE / 4;
        fz.in = 0;
        fz.out = 2000000;
        fz.keys = new Keys();
        fz.trans = 0;
        fz.still = null;
        int at;
        if (time - s < F) at = i;                          // at the start: before the clip
        else if (s + c.dur() - time < F) at = i + 1;       // at the end: after it
        else { splitAt(c, time); at = i + 1; }
        if (at == i + 1 && at < proj.clips.size()) { fz.trans = 0; }
        proj.clips.add(at, fz);
        loadClipAssets(fz);
        select(TimelineView.SEL_CLIP, fz.id, false);
        changed();
        syncPlayers();
    }

    // ---------------------------------------------------------------- speed and speed curves
    /** Selected point on the speed graph (-1 none). */
    int curvePoint = -1;

    // ================================================================ 3.15 speed
    private static final float LOG_MIN = (float) (Math.log(SpeedCurve.MIN) / Math.log(2)), LOG_MAX = (float) (Math.log(SpeedCurve.MAX) / Math.log(2));
    private static final Ui.Slider.Map SPEED_MAP = new Ui.Slider.Map() {
        @Override public float shown(float v) { return (float) Math.pow(2, v); }
        @Override public float value(float s) { return (float) (Math.log(Math.max(SpeedCurve.MIN, Math.min(SpeedCurve.MAX, s))) / Math.log(2)); }
    };

    private interface SpeedSet { void set(float speed); }

    /** A speed slider on a log scale (0.1x - 10x: half and double speed are the same distance from 1x); settles on 1x. */
    private Ui.Slider speedSlider(String name, float speed, final SpeedSet s) {
        float lv = (float) (Math.log(Math.max(SpeedCurve.MIN, Math.min(SpeedCurve.MAX, speed))) / Math.log(2));
        Ui.Slider sl = new Ui.Slider(act, name, LOG_MIN, LOG_MAX, 0, lv, new Ui.Slider.Listener() {
            @Override public void begin() { beginEdit(); }
            @Override public void change(float v) {
                float sp = (float) Math.pow(2, Math.abs(v) < 0.06f ? 0 : v);
                s.set(Math.max(SpeedCurve.MIN, Math.min(SpeedCurve.MAX, sp)));
                changed();
            }
        });
        sl.bipolar = false;   // would print "+2.00x"
        sl.map(SPEED_MAP, 2, "×");
        return sl;
    }

    /** Pitch of a constant speed change: kept (time-stretch) or following the speed (tape). */
    private View pitchRow(final VProject.Clip c, final boolean overlay) {
        return Ui.strip(act,
                chip("Keep pitch", !c.tape, new View.OnClickListener() { @Override public void onClick(View v) { setTape(overlay, false); } }),
                chip("Tape: pitch follows speed", c.tape, new View.OnClickListener() { @Override public void onClick(View v) { setTape(overlay, true); } }));
    }

    private void setTape(boolean overlay, boolean on) {
        VProject.Clip k = overlay ? (selMedia() == null ? null : selMedia().media) : selClip();
        if (k == null || k.tape == on) return;
        beginEdit(); k.tape = on; changed(); syncPlayers(); rebuildPanel();
    }

    /** A small picture of a speed curve (log speed up, footage across) for the Start from sheet. */
    private static Bitmap curveThumb(SpeedCurve sc, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Ui.PANEL2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x44ffffff);
        p.setStrokeWidth(1);
        c.drawLine(4, px / 2f, px - 4, px / 2f, p);
        Path path = new Path();
        for (int i = 0; i <= 60; i++) {
            double u = i / 60.0;
            float x = 6 + (px - 12) * (float) u;
            float y = (float) (px / 2f - Math.log10(sc.speedAt(u)) * (px / 2f - 8));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px / 24f);
        p.setColor(Ui.ACCENT);
        c.drawPath(path, p);
        return b;
    }

    /** Presets, saved curves and a flat start, as curve pictures. */
    private void curveStartSheet() {
        Sheet sh = new Sheet(act, "Start the curve from");
        final VProject.Clip c0 = selClip();
        if (c0 == null) return;
        sh.section("Basic");
        sh.tile("speed", "Flat", new Runnable() { @Override public void run() { VProject.Clip k = selClip(); if (k == null) return; float sp = k.curve != null ? (float) k.avgSpeed() : k.speed; useCurve(new SpeedCurve(new float[]{0, 0.5f, 1}, new float[]{sp, sp, sp}), 1); } });
        Sheet.setThumb(sh.lastTileDisc(), curveThumb(new SpeedCurve(new float[]{0, 1}, new float[]{1, 1}), 96));
        final SpeedCurve rampUp = new SpeedCurve(new float[]{0, 1}, new float[]{1, 4}, new int[]{SpeedCurve.EASE});
        final SpeedCurve rampDown = new SpeedCurve(new float[]{0, 1}, new float[]{4, 1}, new int[]{SpeedCurve.EASE});
        final SpeedCurve stepCut = new SpeedCurve(new float[]{0, 0.5f, 1}, new float[]{1, 0.3f, 0.3f}, new int[]{SpeedCurve.HOLD, SpeedCurve.SMOOTH});
        sh.tile("speed", "Speed up", new Runnable() { @Override public void run() { useCurve(rampUp.copy(), -1); } });
        Sheet.setThumb(sh.lastTileDisc(), curveThumb(rampUp, 96));
        sh.tile("speed", "Slow down", new Runnable() { @Override public void run() { useCurve(rampDown.copy(), -1); } });
        Sheet.setThumb(sh.lastTileDisc(), curveThumb(rampDown, 96));
        sh.tile("speed", "Hard cut to slow", new Runnable() { @Override public void run() { useCurve(stepCut.copy(), 1); } });
        Sheet.setThumb(sh.lastTileDisc(), curveThumb(stepCut, 96));
        sh.section("Presets");
        for (int i = 0; i < SpeedCurve.PRESETS.length; i++) {
            final int q = i;
            sh.tile("speed", SpeedCurve.PRESETS[i], new Runnable() { @Override public void run() { useCurve(SpeedCurve.preset(q), -1); toast(SpeedCurve.PRESET_INFO[q]); } });
            Sheet.setThumb(sh.lastTileDisc(), curveThumb(SpeedCurve.preset(i), 96));
        }
        ArrayList<Presets.Preset> mine = Presets.list(Presets.SPEED);
        if (!mine.isEmpty()) {
            sh.section("Saved");
            for (Presets.Preset pr : mine) {
                final SpeedCurve sc = pr.data instanceof org.json.JSONArray ? SpeedCurve.fromJson((org.json.JSONArray) pr.data) : null;
                if (sc == null) continue;
                sh.tile("star", pr.name, new Runnable() { @Override public void run() { useCurve(sc.copy(), -1); } });
                Sheet.setThumb(sh.lastTileDisc(), curveThumb(sc, 96));
            }
        }
        sh.show();
    }

    private void useCurve(SpeedCurve sc, int point) {
        VProject.Clip k = selClip();
        if (k == null || !timingEditable()) return;
        beginEdit(); k.setCurve(sc); curvePoint = point; changed(); syncPlayers(); rebuildPanel();
    }

    /** Runs an edit on the selected clip's curve as one undo step. */
    private interface CurveEdit { void edit(SpeedCurve c); }

    private void curveEdit(CurveEdit e) {
        VProject.Clip k = selClip();
        if (k == null || k.curve == null || !timingEditable()) return;
        beginEdit();
        e.edit(k.curve);
        k.curve.changed();
        changed(); syncPlayers(); rebuildPanel();
    }

    private void speedSection(final VProject.Clip c) {
        panel.addView(Ui.label(act, "Speed"));
        panel.addView(Ui.strip(act,
                chip("Constant", c.curve == null, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.Clip k = selClip(); if (k == null || k.curve == null || !timingEditable()) return;
                        beginEdit(); k.speed = Math.max(SpeedCurve.MIN, Math.min(SpeedCurve.MAX, (float) k.avgSpeed())); k.curve = null; changed(); syncPlayers(); rebuildPanel();
                    }
                }),
                chip("Curve", c.curve != null, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.Clip k = selClip(); if (k == null) return;
                        if (k.curve == null) useCurve(new SpeedCurve(new float[]{0, 0.5f, 1}, new float[]{k.speed, k.speed, k.speed}), 1);
                    }
                }),
                Ui.chip(act, "speed", "Start from…", false, new View.OnClickListener() { @Override public void onClick(View v) { curveStartSheet(); } })));
        if (c.curve == null) {
            panel.addView(speedSlider("Speed", c.speed, new SpeedSet() { @Override public void set(float sp) { VProject.Clip k = selClip(); if (k != null) k.speed = sp; } }));
            float[] quick = {0.1f, 0.25f, 0.5f, 1, 2, 4, 8};
            View[] qv = new View[quick.length + 1];
            for (int i = 0; i < quick.length; i++) {
                final float q = quick[i];
                qv[i] = chip(Ui.trim(q) + "×", Math.abs(c.speed - q) < 1e-3f, new View.OnClickListener() {
                    @Override public void onClick(View v) { VProject.Clip k = selClip(); if (k == null || !timingEditable()) return; beginEdit(); k.speed = q; changed(); syncPlayers(); rebuildPanel(); }
                });
            }
            qv[quick.length] = chip("Last exactly…", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    final VProject.Clip k = selClip(); if (k == null) return;
                    Ui.askNumber(act, "Clip length (seconds)", Math.round(k.dur() / 1e4f) / 100f, new Ui.NumberCallback() {
                        @Override public void value(float sec) {
                            VProject.Clip kk = selClip(); if (kk == null || sec <= 0 || !timingEditable()) return;
                            float sp = (float) ((kk.out - kk.in) / (sec * 1e6));
                            if (sp < SpeedCurve.MIN || sp > SpeedCurve.MAX) toast(String.format(Locale.US, "That needs %.2f×; speed goes from 0.1× to 10×, so it was clamped.", sp));
                            beginEdit(); kk.speed = Math.max(SpeedCurve.MIN, Math.min(SpeedCurve.MAX, sp)); changed(); syncPlayers(); rebuildPanel();
                        }
                    });
                }
            });
            panel.addView(Ui.strip(act, qv));
            if (c.hasAudio && Math.abs(c.speed - 1) > 1e-3f) panel.addView(pitchRow(c, false));
            panel.addView(hint(String.format(Locale.US, "Plays %s of footage in %s.%s", TimelineView.fmtDur(c.out - c.in), TimelineView.fmtDur(c.dur()),
                    c.speed > 4.001f ? " The preview steps through speeds above 4×; the export is smooth." : "")));
            return;
        }
        SpeedGraphView g = new SpeedGraphView(act, this);
        panel.addView(g, new LinearLayout.LayoutParams(-1, Ui.dp(190)));
        final SpeedCurve sc = c.curve;
        int n = sc.size();
        if (curvePoint >= n) curvePoint = -1;
        // points
        panel.addView(Ui.strip(act,
                chip("◀ Point", false, new View.OnClickListener() { @Override public void onClick(View v) { VProject.Clip k = selClip(); if (k == null || k.curve == null) return; curvePoint = curvePoint <= 0 ? k.curve.size() - 1 : curvePoint - 1; rebuildPanel(); } }),
                chip("Point ▶", false, new View.OnClickListener() { @Override public void onClick(View v) { VProject.Clip k = selClip(); if (k == null || k.curve == null) return; curvePoint = curvePoint < 0 || curvePoint >= k.curve.size() - 1 ? 0 : curvePoint + 1; rebuildPanel(); } }),
                chip("◆ Point at playhead", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.Clip k = selClip(); if (k == null || k.curve == null || !timingEditable()) return;
                        int i = proj.clipIndex(k.id);
                        long s = proj.start(i);
                        if (time <= s || time >= s + k.dur()) { toast("Move the playhead over the clip first."); return; }
                        beginEdit();
                        curvePoint = k.curve.addPoint(k.curveU(proj.srcTime(i, time)));
                        changed(); rebuildPanel();
                    }
                }),
                chip("+ Point between", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.Clip k = selClip(); if (k == null || k.curve == null || !timingEditable()) return;
                        int a = curvePoint < 0 ? 0 : Math.min(curvePoint, k.curve.size() - 2);
                        beginEdit();
                        curvePoint = k.curve.addPoint((k.curve.x[a] + k.curve.x[a + 1]) / 2.0);
                        changed(); rebuildPanel();
                    }
                }),
                chip("Remove point", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.Clip k = selClip(); if (k == null || k.curve == null) return;
                        if (curvePoint <= 0 || curvePoint >= k.curve.size() - 1) { toast("Pick a point between the ends first."); return; }
                        beginEdit(); k.curve.removePoint(curvePoint); curvePoint = -1; changed(); syncPlayers(); rebuildPanel();
                    }
                })));
        if (curvePoint >= 0) {
            final int pi = curvePoint;
            boolean inner = pi > 0 && pi < n - 1;
            panel.addView(Ui.label(act, String.format(Locale.US, "Point %d of %d", pi + 1, n)));
            panel.addView(speedSlider("Speed", sc.v[pi], new SpeedSet() {
                @Override public void set(float sp) { VProject.Clip k = selClip(); if (k == null || k.curve == null || pi >= k.curve.size()) return; k.curve.movePoint(pi, k.curve.x[pi], sp); }
            }));
            if (inner) {
                Ui.Slider pos = new Ui.Slider(act, "Position in the footage", 0, 1, sc.x[pi], sc.x[pi], new Ui.Slider.Listener() {
                    @Override public void begin() { beginEdit(); }
                    @Override public void change(float v) { VProject.Clip k = selClip(); if (k == null || k.curve == null || pi >= k.curve.size()) return; k.curve.movePoint(pi, v, k.curve.v[pi]); changed(); }
                });
                pos.display(100, 1, "%");
                panel.addView(pos);
            }
            final int si = pi < n - 1 ? pi : pi - 1;
            View[] shp = new View[SpeedCurve.SHAPES.length + 1];
            for (int i = 0; i < SpeedCurve.SHAPES.length; i++) {
                final int q = i;
                shp[i] = chip(SpeedCurve.SHAPES[i], sc.seg[si] == i, new View.OnClickListener() {
                    @Override public void onClick(View v) { curveEdit(new CurveEdit() { @Override public void edit(SpeedCurve k) { k.setShape(si, q); } }); }
                });
            }
            final int curShape = sc.seg[si];
            shp[SpeedCurve.SHAPES.length] = chip("Use everywhere", false, new View.OnClickListener() {
                @Override public void onClick(View v) { curveEdit(new CurveEdit() { @Override public void edit(SpeedCurve k) { java.util.Arrays.fill(k.seg, curShape); } }); }
            });
            panel.addView(Ui.label(act, pi < n - 1 ? "Shape to the next point" : "Shape into this point"));
            panel.addView(Ui.strip(act, shp));
        } else panel.addView(hint("Tap a point on the graph (or ◀ Point ▶) to set its exact speed, position and the shape of the change after it."));
        // the whole curve
        panel.addView(Ui.label(act, "Whole curve"));
        panel.addView(Ui.strip(act,
                chip("Slower ÷1.25", false, new View.OnClickListener() { @Override public void onClick(View v) { curveEdit(new CurveEdit() { @Override public void edit(SpeedCurve k) { for (int i = 0; i < k.v.length; i++) k.v[i] /= 1.25f; } }); } }),
                chip("Faster ×1.25", false, new View.OnClickListener() { @Override public void onClick(View v) { curveEdit(new CurveEdit() { @Override public void edit(SpeedCurve k) { for (int i = 0; i < k.v.length; i++) k.v[i] *= 1.25f; } }); } }),
                chip("Fit to length…", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        final VProject.Clip k = selClip(); if (k == null || k.curve == null) return;
                        Ui.askNumber(act, "Clip length (seconds)", Math.round(k.dur() / 1e4f) / 100f, new Ui.NumberCallback() {
                            @Override public void value(final float sec) {
                                final VProject.Clip kk = selClip(); if (kk == null || kk.curve == null || sec <= 0) return;
                                final float f = (float) (kk.dur() / (sec * 1e6));
                                curveEdit(new CurveEdit() { @Override public void edit(SpeedCurve s) { for (int i = 0; i < s.v.length; i++) s.v[i] *= f; } });
                                VProject.Clip after = selClip();
                                if (after != null && Math.abs(after.dur() / 1e6 - sec) > 0.05) toast("Some points hit the 0.1× / 10× limits, so it's as close as the curve allows.");
                            }
                        });
                    }
                }),
                chip("Mirror", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        curveEdit(new CurveEdit() {
                            @Override public void edit(SpeedCurve k) {
                                int m = k.x.length;
                                float[] nx = new float[m], nv = new float[m];
                                int[] ns = new int[Math.max(0, m - 1)];
                                for (int i = 0; i < m; i++) { nx[i] = 1 - k.x[m - 1 - i]; nv[i] = k.v[m - 1 - i]; }
                                for (int i = 0; i < m - 1; i++) ns[i] = k.seg[m - 2 - i];
                                k.x = nx; k.v = nv; k.seg = ns;
                            }
                        });
                    }
                }),
                Ui.chip(act, "plus", "Save curve…", false, new View.OnClickListener() { @Override public void onClick(View v) { saveSpeedCurve(); } }),
                Ui.chip(act, "pencil", "Manage saved…", false, new View.OnClickListener() { @Override public void onClick(View v) { manageSpeedCurves(); } })));
        panel.addView(hint(String.format(Locale.US, "Plays %s of footage in %s. Drag points up (faster) and down (slower), sideways to move them; tap the graph to jump there. The ramp belongs to the footage: trimming crops it, trimming back out restores it. The clip's own sound is muted while a curve is on.",
                TimelineView.fmtDur(c.out - c.in), TimelineView.fmtDur(c.dur()))));
    }

    private void moveClip(int d) {
        if(!timingEditable())return;
        VProject.Clip c = selClip();
        if (c == null) return;
        try{TimelineBatch.Result result=TimelineBatch.reorder(proj,java.util.Collections.singleton(c.id),d);beginEdit();proj=result.project;changed();syncPlayers();}
        catch(IllegalArgumentException e){toast(e.getMessage());}
    }

    // ================================================================ Grade tab
    private ArrayList<Layer> gradeList() {
        switch (gradeTarget) {
            case G_CLIP: { VProject.Clip c = selClip(); return c == null ? null : c.grade; }
            case G_BROLL: { VProject.TextItem t = selMedia(); return t == null ? null : t.media.grade; }
            case G_FOOTAGE: return proj.pgrade;
            default: return proj.grade;
        }
    }

    private String gradeTargetName() {
        switch (gradeTarget) {
            case G_CLIP: return "this clip";
            case G_BROLL: return "this overlay";
            case G_FOOTAGE: return "all footage";
            default: return "everything";
        }
    }

    Layer gradeLayer() {
        ArrayList<Layer> g = gradeList();
        if (g == null) return null;
        for (Layer l : g) if (l.id == gradeSelId) return l;
        return null;
    }

    private void setGradeTarget(int target) {
        if(target==gradeTarget)return;
        maskEdit=false;
        applyPreview();gradeTarget=target;gradeSelId=-1;renderer.soloId=0;rebuildPanel();
    }

    /**
     * Grade targets: a main clip, a B-roll / picture-in-picture overlay, all footage (under the
     * titles and graphics) or everything (the finished frame, graphics included). Processing
     * order: each clip's and overlay's own grade, then All footage, then titles and graphics on
     * top, then Everything.
     */
    private void gradePanel() {
        VProject.Clip c = selClip();
        VProject.TextItem m = selMedia();
        if (gradeTarget == G_CLIP && c == null) gradeTarget = m != null ? G_BROLL : G_FOOTAGE;
        if (gradeTarget == G_BROLL && m == null) gradeTarget = c != null ? G_CLIP : G_FOOTAGE;
        panel.addView(Ui.strip(act,
                chip(c == null ? "This clip (select one)" : "This clip", gradeTarget == G_CLIP, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (selClip() == null) { toast("Tap a clip on the main track first."); return; }
                        setGradeTarget(G_CLIP);
                    }
                }),
                m != null ? chip("This overlay", gradeTarget == G_BROLL, new View.OnClickListener() {
                    @Override public void onClick(View v) { setGradeTarget(G_BROLL); }
                }) : null,
                chip("All footage", gradeTarget == G_FOOTAGE, new View.OnClickListener() {
                    @Override public void onClick(View v) { setGradeTarget(G_FOOTAGE); }
                }),
                chip("Everything", gradeTarget == G_FINAL, new View.OnClickListener() {
                    @Override public void onClick(View v) { setGradeTarget(G_FINAL); }
                })));
        panel.addView(hint(gradeTarget == G_FOOTAGE ? "All footage: the main track and video overlays. Titles, captions, logos and visualizers sit on top, untouched."
                : gradeTarget == G_FINAL ? "Everything: the finished frame, titles and graphics included (for film grain, a final blur or vignette over the lot)."
                : gradeTarget == G_BROLL ? "Only this overlay, before it is placed: match B-roll to the main footage."
                : "Only this clip. Then All footage, then the graphics, then Everything."));
        final ArrayList<Layer> list = gradeList();
        if (list == null) return;
        InspectorSections.markList(panel, "Stack", "Effects list");
        int[] types = {Layer.BASIC, Layer.CURVES, Layer.LEVELS, Layer.HSL, Layer.LUT};
        View[] add = new View[types.length];
        for (int i = 0; i < types.length; i++) {
            final int ty = types[i];
            add[i] = chip("+ " + Layer.TYPE_NAMES[ty], false, new View.OnClickListener() {
                @Override public void onClick(View v) { addGrade(ty); }
            });
        }
        panel.addView(Ui.strip(act, add));
        panel.addView(Ui.strip(act, Ui.chip(act, "fx", "Effect", false, new View.OnClickListener() { @Override public void onClick(View v) { effectSheet(); } }),
                Ui.chip(act, "music", "Audio-driven effects", false, new View.OnClickListener() { @Override public void onClick(View v) { musicPresetSheet(); } }),
                Ui.chip(act, "star", "Looks", false, new View.OnClickListener() { @Override public void onClick(View v) { applyLook(); } }),
                Ui.chip(act, "star_on", "Save look", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ArrayList<Layer> g = gradeList();
                        LookUi.saveDialog(act, g == null ? new ArrayList<Layer>() : g, Projects.name(dir));
                    }
                })));
        ArrayList<View> cp = new ArrayList<View>();
        cp.add(Ui.chip(act, "copy", "Copy grade", false, new View.OnClickListener() { @Override public void onClick(View v) { copyGrade(); } }));
        if (Looks.hasClip()) {
            cp.add(Ui.chip(act, "paste", "Paste on top", false, new View.OnClickListener() { @Override public void onClick(View v) { pasteGrade(false, false); } }));
            cp.add(Ui.chip(act, "paste", "Paste, replacing", false, new View.OnClickListener() { @Override public void onClick(View v) { pasteGrade(false, true); } }));
            if (gradeTarget == G_CLIP && proj.clips.size() > 1)
                cp.add(Ui.chip(act, "paste", "Paste to all clips", false, new View.OnClickListener() { @Override public void onClick(View v) { pasteGrade(true); } }));
        }
        panel.addView(Ui.strip(act, cp.toArray(new View[0])));
        // 3.14: a look group shows as its own row (with its Strength) and its layers indented under it
        ArrayList<Layer> shown = new ArrayList<Layer>();
        for (Layer l : list) {
            if (l.parent != 0) continue;
            shown.add(l);
            if (l.type == Layer.GROUP) for (Layer ch : list) if (ch.parent == l.id) shown.add(ch);
        }
        for (final Layer l : shown) {
            LinearLayout r = Ui.row(act);
            boolean sel = l.id == gradeSelId;
            r.setBackground(Ui.round(sel ? 0xff2a2620 : Ui.PANEL2, 8, sel ? Ui.ACCENT : 0));
            r.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            String label = l.type == Layer.GROUP
                    ? "\u2605 Look \u00b7 " + l.name + String.format(Locale.US, " \u00b7 %d%%", Math.round(l.opacity * 100))
                    : (l.parent != 0 ? "\u21b3 " : "") + l.name;
            TextView n = Ui.text(act, (l.visible ? "" : "(off) ") + label, 14, l.visible ? Ui.TEXT : Ui.DIM);
            r.addView(n, Ui.lp(0, -2, 1f));
            r.addView(chip(l.visible ? "On" : "Off", l.visible, new View.OnClickListener() {
                @Override public void onClick(View v) { beginEdit(); l.visible = !l.visible; changed(); rebuildPanel(); }
            }));
            View sp = new View(act);
            r.addView(sp, new LinearLayout.LayoutParams(Ui.dp(6), 1));
            r.addView(chip("✕", false, new View.OnClickListener() {
                @Override public void onClick(View v) { beginEdit(); GradeGroups.remove(list, l); if (GradeGroups.byId(list, gradeSelId) == null) gradeSelId = -1; changed(); rebuildPanel(); }
            }));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { gradeSelId = l.id; inspector.focus(inspectorContext(),"Settings");rebuildPanel(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(Ui.dp(l.parent != 0 ? 30 : 12), Ui.dp(3), Ui.dp(12), Ui.dp(3));
            panel.addView(r, lp);
        }
        if (gradeLayer() != null && gradeLayer().type == Layer.GROUP) {
            section("Settings");gradeHeader(list,gradeLayer());
        } else if (gradeLayer() != null) {
            section("Settings");gradeHeader(list,gradeLayer());adj.build(panel);
            section("Mask");maskSection(gradeLayer());
            section("Automation");musicLinks(gradeLayer());
        }
        else panel.addView(hint(list.isEmpty()
                ? "Grades are stacks of adjustments: the same tools as the photo editor, applied to every frame. A clip's grade runs first, then the whole-video grade."
                : "Tap an adjustment to edit it."));
    }

    // ================================================================ 3.15 masks on effects and grades
    /**
     * Masks on video effects: the same stack as photo masks (paint, gradients, shapes, text) on
     * the output frame (a B-roll's grades: its own picture). While maskEdit is on, the viewer edits
     * the selected grade layer's mask: brush strokes, handles of the selected shape, freehand
     * outlines and gradients. Shapes are keyframed per shape (keys relative to the stack's start)
     * and can follow the music (Automation targets "<shape> · Size" and so on).
     */
    private boolean maskEdit, pendingMaskFont;
    static final int VM_SHAPE = 0, VM_BRUSH = 1, VM_FREE = 2, VM_LIN = 3, VM_RAD = 4;
    private int vmTool = VM_SHAPE, vmSel = -1, vmSelFor = -1, vmCombine = 1;
    private float vmBrush = 70, vmHard = 0.5f, vmFlow = 0.7f;
    private boolean vmErase;
    private final MaskGizmo vmGizmo = new MaskGizmo();

    /** The selected shape of the selected grade layer's mask (live), or null. */
    MaskStack.Comp selVShape() {
        Layer l = gradeLayer();
        if (l == null || l.mask == null || l.id != vmSelFor || vmSel < 0 || vmSel >= l.comps.size()) return null;
        return l.comps.get(vmSel);
    }

    /** The frame a grade mask covers: the output, or a B-roll's own picture. */
    private float[] maskFrame() {
        VProject.TextItem m = gradeTarget == G_BROLL ? selMedia() : null;
        if (m != null && m.media.srcW > 0 && m.media.srcH > 0) return new float[]{m.media.srcW, m.media.srcH};
        return new float[]{proj.outW(), proj.outH()};
    }

    /** Effect panel buttons: -1 the Mask section, 0 brush, 1 linear, 2 radial, 3 a shape, 4 text. */
    private void maskStart(int how) {
        Layer l = gradeLayer();
        if (l == null) return;
        if (how < 0) { inspector.focus(inspectorContext(), "Mask"); rebuildPanel(); return; }
        if (playing) pause();
        if (l.mask == null) {
            beginEdit();
            float[] f = maskFrame();
            VideoMasks.ensure(l, f[0], f[1], false);   // hide all: the brush or shape reveals the effect
            vmSel = -1; vmSelFor = l.id;
            changed();
        }
        maskEdit = true;
        vmErase = false;
        inspector.focus(inspectorContext(), "Mask");
        switch (how) {
            case 0: vmTool = VM_BRUSH; toast("Paint where the effect should show"); break;
            case 1: vmTool = VM_LIN; toast("Drag across the picture to place the gradient"); break;
            case 2: vmTool = VM_RAD; toast("Drag out from the centre to place the gradient"); break;
            case 3: vmShapeSheet(); break;
            case 4: vmAddKind("text"); break;
        }
        rebuildPanel();
        overlay.invalidate();
    }

    private void vmShapeSheet() {
        MaskPanel.addSheet(act, new MaskPanel.Pick() { @Override public void kind(String k) { vmAddKind(k); } });
    }

    private void vmAddKind(String kind) {
        Layer l = gradeLayer();
        if (l == null) return;
        maskEdit = true;
        if (kind.equals("freehand")) { vmTool = VM_FREE; toast("Draw around the area with one finger"); rebuildPanel(); overlay.invalidate(); return; }
        final float[] f = maskFrame();
        final int[] sz = l.mask != null ? new int[]{l.mask.getWidth(), l.mask.getHeight()} : MaskPaint.size(f[0], f[1]);
        if (kind.equals("text")) {
            Pickers.text(act, "Mask text", "", true, new Pickers.TextCb() {
                @Override public void text(String t) { vmAddComp(MaskPanel.make("text", sz[0] / 2f, sz[1] / 2f, Math.min(sz[0], sz[1]) * 0.7f, t)); }
            });
            return;
        }
        vmAddComp(MaskPanel.make(kind, sz[0] / 2f, sz[1] / 2f, Math.min(sz[0], sz[1]) * 0.45f, null));
    }

    private void vmAddComp(MaskStack.Comp c) {
        Layer l = gradeLayer();
        if (l == null) return;
        if (l.mask != null && l.comps.size() >= MaskStack.MAX) { toast("A mask holds up to " + MaskStack.MAX + " shapes: delete one first."); return; }
        if (l.mask != null && c.isRaster()) {
            int r = 0;
            for (MaskStack.Comp x : l.comps) if (x.isRaster()) r++;
            if (r >= MaskStack.MAX_RASTER) { toast("A mask holds up to " + MaskStack.MAX_RASTER + " text and drawn shapes (rectangles, polygons, stars and gradients don't count)."); return; }
        }
        if (playing) pause();
        beginEdit();
        if (l.mask == null) { float[] f = maskFrame(); VideoMasks.ensure(l, f[0], f[1], false); }
        c.op = vmCombine == 2 ? MaskStack.SUB : vmCombine == 3 ? MaskStack.INTERSECT : MaskStack.ADD;
        c.sanitize();
        l.comps.add(c);
        vmSel = l.comps.size() - 1;
        vmSelFor = l.id;
        vmTool = VM_SHAPE;
        maskEdit = true;
        changed();
        rebuildPanel();
    }

    /** After a shape edit: a keyframed shape gets a key at the playhead (its fields are the edit surface). */
    private void vmChanged() {
        MaskStack.Comp c = selVShape();
        if (c != null && !c.keys.isEmpty()) c.keys.set(time - gradeStart(), c.vec(), frameTol());
        changed();
    }

    /** Music links that point at mask shapes follow them when the stack is reordered or a shape goes. */
    private static void remapMaskLinks(Layer l, int[] newIndexOf) {
        for (int i = l.links.size() - 1; i >= 0; i--) {
            Music.Link k = l.links.get(i);
            if (k.target < Music.T_MASK) continue;
            int idx = (k.target - Music.T_MASK) / 16, ch = (k.target - Music.T_MASK) % 16;
            int ni = idx < newIndexOf.length ? newIndexOf[idx] : -1;
            if (ni < 0) l.links.remove(i); else k.target = Music.T_MASK + ni * 16 + ch;
        }
    }

    private void vmMove(int entry, int dir) {
        Layer l = gradeLayer();
        if (l == null) return;
        int n = l.comps.size();
        int[] o = new int[n + 1];
        int j = 0;
        for (int k = 0; k <= n; k++) { if (k == Math.min(l.paintAt, n)) o[j++] = -1; if (k < n) o[j++] = k; }
        int p = -1;
        for (int i = 0; i < o.length; i++) if (o[i] == entry) p = i;
        int q = p + dir;
        if (p < 0 || q < 0 || q >= o.length) return;
        beginEdit();
        int t = o[p]; o[p] = o[q]; o[q] = t;
        ArrayList<MaskStack.Comp> nc = new ArrayList<MaskStack.Comp>();
        int[] map = new int[n];
        int pa = 0;
        for (int e : o) { if (e < 0) pa = nc.size(); else { map[e] = nc.size(); nc.add(l.comps.get(e)); } }
        l.comps = nc;
        l.paintAt = pa;
        remapMaskLinks(l, map);
        if (entry >= 0) vmSel = map[entry];
        changed();
        rebuildPanel();
    }

    private void vmDelete(int k) {
        Layer l = gradeLayer();
        if (l == null || k < 0 || k >= l.comps.size()) return;
        beginEdit();
        int[] map = new int[l.comps.size()];
        for (int i = 0; i < map.length; i++) map[i] = i < k ? i : i == k ? -1 : i - 1;
        l.comps.remove(k);
        if (l.paintAt > k) l.paintAt--;
        remapMaskLinks(l, map);
        vmSel = -1;
        vmTool = VM_SHAPE;
        changed();
        rebuildPanel();
    }

    private void vmDuplicate() {
        Layer l = gradeLayer();
        MaskStack.Comp c = selVShape();
        if (l == null || c == null) return;
        if (l.comps.size() >= MaskStack.MAX) { toast("A mask holds up to " + MaskStack.MAX + " shapes."); return; }
        int r = 0;
        for (MaskStack.Comp x : l.comps) if (x.isRaster()) r++;
        if (c.isRaster() && r >= MaskStack.MAX_RASTER) { toast("A mask holds up to " + MaskStack.MAX_RASTER + " text and drawn shapes."); return; }
        beginEdit();
        MaskStack.Comp d = c.copy();
        float off = Math.min(l.mask.getWidth(), l.mask.getHeight()) * 0.04f;
        d.moveBy(off, off);
        for (Keys.Key k : d.keys.list) if (k.v.length >= MaskStack.K_N) { k.v[MaskStack.K_X0] += off; k.v[MaskStack.K_Y0] += off; if (d.type == MaskStack.LINEAR) { k.v[MaskStack.K_X1] += off; k.v[MaskStack.K_Y1] += off; } }
        int[] map = new int[l.comps.size()];
        for (int i = 0; i < map.length; i++) map[i] = i <= vmSel ? i : i + 1;
        l.comps.add(vmSel + 1, d);
        if (l.paintAt > vmSel) l.paintAt++;
        remapMaskLinks(l, map);
        vmSel++;
        changed();
        rebuildPanel();
    }

    final MaskPanel.Host vmHost = new MaskPanel.Host() {
        @Override public android.app.Activity act() { return act; }
        @Override public MaskStack.Comp shape() { return selVShape(); }
        @Override public int shapeIndex() { return vmSel; }
        @Override public void beginEdit() { if (playing) pause(); VideoEditor.this.beginEdit(); }
        @Override public void changed() { vmChanged(); }
        @Override public void rebuild() { rebuildPanel(); }
        @Override public void move(int dir) { vmMove(vmSel, dir); }
        @Override public void delete() { vmDelete(vmSel); }
        @Override public void duplicate() { vmDuplicate(); }
        @Override public void extra(LinearLayout panel, MaskStack.Comp c) { vmKeysRow(panel, c); }
        @Override public void importFont() { pendingMaskFont = true; act.pickFont(); }
    };

    /** Keyframes of the selected shape: key here, step between keys, ease, stop animating. */
    private void vmKeysRow(LinearLayout panel, final MaskStack.Comp c) {
        final long lt = time - gradeStart();
        final long tol = frameTol();
        final int at = c.keys.near(lt, tol);
        panel.addView(Ui.label(act, "Animate this shape"));
        panel.addView(hint(c.keys.isEmpty()
                ? "Add a key, move the playhead and change the shape (drag it, or any slider): every change becomes a key there. Size, place, rotation, soft edge, grow, outline and reveal all animate."
                : c.keys.size() + (c.keys.size() == 1 ? " key" : " keys") + (at >= 0 ? " · a key is at the playhead" : " · changing the shape here adds a key")));
        ArrayList<View> row = new ArrayList<View>();
        row.add(Ui.chip(act, "keyframe", at >= 0 ? "Update key" : "Key here", at >= 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                MaskStack.Comp x = selVShape();
                if (x == null) return;
                beginEdit();
                x.keys.set(time - gradeStart(), x.vec(), frameTol());
                changed();
                rebuildPanel();
            }
        }));
        if (!c.keys.isEmpty()) {
            row.add(Ui.chip(act, "frame_back", "Previous", false, new View.OnClickListener() {
                @Override public void onClick(View v) { long k = c.keys.prev(time - gradeStart(), frameTol()); if (k != Long.MIN_VALUE) scrubTo(gradeStart() + k); rebuildPanel(); }
            }));
            row.add(Ui.chip(act, "frame_fwd", "Next", false, new View.OnClickListener() {
                @Override public void onClick(View v) { long k = c.keys.next(time - gradeStart(), frameTol()); if (k != Long.MAX_VALUE) scrubTo(gradeStart() + k); rebuildPanel(); }
            }));
            if (at >= 0) row.add(Ui.chip(act, "trash", "Remove key", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    MaskStack.Comp x = selVShape();
                    if (x == null) return;
                    beginEdit();
                    x.keys.remove(x.keys.near(time - gradeStart(), frameTol()));
                    changed();
                    rebuildPanel();
                }
            }));
            row.add(Ui.chip(act, "close", "Stop animating", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    MaskStack.Comp x = selVShape();
                    if (x == null) return;
                    beginEdit();
                    x.keys = new Keys();   // keeps the shape as it is at the playhead
                    changed();
                    rebuildPanel();
                }
            }));
        }
        panel.addView(Ui.strip(act, row.toArray(new View[0])));
        int ek = at >= 0 ? at : -1;
        if (ek < 0) for (int i = 0; i < c.keys.size(); i++) if (c.keys.list.get(i).t <= lt) ek = i;
        if (ek >= 0 && ek < c.keys.size() - 1) {
            final int idx = ek;
            final Keys.Key k = c.keys.list.get(ek);
            String[] names = {"Linear", "Smooth", "Hold", "Ease in", "Ease out", "Ease in-out", "Overshoot"};
            View[] ev = new View[names.length];
            for (int i = 0; i < names.length; i++) {
                final int q = i;
                boolean on = i < 3 ? k.ease == i : (k.ease == Keys.BEZIER && k.bez != null && java.util.Arrays.equals(k.bez, Keys.EASE_BEZ[i - 3]));
                ev[i] = chip(names[i], on, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        MaskStack.Comp x = selVShape();
                        if (x == null || idx >= x.keys.size()) return;
                        beginEdit();
                        Keys.Key kk = x.keys.list.get(idx);
                        if (q < 3) { kk.ease = q; kk.bez = null; } else { kk.ease = Keys.BEZIER; kk.bez = Keys.EASE_BEZ[q - 3].clone(); }
                        changed();
                        rebuildPanel();
                    }
                });
            }
            panel.addView(Ui.label(act, "Motion to the next key"));
            panel.addView(Ui.strip(act, ev));
        }
        panel.addView(hint("To make it move with the music, open Automation below and pick “" + c.name(vmSel) + " · Size” (or Move, Rotation, Reveal...)."));
    }

    /** The Mask section of a selected effect or adjustment. */
    private void maskSection(final Layer l) {
        if (l == null || l.type == Layer.GROUP) return;
        if (l.id != vmSelFor) { vmSelFor = l.id; vmSel = -1; maskEdit = false; }
        if (l.mask == null) {
            panel.addView(hint("Mask it: the effect then shows only inside a shape or text, or where you paint. Shapes and text can be keyframed and follow the music."));
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "mask", "Shape…", false, new View.OnClickListener() { @Override public void onClick(View v) { maskStart(3); } }),
                    Ui.chip(act, "text", "Text…", false, new View.OnClickListener() { @Override public void onClick(View v) { maskStart(4); } }),
                    Ui.chip(act, "pencil", "Brush", false, new View.OnClickListener() { @Override public void onClick(View v) { maskStart(0); } }),
                    Ui.chip(act, "fit", "Linear", false, new View.OnClickListener() { @Override public void onClick(View v) { maskStart(1); } }),
                    Ui.chip(act, "grade", "Radial", false, new View.OnClickListener() { @Override public void onClick(View v) { maskStart(2); } })));
            return;
        }
        if (vmTool == VM_SHAPE && selVShape() == null && vmSel >= 0) vmSel = -1;
        LinearLayout head = Ui.row(act);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, Ui.dp(12), 0);
        head.addView(Ui.label(act, maskEdit ? "Editing the mask on the picture" : "Mask"), Ui.lp(0, -2, 1f));
        head.addView(chip(maskEdit ? "Done" : "Edit on picture", maskEdit, new View.OnClickListener() {
            @Override public void onClick(View v) { maskEdit = !maskEdit; if (maskEdit && playing) pause(); rebuildPanel(); overlay.invalidate(); }
        }));
        panel.addView(head);
        final int t = vmTool;
        panel.addView(Ui.strip(act,
                vmToolChip("Move shapes", VM_SHAPE, t), vmToolChip("Brush", VM_BRUSH, t),
                Ui.chip(act, "mask", "Shape…", false, new View.OnClickListener() { @Override public void onClick(View v) { vmShapeSheet(); } }),
                Ui.chip(act, "text", "Text…", false, new View.OnClickListener() { @Override public void onClick(View v) { vmAddKind("text"); } }),
                vmToolChip("Draw", VM_FREE, t), vmToolChip("Linear", VM_LIN, t), vmToolChip("Radial", VM_RAD, t)));
        String[] cn = {"Add", "Subtract", "Overlap"};
        View[] cc = new View[3];
        for (int i = 0; i < 3; i++) {
            final int q = i + 1;
            cc[i] = chip(cn[i], vmCombine == q, new View.OnClickListener() { @Override public void onClick(View v) { vmCombine = q; rebuildPanel(); } });
        }
        panel.addView(Ui.label(act, "New shapes"));
        panel.addView(Ui.strip(act, cc));
        // the stack, top (applied last) first
        int n = l.comps.size();
        int[] o = new int[n + 1];
        int j = 0;
        for (int k = 0; k <= n; k++) { if (k == Math.min(l.paintAt, n)) o[j++] = -1; if (k < n) o[j++] = k; }
        if (n > 0) panel.addView(Ui.label(act, "Mask stack (top applies last)"));
        for (int i = o.length - 1; i >= 0 && n > 0; i--) {
            final int e = o[i];
            boolean on = e < 0 ? vmSel < 0 : e == vmSel;
            String name = e < 0 ? "Painted" : l.comps.get(e).name(e);
            int op = e < 0 ? l.paintOp : l.comps.get(e).op;
            String tag = MaskStack.OP_NAMES[op] + (e >= 0 && l.comps.get(e).invert ? " · inverted" : "") + (e >= 0 && !l.comps.get(e).keys.isEmpty() ? " · ◆ animated" : "");
            TextView row = Ui.text(act, name + "   ·   " + tag, 14, on ? 0xff111111 : Ui.TEXT);
            row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));
            row.setBackground(Ui.round(on ? Ui.ACCENT : Ui.PANEL2, 8, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    vmSel = e;
                    vmTool = e >= 0 ? VM_SHAPE : VM_BRUSH;
                    maskEdit = true;
                    if (playing) pause();
                    rebuildPanel();
                    overlay.invalidate();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(Ui.dp(12), Ui.dp(3), Ui.dp(12), Ui.dp(3));
            panel.addView(row, lp);
        }
        MaskStack.Comp c = selVShape();
        if (c != null) {
            MaskPanel.header(panel, vmHost, c);
            MaskPanel.settings(panel, vmHost, c);
        } else {
            panel.addView(Ui.label(act, "Painted part"));
            if (n > 0) {
                View[] ops = new View[3];
                for (int i = 0; i < 3; i++) {
                    final int q = i;
                    ops[i] = chip(MaskStack.OP_NAMES[i], l.paintOp == i, new View.OnClickListener() {
                        @Override public void onClick(View v) { Layer x = gradeLayer(); if (x == null) return; beginEdit(); x.paintOp = q; changed(); rebuildPanel(); }
                    });
                }
                panel.addView(Ui.strip(act, ops));
                panel.addView(Ui.strip(act,
                        Ui.chip(act, "up", "Up", false, new View.OnClickListener() { @Override public void onClick(View v) { vmMove(-1, 1); } }),
                        Ui.chip(act, "down", "Down", false, new View.OnClickListener() { @Override public void onClick(View v) { vmMove(-1, -1); } })));
            }
            panel.addView(Ui.strip(act,
                    chip("Reveal", !vmErase, new View.OnClickListener() { @Override public void onClick(View v) { vmErase = false; vmTool = VM_BRUSH; maskEdit = true; rebuildPanel(); } }),
                    chip("Hide", vmErase, new View.OnClickListener() { @Override public void onClick(View v) { vmErase = true; vmTool = VM_BRUSH; maskEdit = true; rebuildPanel(); } })));
            Ui.Slider size = new Ui.Slider(act, "Brush size", 4, 600, 70, vmBrush, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { vmBrush = v; overlay.invalidate(); }
            });
            size.display(1, 0, " px");
            panel.addView(size);
            Ui.Slider hard = new Ui.Slider(act, "Hardness", 0, 1, 0.5f, vmHard, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { vmHard = v; }
            });
            hard.display(100, 0, "%");
            panel.addView(hard);
            Ui.Slider flow = new Ui.Slider(act, "Flow", 0.05f, 1, 0.7f, vmFlow, new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { vmFlow = v; }
            });
            flow.display(100, 0, "%");
            panel.addView(flow);
            panel.addView(Ui.strip(act,
                    chip("Paint: reveal all", false, new View.OnClickListener() { @Override public void onClick(View v) { vmFill(MaskPaint.REVEAL_ALL); } }),
                    chip("Paint: hide all", false, new View.OnClickListener() { @Override public void onClick(View v) { vmFill(MaskPaint.HIDE_ALL); } }),
                    chip("Invert paint", false, new View.OnClickListener() { @Override public void onClick(View v) { vmFill(MaskPaint.INVERT); } })));
            panel.addView(hint("Brush size is in screen pixels. The painted part stays put on the frame; use shapes for things that move (they can be keyframed)."));
        }
        panel.addView(Ui.strip(act, Ui.chip(act, "trash", "Delete mask", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Layer x = gradeLayer();
                if (x == null) return;
                beginEdit();
                for (int i = x.links.size() - 1; i >= 0; i--) if (x.links.get(i).target >= Music.T_MASK) x.links.remove(i);
                VideoMasks.remove(x);
                maskEdit = false; vmSel = -1;
                changed();
                rebuildPanel();
            }
        })));
    }

    private TextView vmToolChip(String name, final int tool, int cur) {
        return chip(name, tool == cur && maskEdit, new View.OnClickListener() {
            @Override public void onClick(View v) {
                vmTool = tool;
                maskEdit = true;
                if (tool == VM_BRUSH) vmSel = -1;
                if (playing) pause();
                rebuildPanel();
                overlay.invalidate();
            }
        });
    }

    private void vmFill(int kind) {
        Layer l = gradeLayer();
        if (l == null || l.mask == null) return;
        beginEdit();
        VideoMasks.fill(l, kind);
        changed();
    }

    // ---- the viewer while editing a mask
    /** Mask px <-> screen px for the selected grade layer, through the output frame or a B-roll's placement. */
    private final MaskGizmo.Map vmMap = new MaskGizmo.Map() {
        @Override public float[] toScreen(float mx, float my) {
            Layer l = gradeLayer();
            float[] v = viewRect(overlay.getWidth(), overlay.getHeight(), proj);
            float k = v[2] / proj.outW();
            if (l == null || l.mask == null) return new float[]{v[0], v[1]};
            float u = mx / l.mask.getWidth(), w = my / l.mask.getHeight();
            Layer pl = brollPlacement();
            if (pl != null) {
                float[] o = new float[2];
                pl.layerToImg(u * pl.srcW, w * pl.srcH, o);
                return new float[]{v[0] + o[0] * k, v[1] + o[1] * k};
            }
            return new float[]{v[0] + u * v[2], v[1] + w * v[3]};
        }

        @Override public float[] toMask(float sx, float sy) {
            Layer l = gradeLayer();
            float[] v = viewRect(overlay.getWidth(), overlay.getHeight(), proj);
            float k = v[2] / proj.outW();
            if (l == null || l.mask == null) return new float[]{0, 0};
            Layer pl = brollPlacement();
            if (pl != null) {
                float ix = (sx - v[0]) / k, iy = (sy - v[1]) / k;
                float[] r0 = new float[3], r1 = new float[3];
                pl.layerRows(r0, r1);
                float lx = r0[0] * ix + r0[1] * iy + r0[2], ly = r1[0] * ix + r1[1] * iy + r1[2];
                return new float[]{lx / pl.srcW * l.mask.getWidth(), ly / pl.srcH * l.mask.getHeight()};
            }
            return new float[]{(sx - v[0]) / v[2] * l.mask.getWidth(), (sy - v[1]) / v[3] * l.mask.getHeight()};
        }
    };

    /** A B-roll grade's frame on screen: the overlay's placement at the playhead (a copy). */
    private Layer brollPlacement() {
        if (gradeTarget != G_BROLL) return null;
        VProject.TextItem m = selMedia();
        if (m == null) return null;
        Layer c = m.layer.copy();
        float[] xf = proj.textXf(m, time);
        c.cx = xf[0]; c.cy = xf[1]; c.scale = xf[2]; c.rot = xf[3];
        return c;
    }

    private boolean vmActive() {
        Layer l = gradeLayer();
        return maskEdit && tab == T_GRADE && l != null && l.mask != null && !fullView;
    }

    private final ArrayList<Float> vmPts = new ArrayList<Float>();   // screen px: freehand outline / brush stroke
    private float vmDownX, vmDownY, vmCurX, vmCurY, vmPinchD, vmPinchA;
    private boolean vmGrab, vmStroke, vmMoved, vmMulti, vmCursor;
    private MaskPaint.Brush vmBr;
    private Canvas vmCanvas;

    /** Mask px per screen px around the middle of the frame. */
    private float vmScale() {
        float[] a = vmMap.toMask(overlay.getWidth() / 2f, overlay.getHeight() / 2f), b = vmMap.toMask(overlay.getWidth() / 2f + 10, overlay.getHeight() / 2f);
        return (float) Math.hypot(b[0] - a[0], b[1] - a[1]) / 10f;
    }

    private boolean maskTouch(MotionEvent e) {
        if (!vmActive()) return false;
        Layer l = gradeLayer();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (playing) pause();
                vmDownX = vmCurX = x; vmDownY = vmCurY = y;
                vmGrab = vmStroke = vmMoved = vmMulti = false;
                vmPts.clear();
                vmCursor = vmTool == VM_BRUSH;
                if (vmTool == VM_SHAPE) {
                    vmGrab = vmGizmo.grab(selVShape(), x, y, vmMap);
                    if (!vmGrab) {   // a tap on another shape selects it
                        for (int k = l.comps.size() - 1; k >= 0; k--) {
                            MaskStack.Comp c = l.comps.get(k);
                            float[] m = vmMap.toMask(x, y);
                            boolean in = c.isBox() ? Math.abs(MaskGizmo.local(c, m[0], m[1])[0]) <= c.x1 && Math.abs(MaskGizmo.local(c, m[0], m[1])[1]) <= c.y1
                                    : c.type == MaskStack.RADIAL && c.shape(m[0], m[1]) > 0.5f;
                            if (in && k != vmSel) { vmSel = k; vmGrab = vmGizmo.grab(c, x, y, vmMap); rebuildPanel(); break; }
                        }
                    }
                } else if (vmTool == VM_BRUSH) {
                    beginEdit();
                    l.mask = l.mask.copy(Bitmap.Config.ALPHA_8, true);   // copy on write: history keeps the old one
                    l.maskVer++;
                    vmCanvas = new Canvas(l.mask);
                    vmBr = new MaskPaint.Brush();
                    float r = Math.max(0.5f, vmBrush / 2f * vmScale());
                    vmBr.begin(vmCanvas, r, vmHard, vmFlow, vmErase);
                    float[] m = vmMap.toMask(x, y);
                    vmBr.dab(m[0], m[1]);
                    vmPts.add(m[0] / l.mask.getWidth()); vmPts.add(m[1] / l.mask.getHeight());
                    vmStroke = true;
                    changed();
                } else {
                    vmPts.add(x); vmPts.add(y);
                }
                overlay.invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    vmMulti = true;
                    vmPinchD = (float) Math.max(1, Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
                    vmPinchA = (float) Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0));
                    if (vmStroke) vmEndStroke();
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                vmCurX = x; vmCurY = y;
                if (Math.abs(x - vmDownX) + Math.abs(y - vmDownY) > Ui.dp(6)) vmMoved = true;
                if (vmMulti && e.getPointerCount() >= 2) {   // two fingers scale and turn the selected shape
                    MaskStack.Comp c = selVShape();
                    float d = (float) Math.max(1, Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
                    float an = (float) Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0));
                    if (c != null && vmTool == VM_SHAPE) {
                        if (!vmGrab) { beginEdit(); vmGrab = true; c = selVShape(); }
                        c.scaleBy(d / vmPinchD);
                        c.rotateBy((float) Math.toDegrees(an - vmPinchA));
                        vmChanged();
                    }
                    vmPinchD = d; vmPinchA = an;
                    return true;
                }
                if (vmMulti) return true;
                if (vmTool == VM_SHAPE && vmGrab) {
                    if (!vmGizmo.moved) beginEdit();   // one undo step per drag
                    if (vmGizmo.drag(selVShape(), x, y, vmMap)) vmChanged();
                } else if (vmTool == VM_BRUSH && vmStroke) {
                    float[] a = vmMap.toMask(x, y);
                    float px = vmPts.get(vmPts.size() - 2) * l.mask.getWidth(), py = vmPts.get(vmPts.size() - 1) * l.mask.getHeight();
                    vmBr.to(px, py, a[0], a[1]);
                    vmPts.add(a[0] / l.mask.getWidth()); vmPts.add(a[1] / l.mask.getHeight());
                    l.maskVer++;
                    changed();
                } else if (vmTool == VM_FREE || vmTool == VM_LIN || vmTool == VM_RAD) {
                    if (vmTool == VM_FREE) {
                        float px = vmPts.get(vmPts.size() - 2), py = vmPts.get(vmPts.size() - 1);
                        if (Math.hypot(x - px, y - py) >= 2) { vmPts.add(x); vmPts.add(y); }
                    }
                }
                overlay.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                vmCursor = false;
                if (vmStroke) vmEndStroke();
                if (vmTool == VM_SHAPE) {
                    vmGizmo.release();
                    if (vmGrab) rebuildPanel();
                    else if (!vmMoved && !vmMulti && !cancel) { maskEdit = false; rebuildPanel(); }   // a tap off every shape: done editing
                } else if (vmTool == VM_FREE && !cancel && vmPts.size() >= 16) {
                    float[] pts = new float[vmPts.size()];
                    for (int i = 0; i + 1 < pts.length; i += 2) { float[] m = vmMap.toMask(vmPts.get(i), vmPts.get(i + 1)); pts[i] = m[0]; pts[i + 1] = m[1]; }
                    float[] fh = MaskShapes.freehand(pts, Math.max(0.25f, 1.5f * vmScale()));
                    if (fh == null) toast("Draw a closed outline around an area.");
                    else {
                        MaskStack.Comp c = new MaskStack.Comp();
                        c.type = MaskStack.SHAPE; c.feather = 0;
                        c.x0 = fh[0]; c.y0 = fh[1]; c.x1 = fh[2]; c.y1 = fh[3];
                        c.pts = new float[fh.length - 4];
                        System.arraycopy(fh, 4, c.pts, 0, c.pts.length);
                        vmAddComp(c);
                    }
                } else if ((vmTool == VM_LIN || vmTool == VM_RAD) && !cancel && vmMoved) {
                    float[] a = vmMap.toMask(vmDownX, vmDownY), b = vmMap.toMask(vmCurX, vmCurY);
                    MaskStack.Comp c = new MaskStack.Comp();
                    c.type = vmTool == VM_LIN ? MaskStack.LINEAR : MaskStack.RADIAL;
                    c.x0 = a[0]; c.y0 = a[1];
                    if (c.type == MaskStack.LINEAR) { c.x1 = b[0]; c.y1 = b[1]; }
                    else { float r = (float) Math.max(1, Math.hypot(b[0] - a[0], b[1] - a[1])); c.x1 = r; c.y1 = r; c.feather = 0.65f; }
                    vmAddComp(c);
                }
                vmPts.clear();
                vmGrab = false;
                overlay.invalidate();
                return true;
            }
        }
        return true;
    }

    private void vmEndStroke() {
        Layer l = gradeLayer();
        vmStroke = false;
        vmCanvas = null;
        if (l == null || vmPts.size() < 2) return;
        float[] pts = new float[vmPts.size()];
        for (int i = 0; i < pts.length; i++) pts[i] = vmPts.get(i);
        float size = Math.max(0.5f, vmBrush / 2f * vmScale()) * 2 / Math.max(l.mask.getWidth(), l.mask.getHeight());
        ArrayList<MaskPaint.Op> ops = l.paint == null ? new ArrayList<MaskPaint.Op>() : new ArrayList<MaskPaint.Op>(l.paint);
        ops.add(new MaskPaint.Op(MaskPaint.STROKE, pts, size, vmHard, vmFlow, vmErase));
        l.paint = ops;
        vmPts.clear();
        changed();
    }

    private final Paint vmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Draws the mask editing overlay; true if it took over the overlay. */
    private boolean maskDraw(Canvas c) {
        if (!vmActive()) return false;
        MaskStack.Comp s = selVShape();
        if (vmTool == VM_SHAPE && s != null) vmGizmo.draw(c, s, vmMap, overlay.getWidth(), overlay.getHeight());
        if (vmTool == VM_FREE && vmPts.size() >= 4) vmGizmo.drawLasso(c, vmPts);
        if ((vmTool == VM_LIN || vmTool == VM_RAD) && vmPts.size() >= 2 && vmMoved) {
            vmPaint.setStyle(Paint.Style.STROKE);
            vmPaint.setStrokeWidth(Ui.dp(2));
            vmPaint.setColor(0xeeffffff);
            if (vmTool == VM_LIN) c.drawLine(vmDownX, vmDownY, vmCurX, vmCurY, vmPaint);
            else c.drawCircle(vmDownX, vmDownY, (float) Math.hypot(vmCurX - vmDownX, vmCurY - vmDownY), vmPaint);
        }
        if (vmTool == VM_BRUSH && vmCursor) {
            vmPaint.setStyle(Paint.Style.STROKE);
            vmPaint.setStrokeWidth(Ui.dp(1.5f));
            vmPaint.setColor(0xccffffff);
            c.drawCircle(vmCurX, vmCurY, vmBrush / 2f, vmPaint);
        }
        // a small badge so it's clear the picture is in mask mode
        vmPaint.setStyle(Paint.Style.FILL);
        vmPaint.setColor(0xcc101114);
        String label = vmTool == VM_BRUSH ? (vmErase ? "Mask · hide brush" : "Mask · reveal brush") : vmTool == VM_FREE ? "Mask · draw an outline"
                : vmTool == VM_LIN ? "Mask · drag a linear gradient" : vmTool == VM_RAD ? "Mask · drag a radial gradient" : "Mask · drag shapes";
        vmPaint.setTextSize(Ui.dp(12));
        float tw = vmPaint.measureText(label);
        c.drawRoundRect(new android.graphics.RectF(Ui.dp(8), Ui.dp(8), Ui.dp(20) + tw, Ui.dp(32)), Ui.dp(8), Ui.dp(8), vmPaint);
        vmPaint.setColor(Ui.ACCENT);
        c.drawText(label, Ui.dp(14), Ui.dp(25), vmPaint);
        return true;
    }

    /**
     * The selected grade layer's own controls, kept with it (the audit's effect inspector): what
     * it applies to, Mix, blend for effects, bypass, order in the stack, duplicate.
     */
    private void gradeHeader(final ArrayList<Layer> list, final Layer l) {
        if (l.type == Layer.GROUP) { lookHeader(list, l); return; }
        final int idx = list.indexOf(l);
        panel.addView(Ui.label(act, l.name + " \u00b7 " + gradeTargetName()));
        panel.addView(Ui.strip(act,
                chip(l.visible ? "On" : "Bypassed", l.visible, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); l.visible = !l.visible; changed(); rebuildPanel(); }
                }),
                chip("\u25b2 Earlier", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { moveGrade(list, l, -1); }
                }),
                chip("\u25bc Later", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { moveGrade(list, l, 1); }
                }),
                chip("Duplicate", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { duplicateGrade(list, l); }
                })));
        if (l.parent != 0) panel.addView(hint("Part of the look \u201c" + (GradeGroups.byId(list, l.parent) == null ? "" : GradeGroups.byId(list, l.parent).name) + "\u201d: its Strength scales this layer too. Earlier / Later move it within the look."));
        if (l.type != Layer.LUT)   // a LUT's Intensity is the same control, already below
            panel.addView(slider("Mix", 0, 1, 1, l.opacity, 100, 0, "%", new FSet() { @Override public void set(float v) { Layer k = gradeLayer(); if (k != null) k.opacity = v; } }));
        {   // 3.13: every grade layer blends like a photo adjustment layer (same adj path in common.glsl)
            View fav = null;
            String favKind = null, favKey = null;
            if (l.type == Layer.EFFECT) { favKind = "video.fx"; favKey = String.valueOf(l.fx); }
            for (Music.Preset pr : Music.PRESETS) if (pr.name.equals(l.name) && !l.links.isEmpty()) { favKind = "video.music"; favKey = pr.name; }
            if (favKey != null) {   // the visible way to favourite (holding a tile in the catalog is the shortcut)
                final Catalog cat = new Catalog(act, favKind);
                final String key = favKey;
                fav = Ui.chip(act, cat.fav(key) ? "star_on" : "star", cat.fav(key) ? "Favorite" : "Add to Favorites", cat.fav(key),
                        new View.OnClickListener() { @Override public void onClick(View v) { cat.toggle(key); rebuildPanel(); } });
            }
            panel.addView(Ui.strip(act, blendChip(l.mode, new ModeSet() {
                @Override public void set(int mode) { Layer k = gradeLayer(); if (k != null) k.mode = mode; }
            }), fav));
        }
        if (list.size() > 1) panel.addView(hint(String.format(Locale.US, "Step %d of %d: layers run top to bottom, so order changes the result (a blur before Edges differs from after).", idx + 1, list.size())));
    }

    // ---------------------------------------------------------------- music links
    /** Ready-made music-reactive grades: each adds an ordinary layer with its links set up. */
    private void musicPresetSheet() {
        if (gradeList() == null) { toast("Tap a clip in the timeline first, or choose All footage."); return; }
        Sheet sh = new Sheet(act, "Audio-driven effects · " + gradeTargetName()).searchable("Search effects");
        final Catalog cat = new Catalog(act, "video.music");
        sh.note("Hold a preset to add it to Favorites.");
        boolean anyFav = false;
        for (Music.Preset pr : Music.PRESETS) if (cat.fav(pr.name)) { if (!anyFav) sh.section("Favorites"); anyFav = true; musicRow(sh, cat, pr); }
        boolean anyRecent = false;
        for (String k : cat.recent) for (Music.Preset pr : Music.PRESETS) if (pr.name.equals(k)) { if (!anyRecent) sh.section("Recent"); anyRecent = true; musicRow(sh, cat, pr); }
        sh.section("Colour");
        for (int i = 0; i < Music.PRESETS.length; i++) {
            final Music.Preset pr = Music.PRESETS[i];
            if (i == 5) sh.section("Light");
            if (i == 8) sh.section("Effects");
            musicRow(sh, cat, pr);
        }
        sh.show();
    }

    /** Catalog keys are preset names (the presets are built in). */
    private void musicRow(Sheet sh, final Catalog cat, final Music.Preset pr) {
        sh.item(cat.fav(pr.name) ? "star_on" : "music", pr.name, pr.blurb,
                new Runnable() { @Override public void run() { cat.used(pr.name); addMusicPreset(pr); } },
                new Runnable() { @Override public void run() { toast(cat.toggle(pr.name) ? pr.name + " added to Favorites" : pr.name + " removed from Favorites"); } });
    }

    private void addMusicPreset(Music.Preset pr) {
        ArrayList<Layer> list = gradeList();
        if (list == null) return;
        if (previewBase != null) applyPreview();
        Crash.note("music preset " + pr.name);
        beginEdit();
        Layer l = new Layer();
        l.id = proj.nextId++;
        l.type = pr.type;
        if (pr.type == Layer.EFFECT) {
            Fx.defaults(l, pr.fx);
            l.mode = Fx.get(pr.fx).defaultMode;
        }
        l.name = pr.name;
        for (float[] kv : pr.params) {
            int k = (int) kv[0];
            if (k < 0) l.opacity = kv[1];
            else if (pr.type == Layer.EFFECT) l.fxp[k] = kv[1];
            else l.basic[k] = kv[1];
        }
        for (Music.Link k : pr.links) { Music.Link copy=k.copy();copy.audio=defaultControlSource();l.links.add(copy); }
        l.rebuildLut();
        list.add(l);
        gradeSelId = l.id;
        inspector.focus(inspectorContext(),"Automation");
        changed();
        rebuildPanel();
        toast("Added to " + gradeTargetName() + ". Choose its audio source under Audio automation.");
    }

    /** The selected grade layer's links: which setting, from what in the music, how. */
    private void musicLinks(final Layer l) {
        panel.addView(Ui.label(act, "Audio automation · " + gradeTargetName()));
        if(gradeTarget==G_CLIP&&mainCovered()) panel.addView(hint("Covered by B-roll at this time. Solo to inspect this clip, or add the effect to All footage."));
        if(gradeOwner()>0)panel.addView(targetPreviewRow());
        for (int i = 0; i < l.links.size(); i++) linkCard(l, l.links.get(i));
        panel.addView(Ui.strip(act, Ui.chip(act, "music", "Automate an effect setting…", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickLinkTarget(l.id); }
        })));
        if (l.links.isEmpty())
            panel.addView(hint("Choose a setting, its audio source and its response. Or start with Audio effects above. Each effect has its own source, range and bypass."));
    }

    private void pickLinkTarget(final int layerId) {
        Layer l = gradeLayer();
        if (l == null || l.id != layerId) return;
        Sheet sh = new Sheet(act, "Which setting moves with the music?");
        for (final Music.Target t : Music.targets(l)) {
            String now = String.format(java.util.Locale.US, "%." + t.dec + "f", Music.get(l, t.id) * t.scale) + t.unit;
            sh.item("sliders", t.name, t.id >= Music.T_HSL_HUE && t.id <= Music.T_HSL_LUM ? "added to all eight colours" : "now " + now,
                    new Runnable() {
                        @Override public void run() {
                            Layer cur = gradeLayer();
                            if (cur == null || cur.id != layerId) return;
                            beginEdit();
                            Music.Link k = new Music.Link();
                            k.target = t.id;
                            k.src = Music.LEVEL; k.adapt = true;
                            k.mode = Music.FOLLOW;
                            k.audio = defaultControlSource();
                            k.amount = t.cyclic ? 45 : (t.max - t.min) * (Music.get(cur,t.id) > (t.min+t.max)*0.5f ? -0.15f : 0.15f);
                            k.thresh = 0;
                            cur.links.add(k);
                            changed();
                            rebuildPanel();
                        }
                    });
        }
        sh.show();
    }

    private void linkCard(final Layer l, final Music.Link k) {
        final Music.Target t = Music.target(l, k.target);
        if (t == null) return;
        LinearLayout card = Ui.col(act);
        card.setBackground(Ui.round(Ui.PANEL2, 10, Ui.LINE));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        LinearLayout head = Ui.row(act);
        head.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(6), 0);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(act, t.name + "  \u2190  " + Music.SOURCES[k.src] + " \u00b7 " + Music.MODES[k.mode], 14, Ui.TEXT), Ui.lp(0, -2, 1f));
        head.addView(chip("Remove", false, new View.OnClickListener() {
            @Override public void onClick(View v) { beginEdit(); l.links.remove(k); changed(); rebuildPanel(); }
        }));
        card.addView(head);
        card.addView(controlSourceRow(gradeOwner(),k.audio,new ISet(){@Override public void set(int value){beginEdit();k.audio=value;k.phaseUri=null;k.phaseUs=-1;changed();rebuildPanel();}}));
        card.addView(Ui.strip(act,
            chip(k.enabled?"Enabled":"Bypassed",k.enabled,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.enabled=!k.enabled;changed();rebuildPanel();}}),
            chip(k.postFader?"Scale with volume & fades":"Ignore volume & fades",k.postFader,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.postFader=!k.postFader;changed();rebuildPanel();}})));
        card.addView(new ControlMeter(k.audio,gradeOwner(),k.src,k.adapt,k.postFader,l,k,gradeStart(),gradeDuration()));
        View[] src = new View[Music.SOURCES.length];
        for (int i = 0; i < src.length; i++) {
            final int q = i;
            src[i] = chip(Music.SOURCES[i], k.src == i, new View.OnClickListener() {
                @Override public void onClick(View v) { if (k.src == q) return; beginEdit(); k.src = q; changed(); rebuildPanel(); }
            });
        }
        card.addView(Ui.strip(act, src));
        if (Music.canAdapt(k.src)) card.addView(responseChips(k.adapt, new BSet() {
            @Override public void set(boolean on) { k.adapt = on; }
        }));
        View[] md = new View[Music.MODES.length];
        for (int i = 0; i < md.length; i++) {
            final int q = i;
            md[i] = chip(Music.MODES[i], k.mode == i, new View.OnClickListener() {
                @Override public void onClick(View v) { if (k.mode == q) return; beginEdit(); k.mode = q; changed(); rebuildPanel(); }
            });
        }
        card.addView(Ui.strip(act, md));
        float span = t.cyclic ? 360 : t.max - t.min;
        String what = k.mode == Music.STEP ? "Each step" : k.mode == Music.DRIFT ? "Speed at full level" : "Amount at full level";
        card.addView(slider(what, -span, span, 0, Music.amountAt(k,time-gradeStart()), t.scale, t.dec, t.unit + (k.mode == Music.DRIFT ? "/s" : ""), new FSet() {
            @Override public void set(float v) { k.amount = v; if(!k.amountKeys.isEmpty())k.amountKeys.capture(Math.max(0,time-gradeStart()),new float[]{v},frameTol()); }
        }));
        if (k.mode != Music.DRIFT)
            card.addView(slider(k.mode == Music.STEP ? "Glide" : (k.src == Music.BRIGHT ? "Smoothing" : "Fall time"), 0.02f, 2f, 0.15f, k.smooth, 1000, 0, " ms", new FSet() {
                @Override public void set(float v) { k.smooth = v; }
            }));
        if (!(k.mode == Music.STEP && k.src == Music.BEAT))
            card.addView(slider(k.mode == Music.STEP ? "Hit level" : "Threshold", 0, 0.95f, k.mode == Music.STEP ? 0.5f : 0, k.thresh, 100, 0, "%", new FSet() {
                @Override public void set(float v) { k.thresh = v; }
            }));
        if (k.mode == Music.STEP) {
            int[] ev = {1, 2, 4, 8};
            View[] ec = new View[ev.length];
            for (int i = 0; i < ev.length; i++) {
                final int q = ev[i];
                ec[i] = chip(q == 1 ? "Every hit" : "Every " + q, k.every == q, new View.OnClickListener() {
                    @Override public void onClick(View v) { if (k.every == q) return; beginEdit(); k.every = q; changed(); rebuildPanel(); }
                });
            }
            card.addView(Ui.strip(act, ec));
        }
        card.addView(Ui.strip(act,chip(expandedLinks.contains(k)?"Hide timing & keyframes":"Timing, fades & keyframes…",expandedLinks.contains(k),new View.OnClickListener(){@Override public void onClick(View v){if(!expandedLinks.add(k))expandedLinks.remove(k);rebuildPanel();}})));
        card.addView(new AutomationCurveView(act, l, k, gradeOwner(), gradeStart(), gradeDuration()));
        card.addView(Ui.strip(act,
            chip("‹ Key",false,new View.OnClickListener(){@Override public void onClick(View v){long at=k.amountKeys.prev(time-gradeStart(),frameTol());if(at!=Long.MIN_VALUE)scrubTo(gradeStart()+Math.max(0,at));}}),
            chip("Key amount",false,new View.OnClickListener(){@Override public void onClick(View v){if(time<gradeStart()||time>=gradeStart()+gradeDuration()){toast("Move inside this target to add a key.");return;}beginEdit();k.amountKeys.set(time-gradeStart(),new float[]{Music.amountAt(k,time-gradeStart())},frameTol());changed();rebuildPanel();}}),
            chip("Key ›",false,new View.OnClickListener(){@Override public void onClick(View v){long at=k.amountKeys.next(time-gradeStart(),frameTol());if(at!=Long.MAX_VALUE)scrubTo(gradeStart()+Math.min(gradeDuration()-1,at));}}),
            chip("Delete key",false,new View.OnClickListener(){@Override public void onClick(View v){int at=k.amountKeys.near(time-gradeStart(),frameTol());if(at<0)return;beginEdit();k.amountKeys.remove(at);changed();rebuildPanel();}})));
        if(expandedLinks.contains(k)){
        card.addView(Ui.strip(act,
            chip("Clear amount keys",false,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.amount=Music.amountAt(k,time-gradeStart());k.amountKeys.list.clear();changed();rebuildPanel();}})));
        card.addView(hint("Range: " + TimelineView.fmtDur(k.startUs) + " → " + (k.endUs<0?"target end":TimelineView.fmtDur(k.endUs)) + " · " + k.amountKeys.size() + " amount keys"));
        card.addView(Ui.strip(act,
            chip("Start here",false,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.startUs=Math.max(0,Math.min(gradeDuration()-frameTol()*2,time-gradeStart()));k.phaseUri=null;k.phaseUs=-1;if(k.endUs>=0&&k.endUs<=k.startUs)k.endUs=-1;changed();rebuildPanel();}}),
            chip("End here",false,new View.OnClickListener(){@Override public void onClick(View v){long end=time-gradeStart();if(end<=k.startUs){toast("Move the playhead after the range start.");return;}beginEdit();k.endUs=Math.min(gradeDuration(),end);changed();rebuildPanel();}}),
            chip("Whole target",false,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.startUs=0;k.endUs=-1;k.phaseUri=null;k.phaseUs=-1;changed();rebuildPanel();}})));
        card.addView(Ui.strip(act,
            chip(k.local?"Reset at range start":"Continue from source In",k.local,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();k.local=!k.local;k.phaseUri=null;k.phaseUs=-1;changed();rebuildPanel();}}),
            chip(loopFrom>=0?"Stop looping":"Loop this range",loopFrom>=0,new View.OnClickListener(){@Override public void onClick(View v){if(loopFrom>=0){loopFrom=loopTo=-1;}else{loopFrom=gradeStart()+k.startUs;loopTo=gradeStart()+(k.endUs<0?gradeDuration():k.endUs);scrubTo(loopFrom);play();}updateTime();rebuildPanel();}})));
        card.addView(slider("Automation fade in",0,2,0,k.fadeIn,1,2," s",new FSet(){@Override public void set(float v){k.fadeIn=v;}}));
        card.addView(slider("Automation fade out",0,2,0,k.fadeOut,1,2," s",new FSet(){@Override public void set(float v){k.fadeOut=v;}}));
        }
        String how = k.mode == Music.FOLLOW ? "Moves with the level and falls back over the fall time."
                : k.mode == Music.STEP ? (k.src == Music.BEAT ? "Advances on every beat (or every 2, 4, 8)." : "Advances each time " + Music.SOURCES[k.src].toLowerCase(java.util.Locale.US) + " rises through the hit level.")
                + (t.cyclic ? " Hue wraps round the wheel." : " Clamps at the slider's end.")
                : "Keeps moving while the music is active: faster when it's louder." + (t.cyclic ? " Hue wraps round the wheel." : "");
        TextView hn = hint(Music.SOURCE_INFO[k.src] + ". " + how);
        card.addView(hn);
        panel.addView(card, clp);
    }

    // ---------------------------------------------------------------- looks and copy/paste
    private void copyGrade() {
        ArrayList<Layer> g = gradeList();
        if (g == null || g.isEmpty()) { toast("This grade is empty: nothing to copy."); return; }
        Looks.copyStructured(g, gradeTargetName() + " grade, " + Looks.describe(g));
        toast("Copied " + Looks.describe(g) + ". Paste on another clip, the whole video, or a photo.");
    }

    /**
     * A look or pasted grade REPLACES the target grade (that is what matching shots needs, and
     * trying looks one after another doesn't pile them up). One undo step.
     */
    private void applyLook() {
        final VProject thumbBase = (previewBase != null ? previewBase : proj).copy();
        final long thumbT = time;
        LookUi.pick(act, true, new LookUi.Apply() {
            @Override public void apply(ArrayList<Layer> layers, String name) {
                if (previewBase != null) cancelPreview();
                if (gradeList() == null) { toast("Tap a clip in the timeline first."); return; }
                lastLook = layers; lastLookName = name;
                beginPreview(true);
                previewName = name;
                replaceGrades(layers, false, "look \u201c" + name + "\u201d", lookReplaces, name);
            }
        }, new LookUi.Thumbs() {
            @Override public void request(ArrayList<Layer> layers, View disc) { thumbInto(thumbBase, thumbT, layers, disc); }
        });
    }

    // ---------------------------------------------------------------- 3.14: catalog thumbnails
    /** Thumbnails already rendered this session: key -> bitmap (cleared when the picture changes). */
    private final java.util.LinkedHashMap<String, Bitmap> thumbCache = new java.util.LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) { return size() > 96; }
    };
    private long thumbCacheVersion = -1, thumbCacheTime = -1;

    /**
     * Renders the current frame with layers added on top (Everything stage) at tile size on the GL
     * thread, then shows it in the tile. One GL event per tile keeps the preview responsive.
     */
    void thumbInto(final VProject base, final long t, final ArrayList<Layer> layers, final View disc) {
        if (disc == null) return;
        if (thumbCacheVersion != automationVersion || thumbCacheTime != t) { thumbCache.clear(); thumbCacheVersion = automationVersion; thumbCacheTime = t; }
        final String key = thumbKey(layers);
        Bitmap hit = thumbCache.get(key);
        if (hit != null) { Sheet.setThumb(disc, hit); return; }
        final int long_ = 160;
        final int w = proj.outW() >= proj.outH() ? long_ : Math.max(2, Math.round(long_ * proj.outW() / (float) proj.outH()));
        final int hh = proj.outH() >= proj.outW() ? long_ : Math.max(2, Math.round(long_ * proj.outH() / (float) proj.outW()));
        gl.queueEvent(new Runnable() {
            @Override public void run() {
                final Bitmap b;
                try { b = renderer.thumb(base, t, layers, w, hh); } catch (Throwable e) { return; }
                if (b == null) return;
                h.post(new Runnable() { @Override public void run() { thumbCache.put(key, b); Sheet.setThumb(disc, b); } });
            }
        });
    }

    private static String thumbKey(ArrayList<Layer> layers) {
        StringBuilder k = new StringBuilder();
        for (Layer l : layers) {
            k.append(l.type).append(':').append(l.fx).append(':').append(java.util.Arrays.toString(l.basic)).append(java.util.Arrays.toString(l.fxp))
                    .append(java.util.Arrays.toString(l.hsl)).append(java.util.Arrays.deepToString(l.curve)).append(l.lutFile).append(l.mode).append(l.opacity).append('|');
        }
        return k.toString();
    }

    private void pasteGrade(boolean allClips) { pasteGrade(allClips, true); }

    /** 3.14: paste on top of the target's grade, or replace it (Paste to all clips replaces, for matching shots). */
    private void pasteGrade(boolean allClips, boolean replace) {
        if (!Looks.hasClip()) { toast("Nothing copied yet."); return; }
        if (previewBase != null) applyPreview();
        ArrayList<Layer> src = Looks.pasteStructured();
        replaceGrades(src != null ? src : Looks.paste(), allClips, Looks.clipLabel(), replace, null);
    }

    /** The look being previewed, so "On top / Replace" can re-apply it. */
    private ArrayList<Layer> lastLook;
    private String lastLookName;
    private boolean lookReplaces;

    private void relook(boolean replace) {
        if (lastLook == null || previewBase == null || !previewIsLook) return;
        lookReplaces = replace;
        cancelPreview();
        beginPreview(true);
        previewName = lastLookName;
        replaceGrades(lastLook, false, "look \u201c" + lastLookName + "\u201d", replace, lastLookName);
        rebuildPanel();
    }

    /**
     * 3.14: adds src to the target grade(s), on top or replacing what is there. A look (lookName set)
     * goes in as a group whose Mix is its Strength; a pasted grade keeps its own groups.
     */
    private void replaceGrades(ArrayList<Layer> src, boolean allClips, String what, boolean replace, String lookName) {
        ArrayList<Layer> ok = new ArrayList<Layer>();
        int skipped = 0;
        for (Layer l : src) if (Looks.videoOk(l) || l.type == Layer.GROUP) ok.add(l); else skipped++;
        if (ok.isEmpty()) { toast("Nothing video can use: Detail is photo-only."); return; }
        ArrayList<ArrayList<Layer>> targets = new ArrayList<ArrayList<Layer>>();
        if (allClips) { for (VProject.Clip c : proj.clips) targets.add(c.grade); }
        else {
            ArrayList<Layer> g = gradeList();
            if (g == null) { toast("Tap a clip in the timeline first."); return; }
            targets.add(g);
        }
        Crash.note("replace grades x" + targets.size());
        beginEdit();
        int lastId = -1;
        GradeGroups.Ids ids = new GradeGroups.Ids() { @Override public int next() { return proj.nextId++; } };
        for (ArrayList<Layer> g : targets) {
            if (replace) g.clear();
            if (lookName != null) {
                ArrayList<Layer> flat = new ArrayList<Layer>();
                for (Layer l : ok) if (l.type != Layer.GROUP) flat.add(l);
                lastId = GradeGroups.addLook(g, flat, lookName, ids).id;
            } else {
                ArrayList<Layer> add = GradeGroups.copyWithNewIds(ok, ids);
                g.addAll(add);
                if (!add.isEmpty()) lastId = add.get(add.size() - 1).id;
            }
        }
        gradeSelId = allClips ? -1 : lastId;
        changed();
        rebuildPanel();
        String where = allClips ? "all " + targets.size() + " clips" : gradeTargetName();
        if (previewBase != null) {   // a look being previewed: the bar has Apply / Cancel
            if (skipped > 0) toast("Detail is photo-only, so it's left out on video.");
            return;
        }
        act.snack((replace ? "Replaced the grade with " : "Added ") + what + (replace ? " on " : " to ") + where + (skipped > 0 ? " (Detail skipped)" : ""), "Undo",
                new Runnable() { @Override public void run() { doUndo(); } });
    }

    /** Effects for a clip or the whole video: the same catalog and shaders as the photo editor. */
    private void effectSheet() {
        Sheet sh = new Sheet(act, "Add effect · " + gradeTargetName()).searchable("Search effects or categories");
        fxThumbBase = (previewBase != null ? previewBase : proj).copy();
        final Catalog cat = new Catalog(act, "video.fx");
        sh.note("Hold an effect to add it to Favorites.");
        java.util.ArrayList<Fx.Def> favs = new java.util.ArrayList<Fx.Def>(), recent = new java.util.ArrayList<Fx.Def>();
        for (String k : cat.favorites) { Fx.Def d = fxByKey(k); if (d != null) favs.add(d); }
        for (String k : cat.recent) { Fx.Def d = fxByKey(k); if (d != null) recent.add(d); }
        if (!favs.isEmpty()) { sh.section("Favorites"); for (Fx.Def d : favs) fxTile(sh, cat, d); }
        if (!recent.isEmpty()) { sh.section("Recent"); for (Fx.Def d : recent) fxTile(sh, cat, d); }
        for (int c = 0; c < Fx.CATS.length; c++) {
            sh.section(Fx.CATS[c]);
            for (final Fx.Def d : Fx.ALL) if (d.cat == c) fxTile(sh, cat, d);
        }
        sh.show();
    }

    private static Fx.Def fxByKey(String key) {
        for (Fx.Def d : Fx.ALL) if (String.valueOf(d.id).equals(key)) return d;
        return null;
    }

    /** Catalog keys are effect ids (stable: never renumbered). */
    private void fxTile(Sheet sh, final Catalog cat, final Fx.Def d) {
        final String key = String.valueOf(d.id);
        sh.tile(Fx.CAT_ICONS[d.cat], (cat.fav(key) ? "\u2605 " : "") + d.name,
                new Runnable() { @Override public void run() { cat.used(key); addEffect(d.id); } },
                new Runnable() { @Override public void run() { toast(cat.toggle(key) ? d.name + " added to Favorites" : d.name + " removed from Favorites"); } });
        Layer l = new Layer();
        l.type = Layer.EFFECT;
        Fx.defaults(l, d.id);
        l.mode = d.defaultMode;
        ArrayList<Layer> one = new ArrayList<Layer>();
        one.add(l);
        thumbInto(fxThumbBase, time, one, sh.lastTileDisc());
    }

    /** The picture effect thumbnails are drawn on: the project as it was when the sheet opened. */
    private VProject fxThumbBase;

    /** Picked in the Effect sheet: previewed on the grade until Apply; another pick swaps it. */
    private void addEffect(int id) {
        if (previewBase != null && previewIsLook) cancelPreview();
        ArrayList<Layer> list = gradeList();
        if (list == null) return;
        Layer l = null;
        if (previewBase != null) for (Layer k : list) if (k.id == previewLayerId) l = k;
        if (l == null) {
            beginPreview(false);
            list = gradeList();
            l = new Layer();
            l.id = proj.nextId++;
            l.type = Layer.EFFECT;
            list.add(l);
            previewLayerId = l.id;
        }
        Fx.defaults(l, id);
        l.name = Fx.get(id).name;
        l.mode = Fx.get(id).defaultMode;
        l.opacity = 1;
        previewName = l.name;
        gradeSelId = l.id;
        inspector.focus(inspectorContext(),"Settings");
        changed();
        rebuildPanel();
    }

    // ---------------------------------------------------------------- previews (try before applying)
    /** The project before the current preview; null when nothing is being previewed. */
    private VProject previewBase;
    private String previewName;
    private boolean previewIsLook;
    private int previewLayerId = -1;

    private void beginPreview(boolean look) {
        if (previewBase != null) return;
        previewBase = proj.copy();
        previewIsLook = look;
        previewLayerId = -1;
    }

    void applyPreview() {
        if (previewBase == null) return;
        Crash.note("apply preview " + previewName);
        undo.add(previewBase);
        if (undo.size() > 80) undo.remove(0);
        redo.clear();
        previewBase = null;
        updateUndo();
        changed();
        rebuildPanel();
    }

    void cancelPreview() {
        if (previewBase == null) return;
        Crash.note("cancel preview " + previewName);
        proj = previewBase;
        previewBase = null;
        if (gradeLayer() == null) gradeSelId = -1;
        changed();
        syncPlayers();
        rebuildPanel();
    }

    private void previewBar() {
        LinearLayout bar = Ui.row(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        bar.setBackgroundColor(0xff2a2620);
        TextView t = Ui.text(act, (previewIsLook ? "Previewing look \u00b7 " : "Previewing \u00b7 ") + previewName, 13, Ui.ACCENT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, Ui.lp(0, -2, 1f));
        bar.addView(Ui.chip(act, "Try another", false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (previewIsLook) applyLook(); else effectSheet(); }
        }));
        if (previewIsLook && lastLook != null) {
            panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            bar = Ui.row(act);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(Ui.dp(14), 0, Ui.dp(10), Ui.dp(6));
            bar.setBackgroundColor(0xff2a2620);
            bar.addView(Ui.chip(act, "On top of the grade", !lookReplaces, new View.OnClickListener() { @Override public void onClick(View v) { if (lookReplaces) relook(false); } }));
            View gap = new View(act); bar.addView(gap, new LinearLayout.LayoutParams(Ui.dp(6), 1));
            bar.addView(Ui.chip(act, "Replace the grade", lookReplaces, new View.OnClickListener() { @Override public void onClick(View v) { if (!lookReplaces) relook(true); } }));
            bar.addView(new View(act), Ui.lp(0, 1, 1f));
        }
        bar.addView(Ui.chip(act, "Cancel", false, new View.OnClickListener() { @Override public void onClick(View v) { cancelPreview(); } }));
        TextView ap = Ui.chip(act, "Apply", true, new View.OnClickListener() { @Override public void onClick(View v) { applyPreview(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(6);
        bar.addView(ap, lp);
        panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------------------------------------------------------- 3.14: look groups in the grade inspector
    private final GradeGroups.Ids gradeIds = new GradeGroups.Ids() { @Override public int next() { return proj.nextId++; } };

    private void moveGrade(ArrayList<Layer> list, Layer l, int dir) {
        beginEdit();
        if (!GradeGroups.move(list, l, dir)) { toast(l.parent != 0 ? "That's the end of this look." : dir < 0 ? "Already first." : "Already last."); }
        changed(); rebuildPanel();
    }

    private void duplicateGrade(ArrayList<Layer> list, Layer l) {
        beginEdit();
        Layer d = GradeGroups.duplicate(list, l, gradeIds);
        if (d != null) { d.name = l.name + " copy"; gradeSelId = d.id; }
        changed(); rebuildPanel();
    }

    /** A look's own controls: Strength (the group's Mix), bypass, order, duplicate, ungroup. */
    private void lookHeader(final ArrayList<Layer> list, final Layer g) {
        int n = 0; for (Layer c : list) if (c.parent == g.id) n++;
        panel.addView(Ui.label(act, "Look \u00b7 " + g.name + " \u00b7 " + n + (n == 1 ? " layer" : " layers")));
        panel.addView(Ui.strip(act,
                chip(g.visible ? "On" : "Bypassed", g.visible, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); g.visible = !g.visible; changed(); rebuildPanel(); }
                }),
                chip("\u25b2 Earlier", false, new View.OnClickListener() { @Override public void onClick(View v) { moveGrade(list, g, -1); } }),
                chip("\u25bc Later", false, new View.OnClickListener() { @Override public void onClick(View v) { moveGrade(list, g, 1); } }),
                chip("Duplicate", false, new View.OnClickListener() { @Override public void onClick(View v) { duplicateGrade(list, g); } }),
                chip("Ungroup", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        beginEdit(); GradeGroups.ungroup(list, g); gradeSelId = -1; changed(); rebuildPanel();
                        toast("The look's layers stay, at full strength.");
                    }
                })));
        panel.addView(slider("Strength", 0, 1, 1, g.opacity, 100, 0, "%", new FSet() {
            @Override public void set(float v) { Layer k = gradeLayer(); if (k != null) k.opacity = v; }
        }));
        panel.addView(hint("How much of the look shows: 0 % is the picture without it, 100 % is the look as made. Tap a layer under it to edit that layer."));
    }

    private void addGrade(int type) {
        applyPreview();
        ArrayList<Layer> list = gradeList();
        if (list == null) return;
        beginEdit();
        Layer l = new Layer();
        l.id = proj.nextId++;
        l.type = type;
        l.name = Layer.TYPE_NAMES[type];
        l.rebuildLut();
        list.add(l);
        gradeSelId = l.id;
        inspector.focus(inspectorContext(),"Settings");
        changed();
        rebuildPanel();
        if (type == Layer.LUT) Pickers.lut(act, new Pickers.Pick() {
            @Override public void picked(String key) { lutImported(key); }
            @Override public void importNew() { act.pickLut(); }
        });
    }

    // ================================================================ Text tab
    private void editSelectedText() {
        if (selText() == null) return;
        Pickers.text(act, "Edit text", selText().layer.text, true, new Pickers.TextCb() {
            @Override public void text(String s) { if (selText() == null) return; beginEdit(); Layer k = selText().layer; k.text = s; TextRender.refreshPreview(k); changed(); rebuildPanel(); }
        });
    }

    private void textPanel() {
        if(selText()==null)panel.addView(Ui.strip(act, chip("+ Text at playhead", false, new View.OnClickListener() {
            @Override public void onClick(View v) { addText(); }
        }), Ui.chip(act, "text", "Captions", false, new View.OnClickListener() {
            @Override public void onClick(View v) { captionSheet(); }
        })));
        final VProject.TextItem ti = selText();
        if (ti == null) {
            panel.addView(hint("Titles and captions have their own timeline items. Use Add to create one; use More for movement and arrangement. Edge handles change their duration."));
            return;
        }
        section("Style");
        final Layer l = ti.layer;
        if (ti.caption) panel.addView(Ui.label(act, "Caption"));
        TextView body = Ui.button(act, l.text.length() > 60 ? l.text.substring(0, 60) + "…" : l.text, new View.OnClickListener() {
            @Override public void onClick(View v) { editSelectedText(); }
        });
        body.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(4));
        panel.addView(body, blp);
        TextView fontBtn = Ui.button(act, "Font: " + TextRender.displayName(l.font), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Pickers.font(act, l.font, l.text, new Pickers.Pick() {
                    @Override public void picked(String key) { fontImported(key); }
                    @Override public void importNew() { act.pickFont(); }
                });
            }
        });
        fontBtn.setTypeface(TextRender.typeface(l.font, false, false));
        fontBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        panel.addView(fontBtn, blp);
        section("Timing");panel.addView(nudgeRow(true));section("Style");
        panel.addView(Ui.strip(act,
                chip("Bold", l.bold, new View.OnClickListener() { @Override public void onClick(View v) { textEdit(0); } }),
                chip("Italic", l.italic, new View.OnClickListener() { @Override public void onClick(View v) { textEdit(1); } }),
                chip("Left", l.align == 0, new View.OnClickListener() { @Override public void onClick(View v) { textEdit(2); } }),
                chip("Center", l.align == 1, new View.OnClickListener() { @Override public void onClick(View v) { textEdit(3); } }),
                chip("Right", l.align == 2, new View.OnClickListener() { @Override public void onClick(View v) { textEdit(4); } }),
                chip("Color", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Pickers.color(act, "Text color", selText().layer.fillColor, new Pickers.ColorCb() {
                            @Override public void begin() { beginEdit(); }
                            @Override public void color(int c) { Layer k = selText().layer; k.fillColor = c; TextRender.refreshPreview(k); changed(); }
                            @Override public void done() { rebuildPanel(); }
                        });
                    }
                }),
                chip("Center on screen", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); Layer k = selText().layer; k.cx = proj.outW() / 2f; k.cy = proj.outH() / 2f; k.rot = 0; changedXf(); }
                }),
                chip("Box", l.textBg != 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        beginEdit(); Layer k = selText().layer; k.textBg = k.textBg != 0 ? 0 : CAP_BOX; TextRender.refreshPreview(k); changed(); rebuildPanel();
                    }
                }),
                linkChip()));
        if (ti.caption) panel.addView(Ui.strip(act, chip("Style all captions like this", false, new View.OnClickListener() {
            @Override public void onClick(View v) { styleAllCaptions(selText()); }
        })));
        panel.addView(slider("Size", 8, proj.outH() * 0.6f, proj.outH() * 0.08f, l.textSize, 1, 0, " px", new FSet() {
            @Override public void set(float v) { Layer k = selText().layer; k.textSize = v; TextRender.refreshPreview(k); }
        }));
        panel.addView(slider("Tracking", -0.15f, 1f, 0, l.tracking, 1000, 0, "", new FSet() {
            @Override public void set(float v) { Layer k = selText().layer; k.tracking = v; TextRender.refreshPreview(k); }
        }));
        panel.addView(slider("Line spacing", 0.5f, 2.5f, 1, l.leading, 1, 2, "×", new FSet() {
            @Override public void set(float v) { Layer k = selText().layer; k.leading = v; TextRender.refreshPreview(k); }
        }));
        panel.addView(sliderXf("Opacity", 0, 1, 1, l.opacity, 100, 0, "%", new FSet() { @Override public void set(float v) { selText().layer.opacity = v; } }));
        section("Motion");
        keyRow(false);
        reactRow(false);
        section("Timing");
        panel.addView(Ui.label(act, "Timing"));
        panel.addView(slider("Duration", 0.2f, 60, 3, ti.dur / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selText().dur = (long) (v * 1e6); } }));
        panel.addView(slider("Fade in", 0, 3, 0.3f, ti.fadeIn / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selText().fadeIn = (long) (v * 1e6); selText().fadeOffset = 0; selText().fadeSpan = -1; } }));
        panel.addView(slider("Fade out", 0, 3, 0.3f, ti.fadeOut / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selText().fadeOut = (long) (v * 1e6); selText().fadeOffset = 0; selText().fadeSpan = -1; } }));
        panel.addView(hint("On the preview: drag the text to place it, two fingers to scale and rotate."));
    }

    private void textEdit(int what) {
        VProject.TextItem t = selText();
        if (t == null) return;
        beginEdit();
        Layer l = t.layer;
        if (what == 0) l.bold = !l.bold;
        else if (what == 1) l.italic = !l.italic;
        else l.align = what - 2;
        TextRender.refreshPreview(l);
        changed();
        rebuildPanel();
    }

    private void addText() {
        Pickers.text(act, "New text", "", true, new Pickers.TextCb() {
            @Override public void text(String s) {
                beginEdit();
                VProject.TextItem t = new VProject.TextItem();
                t.id = proj.nextId++;
                t.start = Math.min(time, Math.max(0, proj.duration() - 500000));
                if (proj.duration() <= 0) t.start = 0;
                t.link.on = true;   // titles follow the clip they're on
                Layer l = new Layer();
                l.id = t.id;
                l.type = Layer.TEXT;
                l.text = s;
                l.name = "Text";
                l.fillColor = 0xffffffff;
                l.textSize = Math.round(Math.min(proj.outW(), proj.outH()) * 0.08f);
                l.cx = proj.outW() / 2f;
                l.cy = proj.outH() / 2f;
                l.scale = 1;
                TextRender.refreshPreview(l);
                t.layer = l;
                proj.texts.add(t);
                select(TimelineView.SEL_TEXT, t.id, false);
                changed();
            }
        });
    }

    // ================================================================ media overlays (PiP, B-roll, logos)
    void overlaySheet() {
        Sheet sh = new Sheet(act, "Add an overlay at the playhead");
        sh.item("video", "Picture-in-picture", "A video in a corner, over the main track", new Runnable() { @Override public void run() { pickOverlay(0); } });
        sh.item("film", "B-roll", "A video over the whole frame; the main track's sound keeps playing", new Runnable() { @Override public void run() { pickOverlay(1); } });
        sh.item("image", "Logo or photo", "PNG transparency is kept", new Runnable() { @Override public void run() { pickOverlay(2); } });
        sh.show();
    }

    private void pickOverlay(int kind) {
        overlayKind = kind;
        pickMedia(REQ_OVERLAY, kind == 2 ? new String[]{"image/*"} : new String[]{"video/*"});
    }

    private void importOverlay(final Uri u) {
        final int kind = overlayKind;
        showStatus("Reading media…");
        bg.execute(new Runnable() {
            @Override public void run() {
                final VProject.Clip m = new VProject.Clip();
                String err = null;
                m.uri = u.toString();
                m.name = act.displayName(u, kind == 2 ? "Logo" : "Overlay");
                try {
                    if (kind == 2) {
                        Bitmap b = Media.decodeImage(act.getContentResolver(), u, 2048);
                        m.kind = VProject.IMAGE;
                        m.still = b;
                        m.srcW = b.getWidth();
                        m.srcH = b.getHeight();
                        m.srcDur = Long.MAX_VALUE / 4;
                    } else {
                        Media.probeVideo(act, u, m);
                        m.kind = VProject.VIDEO;
                    }
                    m.in = 0;
                    m.mute = true;   // B-roll and PiP usually ride on the main track's sound; one tap turns it on
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String er = err;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (er != null) { toast("Couldn't use that file: " + er); return; }
                        addOverlay(m, kind);
                    }
                });
            }
        });
    }

    private void addOverlay(VProject.Clip m, int kind) {
        beginEdit();
        VProject.TextItem t = new VProject.TextItem();
        t.id = proj.nextId++;
        m.id = proj.nextId++;
        t.media = m;
        int W = proj.outW(), H = proj.outH();
        Layer l = new Layer();
        l.id = t.id;
        l.type = Layer.IMAGE;
        l.name = m.name;
        l.srcW = m.srcW;
        l.srcH = m.srcH;
        l.rot = 0;
        float fit = Math.min(W / (float) m.srcW, H / (float) m.srcH);
        if (kind == 1) {
            l.scale = Math.max(W / (float) m.srcW, H / (float) m.srcH);   // fill the frame
            l.cx = W / 2f;
            l.cy = H / 2f;
        } else {
            // PiP: 38% of the frame's width; logo: 18%; inset from the corner by 4% of the short side
            float frac = kind == 0 ? 0.38f : 0.18f;
            l.scale = Math.min(fit, W * frac / m.srcW);
            float inset = Math.min(W, H) * 0.04f;
            float hw = m.srcW * l.scale / 2, hh = m.srcH * l.scale / 2;
            l.cx = W - inset - hw;
            l.cy = kind == 0 ? H - inset - hh : inset + hh;   // PiP bottom right, logo top right
        }
        t.layer = l;
        t.fadeIn = t.fadeOut = kind == 2 ? 300000 : 0;
        long end = proj.duration();
        t.start = end > 0 ? Math.min(time, Math.max(0, end - 500000)) : 0;
        if (m.kind == VProject.IMAGE) t.dur = end > t.start ? end - t.start : 5000000;   // a logo runs to the end
        else t.dur = m.srcDur;
        t.reactAudio = m.kind == VProject.VIDEO && m.hasAudio ? AudioControl.SELF : AudioControl.MAIN;
        t.link.on = kind != 2;   // picture-in-picture and B-roll follow their clip; a logo stays put
        VProject.syncOverlayOut(t);
        proj.texts.add(t);
        loadClipAssets(m);
        select(TimelineView.SEL_MEDIA, t.id, false);
        changed();
        syncPlayers();
    }

    private void overlayPanel() {
        final VProject.TextItem t = selMedia();
        final VProject.Clip m = t.media;
        final boolean video = m.kind == VProject.VIDEO;
        panel.addView(Ui.label(act, (video ? "Video overlay · " : "Image overlay · ") + m.name));
        section("Timing");
        panel.addView(hint("Timeline " + Timeline.timecode(t.start, proj.fps()) + " → " + Timeline.timecode(t.start + t.dur, proj.fps())
                + " · Source " + Timeline.timecode(m.in, proj.fps()) + " → " + Timeline.timecode(m.out, proj.fps())));
        int W = proj.outW(), H = proj.outH();
        section("Picture");
        panel.addView(Ui.strip(act,
                chip("Bottom right", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(3); } }),
                chip("Bottom left", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(2); } }),
                chip("Top right", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(1); } }),
                chip("Top left", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(0); } }),
                chip("Center", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(4); } }),
                chip("Fill frame", false, new View.OnClickListener() { @Override public void onClick(View v) { placeOverlay(5); } })));
        ArrayList<View> acts = new ArrayList<View>();

        acts.add(chip("Bring forward", false, new View.OnClickListener() { @Override public void onClick(View v) { restack(1); } }));
        acts.add(chip("Send back", false, new View.OnClickListener() { @Override public void onClick(View v) { restack(-1); } }));
        acts.add(blendChip(t.layer.mode, new ModeSet() {
            @Override public void set(int mode) { VProject.TextItem s = selMedia(); if (s != null) s.layer.mode = mode; }
        }));
        panel.addView(Ui.strip(act, acts.toArray(new View[0])));
        section("Timing");
        panel.addView(nudgeRow(true));panel.addView(Ui.strip(act,linkChip()));
        section("Picture");
        panel.addView(sliderXf("Size", 0.02f, 4, 1, t.layer.scale, 100, 0, "%", new FSet() { @Override public void set(float v) { selMedia().layer.scale = v; } }));
        panel.addView(sliderXf("Opacity", 0, 1, 1, t.layer.opacity, 100, 0, "%", new FSet() { @Override public void set(float v) { selMedia().layer.opacity = v; } }));
        if (video) {
            section("Timing");
            panel.addView(speedSlider("Speed", m.speed, new SpeedSet() {
                @Override public void set(float v) { VProject.TextItem s = selMedia(); if (s != null) Edits.overlaySpeed(s, v); }
            }));
            if (m.hasAudio && Math.abs(m.speed - 1) > 1e-3f) panel.addView(pitchRow(m, true));
        }
        section("Motion");
        keyRow(false);
        reactRow(false);
        section("Timing");
        panel.addView(Ui.label(act, "Timing"));
        panel.addView(slider("Fade in", 0, 3, 0, t.fadeIn / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selMedia().fadeIn = (long) (v * 1e6); selMedia().fadeOffset = 0; selMedia().fadeSpan = -1; } }));
        panel.addView(slider("Fade out", 0, 3, 0, t.fadeOut / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selMedia().fadeOut = (long) (v * 1e6); selMedia().fadeOffset = 0; selMedia().fadeSpan = -1; } }));
        panel.addView(hint(video
                ? "On the preview: drag to place it, two fingers to scale and rotate. Use More to move it on the timeline. Later overlays draw on top."
                : "On the preview: drag to place it, two fingers to scale and rotate. Drag its edges on the overlay row to set how long it shows."));
    }

    /** 0 TL, 1 TR, 2 BL, 3 BR, 4 centre (keeps size), 5 fill the frame. */
    private void placeOverlay(int where) {
        VProject.TextItem t = selMedia();
        if (t == null) return;
        beginEdit();
        Layer l = t.layer;
        int W = proj.outW(), H = proj.outH();
        if (where == 5) {
            l.scale = Math.max(W / (float) l.srcW, H / (float) l.srcH);
            l.cx = W / 2f; l.cy = H / 2f; l.rot = 0;
        } else {
            if (where < 4 && l.srcW * l.scale > W * 0.9f) l.scale = W * 0.38f / l.srcW;   // a full-frame overlay shrinks to PiP size
            float inset = Math.min(W, H) * 0.04f, hw = l.srcW * l.scale / 2, hh = l.srcH * l.scale / 2;
            l.cx = where == 4 ? W / 2f : (where % 2 == 0 ? inset + hw : W - inset - hw);
            l.cy = where == 4 ? H / 2f : (where < 2 ? inset + hh : H - inset - hh);
        }
        changedXf();
        rebuildPanel();
    }

    /** Moves the selected overlay up (+1) or down (-1) in drawing order among all overlays. */
    private void restack(int dir) {
        VProject.TextItem t = selMedia();
        if (t == null) return;
        int i = proj.texts.indexOf(t), j = i + dir;
        if (j < 0 || j >= proj.texts.size()) { toast(dir > 0 ? "Already on top." : "Already at the bottom."); return; }
        beginEdit();
        proj.texts.remove(i);
        proj.texts.add(j, t);
        changed();
    }

    // ================================================================ Visual tab (music visualizers)

    // ================================================================ 3.15 Visuals
    private static final java.util.HashMap<String, Bitmap> vizThumbs = new java.util.HashMap<String, Bitmap>();

    /** A style or preset tile picture (assets/vizthumbs, rendered from viz.frag at build time). */
    private Bitmap vizThumb(String key) {
        Bitmap b = vizThumbs.get(key);
        if (b != null) return b;
        try {
            java.io.InputStream in = act.getAssets().open("vizthumbs/" + key + ".png");
            b = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Exception e) { b = null; }
        if (b != null) vizThumbs.put(key, b);
        return b;
    }

    /** Styles as picture tiles: adds a visualizer, or changes the selected one's style. */
    private void vizStyleSheet(final boolean add) {
        Sheet sh = new Sheet(act, add ? "Add a visualizer" : "Visualizer style");
        sh.section("Styles");
        for (int i = 0; i < VProject.Viz.TYPES.length; i++) {
            final int q = i;
            sh.tile("bars", VProject.Viz.TYPES[i], new Runnable() { @Override public void run() { if (add) addViz(q); else setVizType(q); } });
            Sheet.setThumb(sh.lastTileDisc(), vizThumb("style" + i));
        }
        sh.note("Every style follows the music on the sync track. Presets set a whole look at once.");
        sh.item("star", "Presets…", "Neon bars, Trap circle, Unknown Pleasures, Hi-fi meter…", new Runnable() { @Override public void run() { vizPresetSheet(add); } });
        sh.show();
    }

    private void vizPresetSheet(final boolean add) {
        Sheet sh = new Sheet(act, add ? "Add a visualizer from a preset" : "Apply a preset (keeps place and timing)");
        for (final Visuals.Preset p : Visuals.PRESETS) {
            sh.tile("star", p.name, new Runnable() {
                @Override public void run() {
                    if (add) { addViz(p.type); if (selViz() == null) return; }
                    else beginEdit();
                    VProject.TextItem t = selViz();
                    if (t == null) return;
                    boolean wasRound = Visuals.round(t.viz.type);
                    Visuals.apply(t.viz, p.key);
                    if (!add) fitVizBox(t, wasRound);
                    changed();
                    rebuildPanel();
                }
            });
            Sheet.setThumb(sh.lastTileDisc(), vizThumb(p.key));
        }
        sh.show();
    }

    /** Round styles want a square box, the others a wide one: reshape when the style family changes. */
    private void fitVizBox(VProject.TextItem t, boolean wasRound) {
        boolean round = Visuals.round(t.viz.type);
        Layer l = t.layer;
        if (round && !wasRound && l.srcW > l.srcH * 1.3f) {
            int sq = Math.round(Math.min(proj.outW(), proj.outH()) * 0.7f);
            l.srcW = sq; l.srcH = sq;
        } else if (!round && wasRound && l.srcW < l.srcH * 1.3f) {
            l.srcW = Math.round(proj.outW() * 0.9f);
            l.srcH = Math.round(proj.outH() * (t.viz.type == VProject.Viz.RIDGES ? 0.6f : 0.32f));
        }
    }

    private void setVizType(int q) {
        VProject.TextItem t = selViz();
        if (t == null) return;
        beginEdit();
        boolean wasRound = Visuals.round(t.viz.type);
        t.viz.type = q;
        fitVizBox(t, wasRound);
        changed();
        rebuildPanel();
    }

    private TextView vchip(String s, boolean on, final Runnable r) {
        return chip(s, on, new View.OnClickListener() {
            @Override public void onClick(View v) { if (selViz() == null) return; beginEdit(); r.run(); changed(); rebuildPanel(); }
        });
    }

    private void vizPanel() {
        final VProject.TextItem ti = selViz();
        if (ti == null) {
            panel.addView(Ui.strip(act,
                    Ui.chip(act, "plus", "Add a visualizer…", false, new View.OnClickListener() { @Override public void onClick(View v) { vizStyleSheet(true); } }),
                    Ui.chip(act, "star", "From a preset…", false, new View.OnClickListener() { @Override public void onClick(View v) { vizPresetSheet(true); } })));
            if (!syncNeeded("A visualizer")) { /* the buttons are shown */ }
            else if (proj.songFx == null) panel.addView(hint("Analysing the song… visualizers come alive when it's done."));
            panel.addView(hint("Eleven styles (bars, circles, an oscilloscope, a hi-fi meter, Unknown Pleasures ridgelines, a spectrogram, bass shockwaves...) and presets that set a whole look. Each sits on the overlay track: place it like text, animate it with keyframes, mask effects with it."));
            return;
        }
        if (syncNeeded("A visualizer") && proj.songFx == null) panel.addView(hint("Analysing the song… visualizers come alive when it's done."));
        final VProject.Viz z = ti.viz;
        final Layer l = ti.layer;
        final int ty = z.type;
        section("Style");
        LinearLayout sr = Ui.row(act);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        android.widget.ImageView th = new android.widget.ImageView(act);
        th.setImageBitmap(vizThumb("style" + ty));
        th.setBackground(Ui.round(Ui.PANEL2, 10, Ui.LINE));
        th.setClipToOutline(true);
        sr.addView(th, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
        LinearLayout tx = Ui.col(act);
        tx.setPadding(Ui.dp(12), 0, 0, 0);
        String pk = Visuals.presetOf(z);
        String pname = null;
        if (pk != null) for (Visuals.Preset p : Visuals.PRESETS) if (p.key.equals(pk)) pname = p.name;
        tx.addView(Ui.text(act, VProject.Viz.TYPES[ty] + (pname != null ? "  ·  " + pname : ""), 16, Ui.TEXT));
        tx.addView(Ui.text(act, Visuals.STYLE_INFO[ty], 12, Ui.DIM));
        sr.addView(tx, Ui.lp(0, -2, 1f));
        panel.addView(sr);
        panel.addView(Ui.strip(act,
                Ui.chip(act, "bars", "Change style…", false, new View.OnClickListener() { @Override public void onClick(View v) { vizStyleSheet(false); } }),
                Ui.chip(act, "star", "Presets…", false, new View.OnClickListener() { @Override public void onClick(View v) { vizPresetSheet(false); } })));

        // ---- colour
        section("Color");
        View[] pal = new View[Visuals.PALETTES.length];
        for (int i = 0; i < pal.length; i++) {
            final int q = i;
            pal[i] = vchip(Visuals.PALETTE_NAMES[i], Visuals.paletteIs(z, i), new Runnable() { @Override public void run() { Visuals.setPalette(selViz().viz, q); } });
        }
        panel.addView(Ui.strip(act, pal));
        ArrayList<View> cs = new ArrayList<View>();
        cs.add(chip("Color 1…", false, new View.OnClickListener() { @Override public void onClick(View v) { pickVizColor(1); } }));
        cs.add(chip("Color 2…", false, new View.OnClickListener() { @Override public void onClick(View v) { pickVizColor(2); } }));
        if (z.stops == 3) cs.add(chip("Color 3…", false, new View.OnClickListener() { @Override public void onClick(View v) { pickVizColor(3); } }));
        cs.add(vchip("2 colors", z.stops == 2, new Runnable() { @Override public void run() { selViz().viz.stops = 2; } }));
        cs.add(vchip("3 colors", z.stops == 3, new Runnable() { @Override public void run() { selViz().viz.stops = 3; } }));
        panel.addView(Ui.strip(act, cs.toArray(new View[0])));
        View[] cm = new View[Visuals.COLOR_MODES.length];
        for (int i = 0; i < cm.length; i++) {
            final int q = i;
            cm[i] = vchip(Visuals.COLOR_MODES[i], z.colorMode == i, new Runnable() { @Override public void run() { selViz().viz.colorMode = q; } });
        }
        panel.addView(Ui.label(act, "Color by"));
        panel.addView(Ui.strip(act, cm));
        if (z.colorMode == 3)
            panel.addView(slider("Rainbow turn", -360, 360, 30, z.hueSpeed, 1, 0, "°/s", new FSet() { @Override public void set(float v) { selViz().viz.hueSpeed = v; } }));
        panel.addView(slider("Glow", 0, 1, 0.5f, z.glow, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.glow = v; } }));

        // ---- response
        section("Response");
        panel.addView(Ui.strip(act,
                vchip("Auto level", z.auto, new Runnable() { @Override public void run() { selViz().viz.auto = true; } }),
                vchip("Fixed level", !z.auto, new Runnable() { @Override public void run() { selViz().viz.auto = false; } })));
        panel.addView(slider("Sensitivity", 0.3f, 3, 1, z.sens, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.sens = v; } }));
        panel.addView(slider("Smoothing (time)", 0.03f, 0.6f, 0.15f, z.release, 1000, 0, " ms", new FSet() { @Override public void set(float v) { selViz().viz.release = v; } }));
        if (Visuals.usesBars(ty)) {
            int ri = Visuals.rangeIndex(z);
            View[] rg = new View[Visuals.RANGES.length];
            for (int i = 0; i < rg.length; i++) {
                final int q = i;
                rg[i] = vchip(Visuals.RANGES[i], ri == i, new Runnable() { @Override public void run() { Visuals.setRange(selViz().viz, q); } });
            }
            panel.addView(Ui.label(act, String.format(Locale.US, "Frequencies  %s – %s", hz(Visuals.hzOfBand(z.lo)), hz(Visuals.hzOfBand(z.hi + 1)))));
            panel.addView(Ui.strip(act, rg));
            panel.addView(slider("Lowest", 0, 62, 0, z.lo, 1, 0, " band", new FSet() { @Override public void set(float v) { VProject.Viz k = selViz().viz; k.lo = Math.round(v); if (k.hi <= k.lo) k.hi = Math.min(63, k.lo + 1); } }));
            panel.addView(slider("Highest", 1, 63, 63, z.hi, 1, 0, " band", new FSet() { @Override public void set(float v) { VProject.Viz k = selViz().viz; k.hi = Math.round(v); if (k.lo >= k.hi) k.lo = Math.max(0, k.hi - 1); } }));
            panel.addView(slider("Smooth across", 0, 1, 0, z.smooth, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.smooth = v; } }));
        } else if (ty == VProject.Viz.WAVE || ty == VProject.Viz.RING)
            panel.addView(slider("Window", 0.01f, 0.2f, 0.05f, z.span, 1000, 0, " ms", new FSet() { @Override public void set(float v) { selViz().viz.span = v; } }));
        panel.addView(slider("Bounce with the bass", 0, 2, 0, z.bounce, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.bounce = v; } }));
        panel.addView(hint(z.auto ? "Auto level: quiet parts are turned up so it keeps moving (like cava's autosens)."
                : "Fixed level: it follows the song's real loudness, so quiet parts stay small."));

        // ---- shape, per style
        section("Shape");
        boolean barsLike = ty == VProject.Viz.BARS || ty == VProject.Viz.MIRROR || ty == VProject.Viz.RADIAL || ty == VProject.Viz.LED;
        if (Visuals.usesBars(ty))
            panel.addView(slider(barsLike ? "Bars" : "Detail", 4, 64, 32, z.bars, 1, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.bars = Math.round(v); } }));
        if (barsLike) panel.addView(slider("Gap", 0, 0.9f, 0.3f, z.gap, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.gap = v; } }));
        if (ty == VProject.Viz.BARS || ty == VProject.Viz.MIRROR || ty == VProject.Viz.LED)
            panel.addView(slider("Roundness", 0, 1, 0.5f, z.round, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.round = v; } }));
        if (ty == VProject.Viz.LED)
            panel.addView(slider("Cells", 3, 40, 16, z.segs, 1, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.segs = Math.round(v); } }));
        ArrayList<View> opt = new ArrayList<View>();
        if (ty == VProject.Viz.BARS || ty == VProject.Viz.LED)
            opt.add(vchip("Peak caps", z.caps, new Runnable() { @Override public void run() { selViz().viz.caps = !selViz().viz.caps; } }));
        if (Visuals.horizontal(ty) && ty != VProject.Viz.RIDGES)
            opt.add(vchip("Lows in the middle", z.mirrorX, new Runnable() { @Override public void run() { selViz().viz.mirrorX = !selViz().viz.mirrorX; } }));
        if (Visuals.horizontal(ty) && ty != VProject.Viz.MIRROR && ty != VProject.Viz.RIDGES)
            opt.add(vchip("From the top", z.flip, new Runnable() { @Override public void run() { selViz().viz.flip = !selViz().viz.flip; } }));
        if (!opt.isEmpty()) panel.addView(Ui.strip(act, opt.toArray(new View[0])));
        if (ty == VProject.Viz.WAVE || ty == VProject.Viz.RING || ty == VProject.Viz.MOUNTAIN || ty == VProject.Viz.CIRCLE || ty == VProject.Viz.RIDGES)
            panel.addView(slider("Line", 0.001f, 0.03f, 0.006f, z.thick, 1000, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.thick = v; } }));
        if (ty == VProject.Viz.RADIAL || ty == VProject.Viz.RING || ty == VProject.Viz.CIRCLE || ty == VProject.Viz.SHOCK)
            panel.addView(slider(ty == VProject.Viz.SHOCK ? "Centre size" : "Inner radius", 0, 0.85f, 0.35f, z.inner, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.inner = v; } }));
        if (ty == VProject.Viz.MOUNTAIN || ty == VProject.Viz.CIRCLE || ty == VProject.Viz.RIDGES)
            panel.addView(slider("Peak height", 0.1f, 2, 1, z.amp, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.amp = v; } }));
        if (ty == VProject.Viz.MOUNTAIN || ty == VProject.Viz.CIRCLE || ty == VProject.Viz.RIDGES || ty == VProject.Viz.SHOCK)
            panel.addView(slider(ty == VProject.Viz.RIDGES ? "Hide lines behind" : ty == VProject.Viz.SHOCK ? "Centre" : "Fill", 0, 1, 0.6f, z.fill, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().viz.fill = v; } }));
        if (ty == VProject.Viz.RIDGES)
            panel.addView(slider("Lines", 4, 32, 24, z.ridges, 1, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.ridges = Math.round(v); } }));
        if (Visuals.usesHistory(ty) || ty == VProject.Viz.SHOCK)
            panel.addView(slider("Seconds shown", 0.3f, 8, 2, z.hist, 1, 1, " s", new FSet() { @Override public void set(float v) { selViz().viz.hist = v; } }));
        section("Echoes");echoRow(z);
        section("Picture");
        panel.addView(Ui.label(act, "Box"));
        panel.addView(slider("Width", 0.05f, 1.5f, 0.9f, l.srcW / (float) proj.outW(), 100, 0, "%", new FSet() {
            @Override public void set(float v) { selViz().layer.srcW = Math.max(8, Math.round(v * proj.outW())); }
        }));
        panel.addView(slider("Height", 0.05f, 1.5f, 0.3f, l.srcH / (float) proj.outH(), 100, 0, "%", new FSet() {
            @Override public void set(float v) { selViz().layer.srcH = Math.max(8, Math.round(v * proj.outH())); }
        }));
        panel.addView(Ui.strip(act, blendChip(l.mode, new ModeSet() {
            @Override public void set(int mode) { if (selViz() != null) selViz().layer.mode = mode; }
        })));
        panel.addView(sliderXf("Opacity", 0, 1, 1, l.opacity, 100, 0, "%", new FSet() { @Override public void set(float v) { selViz().layer.opacity = v; } }));
        section("Timing");
        panel.addView(Ui.strip(act,
                linkChip(),
                chip("Fit to song", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        VProject.AudioItem m = proj.syncItem();
                        if (m == null) { toast("Turn on Music sync first: Fit to song uses the sync track."); return; }
                        beginEdit();
                        VProject.TextItem t = selViz();
                        t.keys.shift(t.start - m.start);
                        t.start = m.start;
                        t.dur = Math.max(200000, m.dur());
                        changed();
                        rebuildPanel();
                    }
                }),
                chip("Center", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { beginEdit(); Layer k = selViz().layer; k.cx = proj.outW() / 2f; k.cy = proj.outH() / 2f; k.rot = 0; changedXf(); }
                })));
        section("Motion");
        keyRow(false);
        reactRow(false);
        section("Timing");
        panel.addView(Ui.label(act, "Timing"));
        panel.addView(slider("Duration", 0.2f, Math.max(60, proj.duration() / 1e6f), 10, ti.dur / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selViz().dur = (long) (v * 1e6); } }));
        panel.addView(slider("Fade in", 0, 3, 0.3f, ti.fadeIn / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selViz().fadeIn = (long) (v * 1e6); selViz().fadeOffset = 0; selViz().fadeSpan = -1; } }));
        panel.addView(slider("Fade out", 0, 3, 0.3f, ti.fadeOut / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selViz().fadeOut = (long) (v * 1e6); selViz().fadeOffset = 0; selViz().fadeSpan = -1; } }));
        panel.addView(hint("On the preview: drag to place it, two fingers to scale and rotate. Screen or Add blend makes it glow over footage."));
    }

    /** Echoes: copies of the last moments, zoomed, turned and faded (Tunnel, Spiral...). */
    private void echoRow(final VProject.Viz z) {
        panel.addView(Ui.label(act, "Echoes"));
        int cur = z.trailStyle();
        View[] st = new View[VProject.Viz.TRAILS.length];
        for (int i = 0; i < st.length; i++) {
            final int q = i;
            st[i] = chip(VProject.Viz.TRAILS[i], cur == i, new View.OnClickListener() {
                @Override public void onClick(View v) { beginEdit(); selViz().viz.setTrail(q); changed(); rebuildPanel(); }
            });
        }
        panel.addView(Ui.strip(act, st));
        if (z.trailLen <= 0) {
            panel.addView(hint("Leave copies of the last moments behind it: a tunnel, a spiral, ghosts. Each echo is the visualizer as it was a moment ago, so it follows the music too."));
            return;
        }
        panel.addView(slider("Span", 0.05f, 2f, 0.5f, z.trailLen, 1000, 0, " ms", new FSet() { @Override public void set(float v) { selViz().viz.trailLen = v; } }));
        panel.addView(slider("Echoes", 2, 16, 8, z.echoes, 1, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.echoes = Math.round(v); } }));
        panel.addView(slider("Zoom", -2f, 2f, 0, z.tZoom, 100, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.tZoom = v; } }));
        panel.addView(slider("Spin", -360, 360, 0, z.tSpin, 1, 0, "\u00b0/s", new FSet() { @Override public void set(float v) { selViz().viz.tSpin = v; } }));
        panel.addView(slider("Bass push", -3f, 3f, 0, z.tPush, 100, 0, "", new FSet() { @Override public void set(float v) { selViz().viz.tPush = v; } }));
        panel.addView(slider("Color drift", -180, 180, 0, z.tHue, 1, 0, "\u00b0/s", new FSet() { @Override public void set(float v) { selViz().viz.tHue = v; } }));
        panel.addView(Ui.strip(act,
                chip("Stack", !z.echoGlow, new View.OnClickListener() { @Override public void onClick(View v) { beginEdit(); selViz().viz.echoGlow = false; changed(); rebuildPanel(); } }),
                chip("Glow", z.echoGlow, new View.OnClickListener() { @Override public void onClick(View v) { beginEdit(); selViz().viz.echoGlow = true; changed(); rebuildPanel(); } })));
        panel.addView(hint("Echoes stay inside the box: make it bigger to give a tunnel room. Bass push needs Music sync on."));
    }

    private static String hz(float f) { return f >= 1000 ? String.format(Locale.US, "%.1f kHz", f / 1000) : Math.round(f) + " Hz"; }

    private void pickVizColor(final int which) {
        VProject.TextItem t = selViz();
        if (t == null) return;
        String title = which == 1 ? "Color 1 (low / bottom)" : which == 2 ? (t.viz.stops == 3 ? "Color 2 (middle)" : "Color 2 (high / top)") : "Color 3 (high / top)";
        int cur = which == 1 ? t.viz.c1 : which == 2 ? t.viz.c2 : t.viz.c3;
        Pickers.color(act, title, cur, new Pickers.ColorCb() {
            @Override public void begin() { beginEdit(); }
            @Override public void color(int c) {
                VProject.TextItem k = selViz();
                if (k == null) return;
                if (which == 1) k.viz.c1 = c | 0xff000000; else if (which == 2) k.viz.c2 = c | 0xff000000; else k.viz.c3 = c | 0xff000000;
                changed();
            }
            @Override public void done() { rebuildPanel(); }
        });
    }

    private void addViz(int type) {
        beginEdit();
        VProject.TextItem t = new VProject.TextItem();
        t.id = proj.nextId++;
        t.viz = new VProject.Viz();
        t.viz.type = type;
        int W = proj.outW(), H = proj.outH();
        Layer l = new Layer();
        l.id = t.id;
        l.type = Layer.IMAGE;
        l.name = "Visualizer";
        l.scale = 1;
        l.cx = W / 2f;
        if (Visuals.round(type)) {
            int sq = Math.round(Math.min(W, H) * 0.7f);
            l.srcW = sq;
            l.srcH = sq;
            l.cy = H / 2f;
        } else {
            l.srcW = Math.round(W * 0.9f);
            l.srcH = Math.round(H * (type == VProject.Viz.RIDGES ? 0.6f : type == 0 || type == VProject.Viz.LED || type == VProject.Viz.MOUNTAIN ? 0.28f : 0.36f));
            boolean bottom = type == 0 || type == VProject.Viz.LED || type == VProject.Viz.MOUNTAIN;
            l.cy = bottom ? H - l.srcH / 2f - H * 0.06f : H / 2f;
        }
        t.layer = l;
        t.fadeIn = t.fadeOut = 300000;
        long end = proj.duration();
        VProject.AudioItem m = proj.syncItem();
        if (m != null) {
            boolean inSong = time >= m.start && time < m.start + m.dur() - 500000;
            t.start = inSong ? time : m.start;
            end = m.start + m.dur();
        } else t.start = Math.min(time, Math.max(0, end - 500000));
        t.dur = Math.max(1000000, end - t.start);
        if (end <= t.start) t.dur = 10000000;
        proj.texts.add(t);
        select(TimelineView.SEL_VIZ, t.id, false);
        changed();
    }

    // ================================================================ Audio tab
    private void audioPanel() {
        VProject.Clip footage=selClip()!=null?selClip():selMedia()!=null?selMedia().media:null;
        if(footage!=null){
            section("Selected sound");
            panel.addView(Ui.label(act,footage.name));
            if(!footage.hasAudio)panel.addView(hint("This footage has no audio track."));
            else if(selMedia()!=null){
                final VProject.TextItem selected=selMedia();
                View[] options=new View[3];String[] labels={"Keep main sound","Use B-roll sound","Mix both"};
                for(int i=0;i<3;i++){final int mode=i;options[i]=chip(labels[i],selected.audioMode==i,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();selected.audioMode=mode;selected.media.mute=mode==0;changed();syncPlayers();rebuildPanel();}});}
                panel.addView(Ui.strip(act,options));
                if(!footage.mute)panel.addView(slider("B-roll volume",0,2,1,footage.volume,100,0,"%",new FSet(){@Override public void set(float value){selMedia().media.volume=value;}}));
            }else{
                panel.addView(slider("Clip volume",0,2,1,footage.volume,100,0,"%",new FSet(){@Override public void set(float value){selClip().volume=value;}}));
                panel.addView(Ui.strip(act,chip(footage.mute?"Unmute clip":"Mute clip",footage.mute,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();selClip().mute=!selClip().mute;changed();syncPlayers();rebuildPanel();}})));
                if(footage.curve!=null)panel.addView(hint("A speed ramp mutes this clip's original audio. Use a separate audio track for its soundtrack."));
            }
            if(footage.hasAudio){section("Equalizer");eqSection(EQ_FOOTAGE);}
        }
        section("Beat guide");
        if (!proj.audio.isEmpty()) {
            panel.addView(Ui.label(act, "Beat guide / snapping"));
            panel.addView(syncChips());
            float[] bs = proj.syncBeats();
            if (proj.syncItem() == null)
                panel.addView(hint("Optional timeline beat guide. Each effect chooses its own audio source; changing this guide does not change independent effect sources."));
            else {
                panel.addView(Ui.strip(act, chip(bs.length > 0 ? "Re-detect beats" : "Detect beats", false,
                        new View.OnClickListener() { @Override public void onClick(View v) { detectBeats(true); } })));
                panel.addView(hint(bs.length > 0
                        ? String.format(Locale.US, "%.1f BPM \u00b7 %d beats. Amber ticks on the timeline; trims and moves snap to them.", proj.bpm, bs.length)
                        : "Beat ticks and visualizers follow this guide. Effects can choose their own source."));
            }
        }
        final VProject.AudioItem a = selAudio();
        if (a == null) {
            VProject.Clip c = selClip();
            panel.addView(hint(c != null && c.hasAudio
                    ? "Select a separate audio track in the timeline to edit its volume and keys."
                    : "Add a song to cut to. Drag it on the timeline to line it up."));
            section("Master EQ");eqSection(EQ_MASTER);
            return;
        }
        section("Volume");
        panel.addView(Ui.label(act, "♪ " + a.name));
        final long lt = time - a.start;
        final boolean keyed = !a.vol.isEmpty();
        float shown = VProject.musicVol(a, time);
        panel.addView(slider(keyed ? "Volume at playhead" : "Volume", 0, 2, 1, shown, 100, 0, "%", new FSet() {
            @Override public void set(float v) {
                VProject.AudioItem s = selAudio();
                if (s.vol.isEmpty()) s.volume = v;
                else if(keyTimeValid())s.vol.set(time - s.start, new float[]{v}, frameTol());   // animated: edits record a key here
            }
        }));
        section("Keys");volKeyRow(a, lt);
        section("Equalizer");eqSection(EQ_AUDIO);
        section("Timing");
        panel.addView(slider("Fade in", 0, 10, 0, a.fadeIn / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selAudio().fadeIn = (long) (v * 1e6); selAudio().fadeOffset = 0; selAudio().fadeSpan = -1; } }));
        panel.addView(slider("Fade out", 0, 10, 0, a.fadeOut / 1e6f, 1, 1, " s", new FSet() { @Override public void set(float v) { selAudio().fadeOut = (long) (v * 1e6); selAudio().fadeOffset = 0; selAudio().fadeSpan = -1; } }));
        panel.addView(Ui.strip(act,
                chip("Place at playhead",false,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();selAudio().start=time;changed();syncPlayers();rebuildPanel();}}),
                chip("Place at 0",false,new View.OnClickListener(){@Override public void onClick(View v){beginEdit();selAudio().start=0;changed();syncPlayers();rebuildPanel();}}),linkChip()));
        panel.addView(nudgeRow(true));
        panel.addView(hint("Enable Move under More to reposition the item; drag its edges to trim. The yellow line on the timeline is its level (keys, ducking and fades together)."));
        section("Ducking");duckRow();
        section("Master EQ");eqSection(EQ_MASTER);
    }

    // ================================================================ 3.15 equalizer
    static final int EQ_FOOTAGE = 0, EQ_AUDIO = 1, EQ_MASTER = 2;
    private final int[] eqBand = {2, 2, 2};

    /** The live EQ of a target (null until it's first changed). */
    private AudioEq eqOf(int target) {
        if (target == EQ_MASTER) return proj.masterEq;
        if (target == EQ_AUDIO) { VProject.AudioItem a = selAudio(); return a == null ? null : a.eq; }
        VProject.Clip f = selClip() != null ? selClip() : selMedia() != null ? selMedia().media : null;
        return f == null ? null : f.eq;
    }

    /** The target's EQ, created flat if it has none (call inside an edit). */
    private AudioEq eqMake(int target) {
        AudioEq e = eqOf(target);
        if (e != null) return e;
        e = new AudioEq();
        if (target == EQ_MASTER) proj.masterEq = e;
        else if (target == EQ_AUDIO) { VProject.AudioItem a = selAudio(); if (a == null) return null; a.eq = e; }
        else { VProject.Clip f = selClip() != null ? selClip() : selMedia() != null ? selMedia().media : null; if (f == null) return null; f.eq = e; }
        return e;
    }

    private final AudioEq flatEq = new AudioEq();

    private interface EqSet { void set(AudioEq e, AudioEq.Band b, float v); }

    private Ui.Slider eqSlider(final int target, String name, float min, float max, float def, float val, final EqSet s) {
        return new Ui.Slider(act, name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { beginEdit(); eqMake(target); }
            @Override public void change(float v) {
                AudioEq e = eqMake(target);
                if (e == null) return;
                int bi = Math.min(eqBand[target], e.bands.length - 1);
                s.set(e, e.bands[bi], v);
                eqChanged();
            }
        });
    }

    /** An EQ edit: the preview players pick it up on the next sync (no panel rebuild while dragging). */
    private void eqChanged() {
        changed();
        syncPlayers();
        if (eqViewLive != null) eqViewLive.invalidate();
    }

    private EqView eqViewLive;

    private void eqSection(final int target) {
        final AudioEq cur = eqOf(target);
        final AudioEq e = cur != null ? cur : flatEq;
        int pi = e.presetIndex();
        View[] pr = new View[AudioEq.PRESETS.length];
        for (int i = 0; i < pr.length; i++) {
            final int q = i;
            pr[i] = chip(AudioEq.PRESETS[i], cur != null ? pi == i : i == 0, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    beginEdit();
                    AudioEq n = AudioEq.preset(q);
                    if (target == EQ_MASTER) proj.masterEq = q == 0 ? null : n;
                    else if (target == EQ_AUDIO) { VProject.AudioItem a = selAudio(); if (a != null) a.eq = q == 0 ? null : n; }
                    else { VProject.Clip f = selClip() != null ? selClip() : selMedia() != null ? selMedia().media : null; if (f != null) f.eq = q == 0 ? null : n; }
                    eqChanged();
                    rebuildPanel();
                }
            });
        }
        panel.addView(Ui.strip(act, pr));
        final EqView ev = new EqView(act, new EqView.Host() {
            @Override public AudioEq eq() { AudioEq x = eqOf(target); return x != null ? x : flatEq; }
            @Override public int selected() { return eqBand[target]; }
            @Override public void select(int b) { eqBand[target] = b; h.post(new Runnable() { @Override public void run() { rebuildPanel(); } }); }
            @Override public void beginEdit() { VideoEditor.this.beginEdit(); eqMake(target); }
            @Override public void changed() { eqChanged(); }
            @Override public void done() { h.post(new Runnable() { @Override public void run() { rebuildPanel(); } }); }
        });
        eqViewLive = ev;
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        panel.addView(ev, elp);
        // bands
        View[] bv = new View[e.bands.length];
        for (int i = 0; i < bv.length; i++) {
            final int q = i;
            AudioEq.Band b = e.bands[i];
            TextView t = chip((i + 1) + " · " + AudioEq.TYPES[b.type] + (b.on ? "" : " (off)"), eqBand[target] == i, new View.OnClickListener() {
                @Override public void onClick(View v) { eqBand[target] = q; rebuildPanel(); }
            });
            t.setTextColor(b.on ? EqView.COLORS[i % EqView.COLORS.length] : Ui.DIM);
            bv[i] = t;
        }
        panel.addView(Ui.strip(act, bv));
        final int bi = Math.min(eqBand[target], e.bands.length - 1);
        final AudioEq.Band b = e.bands[bi];
        ArrayList<View> row = new ArrayList<View>();
        row.add(chip(b.on ? "Band on" : "Band off", b.on, new View.OnClickListener() {
            @Override public void onClick(View v) { beginEdit(); AudioEq x = eqMake(target); x.bands[bi].on = !x.bands[bi].on; eqChanged(); rebuildPanel(); }
        }));
        for (int ty = 0; ty < AudioEq.TYPES.length; ty++) {
            final int q = ty;
            row.add(chip(AudioEq.TYPES[ty], b.type == ty, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    beginEdit(); AudioEq x = eqMake(target); AudioEq.Band k = x.bands[bi];
                    k.type = q; k.on = true;
                    if (q == AudioEq.NOTCH) k.q = Math.max(k.q, 4);
                    if (q == AudioEq.LOW_SHELF || q == AudioEq.HIGH_SHELF) k.q = Math.min(k.q, 1);
                    eqChanged(); rebuildPanel();
                }
            }));
        }
        panel.addView(Ui.strip(act, row.toArray(new View[0])));
        Ui.Slider fq = eqSlider(target, "Frequency", 0, 1, (float) (Math.log(1000 / 20.0) / Math.log(1000)), (float) (Math.log(b.freq / 20.0) / Math.log(1000)), new EqSet() {
            @Override public void set(AudioEq x, AudioEq.Band k, float v) { k.freq = (float) (20 * Math.pow(1000, v)); }
        });
        fq.map(new Ui.Slider.Map() {
            @Override public float shown(float v) { return (float) (20 * Math.pow(1000, v)); }
            @Override public float value(float hz) { return (float) (Math.log(Math.max(20, Math.min(20000, hz)) / 20.0) / Math.log(1000)); }
        }, 0, " Hz");
        panel.addView(fq);
        if (b.hasGain()) {
            Ui.Slider g = eqSlider(target, "Gain", -AudioEq.G_MAX, AudioEq.G_MAX, 0, b.gain, new EqSet() {
                @Override public void set(AudioEq x, AudioEq.Band k, float v) { k.gain = v; }
            });
            g.display(1, 1, " dB");
            panel.addView(g);
        }
        if (b.hasQ()) {
            boolean shelf = b.type == AudioEq.LOW_SHELF || b.type == AudioEq.HIGH_SHELF;
            Ui.Slider qs = eqSlider(target, shelf ? "Slope" : "Width (Q)", shelf ? 0.1f : 0.2f, shelf ? 1f : 16f, shelf ? 0.7f : 1f, b.q, new EqSet() {
                @Override public void set(AudioEq x, AudioEq.Band k, float v) { k.q = v; }
            });
            qs.display(1, 2, "");
            panel.addView(qs);
        }
        ArrayList<View> tail = new ArrayList<View>();
        if (b.type == AudioEq.LOW_CUT || b.type == AudioEq.HIGH_CUT)
            tail.add(chip(b.steep ? "24 dB/oct" : "12 dB/oct", b.steep, new View.OnClickListener() {
                @Override public void onClick(View v) { beginEdit(); AudioEq x = eqMake(target); x.bands[bi].steep = !x.bands[bi].steep; eqChanged(); rebuildPanel(); }
            }));
        tail.add(chip(e.on ? "EQ on" : "EQ bypassed", e.on, new View.OnClickListener() {
            @Override public void onClick(View v) { beginEdit(); AudioEq x = eqMake(target); x.on = !x.on; eqChanged(); rebuildPanel(); }
        }));
        tail.add(chip("Flat", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eqOf(target) == null) return;
                beginEdit();
                if (target == EQ_MASTER) proj.masterEq = null;
                else if (target == EQ_AUDIO) { VProject.AudioItem a = selAudio(); if (a != null) a.eq = null; }
                else { VProject.Clip f = selClip() != null ? selClip() : selMedia() != null ? selMedia().media : null; if (f != null) f.eq = null; }
                eqChanged(); rebuildPanel();
            }
        }));
        panel.addView(Ui.strip(act, tail.toArray(new View[0])));
        panel.addView(hint(target == EQ_MASTER
                ? "Shapes the whole mix: footage, B-roll and every track, after their own EQs. Drag the dots; double-tap one to switch it off."
                : "Drag a dot: sideways sets the frequency, up and down the boost or cut. Double-tap a dot to switch that band off. The export runs this exact EQ; the preview uses the phone's equalizer, which is very close."));
    }


    // ---------------------------------------------------------------- audio: keys and ducking
    private boolean keyTimeValid() {
        if(time>=selectedStart()&&time<selectedEnd())return true;
        toast("Move the playhead inside the selected item before adding a key.");return false;
    }

    private void volKeyRow(final VProject.AudioItem a, final long lt) {
        final long tol = frameTol();
        final int at = a.vol.near(lt, tol);
        panel.addView(Ui.strip(act,
                chip("\u25c0", false, new View.OnClickListener() { @Override public void onClick(View v) { long previous=a.vol.prev(time-a.start,tol);if (previous != Long.MIN_VALUE && previous >= 0) scrubTo(a.start + previous); } }),
                chip(at >= 0 ? "\u25c6 On key" : "\u25c7 Add volume key", at >= 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (a.vol.near(time-a.start,tol) >= 0 || !keyTimeValid()) return;
                        pause();
                        VProject.AudioItem s = selAudio();
                        beginEdit();
                        s.vol.set(time - s.start, new float[]{VProject.musicVol(s, time)}, tol);
                        changed(); syncPlayers(); rebuildPanel();
                    }
                }),
                chip("\u25b6", false, new View.OnClickListener() { @Override public void onClick(View v) { long next=a.vol.next(time-a.start,tol);if (next != Long.MAX_VALUE) scrubTo(a.start + next); } }),
                chip("Remove key", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { int current=a.vol.near(time-a.start,tol);if (current < 0) return;pause();beginEdit();a.vol.remove(current); changed(); syncPlayers(); rebuildPanel(); }
                }),
                chip("Clear keys", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { if (selAudio().vol.isEmpty()) return; beginEdit();VProject.AudioItem item=selAudio();item.volume=VProject.musicVol(item,time);item.vol.list.clear();changed();syncPlayers();rebuildPanel(); }
                })));
        if (a.vol.isEmpty())
            panel.addView(hint("Volume keys: add one, move the playhead, change the volume: the level ramps between them (e.g. bring the song down under a talking part, back up after)."));
    }

    private void duckRow() {
        panel.addView(Ui.label(act, "Lower music under clip sound"));
        View[] d = new View[Duck.PRESETS_DB.length];
        for (int i = 0; i < d.length; i++) {
            final float db = Duck.PRESETS_DB[i];
            String name = Duck.PRESET_NAMES[i] + (db > 0 ? " (\u2212" + Math.round(db) + " dB)" : "");
            d[i] = chip(name, Math.abs(proj.duckDb - db) < 0.5f, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    beginEdit();
                    proj.duckDb = db;
                    if (db > 0) requestSidechainPeaks();
                    duckVer = -1;
                    changed(); syncPlayers(); rebuildPanel();
                }
            });
        }
        panel.addView(Ui.strip(act, d));
        panel.addView(hint("Ducking dips every music item while clips or overlays with sound are playing (it starts a moment early so first words are clear, and holds through short pauses). Export measures the exact mix; the preview follows the waveforms."));
    }

    private void requestSidechainPeaks() {
        for (VProject.Clip c : proj.clips) if (c.kind == VProject.VIDEO && c.hasAudio && !c.mute) requestPeaks(c.uri);
        for (VProject.TextItem t : proj.texts) if (t.media != null && t.media.kind == VProject.VIDEO && t.media.hasAudio && !t.media.mute) requestPeaks(t.media.uri);
    }

    private volatile int duckVer = -1;
    private float[] duckCurve;
    private int duckBuiltFor = -2;

    /**
     * Preview ducking curve from the clip/overlay waveforms (100 per second, the same peak
     * measure the export uses on its exact mix). Rebuilt when the project or a waveform changes.
     */
    float[] previewDuck() {
        if (proj.duckDb <= 0) return null;
        if (duckVer == -1) { duckVer = ++duckSeq; }
        if (duckBuiltFor == duckVer && duckCurve != null) return duckCurve;
        duckBuiltFor = duckVer;
        long dur = proj.duration();
        float[] side = new float[(int) (dur / 10000) + 2];
        long[] st = proj.starts();
        for (int i = 0; i < proj.clips.size(); i++) {
            VProject.Clip c = proj.clips.get(i);
            if (c.kind != VProject.VIDEO || !c.hasAudio || c.mute || c.volume <= 0 || c.curve != null) continue;
            addSide(side, peaks.get(c.uri), st[i], c.dur(), c.in, c.speed, c.volume, null, true);
        }
        for (VProject.TextItem t : proj.texts) {
            VProject.Clip m = t.media;
            if (m == null || m.kind != VProject.VIDEO || !m.hasAudio || m.mute || m.volume <= 0) continue;
            if(t.layer.visible)addSide(side, peaks.get(m.uri), t.start, t.dur, m.in, m.speed, m.volume, t, false);
        }
        duckCurve = Duck.gains(side, proj.duckDb);
        return duckCurve;
    }
    private int duckSeq;

    private void addSide(float[] side, float[] pk, long start, long dur, long in, float speed, float gain, VProject.TextItem item, boolean main) {
        if (pk == null) return;
        int a = (int) Math.max(0, start / 10000), b = (int) Math.min(side.length - 1, (start + dur) / 10000);
        for (int k = a; k <= b; k++) {
            long src = in + (long) ((k * 10000L - start) * speed);
            int j = (int) (src / 10000);
            if (j >= 0 && j < pk.length) side[k] = Math.max(side[k], pk[j] * gain * (main?proj.mainSoundGain(k*10000L):VProject.overlaySoundFade(item,k*10000L)));
        }
    }

    // ================================================================ captions
    static final int CAP_BOX = 0x99000000;

    void captionSheet() {
        ArrayList<VProject.TextItem> caps = captions();
        Sheet sh = new Sheet(act, caps.isEmpty() ? "Captions" : "Captions \u00b7 " + caps.size());
        sh.item("folder", "Import SRT file\u2026", "Each line becomes a caption you can edit", new Runnable() {
            @Override public void run() { pickMedia(REQ_SRT, new String[]{"application/x-subrip", "text/plain", "text/*", "application/octet-stream"}); }
        });
        sh.item("plus", "Add caption at playhead", new Runnable() { @Override public void run() { addCaption(); } });
        if (!caps.isEmpty()) {
            sh.item("export", "Export SRT", "Saves to Download/Independent Video, for uploading next to the video", new Runnable() { @Override public void run() { exportSrt(); } });
            sh.danger("trash", "Delete all captions", new Runnable() {
                @Override public void run() {
                    beginEdit();
                    proj.texts.removeAll(captions());
                    select(TimelineView.SEL_NONE, -1, false);
                    changed();
                }
            });
        }
        sh.show();
    }

    private ArrayList<VProject.TextItem> captions() {
        ArrayList<VProject.TextItem> out = new ArrayList<VProject.TextItem>();
        for (VProject.TextItem t : proj.texts) if (t.caption) out.add(t);
        java.util.Collections.sort(out, new java.util.Comparator<VProject.TextItem>() {
            @Override public int compare(VProject.TextItem a, VProject.TextItem b) { return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0); }
        });
        return out;
    }

    /** Where the bottom edge of captions sits: low on landscape/square, higher on tall formats (app UI covers the bottom). */
    private float captionBottom() { return proj.outH() > proj.outW() ? 0.80f : 0.92f; }

    /** A new caption layer: the existing captions' style if there are any, else readable defaults. */
    private Layer captionLayer(int id, String text) {
        VProject.TextItem like = null;
        for (VProject.TextItem t : proj.texts) if (t.caption) { like = t; break; }
        Layer l;
        float bottom;
        int W = proj.outW(), H = proj.outH();
        if (like != null) {
            l = like.layer.copy();
            bottom = (like.layer.cy + like.layer.srcH * like.layer.scale / 2) / H;
        } else {
            l = new Layer();
            l.type = Layer.TEXT;
            l.font = "sans-serif-medium";
            l.fillColor = 0xffffffff;
            l.textBg = CAP_BOX;
            l.textSize = Math.round(Math.min(W, H) * 0.052f);
            l.leading = 1.05f;
            l.align = 1;
            l.scale = 1;
            l.wrapW = Math.round(W * 0.84f);
            bottom = captionBottom();
        }
        l.id = id;
        l.name = "Caption";
        l.text = text;
        l.visible = true;
        l.opacity = 1;
        l.rot = 0;
        TextRender.refreshPreview(l);
        l.cx = W / 2f;
        l.cy = bottom * H - l.srcH * l.scale / 2;
        return l;
    }

    private VProject.TextItem newCaption(long start, long dur, String text) {
        VProject.TextItem t = new VProject.TextItem();
        t.id = proj.nextId++;
        t.caption = true;
        t.start = Math.max(0, start);
        t.dur = Math.max(200000, dur);
        t.fadeIn = t.fadeOut = 0;   // captions cut, they don't fade
        t.layer = captionLayer(t.id, text);
        return t;
    }

    private void addCaption() {
        Pickers.text(act, "New caption", "", true, new Pickers.TextCb() {
            @Override public void text(String s) {
                if (s.trim().isEmpty()) return;
                beginEdit();
                VProject.TextItem t = newCaption(time, 2500000, s.trim());
                proj.texts.add(t);
                select(TimelineView.SEL_TEXT, t.id, false);
                changed();
            }
        });
    }

    /** Copies the caption's look (font, size, colours, box, wrap, height on screen) to every caption. */
    private void styleAllCaptions(VProject.TextItem from) {
        if (from == null) return;
        beginEdit();
        Layer s = from.layer;
        int H = proj.outH();
        float bottom = (s.cy + s.srcH * s.scale / 2) / H;
        int n = 0;
        for (VProject.TextItem t : proj.texts) {
            if (!t.caption || t == from) continue;
            Layer l = t.layer;
            l.font = s.font; l.bold = s.bold; l.italic = s.italic; l.textSize = s.textSize; l.tracking = s.tracking;
            l.leading = s.leading; l.align = s.align; l.fillColor = s.fillColor; l.textBg = s.textBg; l.wrapW = s.wrapW;
            l.scale = s.scale; l.rot = s.rot; l.opacity = s.opacity; l.mode = s.mode;
            TextRender.refreshPreview(l);
            l.cx = s.cx;
            l.cy = bottom * H - l.srcH * l.scale / 2;   // same baseline height, whatever the line count
            n++;
        }
        changed();
        toast("Styled " + n + " other caption" + (n == 1 ? "" : "s") + ".");
    }

    private void importSrt(final Uri u) {
        showStatus("Reading captions\u2026");
        bg.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                java.util.List<Srt.Cue> cues = null;
                try {
                    java.io.InputStream in = act.getContentResolver().openInputStream(u);
                    if (in == null) throw new Exception("can't open the file");
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    try {
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > 8 << 20) throw new Exception("file too large for captions"); }
                    } finally { in.close(); }
                    cues = Srt.parse(decodeText(bo.toByteArray()));
                    if (cues.isEmpty()) err = "no captions found (is it an .srt file?)";
                } catch (Exception e) { err = e.getMessage(); }
                final String er = err;
                final java.util.List<Srt.Cue> got = cues;
                h.post(new Runnable() {
                    @Override public void run() {
                        showStatus(null);
                        if (er != null) { toast("Couldn't import captions: " + er); return; }
                        if (captions().isEmpty()) { addCues(got, false); return; }
                        new android.app.AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("This video already has captions")
                                .setMessage("Replace them with the " + got.size() + " from the file, or add these too?")
                                .setPositiveButton("Replace", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) { addCues(got, true); }
                                })
                                .setNeutralButton("Add", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) { addCues(got, false); }
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                });
            }
        });
    }

    /** UTF-8 (with or without BOM), else Windows-1252, the two encodings SRT files come in. */
    private static String decodeText(byte[] b) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(b, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private void addCues(java.util.List<Srt.Cue> cues, boolean replace) {
        beginEdit();
        if (replace) {
            // keep the old captions' style for the new ones
            VProject.TextItem style = null;
            for (VProject.TextItem t : captions()) { style = t; break; }
            proj.texts.removeAll(captions());
            if (style != null) proj.texts.add(style);   // removed again below, after its style is used
            ArrayList<VProject.TextItem> made = new ArrayList<VProject.TextItem>();
            for (Srt.Cue c : cues) made.add(newCaption(c.start, c.end - c.start, c.text));
            if (style != null) proj.texts.remove(style);
            proj.texts.addAll(made);
        } else {
            for (Srt.Cue c : cues) proj.texts.add(newCaption(c.start, c.end - c.start, c.text));
        }
        select(TimelineView.SEL_NONE, -1, false);
        changed();
        long last = cues.isEmpty() ? 0 : cues.get(cues.size() - 1).end;
        act.snack("Imported " + cues.size() + " captions" + (last > proj.duration() && proj.duration() > 0 ? " (some run past the end of the video)" : ""),
                "Undo", new Runnable() { @Override public void run() { doUndo(); } });
    }

    private void exportSrt() {
        ArrayList<Srt.Cue> cues = new ArrayList<Srt.Cue>();
        for (VProject.TextItem t : captions()) cues.add(new Srt.Cue(t.start, t.start + t.dur, t.layer.text));
        String name = Projects.name(dir).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "Independent Video captions";
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name + ".srt");
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Independent Video");
            Uri out = act.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (out == null) throw new Exception("Downloads refused the file");
            java.io.OutputStream os = act.getContentResolver().openOutputStream(out);
            if (os == null) throw new Exception("can't write the file");
            try { os.write(Srt.write(cues).getBytes("UTF-8")); } finally { os.close(); }
            toast("Saved " + cues.size() + " captions to Download/Independent Video/" + name + ".srt");
        } catch (Exception e) {
            toast("Couldn't save captions: " + e.getMessage());
        }
    }

    // ================================================================ Format tab
    private void formatPanel() {
        section("Canvas");
        panel.addView(Ui.label(act, "Aspect"));
        View[] as = new View[VProject.ASPECT_NAMES.length];
        for (int i = 0; i < as.length; i++) {
            final int k = i;
            as[i] = chip(VProject.ASPECT_NAMES[i], proj.aspectIdx == i, new View.OnClickListener() { @Override public void onClick(View v) { setFormat(k, proj.resIdx, proj.fpsIdx); } });
        }
        panel.addView(Ui.strip(act, as));
        section("Output");
        panel.addView(Ui.label(act, "Resolution"));
        View[] rs = new View[VProject.RES_NAMES.length];
        for (int i = 0; i < rs.length; i++) {
            final int k = i;
            rs[i] = chip(VProject.RES_NAMES[i], proj.resIdx == i, new View.OnClickListener() { @Override public void onClick(View v) { setFormat(proj.aspectIdx, k, proj.fpsIdx); } });
        }
        panel.addView(Ui.strip(act, rs));
        panel.addView(Ui.label(act, "Frame rate"));
        View[] fs = new View[VProject.FPS.length];
        for (int i = 0; i < fs.length; i++) {
            final int k = i;
            fs[i] = chip(VProject.FPS[i] + " fps", proj.fpsIdx == i, new View.OnClickListener() { @Override public void onClick(View v) { setFormat(proj.aspectIdx, proj.resIdx, k); } });
        }
        panel.addView(Ui.strip(act, fs));
        section("Canvas");
        panel.addView(Ui.strip(act, chip("Background color", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Pickers.color(act, "Background", proj.bg, new Pickers.ColorCb() {
                    @Override public void begin() { beginEdit(); }
                    @Override public void color(int c) { proj.bg = c | 0xff000000; changed(); }
                    @Override public void done() {}
                });
            }
        })));
        section("Duration");
        panel.addView(Ui.label(act, "Length"));
        View[] lc = new View[VProject.LENGTH.length];
        for (int i = 0; i < lc.length; i++) {
            final int q = i;
            lc[i] = chip(VProject.LENGTH[i], proj.lengthMode == i, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (proj.lengthMode == q) return;
                    beginEdit(); proj.lengthMode = q; time = Math.min(time, proj.duration()); changed(); rebuildPanel();
                }
            });
        }
        panel.addView(Ui.strip(act, lc));
        panel.addView(hint("The video runs to its last clip, title, overlay or visualizer. Choose \"Ends with the music\" to keep going until the song ends (over the background colour)."));
        panel.addView(hint(proj.outW() + " × " + proj.outH() + " at " + proj.fps() + " fps. The background shows where a clip doesn't fill the frame (Fit, zoomed out, rotated)."));
    }

    private void setFormat(int aspect, int res, int fps) {
        beginEdit();
        float ow = proj.outW(), oh = proj.outH();
        proj.aspectIdx = aspect;
        proj.resIdx = res;
        proj.fpsIdx = fps;
        float sx = proj.outW() / ow, sy = proj.outH() / oh, s = Math.min(sx, sy);
        for (VProject.TextItem t : proj.texts) {   // keep overlays in the same relative place and size
            t.layer.cx *= sx;
            t.layer.cy *= sy;
            for (Keys.Key k : t.keys.list) { k.v[0] *= sx; k.v[1] *= sy; }
            if (t.viz != null) {
                t.layer.srcW = Math.max(8, Math.round(t.layer.srcW * sx));
                t.layer.srcH = Math.max(8, Math.round(t.layer.srcH * sy));
            } else if (t.media != null) {
                // media keeps its native pixels; only its placement scale follows the frame
                if (s != 1) {
                    t.layer.scale *= s;
                    for (Keys.Key k : t.keys.list) k.v[2] *= s;
                }
            } else if (s != 1) {
                t.layer.textSize = Math.max(4, t.layer.textSize * s);
                // the wrap width scales with the text, so lines break in the same places
                if (t.layer.wrapW > 0) t.layer.wrapW = Math.max(8, Math.round(t.layer.wrapW * s));
                TextRender.refreshPreview(t.layer);
            }
        }
        changed();
        rebuildPanel();
    }

    // ================================================================ export
    /** Export starts with a check that every file is still there; if not, the fix comes first. */
    private void exportDialog() {
        renderer.soloId=0; gl.requestRender();
        if (exporting) return;
        checkMissing(new Runnable() {
            @Override public void run() { if (missing.isEmpty()) exportDialogNow(); else missingSheet(); }
        });
    }

    private void exportDialogNow() {
        if (previewBase != null) applyPreview();
        Crash.note("video export dialog");
        if (exporting) return;
        if (proj.duration() <= 0) { toast("Add some clips first."); return; }
        pause();
        final int[] sel = {1, 0};
        final LinearLayout box = Ui.col(act);
        box.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        final Runnable[] build = new Runnable[1];
        build[0] = new Runnable() {
            @Override public void run() {
                box.removeAllViews();
                box.addView(Ui.label(act, proj.outW() + " × " + proj.outH() + " · " + proj.fps() + " fps · " + TimelineView.fmtDur(proj.duration())));
                box.addView(Ui.label(act, "Quality"));
                View[] q = new View[Exporter.QUALITY.length];
                for (int i = 0; i < q.length; i++) {
                    final int k = i;
                    q[i] = chip(Exporter.QUALITY[i], sel[0] == i, new View.OnClickListener() { @Override public void onClick(View v) { sel[0] = k; build[0].run(); } });
                }
                box.addView(Ui.strip(act, q));
                box.addView(Ui.label(act, "Codec"));
                box.addView(Ui.strip(act,
                        chip("H.264 (plays everywhere)", sel[1] == 0, new View.OnClickListener() { @Override public void onClick(View v) { sel[1] = 0; build[0].run(); } }),
                        chip("HEVC (smaller)", sel[1] == 1, new View.OnClickListener() { @Override public void onClick(View v) { sel[1] = 1; build[0].run(); } })));
                // 3.13: what this choice means in bitrate and file size
                int vb = Exporter.videoBitrate(proj.outW(), proj.outH(), proj.fps(), sel[0], sel[1] == 1);
                boolean sound = !proj.audio.isEmpty();
                for (VProject.Clip c : proj.clips) if (c.kind == VProject.VIDEO && c.hasAudio && !c.mute) sound = true;
                for (VProject.TextItem t : proj.texts) if (t.media != null && t.media.kind == VProject.VIDEO && t.media.hasAudio && !t.media.mute) sound = true;
                box.addView(AdjPanels.hint(act, String.format(Locale.US, "Video %.1f Mbps%s \u00b7 about %s",
                        vb / 1e6, sound ? " + audio 256 kbps" : "", Exporter.sizeText(Exporter.estimateBytes(proj.duration(), vb, sound)))));
            }
        };
        build[0].run();
        Pickers.dialog(act).setTitle("Export video").setView(box)
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { runExport(sel[0], sel[1] == 1); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void runExport(int quality, boolean hevc) {
        save();
        exporting = true;
        releaseAllPlayers();   // free hardware decoders for the export
        showStatus("Starting export…");
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (exporter == null) return;
                Pickers.dialog(act).setTitle("Cancel export?").setPositiveButton("Cancel export", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { if (exporter != null) exporter.cancel.set(true); }
                }).setNegativeButton("Keep going", null).show();
            }
        });
        exporter = new Exporter(act.getApplicationContext(), proj.copy(), quality, hevc, new Exporter.Listener() {
            @Override public void progress(final float f, final String msg) {
                h.post(new Runnable() { @Override public void run() { showStatus(msg + (f > 0 ? String.format(Locale.US, "  %d%%", Math.round(f * 100)) : "") + "\nTap to cancel"); } });
            }
            @Override public void done(final String result, final boolean ok) {
                h.post(new Runnable() {
                    @Override public void run() {
                        Exporter ex = exporter;
                        exporting = false;
                        exporter = null;
                        status.setOnClickListener(null);
                        showStatus(null);
                        if (!ok && ex != null && ex.failure != null && !ex.cancel.get()) {
                            java.io.StringWriter w = new java.io.StringWriter();
                            ex.failure.printStackTrace(new java.io.PrintWriter(w));
                            Pickers.report(act, "Export failed", result + "\n\n" + proj.outW() + "x" + proj.outH() + " " + proj.fps() + " fps, "
                                    + proj.clips.size() + " clips, " + proj.texts.size() + " overlays, " + proj.audio.size() + " music\n"
                                    + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE + "\n\n" + w, null);
                        } else if (ok && ex != null && ex.resultUri != null) act.exported(result, ex.resultUri, "video/mp4");
                        else toast(result);
                        syncPlayers();
                    }
                });
            }
        });
        Thread t = new Thread(exporter, "export");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
    }

    // ================================================================ Workspace
    @Override public View rootView() { return root; }

    @Override public boolean back() {
        if (exporting) return true;
        if(selectingMany){endBatch();return true;}
        if (fullView) { toggleFull(); return true; }
        if (barsHidden) { setBarsHidden(false); return true; }
        if (previewBase != null) { cancelPreview(); return true; }
        if (playing) { pause(); return true; }
        if (selKind != TimelineView.SEL_NONE) { select(TimelineView.SEL_NONE, -1, false); return true; }
        return false;
    }

    @Override public boolean blocking() { return exporting; }

    @Override public void onPause() {
        if (sourceViewer != null) sourceViewer.pause();
        twoUp.hide();
        pause();
        releaseAllPlayers();
        save();
        gl.onPause();
    }

    @Override public void onResume() {
        gl.onResume();
        syncPlayers();
    }

    @Override public void save() { save(true); }
    @Override public boolean saveForVersion() { pause(); applyPreview(); return save(false, false); }

    // Autosave the project 4 s after the last edit (JSON only; the library thumbnail waits for a real save).
    private final Runnable autosaveRun = new Runnable() {
        @Override public void run() {
            if (closed || !dirty) return;
            Crash.note("video autosave");
            save(false, true);
        }
    };

    /** Saves run one at a time, in order: autosave off the UI thread, explicit saves inline. */
    private final java.util.concurrent.ExecutorService saveExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void save(boolean thumb) { save(thumb, false); }

    private boolean save(boolean thumb, boolean async) {
        h.removeCallbacks(autosaveRun);
        final String json;
        try {
            // a preview isn't part of the project until it's applied
            json = (previewBase != null ? previewBase : proj).toJson().toString();
        } catch (Exception e) {
            toast("Couldn't save project: " + e.getMessage());
            return false;
        }
        dirty = false;
        final boolean[] saved = {false};
        Runnable job = new Runnable() {
            @Override public void run() {
                try { Loader.writeText(new File(dir, "video.json"), json); saved[0] = true; }   // tmp + fsync + rename
                catch (final Exception e) {
                    h.post(new Runnable() { @Override public void run() { toast("Couldn't save project: " + e.getMessage()); dirty = true; } });
                }
            }
        };
        if (async) saveExec.execute(job);
        else {
            try { saveExec.submit(new Runnable() { @Override public void run() {} }).get(); } catch (Exception ignored) {}
            job.run();
        }
        if (!thumb) return async || saved[0];
        final VProject p = proj.copy();
        final File th = new File(dir, "thumb.jpg");
        bg.execute(new Runnable() {
            @Override public void run() {
                if (p.clips.isEmpty()) return;
                VProject.Clip c = p.clips.get(0);
                Bitmap b = c.kind == VProject.IMAGE ? c.still : Media.thumb(act, Uri.parse(c.uri), c.in, 300);
                if (b != null) try { Loader.saveJpeg(b, th, 85); } catch (Exception ignored) {}
            }
        });
        return async || saved[0];
    }

    @Override public void close() {
        gl.onPause();
        closed = true;
        rearmRotation = false;
        if (orient != null) orient.disable();
        immersive(false);
        act.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        twoUp.shutdown();
        h.removeCallbacks(tickRun);
        h.removeCallbacks(rebuildRun);
        cancelBg.set(true);
        releaseAllPlayers();
        bg.shutdown();
        bg2.shutdownNow();
        audioBg.shutdownNow();
        saveExec.shutdown();   // queued saves still finish
    }

    @Override public void flush(final Runnable r) { h.post(r); }

    @Override public void fontImported(String key) {
        if (pendingMaskFont) { pendingMaskFont = false; MaskPanel.setFont(vmHost, key); return; }
        VProject.TextItem t = selText();
        if (t == null) return;
        beginEdit();
        t.layer.font = key;
        TextRender.refreshPreview(t.layer);
        changed();
        rebuildPanel();
    }

    @Override public void lutImported(String name) {
        Layer l = gradeLayer();
        if (l == null || l.type != Layer.LUT) {
            ArrayList<Layer> list = gradeList();
            if (list == null) { gradeTarget = G_FOOTAGE; list = proj.pgrade; }   // a look belongs under the graphics
            beginEdit();
            l = new Layer();
            l.id = proj.nextId++;
            l.type = Layer.LUT;
            list.add(l);
            gradeSelId = l.id;
        } else beginEdit();
        l.lutFile = name;
        l.name = Lut.displayName(name);
        tab = T_GRADE;
        buildTabs();
        changed();
        rebuildPanel();
    }

    // ================================================================ GL preview
    /** Preview size: the output frame scaled so its long side is at most 1280. */
    int[] previewSize() {
        float k = Math.min(1f, 1280f / Math.max(proj.outW(), proj.outH()));
        return new int[]{Math.max(2, Math.round(proj.outW() * k)), Math.max(2, Math.round(proj.outH() * k))};
    }

    /** Where the frame sits in a w x h view (screen px from top-left): x, y, width, height. */
    float[] viewRect(int w, int h, VProject p) {
        float m = Ui.dp(8);
        float k = Math.min((w - 2 * m) / p.outW(), (h - 2 * m) / p.outH());
        float fw = p.outW() * k, fh = p.outH() * k;
        return new float[]{(w - fw) / 2, (h - fh) / 2, fw, fh};
    }

    final class Preview implements GLSurfaceView.Renderer {
        volatile VProject snap;
        volatile long time;
        volatile int soloId;
        VComp comp;
        int sw = 1, sh = 1, frameNo;

        @Override public void onSurfaceCreated(GL10 g, EGLConfig c) {
            comp = new VComp(act);
            comp.previewShapes = true;   // 3.15: text/shape masks are built in the background
            try { comp.init(); } catch (final Exception e) {
                h.post(new Runnable() { @Override public void run() { showStatus("Shader: " + e.getMessage()); } });
            }
            // a new context can't adopt textures from the old one: rebuild the players
            boolean any = false;
            for (Player pl : players.values()) if (pl.attached) any = true;
            if (any) h.post(new Runnable() { @Override public void run() { releaseAllPlayers(); syncPlayers(); } });
        }

        @Override public void onSurfaceChanged(GL10 g, int w, int hh) { sw = w; sh = hh; }

        @Override public void onDrawFrame(GL10 g) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, sw, sh);
            GLES20.glClearColor(0.055f, 0.06f, 0.07f, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            final VProject p = snap;
            if (p == null || comp == null || comp.progs.isEmpty()) return;
            for (Player pl : players.values()) {
                if (!pl.video || pl.released || pl.st == null) continue;
                try {
                    if (!pl.attached) {
                        pl.oes = comp.newOesTex();
                        pl.st.attachToGLContext(pl.oes);
                        pl.frame.tex = pl.oes;
                        pl.attached = true;
                    }
                    if (pl.frameAvail) {
                        pl.frameAvail = false;
                        pl.st.updateTexImage();
                        pl.st.getTransformMatrix(pl.frame.st);
                        pl.hasFrame = true;
                    }
                } catch (Exception ignored) {}
            }
            float k = Math.min(1f, 1280f / Math.max(p.outW(), p.outH()));
            int pw = Math.max(2, Math.round(p.outW() * k)), ph = Math.max(2, Math.round(p.outH() * k));
            comp.soloId = soloId;
            int res = comp.render(p, time, pw, ph, frames);
            comp.display(res, pw, ph, sw, sh, viewRect(sw, sh, p), p.bg);
            if (++frameNo % 30 == 0) {
                // drop cached textures of stills and text bitmaps no longer in the project
                IdentityHashMap<Bitmap, Boolean> live = new IdentityHashMap<Bitmap, Boolean>();
                for (VProject.Clip c : p.clips) if (c.still != null) live.put(c.still, true);
                for (VProject.TextItem t : p.texts) {
                    if (t.layer.preview != null) live.put(t.layer.preview, true);
                    if (t.media != null && t.media.still != null) live.put(t.media.still, true);
                }
                for (ArrayList<Layer> gl0 : p.gradeLists()) for (Layer gl1 : gl0) if (gl1.mask != null) live.put(gl1.mask, true);
                for (Bitmap sb : comp.shapeImages) live.put(sb, true);
                comp.shapeImages.clear();
                for (Bitmap b : new ArrayList<Bitmap>(comp.texCache.keySet())) if (!live.containsKey(b)) comp.forget(b);
            }
        }

        /**
         * 3.13 Save frame, on the GL thread: frame t at W x H into an RGBA8 target through the export
         * output pass, read back (rows flipped: the pass writes window-style, row 0 at the bottom).
         * The compositor's buffers are released afterwards so the preview doesn't keep 4K buffers.
         */
        Bitmap grab(int W, int H, long t) {
            final VProject p = snap;
            if (p == null || comp == null || comp.progs.isEmpty()) throw new IllegalStateException("the preview isn't ready");
            int saveSolo = comp.soloId;
            comp.soloId = 0;
            int[] tf = null;
            try {
                int res = comp.render(p, t, W, H, frames);
                tf = comp.makeOutputTarget(W, H);
                comp.outputTo(tf[1], res, W, H, p.bg);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(W * H * 4);
                GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
                GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                int err = GLES20.glGetError();
                if (err != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error " + err + " at " + W + "\u00d7" + H);
                byte[] row = new byte[W * 4];
                java.nio.ByteBuffer flipped = java.nio.ByteBuffer.allocateDirect(W * H * 4);
                for (int y = H - 1; y >= 0; y--) { buf.position(y * W * 4); buf.get(row); flipped.put(row); }
                flipped.rewind();
                Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(flipped);
                return b;
            } finally {
                if (tf != null) comp.deleteTarget(tf);
                comp.soloId = saveSolo;
                comp.releaseBuffers();
                gl.requestRender();
            }
        }

        /** 3.14: frame t of base with extra layers on top (Everything), rendered at w x h for a catalog tile. */
        private int[] thumbTarget; private int thumbTW, thumbTH;
        Bitmap thumb(VProject base, long t, java.util.List<Layer> extra, int w, int hh) {
            if (comp == null || comp.progs.isEmpty()) return null;
            VProject p = base.copy();
            int id = -1000000;
            for (Layer l : extra) { Layer c = l.copy(); c.id = id--; c.parent = 0; c.rebuildLut(); p.grade.add(c); }
            if (thumbTarget == null || thumbTW != w || thumbTH != hh) {
                if (thumbTarget != null) comp.deleteTarget(thumbTarget);
                thumbTarget = comp.makeOutputTarget(w, hh); thumbTW = w; thumbTH = hh;
            }
            int saveSolo = comp.soloId;
            comp.soloId = 0;
            try {
                int res = comp.render(p, t, w, hh, frames);
                comp.outputTo(thumbTarget[1], res, w, hh, p.bg);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * hh * 4);
                GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
                GLES20.glReadPixels(0, 0, w, hh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                byte[] row = new byte[w * 4];
                java.nio.ByteBuffer flipped = java.nio.ByteBuffer.allocateDirect(w * hh * 4);
                for (int y = hh - 1; y >= 0; y--) { buf.position(y * w * 4); buf.get(row); flipped.put(row); }
                flipped.rewind();
                Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(flipped);
                return b;
            } finally {
                comp.soloId = saveSolo;
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                gl.requestRender();   // the preview's buffers were used: draw the real frame again
            }
        }

        final VComp.Frames frames = new VComp.Frames() {
                @Override public VComp.Frame frame(VProject.Clip c) {
                    if (c.kind == VProject.IMAGE) {
                        if (c.still == null) return null;
                        VComp.Frame f = new VComp.Frame();
                        f.tex = comp.bitmapTex(c.still);
                        return f;
                    }
                    Player pl = players.get(c.id);
                    return pl != null && pl.hasFrame && !pl.released && pl.attached ? pl.frame : null;
                }

                @Override public VComp.TextSrc text(VProject.TextItem t) {
                    if (t.layer.preview == null) return null;
                    VComp.TextSrc s = new VComp.TextSrc();
                    s.bmp = t.layer.preview;
                    s.rect = new android.graphics.RectF(0, 0, t.layer.srcW, t.layer.srcH);
                    return s;
                }
        };
    }

    // ================================================================ preview gestures
    final class Overlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float lastX, lastY, downX, downY, pDist, pAng;
        private boolean multi, editing, moved;

        Overlay(android.content.Context c) { super(c); }

        private boolean textActive(VProject.TextItem t) { return t != null && time >= t.start && time < t.start + t.dur; }

        /** The overlay the preview's drag/pinch edits on this tab, if any. */
        private VProject.TextItem gestureItem() {
            return tab == T_TEXT ? selText() : tab == T_VIZ ? selViz() : tab == T_CLIPS ? selMedia() : null;
        }

        private boolean clipActive(VProject.Clip c) {
            if (c == null) return false;
            long[] a = proj.at(time);
            int i = proj.clipIndex(c.id);
            return i >= 0 && (a[0] == i || a[1] == i);
        }

        @Override
        protected void onDraw(Canvas c) {
            drawGuides(c);
            if(selectingMany)return;
            if (maskDraw(c)) return;
            float[] v = viewRect(getWidth(), getHeight(), proj);
            float k = v[2] / proj.outW();
            VProject.TextItem t = gestureItem();
            if (textActive(t)) {
                Layer l = t.layer;
                float[][] cr = {{0, 0}, {l.srcW, 0}, {l.srcW, l.srcH}, {0, l.srcH}};
                path.reset();
                float[] o = new float[2];
                for (int i = 0; i < 4; i++) {
                    l.layerToImg(cr[i][0], cr[i][1], o);
                    float x = v[0] + o[0] * k, y = v[1] + o[1] * k;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(1.5f));
                p.setColor(Ui.ACCENT);
                c.drawPath(path, p);
            }
            if (proj.clips.isEmpty() && proj.texts.isEmpty()) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.DIM);
                p.setTextSize(Ui.dp(14));
                String s = "Add videos or photos on the Edit tab";
                c.drawText(s, (getWidth() - p.measureText(s)) / 2, getHeight() / 2f, p);
            }
        }

        /** 3.13 guides over the picture area. */
        private void drawGuides(Canvas c) {
            if (guideMode == 0 || !ViewGuides.available(guideMode, proj.outW(), proj.outH())) return;
            float[] v = viewRect(getWidth(), getHeight(), proj);
            p.setStyle(Paint.Style.FILL);
            for (float[] r : ViewGuides.rects(guideMode, proj.outW(), proj.outH())) {
                if (!ViewGuides.shadeOutside(guideMode)) continue;
                float l = v[0] + r[0] * v[2], t = v[1] + r[1] * v[3], rr = v[0] + r[2] * v[2], b = v[1] + r[3] * v[3];
                p.setColor(0x66000000);
                c.drawRect(v[0], v[1], v[0] + v[2], t, p);
                c.drawRect(v[0], b, v[0] + v[2], v[1] + v[3], p);
                c.drawRect(v[0], t, l, b, p);
                c.drawRect(rr, t, v[0] + v[2], b, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1));
            p.setColor(0x99ffffff);
            for (float[] ln : ViewGuides.lines(guideMode))
                c.drawLine(v[0] + ln[0] * v[2], v[1] + ln[1] * v[3], v[0] + ln[2] * v[2], v[1] + ln[3] * v[3], p);
            float[][] rs = ViewGuides.rects(guideMode, proj.outW(), proj.outH());
            String[] labels = ViewGuides.rectLabels(guideMode);
            for (int i = 0; i < rs.length; i++) {
                float[] r = rs[i];
                float l = v[0] + r[0] * v[2], t = v[1] + r[1] * v[3];
                p.setStyle(Paint.Style.STROKE);
                p.setColor(i == 0 && guideMode == ViewGuides.SAFE ? 0x77ffffff : 0xccffd27a);
                c.drawRect(l, t, v[0] + r[2] * v[2], v[1] + r[3] * v[3], p);
                if (i < labels.length) {
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(Ui.dp(9));
                    c.drawText(labels[i], l + Ui.dp(3), t + Ui.dp(10) + (guideMode == ViewGuides.SAFE ? i * Ui.dp(11) : 0), p);
                }
            }
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if(selectingMany)return false;
            if (maskTouch(e)) return true;
            float[] v = viewRect(getWidth(), getHeight(), proj);
            float k = v[2] / proj.outW();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    multi = false;
                    moved = false;
                    editing = textActive(gestureItem()) || (tab == T_CLIPS && clipActive(selClip()));
                    if (editing) { pause(); beginEdit(); }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        multi = true;
                        pDist = (float) Math.max(1, Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
                        pAng = (float) Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0));
                        lastX = (e.getX(0) + e.getX(1)) / 2;
                        lastY = (e.getY(0) + e.getY(1)) / 2;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > Ui.dp(8)) moved = true;
                    if (!editing) return true;
                    float mx = e.getX(), my = e.getY(), sc = 1, da = 0;
                    if (multi && e.getPointerCount() >= 2) {
                        float d = (float) Math.max(1, Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
                        float an = (float) Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0));
                        sc = d / pDist;
                        da = (float) Math.toDegrees(an - pAng);
                        pDist = d;
                        pAng = an;
                        mx = (e.getX(0) + e.getX(1)) / 2;
                        my = (e.getY(0) + e.getY(1)) / 2;
                    } else if (multi) return true;
                    float dx = mx - lastX, dy = my - lastY;
                    lastX = mx;
                    lastY = my;
                    if (gestureItem() != null) {
                        Layer l = gestureItem().layer;
                        l.cx += dx / k;
                        l.cy += dy / k;
                        l.scale = Math.max(0.05f, Math.min(20f, l.scale * sc));
                        l.rot += da;
                    } else {
                        VProject.Clip c = selClip();
                        c.panX += dx / v[2];
                        c.panY += dy / v[3];
                        c.scale = Math.max(0.1f, Math.min(10f, c.scale * sc));
                        c.rot += da;
                    }
                    changedXf();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (editing && !moved && !multi) { if (!undo.isEmpty()) undo.remove(undo.size() - 1); updateUndo(); }
                    if (!moved && !multi && !editing) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastViewTap < 320 && Math.hypot(e.getX() - lastViewTapX, e.getY() - lastViewTapY) < Ui.dp(48)) {
                            // double tap: undo the first tap's play/pause, then full screen (LumaFusion)
                            lastViewTap = 0;
                            if (viewTapWasPlaying) { if (!playing) play(); } else { if (playing) pause(); scrubTo(viewTapTime); }
                            toggleFull();
                        } else {
                            lastViewTap = now; lastViewTapX = e.getX(); lastViewTapY = e.getY();
                            viewTapWasPlaying = playing; viewTapTime = time;
                            if (playing) pause(); else play();
                        }
                    }
                    if (editing) rebuildPanel();
                    editing = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    editing = false;
                    return true;
            }
            return true;
        }
    }
}
