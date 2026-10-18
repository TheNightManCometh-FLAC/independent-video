package com.christopher.strata;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Decoding (preview, full, region), orientation, file copies and gallery export. */
final class Loader {

    /** Full-resolution facts about a source file. */
    static final class Info {
        int rawW, rawH, orientation = 1;
        int w, h;   // oriented
        boolean regionOk;
    }

    static void copy(ContentResolver cr, Uri uri, File dest) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Can't open that file");
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    static Info probe(File f) {
        Info i = new Info();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        i.rawW = o.outWidth; i.rawH = o.outHeight;
        try {
            ExifInterface ex = new ExifInterface(f.getPath());
            i.orientation = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (Throwable t) { i.orientation = 1; }
        if (i.orientation < 1 || i.orientation > 8) i.orientation = 1;
        boolean swap = i.orientation >= 5;
        i.w = swap ? i.rawH : i.rawW;
        i.h = swap ? i.rawW : i.rawH;
        String mime = o.outMimeType == null ? "" : o.outMimeType;
        i.regionOk = mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp");
        return i;
    }

    /**
     * Decode, orientation applied, scaled so the long edge is <= maxSide, always ARGB_8888 and mutable-free.
     * ImageDecoder handles JPEG, PNG, WebP, HEIF and DNG raw.
     */
    static Bitmap decode(File f, int maxSide) throws IOException { return decode(f, maxSide, null); }

    /** sizeOut (optional) receives the full-resolution size the decoder reports in its header. */
    static Bitmap decode(File f, final int maxSide, final int[] sizeOut) throws IOException {
        Bitmap b;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(f);
            b = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    if (sizeOut != null) { sizeOut[0] = w; sizeOut[1] = h; }
                    int m = Math.max(w, h);
                    if (m > maxSide) {
                        float k = maxSide / (float) m;
                        d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                    }
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Can't decode: " + t.getMessage());
        }
        return toArgb(b);
    }

    static Bitmap toArgb(Bitmap b) {
        if (b == null) return null;
        if (b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
        b.recycle();
        return c;
    }

    /** Decode the oriented rectangle r of the full-resolution image, downsampled by sample (power of 2). */
    static Bitmap decodeRegion(BitmapRegionDecoder dec, Info info, Rect r, int sample) {
        int W = info.rawW, H = info.rawH;
        int[] xs = new int[4], ys = new int[4];
        int[][] c = {{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}};
        for (int k = 0; k < 4; k++) {
            int u = c[k][0], v = c[k][1], x, y;
            switch (info.orientation) {
                case 2: x = W - u; y = v; break;
                case 3: x = W - u; y = H - v; break;
                case 4: x = u; y = H - v; break;
                case 5: x = v; y = u; break;
                case 6: x = v; y = H - u; break;
                case 7: x = W - v; y = H - u; break;
                case 8: x = W - v; y = u; break;
                default: x = u; y = v;
            }
            xs[k] = x; ys[k] = y;
        }
        Rect raw = new Rect(min(xs), min(ys), max(xs), max(ys));
        raw.intersect(0, 0, W, H);
        if (raw.isEmpty()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inSampleSize = Math.max(1, sample);
        Bitmap b = dec.decodeRegion(raw, o);
        if (b == null || info.orientation == 1) return b;
        Matrix m = new Matrix();
        switch (info.orientation) {
            case 2: m.postScale(-1, 1); break;
            case 3: m.postRotate(180); break;
            case 4: m.postScale(1, -1); break;
            case 5: m.postRotate(90); m.postScale(-1, 1); break;
            case 6: m.postRotate(90); break;
            case 7: m.postRotate(90); m.postScale(1, -1); break;
            case 8: m.postRotate(270); break;
        }
        Bitmap o2 = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
        if (o2 != b) b.recycle();
        return o2;
    }

    private static int min(int[] a) { int m = a[0]; for (int v : a) m = Math.min(m, v); return m; }
    private static int max(int[] a) { int m = a[0]; for (int v : a) m = Math.max(m, v); return m; }

    /** Writes into Pictures/Emulsion. No storage permission needed on Android 10+. */
    static Uri saveToGallery(Context ctx, Bitmap bmp, boolean png, int quality, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name + (png ? ".png" : ".jpg"));
        v.put(MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Independent Video");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Gallery refused the file");
        OutputStream out = cr.openOutputStream(uri);
        try {
            if (out == null || !bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, out))
                throw new IOException("Encoding failed");
        } finally {
            if (out != null) out.close();
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    // ---- masks on disk: ALPHA_8 <-> grey PNG ----
    static void saveMask(Bitmap mask, File f) throws IOException {
        Bitmap g = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        g.eraseColor(0xff000000);
        Paint p = new Paint();
        p.setColor(0xffffffff);
        new Canvas(g).drawBitmap(mask, 0, 0, p);   // ALPHA_8 draws the paint colour with its alpha
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try { g.compress(Bitmap.CompressFormat.PNG, 100, out); out.getFD().sync(); } finally { out.close(); g.recycle(); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");   // a crash mid-write never leaves a half PNG
    }

    /** w, h <= 0: keep the file's own size (linked masks). */
    static Bitmap loadMask(File f, int w, int h) {
        Bitmap g = BitmapFactory.decodeFile(f.getPath());
        if (g == null) return null;
        if (w <= 0 || h <= 0) { w = g.getWidth(); h = g.getHeight(); }
        Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0})));  // alpha = red
        new Canvas(m).drawBitmap(g, new Rect(0, 0, g.getWidth(), g.getHeight()), new Rect(0, 0, w, h), p);
        g.recycle();
        return m;
    }

    static void saveJpeg(Bitmap b, File f, int q) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { b.compress(Bitmap.CompressFormat.JPEG, q, out); } finally { out.close(); }
    }

    static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, "UTF-8");
        } finally { in.close(); }
    }

    static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try { out.write(s.getBytes("UTF-8")); out.getFD().sync(); } finally { out.close(); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
