package com.christopher.strata;

import java.io.*;

/**
 * Helper for tests/maskdemo.py: the text-mask texture pipeline on desktop, with the text drawn by
 * PIL instead of Android's canvas. "layout <aspect>" prints the texture layout MaskRaster uses;
 * "sdf <coverage.pgm at 2x> <pad> <out.pgm>" runs MaskRaster.sdf on it.
 */
public class MaskDemo {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("layout")) {
            int[] bs = MaskRaster.boxSize(Float.parseFloat(a[1]));
            int pad = Math.round(0.25f * Math.min(bs[0], bs[1]) + 6);
            System.out.println(bs[0] + " " + bs[1] + " " + pad);
            return;
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[1])));
        String magic = tok(in); int W = Integer.parseInt(tok(in)), H = Integer.parseInt(tok(in)); tok(in);
        byte[] cov = new byte[W * H];
        in.readFully(cov);
        in.close();
        byte[] s = MaskRaster.sdf(cov, W, H, Float.parseFloat(a[2]));
        FileOutputStream o = new FileOutputStream(a[3]);
        o.write(("P5\n" + (W / 2) + " " + (H / 2) + "\n255\n").getBytes("US-ASCII"));
        o.write(s);
        o.close();
    }

    static String tok(DataInputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) { if (Character.isWhitespace(c)) { if (b.length() > 0) break; } else b.append((char) c); }
        return b.toString();
    }
}
