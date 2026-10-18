package com.christopher.strata;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Shared look: graphite panels, amber accent, compact type. Plus the precision slider. */
final class Ui {
    static final int BG = 0xff0e0f11, PANEL = 0xff16171a, PANEL2 = 0xff1f2125, LINE = 0xff2c2f35;
    static final int TEXT = 0xffe7e7e9, DIM = 0xff8b9099, ACCENT = 0xffe9a23b, DANGER = 0xffe0564b;

    static float density = 3f;

    static int dp(float v) { return Math.round(v * density); }

    static GradientDrawable round(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.US), 11, DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(14), dp(12), dp(14), dp(4));
        return t;
    }

    static TextView button(Context c, String s, View.OnClickListener l) {
        TextView t = text(c, s, 13, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), round(PANEL2, 8, LINE), null));
        t.setOnClickListener(l);
        return t;
    }

    static TextView chip(Context c, String s, boolean on, View.OnClickListener l) {
        TextView t = text(c, s, 12, on ? 0xff111111 : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(on ? ACCENT : PANEL2, 16, on ? 0 : LINE));
        t.setOnClickListener(l);
        return t;
    }

    static TextView iconButton(Context c, String glyph, View.OnClickListener l) {
        TextView t = text(c, glyph, 19, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, new ColorDrawable(0xffffffff)));
        t.setOnClickListener(l);
        return t;
    }

    // ---------------------------------------------------------------- icons
    static android.widget.ImageView icon(Context c, String name, int color, float sizeDp) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageDrawable(new IconDrawable(name, color));
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** 44 dp touch target with a borderless ripple and a 22 dp icon. */
    static android.widget.ImageView iconBtn(Context c, String name, View.OnClickListener l) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageDrawable(new IconDrawable(name, TEXT));
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setPadding(dp(11), dp(11), dp(11), dp(11));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, new ColorDrawable(0xffffffff)));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));   // Android's minimum touch target
        v.setContentDescription(label(name));
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    /** Spoken label for an icon button (TalkBack); also shown as a tooltip on long-press. */
    static String label(String icon) {
        String[][] m = {{"back", "Back"}, {"undo", "Undo"}, {"redo", "Redo"}, {"compare", "Hold to compare with the original"},
                {"more", "More options"}, {"drag", "Drag to reorder"}, {"plus", "Add"}, {"close", "Close"}, {"eye", "Show or hide"},
                {"eye_off", "Show or hide"}, {"play", "Play"}, {"pause", "Pause"}, {"trash", "Delete"}, {"share", "Share"},
                {"reset", "Reset"}, {"frame_back", "Back one frame"}, {"frame_fwd", "Forward one frame"}, {"start", "Go to start"}, {"star", "Add to favorites"}, {"star_on", "Remove from favorites"}};
        for (String[] e : m) if (e[0].equals(icon)) return e[1];
        return Character.toUpperCase(icon.charAt(0)) + icon.substring(1).replace('_', ' ');
    }

    static void setIcon(android.widget.ImageView v, String name) {
        v.setImageDrawable(new IconDrawable(name, TEXT));
        v.setContentDescription(label(name));
    }

    static void setIconColor(android.widget.ImageView v, int color) {
        if (v.getDrawable() instanceof IconDrawable) ((IconDrawable) v.getDrawable()).setColor(color);
    }

    /** Bottom tab: icon over a short label; accent when selected. */
    static LinearLayout tab(Context c, String iconName, String label, boolean on, View.OnClickListener l) {
        LinearLayout t = col(c);
        t.setGravity(Gravity.CENTER);
        int color = on ? ACCENT : DIM;
        android.widget.ImageView i = icon(c, iconName, color, 22);
        t.addView(i);
        TextView tv = text(c, label, 10.5f, color);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(2), 0, 0);
        t.addView(tv);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), null, new ColorDrawable(0xffffffff)));
        t.setOnClickListener(l);
        return t;
    }

    /** Chip with a leading icon. */
    static TextView chip(Context c, String iconName, String s, boolean on, View.OnClickListener l) {
        TextView t = chip(c, s, on, l);
        IconDrawable d = new IconDrawable(iconName, on ? 0xff111111 : TEXT);
        d.setBounds(0, 0, dp(16), dp(16));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(dp(10), dp(7), dp(12), dp(7));
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Horizontally scrolling strip of views with gaps. */
    static HorizontalScrollView strip(Context c, View... views) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row(c);
        r.setPadding(dp(12), dp(6), dp(12), dp(6));
        for (View v : views) {
            if (v == null) continue;   // optional entries
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            r.addView(v, lp);
        }
        h.addView(r);
        return h;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    interface NumberCallback { void value(float v); }

    static void askNumber(Context c, String title, float current, final NumberCallback cb) {
        final EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setText(trim(current));
        e.setSelectAllOnFocus(true);
        final AlertDialog dialog = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(e).setPositiveButton("Set",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){@Override public void onShow(DialogInterface ignored){
            dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){
                try { float value=NumberInput.parse(e.getText().toString());cb.value(value);dialog.dismiss(); }
                catch(IllegalArgumentException error){e.setError(error.getMessage());}
            }});
        }});dialog.show();
    }

    static String trim(float v) {
        if (Math.abs(v - Math.round(v)) < 1e-4) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.2f", v);
    }

    /**
     * Precision slider. Horizontal drag moves the value relative to where you started (no jump
     * on touch); a vertical drag passes through to the scrolling panel. Double-tap resets.
     * Tap the number to type an exact value. The shown value is value*displayScale.
     */
    static final class Slider extends View {
        interface Listener {
            void begin();
            void change(float v);
        }

        final String name;
        final float min, max, def;
        float value;
        float displayScale = 1f;
        String unit = "";
        int decimals = 0;
        boolean bipolar;
        Listener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY, startV;
        private boolean dragging, began;
        private long lastTap;
        private final int slop;
        private final RectF r = new RectF();

        Slider(Context c, String name, float min, float max, float def, float value, Listener l) {
            super(c);
            this.name = name; this.min = min; this.max = max; this.def = def; this.value = value; this.listener = l;
            bipolar = min < 0 && max > 0;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        /** 3.15: a non-linear readout (log speed, frequency): the slider moves in value, shows map.shown(value). */
        interface Map { float shown(float v); float value(float shown); }
        Map map;

        Slider map(Map m, int decimals, String unit) { map = m; this.decimals = decimals; this.unit = unit; invalidate(); return this; }

        Slider display(float scale, int decimals, String unit) {
            displayScale = scale; this.decimals = decimals; this.unit = unit;
            return this;
        }

        void set(float v) { value = v; invalidate(); }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dp(50));
        }

        String valueText() {
            float v = map != null ? map.shown(value) : value * displayScale;
            String s = decimals == 0 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%." + decimals + "f", v);
            if (bipolar && v > 0.0001f) s = "+" + s;
            return s + unit;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), pad = dp(16);
            boolean changed = Math.abs(value - def) > 1e-5f;
            p.setTextSize(dp(13));
            p.setColor(TEXT);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText(name, pad, dp(20), p);
            p.setColor(changed ? ACCENT : DIM);
            p.setTypeface(Typeface.MONOSPACE);
            String vt = valueText();
            c.drawText(vt, w - pad - p.measureText(vt), dp(20), p);
            float y = dp(35), x0 = pad, x1 = w - pad;
            p.setColor(LINE);
            r.set(x0, y - dp(2), x1, y + dp(2));
            c.drawRoundRect(r, dp(2), dp(2), p);
            float t = (value - min) / (max - min), td = (def - min) / (max - min);
            float xv = x0 + (x1 - x0) * t, xd = x0 + (x1 - x0) * (bipolar ? td : 0);
            p.setColor(changed ? ACCENT : 0xff5b606a);
            r.set(Math.min(xv, xd), y - dp(2), Math.max(xv, xd), y + dp(2));
            c.drawRect(r, p);
            if (bipolar) { p.setColor(DIM); c.drawRect(xd - dp(1), y - dp(5), xd + dp(1), y + dp(5), p); }
            p.setColor(dragging ? ACCENT : TEXT);
            c.drawCircle(xv, y, dp(dragging ? 8 : 7), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); startV = value; dragging = false; began = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!dragging) {
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                            dragging = true;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            downX = e.getX();
                        } else return true;
                    }
                    if (!began) { began = true; if (listener != null) listener.begin(); }
                    float span = getWidth() - dp(32);
                    float nv = startV + (e.getX() - downX) / span * (max - min);
                    nv = Math.max(min, Math.min(max, nv));
                    if (nv != value) { value = nv; if (listener != null) listener.change(value); }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!dragging) {
                        long now = e.getEventTime();
                        boolean onNumber = e.getX() > getWidth() * 0.6f && e.getY() < dp(28);
                        if (now - lastTap < 300) {
                            lastTap = 0;
                            if (listener != null) listener.begin();
                            value = def;
                            if (listener != null) listener.change(value);
                        } else if (onNumber) {
                            askNumber(getContext(), name, map != null ? map.shown(value) : value * displayScale, new NumberCallback() {
                                @Override
                                public void value(float v) {
                                    float nv = Math.max(min, Math.min(max, map != null ? map.value(v) : v / displayScale));
                                    if (listener != null) listener.begin();
                                    value = nv;
                                    if (listener != null) listener.change(value);
                                    invalidate();
                                }
                            });
                        } else lastTap = now;
                    }
                    dragging = false;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
            }
            return true;
        }
    }
}
