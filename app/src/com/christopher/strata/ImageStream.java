package com.christopher.strata;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * An image encoder fed top to bottom, a few rows at a time, so an export never needs the whole
 * picture in memory. Rows are unpremultiplied ARGB ints (Bitmap.getPixels order), stride = width.
 * Pure Java (no android.*) so it can be tested on a desktop JVM.
 */
abstract class ImageStream {
    final int w, h;
    protected int rowsIn;

    ImageStream(int w, int h) {
        if (w < 1 || h < 1) throw new IllegalArgumentException("empty image");
        this.w = w;
        this.h = h;
    }

    /** n rows starting at argb[off], stride w. Rows must arrive in order. */
    abstract void rows(int[] argb, int off, int n) throws IOException;

    /** Call once every row has been given; writes the trailer. Does not close the stream. */
    abstract void finish() throws IOException;

    /** Stops the worker threads (also called by finish). Safe to call more than once. */
    abstract void shutdown();

    static int threadsFor() {
        // leave one core for the GL thread rendering the next band
        return Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
    }

    static ExecutorService pool(int threads, final String name) {
        return Executors.newFixedThreadPool(threads, new ThreadFactory() {
            int n;
            @Override public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + (n++));
                t.setDaemon(true);
                return t;
            }
        });
    }

    static IOException unwrap(Exception e) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        if (c instanceof IOException) return (IOException) c;
        if (c instanceof OutOfMemoryError) return new IOException("not enough memory to encode");
        return new IOException(c.toString(), c);
    }
}
