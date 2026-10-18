package com.christopher.strata;

/** Desktop test for ViewMath (Fit / 100 %, offsets, loupe, clipping flags). Run: see tests/run.sh */
public class ViewTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
    static boolean near(float a, float b, float e) { return Math.abs(a - b) <= e; }

    public static void main(String[] a) {
        // S23 Ultra canvas ~1080 x 1500 px; a 12 MP photo 4000 x 3000 fits at 0.264
        float W = 1080, H = 1500, dw = 4000, dh = 3000;
        float fit = Math.min((W - 24) / dw, (H - 24) / dh);
        check("12 MP photo: double-tap from fit goes to exactly 100 %", ViewMath.zoomTarget(fit, fit, true) == 1f);
        check("a fitted-looking view (scale within 2 %) counts as fit", ViewMath.zoomTarget(fit, fit * 1.015f, false) == 1f);
        check("from any other zoom, double-tap goes back to fit", ViewMath.zoomTarget(fit, 0.6f, false) < 0 && ViewMath.zoomTarget(fit, 1f, false) < 0);
        check("an 800 px image (fits at 1.32) goes to 2x fit, not to a smaller 100 %", near(ViewMath.zoomTarget(1.32f, 1.32f, true), 2.64f, 1e-4f));
        check("2x fit is capped at 64", ViewMath.zoomTarget(40f, 40f, true) == 64f);

        // the tapped point stays under the finger
        float off = (W - dw * fit) / 2, sp = 300;
        float docPt = (sp - off) / fit;
        float off1 = ViewMath.anchor(off, fit, 1f, sp);
        check("anchor keeps the tapped doc point under the finger", near((sp - off1) / 1f, docPt, 1e-2f));

        // clamping: no empty band past the image edge at 100 %
        float l = ViewMath.clampOff(ViewMath.anchor(off, fit, 1f, 20), dw, W);
        check("tap just inside the left edge: that pixel stays under the finger, no empty band (off " + l + ")", near(l, 20 - (20 - off) / fit, 1e-2f) && l <= 0);
        check("tap in the margin left of the image: the left edge snaps to the screen edge", ViewMath.clampOff(ViewMath.anchor(off, fit, 1f, 4), dw, W) == 0f);
        float r0 = ViewMath.clampOff(ViewMath.anchor(off, fit, 1f, W - 4), dw, W);
        check("tap in the margin right of the image: the right edge snaps to the screen edge", near(r0 + dw, W, 1e-2f));
        check("in the middle the anchor is kept", near(ViewMath.clampOff(-1500f, dw, W), -1500f, 0));
        check("content smaller than the view is centred", ViewMath.clampOff(-50f, 600f, W) == 240f);

        // readout
        check("readout 'Fit · 26%'", ViewMath.zoomLabel(fit, true).equals("Fit · 26%"));
        check("readout '100%'", ViewMath.zoomLabel(1f, false).equals("100%"));
        check("readout below 10 % keeps a decimal ('6.5%', '2%')", ViewMath.zoomLabel(0.065f, false).equals("6.5%") && ViewMath.zoomLabel(0.02f, false).equals("2%"));
        // 200 MP (16320 x 12240) fits at ~0.065
        float fit200 = Math.min((W - 24) / 16320f, (H - 24) / 12240f);
        check("200 MP photo: readout " + ViewMath.zoomLabel(fit200, true) + ", double-tap goes to 100 %", ViewMath.zoomTarget(fit200, fit200, true) == 1f);

        // loupe
        check("loupe magnifies 6x at fit", ViewMath.loupeMag(fit) == 6f);
        check("loupe at 100 % magnifies 6x (6 screen px per image px)", ViewMath.loupeMag(1f) == 6f);
        check("loupe at 800 % stops at 24 px per image px (3x)", ViewMath.loupeMag(8f) == 3f);
        check("loupe never shrinks the view", ViewMath.loupeMag(64f) == 1f);
        float r = 150, gap = 90;
        float[] c = ViewMath.loupeCentre(540, 900, r, gap, W, H);
        check("loupe sits above the finger, clear of it", c[0] == 540 && c[1] == 900 - gap - r);
        c = ViewMath.loupeCentre(540, 200, r, gap, W, H);
        check("near the top it flips below the finger", c[1] == 200 + gap + r && c[1] - r > 200);
        c = ViewMath.loupeCentre(10, 1490, r, gap, W, H);
        check("near a corner it stays fully on screen", c[0] == r && c[1] + r <= H && c[1] - r >= 0);

        // clipping flags from a histogram (4 x 256 bins: r, g, b, luma)
        int[] h = new int[1024];
        for (int ch = 0; ch < 4; ch++) h[ch * 256 + 128] = 100000;
        boolean[] f = ViewMath.clipFlags(h);
        check("mid-grey image: no clipping triangles", !f[0] && !f[1]);
        h[255] = 100; h[128] -= 100;   // 0.1 % of red at 255
        f = ViewMath.clipFlags(h);
        check("0.1 % of red at 255 lights the highlight triangle", f[1] && !f[0]);
        h[3 * 256] = 30; h[3 * 256 + 128] -= 30;   // 0.03 % black: below the threshold
        check("0.03 % black does not light the shadow triangle", !ViewMath.clipFlags(h)[0]);
        h[3 * 256] = 80; h[3 * 256 + 128] -= 50;
        check("0.08 % black lights it", ViewMath.clipFlags(h)[0]);
        check("an empty histogram reports nothing", !ViewMath.clipFlags(new int[1024])[1]);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
