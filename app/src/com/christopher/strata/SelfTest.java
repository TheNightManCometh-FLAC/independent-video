package com.christopher.strata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device check of the video pipeline, for a phone nobody can attach a debugger to. Generates a
 * test picture and a click track, then runs the real Exporter three times:
 *  A. photo + title + visualizer + music + beat pulse -> H.264 720p30 (encoder, GL, audio mix)
 *  B. two clips cut from A with a dissolve and a 2x speed-up -> (decoder/SurfaceTexture, WSOLA)
 *  C. A re-framed to 9:16 1080p as HEVC
 * and checks each result: size, duration, orientation and colour of known regions, audio
 * present, audio/video track lengths, and where the clicks land relative to the beat grid.
 * The report is plain text so it can be copied straight into a message.
 */
final class SelfTest implements Runnable {
    interface Out {
        void status(String s);
        void done(String report);
    }

    private static final int BPM = 120;
    private final Context ctx;
    private final Out out;
    final AtomicBoolean cancel = new AtomicBoolean();
    private volatile Exporter current;

    /** Stops the test, including an export in progress. */
    void stop() {
        cancel.set(true);
        Exporter e = current;
        if (e != null) e.cancel.set(true);
    }
    private final StringBuilder rep = new StringBuilder();
    private int pass, fail;
    private boolean glShown;
    private File dir;

    SelfTest(Context ctx, Out out) {
        this.ctx = ctx.getApplicationContext();
        this.out = out;
    }

    private void line(String s) { rep.append(s).append('\n'); }

    private void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        line((ok ? "PASS  " : "FAIL  ") + name + (detail == null || detail.isEmpty() ? "" : "  — " + detail));
    }

    private static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        String s = w.toString();
        return s.length() > 2500 ? s.substring(0, 2500) + "\n…" : s;
    }

    @Override
    public void run() {
        long t0 = System.currentTimeMillis();
        try {
            header();
            dir = new File(ctx.getCacheDir(), "selftest");
            MainActivity.deleteDir(dir);
            dir.mkdirs();
            out.status("Making test media…");
            File png = new File(dir, "pattern.png");
            File wav = new File(dir, "clicks.wav");
            writePattern(png, 1280, 720);
            writeClicks(wav, 8);
            analysisTests(wav);
            encoderTests();
            if (cancel.get()) throw new Exception("cancelled");
            Uri a = exportA(png, wav);
            if (a != null && !cancel.get()) exportB(a);
            if (a != null && !cancel.get()) exportC(a);
        } catch (Throwable t) {
            fail++;
            line("FAIL  self-test stopped: " + t);
            line(stack(t));
        }
        line("");
        line(String.format(Locale.US, "%d passed, %d failed, %.0f s", pass, fail, (System.currentTimeMillis() - t0) / 1000.0));
        if (fail == 0) line("Test videos are in Movies/Independent Video if you want to look at them; delete them whenever.");
        MainActivity.deleteDir(dir);
        out.done(rep.toString());
    }

    // ================================================================ photo encoders
    /**
     * The streaming JPEG/PNG encoders on this phone's own zlib and threads: encode a test picture
     * (gradients, noise, hard edges, transparent ramp) in small pieces so batches and blocks
     * split many times, decode with Android's decoder and compare.
     */
    private void encoderTests() {
        out.status("Testing photo encoders…");
        int w = 1003, h = 517;
        int[] px = new int[w * h];
        java.util.Random rnd = new java.util.Random(7);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int r = (int) (128 + 100 * Math.sin(x / 37.0) * Math.cos(y / 23.0)), g = x * 255 / (w - 1), b = y * 255 / (h - 1);
                if (((x / 40) + (y / 40)) % 2 == 0) { r = r * 3 / 5; g = g * 3 / 5; b = b * 3 / 5; }
                r = Math.max(0, Math.min(255, r + rnd.nextInt(13) - 6));
                int a = y < h / 4 ? 0 : x * 255 / (w - 1);
                px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        for (int fmt = 0; fmt < 3; fmt++) {
            String label = fmt == 0 ? "PNG encoder (RGBA, lossless)" : fmt == 1 ? "JPEG encoder q95 (4:4:4)" : "JPEG encoder q80 (4:2:0)";
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                long t = System.nanoTime();
                ImageStream enc = fmt == 0 ? new PngStream(bo, w, h, true, ImageStream.threadsFor(), 6, 7)
                        : new JpegStream(bo, w, h, fmt == 1 ? 95 : 80, ImageStream.threadsFor());
                for (int y = 0; y < h; y += 13) enc.rows(px, y * w, Math.min(13, h - y));
                enc.finish();
                double ms = (System.nanoTime() - t) / 1e6;
                byte[] data = bo.toByteArray();
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPremultiplied = false;
                Bitmap d = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (d == null || d.getWidth() != w || d.getHeight() != h) { check(label, false, "Android couldn't decode it"); continue; }
                int[] got = new int[w * h];
                d.getPixels(got, 0, w, 0, 0, w, h);
                d.recycle();
                if (fmt == 0) {
                    int bad = 0;
                    for (int i = 0; i < got.length; i++) if (got[i] != px[i] && (px[i] >>> 24) != 0) bad++;
                    check(label, bad == 0, String.format(Locale.US, "%d KB in %.0f ms%s", data.length / 1024, ms, bad == 0 ? ", bit-exact" : ", " + bad + " pixels differ"));
                } else {
                    double se = 0;
                    for (int i = 0; i < got.length; i++)
                        for (int sh = 0; sh < 24; sh += 8) {
                            // JPEG has no alpha: compare against the colour the encoder was given
                            int e = ((got[i] >> sh) & 255) - ((px[i] >> sh) & 255);
                            se += e * e;
                        }
                    double psnr = 10 * Math.log10(255.0 * 255.0 / (se / (3.0 * got.length)));
                    check(label, psnr > (fmt == 1 ? 34 : 30), String.format(Locale.US, "%d KB in %.0f ms, %.1f dB", data.length / 1024, ms, psnr));
                }
            } catch (Throwable e) {
                check(label, false, e.toString());
            }
        }
        line("");
    }

    // ================================================================ environment
    private void header() {
        String ver = "?";
        try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        line("Independent Video " + ver + " self-test, " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()));
        line(Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT + ", board " + Build.BOARD + ", hw " + Build.HARDWARE);
        line("Max memory " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String[] mimes = {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC};
        for (String m : mimes) {
            String enc = list.findEncoderForFormat(MediaFormat.createVideoFormat(m, 1920, 1080));
            String dec = list.findDecoderForFormat(MediaFormat.createVideoFormat(m, 1920, 1080));
            String k4 = "?";
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder() || !ci.getName().equals(enc)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(m).getVideoCapabilities();
                    k4 = "4K30 " + (vc.areSizeAndRateSupported(3840, 2160, 30) ? "yes" : "no")
                            + ", 4K60 " + (vc.areSizeAndRateSupported(3840, 2160, 60) ? "yes" : "no")
                            + ", max bitrate " + vc.getBitrateRange().getUpper() / 1000000 + " Mbps";
                } catch (Exception e) { k4 = e.toString(); }
            }
            line(m.substring(6).toUpperCase(Locale.US) + ": encoder " + enc + " (" + k4 + "), decoder " + dec);
        }
        line("");
    }

    // ================================================================ test media
    /** Four coloured quadrants (so a flip or a swap shows up as a wrong colour) plus a grid. */
    private static final int TL = 0xffdc2828, TR = 0xff28c83c, BL = 0xff2846dc, BR = 0xffe6d228;

    private void writePattern(File f, int w, int h) throws IOException {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setColor(TL); c.drawRect(0, 0, w / 2f, h / 2f, p);
        p.setColor(TR); c.drawRect(w / 2f, 0, w, h / 2f, p);
        p.setColor(BL); c.drawRect(0, h / 2f, w / 2f, h, p);
        p.setColor(BR); c.drawRect(w / 2f, h / 2f, w, h, p);
        p.setColor(0x60ffffff);
        p.setStrokeWidth(2);
        for (int x = 0; x <= w; x += 80) c.drawLine(x, 0, x, h, p);
        for (int y = 0; y <= h; y += 80) c.drawLine(0, y, w, y, p);
        FileOutputStream o = new FileOutputStream(f);
        try { b.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { o.close(); }
        b.recycle();
    }

    /** 48 kHz stereo WAV: a low kick on every beat at 120 BPM over a quiet 220 Hz tone. */
    private void writeClicks(File f, int seconds) throws IOException {
        int sr = 48000, n = sr * seconds;
        byte[] d = new byte[n * 4];
        double beat = 60.0 / BPM;
        for (int i = 0; i < n; i++) {
            double t = i / (double) sr, bt = t % beat;
            double v = 0.08 * Math.sin(2 * Math.PI * 220 * t) + 0.8 * Math.exp(-bt / 0.05) * Math.sin(2 * Math.PI * 60 * bt);
            short s = (short) Math.max(-32767, Math.min(32767, Math.round(v * 32767)));
            d[i * 4] = (byte) s; d[i * 4 + 1] = (byte) (s >> 8);
            d[i * 4 + 2] = (byte) s; d[i * 4 + 3] = (byte) (s >> 8);
        }
        BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(f));
        try {
            o.write("RIFF".getBytes("US-ASCII")); le32(o, 36 + d.length); o.write("WAVE".getBytes("US-ASCII"));
            o.write("fmt ".getBytes("US-ASCII")); le32(o, 16); le16(o, 1); le16(o, 2); le32(o, sr); le32(o, sr * 4); le16(o, 4); le16(o, 16);
            o.write("data".getBytes("US-ASCII")); le32(o, d.length);
            o.write(d);
        } finally { o.close(); }
    }

    private static void le32(BufferedOutputStream o, int v) throws IOException { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void le16(BufferedOutputStream o, int v) throws IOException { o.write(v); o.write(v >> 8); }

    // ================================================================ analysis on the device's decoder
    private void analysisTests(File wav) {
        out.status("Analysing the test track…");
        Uri u = Uri.fromFile(wav);
        try {
            Object[] r = Media.analyze(ctx, u, true, cancel);
            check("decode WAV through MediaCodec", r != null, r == null ? "no audio" : ((float[]) r[0]).length + " peak frames");
            if (r != null) {
                Beats.Result b = Beats.analyze((float[]) r[1]);
                check("beat detection", Math.abs(b.bpm - BPM) < 2 && b.beats.length >= 12,
                        String.format(Locale.US, "%.1f BPM, %d beats, first at %.3f s", b.bpm, b.beats.length, b.beats.length > 0 ? b.beats[0] : -1f));
            }
        } catch (Throwable t) { check("decode WAV through MediaCodec", false, t.toString()); }
        try {
            new File(new File(ctx.getCacheDir(), "fx"), AudioFeatures.cacheName(u.toString())).delete();
            long t = System.currentTimeMillis();
            AudioFeatures fx = Media.songFeatures(ctx, u.toString(), cancel);
            float on = 0, off = 0;
            for (int k = 1; k < 14; k++) {
                on += fx.bassAt(k * 500000L + 20000, 0.05f);
                off += fx.bassAt(k * 500000L + 330000, 0.05f);
            }
            on /= 13; off /= 13;
            check("song analysis (visualizers)", on > 0.7f && off < 0.35f,
                    String.format(Locale.US, "bass on beats %.2f, between %.2f, %d ms", on, off, System.currentTimeMillis() - t));
        } catch (Throwable t) { check("song analysis (visualizers)", false, t.toString()); }
    }

    // ================================================================ exports
    private Exporter runExport(final String label, VProject p, int quality, boolean hevc) {
        final String[] msg = {null};
        Exporter ex = new Exporter(ctx, p, quality, hevc, new Exporter.Listener() {
            long last;
            @Override public void progress(float f, String m) {
                long now = System.currentTimeMillis();
                if (now - last < 200) return;
                last = now;
                out.status(label + ": " + m + (f > 0 ? String.format(Locale.US, " %d%%", Math.round(f * 100)) : ""));
            }
            @Override public void done(String result, boolean ok) { msg[0] = result; }
        });
        long t = System.currentTimeMillis();
        current = ex;
        if (cancel.get()) ex.cancel.set(true);
        ex.run();
        current = null;
        boolean ok = ex.resultUri != null;
        check(label + " export", ok, ok ? String.format(Locale.US, "%.1f s, rendered at %.1f fps", (System.currentTimeMillis() - t) / 1000.0, ex.renderFps)
                : msg[0]);
        if (!ok && ex.failure != null) line(stack(ex.failure));
        if (ex.glInfo != null && !glShown) { line("      GPU: " + ex.glInfo); glShown = true; }
        return ex;
    }

    private Uri exportA(File png, File wav) throws Exception {
        out.status("Export A: photo, title, visualizer, music…");
        VProject p = new VProject();
        p.resIdx = 0; p.aspectIdx = 0; p.fpsIdx = 2;   // 1280x720, 30 fps
        VProject.Clip c = new VProject.Clip();
        c.id = p.nextId++;
        c.kind = VProject.IMAGE;
        c.uri = Uri.fromFile(png).toString();
        c.name = "pattern";
        c.srcW = 1280; c.srcH = 720;
        c.srcDur = Long.MAX_VALUE / 4;
        c.in = 0; c.out = 3000000;
        c.reactAudio = AudioControl.GUIDE; c.reactSrc = 0;
        c.pulse = 0.1f;   // beat punch; the quadrant probes sit far enough out to survive it
        p.clips.add(c);
        VProject.AudioItem a = new VProject.AudioItem();
        a.id = p.nextId++;
        a.uri = Uri.fromFile(wav).toString();
        a.name = "clicks";
        a.srcDur = 8000000; a.in = 0; a.out = 3000000; a.start = 0;
        p.audio.add(a);
        float[] beats = new float[16];
        for (int i = 0; i < beats.length; i++) beats[i] = i * 60f / BPM;
        p.beats = beats;
        p.bpm = BPM;
        p.beatsUri = a.uri;
        p.syncId = a.id;   // music sync is opt-in: the self-test turns it on for its click track
        VProject.TextItem t = new VProject.TextItem();
        t.id = p.nextId++;
        Layer l = new Layer();
        l.id = t.id; l.type = Layer.TEXT; l.text = "SELF TEST"; l.fillColor = 0xffffffff; l.textSize = 64;
        l.cx = 640; l.cy = 360; l.scale = 1;
        TextRender.relayout(l);
        t.layer = l; t.start = 0; t.dur = 3000000;
        p.texts.add(t);
        VProject.TextItem v = new VProject.TextItem();
        v.id = p.nextId++;
        v.viz = new VProject.Viz();
        Layer vl = new Layer();
        vl.id = v.id; vl.type = Layer.IMAGE; vl.srcW = 1100; vl.srcH = 150; vl.cx = 640; vl.cy = 620; vl.scale = 1;
        v.layer = vl; v.start = 0; v.dur = 3000000;
        p.texts.add(v);
        Exporter ex = runExport("A (720p30 H.264, photo+text+visualizer+music)", p, 1, false);
        if (ex.resultUri == null) return null;
        verify("A", ex.resultUri, 1280, 720, 3000, true, 1000000, true);
        return ex.resultUri;
    }

    private void exportB(Uri a) throws Exception {
        out.status("Export B: video clips, dissolve, 2x speed…");
        VProject p = new VProject();
        p.resIdx = 0; p.aspectIdx = 0; p.fpsIdx = 2;
        VProject.Clip c1 = new VProject.Clip();
        Media.probeVideo(ctx, a, c1);
        check("probe exported file as a clip", c1.srcW == 1280 && c1.srcH == 720 && c1.hasAudio,
                c1.srcW + "x" + c1.srcH + ", " + c1.srcDur / 1000 + " ms, audio " + c1.hasAudio);
        c1.id = p.nextId++;
        c1.uri = a.toString();
        c1.name = "A1";
        c1.in = 0; c1.out = 2000000;
        c1.trans = 1; c1.transDur = 500000;
        VProject.Clip c2 = c1.copy();
        c2.id = p.nextId++;
        c2.name = "A2";
        c2.trans = 0;
        c2.in = 500000; c2.out = 3000000; c2.speed = 2f;
        p.clips.add(c1);
        p.clips.add(c2);
        long expect = p.duration() / 1000;   // 2.0 + 1.25 - 0.5 = 2.75 s
        Exporter ex = runExport("B (decode + dissolve + 2x speed)", p, 0, false);
        if (ex.resultUri == null) return;
        // 0.3 s into clip 1 shows the source unchanged: the decoder -> SurfaceTexture path must keep orientation
        verify("B", ex.resultUri, 1280, 720, expect, true, 300000, false);
    }

    private void exportC(Uri a) throws Exception {
        out.status("Export C: HEVC 1080p vertical…");
        VProject p = new VProject();
        p.resIdx = 1; p.aspectIdx = 1; p.fpsIdx = 2;   // 1080x1920
        VProject.Clip c = new VProject.Clip();
        Media.probeVideo(ctx, a, c);
        c.id = p.nextId++;
        c.uri = a.toString();
        c.name = "A";
        c.in = 0; c.out = 1000000;
        c.fit = 0;   // fit: the whole 16:9 frame letterboxed inside 9:16
        p.clips.add(c);
        Exporter ex = runExport("C (1080x1920 HEVC)", p, 1, true);
        if (ex.resultUri == null) return;
        verify("C", ex.resultUri, 1080, 1920, 1000, true, 500000, false);
    }

    // ================================================================ verification
    private void verify(String n, Uri u, int w, int h, long durMs, boolean audio, long frameUs, boolean pulsed) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, u);
            int vw = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int vh = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long d = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            check(n + " size", vw == w && vh == h, vw + "x" + vh);
            check(n + " duration", Math.abs(d - durMs) <= 120, d + " ms (expected " + durMs + ")");
            check(n + " has audio", "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)) == audio, null);
            Bitmap f = r.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (f == null) check(n + " frame decode", false, "no frame at " + frameUs / 1000 + " ms");
            else {
                // where the pattern's quadrant centres-ish land in the output frame
                float fw = f.getWidth(), fh = f.getHeight();
                float pw, ph, ox, oy;
                if (fw / fh > 16f / 9f) { ph = fh; pw = fh * 16 / 9f; } else { pw = fw; ph = fw * 9 / 16f; }
                ox = (fw - pw) / 2; oy = (fh - ph) / 2;
                float q = pulsed ? 0.12f : 0.2f;   // stay inside each quadrant even with the zoom punch
                int[] got = {avg(f, ox + pw * q, oy + ph * q), avg(f, ox + pw * (1 - q), oy + ph * q)};
                int[] want = {TL, TR};
                boolean ok = true;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 2; i++) {
                    int dist = cdist(got[i], want[i]);
                    ok &= dist < 70;
                    sb.append(String.format(Locale.US, "%s #%06X (want #%06X) ", i == 0 ? "top-left" : "top-right", got[i] & 0xffffff, want[i] & 0xffffff));
                }
                // a vertical flip would put blue/yellow at the top
                boolean flipped = cdist(got[0], BL) < cdist(got[0], TL);
                check(n + " picture orientation + colour", ok && !flipped, (flipped ? "UPSIDE DOWN · " : "") + sb.toString().trim());
                f.recycle();
            }
        } catch (Throwable t) {
            check(n + " read back", false, t.toString());
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        tracks(n, u, audio);
    }

    private static int avg(Bitmap b, float cx, float cy) {
        int r = 0, g = 0, bl = 0, k = 0;
        for (int dy = -6; dy <= 6; dy += 3) for (int dx = -6; dx <= 6; dx += 3) {
            int x = Math.max(0, Math.min(b.getWidth() - 1, Math.round(cx) + dx));
            int y = Math.max(0, Math.min(b.getHeight() - 1, Math.round(cy) + dy));
            int c = b.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); k++;
        }
        return Color.rgb(r / k, g / k, bl / k);
    }

    private static int cdist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Track lengths from the container, and (if there's music) where the kicks land. */
    private void tracks(String n, Uri u, boolean audio) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, u, null);
            long vEnd = -1, aEnd = -1, aFirst = -1;
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                ex.selectTrack(i);
                long last = -1, first = -1;
                while (ex.readSampleData(buf, 0) >= 0) {
                    long t = ex.getSampleTime();
                    if (first < 0) first = t;
                    last = Math.max(last, t);
                    ex.advance();
                }
                ex.unselectTrack(i);
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                if (mime != null && mime.startsWith("video/")) vEnd = last;
                else if (mime != null && mime.startsWith("audio/")) { aEnd = last; aFirst = first; }
            }
            if (audio && vEnd >= 0 && aEnd >= 0)
                check(n + " audio/video track lengths", Math.abs(vEnd - aEnd) < 80000,
                        String.format(Locale.US, "video ends %.3f s, audio ends %.3f s, audio starts %.3f s", vEnd / 1e6, aEnd / 1e6, aFirst / 1e6));
        } catch (Throwable t) {
            check(n + " container read", false, t.toString());
        } finally { ex.release(); }
        if (n.equals("A")) clickTiming(u);
    }

    /** In export A the kicks should land on 0, 0.5, 1.0 … s. Measures the median offset after decode. */
    private void clickTiming(Uri u) {
        try {
            final ArrayList<Float> env = new ArrayList<Float>();   // |x| max per 1 ms
            final int[] rate = {48000};
            final float[] acc = {0};
            final int[] cnt = {0};
            Media.decodeAudio(ctx, u, 0, 0, new Media.PcmSink() {
                @Override public void pcm(short[] d, int frames, int channels, int r) {
                    rate[0] = r;
                    int per = r / 1000;
                    for (int i = 0; i < frames; i++) {
                        acc[0] = Math.max(acc[0], Math.abs(d[i * channels]) / 32768f);
                        if (++cnt[0] >= per) { env.add(acc[0]); acc[0] = 0; cnt[0] = 0; }
                    }
                }
            }, cancel);
            float peak = 0;
            for (float v : env) peak = Math.max(peak, v);
            check("A audio not silent", peak > 0.2f, String.format(Locale.US, "peak %.2f", peak));
            ArrayList<Integer> offs = new ArrayList<Integer>();
            for (int b = 1; b < 6; b++) {
                int c = b * 500;
                float m = 0;
                for (int i = Math.max(0, c - 150); i < Math.min(env.size(), c + 200); i++) m = Math.max(m, env.get(i));
                for (int i = Math.max(0, c - 150); i < Math.min(env.size(), c + 200); i++)
                    if (env.get(i) >= m * 0.5f) { offs.add(i - c); break; }
            }
            if (offs.isEmpty()) { check("A beat timing in the file", false, "no kicks found"); return; }
            Integer[] o = offs.toArray(new Integer[0]);
            Arrays.sort(o);
            int med = o[o.length / 2];
            check("A beat timing in the file", Math.abs(med) <= 45,
                    "kicks land " + med + " ms from the beat grid (each: " + offs + "; under ±45 ms reads as in sync)");
        } catch (Throwable t) {
            check("A beat timing in the file", false, t.toString());
        }
    }
}
