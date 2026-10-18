package com.christopher.strata;

import android.graphics.Bitmap;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Timeline thumbnails under one global memory budget. Each video holds up to ~60 frames of
 * ~160 KB, so a long project with many clips used to keep hundreds of MB alive (and never let go
 * of clips that were deleted). Frames are kept in least-recently-drawn order; past the budget
 * the oldest are dropped (not recycled: the timeline may be drawing one this instant, and the
 * GC frees it once nothing points at it). Lookups return the nearest frame still cached and
 * report when it is too far from the wanted time, so the caller can re-fetch just that frame.
 */
final class ThumbCache {
    static final long DEFAULT_BUDGET = 48L << 20;

    private final long budget;
    private long bytes;
    /** "uri\ntime" -> entry, in access order (eldest = least recently drawn). */
    private final LinkedHashMap<String, Entry> lru = new LinkedHashMap<String, Entry>(256, 0.75f, true);
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, Bitmap>> byUri =
            new ConcurrentHashMap<String, ConcurrentSkipListMap<Long, Bitmap>>();
    /** Spacing of each video's frame grid (the fill job's step). */
    private final ConcurrentHashMap<String, Long> spacing = new ConcurrentHashMap<String, Long>();
    /** Frames the budget dropped: only these are re-fetched (frames that never decoded aren't retried). */
    private final java.util.Set<String> evicted = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final class Entry {
        final String uri;
        final long t;
        final long size;
        Entry(String uri, long t, long size) { this.uri = uri; this.t = t; this.size = size; }
    }

    ThumbCache(long budget) { this.budget = budget; }

    static long sizeOf(Bitmap b) { return b.getAllocationByteCount(); }

    void setSpacing(String uri, long us) { spacing.put(uri, us); }

    synchronized void put(String uri, long t, Bitmap b) {
        String k = uri + "\n" + t;
        evicted.remove(k);
        Entry old = lru.remove(k);
        if (old != null) bytes -= old.size;
        Entry e = new Entry(uri, t, sizeOf(b));
        lru.put(k, e);
        bytes += e.size;
        map(uri).put(t, b);
        trim(e);
    }

    boolean has(String uri, long t) {
        ConcurrentSkipListMap<Long, Bitmap> m = byUri.get(uri);
        return m != null && m.containsKey(t);
    }

    /**
     * Nearest cached frame to us, or null. want[0] is the grid frame nearest us if the budget
     * dropped it (worth re-fetching), else -1.
     */
    Bitmap get(String uri, long us, long[] want) {
        want[0] = -1;
        ConcurrentSkipListMap<Long, Bitmap> m = byUri.get(uri);
        Long sp = spacing.get(uri);
        Map.Entry<Long, Bitmap> f = m == null ? null : m.floorEntry(us), c = m == null ? null : m.ceilingEntry(us);
        Map.Entry<Long, Bitmap> best = f == null ? c : (c == null ? f : (us - f.getKey() <= c.getKey() - us ? f : c));
        if (sp != null && sp > 0) {
            long k = Math.max(0, Math.round(us / (double) sp) * sp);
            if (evicted.contains(uri + "\n" + k)) want[0] = k;
        }
        if (best == null) return null;
        synchronized (this) { lru.get(uri + "\n" + best.getKey()); }   // mark as recently drawn
        return best.getValue();
    }

    /** A re-fetch failed: stop asking for this frame. */
    void giveUp(String uri, long t) { evicted.remove(uri + "\n" + t); }

    /** Drops every frame of a video (e.g. its last clip left the project). */
    synchronized void forget(String uri) {
        Iterator<Map.Entry<String, Entry>> it = lru.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.uri.equals(uri)) { bytes -= e.size; it.remove(); }
        }
        Iterator<String> ev = evicted.iterator();
        while (ev.hasNext()) if (ev.next().startsWith(uri + "\n")) ev.remove();
        byUri.remove(uri);
        spacing.remove(uri);
    }

    synchronized long bytes() { return bytes; }
    synchronized int count() { return lru.size(); }

    private ConcurrentSkipListMap<Long, Bitmap> map(String uri) {
        ConcurrentSkipListMap<Long, Bitmap> m = byUri.get(uri);
        if (m == null) {
            m = new ConcurrentSkipListMap<Long, Bitmap>();
            ConcurrentSkipListMap<Long, Bitmap> prev = byUri.putIfAbsent(uri, m);
            if (prev != null) m = prev;
        }
        return m;
    }

    /** Evicts least recently drawn frames until under budget, never the one just added. */
    private void trim(Entry keep) {
        Iterator<Map.Entry<String, Entry>> it = lru.entrySet().iterator();
        while (bytes > budget && it.hasNext()) {
            Entry e = it.next().getValue();
            if (e == keep) continue;
            it.remove();
            bytes -= e.size;
            evicted.add(e.uri + "\n" + e.t);
            ConcurrentSkipListMap<Long, Bitmap> m = byUri.get(e.uri);
            if (m != null) m.remove(e.t);
        }
    }
}
