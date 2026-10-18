package com.christopher.strata;

/**
 * View maths for the photo canvas (pure Java, desktop-tested in tests/ViewTest.java):
 * the Fit / 100 % toggle, keeping a zoomed image on screen, the zoom readout, and where the
 * sample loupe sits and how much it magnifies. scale = screen px per doc px (doc px = image px).
 */
final class ViewMath {
    private ViewMath() {}

    /**
     * Double-tap / zoom readout target. From the fitted view: 100 % (one image px per screen px),
     * or 2x fit for an image that already fits at roughly 1:1 or larger (100 % would barely
     * change it, or zoom out). From any other zoom: back to fit, returned as -1.
     */
    static float zoomTarget(float fitScale, float scale, boolean fitted) {
        boolean atFit = fitted || Math.abs(scale - fitScale) <= fitScale * 0.02f;
        if (!atFit) return -1f;
        return fitScale < 0.8f ? 1f : Math.min(64f, fitScale * 2f);
    }

    /** The offset that keeps screen point sp over the same doc point when the scale goes s0 -> s1. */
    static float anchor(float off, float s0, float s1, float sp) {
        return sp - (sp - off) * s1 / s0;
    }

    /**
     * Offset for one axis: content (doc length x scale) larger than the view is kept covering
     * it (no empty band past the image edge); smaller content is centred.
     */
    static float clampOff(float off, float content, float view) {
        if (content <= view) return (view - content) / 2f;
        return Math.max(view - content, Math.min(0f, off));
    }

    /** Readout under the canvas: "Fit · 23%" when fitted, else the zoom ("100%", "6.5%", "400%"). */
    static String zoomLabel(float scale, boolean fitted) {
        float pc = scale * 100f;
        String s = pc < 9.95f ? String.valueOf(Math.round(pc * 10f) / 10f) : String.valueOf(Math.round(pc));
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return (fitted ? "Fit · " : "") + s + "%";
    }

    /** Loupe magnification over the current view: 6x, but never past 24 screen px per image px. */
    static float loupeMag(float viewScale) {
        return Math.max(1f, Math.min(6f, 24f / viewScale));
    }

    /**
     * Loupe centre {x, y}: above the finger (gap px clear of it), below the finger when there's
     * no room above, kept fully inside the w x h view.
     */
    static float[] loupeCentre(float x, float y, float r, float gap, float w, float h) {
        float cy = y - gap - r;
        if (cy - r < 0) cy = y + gap + r;
        cy = Math.max(r, Math.min(h - r, cy));
        float cx = Math.max(r, Math.min(w - r, x));
        return new float[]{cx, cy};
    }

    /** Fraction of pixels in a 256-bin channel histogram that sit in bin `bin`. */
    static float binShare(int[] hist, int ch, int bin) {
        long n = 0;
        for (int i = 0; i < 256; i++) n += hist[ch * 256 + i];
        return n == 0 ? 0f : hist[ch * 256 + bin] / (float) n;
    }

    /**
     * Histogram clipping indicators {shadows, highlights}: highlights when any colour channel
     * has more than 0.05 % of its pixels at 255, shadows when the luminance channel (index 3)
     * has more than 0.05 % at 0, i.e. pixels black in every channel.
     */
    static boolean[] clipFlags(int[] hist) {
        float hi = 0;
        for (int ch = 0; ch < 3; ch++) hi = Math.max(hi, binShare(hist, ch, 255));
        return new boolean[]{binShare(hist, 3, 0) > 0.0005f, hi > 0.0005f};
    }
}
