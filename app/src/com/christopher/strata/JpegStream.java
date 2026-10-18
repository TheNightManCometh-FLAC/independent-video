package com.christopher.strata;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Baseline JFIF encoder that streams. The restart interval is one row of MCUs, so every MCU row
 * is an independent entropy-coded segment: rows are encoded in parallel and written in order
 * with RSTn markers between them. Memory is a few MCU rows per worker regardless of image size.
 *
 * 4:4:4 chroma at quality >= 90 (artwork keeps clean colour edges), 4:2:0 below.
 * Standard Annex K Huffman tables, IJG quality scaling, float AAN forward DCT.
 */
final class JpegStream extends ImageStream {
    private final OutputStream out;
    private final boolean sub;               // 4:2:0
    private final int mcuW, mcuH, mcusX, mcuRows;
    private final float[] fqY = new float[64], fqC = new float[64];
    private final int batch;                 // MCU rows per parallel batch
    private final int[] buf;                 // batch * mcuH rows of pixels
    private int bufRows, mcuRowsDone;
    private final ExecutorService pool;
    private boolean finished;

    JpegStream(OutputStream out, int w, int h, int quality, int threads) throws IOException {
        super(w, h);
        if (w > 65535 || h > 65535) throw new IOException("JPEG can't be larger than 65535 px on a side; use PNG");
        this.out = out;
        sub = quality < 90;
        mcuW = mcuH = sub ? 16 : 8;
        mcusX = (w + mcuW - 1) / mcuW;
        mcuRows = (h + mcuH - 1) / mcuH;
        threads = Math.max(1, threads);
        batch = threads * 2;
        buf = new int[w * mcuH * batch];
        pool = pool(threads, "jpeg");
        int[] qy = new int[64], qc = new int[64];
        scaleQuant(LUMA_Q, quality, qy);
        scaleQuant(CHROMA_Q, quality, qc);
        for (int r = 0, i = 0; r < 8; r++)
            for (int c = 0; c < 8; c++, i++) {
                fqY[i] = (float) (1.0 / (qy[i] * AAN[r] * AAN[c] * 8.0));
                fqC[i] = (float) (1.0 / (qc[i] * AAN[r] * AAN[c] * 8.0));
            }
        writeHeader(qy, qc);
    }

    // ------------------------------------------------------------------ feeding
    @Override void rows(int[] argb, int off, int n) throws IOException {
        while (n > 0) {
            if (rowsIn >= h) throw new IOException("more rows than the image has");
            int k = Math.min(n, batch * mcuH - bufRows);
            System.arraycopy(argb, off, buf, bufRows * w, k * w);
            bufRows += k; rowsIn += k; off += k * w; n -= k;
            if (bufRows == batch * mcuH) flush();
        }
    }

    @Override void finish() throws IOException {
        if (finished) return;
        try {
            if (rowsIn != h) throw new IOException("image ended after " + rowsIn + " of " + h + " rows");
            if (bufRows > 0) {
                // pad the last MCU row by repeating the bottom row (no dark fringe from zeros)
                int need = (bufRows + mcuH - 1) / mcuH * mcuH;
                for (int r = bufRows; r < need; r++) System.arraycopy(buf, (bufRows - 1) * w, buf, r * w, w);
                bufRows = need;
                flush();
            }
            out.write(new byte[]{(byte) 0xFF, (byte) 0xD9});
            finished = true;
        } finally {
            shutdown();
        }
    }

    @Override void shutdown() { pool.shutdownNow(); }

    private void flush() throws IOException {
        int n = bufRows / mcuH;
        ArrayList<Future<byte[]>> fs = new ArrayList<Future<byte[]>>(n);
        for (int i = 0; i < n; i++) {
            final int base = i * mcuH;
            fs.add(pool.submit(new Callable<byte[]>() {
                @Override public byte[] call() { return encodeMcuRow(base); }
            }));
        }
        try {
            for (int i = 0; i < n; i++) {
                byte[] b = fs.get(i).get();
                out.write(b);
                int g = mcuRowsDone++;
                if (g < mcuRows - 1) out.write(new byte[]{(byte) 0xFF, (byte) (0xD0 + (g & 7))});
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw unwrap(e);
        }
        bufRows = 0;
    }

    // ------------------------------------------------------------------ one MCU row
    private byte[] encodeMcuRow(int rowBase) {
        Bits bw = new Bits(w * mcuH * 3 / 4 + 1024);
        float[] Y = new float[mcuW * mcuH], Cb = new float[mcuW * mcuH], Cr = new float[mcuW * mcuH];
        float[] blk = new float[64];
        int[] q = new int[64];
        int[] pred = new int[3];
        for (int mx = 0; mx < mcusX; mx++) {
            int x0 = mx * mcuW;
            for (int yy = 0; yy < mcuH; yy++) {
                int row = (rowBase + yy) * w;
                for (int xx = 0; xx < mcuW; xx++) {
                    int p = buf[row + Math.min(w - 1, x0 + xx)];
                    float r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                    int i = yy * mcuW + xx;
                    Y[i] = 0.299f * r + 0.587f * g + 0.114f * b - 128f;
                    Cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b;
                    Cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b;
                }
            }
            if (!sub) {
                block(Y, 8, 0, 0, blk); code(blk, fqY, q, bw, pred, 0, DC_Y, AC_Y);
                block(Cb, 8, 0, 0, blk); code(blk, fqC, q, bw, pred, 1, DC_C, AC_C);
                block(Cr, 8, 0, 0, blk); code(blk, fqC, q, bw, pred, 2, DC_C, AC_C);
            } else {
                for (int by = 0; by < 2; by++)
                    for (int bx = 0; bx < 2; bx++) { block(Y, 16, bx * 8, by * 8, blk); code(blk, fqY, q, bw, pred, 0, DC_Y, AC_Y); }
                down(Cb, blk); code(blk, fqC, q, bw, pred, 1, DC_C, AC_C);
                down(Cr, blk); code(blk, fqC, q, bw, pred, 2, DC_C, AC_C);
            }
        }
        bw.pad();
        return bw.bytes();
    }

    private static void block(float[] src, int stride, int x0, int y0, float[] blk) {
        for (int y = 0; y < 8; y++) System.arraycopy(src, (y0 + y) * stride + x0, blk, y * 8, 8);
    }

    /** 16x16 chroma (centred on 0) -> 8x8 by 2x2 box average. */
    private static void down(float[] src, float[] blk) {
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int i = y * 32 + x * 2;
                blk[y * 8 + x] = (src[i] + src[i + 1] + src[i + 16] + src[i + 17]) * 0.25f;
            }
    }

    private static void code(float[] blk, float[] fq, int[] q, Bits bw, int[] pred, int comp, int[][] dc, int[][] ac) {
        fdct(blk);
        for (int i = 0; i < 64; i++) {
            int v = Math.round(blk[i] * fq[i]);
            q[i] = v > 1023 ? 1023 : (v < -1023 ? -1023 : v);
        }
        int diff = q[0] - pred[comp];
        pred[comp] = q[0];
        int ad = diff < 0 ? -diff : diff, nb = 32 - Integer.numberOfLeadingZeros(ad);
        bw.put(dc[0][nb], dc[1][nb]);
        if (nb > 0) bw.put((diff < 0 ? diff - 1 : diff) & ((1 << nb) - 1), nb);
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = q[ZZ[k]];
            if (v == 0) { run++; continue; }
            while (run > 15) { bw.put(ac[0][0xF0], ac[1][0xF0]); run -= 16; }
            int av = v < 0 ? -v : v;
            nb = 32 - Integer.numberOfLeadingZeros(av);
            int s = (run << 4) | nb;
            bw.put(ac[0][s], ac[1][s]);
            bw.put((v < 0 ? v - 1 : v) & ((1 << nb) - 1), nb);
            run = 0;
        }
        if (run > 0) bw.put(ac[0][0], ac[1][0]);
    }

    /** Float AAN forward DCT in place (IJG jfdctflt); output scaled by 8 * AAN[r] * AAN[c]. */
    static void fdct(float[] d) {
        for (int p = 0; p < 64; p += 8) pass(d, p, 1);
        for (int p = 0; p < 8; p++) pass(d, p, 8);
    }

    private static void pass(float[] d, int o, int s) {
        float t0 = d[o] + d[o + 7 * s], t7 = d[o] - d[o + 7 * s];
        float t1 = d[o + s] + d[o + 6 * s], t6 = d[o + s] - d[o + 6 * s];
        float t2 = d[o + 2 * s] + d[o + 5 * s], t5 = d[o + 2 * s] - d[o + 5 * s];
        float t3 = d[o + 3 * s] + d[o + 4 * s], t4 = d[o + 3 * s] - d[o + 4 * s];
        float t10 = t0 + t3, t13 = t0 - t3, t11 = t1 + t2, t12 = t1 - t2;
        d[o] = t10 + t11;
        d[o + 4 * s] = t10 - t11;
        float z1 = (t12 + t13) * 0.707106781f;
        d[o + 2 * s] = t13 + z1;
        d[o + 6 * s] = t13 - z1;
        t10 = t4 + t5; t11 = t5 + t6; t12 = t6 + t7;
        float z5 = (t10 - t12) * 0.382683433f;
        float z2 = 0.541196100f * t10 + z5, z4 = 1.306562965f * t12 + z5, z3 = t11 * 0.707106781f;
        float z11 = t7 + z3, z13 = t7 - z3;
        d[o + 5 * s] = z13 + z2;
        d[o + 3 * s] = z13 - z2;
        d[o + s] = z11 + z4;
        d[o + 7 * s] = z11 - z4;
    }

    // ------------------------------------------------------------------ bit writer with 0xFF stuffing
    static final class Bits {
        private byte[] b;
        private int n;
        private long acc;
        private int cnt;

        Bits(int cap) { b = new byte[Math.max(64, cap)]; }

        void put(int code, int len) {
            acc = (acc << len) | (code & ((1L << len) - 1));
            cnt += len;
            while (cnt >= 8) {
                cnt -= 8;
                emit((int) (acc >>> cnt) & 255);
            }
        }

        private void emit(int v) {
            if (n + 2 > b.length) b = java.util.Arrays.copyOf(b, b.length * 2);
            b[n++] = (byte) v;
            if (v == 0xFF) b[n++] = 0;
        }

        /** Fills the last byte with 1 bits, as the standard requires before a marker. */
        void pad() { if (cnt > 0) put((1 << (8 - cnt)) - 1, 8 - cnt); }

        byte[] bytes() { return java.util.Arrays.copyOf(b, n); }
    }

    // ------------------------------------------------------------------ headers
    private void writeHeader(int[] qy, int[] qc) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(1024);
        o.write(0xFF); o.write(0xD8);
        // APP0 JFIF 1.01, no density
        o.write(new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        // DQT, both tables, zigzag order
        o.write(0xFF); o.write(0xDB); u16(o, 2 + 65 * 2);
        o.write(0); for (int k = 0; k < 64; k++) o.write(qy[ZZ[k]]);
        o.write(1); for (int k = 0; k < 64; k++) o.write(qc[ZZ[k]]);
        // SOF0
        o.write(0xFF); o.write(0xC0); u16(o, 17); o.write(8); u16(o, h); u16(o, w); o.write(3);
        o.write(1); o.write(sub ? 0x22 : 0x11); o.write(0);
        o.write(2); o.write(0x11); o.write(1);
        o.write(3); o.write(0x11); o.write(1);
        // DHT
        o.write(0xFF); o.write(0xC4);
        u16(o, 2 + 4 * 17 + DC_Y_VALS.length + AC_Y_VALS.length + DC_C_VALS.length + AC_C_VALS.length);
        dht(o, 0x00, DC_Y_BITS, DC_Y_VALS); dht(o, 0x10, AC_Y_BITS, AC_Y_VALS);
        dht(o, 0x01, DC_C_BITS, DC_C_VALS); dht(o, 0x11, AC_C_BITS, AC_C_VALS);
        // DRI: one MCU row per restart interval
        o.write(0xFF); o.write(0xDD); u16(o, 4); u16(o, mcusX);
        // SOS
        o.write(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});
        out.write(o.toByteArray());
    }

    private static void u16(java.io.ByteArrayOutputStream o, int v) { o.write((v >> 8) & 255); o.write(v & 255); }

    private static void dht(java.io.ByteArrayOutputStream o, int id, int[] bits, int[] vals) {
        o.write(id);
        for (int i = 0; i < 16; i++) o.write(bits[i]);
        for (int v : vals) o.write(v);
    }

    static void scaleQuant(int[] base, int quality, int[] out) {
        int q = Math.max(1, Math.min(100, quality));
        int scale = q < 50 ? 5000 / q : 200 - 2 * q;
        for (int i = 0; i < 64; i++) out[i] = Math.max(1, Math.min(255, (base[i] * scale + 50) / 100));
    }

    /** Annex C: code lengths in BITS order -> {codes[symbol], lengths[symbol]}. */
    static int[][] huff(int[] bits, int[] vals) {
        int[] code = new int[256], size = new int[256];
        int c = 0, k = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len - 1]; i++) {
                code[vals[k]] = c++;
                size[vals[k]] = len;
                k++;
            }
            c <<= 1;
        }
        return new int[][]{code, size};
    }

    // ------------------------------------------------------------------ tables
    static final double[] AAN = {1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379};

    /** ZZ[k] = natural (row-major) index of the k-th coefficient in zigzag order. */
    static final int[] ZZ = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    static final int[] LUMA_Q = {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

    static final int[] CHROMA_Q = {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    static final int[] DC_Y_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    static final int[] DC_Y_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static final int[] DC_C_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    static final int[] DC_C_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static final int[] AC_Y_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    static final int[] AC_Y_VALS = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};
    static final int[] AC_C_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    static final int[] AC_C_VALS = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    static final int[][] DC_Y = huff(DC_Y_BITS, DC_Y_VALS), AC_Y = huff(AC_Y_BITS, AC_Y_VALS);
    static final int[][] DC_C = huff(DC_C_BITS, DC_C_VALS), AC_C = huff(AC_C_BITS, AC_C_VALS);
}
