package com.christopher.strata;

/**
 * The effect catalog. An effect is an adjustment layer (type EFFECT) with an effect id, up to 8
 * parameters and 3 colours, so every effect gets masks, Blend If, blend modes, opacity and undo
 * from the layer system. One shader (adj_fx.frag) implements them; blur-based effects add two
 * separable passes (fx_blur.frag). Sizes are fractions of the image's long side, so an effect
 * looks the same in the preview, a thumbnail and a full-resolution export.
 */
final class Fx {
    static final String[] CATS = {"Color", "Tone", "Light", "Blur", "Stylize", "Geometry", "Fractal"};
    static final String[] CAT_ICONS = {"hsl", "levels", "light", "detail", "canvas", "mirror", "spiral"};

    /** One slider. value shown = v * scale, with dec decimals and unit. */
    static final class P {
        final String label;
        final float min, max, def, scale;
        final int dec;
        final String unit;

        P(String label, float min, float max, float def, float scale, int dec, String unit) {
            this.label = label; this.min = min; this.max = max; this.def = def; this.scale = scale; this.dec = dec; this.unit = unit;
        }
    }

    /** A parameter shown as chips instead of a slider (value = option index). */
    static final class Choice {
        final int param;
        final String label;
        final String[] options;

        int def;

        Choice(int param, String label, String... options) { this.param = param; this.label = label; this.options = options; }

        Choice dflt(int d) { def = d; return this; }
    }

    static final class Def {
        final int id, cat;
        final String name, blurb;
        P[] params = new P[0];
        Choice[] choices = new Choice[0];
        String[] colorLabels = new String[0];
        int[] colorDefs = new int[0];
        String[] presetNames;
        float[][] presets;          // values for params 0..n
        boolean twoPass, mipBelow;
        int defaultMode;            // blend mode a new layer starts with
        int kind;                   // 0 colour/neighbourhood (adj_fx), 1 generated (fx_gen), 2 geometry remap (fx_remap)
        int cxParam = -1, cyParam = -1;   // params holding an on-canvas point (fractions of the crop); -1 none

        Def(int id, int cat, String name, String blurb) { this.id = id; this.cat = cat; this.name = name; this.blurb = blurb; }

        Def p(P... ps) { params = ps; return this; }
        Def ch(Choice... c) { choices = c; return this; }
        Def col(String[] labels, int... defs) { colorLabels = labels; colorDefs = defs; return this; }
        Def two() { twoPass = true; return this; }
        Def mip() { mipBelow = true; return this; }
        Def mode(int m) { defaultMode = m; return this; }
        Def presets(String[] names, float[][] v) { presetNames = names; presets = v; return this; }
        Def gen() { kind = 1; return this; }
        Def remap() { kind = 2; return this; }
        Def center(int px, int py) { cxParam = px; cyParam = py; return this; }

        boolean isChoice(int param) {
            for (Choice c : choices) if (c.param == param) return true;
            return false;
        }
    }

    // ids are stored in projects: never renumber, only append
    static final int INVERT = 0, SEPIA = 1, BW = 2, DUOTONE = 3, GRADMAP = 4, SPLIT = 5, PHOTOFILTER = 6, COLORIZE = 7,
            CROSS = 8, BLEACH = 9, INFRARED = 10, POSTERIZE = 11, THRESHOLD = 12, SOLARIZE = 13, DODGE = 14, BURN = 15,
            VIGNETTE = 16, GLOW = 17, GRAIN = 18, CHROMA = 19, GAUSS = 20, MOTION = 21, ZOOM = 22, TILT = 23, HIGHPASS = 24,
            EMBOSS = 25, EDGES = 26, SKETCH = 27, PIXELATE = 28, HALFTONE = 29, OIL = 30,
            MIRROR = 31, FLIP = 32, REFLECT = 33, OFFSET = 34, TILE = 35, KALEIDO = 36, POLAR = 37, TWIRL = 38,
            PINCH = 39, WAVE = 40, ROUND = 41, DROSTE = 42, JULIA = 43, MANDEL = 44, FOLDS = 45, NOISE = 46,
            HUESHIFT = 47;

    private static final String[] RANGE = {"Shadows", "Midtones", "Highlights"};
    static final Def[] ALL = {
            new Def(INVERT, 0, "Invert", "Negative image, or only the lightness").ch(new Choice(0, "Invert", "Colours", "Lightness only")),
            new Def(SEPIA, 0, "Sepia", "Warm brown monochrome").p(
                    new P("Tone", 0, 1, 0.8f, 100, 0, "%"), new P("Contrast", -0.5f, 0.5f, 0.1f, 100, 0, "")),
            new Def(BW, 0, "Black & White", "Monochrome with a channel mix, like coloured lens filters").p(
                    new P("Red", -0.5f, 1.5f, 0.30f, 100, 0, "%"), new P("Green", -0.5f, 1.5f, 0.59f, 100, 0, "%"),
                    new P("Blue", -0.5f, 1.5f, 0.11f, 100, 0, "%"), new P("Contrast", -0.5f, 0.5f, 0, 100, 0, ""))
                    .presets(new String[]{"Neutral", "Red filter", "Orange", "Yellow", "Green", "Blue", "Infrared"},
                            new float[][]{{0.30f, 0.59f, 0.11f}, {0.9f, 0.2f, -0.1f}, {0.65f, 0.4f, -0.05f}, {0.5f, 0.5f, 0},
                                    {0.1f, 0.85f, 0.05f}, {0.05f, 0.25f, 0.7f}, {0.45f, 1.25f, -0.7f}}),
            new Def(DUOTONE, 0, "Duotone", "Shadows to one ink, highlights to another").p(new P("Contrast", -0.5f, 0.5f, 0, 100, 0, ""))
                    .col(new String[]{"Shadows", "Highlights"}, 0xff1b1f3b, 0xfff2c48d),
            new Def(GRADMAP, 0, "Gradient Map", "Maps lightness onto three colours").p(new P("Midpoint", 0.1f, 0.9f, 0.5f, 100, 0, "%"))
                    .col(new String[]{"Shadows", "Midtones", "Highlights"}, 0xff0b0b12, 0xffb3263e, 0xffffe7a0),
            new Def(SPLIT, 0, "Split Toning", "One hue in the highlights, another in the shadows").p(
                    new P("Highlight hue", 0, 360, 45, 1, 0, "°"), new P("Highlight sat.", 0, 1, 0.3f, 100, 0, "%"),
                    new P("Shadow hue", 0, 360, 215, 1, 0, "°"), new P("Shadow sat.", 0, 1, 0.3f, 100, 0, "%"),
                    new P("Balance", -1, 1, 0, 100, 0, "")),
            new Def(PHOTOFILTER, 0, "Photo Filter", "A coloured lens filter: warming, cooling, any colour").p(new P("Density", 0, 1, 0.3f, 100, 0, "%"))
                    .ch(new Choice(1, "Luminosity", "Change", "Preserve")).col(new String[]{"Filter"}, 0xffec8a00),
            new Def(COLORIZE, 0, "Colorize", "Recolour everything to one hue, keeping light and shade").p(
                    new P("Hue", 0, 360, 30, 1, 0, "°"), new P("Saturation", 0, 1, 0.4f, 100, 0, "%"), new P("Lightness", -1, 1, 0, 100, 0, "")),
            new Def(CROSS, 0, "Cross Process", "Slide film in negative chemistry: punchy, cyan-yellow").p(new P("Strength", 0, 1, 0.7f, 100, 0, "%")),
            new Def(BLEACH, 0, "Bleach Bypass", "Silver left in: low saturation, hard contrast").p(new P("Strength", 0, 1, 0.6f, 100, 0, "%")),
            new Def(INFRARED, 0, "Infrared", "Faux IR black & white: bright foliage, dark skies").p(
                    new P("Strength", 0, 1, 1, 100, 0, "%"), new P("Foliage", 0, 1, 0.8f, 100, 0, "%")),
            new Def(POSTERIZE, 1, "Posterize", "Reduce each channel to a few levels").p(new P("Levels", 2, 16, 5, 1, 0, "")),
            new Def(THRESHOLD, 1, "Threshold", "Pure black and white at a cut-off").p(
                    new P("Level", 0, 1, 0.5f, 100, 0, "%"), new P("Softness", 0, 0.5f, 0.02f, 100, 0, "%")),
            new Def(SOLARIZE, 1, "Solarize", "Tones above a threshold reverse, as in the darkroom").p(new P("Threshold", 0, 1, 0.5f, 100, 0, "%")),
            new Def(DODGE, 1, "Dodge", "Lighten a tonal range; paint it in with a mask").p(new P("Exposure", 0, 2, 0.6f, 1, 2, " EV"))
                    .ch(new Choice(1, "Range", RANGE)),
            new Def(BURN, 1, "Burn", "Darken a tonal range; paint it in with a mask").p(new P("Exposure", 0, 2, 0.6f, 1, 2, " EV"))
                    .ch(new Choice(1, "Range", RANGE)),
            new Def(VIGNETTE, 2, "Vignette", "Darken or lighten towards the edges of the crop").p(
                    new P("Amount", -1, 1, -0.5f, 100, 0, ""), new P("Size", 0, 1, 0.5f, 100, 0, "%"),
                    new P("Roundness", -1, 1, 0, 100, 0, ""), new P("Feather", 0, 1, 0.5f, 100, 0, "%"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%")),
            new Def(GLOW, 2, "Glow", "Bloom from the highlights (Orton effect at a low threshold)").p(
                    new P("Radius", 0.002f, 0.08f, 0.02f, 100, 1, "%"), new P("Threshold", 0, 1, 0.55f, 100, 0, "%"),
                    new P("Intensity", 0, 2, 0.8f, 100, 0, "%")).two(),
            new Def(GRAIN, 2, "Film Grain", "Fine photographic grain, strongest in the midtones").p(
                    new P("Amount", 0, 1, 0.35f, 100, 0, "%"), new P("Size", 0.0002f, 0.003f, 0.0006f, 10000, 0, ""),
                    new P("Colour", 0, 1, 0, 100, 0, "%")),
            new Def(CHROMA, 2, "Chromatic Aberration", "Red and blue fringes towards the edges, like a cheap lens").p(
                    new P("Amount", 0, 0.01f, 0.003f, 1000, 1, "")),
            new Def(GAUSS, 3, "Gaussian Blur", "Soft, even blur in linear light").p(new P("Radius", 0.0005f, 0.1f, 0.01f, 100, 2, "%")).two(),
            new Def(MOTION, 3, "Motion Blur", "Streaks along one direction").p(
                    new P("Distance", 0.002f, 0.1f, 0.02f, 100, 1, "%"), new P("Angle", 0, 180, 0, 1, 0, "°")).mip(),
            new Def(ZOOM, 3, "Zoom Blur", "Radial streaks from a centre point").p(
                    new P("Amount", 0, 0.5f, 0.12f, 100, 0, "%"), new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%")).mip(),
            new Def(TILT, 3, "Tilt-Shift", "A sharp band, blur growing away from it: miniature look").p(
                    new P("Blur", 0.002f, 0.05f, 0.015f, 100, 1, "%"), new P("Focus position", 0, 1, 0.55f, 100, 0, "%"),
                    new P("Focus width", 0, 0.5f, 0.12f, 100, 0, "%"), new P("Transition", 0.02f, 0.5f, 0.2f, 100, 0, "%"),
                    new P("Angle", -90, 90, 0, 1, 0, "°")).two(),
            new Def(HIGHPASS, 3, "High Pass", "Keeps only detail. Starts in Overlay blend: a classic sharpen").p(
                    new P("Radius", 0.0005f, 0.02f, 0.003f, 100, 2, "%")).two().mode(9),
            new Def(EMBOSS, 4, "Emboss", "Relief from edges, lit from one side").p(
                    new P("Height", 0, 5, 1.5f, 100, 0, "%"), new P("Angle", 0, 360, 135, 1, 0, "°"), new P("Colour", 0, 1, 0, 100, 0, "%")),
            new Def(EDGES, 4, "Find Edges", "Outlines where tones change").p(new P("Strength", 0, 4, 1.5f, 100, 0, "%"))
                    .ch(new Choice(1, "Style", "Light on dark", "Dark on light")),
            new Def(SKETCH, 4, "Pencil Sketch", "Graphite lines on paper from the image's edges").p(
                    new P("Strength", 0, 3, 1.2f, 100, 0, "%"), new P("Line width", 0.0005f, 0.006f, 0.0015f, 10000, 0, ""),
                    new P("Colour", 0, 1, 0, 100, 0, "%")),
            new Def(PIXELATE, 4, "Pixelate", "Square cells of flat colour").p(new P("Cell size", 0.002f, 0.08f, 0.015f, 100, 1, "%")),
            new Def(HALFTONE, 4, "Halftone", "Printed dot screen, one ink or CMY").p(
                    new P("Dot size", 0.002f, 0.03f, 0.008f, 100, 1, "%"), new P("Angle", 0, 90, 45, 1, 0, "°"))
                    .ch(new Choice(2, "Inks", "Black", "Colour (CMY)")),
            new Def(OIL, 4, "Oil Paint", "Kuwahara filter: flat brush strokes that keep edges").p(
                    new P("Brush", 0.001f, 0.015f, 0.006f, 1000, 1, "")).mip(),
            // ---- geometry: every output pixel is fetched from somewhere else in the image
            new Def(MIRROR, 5, "Mirror", "One half reflected onto the other, across a line you can move").p(
                    new P("Axis", 0, 2, 0, 1, 0, ""), new P("Keep", 0, 1, 0, 1, 0, ""),
                    new P("Axis X", 0, 1, 0.5f, 100, 0, "%"), new P("Axis Y", 0, 1, 0.5f, 100, 0, "%"))
                    .ch(new Choice(0, "Mirror line", "Vertical (left | right)", "Horizontal (top / bottom)", "Both (4 ways)"),
                            new Choice(1, "Keep", "Left / top half", "Right / bottom half")).remap().center(2, 3),
            new Def(FLIP, 5, "Flip", "Turn the image over, non-destructively").p(new P("Direction", 0, 2, 0, 1, 0, ""))
                    .ch(new Choice(0, "Flip", "Horizontal", "Vertical", "Both (180°)")).remap(),
            new Def(REFLECT, 5, "Reflection", "Water reflection below a horizon, with ripples and fade").p(
                    new P("Horizon", 0, 1, 0.62f, 100, 0, "%"), new P("Fade", 0, 1, 0.5f, 100, 0, "%"),
                    new P("Ripple", 0, 0.02f, 0.004f, 1000, 1, ""), new P("Ripple size", 0.005f, 0.1f, 0.02f, 100, 1, "%"),
                    new P("Darken", 0, 1, 0.25f, 100, 0, "%")).remap().center(-1, 0),
            new Def(OFFSET, 5, "Offset", "Slide the image and wrap it around (Photoshop's Offset)").p(
                    new P("Shift X", -1, 1, 0.5f, 100, 0, "%"), new P("Shift Y", -1, 1, 0, 100, 0, "%"), new P("Edges", 0, 1, 0, 1, 0, ""))
                    .ch(new Choice(2, "Edges", "Wrap around", "Mirror")).remap(),
            new Def(TILE, 5, "Tile", "Repeat the image in a grid, plain or mirrored").p(
                    new P("Columns", 1, 12, 3, 1, 0, ""), new P("Rows", 1, 12, 3, 1, 0, ""), new P("Pattern", 0, 1, 0, 1, 0, ""))
                    .ch(new Choice(2, "Pattern", "Repeat", "Mirror (seamless)")).remap(),
            new Def(KALEIDO, 5, "Kaleidoscope", "Mirrored wedges around a centre; recursion folds it into itself").p(
                    new P("Segments", 2, 24, 6, 1, 0, ""), new P("Rotation", 0, 360, 0, 1, 0, "°"), new P("Zoom", 0.25f, 4, 1, 100, 0, "%"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%"),
                    new P("Recursion", 0, 5, 0, 1, 0, ""), new P("Source angle", 0, 360, 0, 1, 0, "°")).remap().center(3, 4),
            new Def(POLAR, 5, "Polar / Little Planet", "Wrap the image into a circle (tiny planet) or unwrap a circle").p(
                    new P("Mode", 0, 1, 0, 1, 0, ""), new P("Rotation", 0, 360, 0, 1, 0, "°"), new P("Size", 0.25f, 3, 1, 100, 0, "%"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%"))
                    .ch(new Choice(0, "Mode", "Little planet", "Unwrap circle")).remap().center(3, 4),
            new Def(TWIRL, 5, "Twirl", "Spin the image around a point, strongest at the centre").p(
                    new P("Angle", -720, 720, 180, 1, 0, "°"), new P("Radius", 0.05f, 1.5f, 0.5f, 100, 0, "%"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%")).remap().center(2, 3),
            new Def(PINCH, 5, "Pinch / Bulge", "Swell (bulge) or squeeze (pinch) a round area").p(
                    new P("Amount", -0.9f, 1, 0.5f, 100, 0, ""), new P("Radius", 0.05f, 1.5f, 0.45f, 100, 0, "%"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%")).remap().center(2, 3),
            new Def(WAVE, 5, "Wave / Ripple", "Sine-wave displacement, or rings like a drop in water").p(
                    new P("Amplitude", 0, 0.1f, 0.015f, 1000, 0, ""), new P("Wavelength", 0.01f, 0.5f, 0.08f, 100, 0, "%"),
                    new P("Phase", 0, 360, 0, 1, 0, "°"), new P("Style", 0, 3, 0, 1, 0, ""),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%"))
                    .ch(new Choice(3, "Style", "Horizontal", "Vertical", "Both", "Ripple rings")).remap().center(4, 5),
            new Def(ROUND, 5, "Round Corners", "Transparent rounded corners (export PNG to keep them)").p(
                    new P("Radius", 0, 0.5f, 0.08f, 100, 0, "%"), new P("Softness", 0, 0.05f, 0.002f, 1000, 0, "")),
            new Def(DROSTE, 6, "Droste", "The picture contains itself, forever (nested or Escher spiral)").p(
                    new P("Inner size", 0.1f, 0.9f, 0.4f, 100, 0, "%"), new P("Style", 0, 1, 0, 1, 0, ""), new P("Rotation", 0, 360, 0, 1, 0, "°"),
                    new P("Center X", 0, 1, 0.5f, 100, 0, "%"), new P("Center Y", 0, 1, 0.5f, 100, 0, "%"), new P("Zoom phase", 0, 1, 0, 100, 0, "%"))
                    .ch(new Choice(1, "Style", "Nested frames", "Escher spiral")).remap().center(3, 4),
            // ---- fractals: generated with a 3-colour palette, or with the image mapped through them
            new Def(JULIA, 6, "Julia Set", "Classic escape-time fractal; the constant C shapes it").p(
                    new P("C real", -1.2f, 1.2f, -0.8f, 1000, 0, ""), new P("C imaginary", -1.2f, 1.2f, 0.156f, 1000, 0, ""),
                    new P("Zoom", -1, 8, 0, 1, 1, " stops"), new P("Center X", -2, 2, 0, 100, 0, ""), new P("Center Y", -2, 2, 0, 100, 0, ""),
                    new P("Iterations", 16, 300, 120, 1, 0, ""), new P("Colour cycle", 0, 1, 0, 100, 0, "%"), new P("Fill", 0, 1, 0, 1, 0, ""))
                    .ch(new Choice(7, "Fill", "Palette", "Image mapped through it"))
                    .presets(new String[]{"Spirals", "Dendrite", "Rabbit", "San Marco", "Siegel disk", "Dragon"},
                            new float[][]{{-0.8f, 0.156f}, {0, 1}, {-0.123f, 0.745f}, {-0.75f, 0.02f}, {-0.391f, -0.587f}, {0.36f, 0.1f}})
                    .col(new String[]{"Deep", "Mid", "Bright"}, 0xff0b1026, 0xffe9a23b, 0xfffff3d6).gen(),
            new Def(MANDEL, 6, "Mandelbrot", "Explore the Mandelbrot set: pick a region, zoom up to 14 stops").p(
                    new P("Center X", -2.5f, 1, -0.5f, 1000, 0, ""), new P("Center Y", -1.5f, 1.5f, 0, 1000, 0, ""),
                    new P("Zoom", 0, 14, 0, 1, 1, " stops"), new P("Rotation", 0, 360, 0, 1, 0, "°"),
                    new P("Iterations", 16, 400, 150, 1, 0, ""), new P("Colour cycle", 0, 1, 0, 100, 0, "%"), new P("Fill", 0, 1, 0, 1, 0, ""))
                    .ch(new Choice(6, "Fill", "Palette", "Image mapped through it"))
                    .presets(new String[]{"Whole set", "Seahorse valley", "Elephant valley", "Spiral", "Mini-brot"},
                            new float[][]{{-0.5f, 0, 0}, {-0.745f, 0.11f, 6}, {0.28f, 0.008f, 7}, {-0.7435f, 0.1314f, 10}, {-1.7549f, 0f, 5.5f}})
                    .col(new String[]{"Deep", "Mid", "Bright"}, 0xff070a1a, 0xff2f8fd8, 0xfffff0c8).gen(),
            new Def(FOLDS, 6, "Fractal Folds", "Kaliset: folding space over and over; organic, lacy, cellular").p(
                    new P("Fold X", 0.1f, 1.2f, 0.52f, 1000, 0, ""), new P("Fold Y", 0.1f, 1.2f, 0.48f, 1000, 0, ""),
                    new P("Zoom", -2, 4, 0, 1, 1, " stops"), new P("Iterations", 4, 24, 12, 1, 0, ""),
                    new P("Rotation", 0, 360, 0, 1, 0, "°"), new P("Detail", 0.2f, 3, 1, 100, 0, "%"), new P("Fill", 0, 1, 0, 1, 0, ""))
                    .ch(new Choice(6, "Fill", "Palette", "Image mapped through it"))
                    .presets(new String[]{"Lace", "Coral", "Crystal", "Cells"},
                            new float[][]{{0.52f, 0.48f}, {0.8f, 0.35f}, {0.95f, 0.95f}, {0.3f, 0.6f}})
                    .col(new String[]{"Deep", "Mid", "Bright"}, 0xff120818, 0xffb3263e, 0xffffd7a0).gen(),
            new Def(NOISE, 6, "Fractal Noise", "Clouds, turbulence, ridges or marble from layered noise").p(
                    new P("Style", 0, 3, 0, 1, 0, ""), new P("Scale", 0.02f, 1, 0.25f, 100, 0, "%"), new P("Octaves", 1, 8, 6, 1, 0, ""),
                    new P("Roughness", 0.3f, 0.8f, 0.5f, 100, 0, "%"), new P("Contrast", -1, 1, 0, 100, 0, ""),
                    new P("Seed", 0, 100, 0, 1, 0, ""), new P("Warp", 0, 2, 0, 100, 0, "%"))
                    .ch(new Choice(0, "Style", "Clouds", "Turbulence", "Ridged", "Marble"))
                    .col(new String[]{"Dark", "Light"}, 0xff101114, 0xffe8e8ea).gen(),
            new Def(HUESHIFT, 0, "Hue Shift", "Turn every colour around the colour wheel. Link it to the music for colour cycling").p(
                    new P("Hue", -180, 180, 0, 1, 0, "°"), new P("Saturation", -1, 1, 0, 100, 0, "")),
    };

    static Def get(int id) { return id >= 0 && id < ALL.length ? ALL[id] : ALL[0]; }

    /** Which program draws the layer: 0 adj_fx, 1 fx_gen, 2 fx_remap. Image-filled fractals are remaps. */
    static int program(Layer l) { return get(l.fx).kind; }

    /** Reads image pixels from elsewhere (geometry, image-filled fractals): needs a source region. */
    static boolean needsSource(Layer l) {
        Def d = get(l.fx);
        return d.kind == 2 || (d.kind == 1 && imageFill(l));
    }

    static boolean imageFill(Layer l) {
        switch (l.fx) {
            case JULIA: return l.fxp[7] > 0.5f;
            case MANDEL: case FOLDS: return l.fxp[6] > 0.5f;
            default: return false;
        }
    }

    /** Remaps whose source points jump around chaotically: they read a whole-image source. */
    static boolean chaotic(Layer l) { return get(l.fx).kind == 1 && imageFill(l); }

    /** Parameters that hold a size as a fraction of the long side. */
    private static boolean isSize(int id, int param) {
        switch (id) {
            case GLOW: case GAUSS: case MOTION: case TILT: case HIGHPASS: case PIXELATE: case HALFTONE: case OIL: case SKETCH: case CHROMA:
                return param == (id == SKETCH ? 1 : 0);
            default: return false;
        }
    }

    /** How far (fraction of the long side) the effect reads from neighbouring pixels. */
    static float reach(Layer l) {
        float[] p = l.fxp;
        switch (l.fx) {
            case GLOW: case GAUSS: case TILT: case HIGHPASS: return p[0] * 3.2f;
            case MOTION: return p[0] * 0.55f;
            case ZOOM: return p[0] * 0.75f;
            case CHROMA: return p[0] * 1.5f;
            case OIL: return p[0] * 1.2f;
            case SKETCH: return p[1] * 1.2f;
            case PIXELATE: case HALFTONE: return p[0] * 1.5f;
            case EMBOSS: case EDGES: return 0.002f;
            default: return 0;
        }
    }

    /** Fills a new or reset effect layer with the effect's defaults. */
    static void defaults(Layer l, int id) {
        Def d = get(id);
        l.fx = id;
        l.fxp = new float[8];
        for (int i = 0; i < d.params.length; i++) l.fxp[i] = d.params[i].def;
        for (Choice c : d.choices) l.fxp[c.param] = c.def + (c.param == 1 && (id == PHOTOFILTER || id == DODGE || id == BURN) ? 1 : 0);
        l.fxc = new int[]{0xff000000, 0xff808080, 0xffffffff};
        for (int i = 0; i < d.colorDefs.length; i++) l.fxc[i] = d.colorDefs[i];
    }

    static boolean anyNeighbourhood(Layer l) { return reach(l) > 0; }

    @SuppressWarnings("unused")
    static boolean sizeParam(int id, int param) { return isSize(id, param); }
}
