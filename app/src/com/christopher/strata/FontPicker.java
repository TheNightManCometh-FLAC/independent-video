package com.christopher.strata;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 3.15 font browser for text masks and titles: the bundled library by mood (Impact, Sans, Serif,
 * Script, Retro, Mono, Grunge), the phone's own families and imported files, each row drawn in its
 * own font with your text as the sample. Search by name; tap the star to keep a favourite (a
 * visible control, plus long press); Recent remembers the last picks.
 */
final class FontPicker {
    private FontPicker() {}

    private static final String ALL = "All", FAV = "Favorites", SYSTEM = "Phone", IMPORTED = "Imported";

    static void show(final Activity a, final String current, String sample, final Pickers.Pick cb) {
        final Catalog cat = new Catalog(a, "fonts");
        final String smp = sample == null || sample.trim().isEmpty() ? "The quick brown fox 2026" : firstLine(sample);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.col(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL);
        float r = Ui.dp(18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(0, Ui.dp(10), 0, 0);
        TextView title = Ui.text(a, "Font", 15, Ui.TEXT);
        title.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(6));
        root.addView(title);
        final EditText search = new EditText(a);
        search.setHint("Search fonts");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setTextSize(14);
        search.setBackground(Ui.round(Ui.PANEL2, 10, 0));
        search.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(6));
        root.addView(search, slp);
        final LinearLayout chips = Ui.row(a);
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(a);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(chips);
        chips.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(4));
        root.addView(cs);
        final LinearLayout list = Ui.col(a);
        final ScrollView sv = new ScrollView(a) {
            @Override protected void onMeasure(int w, int h) {
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY));
            }
        };
        sv.addView(list);
        root.addView(sv);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        final String[] tab = {cat.favorites.isEmpty() && cat.recent.isEmpty() ? ALL : FAV};
        Fonts.F cf = Fonts.byKey(current);
        if (cf != null && tab[0].equals(ALL)) tab[0] = cf.cat;
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                chips.removeAllViews();
                ArrayList<String> tabs = new ArrayList<String>();
                tabs.add(FAV); tabs.add(ALL); tabs.addAll(Arrays.asList(Fonts.CATS)); tabs.add(SYSTEM);
                if (!TextRender.importedFonts().isEmpty()) tabs.add(IMPORTED);
                for (final String t : tabs) {
                    chips.addView(Ui.chip(a, t, t.equals(tab[0]), new View.OnClickListener() {
                        @Override public void onClick(View v) { tab[0] = t; rebuild[0].run(); sv.scrollTo(0, 0); }
                    }));
                }
                list.removeAllViews();
                String q = search.getText().toString().trim().toLowerCase();
                ArrayList<String> keys = new ArrayList<String>();
                if (!q.isEmpty()) {
                    for (Fonts.F f : Fonts.ALL) if (f.name.toLowerCase().contains(q)) keys.add(f.key());
                    for (String k : TextRender.BUILTIN) if (TextRender.displayName(k).toLowerCase().contains(q)) keys.add(k);
                    for (String k : TextRender.importedFonts()) if (TextRender.displayName(k).toLowerCase().contains(q)) keys.add(k);
                } else if (tab[0].equals(FAV)) {
                    if (!cat.favorites.isEmpty()) { header(a, list, "Favorites"); for (String k : cat.favorites) row(a, d, list, k, current, smp, cat, cb, rebuild[0]); }
                    if (!cat.recent.isEmpty()) { header(a, list, "Recent"); for (String k : cat.recent) row(a, d, list, k, current, smp, cat, cb, rebuild[0]); }
                    if (cat.favorites.isEmpty() && cat.recent.isEmpty())
                        header(a, list, "Fonts you pick show up here. Tap ☆ on any font to keep it.");
                } else if (tab[0].equals(ALL)) {
                    for (String c : Fonts.CATS) { header(a, list, c); for (Fonts.F f : Fonts.inCat(c)) row(a, d, list, f.key(), current, smp, cat, cb, rebuild[0]); }
                    header(a, list, "Phone");
                    for (String k : TextRender.BUILTIN) row(a, d, list, k, current, smp, cat, cb, rebuild[0]);
                } else if (tab[0].equals(SYSTEM)) {
                    keys.addAll(Arrays.asList(TextRender.BUILTIN));
                } else if (tab[0].equals(IMPORTED)) {
                    keys.addAll(TextRender.importedFonts());
                } else for (Fonts.F f : Fonts.inCat(tab[0])) keys.add(f.key());
                for (String k : keys) row(a, d, list, k, current, smp, cat, cb, rebuild[0]);
                TextView imp = Ui.text(a, "+  Import a font file (.ttf / .otf)…", 14, Ui.ACCENT);
                imp.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(18));
                imp.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { d.dismiss(); cb.importNew(); }
                });
                list.addView(imp);
                TextView lic = Ui.text(a, "Bundled fonts: Google Fonts under the SIL Open Font License / Apache 2.0.", 11, Ui.DIM);
                lic.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(24));
                list.addView(lic);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { rebuild[0].run(); }
        });
        rebuild[0].run();
        d.show();
    }

    private static String firstLine(String s) {
        String t = s.trim();
        int n = t.indexOf('\n');
        if (n > 0) t = t.substring(0, n);
        return t.length() > 28 ? t.substring(0, 27) + "…" : t;
    }

    private static void header(Activity a, LinearLayout list, String s) {
        TextView t = Ui.text(a, s, 12, Ui.DIM);
        t.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(4));
        list.addView(t);
    }

    private static void row(final Activity a, final Dialog d, LinearLayout list, final String key, String current,
                            String sample, final Catalog cat, final Pickers.Pick cb, final Runnable rebuild) {
        Typeface tf = TextRender.typeface(key, false, false);
        boolean on = key.equals(current);
        LinearLayout r = Ui.row(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        r.setBackground(Ui.round(on ? 0x33e9a23b : 0, 10, 0));
        LinearLayout txt = Ui.col(a);
        TextView name = Ui.text(a, TextRender.displayName(key), 12, on ? Ui.ACCENT : Ui.DIM);
        TextView s = Ui.text(a, sample, 24, Ui.TEXT);
        s.setTypeface(tf);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(s);
        r.addView(txt, Ui.lp(0, -2, 1f));
        final boolean fav = cat.fav(key);
        TextView star = Ui.text(a, fav ? "★" : "☆", 22, fav ? Ui.ACCENT : Ui.DIM);
        star.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        star.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cat.toggle(key); rebuild.run(); }
        });
        r.addView(star);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cat.used(key); d.dismiss(); cb.picked(key); }
        });
        r.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { cat.toggle(key); rebuild.run(); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(8), 0, Ui.dp(8), 0);
        list.addView(r, lp);
    }
}
