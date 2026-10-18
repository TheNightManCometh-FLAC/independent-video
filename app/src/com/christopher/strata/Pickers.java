package com.christopher.strata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Small reusable dialogs: colour, text entry, font and LUT choice. */
final class Pickers {
    interface Pick {
        void picked(String key);
        void importNew();
    }

    interface ColorCb {
        void begin();
        void color(int argb);
        void done();
    }

    interface TextCb { void text(String s); }

    static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    static void text(Activity a, String title, String initial, boolean multiLine, final TextCb cb) {
        final EditText e = new EditText(a);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if (multiLine) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        e.setInputType(type);
        if (multiLine) e.setMinLines(2);
        e.setText(initial);
        e.setSelectAllOnFocus(true);
        dialog(a).setTitle(title).setView(e)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString();
                        if (!s.trim().isEmpty()) cb.text(s);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** HSV sliders + hex entry. cb.begin() fires once before the first change. */
    static void color(final Activity a, String title, int initial, final ColorCb cb) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        LinearLayout box = Ui.col(a);
        box.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        final View swatch = new View(a);
        swatch.setBackground(Ui.round(initial, 8, Ui.LINE));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(48));
        slp.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(6));
        box.addView(swatch, slp);
        final TextView hex = Ui.text(a, String.format(Locale.US, "#%06X", initial & 0xffffff), 13, Ui.DIM);
        hex.setPadding(Ui.dp(16), 0, 0, Ui.dp(4));
        box.addView(hex);
        final boolean[] began = {false};
        final Runnable apply = new Runnable() {
            @Override public void run() {
                int c = Color.HSVToColor(hsv);
                if (!began[0]) { began[0] = true; cb.begin(); }
                cb.color(c);
                swatch.setBackground(Ui.round(c, 8, Ui.LINE));
                hex.setText(String.format(Locale.US, "#%06X", c & 0xffffff));
            }
        };
        String[] names = {"Hue", "Saturation", "Brightness"};
        float[] max = {360, 1, 1};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Ui.Slider s = new Ui.Slider(a, names[i], 0, max[i], hsv[i], hsv[i], new Ui.Slider.Listener() {
                @Override public void begin() {}
                @Override public void change(float v) { hsv[k] = v; apply.run(); }
            });
            if (i == 0) s.display(1, 0, "°"); else s.display(100, 0, "%");
            box.addView(s);
        }
        TextView hexBtn = Ui.button(a, "Enter hex…", new View.OnClickListener() {
            @Override public void onClick(View v) {
                text(a, "Hex color", String.format(Locale.US, "%06X", Color.HSVToColor(hsv) & 0xffffff), false, new TextCb() {
                    @Override public void text(String s) {
                        try {
                            int c = (int) Long.parseLong(s.replace("#", "").trim(), 16) | 0xff000000;
                            Color.colorToHSV(c, hsv);
                            apply.run();
                        } catch (Exception ignored) {}
                    }
                });
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.setMargins(Ui.dp(16), Ui.dp(4), 0, 0);
        box.addView(hexBtn, hlp);
        dialog(a).setTitle(title).setView(box)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cb.done(); }
                }).show();
    }

    static void font(Activity a, final Pick cb) { font(a, null, null, cb); }

    /** 3.15: the font browser (bundled library, phone fonts, imported files), current highlighted. */
    static void font(Activity a, String current, String sample, final Pick cb) { FontPicker.show(a, current, sample, cb); }

    static void lut(Activity a, final Pick cb) {
        final ArrayList<String> keys = Lut.list();
        String[] names = new String[keys.size() + 1];
        for (int i = 0; i < keys.size(); i++) names[i] = Lut.displayName(keys.get(i));
        names[keys.size()] = "Import .cube file…";
        dialog(a).setTitle(keys.isEmpty() ? "No LUTs yet" : "LUT").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which >= keys.size()) cb.importNew(); else cb.picked(keys.get(which));
            }
        }).show();
    }

    /**
     * A scrollable plain-text report with Copy (keeps the dialog open) and Close. Monospace, so
     * pasted into a message it reads the same.
     */
    static android.widget.TextView report(final android.app.Activity a, String title, final String text, final Runnable onClose) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        final android.widget.TextView tv = Ui.text(a, text, 11, Ui.TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8));
        sv.addView(tv);
        final android.app.AlertDialog d = dialog(a).setTitle(title).setView(sv)
                .setNeutralButton("Copy", null)
                .setNegativeButton("Close", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) { if (onClose != null) onClose.run(); }
                }).create();
        d.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface di) {
                d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Independent Video report", tv.getText()));
                        android.widget.Toast.makeText(a, "Copied", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        d.setCancelable(false);
        d.show();
        return tv;
    }
}
