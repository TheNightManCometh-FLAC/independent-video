package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-up trim view (3.7, audit package 6; Final Cut's "Show 2-Up When Trimming"): while a cut is
 * trimmed, rolled or slipped, the viewer shows the frame on each side of the edit with its
 * source timecode. Frames are decoded exactly (OPTION_CLOSEST) on one worker thread that keeps
 * a retriever open per file for the whole drag; only the newest request per side is decoded.
 */
final class TwoUp {
    static final class Side {
        String uri, label;
        long us;
        Bitmap still;
        String key() { return still != null ? "still@" + System.identityHashCode(still) : uri + "@" + us; }
    }

    final LinearLayout view;
    private final Context ctx;
    private final ImageView[] img = new ImageView[2];
    private final TextView[] lab = new TextView[2];
    private final String[] shown = new String[2];
    private final java.util.concurrent.atomic.AtomicIntegerArray gen = new java.util.concurrent.atomic.AtomicIntegerArray(2);   // read on the worker
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final HashMap<String, MediaMetadataRetriever> open = new HashMap<String, MediaMetadataRetriever>();   // worker only

    TwoUp(Context c) {
        ctx = c;
        view = Ui.row(c);
        view.setBackgroundColor(0xff000000);
        view.setVisibility(View.GONE);
        for (int i = 0; i < 2; i++) {
            FrameLayout f = new FrameLayout(c);
            img[i] = new ImageView(c);
            img[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(img[i], new FrameLayout.LayoutParams(-1, -1));
            lab[i] = Ui.text(c, "", 12, Ui.TEXT);
            lab[i].setTypeface(android.graphics.Typeface.MONOSPACE);
            lab[i].setBackground(Ui.round(0xcc101114, 10, 0));
            lab[i].setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Ui.dp(8);
            f.addView(lab[i], lp);
            LinearLayout.LayoutParams wl = Ui.lp(0, -1, 1f);
            if (i == 1) wl.leftMargin = Ui.dp(2);
            view.addView(f, wl);
        }
    }

    /** UI thread: show the two sides of the edit (null side = nothing there, e.g. the start of the video). */
    void show(Side l, Side r) {
        view.setVisibility(View.VISIBLE);
        set(0, l);
        set(1, r);
    }

    private void set(final int i, final Side s) {
        String key = s == null ? "none" : s.key();
        if (key.equals(shown[i])) return;
        shown[i] = key;
        final int g = gen.incrementAndGet(i);
        if (s == null) { img[i].setImageBitmap(null); lab[i].setText(i == 0 ? "Start" : "End"); return; }
        lab[i].setText(s.label);
        if (s.still != null || s.uri == null) { img[i].setImageBitmap(s.still); return; }
        final int w = Math.max(64, view.getWidth() / 2), hh = Math.max(64, view.getHeight());
        ex.execute(new Runnable() {
            @Override public void run() {
                if (g != gen.get(i)) return;   // a newer frame was asked for meanwhile
                Bitmap b = null;
                try {
                    MediaMetadataRetriever r = open.get(s.uri);
                    if (r == null) {
                        r = new MediaMetadataRetriever();
                        r.setDataSource(ctx, Uri.parse(s.uri));
                        open.put(s.uri, r);
                    }
                    b = r.getScaledFrameAtTime(s.us, MediaMetadataRetriever.OPTION_CLOSEST, w, hh);
                } catch (Exception ignored) {}
                final Bitmap fb = b;
                h.post(new Runnable() {
                    @Override public void run() { if (g == gen.get(i) && view.getVisibility() == View.VISIBLE && fb != null) img[i].setImageBitmap(fb); }
                });
            }
        });
    }

    /** UI thread: the drag ended. Closes the retrievers on the worker. */
    void hide() {
        if (view.getVisibility() == View.GONE) return;
        view.setVisibility(View.GONE);
        for (int i = 0; i < 2; i++) { gen.incrementAndGet(i); shown[i] = null; img[i].setImageBitmap(null); }
        ex.execute(new Runnable() {
            @Override public void run() {
                for (MediaMetadataRetriever r : open.values()) try { r.release(); } catch (Exception ignored) {}
                open.clear();
            }
        });
    }

    void shutdown() {
        hide();
        ex.shutdown();
    }
}
