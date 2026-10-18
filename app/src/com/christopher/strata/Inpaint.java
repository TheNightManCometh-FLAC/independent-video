package com.christopher.strata;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Content-aware fill: multi-scale PatchMatch nearest-neighbour search with EM voting
 * (Barnes et al. 2009 "PatchMatch"; Wexler et al. 2007 "Space-time completion").
 * Coarse levels find the structure, finer levels inherit the patch mapping and refine it, the
 * finest level only votes and polishes, so the result has full-resolution texture.
 * Pure Java (no Android types) so it is tested off-device.
 */
final class Inpaint {
    interface Progress {
        void step(float f);
        boolean cancelled();
    }

    static final int R = 3;   // 7 x 7 patches

    private static final class Level {
        int w, h, W, H;        // size and padded size
        int[] img;             // padded, 0xRRGGBB
        byte[] hole;           // padded (pad replicates the edge flags)
        boolean[] valid;       // q usable as a source patch centre
        boolean[] target;      // p is a patch centre whose patch touches the hole
        int[] targets, holes, validList;
        int[] nnf, nnd;
    }

    private long seed = 0x2545F4914F6CDD1DL;

    private int rnd(int n) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 1) % n);
    }

    /** img: 0xRRGGBB per pixel (alpha ignored); hole: 1 = fill. Returns a filled copy, or null if cancelled/impossible. */
    static int[] fill(int[] img, byte[] hole, int w, int h, Progress pr) {
        return new Inpaint().run(img, hole, w, h, pr);
    }

    private int[] run(int[] img0, byte[] hole0, int w0, int h0, Progress pr) {
        ArrayList<int[]> imgs = new ArrayList<int[]>();
        ArrayList<byte[]> holes = new ArrayList<byte[]>();
        ArrayList<int[]> sizes = new ArrayList<int[]>();
        imgs.add(img0.clone());
        holes.add(hole0.clone());
        sizes.add(new int[]{w0, h0});
        while (imgs.size() < 12) {
            int[] sz = sizes.get(sizes.size() - 1);
            byte[] ho = holes.get(holes.size() - 1);
            int[] bb = holeBox(ho, sz[0], sz[1]);
            if (bb == null) break;
            int ext = Math.max(bb[2] - bb[0], bb[3] - bb[1]) + 1;
            if (ext <= 4 * R || sz[0] / 2 < 8 * R || sz[1] / 2 < 8 * R) break;
            Object[] d = down(imgs.get(imgs.size() - 1), ho, sz[0], sz[1]);
            int[] nsz = (int[]) d[2];
            if (holeBox((byte[]) d[1], nsz[0], nsz[1]) == null) break;   // hole vanished: stay finer
            imgs.add((int[]) d[0]);
            holes.add((byte[]) d[1]);
            sizes.add(nsz);
        }
        int L = imgs.size();
        int totalSteps = 0;
        for (int li = L - 1; li >= 0; li--) totalSteps += emCount(li, L);
        int done = 0;
        Level prev = null;
        for (int li = L - 1; li >= 0; li--) {
            int[] sz = sizes.get(li);
            Level lv = makeLevel(imgs.get(li), holes.get(li), sz[0], sz[1]);
            if (lv.validList.length == 0) return null;
            if (prev == null) {
                diffuse(lv);
                for (int p : lv.targets) {
                    lv.nnf[p] = lv.validList[rnd(lv.validList.length)];
                    lv.nnd[p] = dist(lv, p, lv.nnf[p], Integer.MAX_VALUE);
                }
            } else {
                upsample(prev, lv);
                vote(lv);
            }
            int em = emCount(li, L), pm = (li == L - 1) ? 4 : 2;
            int search = (li == L - 1) ? Math.max(lv.w, lv.h) : Math.max(8, Math.max(lv.w, lv.h) / 3);
            for (int e = 0; e < em; e++) {
                for (int k = 0; k < pm; k++) {
                    patchMatch(lv, k % 2 == 0, search);
                    if (pr != null && pr.cancelled()) return null;
                }
                vote(lv);
                done++;
                if (pr != null) pr.step(done / (float) totalSteps);
            }
            prev = lv;
        }
        int[] out = new int[w0 * h0];
        for (int y = 0; y < h0; y++)
            for (int x = 0; x < w0; x++)
                out[y * w0 + x] = (img0[y * w0 + x] & 0xff000000) | (prev.img[(y + R) * prev.W + x + R] & 0xffffff);
        return out;
    }

    private static int emCount(int li, int L) {
        if (li == L - 1) return 10;
        if (li == 0) return 3;
        return 6;
    }

    // ------------------------------------------------------------------ pyramid
    static int[] holeBox(byte[] ho, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (ho[y * w + x] != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    /** Halves the image. A coarse pixel is a hole only if all its fine pixels are; otherwise it averages the known ones. */
    private static Object[] down(int[] im, byte[] ho, int w, int h) {
        int w2 = (w + 1) / 2, h2 = (h + 1) / 2;
        int[] o = new int[w2 * h2];
        byte[] oh = new byte[w2 * h2];
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < w2; x++) {
                int r = 0, g = 0, b = 0, n = 0, ra = 0, ga = 0, ba = 0, na = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int yy = Math.min(h - 1, y * 2 + dy);
                    for (int dx = 0; dx < 2; dx++) {
                        int xx = Math.min(w - 1, x * 2 + dx);
                        int c = im[yy * w + xx];
                        ra += (c >> 16) & 255; ga += (c >> 8) & 255; ba += c & 255; na++;
                        if (ho[yy * w + xx] == 0) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; }
                    }
                }
                if (n > 0) o[y * w2 + x] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
                else { o[y * w2 + x] = ((ra / na) << 16) | ((ga / na) << 8) | (ba / na); oh[y * w2 + x] = 1; }
            }
        }
        return new Object[]{o, oh, new int[]{w2, h2}};
    }

    private static Level makeLevel(int[] im, byte[] ho, int w, int h) {
        Level lv = new Level();
        lv.w = w; lv.h = h; lv.W = w + 2 * R; lv.H = h + 2 * R;
        int W = lv.W, H = lv.H, N = W * H;
        lv.img = new int[N];
        lv.hole = new byte[N];
        for (int y = 0; y < H; y++) {
            int sy = Math.max(0, Math.min(h - 1, y - R));
            for (int x = 0; x < W; x++) {
                int sx = Math.max(0, Math.min(w - 1, x - R));
                lv.img[y * W + x] = im[sy * w + sx] & 0xffffff;
                lv.hole[y * W + x] = ho[sy * w + sx];
            }
        }
        // integral image of hole flags
        int[] I = new int[(W + 1) * (H + 1)];
        for (int y = 0; y < H; y++) {
            int row = 0;
            for (int x = 0; x < W; x++) {
                row += lv.hole[y * W + x];
                I[(y + 1) * (W + 1) + x + 1] = I[y * (W + 1) + x + 1] + row;
            }
        }
        lv.valid = new boolean[N];
        lv.target = new boolean[N];
        lv.nnf = new int[N];
        lv.nnd = new int[N];
        int nv = 0, nt = 0, nh = 0;
        for (int y = R; y < R + h; y++) {
            for (int x = R; x < R + w; x++) {
                int s = I[(y + R + 1) * (W + 1) + x + R + 1] - I[(y - R) * (W + 1) + x + R + 1]
                        - I[(y + R + 1) * (W + 1) + x - R] + I[(y - R) * (W + 1) + x - R];
                int i = y * W + x;
                if (s == 0) { lv.valid[i] = true; nv++; } else { lv.target[i] = true; nt++; }
                if (lv.hole[i] != 0) nh++;
            }
        }
        lv.validList = new int[nv];
        lv.targets = new int[nt];
        lv.holes = new int[nh];
        nv = nt = nh = 0;
        for (int y = R; y < R + h; y++) {
            for (int x = R; x < R + w; x++) {
                int i = y * W + x;
                if (lv.valid[i]) lv.validList[nv++] = i;
                if (lv.target[i]) lv.targets[nt++] = i;
                if (lv.hole[i] != 0) lv.holes[nh++] = i;
            }
        }
        return lv;
    }

    /** Coarsest level start: fill the hole inward from its edge (onion peeling with neighbour averages). */
    private static void diffuse(Level lv) {
        int W = lv.W;
        boolean[] known = new boolean[lv.img.length];
        for (int i = 0; i < known.length; i++) known[i] = lv.hole[i] == 0;
        int[] todo = lv.holes.clone();
        int left = todo.length;
        int[] newly = new int[todo.length];
        int[] col = new int[todo.length];
        while (left > 0) {
            int nn = 0;
            for (int t = 0; t < left; t++) {
                int p = todo[t];
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int q = p + dy * W + dx;
                        if ((dx | dy) == 0 || q < 0 || q >= known.length || !known[q]) continue;
                        int c = lv.img[q];
                        r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                    }
                if (n > 0) { newly[nn] = p; col[nn] = ((r / n) << 16) | ((g / n) << 8) | (b / n); nn++; }
            }
            if (nn == 0) break;
            for (int k = 0; k < nn; k++) { lv.img[newly[k]] = col[k]; known[newly[k]] = true; }
            int j = 0;
            for (int t = 0; t < left; t++) if (!known[todo[t]]) todo[j++] = todo[t];
            left = j;
        }
        refreshPad(lv);
    }

    private void upsample(Level c, Level f) {
        int W = f.W;
        for (int p : f.holes) {
            int x = p % W - R, y = p / W - R;
            f.img[p] = c.img[(y / 2 + R) * c.W + x / 2 + R];
        }
        refreshPad(f);
        for (int p : f.targets) {
            int x = p % W - R, y = p / W - R;
            int pc = (y / 2 + R) * c.W + x / 2 + R;
            int q = -1;
            if (c.target[pc]) {
                int qc = c.nnf[pc];
                int qx = 2 * (qc % c.W - R) + (x & 1), qy = 2 * (qc / c.W - R) + (y & 1);
                if (qx >= 0 && qy >= 0 && qx < f.w && qy < f.h) {
                    int qf = (qy + R) * W + qx + R;
                    if (f.valid[qf]) q = qf;
                }
            }
            if (q < 0) q = f.validList[rnd(f.validList.length)];
            f.nnf[p] = q;
            f.nnd[p] = dist(f, p, q, Integer.MAX_VALUE);
        }
    }

    // ------------------------------------------------------------------ PatchMatch + voting
    private static int dist(Level lv, int p, int q, int best) {
        int[] im = lv.img;
        int W = lv.W, s = 0;
        for (int dy = -R; dy <= R; dy++) {
            int pp = p + dy * W - R, qq = q + dy * W - R;
            for (int dx = 0; dx <= 2 * R; dx++) {
                int a = im[pp + dx], b = im[qq + dx];
                int dr = ((a >> 16) & 255) - ((b >> 16) & 255);
                int dg = ((a >> 8) & 255) - ((b >> 8) & 255);
                int db = (a & 255) - (b & 255);
                s += dr * dr + dg * dg + db * db;
            }
            if (s >= best) return s;
        }
        return s;
    }

    private void patchMatch(Level lv, boolean fwd, int search) {
        int n = lv.targets.length, W = lv.W, N = lv.img.length;
        int step = fwd ? 1 : -1;
        for (int t = 0; t < n; t++) {
            int p = lv.targets[fwd ? t : n - 1 - t];
            int best = lv.nnf[p], bd = lv.nnd[p];
            int np = p - step;
            if (lv.target[np]) {
                int q = lv.nnf[np] + step;
                if (q >= 0 && q < N && q != best && lv.valid[q]) {
                    int d = dist(lv, p, q, bd);
                    if (d < bd) { bd = d; best = q; }
                }
            }
            np = p - step * W;
            if (np >= 0 && np < N && lv.target[np]) {
                int q = lv.nnf[np] + step * W;
                if (q >= 0 && q < N && q != best && lv.valid[q]) {
                    int d = dist(lv, p, q, bd);
                    if (d < bd) { bd = d; best = q; }
                }
            }
            int bx = best % W, by = best / W;
            for (int r = search; r >= 1; r >>= 1) {
                int x = bx + rnd(2 * r + 1) - r, y = by + rnd(2 * r + 1) - r;
                if (x < R || y < R || x >= lv.w + R || y >= lv.h + R) continue;
                int q = y * W + x;
                if (q == best || !lv.valid[q]) continue;
                int d = dist(lv, p, q, bd);
                if (d < bd) { bd = d; best = q; }
            }
            lv.nnf[p] = best;
            lv.nnd[p] = bd;
        }
    }

    /** Every hole pixel becomes the similarity-weighted average of what all overlapping patches propose. */
    private static void vote(Level lv) {
        int W = lv.W, n = lv.targets.length;
        if (n == 0) return;
        // weight scale: 75th percentile of current patch distances
        int sn = Math.min(n, 4000);
        int[] sample = new int[sn];
        for (int i = 0; i < sn; i++) sample[i] = lv.nnd[lv.targets[(int) ((long) i * n / sn)]];
        Arrays.sort(sample);
        double sig2 = Math.max(1, sample[(int) (sn * 0.75)]);
        float[] wt = new float[lv.img.length];
        for (int p : lv.targets) wt[p] = (float) Math.exp(-lv.nnd[p] / (2 * sig2));
        for (int x : lv.holes) {
            float r = 0, g = 0, b = 0, ws = 0;
            for (int dy = -R; dy <= R; dy++) {
                for (int dx = -R; dx <= R; dx++) {
                    int p = x - dy * W - dx;
                    if (!lv.target[p]) continue;
                    int c = lv.img[lv.nnf[p] + dy * W + dx];
                    float w = wt[p];
                    r += w * ((c >> 16) & 255); g += w * ((c >> 8) & 255); b += w * (c & 255);
                    ws += w;
                }
            }
            if (ws > 1e-12f) {
                int ri = Math.min(255, Math.round(r / ws)), gi = Math.min(255, Math.round(g / ws)), bi = Math.min(255, Math.round(b / ws));
                lv.img[x] = (ri << 16) | (gi << 8) | bi;
            }
        }
        refreshPad(lv);
        for (int p : lv.targets) lv.nnd[p] = dist(lv, p, lv.nnf[p], Integer.MAX_VALUE);
    }

    private static void refreshPad(Level lv) {
        int W = lv.W, H = lv.H;
        for (int y = 0; y < H; y++) {
            int sy = Math.max(R, Math.min(R + lv.h - 1, y));
            for (int x = 0; x < W; x++) {
                if (y >= R && y < R + lv.h && x >= R && x < R + lv.w) continue;
                int sx = Math.max(R, Math.min(R + lv.w - 1, x));
                lv.img[y * W + x] = lv.img[sy * W + sx];
            }
        }
    }
}
