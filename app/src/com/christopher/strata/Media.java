package com.christopher.strata;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Media probing and audio decoding (MediaExtractor + MediaCodec to 16-bit PCM). */
final class Media {

    /** Fills a clip's source facts from the file. Throws if it isn't a playable video. */
    static void probeVideo(Context ctx, Uri uri, VProject.Clip c) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String au = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (d == null || w == null || h == null) throw new IOException("not a video this phone can read");
            c.rotation = rot == null ? 0 : Integer.parseInt(rot);
            int vw = Integer.parseInt(w), vh = Integer.parseInt(h);
            boolean swap = c.rotation == 90 || c.rotation == 270;
            c.srcW = swap ? vh : vw;
            c.srcH = swap ? vw : vh;
            c.srcDur = Long.parseLong(d) * 1000;
            c.hasAudio = "yes".equals(au);
            c.kind = VProject.VIDEO;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static long probeAudio(Context ctx, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null) throw new IOException("no duration");
            return Long.parseLong(d) * 1000;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static Bitmap decodeImage(ContentResolver cr, Uri uri, final int maxSide) throws IOException {
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri), new ImageDecoder.OnHeaderDecodedListener() {
            @Override public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                if (m > maxSide) {
                    float k = maxSide / (float) m;
                    d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                }
            }
        });
        return Loader.toArgb(b);
    }

    /** The exact frame at us (a freeze frame), fitting maxSide x maxSide. Null if it can't be read. */
    static Bitmap frame(Context ctx, Uri uri, long us, int maxSide) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            return r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide);
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static Bitmap thumb(Context ctx, Uri uri, long us, int h) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            Bitmap f = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, h * 3, h);
            return f;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- audio decoding
    interface PcmSink {
        /** Interleaved 16-bit samples; frames = samples / channels. */
        void pcm(short[] data, int frames, int channels, int rate);
    }

    /**
     * Decodes the first audio track between startUs and endUs (source time). Returns false if the
     * file has no audio track. PCM 16-bit, 8-bit and float outputs are converted to 16-bit.
     */
    static boolean decodeAudio(Context ctx, Uri uri, long startUs, long endUs, PcmSink sink, AtomicBoolean cancel)
            throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return false;
            ex.selectTrack(track);
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = 2;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            short[] buf = new short[0];
            int idle = 0;
            while (!outEos) {
                if (cancel != null && cancel.get()) break;
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = ex.readSampleData(b, 0);
                        long st = ex.getSampleTime();
                        if (n < 0 || (endUs > 0 && st > endUs + 200000)) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, st, 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey("pcm-encoding")) enc = of.getInteger("pcm-encoding");
                } else if (oi >= 0) {
                    idle = 0;
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int samples = enc == 4 ? info.size / 4 : (enc == 3 ? info.size : info.size / 2);
                        if (buf.length < samples) buf = new short[samples];
                        if (enc == 4) for (int i = 0; i < samples; i++) buf[i] = (short) Math.max(-32768, Math.min(32767, ob.getFloat() * 32767));
                        else if (enc == 3) for (int i = 0; i < samples; i++) buf[i] = (short) (((ob.get() & 255) - 128) << 8);
                        else ob.asShortBuffer().get(buf, 0, samples);
                        int frames = samples / ch;
                        long pts = info.presentationTimeUs;
                        int skip = 0;
                        if (pts < startUs) skip = (int) Math.min(frames, (startUs - pts) * rate / 1000000L);
                        int keep = frames - skip;
                        if (endUs > 0) {
                            long lastUs = pts + (long) frames * 1000000L / rate;
                            if (lastUs > endUs) keep = Math.max(0, (int) ((endUs - pts) * rate / 1000000L) - skip);
                            if (pts >= endUs) outEos = true;
                        }
                        if (keep > 0) {
                            if (skip > 0) System.arraycopy(buf, skip * ch, buf, 0, keep * ch);
                            sink.pcm(buf, keep, ch, rate);
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inEos && ++idle > 400) break;   // ~2 s stall guard
            }
            return true;
        } finally {
            if (dec != null) try { dec.stop(); } catch (Exception ignored) {}
            if (dec != null) try { dec.release(); } catch (Exception ignored) {}
            ex.release();
        }
    }

    /** Growable 16-bit buffer. */
    static final class Shorts {
        short[] a = new short[48000 * 2 * 4];
        int n;

        void add(short[] src, int off, int len) {
            if (n + len > a.length) {
                short[] b = new short[Math.max(a.length * 2, n + len)];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            System.arraycopy(src, off, a, n, len);
            n += len;
        }

        short[] trim() {
            short[] b = new short[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }

    /** Whole range decoded and converted to 48 kHz stereo. Null if the file has no audio. */
    static short[] decodeStereo48k(Context ctx, Uri uri, long startUs, long endUs, AtomicBoolean cancel) throws IOException {
        final Shorts out = new Shorts();
        final AudioDsp.Resampler[] rs = {null};
        final int[] fmt = {0, 0};
        boolean ok = decodeAudio(ctx, uri, startUs, endUs, new PcmSink() {
            @Override public void pcm(short[] d, int frames, int channels, int rate) {
                if (rs[0] == null || fmt[0] != channels || fmt[1] != rate) {
                    rs[0] = new AudioDsp.Resampler(channels, rate);
                    fmt[0] = channels;
                    fmt[1] = rate;
                }
                short[] st = rs[0].process(d, frames);
                out.add(st, 0, st.length);
            }
        }, cancel);
        if (!ok) return null;
        if (rs[0] != null) { short[] t = rs[0].flush(); out.add(t, 0, t.length); }
        return out.trim();
    }

    /**
     * Waveform peaks (max |sample| per 10 ms, 0..1) and a mono 11025 Hz copy for beat analysis
     * (only when wantMono), streamed so long songs never sit in memory at full rate.
     */
    static Object[] analyze(Context ctx, Uri uri, final boolean wantMono, AtomicBoolean cancel) throws IOException {
        final java.util.ArrayList<Float> peaks = new java.util.ArrayList<Float>();
        final float[][] mono = {new float[Beats.SR * 60]};
        final int[] mn = {0};
        final double[] st = {0, 0, 0, 0};   // peak acc, peak count, mono acc, mono count
        final double[] pos = {0};           // mono resample accumulator
        boolean ok = decodeAudio(ctx, uri, 0, 0, new PcmSink() {
            @Override public void pcm(short[] d, int frames, int channels, int rate) {
                int per = Math.max(1, rate / 100);
                double step = rate / (double) Beats.SR;
                for (int i = 0; i < frames; i++) {
                    float s = 0;
                    for (int c = 0; c < channels; c++) s += d[i * channels + c];
                    s /= channels * 32768f;
                    st[0] = Math.max(st[0], Math.abs(s));
                    if (++st[1] >= per) { peaks.add((float) st[0]); st[0] = 0; st[1] = 0; }
                    if (wantMono) {
                        st[2] += s;
                        st[3]++;
                        pos[0] += 1;
                        if (pos[0] >= step) {
                            pos[0] -= step;
                            if (mn[0] >= mono[0].length) {
                                float[] b = new float[mono[0].length * 2];
                                System.arraycopy(mono[0], 0, b, 0, mn[0]);
                                mono[0] = b;
                            }
                            mono[0][mn[0]++] = (float) (st[2] / st[3]);
                            st[2] = 0;
                            st[3] = 0;
                        }
                    }
                }
            }
        }, cancel);
        if (!ok) return null;
        float[] p = new float[peaks.size()];
        for (int i = 0; i < p.length; i++) p[i] = peaks.get(i);
        float[] m = null;
        if (wantMono) {
            m = new float[mn[0]];
            System.arraycopy(mono[0], 0, m, 0, mn[0]);
        }
        return new Object[]{p, m};
    }

    /**
     * The song's analysis for visualizers and music-reactive effects: read from the cache, or
     * decoded (whole file, streamed to 24 kHz mono), analysed and cached. Null if no audio.
     */
    static AudioFeatures songFeatures(Context ctx, String uri, AtomicBoolean cancel) throws IOException {
        File dir = new File(ctx.getCacheDir(), "fx");
        dir.mkdirs();
        File f = new File(dir, AudioFeatures.cacheName(uri));
        if (f.exists()) {
            try { return AudioFeatures.read(f); } catch (IOException e) { f.delete(); }
        }
        final float[][] buf = {new float[AudioFeatures.SR * 60]};
        final int[] n = {0};
        final AudioDsp.Resampler[] rs = {null};
        final int[] fmt = {0, 0};
        final int[] carry = {0, 0};   // {has a leftover frame, its L+R}: 48 k -> 24 k pairs frames across chunks
        boolean ok = decodeAudio(ctx, Uri.parse(uri), 0, 0, new PcmSink() {
            @Override public void pcm(short[] d, int frames, int channels, int rate) {
                if (rs[0] == null || fmt[0] != channels || fmt[1] != rate) {
                    rs[0] = new AudioDsp.Resampler(channels, rate);
                    fmt[0] = channels;
                    fmt[1] = rate;
                }
                short[] st = rs[0].process(d, frames);      // 48 kHz stereo
                // -> 24 kHz mono, averaging frame pairs. A chunk with an odd frame count leaves
                // one frame over for the next chunk (3.2 dropped it: ~0.1 s of analysis drift over
                // a 4-minute 44.1 kHz song)
                int fr = st.length / 2;
                if (n[0] + fr / 2 + 1 > buf[0].length) {
                    float[] b = new float[Math.max(buf[0].length * 2, n[0] + fr / 2 + 1)];
                    System.arraycopy(buf[0], 0, b, 0, n[0]);
                    buf[0] = b;
                }
                float[] o = buf[0];
                for (int i = 0; i < fr; i++) {
                    int s2 = st[i * 2] + st[i * 2 + 1];
                    if (carry[0] == 0) { carry[0] = 1; carry[1] = s2; }
                    else { o[n[0]++] = (carry[1] + s2) / (4 * 32768f); carry[0] = 0; }
                }
            }
        }, cancel);
        if (!ok || n[0] == 0 || (cancel != null && cancel.get())) return null;
        AudioFeatures fx = AudioFeatures.compute(buf[0], n[0]);
        try { fx.write(f); } catch (IOException ignored) {}
        return fx;
    }
}
