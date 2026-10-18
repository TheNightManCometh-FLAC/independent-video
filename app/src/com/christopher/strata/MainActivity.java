package com.christopher.strata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int REQ_OPEN = 1, REQ_ADD_LAYER = 2, REQ_FONT = 3, REQ_LUT = 4;
    static final int BASE_PREVIEW = 4096, LAYER_PREVIEW = 2048;

    private FrameLayout host;
    private Workspace ws;
    /** The photo editor, when that's the open workspace (photo-only callbacks). */
    private Editor editor;
    private TextView busy;
    private View busyBlocker;
    private boolean working;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.density = getResources().getDisplayMetrics().density;
        TextRender.fontsDir = new File(getFilesDir(), "fonts");
        TextRender.fontsDir.mkdirs();
        Lut.dir = new File(getFilesDir(), "luts");
        Looks.dir = new File(getFilesDir(), "looks");
        Looks.assets = getAssets();   // 3.14: built-in looks in assets/looks
        Fonts.assets = getAssets();   // 3.15: the bundled font library in assets/fonts
        Fonts.cacheDir = new File(getFilesDir(), "fontlib");
        Fonts.warm();
        MaskRaster.text = new TextRaster();   // 3.15: text masks drawn with the platform's text engine
        Presets.root = new File(getFilesDir(), "presets");   // 3.14: saved speed curves (more kinds later)
        Lut.dir.mkdirs();
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        host = new FrameLayout(this);
        host.setBackgroundColor(Ui.BG);
        host.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
                // Android 10 (API 29): the Type API doesn't exist yet
                int l = in.getSystemWindowInsetLeft(), t = in.getSystemWindowInsetTop();
                int r = in.getSystemWindowInsetRight(), b = in.getSystemWindowInsetBottom();
                android.view.DisplayCutout dc = in.getDisplayCutout();
                if (dc != null) {
                    l = Math.max(l, dc.getSafeInsetLeft()); t = Math.max(t, dc.getSafeInsetTop());
                    r = Math.max(r, dc.getSafeInsetRight()); b = Math.max(b, dc.getSafeInsetBottom());
                }
                v.setPadding(l, t, r, b);
                return in.consumeSystemWindowInsets();
            }
        });
        setContentView(host);
        busy = Ui.text(this, "", 14, Ui.TEXT);
        busy.setBackground(Ui.round(0xf0101114, 10, Ui.LINE));
        busy.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
        busyBlocker=new View(this);busyBlocker.setClickable(true);busyBlocker.setBackgroundColor(0x44000000);
        registerBack();
        installCrashLog();
        if (b == null) showSplash();
        showStart();
        showLastCrash();
    }

    // ---------------------------------------------------------------- crash log (no logcat on the phone)
    private File crashFile() { return new File(getFilesDir(), "crash.txt"); }

    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final File f = crashFile();
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        final String v = ver;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter w = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(w);
                    pw.println(Brand.NAME + " " + v + " crashed " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    pw.println(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
                    pw.println("Thread: " + t.getName());
                    pw.println("Last actions:");
                    pw.print(Crash.trail());
                    e.printStackTrace(pw);
                    pw.flush();
                    Loader.writeText(f, w.toString());
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private void showLastCrash() {
        final File f = crashFile();
        if (!f.exists()) return;
        String txt;
        try { txt = Loader.readText(f); } catch (Exception e) { f.delete(); return; }
        Pickers.report(this, Brand.NAME + " closed unexpectedly last time", txt + "\nCopy this and email it to " + Brand.CONTACT + ".", new Runnable() {
            @Override public void run() { f.delete(); }
        });
    }

    private void runSelfTest() {
        if (working) return;
        final SelfTest[] st = new SelfTest[1];
        final android.widget.TextView[] tv = new android.widget.TextView[1];
        tv[0] = Pickers.report(this, "Self-test", "Runs three short exports through the real video pipeline (about a minute).\nStarting…",
                new Runnable() { @Override public void run() { if (st[0] != null) st[0].stop(); } });
        st[0] = new SelfTest(this, new SelfTest.Out() {
            @Override public void status(final String s) {
                runOnUiThread(new Runnable() { @Override public void run() { tv[0].setText(s); } });
            }
            @Override public void done(final String report) {
                try { Loader.writeText(new File(getFilesDir(), "selftest.txt"), report); } catch (Exception ignored) {}
                runOnUiThread(new Runnable() { @Override public void run() { tv[0].setText(report); } });
            }
        });
        Thread t = new Thread(st[0], "selftest");
        t.start();
    }

    // ---------------------------------------------------------------- back handling (Android 13-16)
    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) try {
            Class<?> cb = Class.forName("android.window.OnBackInvokedCallback");
            Object proxy = Proxy.newProxyInstance(MainActivity.class.getClassLoader(), new Class<?>[]{cb},
                    new InvocationHandler() {
                        @Override public Object invoke(Object p, Method m, Object[] a) {
                            String n = m.getName();
                            if (n.equals("onBackInvoked")) { handleBack(); return null; }
                            if (n.equals("hashCode")) return System.identityHashCode(p);
                            if (n.equals("equals")) return a != null && a.length == 1 && p == a[0];
                            if (n.equals("toString")) return "Back";
                            return null;
                        }
                    });
            Object disp = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            Class.forName("android.window.OnBackInvokedDispatcher")
                    .getMethod("registerOnBackInvokedCallback", int.class, cb).invoke(disp, 0, proxy);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() { handleBack(); }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (ws instanceof VideoEditor) ((VideoEditor) ws).orientationChanged(c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    }

    private void handleBack() {
        if (working || (ws != null && ws.blocking())) return;
        if (ws != null) {
            if (!ws.back()) closeWorkspace();
        } else if (selecting) endSelection();
        else if (library) { library = false; showStart(); }
        else finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ws != null) ws.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ws != null) ws.onResume();
    }

    // ---------------------------------------------------------------- busy overlay
    private void setBusy(String s) {
        working = s != null;
        if (s == null) { if (busy.getParent() != null) host.removeView(busy); if(busyBlocker.getParent()!=null)host.removeView(busyBlocker); return; }
        busy.setText(s);
        if(busyBlocker.getParent()==null)host.addView(busyBlocker,new FrameLayout.LayoutParams(-1,-1));
        busyBlocker.bringToFront();
        if (busy.getParent() == null) host.addView(busy, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        busy.bringToFront();
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setBusy(null);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------- start screen
    static File projectsRoot(Activity a) {
        File f = new File(a.getFilesDir(), "projects");
        f.mkdirs();
        return f;
    }

    // state of the library screen
    private int filter;                 // 0 all, 1 photos, 2 videos
    private boolean sortByName;
    private final HashSet<String> selected = new HashSet<String>();
    private boolean selecting;
    private final Runnable hideSnack = new Runnable() { @Override public void run() { dismissSnack(); } };

    private android.content.SharedPreferences prefs() { return getSharedPreferences("ui", MODE_PRIVATE); }

    private void showStart() {
        if (selecting) library = true;   // selection lives on the Projects screen
        Crash.note(library ? "projects screen" : "start screen");
        if (ws != null) return;
        filter = prefs().getInt("filter", 0);
        sortByName = prefs().getBoolean("sortByName", false);
        host.removeAllViews();
        final File root = projectsRoot(this);
        ArrayList<Projects.Entry> all = Projects.list(root);
        chrome(!library);
        host.addView(library ? libraryScreen(root, all) : homeScreen(all), new FrameLayout.LayoutParams(-1, -1));
        if (snackBar != null && snackBar.getParent() == null) host.addView(snackBar, snackParams());
        if (splash != null && splash.getParent() == null) host.addView(splash, new FrameLayout.LayoutParams(-1, -1));
    }

    /** System bars and window background: true black on home, the editors' grey everywhere else. */
    private void chrome(boolean home) {
        int c = home ? Brand.BLACK : Ui.BG;
        if (splash != null) c = Brand.BLACK;
        Window w = getWindow();
        w.setStatusBarColor(c);
        w.setNavigationBarColor(c);
        host.setBackgroundColor(c);
    }

    // ---------------------------------------------------------------- home: the two modes up front
    private boolean library;          // false: home; true: the full Projects screen
    private boolean introPlayed;      // the wordmark's converge plays once per launch
    private View splash;
    private WordmarkView wordmark;
    static final int RECENT_MAX = 10;

    /**
     * Home is "here are the options", on one screen that doesn't scroll: the framed wordmark, one
     * card per mode, a short Recent strip and the studio mark. Pure black, one neutral card style,
     * orange for everything you can press, red/blue only in the wordmark. The full grid (filters,
     * sort, multi-select, Trash) lives on its own Projects screen. (Pattern from CapCut / VN: big
     * create entry points first, drafts as a short strip with "see all".)
     */
    private View homeScreen(ArrayList<Projects.Entry> all) {
        FitColumn col = new FitColumn(this);
        col.setBackgroundColor(Brand.BLACK);
        col.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10));

        // ---- top: menu only
        LinearLayout head = Ui.row(this);
        head.addView(new View(this), Ui.lp(0, 1, 1f));
        android.widget.ImageView menu = Ui.iconBtn(this, "more", new View.OnClickListener() { @Override public void onClick(View v) { appMenu(); } });
        menu.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        head.addView(menu, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(40)));
        col.addView(head, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 0.6f));   // the screen's slack is shared out:

        // ---- the title in a viewfinder frame
        LinearLayout frame = Ui.col(this);
        frame.setBackground(new Brand.Viewfinder());
        frame.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(14));
        wordmark = new WordmarkView(this);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.setMargins(0, -Ui.dp(10), 0, -Ui.dp(14));   // the PNG carries its own glow margin
        frame.addView(wordmark, wl);
        if (introPlayed) wordmark.rest();
        else if (splash == null) { introPlayed = true; wordmark.play(); }
        TextView tag = Ui.text(this, "NO ACCOUNT  ·  NO UPLOADS  ·  STAYS ON YOUR PHONE", 10, Brand.MUTED);
        tag.setLetterSpacing(0.14f);
        tag.setGravity(Gravity.CENTER);
        tag.setSingleLine(true);
        frame.addView(tag, new LinearLayout.LayoutParams(-1, -2));
        col.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));     // ...between title and modes

        // ---- the two modes
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(-1, -2);
        c1.topMargin = Ui.dp(16);
        col.addView(modeCard("video", "Video",
                "Cut, color, retime and title your footage. Add music, captions and sound.",
                "Timeline · Color · Speed · Titles · Audio",
                new View.OnClickListener() { @Override public void onClick(View v) { newVideoDialog(); } }), c1);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(-1, -2);
        c2.topMargin = Ui.dp(10);
        col.addView(modeCard("image", "Photo",
                "Layers, masks, curves and effects. Heal, remove and edit RAW photos.",
                "Layers · Masks · Effects · Retouch · RAW",
                new View.OnClickListener() { @Override public void onClick(View v) { pickImage(REQ_OPEN); } }), c2);

        TextView files = Ui.text(this, "Open a photo from Files (includes DNG)", 12.5f, Brand.MUTED);
        IconDrawable fi = new IconDrawable("folder", Brand.MUTED);
        fi.setBounds(0, 0, Ui.dp(15), Ui.dp(15));
        files.setCompoundDrawablesRelative(fi, null, null, null);
        files.setCompoundDrawablePadding(Ui.dp(8));
        files.setGravity(Gravity.CENTER);
        files.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickImageFile(REQ_OPEN); } });
        LinearLayout fr = Ui.row(this);
        fr.setGravity(Gravity.CENTER);
        fr.addView(files, new LinearLayout.LayoutParams(-2, Ui.dp(40)));
        col.addView(fr, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        col.optional(fr, 2);

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1.2f));   // ...and above Recent

        // ---- recent projects: one short strip
        if (!all.isEmpty()) {
            LinearLayout recentBlock = Ui.col(this);
            LinearLayout rh = Ui.row(this);
            TextView rt = Ui.text(this, "RECENT", 10.5f, Brand.MUTED);
            rt.setLetterSpacing(0.16f);
            rh.addView(rt, Ui.lp(0, -2, 1f));
            TextView see = Ui.text(this, "All projects · " + all.size(), 12.5f, Brand.ORANGE);
            see.setPadding(Ui.dp(10), Ui.dp(8), 0, Ui.dp(8));
            IconDrawable chev = new IconDrawable("next", Brand.ORANGE);
            chev.setBounds(0, 0, Ui.dp(13), Ui.dp(13));
            see.setCompoundDrawablesRelative(null, null, chev, null);
            see.setCompoundDrawablePadding(Ui.dp(2));
            see.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { library = true; showStart(); } });
            rh.addView(see);
            recentBlock.addView(rh, new LinearLayout.LayoutParams(-1, -2));
            ArrayList<Projects.Entry> recent = new ArrayList<Projects.Entry>(all);
            java.util.Collections.sort(recent, new Comparator<Projects.Entry>() {
                @Override public int compare(Projects.Entry x, Projects.Entry y) { return Long.compare(y.modified, x.modified); }
            });
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout strip = Ui.row(this);
            strip.setGravity(Gravity.TOP);
            for (int i = 0; i < Math.min(RECENT_MAX, recent.size()); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(108), -2);
                lp.rightMargin = Ui.dp(8);
                strip.addView(recentCard(recent.get(i)), lp);
            }
            hs.addView(strip);
            recentBlock.addView(hs, new LinearLayout.LayoutParams(-1, -2));
            col.addView(recentBlock, new LinearLayout.LayoutParams(-1, -2));
            col.optional(recentBlock, 1);
        }

        // ---- bottom bar: studio mark left, support right
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, Ui.dp(12), 0, 0);
        android.widget.ImageView bam = new android.widget.ImageView(this);
        bam.setImageBitmap(WordmarkView.asset(this, "brand/bam_logo.png"));
        bam.setContentDescription("Better App Mafia");
        bar.addView(bam, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        TextView by = Ui.text(this, "A BETTER APP\nMAFIA APP", 9, Brand.MUTED);
        by.setLetterSpacing(0.16f);
        by.setLineSpacing(0, 1.15f);
        by.setPadding(Ui.dp(8), 0, 0, 0);
        bar.addView(by, Ui.lp(0, -2, 1f));
        final String donate = Brand.donateUrl(getAssets());
        if (donate != null) {
            TextView d = Ui.text(this, "Support on Ko-fi", 12.5f, Brand.ORANGE);
            IconDrawable heart = new IconDrawable("heart", Brand.ORANGE);
            heart.setBounds(0, 0, Ui.dp(15), Ui.dp(15));
            d.setCompoundDrawablesRelative(heart, null, null, null);
            d.setCompoundDrawablePadding(Ui.dp(7));
            d.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ff8a1e),
                    Ui.round(0x00000000, 18, Brand.ORANGE_LINE), null));
            d.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(15), Ui.dp(8));
            d.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Brand.openUrl(MainActivity.this, donate); } });
            bar.addView(d);
        }
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    /**
     * Home column: exactly the screen's height, never scrolls. If the content doesn't fit (a small
     * phone, a large display-size setting), it hides the optional rows in priority order (Recent
     * first; it's also under ⋮ → All projects) instead of scrolling.
     */
    static final class FitColumn extends LinearLayout {
        private final java.util.ArrayList<View> optional = new java.util.ArrayList<View>();
        private final java.util.ArrayList<Integer> rank = new java.util.ArrayList<Integer>();
        FitColumn(android.content.Context c) { super(c); setOrientation(VERTICAL); }
        void optional(View v, int r) { optional.add(v); rank.add(r); }
        @Override protected void onMeasure(int w, int h) {
            if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) {
                // natural heights, measured once; visibility only changes when the decision does
                int fixed = getPaddingTop() + getPaddingBottom(), r1 = 0, r2 = 0;
                int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                for (int i = 0; i < getChildCount(); i++) {
                    View c = getChildAt(i);
                    int k = optional.indexOf(c);
                    if (k < 0 && c.getVisibility() == GONE) continue;
                    measureChildWithMargins(c, w, 0, free, 0);
                    LayoutParams lp = (LayoutParams) c.getLayoutParams();
                    int ch = c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                    if (k < 0) fixed += ch; else if (rank.get(k) == 1) r1 += ch; else r2 += ch;
                }
                int avail = MeasureSpec.getSize(h);
                boolean show2 = fixed + r2 <= avail;
                boolean show1 = show2 && fixed + r2 + r1 <= avail;
                for (int i = 0; i < optional.size(); i++) {
                    int want = (rank.get(i) == 1 ? show1 : show2) ? VISIBLE : GONE;
                    if (optional.get(i).getVisibility() != want) optional.get(i).setVisibility(want);
                }
            }
            super.onMeasure(w, h);
        }
    }

    /** One mode: orange icon tile, name, two lines on what it does, its tools; the whole card is the button. */
    private View modeCard(String icon, String title, String body, String tools, View.OnClickListener l) {
        Brand.Card bg = new Brand.Card();
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x26ff8a1e), bg, bg.mask()));
        card.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(14), Ui.dp(16));
        card.setOnClickListener(l);
        card.setContentDescription(title + ". " + body);

        android.widget.ImageView badge = new android.widget.ImageView(this);
        badge.setImageDrawable(new IconDrawable(icon, Brand.ORANGE));
        badge.setBackground(Ui.round(Brand.ORANGE_TINT, 14, Brand.ORANGE_LINE));
        badge.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52));
        blp.gravity = Gravity.TOP;
        card.addView(badge, blp);

        LinearLayout txt = Ui.col(this);
        txt.setPadding(Ui.dp(14), 0, Ui.dp(8), 0);
        TextView t = Ui.text(this, title, 22, 0xffffffff);
        t.setTypeface(Brand.display(getAssets(), 750));
        txt.addView(t);
        TextView d = Ui.text(this, body, 13.5f, Brand.BODY);
        d.setLineSpacing(0, 1.18f);
        d.setPadding(0, Ui.dp(4), 0, 0);
        txt.addView(d);
        TextView k = Ui.text(this, tools.toUpperCase(Locale.US), 9.5f, Brand.MUTED);
        k.setLetterSpacing(0.12f);
        k.setSingleLine(true);
        k.setEllipsize(android.text.TextUtils.TruncateAt.END);
        k.setPadding(0, Ui.dp(9), 0, 0);
        txt.addView(k);
        card.addView(txt, Ui.lp(0, -2, 1f));

        android.widget.ImageView go = new android.widget.ImageView(this);
        go.setImageDrawable(new IconDrawable("next", Brand.ORANGE));
        go.setPadding(Ui.dp(7), Ui.dp(7), Ui.dp(7), Ui.dp(7));
        go.setBackground(Ui.round(0x00000000, 16, Brand.ORANGE_LINE));
        go.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(go, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
        return card;
    }

    /** Small card for the Recent strip: thumbnail, name. Menu on ⋮ (and on long-press). */
    private View recentCard(final Projects.Entry e) {
        LinearLayout c = Ui.col(this);
        FrameLayout box = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Brand.CARD, 10, Brand.CARD_LINE));
        iv.setClipToOutline(true);
        File th = new File(e.dir, "thumb.jpg");
        if (th.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            iv.setImageBitmap(BitmapFactory.decodeFile(th.getPath(), o));
        } else {
            iv.setImageDrawable(new IconDrawable(e.video ? "film" : "image", Brand.CARD_LINE));
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(Ui.dp(32), Ui.dp(18), Ui.dp(32), Ui.dp(18));
        }
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        ImageView kind = new ImageView(this);
        kind.setImageDrawable(new IconDrawable(e.video ? "play" : "image", 0xffffffff));
        kind.setBackground(Ui.round(0xa0000000, 9, 0));
        kind.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18), Gravity.BOTTOM | Gravity.START);
        klp.setMargins(Ui.dp(5), 0, 0, Ui.dp(5));
        box.addView(kind, klp);
        ImageView more = new ImageView(this);
        more.setImageDrawable(new IconDrawable("more", 0xffffffff));
        more.setBackground(Ui.round(0x8c000000, 13, 0));
        more.setPadding(Ui.dp(5), Ui.dp(5), Ui.dp(5), Ui.dp(5));
        more.setContentDescription("Project options");
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { projectMenu(e); } });
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.TOP | Gravity.END);
        mlp.setMargins(0, Ui.dp(4), Ui.dp(4), 0);
        box.addView(more, mlp);
        c.addView(box, new LinearLayout.LayoutParams(-1, Ui.dp(68)));
        TextView t = Ui.text(this, e.name, 11.5f, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0);
        c.addView(t);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openProject(e.dir); } });
        c.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { projectMenu(e); return true; } });
        return c;
    }

    /** Studio mark on launch: fades in, holds, fades out over home. Tap skips. Cold starts only. */
    private void showSplash() {
        final FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(Brand.BLACK);   // the logo's edges are feathered, so it sits on true black with no square
        s.setClickable(true);
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(WordmarkView.asset(this, "brand/bam_logo.png"));
        logo.setContentDescription("Better App Mafia");
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.78f);
        s.addView(logo, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        logo.setAlpha(0f);
        logo.setScaleX(0.94f);
        logo.setScaleY(0.94f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        final Window w = getWindow();
        w.setStatusBarColor(Brand.BLACK);
        w.setNavigationBarColor(Brand.BLACK);
        splash = s;
        host.setBackgroundColor(Brand.BLACK);
        final Runnable end = new Runnable() {
            @Override public void run() {
                if (splash != s) return;
                splash = null;
                chrome(ws == null && !library);
                s.animate().alpha(0f).setDuration(380).withEndAction(new Runnable() {
                    @Override public void run() { if (s.getParent() != null) ((ViewGroup) s.getParent()).removeView(s); }
                }).start();
                if (wordmark != null && !introPlayed && ws == null) { introPlayed = true; wordmark.play(); }
            }
        };
        s.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { end.run(); } });
        host.postDelayed(end, 1700);
    }

    // ---------------------------------------------------------------- Projects screen (full grid)
    private View libraryScreen(final File root, ArrayList<Projects.Entry> all) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(96));
        sv.addView(col);

        // ---- header (normal, or the selection bar)
        LinearLayout head = Ui.row(this);
        head.setMinimumHeight(Ui.dp(56));
        if (selecting) {
            head.addView(Ui.iconBtn(this, "close", new View.OnClickListener() { @Override public void onClick(View v) { endSelection(); } }));
            TextView n = Ui.text(this, selected.size() + " selected", 18, Ui.TEXT);
            n.setPadding(Ui.dp(6), 0, 0, 0);
            head.addView(n, Ui.lp(0, -2, 1f));
            final ArrayList<Projects.Entry> shown = visible(all);
            TextView allBtn = Ui.text(this, "All", 14, Ui.ACCENT);
            allBtn.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
            allBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { for (Projects.Entry e : shown) selected.add(e.dir.getName()); showStart(); }
            });
            head.addView(allBtn);
            head.addView(Ui.iconBtn(this, "copy", new View.OnClickListener() { @Override public void onClick(View v) { duplicateSelected(); } }));
            head.addView(Ui.iconBtn(this, "trash", new View.OnClickListener() { @Override public void onClick(View v) { deleteSelected(); } }));
        } else {
            head.addView(Ui.iconBtn(this, "back", new View.OnClickListener() { @Override public void onClick(View v) { library = false; showStart(); } }));
            TextView title = Ui.text(this, "Projects", 22, Ui.TEXT);
            title.setTypeface(Brand.display(getAssets(), 650));
            title.setPadding(Ui.dp(4), 0, 0, 0);
            head.addView(title, Ui.lp(0, -2, 1f));
            head.addView(Ui.iconBtn(this, "more", new View.OnClickListener() { @Override public void onClick(View v) { appMenu(); } }));
        }
        col.addView(head);
        int trashCount = Projects.trashCount(root);
        if (!selecting && trashCount > 0) col.addView(Ui.chip(this, "trash", "Trash · " + trashCount, false, new View.OnClickListener() { @Override public void onClick(View v) { trashSheet(); } }));
        // ---- filter + sort
        int nPhoto = 0, nVideo = 0;
        for (Projects.Entry e : all) if (e.video) nVideo++; else nPhoto++;
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, Ui.dp(18), 0, Ui.dp(10));
        String[] fl = {"All " + all.size(), "Photos " + nPhoto, "Videos " + nVideo};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView c = Ui.chip(this, fl[i], filter == i, new View.OnClickListener() {
                @Override public void onClick(View v) { filter = k; prefs().edit().putInt("filter", k).apply(); showStart(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(6);
            bar.addView(c, lp);
        }
        bar.addView(new View(this), Ui.lp(0, 1, 1f));
        bar.addView(Ui.chip(this, "sort", sortByName ? "Name" : "Recent", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sortByName = !sortByName; prefs().edit().putBoolean("sortByName", sortByName).apply(); showStart(); }
        }));
        if (!all.isEmpty()) col.addView(bar);

        // ---- grid
        ArrayList<Projects.Entry> list = visible(all);
        if (list.isEmpty()) {
            TextView empty = Ui.text(this, all.isEmpty()
                    ? "No projects yet.\nGo back and start a video, or pick a photo."
                    : (filter == 1 ? "No photo projects." : "No video projects."), 14, Ui.DIM);
            empty.setPadding(Ui.dp(4), Ui.dp(28), Ui.dp(4), 0);
            empty.setLineSpacing(0, 1.25f);
            col.addView(empty);
        }
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.TOP);
                col.addView(row);
            }
            LinearLayout.LayoutParams lp = Ui.lp(0, -2, 1f);
            lp.setMargins(i % 2 == 0 ? 0 : Ui.dp(5), 0, i % 2 == 0 ? Ui.dp(5) : 0, Ui.dp(12));
            row.addView(projectCard(list.get(i)), lp);
        }
        if (list.size() % 2 == 1 && row != null) row.addView(new View(this), Ui.lp(0, 1, 1f));
        return sv;
    }

    private View gap(int dp) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(dp), 1)); return v; }

    private ArrayList<Projects.Entry> visible(ArrayList<Projects.Entry> all) {
        ArrayList<Projects.Entry> list = new ArrayList<Projects.Entry>();
        for (Projects.Entry e : all) if (filter == 0 || (filter == 1) != e.video) list.add(e);
        java.util.Collections.sort(list, new Comparator<Projects.Entry>() {
            @Override public int compare(Projects.Entry a, Projects.Entry b) {
                if (sortByName) return a.name.compareToIgnoreCase(b.name);
                return Long.compare(b.modified, a.modified);
            }
        });
        return list;
    }

    private View projectCard(final Projects.Entry e) {
        final File dir = e.dir;
        final boolean sel = selected.contains(dir.getName());
        LinearLayout c = Ui.col(this);
        c.setBackground(Ui.round(sel ? 0xff2a2620 : Ui.PANEL, 12, sel ? Ui.ACCENT : Ui.LINE));
        c.setPadding(Ui.dp(5), Ui.dp(5), Ui.dp(5), Ui.dp(9));
        FrameLayout thumbBox = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.PANEL2, 8, 0));
        iv.setClipToOutline(true);
        File th = new File(dir, "thumb.jpg");
        if (th.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            iv.setImageBitmap(BitmapFactory.decodeFile(th.getPath(), o));
        } else {
            iv.setImageDrawable(new IconDrawable(e.video ? "film" : "image", Ui.LINE));
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(Ui.dp(40), Ui.dp(40), Ui.dp(40), Ui.dp(40));
        }
        thumbBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (e.video) {   // kind badge
            LinearLayout badge = Ui.row(this);
            badge.setBackground(Ui.round(0xb0000000, 6, 0));
            badge.setPadding(Ui.dp(5), Ui.dp(3), Ui.dp(7), Ui.dp(3));
            badge.addView(Ui.icon(this, "play", 0xffffffff, 12));
            String d = e.detail.contains(" · ") ? e.detail.substring(e.detail.lastIndexOf(" · ") + 3) : "Video";
            TextView bt = Ui.text(this, d, 11, 0xffffffff);
            bt.setPadding(Ui.dp(4), 0, 0, 0);
            badge.addView(bt);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            blp.setMargins(Ui.dp(6), 0, 0, Ui.dp(6));
            thumbBox.addView(badge, blp);
        }
        if (selecting) {   // selection mark
            ImageView mark = new ImageView(this);
            mark.setBackground(sel ? Ui.round(Ui.ACCENT, 12, 0) : Ui.round(0x80000000, 12, 0xffffffff));
            if (sel) mark.setImageDrawable(new IconDrawable("check", 0xff111111));
            mark.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.TOP | Gravity.START);
            mlp.setMargins(Ui.dp(7), Ui.dp(7), 0, 0);
            thumbBox.addView(mark, mlp);
        } else {   // per-project menu, visible (not a hidden long-press)
            ImageView more = new ImageView(this);
            more.setImageDrawable(new IconDrawable("more", 0xffffffff));
            more.setBackground(Ui.round(0x8c000000, 16, 0));
            more.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
            more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { projectMenu(e); } });
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(32), Ui.dp(32), Gravity.TOP | Gravity.END);
            mlp.setMargins(0, Ui.dp(6), Ui.dp(6), 0);
            thumbBox.addView(more, mlp);
        }
        c.addView(thumbBox, new LinearLayout.LayoutParams(-1, Ui.dp(140)));
        TextView t = Ui.text(this, e.name, 14, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), 0);
        c.addView(t);
        String when = new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(e.modified));
        TextView s = Ui.text(this, when + (e.video ? "" : "  ·  " + e.detail), 11, Ui.DIM);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), 0);
        c.addView(s);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selecting) toggleSelect(dir);
                else openProject(dir);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!selecting) { selecting = true; selected.clear(); }
                toggleSelect(dir);
                return true;
            }
        });
        return c;
    }

    private void toggleSelect(File dir) {
        if (!selected.remove(dir.getName())) selected.add(dir.getName());
        if (selected.isEmpty()) selecting = false;
        showStart();
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        showStart();
    }

    private void projectMenu(final Projects.Entry e) {
        new Sheet(this, e.name + "  ·  " + (e.video ? "video" : "photo"))
                .item("open", "Open", new Runnable() { @Override public void run() { openProject(e.dir); } })
                .item("pencil", "Rename", new Runnable() { @Override public void run() { renameProject(e.dir, null); } })
                .item("folder", "Versions…", new Runnable() { @Override public void run() { versions(e.dir); } })
                .item("copy", "Duplicate", new Runnable() {
                    @Override public void run() {
                        try { Projects.duplicate(projectsRoot(MainActivity.this), e.dir); showStart(); snack("Duplicated", null, null); }
                        catch (Exception x) { Toast.makeText(MainActivity.this, "Couldn't duplicate: " + x.getMessage(), Toast.LENGTH_LONG).show(); }
                    }
                })
                .item("pulse", "Select several", new Runnable() { @Override public void run() { selecting = true; selected.clear(); selected.add(e.dir.getName()); showStart(); } })
                .danger("trash", "Delete", new Runnable() {
                    @Override public void run() {
                        ArrayList<File> one = new ArrayList<File>();
                        one.add(e.dir);
                        trash(one);
                    }
                })
                .show();
    }

    /** Rename dialog; onDone (optional) gets the new name. */
    void renameProject(final File dir, final Pickers.TextCb onDone) {
        Pickers.text(this, "Project name", Projects.name(dir), false, new Pickers.TextCb() {
            @Override public void text(String s) {
                if (s.trim().isEmpty()) return;
                Projects.setName(dir, s);
                if (onDone != null) onDone.text(s.trim());
                if (ws == null) showStart();
            }
        });
    }

    private void duplicateSelected() {
        File root = projectsRoot(this);
        int n = 0;
        for (String name : new ArrayList<String>(selected)) {
            try { Projects.duplicate(root, new File(root, name)); n++; } catch (Exception ignored) {}
        }
        selecting = false;
        selected.clear();
        showStart();
        snack("Duplicated " + n + (n == 1 ? " project" : " projects"), null, null);
    }

    private void deleteSelected() {
        File root = projectsRoot(this);
        ArrayList<File> dirs = new ArrayList<File>();
        for (String name : selected) dirs.add(new File(root, name));
        selecting = false;
        selected.clear();
        trash(dirs);
    }

    /** Trash survives restarts. Report actual moves and failed restores rather than optimistic counts. */
    private void trash(ArrayList<File> dirs) {
        final File root = projectsRoot(this);
        final ArrayList<File> moved = new ArrayList<File>();
        for (File d : dirs) {
            File t = Projects.trash(root, d);
            if (t != null) moved.add(t);
        }
        showStart();
        final int failed=dirs.size()-moved.size();
        snack("Moved "+moved.size()+" to Trash"+(failed>0?" · "+failed+" couldn't be moved":""), "Undo", new Runnable() {
            @Override public void run() {
                int restored=0;for (File t : moved) if(Projects.restore(root, t))restored++;
                showStart();
                if(restored!=moved.size())snack("Restored "+restored+" of "+moved.size()+". Check Trash for the remaining projects.",null,null);
            }
        });
    }

    private void trashSheet() {
        final File root=projectsRoot(this);
        final ArrayList<Projects.Entry> entries=Projects.listTrash(root);
        java.util.Collections.sort(entries,new Comparator<Projects.Entry>(){public int compare(Projects.Entry a,Projects.Entry b){return Long.compare(Projects.deletedAt(b.dir),Projects.deletedAt(a.dir));}});
        Sheet sheet=new Sheet(this,"Trash · kept until you delete permanently");
        for(final Projects.Entry e:entries)sheet.item(e.video?"film":"image",e.name,"Deleted "+new SimpleDateFormat("MMM d, HH:mm",Locale.US).format(new Date(Projects.deletedAt(e.dir))),new Runnable(){public void run(){
            new Sheet(MainActivity.this,e.name)
                .item("undo","Restore project","Returns it to Projects, including its saved versions",new Runnable(){public void run(){boolean ok=Projects.restore(root,e.dir);showStart();snack(ok?"Project restored":"Couldn't restore project. It is still in Trash.",null,null);}})
                .danger("trash","Delete permanently…",new Runnable(){public void run(){new AlertDialog.Builder(MainActivity.this).setTitle("Delete permanently?").setMessage("“"+e.name+"” and its saved versions will be removed. This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                    setBusy("Deleting project…");new Thread(new Runnable(){public void run(){final boolean ok=ProjectFiles.remove(e.dir);runOnUiThread(new Runnable(){public void run(){setBusy(null);showStart();snack(ok?"Permanently deleted":"Some files couldn't be removed. The project remains in Trash.",null,null);}});}}).start();
                }}).show();}}).show();
        }});
        if(entries.isEmpty())sheet.item("info","Trash is empty",null);
        sheet.show();
    }

    void versions(final File dir) {
        final ArrayList<ProjectVersions.Entry> entries=ProjectVersions.list(dir);
        Sheet sheet=new Sheet(this,"Versions · "+Projects.name(dir));
        sheet.item("plus","Save a named version…","Keeps the current edit and local photo assets; source videos remain linked",new Runnable(){public void run(){
            Pickers.text(MainActivity.this,"Version name",new SimpleDateFormat("MMM d, HH:mm",Locale.US).format(new Date()),false,new Pickers.TextCb(){public void text(final String name){
                if(name.trim().isEmpty()){snack("Give this version a name.",null,null);return;}
                if(ws!=null&&!ws.saveForVersion()){snack("Couldn't save the current edit. No version was created.",null,null);return;}
                setBusy("Saving recovery version…");
                new Thread(new Runnable(){public void run(){try{ProjectVersions.create(dir,name);runOnUiThread(new Runnable(){public void run(){setBusy(null);snack("Saved version “"+name.trim()+"”",null,null);}});}catch(final Exception e){fail("Couldn't save version: "+e.getMessage());}}}).start();
            }});
        }}).section(entries.isEmpty()?"No saved versions yet":"Saved versions · newest first");
        for(final ProjectVersions.Entry entry:entries)sheet.item("folder",entry.name,new SimpleDateFormat("MMM d, yyyy · HH:mm",Locale.US).format(new Date(entry.created)),new Runnable(){public void run(){versionActions(dir,entry);}});
        sheet.show();
    }

    private void versionActions(final File dir,final ProjectVersions.Entry entry) {
        new Sheet(this,entry.name)
            .item("undo","Restore as a new project","Your current project stays unchanged. Media links still need their original files.",new Runnable(){public void run(){
                if(ws!=null&&!ws.saveForVersion()){snack("Couldn't save the current edit. Restore was stopped.",null,null);return;}
                setBusy("Checking and restoring version…");
                new Thread(new Runnable(){public void run(){try{
                    final File copy=ProjectVersions.restoreCopy(projectsRoot(MainActivity.this),entry);
                    Projects.setName(copy,Projects.name(dir)+" · "+entry.name);
                    runOnUiThread(new Runnable(){public void run(){setBusy(null);openRestored(copy);}});
                }catch(final Exception e){fail("Couldn't restore version: "+e.getMessage());}}}).start();
            }})
            .item("pencil","Rename version…",new Runnable(){public void run(){Pickers.text(MainActivity.this,"Version name",entry.name,false,new Pickers.TextCb(){public void text(String name){try{ProjectVersions.rename(entry,name);versions(dir);}catch(Exception e){snack(e.getMessage(),null,null);}}});}})
            .danger("trash","Delete this version…",new Runnable(){public void run(){new AlertDialog.Builder(MainActivity.this).setTitle("Delete saved version?").setMessage("Only “"+entry.name+"” will be removed. The working project stays available.").setNegativeButton("Cancel",null).setPositiveButton("Delete version",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                setBusy("Deleting saved version…");new Thread(new Runnable(){public void run(){final boolean ok=ProjectFiles.remove(entry.dir);runOnUiThread(new Runnable(){public void run(){setBusy(null);snack(ok?"Version deleted":"Couldn't remove all version files.",null,null);}});}}).start();
            }}).show();}}).show();
    }

    private void openRestored(final File copy) {
        if(ws==null){openProject(copy);return;}
        final Workspace old=ws;final boolean[] done={false};setBusy("Opening restored copy…");
        Runnable next=new Runnable(){public void run(){if(done[0]||ws!=old)return;done[0]=true;old.close();ws=null;editor=null;openProject(copy);}};
        old.flush(next);host.postDelayed(next,1500);
    }

    private void appMenu() {
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        Sheet menu = new Sheet(this, Brand.NAME + " " + ver);
        menu
                .section("Projects")
                .item("folder", "All projects", "Filter, sort, select several", new Runnable() { @Override public void run() { library = true; showStart(); } })
                .item("trash", "Trash · "+Projects.trashCount(projectsRoot(this)), "Restore deleted projects, even after restarting", new Runnable() { @Override public void run() { trashSheet(); } })
                .item("folder", "Open photo from Files", "Includes DNG decoded by this device", new Runnable() { @Override public void run() { pickImageFile(REQ_OPEN); } })
                .section("Help & diagnostics")
                .item("mail", "Contact the developer", Brand.CONTACT, new Runnable() { @Override public void run() { Brand.email(MainActivity.this, Brand.NAME + " " + getVersionName(), null); } })
                .item("pulse", "Run video self-test", "Checks export on this phone; copy or share the diagnostic report", new Runnable() { @Override public void run() { runSelfTest(); } })
                .item("info", "About", "Photo layers, masks and retouching; video timeline, grading and visualizers", new Runnable() {
                    @Override public void run() { Toast.makeText(MainActivity.this, Brand.NAME + " " + getVersionName() + " by Better App Mafia · no account, no uploads, everything stays on this phone", Toast.LENGTH_LONG).show(); }
                })
                ;
        final String donate = Brand.donateUrl(getAssets());
        if (donate != null) menu.item("heart", "Support on Ko-fi", "Tips help keep it free, with no ads", new Runnable() { @Override public void run() { Brand.openUrl(MainActivity.this, donate); } });
        menu.show();
    }

    String getVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** After an export: snackbar with Share (system share sheet) and Open (gallery / player). */
    void exported(String msg, final Uri uri, final String mime) {
        snack(msg, "Share", new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Share")); } catch (Exception e) { Toast.makeText(MainActivity.this, "Nothing to share with", Toast.LENGTH_SHORT).show(); }
            }
        }, "Open", new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(i); } catch (Exception e) { Toast.makeText(MainActivity.this, "No app to open it", Toast.LENGTH_SHORT).show(); }
            }
        });
    }

    // ---------------------------------------------------------------- snackbar
    private FrameLayout.LayoutParams snackParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.dp(14));
        return lp;
    }

    /**
     * Bottom message with up to two actions (Material snackbar). Stays 5 s (8 s with actions).
     * Works over the library and over the editors (host is the root FrameLayout).
     */
    void snack(String msg, String action, Runnable r) { snack(msg, action, r, null, null); }

    void snack(String msg, String a1, final Runnable r1, String a2, final Runnable r2) {
        dismissSnack();
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(0xf2262930, 10, Ui.LINE));
        bar.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        bar.setMinimumHeight(Ui.dp(52));
        bar.setElevation(Ui.dp(6));
        TextView m = Ui.text(this, msg, 14, Ui.TEXT);
        bar.addView(m, Ui.lp(0, -2, 1f));
        String[] labels = {a1, a2};
        final Runnable[] runs = {r1, r2};
        for (int i = 0; i < 2; i++) {
            if (labels[i] == null) continue;
            final int k = i;
            TextView b = Ui.text(this, labels[i].toUpperCase(Locale.US), 13, Ui.ACCENT);
            b.setLetterSpacing(0.05f);
            b.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismissSnack(); if (runs[k] != null) runs[k].run(); }
            });
            bar.addView(b);
        }
        snackBar = bar;
        host.addView(bar, snackParams());
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(24));
        bar.animate().alpha(1f).translationY(0).setDuration(160).start();
        host.removeCallbacks(hideSnack);
        host.postDelayed(hideSnack, a1 != null ? 8000 : 4000);
    }

    private View snackBar;

    private void dismissSnack() {
        host.removeCallbacks(hideSnack);
        if (snackBar != null && snackBar.getParent() != null) ((ViewGroup) snackBar.getParent()).removeView(snackBar);
        snackBar = null;
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }

    private void newVideoDialog() {
        final int[] sel = {0, 1, 2};   // aspect, resolution, fps
        final LinearLayout box = Ui.col(this);
        box.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        final Runnable[] build = new Runnable[1];
        build[0] = new Runnable() {
            @Override public void run() {
                box.removeAllViews();
                String[][] names = {
                        {"16:9 · YouTube", "9:16 · Reels/TikTok", "1:1 · square", "4:5 · feed"},
                        VProject.RES_NAMES,
                        {"24", "25", "30", "60"}};
                String[] labels = {"Shape", "Resolution (short side follows the shape)", "Frame rate"};
                for (int g = 0; g < 3; g++) {
                    box.addView(Ui.label(MainActivity.this, labels[g]));
                    View[] v = new View[names[g].length];
                    for (int i = 0; i < v.length; i++) {
                        final int gg = g, k = i;
                        v[i] = Ui.chip(MainActivity.this, names[g][i], sel[g] == i, new View.OnClickListener() {
                            @Override public void onClick(View x) { sel[gg] = k; build[0].run(); }
                        });
                    }
                    box.addView(Ui.strip(MainActivity.this, v));
                }
                TextView hint = Ui.text(MainActivity.this, "You can change all of this later under Format.", 12, Ui.DIM);
                hint.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), 0);
                box.addView(hint);
            }
        };
        build[0].run();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("New video").setView(box)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        VProject p = new VProject();
                        p.aspectIdx = sel[0];
                        p.resIdx = sel[1];
                        p.fpsIdx = sel[2];
                        File dir = new File(projectsRoot(MainActivity.this), "v" + System.currentTimeMillis());
                        dir.mkdirs();
                        Projects.init(dir, "Video " + VProject.ASPECT_NAMES[p.aspectIdx]);
                        openVideo(dir, p);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void openVideo(File dir, VProject p) {
        Crash.note("open video");
        host.removeAllViews();
        chrome(false);
        VideoEditor ve = new VideoEditor(this, dir, p);
        editor = null;
        ws = ve;
        host.addView(ve.rootView(), new FrameLayout.LayoutParams(-1, -1));
        ve.save();
    }

    // ---------------------------------------------------------------- picking + loading
    /** The system photo picker (Android 13+: gallery grid, albums, no permission); Files on older phones. */
    void pickImage(int req) {
        if (working) return;
        if (Build.VERSION.SDK_INT >= 33) {
            Intent i = new Intent("android.provider.action.PICK_IMAGES");
            i.setType("image/*");
            try { startActivityForResult(i, req); return; } catch (Exception ignored) {}
        }
        pickImageFile(req);
    }

    /** The Files app: any folder, cloud drives, and RAW/DNG files the photo picker may not list. */
    void pickImageFile(int req) {
        if (working) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "image/x-adobe-dng"});
        try { startActivityForResult(i, req); } catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        if (req == VideoEditor.REQ_MEDIA || req == VideoEditor.REQ_AUDIO || req == VideoEditor.REQ_OVERLAY || req == VideoEditor.REQ_SRT
                || req == VideoEditor.REQ_RELINK || req == VideoEditor.REQ_RELINK_DIR) {
            if (ws != null) ws.mediaPicked(req, data);
            return;
        }
        if (data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_OPEN) newProjectFrom(uri);
        else if (req == REQ_ADD_LAYER && editor != null) addLayerFrom(uri);
        else if (req == REQ_FONT && ws != null) importFont(uri);
        else if (req == REQ_LUT && ws != null) importLut(uri);
    }

    private static String ext(String mime) {
        if (mime == null) return ".img";
        if (mime.contains("png")) return ".png";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("heic") || mime.contains("heif")) return ".heic";
        if (mime.contains("dng")) return ".dng";
        return ".jpg";
    }

    /** Copies the file into the project, decodes a preview, and returns {info, preview}. */
    private Object[] importSource(Uri uri, File dir, String base, int previewSide) throws Exception {
        String name = base + ext(getContentResolver().getType(uri));
        File f = new File(dir, name);
        Loader.copy(getContentResolver(), uri, f);
        int[] full = new int[2];
        Bitmap prev = Loader.decode(f, previewSide, full);
        Loader.Info info = Loader.probe(f);
        // the decoder's header is the truth for size (a raw's bounds probe can report its embedded preview)
        if (full[0] > 0 && full[1] > 0) { info.w = full[0]; info.h = full[1]; }
        if (info.w <= 0 || info.h <= 0) { info.w = prev.getWidth(); info.h = prev.getHeight(); }
        // trust the decoder's orientation: if the preview is landscape and the probe says portrait, swap
        if ((prev.getWidth() > prev.getHeight()) != (info.w > info.h) && prev.getWidth() != prev.getHeight()) {
            int t = info.w; info.w = info.h; info.h = t;
        }
        return new Object[]{name, info, prev};
    }

    private void newProjectFrom(final Uri uri) {
        setBusy("Opening photo…");
        final File dir = new File(projectsRoot(this), "p" + System.currentTimeMillis());
        dir.mkdirs();
        String nm = Projects.baseName(displayName(uri, null));
        Projects.init(dir, nm == null || nm.isEmpty() ? "Photo" : nm);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object[] r = importSource(uri, dir, "src_1", BASE_PREVIEW);
                    Loader.Info info = (Loader.Info) r[1];
                    final Doc d = new Doc();
                    d.imgW = info.w;
                    d.imgH = info.h;
                    d.nextId = 2;
                    d.resetCrop();
                    Layer l = new Layer();
                    l.id = 1;
                    l.type = Layer.IMAGE;
                    l.name = "Photo";
                    l.isBase = true;
                    l.src = (String) r[0];
                    l.srcW = info.w;
                    l.srcH = info.h;
                    l.cx = info.w / 2f;
                    l.cy = info.h / 2f;
                    l.preview = (Bitmap) r[2];
                    d.layers.add(l);
                    runOnUiThread(new Runnable() { @Override public void run() { setBusy(null); openEditor(dir, d); } });
                } catch (Throwable t) {
                    deleteDir(dir);
                    fail("Couldn't open that image: " + t.getMessage());
                }
            }
        }).start();
    }

    private void addLayerFrom(final Uri uri) {
        setBusy("Adding photo…");
        final Editor ed = editor;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Object[] r = importSource(uri, ed.dir, "src_" + System.currentTimeMillis(), LAYER_PREVIEW);
                    final Loader.Info info = (Loader.Info) r[1];
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setBusy(null);
                            if (editor == ed) ed.addImageLayer((String) r[0], info.w, info.h, (Bitmap) r[2]);
                        }
                    });
                } catch (Throwable t) {
                    fail("Couldn't add that image: " + t.getMessage());
                }
            }
        }).start();
    }

    private void openProject(final File dir) {
        setBusy("Loading project…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (isVideoProject(dir)) {
                        final ProjectIO.Loaded ld = ProjectIO.read(dir, "video.json", ProjectIO.VIDEO_VERSION);
                        final VProject vp = VProject.fromJson(ld.json);
                        ProjectIO.sanitize(vp, ld);
                        loadedNote(dir, "video.json", ld);
                        runOnUiThread(new Runnable() {
                            @Override public void run() { setBusy(null); openVideo(dir, vp); loadedToast(ld); }
                        });
                        return;
                    }
                    final ProjectIO.Loaded ld = ProjectIO.read(dir, "doc.json", ProjectIO.DOC_VERSION);
                    final Doc d = Doc.fromJson(ld.json);
                    ProjectIO.sanitize(d, ld);
                    d.syncRetouch();   // older retouch layers adopt the photo below them, nothing moves
                    HashSet<String> used = new HashSet<String>();
                    for (Layer l : d.layers) {
                        if (l.type == Layer.IMAGE && l.src != null) {
                            used.add(l.src);
                            File sf = new File(dir, l.src);
                            if (!sf.exists()) ld.fixes.add(l.name + ": its photo file is missing");
                            l.preview = Loader.decode(sf, l.isBase ? BASE_PREVIEW : LAYER_PREVIEW);
                        }
                        if (l.type == Layer.TEXT) TextRender.refreshPreview(l);
                        // 3.1+ names the file in the layer; older projects use mask_<id>.png
                        String mn = l.maskFile != null ? l.maskFile : "mask_" + l.id + ".png";
                        File mf = new File(dir, mn);
                        if (mf.exists() && l.mask == null) {
                            l.mask = l.maskK > 0 ? Loader.loadMask(mf, -1, -1) : Loader.loadMask(mf, d.maskW(), d.maskH());
                            if (l.mask != null) { l.maskFile = mn; l.maskSavedVer = l.maskVer; }
                        }
                        if (l.mask == null && l.hadMask) ld.fixes.add(l.name + ": its mask file is missing (the layer shows unmasked)");
                        if (l.mask == null) { l.maskK = 0; l.maskFile = null; }
                    }
                    // after the assets: a project with missing files is reported and doesn't overwrite the good backup
                    loadedNote(dir, "doc.json", ld);
                    // drop source copies of layers deleted last session, but never one the backup
                    // (or an interrupted save) still needs: recovering it must bring its photos back too
                    used.addAll(ProjectIO.sourcesIn(new File(dir, "doc.json.bak")));
                    used.addAll(ProjectIO.sourcesIn(new File(dir, "doc.json.tmp")));
                    used.addAll(ProjectIO.sourcesIn(new File(dir, "doc.json")));
                    File[] fs = dir.listFiles();
                    if (fs != null) for (File f : fs) if (f.getName().startsWith("src_") && !used.contains(f.getName())) f.delete();
                    runOnUiThread(new Runnable() { @Override public void run() { setBusy(null); openEditor(dir, d); loadedToast(ld); } });
                } catch (Throwable t) {
                    Crash.note("load failed: " + t);
                    fail("Couldn't load project: " + t.getMessage());
                }
            }
        }).start();
    }

    /** A video project, also when only its .tmp/.bak survived. */
    private static boolean isVideoProject(File dir) {
        for (String n : new String[]{"video.json", "video.json.tmp", "video.json.bak"}) if (new File(dir, n).exists()) return true;
        return false;
    }

    /** Logs repairs; keeps a backup of a file that loaded cleanly. Worker thread. */
    private void loadedNote(File dir, String name, ProjectIO.Loaded ld) {
        if (ld.recovered()) Crash.note("loaded " + name + " from " + ld.from);
        for (String f : ld.fixes) Crash.note("repaired: " + f);
        if (!ld.recovered() && ld.fixes.isEmpty()) ProjectIO.keepBackup(dir, name);
    }

    private void loadedToast(ProjectIO.Loaded ld) {
        String m = null;
        if (ld.recovered()) m = ld.from.equals(".tmp") ? "Recovered your last unsaved changes." : "The project file was damaged. Opened the last good copy.";
        else if (!ld.fixes.isEmpty()) m = "Repaired " + ld.fixes.size() + " damaged setting" + (ld.fixes.size() == 1 ? "" : "s") + " in this project.";
        if (m != null) Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void openEditor(File dir, Doc d) {
        Crash.note("open photo");
        host.removeAllViews();
        chrome(false);
        editor = new Editor(this, dir, d);
        ws = editor;
        host.addView(editor.root, new FrameLayout.LayoutParams(-1, -1));
        editor.save();
    }

    void closeWorkspace() { closeWorkspace(null); }

    /** 3.13: leave the open editor, then start a photo project from uri (a frame saved from a video). */
    void closeWorkspaceThenOpenPhoto(final Uri uri) {
        closeWorkspace(new Runnable() { @Override public void run() { newProjectFrom(uri); } });
    }

    void closeWorkspace(final Runnable then) {
        if (ws == null || ws.blocking()) return;
        final Workspace w = ws;
        w.save();
        final boolean[] done = {false};
        final Runnable leave = new Runnable() {
            @Override public void run() {
                if (done[0] || ws != w) return;
                done[0] = true;
                w.close();
                ws = null;
                editor = null;
                showStart();
                if (then != null) then.run();
            }
        };
        // the photo thumbnail renders on the GL thread; leave once that has run (or after 1.5 s)
        w.flush(leave);
        host.postDelayed(leave, 1500);
    }

    // ---------------------------------------------------------------- LUTs
    void pickLut() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_LUT); } catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    private void importLut(Uri uri) {
        String name = displayName(uri, "look_" + System.currentTimeMillis() + ".cube");
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!name.toLowerCase(Locale.US).endsWith(".cube")) name = name + ".cube";
        File dest = new File(Lut.dir, name);
        try {
            Loader.copy(getContentResolver(), uri, dest);
            Lut.parse(dest, name);
            if (ws != null) ws.lutImported(name);
            Toast.makeText(this, "LUT added: " + Lut.displayName(name), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            dest.delete();
            Toast.makeText(this, "Couldn't read that LUT: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    String displayName(Uri uri, String fallback) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return name == null ? fallback : name;
    }

    // ---------------------------------------------------------------- fonts
    void pickFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"});
        try { startActivityForResult(i, REQ_FONT); } catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    private void importFont(Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        if (name == null) name = "font_" + System.currentTimeMillis() + ".ttf";
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        String low = name.toLowerCase(Locale.US);
        if (!low.endsWith(".ttf") && !low.endsWith(".otf")) name = name + ".ttf";
        File dest = new File(TextRender.fontsDir, name);
        try {
            Loader.copy(getContentResolver(), uri, dest);
            android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(dest);
            if (tf == null) throw new Exception("not a font");
            if (ws != null) ws.fontImported("file:" + name);
            Toast.makeText(this, "Font added: " + name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            dest.delete();
            Toast.makeText(this, "That file isn't a usable TrueType/OpenType font.", Toast.LENGTH_LONG).show();
        }
    }

}
