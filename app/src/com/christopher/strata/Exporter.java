package com.christopher.strata;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Frame-accurate export on its own thread with its own EGL context:
 * 1. every audio source (clip audio, music) is decoded once, converted to 48 kHz stereo,
 *    time-stretched for speed changes and written to a temp file;
 * 2. for each output frame, each needed clip's decoder is advanced to the exact source time and
 *    renders into a SurfaceTexture; VComp composes and grades into the encoder's input surface;
 * 3. the audio mix is produced block by block in step with the video and AAC-encoded;
 * 4. both streams go into an MP4 in Movies/Emulsion via MediaStore (no permission needed).
 */
final class Exporter implements Runnable {
    interface Listener {
        void progress(float f, String msg);
        void done(String result, boolean ok);
    }

    static final String[] QUALITY = {"Standard", "High", "Max"};
    static final float[] BPP = {0.12f, 0.25f, 0.4f};
    static final int AUDIO_BITRATE = 256000;

    /** The video bitrate an export asks the encoder for (bits per pixel per frame, capped at 120 Mbps). */
    static int videoBitrate(int W, int H, int fps, int quality, boolean hevc) {
        return (int) Math.min(120e6, W * (double) H * fps * BPP[quality] * (hevc ? 0.6 : 1));
    }

    /**
     * 3.13: expected file size: requested video + audio bitrate over the duration, plus ~1 % MP4
     * overhead. Hardware encoders usually land at or a little under the request, so this is an upper
     * estimate, shown as "about".
     */
    static long estimateBytes(long durUs, int videoBitrate, boolean audio) {
        double bits = (videoBitrate + (audio ? AUDIO_BITRATE : 0)) * (durUs / 1e6);
        return (long) (bits / 8 * 1.01);
    }

    static String sizeText(long bytes) {
        if (bytes >= 1000L * 1000 * 1000) return String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9);
        if (bytes >= 1000L * 1000) return String.format(java.util.Locale.US, "%d MB", Math.round(bytes / 1e6));
        return String.format(java.util.Locale.US, "%d kB", Math.max(1, Math.round(bytes / 1e3)));
    }

    final Context ctx;
    final VProject p;
    final int quality;
    final boolean hevc;
    final Listener lis;
    final AtomicBoolean cancel = new AtomicBoolean();
    /** After a successful run: the saved video. After a failure: what went wrong (with stack). */
    volatile Uri resultUri;
    volatile Throwable failure;
    /** Frames rendered per second of wall time, for diagnostics. */
    volatile float renderFps;
    volatile String glInfo;

    private VComp comp;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ectx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface esurf = EGL14.EGL_NO_SURFACE;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private MediaCodec venc, aenc;
    private MediaMuxer muxer;
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted, wantAudio;
    private final ArrayList<Object[]> pending = new ArrayList<Object[]>();
    private final ArrayList<Segment> segments = new ArrayList<Segment>();
    private long audioFrames;   // frames fed to the AAC encoder so far
    /** Input EOS was queued. Output completion is a separate, one-time codec event. */
    private boolean audioEos;
    private boolean audioOutputEos, videoOutputEos;
    private long totalFrames48;

    Exporter(Context ctx, VProject p, int quality, boolean hevc, Listener l) {
        this.ctx = ctx;
        this.p = p;
        this.quality = quality;
        this.hevc = hevc;
        this.lis = l;
    }

    // ================================================================ audio sources
    /** One decoded source placed on the timeline: 48 kHz stereo 16-bit in a temp file. */
    private static final class Segment {
        File file;
        RandomAccessFile raf;
        long startFrame, frames;      // timeline position and length in 48 kHz frames
        float gain;
        long fadeIn, fadeOut;         // frames
        /** Music items: volume keys and ducking apply (null for clip / overlay sound). */
        VProject.AudioItem music;
        VProject.TextItem overlay;
        boolean main;
        /** 3.15: this source's equalizer (null = none). */
        AudioEq.Chain eq;
    }

    /** Music gain under clip/overlay sound, per 10 ms of timeline (null = no ducking). */
    private float[] duck;

    /**
     * Sidechain for ducking, measured from exactly the clip and overlay audio being mixed:
     * per 10 ms, the peak of the mono average times the source's gain (the same measure the
     * preview gets from its waveform analysis), then Duck.gains turns it into a gain curve.
     */
    private void buildDuck() throws IOException {
        if (p.duckDb <= 0) return;
        int per = AudioDsp.RATE / Duck.RATE;   // 480 frames
        float[] side = new float[(int) (totalFrames48 / per) + 2];
        byte[] buf = new byte[per * 4 * 64];
        for (Segment s : segments) {
            if (s.music != null) continue;
            RandomAccessFile in = new RandomAccessFile(s.file, "r");
            try {
                long f = 0, total = Math.min(s.frames, in.length() / 4);
                float peak = 0;
                while (f < total) {
                    int cnt = (int) Math.min(buf.length / 4, total - f);
                    in.readFully(buf, 0, cnt * 4);   // whole frames, never a partial read
                    for (int i = 0; i < cnt * 4; i += 4) {
                        short l = (short) ((buf[i] & 255) | (buf[i + 1] << 8)), r = (short) ((buf[i + 2] & 255) | (buf[i + 3] << 8));
                        long timelineUs=(s.startFrame+f)*1000000L/AudioDsp.RATE;
                        peak = Math.max(peak, Math.abs((l + r) * 0.5f / 32768f) * (s.main?p.mainSoundGain(timelineUs):1));
                        f++;
                        if (f % per == 0 || f == total) {
                            int k = (int) ((s.startFrame + f - 1) / per);
                            if (k >= 0 && k < side.length) side[k] = Math.max(side[k], peak * s.gain);
                            peak = 0;
                        }
                    }
                }
            } finally { in.close(); }
        }
        duck = Duck.gains(side, p.duckDb);
    }

    private void prepareAudio() throws IOException {
        long[] starts = p.starts();
        long[] durs = p.durs(), tr = p.transDurs();
        int n = p.clips.size();
        int k = 0, total = 0;
        for (VProject.Clip c : p.clips) if (c.kind == VProject.VIDEO && c.hasAudio && !c.mute && c.volume > 0 && c.curve == null) total++;
        total += p.audio.size();
        for (int i = 0; i < n; i++) {
            VProject.Clip c = p.clips.get(i);
            // a clip on a speed curve is silent (as in the preview): its sound would warble through the ramp
            if (c.kind != VProject.VIDEO || !c.hasAudio || c.mute || c.volume <= 0 || c.curve != null) continue;
            if (cancel.get()) return;
            lis.progress(0, "Preparing audio " + (++k) + " of " + total);
            Segment s;
            if (Math.abs(c.speed - 1f) > 0.001f) {
                // WSOLA needs the whole range; speed-changed clips are short in practice
                short[] pcm = Media.decodeStereo48k(ctx, Uri.parse(c.uri), c.in, c.out, cancel);
                if (pcm == null || pcm.length == 0) continue;
                pcm = c.tape ? AudioDsp.varispeed(pcm, c.speed) : AudioDsp.stretch(pcm, c.speed);   // 3.15: tape or keep pitch
                s = new Segment();
                s.file = writeTemp(pcm);
                s.frames = pcm.length / 2;
            } else {
                s = decodeToTemp(Uri.parse(c.uri), c.in, c.out);
                if (s == null) continue;
            }
            applyEq(s, c.eq);
            s.startFrame = starts[i] * AudioDsp.RATE / 1000000L;
            s.gain = c.volume; s.main = true;
            long tin = i > 0 ? Timeline.trans(durs, tr, i - 1) : 0, tout = Timeline.trans(durs, tr, i);
            s.fadeIn = Math.max(240, tin * AudioDsp.RATE / 1000000L);    // 5 ms de-click minimum
            s.fadeOut = Math.max(240, tout * AudioDsp.RATE / 1000000L);
            segments.add(s);
        }
        for (VProject.TextItem ti : p.texts) {
            VProject.Clip m = ti.media;
            if (m == null || m.kind != VProject.VIDEO || !m.hasAudio || m.mute || m.volume <= 0 || !ti.layer.visible) continue;
            if (cancel.get()) return;
            lis.progress(0, "Preparing overlay audio");
            long out = Math.min(m.srcDur, m.in + (long) (ti.dur * m.speed));
            Segment s;
            if (Math.abs(m.speed - 1f) > 0.001f) {
                short[] pcm = Media.decodeStereo48k(ctx, Uri.parse(m.uri), m.in, out, cancel);
                if (pcm == null || pcm.length == 0) continue;
                pcm = m.tape ? AudioDsp.varispeed(pcm, m.speed) : AudioDsp.stretch(pcm, m.speed);
                s = new Segment();
                s.file = writeTemp(pcm);
                s.frames = pcm.length / 2;
            } else {
                s = decodeToTemp(Uri.parse(m.uri), m.in, out);
                if (s == null) continue;
            }
            applyEq(s, m.eq);
            s.startFrame = ti.start * AudioDsp.RATE / 1000000L;
            s.frames = Math.min(s.frames, ti.dur * AudioDsp.RATE / 1000000L);
            s.gain = m.volume; s.overlay = ti;
            s.fadeIn = Math.max(240, ti.fadeIn * AudioDsp.RATE / 1000000L);
            s.fadeOut = Math.max(240, ti.fadeOut * AudioDsp.RATE / 1000000L);
            segments.add(s);
        }
        for (VProject.AudioItem a : p.audio) {
            if (cancel.get()) return;
            lis.progress(0, "Preparing audio " + (++k) + " of " + total);
            Segment s = decodeToTemp(Uri.parse(a.uri), a.in, a.out);
            // music always has sound: none means the file couldn't be decoded, not a silent clip
            if (s == null) throw new IOException("can't read the sound in \"" + a.name + "\". Replace or remove it, then export again.");
            applyEq(s, a.eq);
            s.startFrame = a.start * AudioDsp.RATE / 1000000L;
            s.gain = a.volume;
            s.music = a;
            s.fadeIn = Math.max(240, a.fadeIn * AudioDsp.RATE / 1000000L);
            s.fadeOut = Math.max(240, a.fadeOut * AudioDsp.RATE / 1000000L);
            segments.add(s);
        }
        buildDuck();
        for (Segment s : segments) s.raf = new RandomAccessFile(s.file, "r");
        masterEq = new AudioEq.Chain(p.masterEq);
    }

    /** 3.15: the whole mix's equalizer, run over the mix in timeline order (state carries between blocks). */
    private AudioEq.Chain masterEq;

    /**
     * 3.15: a source's equalizer, run in float as the source is mixed (no clipping however much
     * a band boosts). Sources are read in timeline order, so each filter's state carries over.
     */
    private void applyEq(Segment s, AudioEq eq) {
        if (s == null || eq == null || eq.flat()) return;
        AudioEq.Chain ch = new AudioEq.Chain(eq);
        if (!ch.empty()) s.eq = ch;
    }

    /**
     * Every file the export needs must be readable before anything is written: photos and videos
     * on the main track, media overlays and music. A missing one (moved, deleted, access revoked)
     * fails the export by name instead of leaving a black gap or silence in a "saved" video.
     */
    private void preflight() throws Exception {
        java.util.LinkedHashMap<String, String> need = new java.util.LinkedHashMap<String, String>();
        for (VProject.Clip c : p.clips) need.put(c.uri, c.name);
        for (VProject.TextItem t : p.texts) if (t.media != null) need.put(t.media.uri, t.media.name);
        for (VProject.AudioItem a : p.audio) need.put(a.uri, a.name);
        ArrayList<String> bad = new ArrayList<String>();
        ContentResolver cr = ctx.getContentResolver();
        for (Map.Entry<String, String> e : need.entrySet()) {
            try {
                ParcelFileDescriptor f = cr.openFileDescriptor(Uri.parse(e.getKey()), "r");
                if (f == null) bad.add(e.getValue()); else f.close();
            } catch (Exception x) { bad.add(e.getValue()); }
        }
        if (bad.isEmpty()) return;
        StringBuilder s = new StringBuilder("can't read ");
        for (int i = 0; i < bad.size(); i++) s.append(i == 0 ? "" : ", ").append('"').append(bad.get(i)).append('"');
        s.append(bad.size() == 1 ? ". The file" : ". The files").append(" may have been moved or deleted. Replace or remove ")
                .append(bad.size() == 1 ? "it" : "them").append(", then export again.");
        throw new Exception(s.toString());
    }

    /** Decodes [in, out) straight to a 48 kHz stereo temp file through a streaming resampler. */
    private Segment decodeToTemp(Uri uri, long in, long out) throws IOException {
        final File f = File.createTempFile("mix", ".pcm", ctx.getCacheDir());
        final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
        final long[] frames = {0};
        final IOException[] err = {null};
        final AudioDsp.Resampler[] rs = {null};
        final int[] fmt = {0, 0};
        boolean ok;
        try {
            ok = Media.decodeAudio(ctx, uri, in, out, new Media.PcmSink() {
                @Override public void pcm(short[] d, int fr, int channels, int rate) {
                    if (rs[0] == null || fmt[0] != channels || fmt[1] != rate) {
                        rs[0] = new AudioDsp.Resampler(channels, rate);
                        fmt[0] = channels;
                        fmt[1] = rate;
                    }
                    short[] st = rs[0].process(d, fr);
                    try { writeShorts(os, st); } catch (IOException e) { err[0] = e; }
                    frames[0] += st.length / 2;
                }
            }, cancel);
            if (rs[0] != null) {   // the resampler's last few frames
                short[] tail = rs[0].flush();
                writeShorts(os, tail);
                frames[0] += tail.length / 2;
            }
        } finally { os.close(); }
        if (err[0] != null) { f.delete(); throw err[0]; }
        if (!ok || frames[0] == 0) { f.delete(); return null; }
        Segment s = new Segment();
        s.file = f;
        s.frames = frames[0];
        return s;
    }

    private File writeTemp(short[] pcm) throws IOException {
        File f = File.createTempFile("mix", ".pcm", ctx.getCacheDir());
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
        try { writeShorts(out, pcm); } finally { out.close(); }
        return f;
    }

    private static void writeShorts(BufferedOutputStream out, short[] s) throws IOException {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) { b[i * 2] = (byte) s[i]; b[i * 2 + 1] = (byte) (s[i] >> 8); }
        out.write(b);
    }

    /** Mixes frames [from, from + n) of the timeline into interleaved 16-bit stereo. */
    private short[] mix(long from, int n) throws IOException {
        float[] acc = new float[n * 2];
        byte[] tmp = new byte[n * 4];
        for (Segment s : segments) {
            long a = Math.max(from, s.startFrame), b = Math.min(from + n, s.startFrame + s.frames);
            if (b <= a) continue;
            int cnt = (int) (b - a);
            long local = a - s.startFrame;
            s.raf.seek(local * 4);
            s.raf.readFully(tmp, 0, cnt * 4);
            int o = (int) (a - from);
            // music: volume keys and ducking, evaluated at the block's ends and interpolated
            float g0 = s.gain, g1 = s.gain;
            if (s.music != null) {
                long t0 = a * 1000000L / AudioDsp.RATE, t1 = b * 1000000L / AudioDsp.RATE;
                g0 = VProject.musicVol(s.music, t0) * Duck.at(duck, t0);
                g1 = VProject.musicVol(s.music, t1) * Duck.at(duck, t1);
            }
            float[] fq = null;   // equalized samples of this block
            if (s.eq != null) {
                fq = new float[cnt * 2];
                for (int i = 0; i < cnt * 2; i++) fq[i] = (short) ((tmp[i * 2] & 255) | (tmp[i * 2 + 1] << 8));
                s.eq.process(fq, 0, cnt);
            }
            for (int i = 0; i < cnt; i++) {
                long lf = local + i;
                float g = cnt > 1 ? g0 + (g1 - g0) * i / (float) (cnt - 1) : g0;
                if (s.main) g *= p.mainSoundGain((a + i) * 1000000L / AudioDsp.RATE);
                long sampleTime = (a + i) * 1000000L / AudioDsp.RATE;
                if (s.overlay != null) g *= VProject.overlaySoundFade(s.overlay, sampleTime);
                else if (s.music != null) g *= VProject.audioSoundFade(s.music, sampleTime);
                else {
                    if (lf < s.fadeIn) g *= lf / (float) s.fadeIn;
                    long rem = s.frames - lf;
                    if (rem < s.fadeOut) g *= rem / (float) s.fadeOut;
                }
                float l, r;
                if (fq != null) { l = fq[i * 2]; r = fq[i * 2 + 1]; }
                else { l = (short) ((tmp[i * 4] & 255) | (tmp[i * 4 + 1] << 8)); r = (short) ((tmp[i * 4 + 2] & 255) | (tmp[i * 4 + 3] << 8)); }
                acc[(o + i) * 2] += l * g;
                acc[(o + i) * 2 + 1] += r * g;
            }
        }
        if (masterEq != null && !masterEq.empty()) masterEq.process(acc, 0, n);
        short[] out = new short[n * 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) Math.round(clipGuard(acc[i] / 32768f) * 32767);
        return out;
    }

    static final float GUARD_KNEE = 0.944f;   // -0.5 dBFS

    /**
     * Master clip guard: untouched up to -0.5 dBFS (so the export sounds like the preview, which
     * has no master processing), then a soft knee into full scale so overs don't hard-clip. It
     * only acts on samples that would otherwise clip or nearly clip; it is not a loudness
     * limiter. (Before 3.5.1 a tanh curve started at 0.8 and coloured every loud mix.)
     */
    static float clipGuard(float x) {
        float ax = Math.abs(x);
        if (ax <= GUARD_KNEE) return x;
        float r = 1 - GUARD_KNEE;
        return Math.signum(x) * (GUARD_KNEE + r * (float) Math.tanh((ax - GUARD_KNEE) / r));
    }

    // ================================================================ video sources
    /** Decoder for one clip, rendering into a SurfaceTexture on this GL context. */
    private final class Reader {
        final VProject.Clip clip;
        final MediaExtractor ex = new MediaExtractor();
        MediaCodec dec;
        SurfaceTexture st;
        Surface surface;
        int oes;
        final Object lock = new Object();
        boolean avail;
        int held = -1;
        long heldPts, curPts = Long.MIN_VALUE, lastDelta = 33333;
        long prevOutPts = Long.MIN_VALUE;
        boolean inEos, outEos, hasFrame;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final VComp.Frame frame = new VComp.Frame();

        Reader(VProject.Clip c, long startUs) throws IOException {
            clip = c;
            ex.setDataSource(ctx, Uri.parse(c.uri), null);
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { ex.selectTrack(i); fmt = f; break; }
            }
            if (fmt == null) throw new IOException("no video track in " + c.name);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            oes = comp.newOesTex();
            st = new SurfaceTexture(oes);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override public void onFrameAvailable(SurfaceTexture s) {
                    synchronized (lock) { avail = true; lock.notifyAll(); }
                }
            }, frameHandler);
            surface = new Surface(st);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, surface, null, 0);   // the track's rotation-degrees rotates the output
            dec.start();
            frame.tex = oes;
            frame.oes = true;
        }

        private void feed() {
            while (!inEos) {
                int ii = dec.dequeueInputBuffer(0);
                if (ii < 0) return;
                ByteBuffer b = dec.getInputBuffer(ii);
                int n = ex.readSampleData(b, 0);
                if (n < 0) {
                    dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inEos = true;
                } else {
                    dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                    ex.advance();
                }
            }
        }

        private void renderHeld() throws Exception {
            synchronized (lock) { avail = false; }
            dec.releaseOutputBuffer(held, true);
            synchronized (lock) {
                long until = System.currentTimeMillis() + 2000;
                while (!avail) {
                    long w = until - System.currentTimeMillis();
                    if (w <= 0) throw new Exception("video frame timed out in " + clip.name);
                    lock.wait(w);
                }
                avail = false;
            }
            st.updateTexImage();
            st.getTransformMatrix(frame.st);
            curPts = heldPts;
            hasFrame = true;
            held = -1;
        }

        /** Shows the latest frame with pts <= target (or the first frame, if none yet). */
        void frameAt(long target) throws Exception {
            int idle = 0;
            while (true) {
                if (held >= 0) {
                    if (heldPts <= target + 1000 || !hasFrame) {
                        // skip without rendering if the next frame will also be due
                        if (hasFrame && heldPts + lastDelta <= target + 1000 && !outEos) {
                            dec.releaseOutputBuffer(held, false);
                            held = -1;
                            continue;
                        }
                        renderHeld();
                        continue;
                    }
                    return;
                }
                if (outEos) return;
                feed();
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    idle = 0;
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size == 0 && eos) {
                        dec.releaseOutputBuffer(oi, false);
                        outEos = true;
                        continue;
                    }
                    held = oi;
                    heldPts = info.presentationTimeUs;
                    if (prevOutPts != Long.MIN_VALUE && heldPts > prevOutPts) lastDelta = heldPts - prevOutPts;
                    prevOutPts = heldPts;
                    if (eos) outEos = true;
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (++idle > 300) throw new Exception("video decoder stalled in " + clip.name);
                }
            }
        }

        void release() {
            try { dec.stop(); } catch (Exception ignored) {}
            try { dec.release(); } catch (Exception ignored) {}
            ex.release();
            surface.release();
            st.release();
            GLES20.glDeleteTextures(1, new int[]{oes}, 0);
        }
    }

    // ================================================================ main
    @Override
    public void run() {
        Uri out = null;
        ParcelFileDescriptor pfd = null;
        HashMap<Integer, Reader> readers = new HashMap<Integer, Reader>();
        final HashMap<Integer, Bitmap> stills = new HashMap<Integer, Bitmap>();
        final HashMap<Integer, VComp.TextSrc> texts = new HashMap<Integer, VComp.TextSrc>();
        String error = null;
        try {
            final int W = p.outW(), H = p.outH(), fps = p.fps();
            long dur = p.duration();
            if (dur <= 0) throw new Exception("the timeline is empty");
            int N = (int) Math.ceil(dur * (double) fps / 1e6);
            totalFrames48 = dur * AudioDsp.RATE / 1000000L;

            preflight();
            prepareAudio();
            if (cancel.get()) throw new Exception("cancelled");
            for (String uri : AudioControl.required(p)) {
                if (cancel.get()) throw new IOException("cancelled");
                if (!p.controlFx.containsKey(uri)) {
                    lis.progress(0, "Analysing effect audio…");
                    AudioFeatures features = Media.songFeatures(ctx, uri, cancel);
                    if (features == null) throw new IOException("An effect's audio source has no readable audio. Choose another source or bypass that automation.");
                    p.controlFx.put(uri, features);
                }
                if (!p.controlBeats.containsKey(uri)) p.controlBeats.put(uri, Beats.fromFeatures(p.controlFx.get(uri)));
            }
            VProject.AudioItem sync = p.syncItem();
            p.songFx = sync == null ? null : p.controlFx.get(sync.uri);
            p.songFxUri = sync == null ? null : sync.uri;
            wantAudio = !segments.isEmpty();

            // ---- output file
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            String name = "IndependentVideo_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name + ".mp4");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Independent Video");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            out = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (out == null) throw new Exception("the gallery refused the file");
            pfd = cr.openFileDescriptor(out, "rw");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ---- encoders
            String vmime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            // capped at 120 Mbps: hardware AVC/HEVC encoders reject or choke on higher rates at 4K60
            int bitrate = videoBitrate(W, H, fps, quality, hevc);
            venc = MediaCodec.createEncoderByType(vmime);
            Surface input = configureVideo(vmime, W, H, fps, bitrate);
            if (wantAudio) {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioDsp.RATE, 2);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aenc.start();
            }

            // ---- GL on the encoder surface
            frameThread = new HandlerThread("export-frames");
            frameThread.start();
            frameHandler = new Handler(frameThread.getLooper());
            setupEgl(input);
            comp = new VComp(ctx);
            comp.vizCap = 4096;
            comp.init();
            // overlays are converted at the size they appear (VComp.media), up to their own pixels:
            // the cap only guards the GPU. 2160 here made full-frame 4K B-roll ~2160x1215, upscaled.
            comp.ovlCap = Math.min(comp.maxTex, 4096);
            glInfo = GLES20.glGetString(GLES20.GL_RENDERER) + " · " + GLES20.glGetString(GLES20.GL_VERSION);

            final HashMap<Integer, Reader> rd = readers;
            VComp.Frames frames = new VComp.Frames() {
                @Override public VComp.Frame frame(VProject.Clip c) {
                    if (c.kind == VProject.IMAGE) {
                        Bitmap b = stills.get(c.id);
                        if (b == null) {
                            try {
                                int side = (int) Math.min(4096, Math.max(W, H) * Math.max(1f, c.scale) * 1.1f);
                                b = c.frameUs >= 0 ? Media.frame(ctx, Uri.parse(c.uri), c.frameUs, side)
                                        : Media.decodeImage(ctx.getContentResolver(), Uri.parse(c.uri), side);
                            } catch (Exception e) { b = null; }
                            // a photo that can't be read fails the export: a "finished" video with a
                            // black hole where the picture should be is worse than an error
                            if (b == null) throw new RuntimeException("can't read the photo \"" + c.name + "\". Replace or remove it, then export again.");
                            stills.put(c.id, b);
                        }
                        VComp.Frame f = new VComp.Frame();
                        f.tex = comp.bitmapTex(b);
                        return f;
                    }
                    Reader r = rd.get(c.id);
                    return r != null && r.hasFrame ? r.frame : null;
                }

                @Override public VComp.TextSrc text(VProject.TextItem t) {
                    VComp.TextSrc s = texts.get(t.id);
                    if (s == null) {
                        Layer l = t.layer;
                        if (l.layout == null) TextRender.relayout(l);
                        s = new VComp.TextSrc();
                        s.rect = new RectF(0, 0, l.srcW, l.srcH);
                        // rendered at the largest size its keyframes / pulse reach, so zooms stay sharp
                        s.bmp = TextRender.render(l, s.rect, Math.max(0.05f, Math.min(4f, VProject.maxScale(t))));
                        texts.put(t.id, s);
                    }
                    return s;
                }
            };

            // ---- frames
            HashSet<Integer> need = new HashSet<Integer>();
            long tRender0 = System.nanoTime();
            for (int n = 0; n < N; n++) {
                if (cancel.get()) throw new Exception("cancelled");
                long t = n * 1000000L / fps;
                long[] a = p.at(t);
                need.clear();
                for (int k = 0; k < 2; k++) {
                    int idx = (int) a[k];
                    if (idx < 0) continue;
                    VProject.Clip c = p.clips.get(idx);
                    need.add(c.id);
                    if (c.kind != VProject.VIDEO) continue;
                    long src = p.srcTime(idx, t);
                    Reader r = readers.get(c.id);
                    if (r != null && src < r.curPts - 1000) { r.release(); readers.remove(c.id); r = null; }
                    if (r == null) { r = new Reader(c, src); readers.put(c.id, r); }
                    r.frameAt(src);
                }
                // media overlays active now: stills stay cached, videos get their own decoder
                for (VProject.TextItem ti : p.texts) {
                    VProject.Clip m = ti.media;
                    if (m == null || t < ti.start || t >= ti.start + ti.dur || !ti.layer.visible) continue;
                    need.add(m.id);
                    if (m.kind != VProject.VIDEO) continue;
                    long src = VProject.overlaySrc(ti, t);
                    Reader r = readers.get(m.id);
                    if (r != null && src < r.curPts - 1000) { r.release(); readers.remove(m.id); r = null; }
                    if (r == null) { r = new Reader(m, src); readers.put(m.id, r); }
                    r.frameAt(src);
                }
                Iterator<Map.Entry<Integer, Reader>> it = readers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Reader> e = it.next();
                    if (!need.contains(e.getKey())) { e.getValue().release(); it.remove(); }
                }
                Iterator<Map.Entry<Integer, Bitmap>> si = stills.entrySet().iterator();
                while (si.hasNext()) {
                    Map.Entry<Integer, Bitmap> e = si.next();
                    if (!need.contains(e.getKey())) { comp.forget(e.getValue()); si.remove(); }
                }
                int res = comp.render(p, t, W, H, frames);
                comp.output(res, W, H, p.bg);
                EGLExt.eglPresentationTimeANDROID(dpy, esurf, t * 1000L);
                EGL14.eglSwapBuffers(dpy, esurf);
                drainVideo(false);
                if (wantAudio) pumpAudio((n + 1) * 1000000L / fps + 200000);
                if (n % 5 == 0) lis.progress(n / (float) N, "Rendering frame " + (n + 1) + " of " + N);
            }
            renderFps = N / Math.max(1e-3f, (System.nanoTime() - tRender0) / 1e9f);
            venc.signalEndOfInputStream();
            drainVideo(true);
            if (wantAudio) {
                pumpAudio(Long.MAX_VALUE);
                drainAudio(true);
            }
            if (!muxStarted) throw new Exception("the encoder produced no output");
            muxer.stop();
            muxer.release();
            muxer = null;
            pfd.close();
            pfd = null;
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(out, v, null, null);
            resultUri = out;
            out = null;
            lis.done("Saved " + W + " × " + H + " " + fps + " fps to Movies/Independent Video", true);
        } catch (Throwable t) {
            failure = t;
            error = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            for (Reader r : readers.values()) try { r.release(); } catch (Exception ignored) {}
            for (Segment s : segments) {
                try { if (s.raf != null) s.raf.close(); } catch (Exception ignored) {}
                s.file.delete();
            }
            try { if (venc != null) { venc.stop(); venc.release(); } } catch (Exception ignored) {}
            try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            if (out != null) try { ctx.getContentResolver().delete(out, null, null); } catch (Exception ignored) {}
            releaseEgl();
            if (frameThread != null) frameThread.quitSafely();
            if (error != null) lis.done(cancel.get() ? "Export cancelled" : "Export failed: " + error, false);
        }
    }

    private Surface configureVideo(String mime, int W, int H, int fps, int bitrate) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            MediaFormat f = MediaFormat.createVideoFormat(mime, W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (attempt == 0 && mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC))
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            try {
                venc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface s = venc.createInputSurface();
                venc.start();
                return s;
            } catch (Exception e) {
                if (attempt == 1) throw new IOException("this phone's encoder can't do " + W + " × " + H + " " + mime.substring(6) + ": " + e.getMessage());
                venc.reset();
            }
        }
        throw new IOException("encoder setup failed");
    }

    private void setupEgl(Surface input) throws Exception {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new Exception("EGL init failed");
        int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, num, 0) || num[0] < 1) throw new Exception("no recordable EGL config");
        ectx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (ectx == null || ectx == EGL14.EGL_NO_CONTEXT) throw new Exception("no GLES 3 context");
        esurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], input, new int[]{EGL14.EGL_NONE}, 0);
        if (esurf == null || esurf == EGL14.EGL_NO_SURFACE) throw new Exception("no encoder surface");
        if (!EGL14.eglMakeCurrent(dpy, esurf, esurf, ectx)) throw new Exception("eglMakeCurrent failed");
    }

    private void releaseEgl() {
        if (dpy == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (esurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, esurf);
        if (ectx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ectx);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(dpy);
        dpy = EGL14.EGL_NO_DISPLAY;
    }

    // ================================================================ encoders -> muxer
    private void write(int track, ByteBuffer b, MediaCodec.BufferInfo info) {
        if (muxStarted) {
            muxer.writeSampleData(track, b, info);
            return;
        }
        ByteBuffer copy = ByteBuffer.allocate(info.size);
        b.position(info.offset);
        b.limit(info.offset + info.size);
        copy.put(b);
        copy.flip();
        MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
        ci.set(0, info.size, info.presentationTimeUs, info.flags);
        pending.add(new Object[]{track, copy, ci});
    }

    private void maybeStart() {
        if (muxStarted || vTrack < 0 || (wantAudio && aTrack < 0)) return;
        muxer.start();
        muxStarted = true;
        for (Object[] o : pending) muxer.writeSampleData((Integer) o[0], (ByteBuffer) o[1], (MediaCodec.BufferInfo) o[2]);
        pending.clear();
    }

    private void drainVideo(boolean end) throws Exception {
        if (videoOutputEos) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            if (cancel.get()) throw new Exception("cancelled");
            int oi = venc.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                if (++idle > 500) throw new Exception("video encoder didn't finish");
                if (wantAudio) drainAudio(false);
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                idle = 0;
                vTrack = muxer.addTrack(venc.getOutputFormat());
                maybeStart();
            } else if (oi >= 0) {
                idle = 0;
                ByteBuffer b = venc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && vTrack >= 0) write(vTrack, b, info);
                venc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoOutputEos = true;
                    return;
                }
            }
        }
    }

    /** Feeds mixed PCM to the AAC encoder until its timestamps reach untilUs (or the end). */
    private void pumpAudio(long untilUs) throws Exception {
        int guard = 0;
        while (!audioEos) {
            long pts = audioFrames * 1000000L / AudioDsp.RATE;
            if (pts >= untilUs && untilUs != Long.MAX_VALUE) return;
            int ii = aenc.dequeueInputBuffer(untilUs == Long.MAX_VALUE ? 10000 : 0);
            if (ii < 0) {
                drainAudio(false);
                if (untilUs != Long.MAX_VALUE) return;
                if (++guard > 500) throw new Exception("audio encoder stalled");
                continue;
            }
            ByteBuffer in = aenc.getInputBuffer(ii);
            long left = totalFrames48 - audioFrames;
            if (left <= 0) {
                aenc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                audioEos = true;
                break;
            }
            int n = (int) Math.min(Math.min(1024, left), in.capacity() / 4);
            short[] pcm = mix(audioFrames, n);
            in.clear();
            in.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
            aenc.queueInputBuffer(ii, 0, n * 4, pts, 0);
            audioFrames += n;
            guard = 0;
            drainAudio(false);
        }
    }

    private void drainAudio(boolean end) throws Exception {
        // drainVideo(true) also drains audio while waiting. It can consume AAC's EOS before
        // run() reaches its final drainAudio(true); the codec will never emit that EOS again.
        if (audioOutputEos) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            if (cancel.get()) throw new Exception("cancelled");
            int oi = aenc.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                if (++idle > 500) throw new Exception("audio encoder didn't finish");
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                idle = 0;
                aTrack = muxer.addTrack(aenc.getOutputFormat());
                maybeStart();
            } else if (oi >= 0) {
                idle = 0;
                ByteBuffer b = aenc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && aTrack >= 0) write(aTrack, b, info);
                aenc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    audioOutputEos = true;
                    return;
                }
            }
        }
    }
}
