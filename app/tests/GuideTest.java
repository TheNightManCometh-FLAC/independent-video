package com.christopher.strata;

/** 3.13: viewer guide geometry. */
public class GuideTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) {
        check("Thirds: four lines at 1/3 and 2/3", ViewGuides.lines(ViewGuides.THIRDS).length == 4
                && Math.abs(ViewGuides.lines(ViewGuides.THIRDS)[0][0] - 1f / 3) < 1e-6 && Math.abs(ViewGuides.lines(ViewGuides.THIRDS)[3][1] - 2f / 3) < 1e-6);
        float[][] safe = ViewGuides.rects(ViewGuides.SAFE, 1920, 1080);
        check("Action safe is 93 % of each dimension", Math.abs((safe[0][2] - safe[0][0]) - 0.93f) < 1e-6 && Math.abs((safe[0][3] - safe[0][1]) - 0.93f) < 1e-6);
        check("Title safe is 90 % and centred", Math.abs((safe[1][2] - safe[1][0]) - 0.90f) < 1e-6 && Math.abs(safe[1][0] - (1 - safe[1][2])) < 1e-6);
        check("Social overlay offered on 9:16", ViewGuides.available(ViewGuides.SOCIAL, 1080, 1920));
        check("Social overlay not offered on 16:9, 1:1 or 4:5", !ViewGuides.available(ViewGuides.SOCIAL, 1920, 1080)
                && !ViewGuides.available(ViewGuides.SOCIAL, 1080, 1080) && !ViewGuides.available(ViewGuides.SOCIAL, 1080, 1350));
        check("Cycling on 16:9 skips the social overlay", ViewGuides.next(ViewGuides.SAFE, 1920, 1080) == ViewGuides.OFF);
        check("Cycling on 9:16 reaches it", ViewGuides.next(ViewGuides.SAFE, 1080, 1920) == ViewGuides.SOCIAL && ViewGuides.next(ViewGuides.SOCIAL, 1080, 1920) == ViewGuides.OFF);
        float[] s = ViewGuides.rects(ViewGuides.SOCIAL, 1080, 1920)[0];
        check("Reels working margins: 14 % top, 35 % bottom, 6 % sides", Math.abs(s[1] - 0.14f) < 1e-6 && Math.abs(1 - s[3] - 0.35f) < 1e-6 && Math.abs(s[0] - 0.06f) < 1e-6 && Math.abs(1 - s[2] - 0.06f) < 1e-6);
        check("Only the social overlay shades the covered area", ViewGuides.shadeOutside(ViewGuides.SOCIAL) && !ViewGuides.shadeOutside(ViewGuides.SAFE));
        check("Off draws nothing", ViewGuides.lines(ViewGuides.OFF).length == 0 && ViewGuides.rects(ViewGuides.OFF, 1080, 1920).length == 0);
        System.out.println(count + " passed, 0 failed");
    }
}
