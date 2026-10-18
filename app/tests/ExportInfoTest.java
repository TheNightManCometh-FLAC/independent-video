package com.christopher.strata;

/** 3.13: the export dialog's bitrate and size use the exporter's own numbers. */
public class ExportInfoTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) {
        int hi = Exporter.videoBitrate(1920, 1080, 30, 1, false);
        check("1080p30 High H.264 = 0.25 bit per pixel per frame (15.6 Mbps)", hi == (int) (1920 * 1080 * 30 * 0.25));
        check("HEVC asks for 60 % of that", Exporter.videoBitrate(1920, 1080, 30, 1, true) == (int) (1920.0 * 1080 * 30 * 0.25f * 0.6));
        check("4K60 Max is capped at 120 Mbps", Exporter.videoBitrate(3840, 2160, 60, 2, false) == 120000000);
        long one = Exporter.estimateBytes(60000000L, 8000000, false);
        check("8 Mbps for a minute is about 60 MB (+1 % container)", one == (long) (8e6 * 60 / 8 * 1.01));
        check("Audio adds 256 kbps", Exporter.estimateBytes(60000000L, 8000000, true) - one == (long) (8256000.0 * 60 / 8 * 1.01) - one);
        check("Sizes read naturally", Exporter.sizeText(60600000L).equals("61 MB") && Exporter.sizeText(1234000000L).equals("1.2 GB") && Exporter.sizeText(900).equals("1 kB"));
        System.out.println(count + " passed, 0 failed");
    }
}
