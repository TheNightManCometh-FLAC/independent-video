package com.christopher.strata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Streaming 8-bit RGBA (or RGB) PNG encoder, compressed in parallel the way pigz does it: the
 * rows are cut into blocks, each block is raw-deflated on its own thread with the previous 32 KB
 * of the stream as a preset dictionary and ends on a sync flush, so the blocks concatenate into
 * one valid zlib stream. Adler-32 is computed in order on the writer thread.
 * Per row the filter with the smallest sum of |signed bytes| is chosen (libpng's heuristic).
 */
final class PngStream extends ImageStream {
    private static final int WINDOW = 32768;

    private final OutputStream out;
    private final boolean alpha;
    private final int bpp, rowBytes, blockRows, batchRows, level;
    private final byte[] raw;              // batchRows rows, unfiltered
    private int bufRows;
    private byte[] prevRaw;                // last raw row before this batch (null at the top)
    private byte[] dict = new byte[0];     // last <= 32 KB of the filtered stream so far
    private final Adler32 adler = new Adler32();
    private final ExecutorService pool;
    private boolean headerDone, finished;

    PngStream(OutputStream out, int w, int h, boolean alpha, int threads) throws IOException {
        this(out, w, h, alpha, threads, 6, 0);
    }

    /** blockRowsHint > 0 forces small blocks (tests). */
    PngStream(OutputStream out, int w, int h, boolean alpha, int threads, int level, int blockRowsHint) throws IOException {
        super(w, h);
        this.out = out;
        this.alpha = alpha;
        this.level = level;
        bpp = alpha ? 4 : 3;
        long rb = (long) w * bpp;
        if (rb > Integer.MAX_VALUE / 4) throw new IOException("image too wide");
        rowBytes = (int) rb;
        threads = Math.max(1, threads);
        blockRows = blockRowsHint > 0 ? blockRowsHint : Math.max(1, (1 << 21) / (rowBytes + 1));   // ~2 MB a block
        batchRows = blockRows * threads;
        raw = new byte[batchRows * rowBytes];
        pool = pool(threads, "png");
        writeHeader();
    }

    @Override void rows(int[] argb, int off, int n) throws IOException {
        for (int r = 0; r < n; r++) {
            if (rowsIn >= h) throw new IOException("more rows than the image has");
            int o = bufRows * rowBytes, s = off + r * w;
            if (alpha) {
                for (int x = 0; x < w; x++) {
                    int p = argb[s + x];
                    raw[o++] = (byte) (p >> 16); raw[o++] = (byte) (p >> 8); raw[o++] = (byte) p; raw[o++] = (byte) (p >>> 24);
                }
            } else {
                for (int x = 0; x < w; x++) {
                    int p = argb[s + x];
                    raw[o++] = (byte) (p >> 16); raw[o++] = (byte) (p >> 8); raw[o++] = (byte) p;
                }
            }
            bufRows++;
            rowsIn++;
            if (bufRows == batchRows) flush(rowsIn == h);
        }
    }

    @Override void finish() throws IOException {
        if (finished) return;
        try {
            if (rowsIn != h) throw new IOException("image ended after " + rowsIn + " of " + h + " rows");
            if (bufRows > 0) flush(true);
            long a = adler.getValue();
            chunk("IDAT", new byte[]{(byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a});
            chunk("IEND", new byte[0]);
            finished = true;
        } finally {
            shutdown();
        }
    }

    @Override void shutdown() { pool.shutdownNow(); }

    private void flush(final boolean last) throws IOException {
        if (bufRows == 0) return;
        final int nb = (bufRows + blockRows - 1) / blockRows;
        final byte[][] filt = new byte[nb][];
        try {
            // 1. filter every block in parallel (each needs only the raw row above it)
            ArrayList<Future<?>> fs = new ArrayList<Future<?>>();
            for (int j = 0; j < nb; j++) {
                final int jj = j;
                fs.add(pool.submit(new Callable<Object>() {
                    @Override public Object call() {
                        int r0 = jj * blockRows, r1 = Math.min(bufRows, r0 + blockRows);
                        byte[] f = new byte[(r1 - r0) * (rowBytes + 1)];
                        for (int r = r0; r < r1; r++) filterRow(r, f, (r - r0) * (rowBytes + 1));
                        filt[jj] = f;
                        return null;
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
            // 2. deflate every block in parallel, primed with the 32 KB before it
            ArrayList<Future<byte[]>> zs = new ArrayList<Future<byte[]>>();
            byte[] before = dict;
            for (int j = 0; j < nb; j++) {
                final byte[] d = before, in = filt[j];
                final boolean end = last && j == nb - 1;
                zs.add(pool.submit(new Callable<byte[]>() {
                    @Override public byte[] call() { return deflate(in, d, end); }
                }));
                before = tail(before, in);
            }
            for (int j = 0; j < nb; j++) {
                adler.update(filt[j], 0, filt[j].length);
                byte[] z = zs.get(j).get();
                if (!headerDone) {
                    byte[] zz = new byte[z.length + 2];
                    zz[0] = 0x78; zz[1] = (byte) 0x9C;   // zlib: deflate, 32 K window, default level, no dict
                    System.arraycopy(z, 0, zz, 2, z.length);
                    z = zz;
                    headerDone = true;
                }
                chunk("IDAT", z);
            }
            dict = before;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw unwrap(e);
        }
        prevRaw = java.util.Arrays.copyOfRange(raw, (bufRows - 1) * rowBytes, bufRows * rowBytes);
        bufRows = 0;
    }

    private byte[] deflate(byte[] in, byte[] dict, boolean end) {
        Deflater d = new Deflater(level, true);
        try {
            d.setStrategy(Deflater.FILTERED);
            if (dict.length > 0) d.setDictionary(dict);
            d.setInput(in);
            ByteArrayOutputStream o = new ByteArrayOutputStream(in.length / 2 + 64);
            byte[] b = new byte[1 << 16];
            if (end) {
                d.finish();
                while (!d.finished()) { int n = d.deflate(b); o.write(b, 0, n); }
            } else {
                // The first call after setStrategy only applies the parameter change and can return
                // 0 bytes with the input untouched, so loop until the input is consumed AND flushed.
                int n;
                do { n = d.deflate(b, 0, b.length, Deflater.SYNC_FLUSH); o.write(b, 0, n); } while (n == b.length || !d.needsInput());
            }
            return o.toByteArray();
        } finally {
            d.end();
        }
    }

    /** Last 32 KB of (a ++ b). */
    private static byte[] tail(byte[] a, byte[] b) {
        if (b.length >= WINDOW) return java.util.Arrays.copyOfRange(b, b.length - WINDOW, b.length);
        int fromA = Math.min(a.length, WINDOW - b.length);
        byte[] t = new byte[fromA + b.length];
        System.arraycopy(a, a.length - fromA, t, 0, fromA);
        System.arraycopy(b, 0, t, fromA, b.length);
        return t;
    }

    private void filterRow(int r, byte[] f, int o) {
        int s = r * rowBytes;
        byte[] up = r > 0 ? raw : prevRaw;
        int us = r > 0 ? s - rowBytes : 0;
        // cost of each filter = sum of |signed residual|
        long c0 = 0, c1 = 0, c2 = 0, c3 = 0, c4 = 0;
        for (int i = 0; i < rowBytes; i++) {
            int x = raw[s + i] & 255;
            int a = i >= bpp ? raw[s + i - bpp] & 255 : 0;
            int b = up != null ? up[us + i] & 255 : 0;
            int c = up != null && i >= bpp ? up[us + i - bpp] & 255 : 0;
            c0 += Math.abs((byte) x);
            c1 += Math.abs((byte) (x - a));
            c2 += Math.abs((byte) (x - b));
            c3 += Math.abs((byte) (x - ((a + b) >> 1)));
            c4 += Math.abs((byte) (x - paeth(a, b, c)));
        }
        int best = 0;
        long bc = c0;
        if (c1 < bc) { best = 1; bc = c1; }
        if (c2 < bc) { best = 2; bc = c2; }
        if (c3 < bc) { best = 3; bc = c3; }
        if (c4 < bc) { best = 4; }
        f[o++] = (byte) best;
        for (int i = 0; i < rowBytes; i++) {
            int x = raw[s + i] & 255;
            int a = i >= bpp ? raw[s + i - bpp] & 255 : 0;
            int b = up != null ? up[us + i] & 255 : 0;
            int c = up != null && i >= bpp ? up[us + i - bpp] & 255 : 0;
            int p;
            switch (best) {
                case 1: p = a; break;
                case 2: p = b; break;
                case 3: p = (a + b) >> 1; break;
                case 4: p = paeth(a, b, c); break;
                default: p = 0;
            }
            f[o + i] = (byte) (x - p);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
    }

    // ------------------------------------------------------------------ chunks
    private void writeHeader() throws IOException {
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] ih = new byte[13];
        be32(ih, 0, w); be32(ih, 4, h);
        ih[8] = 8; ih[9] = (byte) (alpha ? 6 : 2); ih[10] = 0; ih[11] = 0; ih[12] = 0;
        chunk("IHDR", ih);
        chunk("sRGB", new byte[]{0});   // perceptual: the pixels are sRGB
    }

    private void chunk(String type, byte[] data) throws IOException {
        byte[] hd = new byte[8];
        be32(hd, 0, data.length);
        for (int i = 0; i < 4; i++) hd[4 + i] = (byte) type.charAt(i);
        CRC32 crc = new CRC32();
        crc.update(hd, 4, 4);
        crc.update(data, 0, data.length);
        out.write(hd);
        out.write(data);
        byte[] c = new byte[4];
        be32(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void be32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
}
