package com.christopher.strata;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * 3D colour lookup tables from Adobe/Resolve .cube files (LUT_3D_SIZE, optional DOMAIN_MIN/MAX,
 * red varying fastest). Stored as an RGBA half-float atlas of size*size x size texels:
 * texel (r + b*size, g). Files live in the app's luts folder, shared by all projects.
 */
final class Lut {
    static File dir;
    private static final HashMap<String, Lut> cache = new HashMap<String, Lut>();

    final String name;
    final int size;
    final short[] data;
    /**
     * Input domain (Cube spec 5.6): the colour range the table's grid spans. Table values are
     * outputs and are used as they are; the domain only maps an input colour to grid coordinates.
     */
    final float[] dmin, dmax;

    private Lut(String name, int size, short[] data, float[] dmin, float[] dmax) {
        this.name = name; this.size = size; this.data = data; this.dmin = dmin; this.dmax = dmax;
    }

    static synchronized Lut get(String name) {
        if (name == null || name.isEmpty()) return null;
        Lut l = cache.get(name);
        if (l != null) return l;
        try {
            l = parse(new File(dir, name), name);
            cache.put(name, l);
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    static ArrayList<String> list() {
        ArrayList<String> out = new ArrayList<String>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().toLowerCase().endsWith(".cube")) out.add(f.getName());
        Collections.sort(out);
        return out;
    }

    static String displayName(String file) {
        if (file == null) return "None";
        int d = file.lastIndexOf('.');
        return d > 0 ? file.substring(0, d) : file;
    }

    static Lut parse(File f, String name) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            int size = 0, n = 0;
            float[] dmin = {0, 0, 0}, dmax = {1, 1, 1};
            float[] vals = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                char c = line.charAt(0);
                if (Character.isLetter(c)) {
                    String[] t = line.split("\\s+");
                    if (t[0].equals("LUT_3D_SIZE")) {
                        size = Integer.parseInt(t[1]);
                        if (size < 2 || size > 128) throw new IOException("unsupported LUT size " + size);
                        vals = new float[size * size * size * 3];
                    } else if (t[0].equals("DOMAIN_MIN") && t.length >= 4) {
                        for (int i = 0; i < 3; i++) dmin[i] = Float.parseFloat(t[i + 1]);
                    } else if (t[0].equals("DOMAIN_MAX") && t.length >= 4) {
                        for (int i = 0; i < 3; i++) dmax[i] = Float.parseFloat(t[i + 1]);
                    } else if (t[0].equals("LUT_3D_INPUT_RANGE") && t.length >= 3) {   // Resolve's spelling of the same thing
                        for (int i = 0; i < 3; i++) { dmin[i] = Float.parseFloat(t[1]); dmax[i] = Float.parseFloat(t[2]); }
                    } else if (t[0].equals("LUT_1D_SIZE")) {
                        throw new IOException("1D LUTs aren't supported, only 3D .cube");
                    }
                    continue;
                }
                if (vals == null) continue;
                String[] t = line.split("\\s+");
                if (t.length < 3 || n >= vals.length) continue;
                vals[n++] = Float.parseFloat(t[0]);
                vals[n++] = Float.parseFloat(t[1]);
                vals[n++] = Float.parseFloat(t[2]);
            }
            if (vals == null || n != vals.length) throw new IOException("incomplete LUT (" + n / 3 + " entries)");
            for (int i = 0; i < 3; i++)
                if (!(dmax[i] > dmin[i]) || Float.isInfinite(dmin[i]) || Float.isInfinite(dmax[i])) throw new IOException("invalid DOMAIN_MIN / DOMAIN_MAX");
            for (float v : vals) if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 65000) throw new IOException("the LUT contains invalid numbers");
            // table values are outputs: stored as they are; cube order: r fastest, then g, then b
            short[] out = new short[size * size * size * 4];
            int W = size * size;
            for (int b = 0; b < size; b++)
                for (int g = 0; g < size; g++)
                    for (int rr = 0; rr < size; rr++) {
                        int i = ((b * size + g) * size + rr) * 3;
                        int o = (g * W + rr + b * size) * 4;
                        for (int ch = 0; ch < 3; ch++) out[o + ch] = Curves.toHalf(vals[i + ch]);
                        out[o + 3] = Curves.toHalf(1f);
                    }
            return new Lut(name, size, out, dmin, dmax);
        } finally { r.close(); }
    }

    /** Output at grid point (r, g, b), channel ch. */
    float at(int r, int g, int b, int ch) { return fromHalf(data[(g * size * size + r + b * size) * 4 + ch]); }

    /**
     * This LUT on a smaller grid (n per axis), trilinearly resampled: for GPUs whose texture
     * limit can't hold the size^2-wide atlas (65^3 needs 4225 px, 128^3 16384 px).
     */
    Lut resampled(int n) {
        if (n >= size) return this;
        short[] out = new short[n * n * n * 4];
        int W = n * n;
        for (int b = 0; b < n; b++)
            for (int g = 0; g < n; g++)
                for (int r = 0; r < n; r++) {
                    float fr = r * (size - 1f) / (n - 1), fg = g * (size - 1f) / (n - 1), fb = b * (size - 1f) / (n - 1);
                    int r0 = Math.min((int) fr, size - 2), g0 = Math.min((int) fg, size - 2), b0 = Math.min((int) fb, size - 2);
                    float tr = fr - r0, tg = fg - g0, tb = fb - b0;
                    int o = (g * W + r + b * n) * 4;
                    for (int ch = 0; ch < 3; ch++) {
                        float c00 = at(r0, g0, b0, ch) * (1 - tr) + at(r0 + 1, g0, b0, ch) * tr;
                        float c10 = at(r0, g0 + 1, b0, ch) * (1 - tr) + at(r0 + 1, g0 + 1, b0, ch) * tr;
                        float c01 = at(r0, g0, b0 + 1, ch) * (1 - tr) + at(r0 + 1, g0, b0 + 1, ch) * tr;
                        float c11 = at(r0, g0 + 1, b0 + 1, ch) * (1 - tr) + at(r0 + 1, g0 + 1, b0 + 1, ch) * tr;
                        float c0 = c00 * (1 - tg) + c10 * tg, c1 = c01 * (1 - tg) + c11 * tg;
                        out[o + ch] = Curves.toHalf(c0 * (1 - tb) + c1 * tb);
                    }
                    out[o + 3] = Curves.toHalf(1f);
                }
        return new Lut(name, n, out, dmin, dmax);
    }

    /** Largest grid whose atlas (n^2 x n) fits a maxTex texture. */
    static int fitSize(int size, int maxTex) {
        int n = size;
        while (n > 2 && n * n > maxTex) n--;
        return n;
    }

    static float fromHalf(short h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
        float v;
        if (e == 0) v = m / 1024f * (float) Math.pow(2, -14);
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = (1 + m / 1024f) * (float) Math.pow(2, e - 15);
        return s == 1 ? -v : v;
    }
}
