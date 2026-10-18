package com.christopher.strata;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Panels for editing one adjustment (Light & Color, Curves, Levels, HSL, Detail, LUT). Shared by
 * the photo editor's Edit tab and the video editor's Grade tab. The host supplies the live layer
 * and the undo/redraw plumbing; every slider gesture is one undo step.
 */
final class AdjPanels {
    interface Host {
        Activity act();
        Layer layer();
        void beginEdit();
        void changed();
        void rebuild();
        void pickLut();
        /** White balance: whether the host can sample a neutral point (photo editor), and start it. */
        boolean canPickNeutral();
        void pickNeutral();
        /** Clipping warnings on the canvas (photo editor): whether the host has them, and their state. */
        boolean canShowClipping();
        boolean clipVis();
        void setClipVis(boolean on);
        /** Effects: whether the host has masks (photo editor), and jump to masking this layer. */
        boolean canMask();
        void mask(int how);   // 0 paint it in, 1 linear gradient, 2 radial gradient, 3 a shape, 4 text (3.15)
    }

    interface Setter { void set(float v); }

    static final String[] BANDS = {"Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"};
    static final int[] BAND_COLORS = {0xffe8463c, 0xfff08c2c, 0xfff2d43a, 0xff52c25a, 0xff3fc9c4, 0xff3f7fe8, 0xff8a52e0, 0xffe04fb4};

    final Host h;
    final Views.Hist histView;
    final Views.CurveView curveView;
    int[] hist;
    int curveChannel, levelsChannel, hslBand;
    boolean showHistogram = true;

    AdjPanels(Host host) {
        h = host;
        histView = new Views.Hist(host.act());
        curveView = new Views.CurveView(host.act());
        curveView.listener = new Views.CurveView.Listener() {
            @Override public void begin() { h.beginEdit(); }
            @Override public void changed() {
                Layer l = h.layer();
                if (l != null) l.rebuildLut();
                h.changed();
            }
        };
    }

    void setHist(int[] hh) {
        hist = hh;
        histView.set(hh);
        curveView.hist = hh;
        curveView.invalidate();
    }

    /** Histogram, plus the clipping toggle under it where the host has one (tap either). */
    private void histBlock(LinearLayout panel, int channel) {
        if (!showHistogram) return;
        View hv = hist(channel);
        panel.addView(hv);
        if (!h.canShowClipping()) { hv.setOnClickListener(null); hv.setClickable(false); return; }
        View.OnClickListener tog = new View.OnClickListener() {
            @Override public void onClick(View v) { h.setClipVis(!h.clipVis()); h.rebuild(); }
        };
        hv.setOnClickListener(tog);
        panel.addView(Ui.strip(h.act(), Ui.chip(h.act(), h.clipVis() ? "Clipping shown: red = highlights, blue = shadows" : "Show clipping", h.clipVis(), tog)));
    }

    /** The histogram view, detached from any previous parent. */
    View hist(int channel) {
        if (histView.getParent() != null) ((ViewGroup) histView.getParent()).removeView(histView);
        histView.channel = channel;
        histView.set(hist);
        return histView;
    }

    Ui.Slider slider(String name, float min, float max, float def, float val, float scale, int dec, String unit, final Setter s) {
        Ui.Slider sl = new Ui.Slider(h.act(), name, min, max, def, Math.max(min, Math.min(max, val)), new Ui.Slider.Listener() {
            @Override public void begin() { h.beginEdit(); }
            @Override public void change(float v) {
                if (h.layer() == null) return;
                s.set(v);
                h.changed();
            }
        });
        sl.display(scale, dec, unit);
        return sl;
    }

    static TextView hint(Activity a, String s) {
        TextView t = Ui.text(a, s, 12, Ui.DIM);
        t.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(4));
        return t;
    }

    /** Header (name + Reset) and the controls for h.layer(). */
    void build(LinearLayout panel) {
        Layer l = h.layer();
        if (l == null) return;
        LinearLayout head = Ui.row(h.act());
        head.setPadding(0, 0, Ui.dp(12), 0);
        head.addView(Ui.label(h.act(), l.name), Ui.lp(0, -2, 1f));
        if (h.canMask()) head.addView(Ui.chip(h.act(), l.mask != null ? "Edit mask" : "Mask", false, new View.OnClickListener() {
            @Override public void onClick(View v) { h.mask(-1); }
        }));
        head.addView(Ui.chip(h.act(), "Reset", false, new View.OnClickListener() {
            @Override public void onClick(View v) { reset(); }
        }));
        panel.addView(head);
        switch (l.type) {
            case Layer.BASIC: basic(panel); break;
            case Layer.CURVES: curves(panel); break;
            case Layer.LEVELS: levels(panel); break;
            case Layer.HSL: hsl(panel); break;
            case Layer.DETAIL: detail(panel); break;
            case Layer.LUT: lut(panel); break;
            case Layer.EFFECT: effect(panel); break;
        }
    }

    // ---------------------------------------------------------------- effects
    static java.util.HashSet<String> favorites(Activity a) {
        return new java.util.HashSet<String>(a.getSharedPreferences("ui", Activity.MODE_PRIVATE)
                .getStringSet("fxfav", new java.util.HashSet<String>()));
    }

    static void setFavorite(Activity a, int id, boolean on) {
        java.util.HashSet<String> f = favorites(a);
        if (on) f.add(String.valueOf(id)); else f.remove(String.valueOf(id));
        a.getSharedPreferences("ui", Activity.MODE_PRIVATE).edit().putStringSet("fxfav", f).apply();
    }

    private void effect(LinearLayout panel) {
        final Layer l = h.layer();
        final Fx.Def d = Fx.get(l.fx);
        final Activity a = h.act();
        panel.addView(hint(a, d.blurb));
        final boolean fav = favorites(a).contains(String.valueOf(d.id));
        java.util.ArrayList<View> top = new java.util.ArrayList<View>();
        top.add(Ui.chip(a, fav ? "star_on" : "star", fav ? "Favorite" : "Add to favorites", fav, new View.OnClickListener() {
            @Override public void onClick(View v) { setFavorite(a, d.id, !fav); h.rebuild(); }
        }));
        if (h.canMask()) {
            top.add(Ui.chip(a, "mask", "Paint it in", false, new View.OnClickListener() { @Override public void onClick(View v) { h.mask(0); } }));
            top.add(Ui.chip(a, "fit", "Linear", false, new View.OnClickListener() { @Override public void onClick(View v) { h.mask(1); } }));
            top.add(Ui.chip(a, "grade", "Radial", false, new View.OnClickListener() { @Override public void onClick(View v) { h.mask(2); } }));
            top.add(Ui.chip(a, "mask", "Shape", false, new View.OnClickListener() { @Override public void onClick(View v) { h.mask(3); } }));
            top.add(Ui.chip(a, "text", "Text", false, new View.OnClickListener() { @Override public void onClick(View v) { h.mask(4); } }));
        }
        panel.addView(Ui.strip(a, top.toArray(new View[0])));
        if (d.presets != null) {
            View[] pv = new View[d.presetNames.length];
            for (int i = 0; i < pv.length; i++) {
                final float[] vals = d.presets[i];
                boolean on = true;
                for (int j = 0; j < vals.length; j++) on &= Math.abs(l.fxp[j] - vals[j]) < 1e-3f;
                pv[i] = Ui.chip(a, d.presetNames[i], on, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        h.beginEdit();
                        System.arraycopy(vals, 0, h.layer().fxp, 0, vals.length);
                        h.changed();
                        h.rebuild();
                    }
                });
            }
            panel.addView(Ui.label(a, "Presets"));
            panel.addView(Ui.strip(a, pv));
        }
        for (final Fx.Choice c : d.choices) {
            View[] cv = new View[c.options.length];
            for (int i = 0; i < cv.length; i++) {
                final int k = i;
                cv[i] = Ui.chip(a, c.options[i], Math.round(l.fxp[c.param]) == i, new View.OnClickListener() {
                    @Override public void onClick(View v) { h.beginEdit(); h.layer().fxp[c.param] = k; h.changed(); h.rebuild(); }
                });
            }
            panel.addView(Ui.label(a, c.label));
            panel.addView(Ui.strip(a, cv));
        }
        if (d.colorLabels.length > 0) {
            View[] cs = new View[d.colorLabels.length];
            for (int i = 0; i < cs.length; i++) {
                final int k = i;
                TextView t = Ui.chip(a, d.colorLabels[i], false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Pickers.color(a, d.colorLabels[k], h.layer().fxc[k], new Pickers.ColorCb() {
                            @Override public void begin() { h.beginEdit(); }
                            @Override public void color(int col) { if (h.layer() != null) { h.layer().fxc[k] = col | 0xff000000; h.changed(); } }
                            @Override public void done() { h.rebuild(); }
                        });
                    }
                });
                GradientDrawable sw = Ui.round(l.fxc[i] | 0xff000000, 8, 0xffffffff);
                sw.setBounds(0, 0, Ui.dp(16), Ui.dp(16));
                t.setCompoundDrawablesRelative(sw, null, null, null);
                t.setCompoundDrawablePadding(Ui.dp(6));
                cs[i] = t;
            }
            panel.addView(Ui.label(a, "Colours"));
            panel.addView(Ui.strip(a, cs));
        }
        for (int i = 0; i < d.params.length; i++) {
            if (d.isChoice(i)) continue;
            final Fx.P p = d.params[i];
            final int k = i;
            panel.addView(slider(p.label, p.min, p.max, p.def, l.fxp[i], p.scale, p.dec, p.unit, new Setter() {
                @Override public void set(float v) { h.layer().fxp[k] = v; }
            }));
        }
        if (d.params.length == 0 && d.choices.length == 0) panel.addView(hint(a, "No settings. Use opacity, blend mode or a mask to control it."));
    }

    void reset() {
        Layer l = h.layer();
        if (l == null) return;
        h.beginEdit();
        if (l.type == Layer.BASIC) l.basic = new float[10];
        if (l.type == Layer.CURVES) {
            for (int i = 0; i < 4; i++) l.curve[i] = new float[]{0, 0, 1, 1};
            l.rebuildLut();
        }
        if (l.type == Layer.LEVELS) l.levels = Layer.defaultLevels();
        if (l.type == Layer.HSL) l.hsl = new float[24];
        if (l.type == Layer.DETAIL) l.det = new float[]{0, 1f, 0, 0, 0, 0};
        if (l.type == Layer.LUT) l.opacity = 1f;
        if (l.type == Layer.EFFECT) { int[] keep = l.fxc.clone(); Fx.defaults(l, l.fx); if (Fx.get(l.fx).colorDefs.length == 0) l.fxc = keep; }
        h.changed();
        h.rebuild();
    }

    private Ui.Slider basicSlider(String name, final int idx, float min, float max, float scale, int dec, String unit) {
        return slider(name, min, max, 0, h.layer().basic[idx], scale, dec, unit, new Setter() {
            @Override public void set(float v) { h.layer().basic[idx] = v; }
        });
    }

    /**
     * Temperature and tint (adj_basic.frag's gains) that make a colour neutral: the shader scales
     * linear R by e^(0.35T + 0.12G), G by e^(-0.22G), B by e^(-0.35T + 0.12G), so a sampled
     * sRGB colour (r, g, b) comes out grey when e^(0.7T) = b/r and e^(-0.34G) = sqrt(rb)/g.
     * Returns {T, G, 1 if it had to be clamped to the sliders' range}.
     */
    static float[] neutralFor(float r, float g, float b) {
        double R = lin(r), Gc = lin(g), B = lin(b);
        R = Math.max(R, 1e-4); Gc = Math.max(Gc, 1e-4); B = Math.max(B, 1e-4);
        double T = Math.log(B / R) / 0.7;
        double G = -Math.log(Math.sqrt(R * B) / Gc) / 0.34;
        boolean clamped = Math.abs(T) > 1 || Math.abs(G) > 1;
        return new float[]{(float) Math.max(-1, Math.min(1, T)), (float) Math.max(-1, Math.min(1, G)), clamped ? 1 : 0};
    }

    static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

    private final java.util.HashMap<String,Boolean> expanded=new java.util.HashMap<String,Boolean>();
    private LinearLayout fold(LinearLayout panel,final String title,boolean defaultOpen) {
        final String key=h.layer().id+":"+title;
        if(!expanded.containsKey(key))expanded.put(key,defaultOpen);
        final TextView heading=Ui.text(h.act(),"",13,Ui.TEXT);
        heading.setPadding(Ui.dp(16),Ui.dp(12),Ui.dp(16),Ui.dp(12));
        heading.setMinimumHeight(Ui.dp(44));heading.setBackgroundColor(Ui.PANEL2);
        final LinearLayout body=Ui.col(h.act());
        boolean open=expanded.get(key);body.setVisibility(open?View.VISIBLE:View.GONE);heading.setText((open?"▾ ":"▸ ")+title);
        heading.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){
            boolean open=body.getVisibility()!=View.VISIBLE;expanded.put(key,open);body.setVisibility(open?View.VISIBLE:View.GONE);heading.setText((open?"▾ ":"▸ ")+title);
        }});
        panel.addView(heading);panel.addView(body);return body;
    }

    private void basic(LinearLayout panel) {
        histBlock(panel,-1);
        LinearLayout light=fold(panel,"Light",true);
        light.addView(basicSlider("Exposure",2,-5,5,1,2," EV"));
        light.addView(basicSlider("Contrast",3,-1,1,100,0,""));
        light.addView(basicSlider("Highlights",4,-1,1,100,0,""));
        light.addView(basicSlider("Shadows",5,-1,1,100,0,""));
        light.addView(basicSlider("Whites",6,-1,1,100,0,""));
        light.addView(basicSlider("Blacks",7,-1,1,100,0,""));
        LinearLayout color=fold(panel,"Color",false);
        color.addView(basicSlider("Vibrance",8,-1,1,100,0,""));
        color.addView(basicSlider("Saturation",9,-1,1,100,0,""));
        LinearLayout wb=fold(panel,"White balance",false);
        if(h.canPickNeutral())wb.addView(Ui.strip(h.act(),Ui.chip(h.act(),"Pick neutral…",false,new View.OnClickListener(){@Override public void onClick(View v){h.pickNeutral();}})));
        wb.addView(basicSlider("Temperature",0,-1,1,100,0,""));
        wb.addView(basicSlider("Tint",1,-1,1,100,0,""));
    }

    private Ui.Slider detSlider(String name, final int idx, float min, float max, float def, float scale, int dec, String unit) {
        return slider(name, min, max, def, h.layer().det[idx], scale, dec, unit, new Setter() {
            @Override public void set(float v) { h.layer().det[idx] = v; }
        });
    }

    private void detail(LinearLayout panel) {
        LinearLayout sharpening=fold(panel,"Sharpening",true);
        sharpening.addView(detSlider("Amount", 0, 0, 3, 0, 50, 0, ""));
        sharpening.addView(detSlider("Radius", 1, 0.5f, 3, 1, 1, 1, " px"));
        sharpening.addView(detSlider("Threshold", 2, 0, 1, 0, 100, 0, ""));
        sharpening.addView(detSlider("Masking", 3, 0, 1, 0, 100, 0, ""));
        LinearLayout noise=fold(panel,"Noise reduction",false);
        noise.addView(detSlider("Luminance", 4, 0, 1, 0, 100, 0, ""));
        noise.addView(detSlider("Color", 5, 0, 1, 0, 100, 0, ""));
        panel.addView(hint(h.act(), "Judge sharpening and noise at 100%: zoom in, and after a moment the view renders the visible area at full resolution. Threshold ignores fine grain; Masking keeps sharpening to edges."));
    }

    private View channelChips(final int current, final boolean curves) {
        String[] n = {"RGB", "Red", "Green", "Blue"};
        View[] v = new View[4];
        for (int i = 0; i < 4; i++) {
            final int c = i;
            v[i] = Ui.chip(h.act(), n[i], current == i, new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (curves) curveChannel = c; else levelsChannel = c;
                    h.rebuild();
                }
            });
        }
        return Ui.strip(h.act(), v);
    }

    private void curves(LinearLayout panel) {
        Layer l = h.layer();
        panel.addView(channelChips(curveChannel, true));
        curveView.layer = l;
        curveView.channel = curveChannel;
        curveView.hist = showHistogram ? hist : null;
        if (curveView.getParent() != null) ((ViewGroup) curveView.getParent()).removeView(curveView);
        panel.addView(curveView);
        panel.addView(hint(h.act(), "Tap the graph to add a point, drag to shape, drag a point far off the graph to remove it. Endpoints set black and white points."));
        panel.addView(Ui.strip(h.act(), Ui.chip(h.act(), "Reset channel", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Layer cur = h.layer();
                if (cur == null) return;
                h.beginEdit();
                cur.curve[curveChannel] = new float[]{0, 0, 1, 1};
                cur.rebuildLut();
                h.changed();
                h.rebuild();
            }
        })));
    }

    private void levels(LinearLayout panel) {
        final int c = levelsChannel;
        panel.addView(channelChips(c, false));
        histBlock(panel, c);
        panel.addView(levelsSlider("Input black", c * 5, 0, 1, 0, 255, 0));
        panel.addView(levelsSlider("Gamma", c * 5 + 2, 0.1f, 5f, 1, 1, 2));
        panel.addView(levelsSlider("Input white", c * 5 + 1, 0, 1, 1, 255, 0));
        panel.addView(levelsSlider("Output black", c * 5 + 3, 0, 1, 0, 255, 0));
        panel.addView(levelsSlider("Output white", c * 5 + 4, 0, 1, 1, 255, 0));
    }

    private Ui.Slider levelsSlider(String name, final int idx, float min, float max, float def, float scale, int dec) {
        return slider(name, min, max, def, h.layer().levels[idx], scale, dec, "", new Setter() {
            @Override public void set(float v) {
                float[] lv = h.layer().levels;
                int base = idx - idx % 5, k = idx % 5;
                if (k == 0) v = Math.min(v, lv[base + 1] - 0.004f);
                if (k == 1) v = Math.max(v, lv[base] + 0.004f);
                lv[idx] = v;
            }
        });
    }

    private void hsl(LinearLayout panel) {
        View[] chips = new View[8];
        for (int i = 0; i < 8; i++) {
            final int b = i;
            TextView t = Ui.chip(h.act(), BANDS[i], hslBand == i, new View.OnClickListener() {
                @Override public void onClick(View v) { hslBand = b; h.rebuild(); }
            });
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(BAND_COLORS[i]);
            dot.setSize(Ui.dp(10), Ui.dp(10));
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            t.setCompoundDrawablePadding(Ui.dp(6));
            chips[i] = t;
        }
        panel.addView(Ui.strip(h.act(), chips));
        final int b = hslBand;
        panel.addView(slider("Hue", -1, 1, 0, h.layer().hsl[b * 3], 30, 0, "°", new Setter() {
            @Override public void set(float v) { h.layer().hsl[b * 3] = v; }
        }));
        panel.addView(slider("Saturation", -1, 1, 0, h.layer().hsl[b * 3 + 1], 100, 0, "", new Setter() {
            @Override public void set(float v) { h.layer().hsl[b * 3 + 1] = v; }
        }));
        panel.addView(slider("Luminance", -1, 1, 0, h.layer().hsl[b * 3 + 2], 100, 0, "", new Setter() {
            @Override public void set(float v) { h.layer().hsl[b * 3 + 2] = v; }
        }));
        panel.addView(hint(h.act(), "Each band blends smoothly into its neighbours, so shifts never leave hard color edges."));
    }

    private void lut(LinearLayout panel) {
        final Layer l = h.layer();
        TextView pick = Ui.button(h.act(), "LUT: " + Lut.displayName(l.lutFile), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Pickers.lut(h.act(), new Pickers.Pick() {
                    @Override public void picked(String key) {
                        h.beginEdit();
                        Layer cur = h.layer();
                        if (cur == null) return;
                        cur.lutFile = key;
                        cur.name = Lut.displayName(key);
                        h.changed();
                        h.rebuild();
                    }
                    @Override public void importNew() { h.pickLut(); }
                });
            }
        });
        pick.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(4));
        panel.addView(pick, lp);
        panel.addView(slider("Intensity", 0, 1, 1, l.opacity, 100, 0, "%", new Setter() {
            @Override public void set(float v) { h.layer().opacity = v; }
        }));
        if (l.lutFile != null && Lut.get(l.lutFile) == null)
            panel.addView(hint(h.act(), "This LUT file couldn't be read. Import it again."));
        panel.addView(hint(h.act(), "3D LUTs in .cube format (Resolve, Premiere, camera log conversions, film looks). Imported LUTs are shared by all projects."));
    }
}
