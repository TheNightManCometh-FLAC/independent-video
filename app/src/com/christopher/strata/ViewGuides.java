package com.christopher.strata;

/**
 * 3.13: viewer guides (LumaFusion: grids, title-safe and action-safe overlays; Premiere mobile:
 * social safety zones). All geometry is in fractions of the output frame.
 * - Title / action safe: SMPTE ST 2046-1 for HD, 90 % and 93 % of the frame.
 * - Social app UI (9:16 only): the area Reels / TikTok / Shorts leave clear of their own text and
 *   buttons. Platforms publish this only for ads, so these are working figures (Instagram Reels:
 *   ~14 % top, ~35 % bottom, ~6 % each side), shown as approximate.
 */
final class ViewGuides {
    static final int OFF = 0, THIRDS = 1, SAFE = 2, SOCIAL = 3, COUNT = 4;
    static final String[] NAMES = {"Off", "Thirds", "Title & action safe", "Social app UI (approx.)"};

    /** The social overlay only makes sense on a tall (9:16-like) canvas. */
    static boolean available(int mode, int w, int h) {
        return mode >= 0 && mode < COUNT && (mode != SOCIAL || h >= w * 1.5f);
    }

    static int next(int mode, int w, int h) {
        int m = mode;
        for (int i = 0; i < COUNT; i++) {
            m = (m + 1) % COUNT;
            if (available(m, w, h)) return m;
        }
        return OFF;
    }

    /** Lines {x0, y0, x1, y1}. */
    static float[][] lines(int mode) {
        if (mode != THIRDS) return new float[0][];
        float a = 1f / 3, b = 2f / 3;
        return new float[][]{{a, 0, a, 1}, {b, 0, b, 1}, {0, a, 1, a}, {0, b, 1, b}};
    }

    /** Outlined rectangles {l, t, r, b}. */
    static float[][] rects(int mode, int w, int h) {
        if (mode == SAFE) return new float[][]{{0.035f, 0.035f, 0.965f, 0.965f}, {0.05f, 0.05f, 0.95f, 0.95f}};
        if (mode == SOCIAL && available(SOCIAL, w, h)) return new float[][]{{0.06f, 0.14f, 0.94f, 0.65f}};
        return new float[0][];
    }

    static String[] rectLabels(int mode) {
        if (mode == SAFE) return new String[]{"Action safe 93%", "Title safe 90%"};
        if (mode == SOCIAL) return new String[]{"Clear of app text and buttons (approx.)"};
        return new String[0];
    }

    /** Shade outside the rectangle (the social overlay shows what the app's UI covers). */
    static boolean shadeOutside(int mode) { return mode == SOCIAL; }
}
