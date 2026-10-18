package com.christopher.strata;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Source viewer (3.7, audit package 6), after LumaFusion's library preview: play and scrub one
 * video, mark In and Out, then Insert, Overwrite or Append the range at the timeline's
 * playhead, or Replace the selected clip's footage with it. Frame-accurate seeks
 * (SEEK_CLOSEST) into a TextureView; the marks are source microseconds, Out exclusive.
 */
final class SourceViewer {
    interface Action { void edit(int what, VProject.Clip probe, long in, long out); }

    static final int INSERT = 0, OVERWRITE = 1, APPEND = 2, REPLACE_KEEP = 3, REPLACE_MARKED = 4, TRIM = 5;

    private final Activity act;
    private final Dialog d;
    private final VProject.Clip probe;
    private final int fps;
    private final boolean trimOnly;
    private final long frame;
    private final Action cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared, seeking, playing, closed;
    private boolean focusRange, loopRange;
    private long viewStart, viewEnd;
    private long pendingSeek = -1;
    private long in, out, pos;
    private final Bar bar;
    private final TextView time, info;
    private final ImageView playBtn;

    SourceViewer(Activity a, VProject.Clip probe, int fps, long in, long out, long at, boolean canReplace, Action cb) {
        this(a, probe, fps, in, out, at, canReplace, false, cb);
    }

    SourceViewer(Activity a, VProject.Clip probe, int fps, long in, long out, long at, boolean canReplace, boolean trimOnly, Action cb) {
        act = a;
        this.probe = probe;
        this.fps = fps; this.trimOnly = trimOnly;
        frame = Timeline.frameTime(1, fps);
        this.cb = cb;
        this.in = Math.max(0, Math.min(probe.srcDur - frame, in));
        this.out = Math.max(this.in + frame, Math.min(probe.srcDur, out));
        viewEnd=probe.srcDur;
        pos = Math.max(0, Math.min(probe.srcDur - frame, at));

        d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(0xff000000);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets wi) {
                android.graphics.Insets s = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(s.left, s.top, s.right, s.bottom);
                return WindowInsets.CONSUMED;
            }
        });

        // top bar: close, name, timecode
        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.iconBtn(a, "close", new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } }));
        TextView name = Ui.text(a, (trimOnly ? "Trim · " : "Source · ") + probe.name, 15, Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, Ui.lp(0, -2, 1f));
        time = Ui.text(a, "", 13, Ui.DIM);
        time.setTypeface(android.graphics.Typeface.MONOSPACE);
        time.setPadding(0, 0, Ui.dp(14), 0);
        top.addView(time);
        root.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(52)));

        // the picture, fitted to the space
        FrameLayout pic = new FrameLayout(a);
        Fitted tv = new Fitted(a, probe.srcW, probe.srcH);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) { open(st); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        pic.addView(tv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(pic, Ui.lp(-1, 0, 1f));

        bar = new Bar(a);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(56));
        blp.setMargins(Ui.dp(14), Ui.dp(6), Ui.dp(14), 0);
        root.addView(bar, blp);

        LinearLayout rangeTools=Ui.row(a);rangeTools.setGravity(Gravity.CENTER);
        rangeTools.addView(markBtn("sliders","In / Out times",new View.OnClickListener(){public void onClick(View v){exactMarks();}}));
        final TextView focus=Ui.chip(a,"Focus range",false,null);
        focus.setOnClickListener(new View.OnClickListener(){public void onClick(View v){focusRange=!focusRange;updateViewRange();focus.setText(focusRange?"Fit whole file":"Focus range");bar.invalidate();}});
        rangeTools.addView(focus);
        final TextView loop=Ui.chip(a,"Loop range",false,null);
        loop.setOnClickListener(new View.OnClickListener(){public void onClick(View v){loopRange=!loopRange;loop.setText(loopRange?"Stop loop":"Loop range");if(loopRange){pause();seek(in);togglePlay();}}});
        rangeTools.addView(loop);
        root.addView(rangeTools);

        info = Ui.text(a, "", 12, Ui.DIM);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        info.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { exactMarks(); } });
        root.addView(info);

        // transport and marks
        LinearLayout tr = Ui.row(a);
        tr.setGravity(Gravity.CENTER);
        tr.addView(Ui.iconBtn(a, "frame_back", new View.OnClickListener() { @Override public void onClick(View v) { step(-1); } }));
        playBtn = Ui.iconBtn(a, "play", new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        tr.addView(playBtn);
        tr.addView(Ui.iconBtn(a, "frame_fwd", new View.OnClickListener() { @Override public void onClick(View v) { step(1); } }));
        tr.addView(markBtn("mark_in", "In", new View.OnClickListener() { @Override public void onClick(View v) { markIn(); } }));
        tr.addView(markBtn("mark_out", "Out", new View.OnClickListener() { @Override public void onClick(View v) { markOut(); } }));
        tr.addView(markBtn("close", "Clear", new View.OnClickListener() { @Override public void onClick(View v) { SourceViewer.this.in = 0; SourceViewer.this.out = probe.srcDur; updateViewRange();refresh(); } }));
        android.widget.HorizontalScrollView transportScroll=new android.widget.HorizontalScrollView(a);
        transportScroll.setHorizontalScrollBarEnabled(false);transportScroll.setFillViewport(true);transportScroll.addView(tr);
        root.addView(transportScroll,new LinearLayout.LayoutParams(-1,Ui.dp(52)));

        // the edits
        LinearLayout ed = Ui.row(a);
        ed.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(10));
        if (trimOnly) {
            ed.addView(Ui.chip(act, "Cancel", false, new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } }), Ui.lp(0, -2, 1f));
            ed.addView(action("check", "Apply trim", TRIM), Ui.lp(0, -2, 1f));
        } else {
        ed.addView(action("insert", "Insert", INSERT), Ui.lp(0, -2, 1f));
        ed.addView(action("overwrite", "Overwrite", OVERWRITE), Ui.lp(0, -2, 1f));
        ed.addView(action("append", "Append", APPEND), Ui.lp(0, -2, 1f));
        if (canReplace) {
            TextView r = actionView("replace", "Replace");
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pause();
                    new Sheet(act, "Replace the selected clip's footage")
                            .item("replace", "Keep the clip's length", "Footage from In, as long as the clip is now; nothing on the timeline moves",
                                    new Runnable() { @Override public void run() { finish(REPLACE_KEEP); } })
                            .item("replace", "Use the marked length", "The clip becomes In to Out; later clips move",
                                    new Runnable() { @Override public void run() { finish(REPLACE_MARKED); } })
                            .show();
                }
            });
            ed.addView(r, Ui.lp(0, -2, 1f));
        }
        }
        root.addView(ed);
        TextView hint = Ui.text(a, trimOnly ? "Drag the amber In/Out handles or scrub and mark In/Out. Tap the times to type exact values. Apply trims this item; Cancel leaves it unchanged." : "Drag In/Out handles; tap the times for exact entry. Insert pushes later footage; Overwrite keeps its timing.", 11, Ui.DIM);
        hint.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10));
        root.addView(hint);

        d.setContentView(root);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) { closed = true; release(); h.removeCallbacksAndMessages(null); }
        });
        refresh();
    }

    void show() {
        d.show();
        Window w = d.getWindow();
        if (w != null) w.setLayout(-1, -1);
    }

    /** The editor went to the background. */
    void pause() {
        h.removeCallbacks(tick);
        if (mp != null && playing) {
            try { mp.pause(); } catch (Exception ignored) {}
        }
        playing = false;
        Ui.setIcon(playBtn, "play");
    }

    // ---------------------------------------------------------------- playback
    private void open(SurfaceTexture st) {
        if (closed) return;
        surface = new Surface(st);
        mp = new MediaPlayer();
        try {
            mp.setDataSource(act, Uri.parse(probe.uri));
            mp.setSurface(surface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) { prepared = true; seek(pos); }
            });
            mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override public void onSeekComplete(MediaPlayer m) {
                    seeking = false;
                    if (pendingSeek >= 0) { long p = pendingSeek; pendingSeek = -1; seek(p); }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { playing = false; Ui.setIcon(playBtn, "play");if(loopRange&&!closed){seek(in);togglePlay();} }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) { info.setText("This video can't be played here (" + what + ")."); return true; }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            info.setText("Can't open this video: " + e.getMessage());
        }
    }

    private void release() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        if (surface != null) { surface.release(); surface = null; }
        prepared = false;
        playing = false;
    }

    /** Frame-accurate seek; while one runs, only the latest target is kept. */
    private void seek(long us) {
        pos = Math.max(0, Math.min(probe.srcDur - 1, us));
        refresh();
        if (mp == null || !prepared) return;
        if (seeking) { pendingSeek = pos; return; }
        seeking = true;
        try { mp.seekTo(pos / 1000, MediaPlayer.SEEK_CLOSEST); } catch (Exception e) { seeking = false; }
    }

    private void togglePlay() {
        if (mp == null || !prepared) return;
        if (playing) { pause(); return; }
        if ((trimOnly||loopRange) && (pos < in || pos >= out - frame)) seek(in);
        else if (pos >= probe.srcDur - frame) seek(0);
        try { mp.start(); } catch (Exception e) { return; }
        playing = true;
        Ui.setIcon(playBtn, "pause");
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing || mp == null || closed) return;
            try { pos = mp.getCurrentPosition() * 1000L; } catch (Exception ignored) {}
            if ((trimOnly||loopRange) && pos >= out) {
                if(loopRange){seek(in);h.postDelayed(this,33);return;}
                pause(); seek(Math.max(in, out - frame)); return;
            }
            refresh();
            h.postDelayed(this, 33);
        }
    };

    private void step(int dir) {
        pause();
        long f = Timeline.frameIndex(pos, fps) + dir;
        seek(Timeline.frameTime(Math.max(0, f), fps));
    }

    // ---------------------------------------------------------------- marks
    private void markIn() {
        in = Math.min(Math.max(0, probe.srcDur - frame), Timeline.quant(pos, fps));
        if (out < in + frame) out = probe.srcDur;
        updateViewRange();refresh();
    }

    /** Out is exclusive: marking a frame keeps it. */
    private void markOut() {
        out = Math.min(probe.srcDur, ClipRange.playheadBoundary(pos,fps,false));
        if (in > out - frame) in = 0;
        updateViewRange();refresh();
    }

    private void exactMarks() {
        pause();
        RangeInput.show(act,probe,fps,in,out,"Marks change only this preview until you apply the edit.",new RangeInput.Result(){public void apply(long i,long o){in=i;out=o;updateViewRange();seek(in);}});
    }

    private void updateViewRange() {
        if(!focusRange){viewStart=0;viewEnd=probe.srcDur;return;}
        long pad=Math.max(frame*5,(out-in)/5);
        viewStart=Math.max(0,in-pad);viewEnd=Math.min(probe.srcDur,out+pad);
    }

    private void finish(int what) {
        try{ClipRange.checked(probe,in,out,fps);}catch(IllegalArgumentException e){info.setText(e.getMessage());return;}
        pause();
        cb.edit(what, probe, in, out);
        d.dismiss();
    }

    private void refresh() {
        time.setText(Timeline.timecode(pos, fps) + " / " + Timeline.timecode(probe.srcDur, fps));
        info.setText("In " + Timeline.timecode(in, fps) + "   ·   Out " + Timeline.timecode(out, fps)
                + "   ·   source " + TimelineView.fmtDur(out - in));
        bar.invalidate();
    }

    // ---------------------------------------------------------------- views
    private View markBtn(String icon, String label, View.OnClickListener l) {
        TextView t = Ui.chip(act, icon, label, false, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView actionView(String icon, String label) {
        TextView t = Ui.chip(act, icon, label, false, null);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View action(String icon, String label, final int what) {
        TextView t = actionView(icon, label);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(what); } });
        LinearLayout.LayoutParams lp = Ui.lp(0, -2, 1f);
        lp.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** A TextureView sized to the video's shape inside whatever space it gets. */
    static final class Fitted extends TextureView {
        private final float aspect;
        Fitted(Context c, int w, int h) { super(c); aspect = w > 0 && h > 0 ? w / (float) h : 16f / 9f; }
        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            if (W <= 0 || H <= 0) { super.onMeasure(ws, hs); return; }
            int w = W, h = Math.round(W / aspect);
            if (h > H) { h = H; w = Math.round(H * aspect); }
            setMeasuredDimension(w, h);
        }
    }

    /** Scrub bar: the whole file, the marked range shaded, In/Out ticks and the playhead. */
    final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int drag;
        private float downX;
        private long downTime;

        Bar(Context c) { super(c);setContentDescription("Source trim range. Use In / Out times for exact accessible editing."); }

        private float x(long us) { return Ui.dp(10) + (getWidth() - Ui.dp(20)) * ((us-viewStart) / (float) Math.max(1, viewEnd-viewStart)); }

        @Override protected void onDraw(Canvas c) {
            float y0 = Ui.dp(16), y1 = getHeight() - Ui.dp(12);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.PANEL2);
            c.drawRoundRect(Ui.dp(10), y0, getWidth() - Ui.dp(10), y1, Ui.dp(5), Ui.dp(5), p);
            p.setColor((Ui.ACCENT & 0x00ffffff) | 0x66000000);
            c.drawRect(x(in), y0, x(out), y1, p);
            p.setColor(Ui.ACCENT);
            c.drawRect(x(in), y0 - Ui.dp(6), x(in) + Ui.dp(3), y1, p);
            c.drawRect(x(out) - Ui.dp(3), y0 - Ui.dp(6), x(out), y1, p);
            p.setColor(0xffffffff);
            float px = Math.max(Ui.dp(10),Math.min(getWidth()-Ui.dp(10),x(pos)));
            c.drawRect(px - Ui.dp(1), y0 - Ui.dp(10), px + Ui.dp(1), y1 + Ui.dp(4), p);
            c.drawCircle(px, y0 - Ui.dp(10), Ui.dp(5), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true); pause();
                float left = Math.abs(e.getX() - x(in)), right = Math.abs(e.getX() - x(out));
                drag = Math.min(left, right) <= Ui.dp(24) ? (left <= right ? 1 : 2) : 0;
                downX=e.getX();downTime=drag==1?in:out;
                if(drag!=0){seek(drag==1?in:Math.max(in,out-frame));return true;}
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                float f = (e.getX() - Ui.dp(10)) / Math.max(1, getWidth() - Ui.dp(20));
                long t = viewStart+(long) (Math.max(0, Math.min(1, f)) * (viewEnd-viewStart));
                t = Timeline.quant(t, fps);
                if(drag!=0)t=Timeline.quant(downTime+(long)((e.getX()-downX)*(viewEnd-viewStart)/(double)Math.max(1,getWidth()-Ui.dp(20))),fps);
                if (drag == 1) { in = Math.max(0, Math.min(out - frame, t)); seek(in); }
                else if (drag == 2) { out = Math.min(probe.srcDur, Math.max(in + frame, t)); seek(Math.max(in, out - frame)); }
                else seek(t);
            }
            return true;
        }
    }
}
