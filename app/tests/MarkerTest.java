package com.christopher.strata;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** 3.13: timeline markers and the project default transition (format v16). */
public class MarkerTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    static VProject project() {
        VProject p = new VProject();
        VProject.Clip c = new VProject.Clip();
        c.id = 1; c.uri = "content://a"; c.kind = VProject.VIDEO; c.in = 0; c.out = 10000000; c.srcDur = 20000000; c.speed = 1;
        p.clips.add(c);
        p.nextId = 2;
        return p;
    }

    public static void main(String[] args) throws Exception {
        long f = Timeline.frame(30);
        VProject p = project();
        check("No markers: format stays 14", p.formatVersion() == 14);

        VProject.Marker m2 = p.addMarker(2000000, f / 2);
        VProject.Marker m1 = p.addMarker(1000000, f / 2);
        p.addMarker(3000000, f / 2);
        check("Markers are kept in time order", p.markers.get(0) == m1 && p.markers.get(1) == m2 && p.markers.size() == 3);
        check("Tapping on an existing marker's frame doesn't duplicate it", p.addMarker(1000000 + f / 3, f / 2) == m1 && p.markers.size() == 3);
        check("Markers bump the format to 16", p.formatVersion() == 16 && ProjectIO.VIDEO_VERSION >= 16);

        check("Next marker after the playhead", p.markerStep(1000000, 1, f / 2) == 2000000);
        check("Previous marker before the playhead", p.markerStep(2000000, -1, f / 2) == 1000000);
        check("Sitting on a marker, next skips it", p.markerStep(1000000 + f / 4, 1, f / 2) == 2000000);
        check("No marker past the last one", p.markerStep(3000000, 1, f / 2) == -1);
        check("No marker before the first one", p.markerStep(500000, -1, f / 2) == -1);

        m2.color = 3; m2.note = "Drop";
        VProject q = VProject.fromJson(p.toJson());
        ProjectIO.sanitize(q, new ProjectIO.Loaded());
        check("Markers survive save and reload", q.markers.size() == 3 && q.markers.get(1).t == 2000000
                && q.markers.get(1).color == 3 && q.markers.get(1).note.equals("Drop") && q.markers.get(0).color == 0);

        VProject c = p.copy();
        c.markers.get(0).note = "changed";
        c.markers.remove(2);
        check("Undo snapshots copy markers deeply", p.markers.get(0).note.equals("") && p.markers.size() == 3);

        long[] snap = TimelineSnapping.targets(p, true, false, false, true, Collections.singleton(1), 0);
        boolean has = false; for (long t : snap) if (t == 2000000) has = true;
        check("Markers are magnet targets when enabled", has);
        long[] noSnap = TimelineSnapping.targets(p, true, false, false, false, Collections.singleton(1), 0);
        has = false; for (long t : noSnap) if (t == 2000000) has = true;
        check("Markers are not targets when their magnet is off", !has);
        check("Magnets off: nothing snaps, markers included", TimelineSnapping.targets(p, false, true, true, true, Collections.singleton(1), 0).length == 0);

        // damaged markers in a file
        JSONObject o = p.toJson();
        JSONArray ma = o.getJSONArray("markers");
        JSONObject bad = new JSONObject(); bad.put("t", -5); ma.put(bad);
        JSONObject wild = new JSONObject(); wild.put("t", 500000); wild.put("c", 99); wild.put("n", new String(new char[500]).replace('\0', 'x')); ma.put(wild);
        VProject r = VProject.fromJson(o);
        ProjectIO.Loaded log = new ProjectIO.Loaded();
        ProjectIO.sanitize(r, log);
        check("A marker before zero is dropped and reported", r.markers.size() == 4 && !log.fixes.isEmpty());
        check("Damaged marker repaired: sorted first, legal colour, bounded note",
                r.markers.get(0).t == 500000 && r.markers.get(0).color == VProject.MARKER_COLORS.length - 1 && r.markers.get(0).note.length() == 200);

        // an older file has no markers and no default
        JSONObject old = project().toJson();
        VProject oldP = VProject.fromJson(old);
        check("Older projects open with no markers and plain cuts", oldP.markers.isEmpty() && oldP.defTrans == 0 && oldP.formatVersion() == 14);

        // default transition
        VProject d = project();
        d.defTrans = 1; d.defTransDur = 750000;
        check("A default transition bumps the format to 16", d.formatVersion() == 16);
        VProject dr = VProject.fromJson(d.toJson());
        ProjectIO.sanitize(dr, new ProjectIO.Loaded());
        check("Default transition survives reload", dr.defTrans == 1 && dr.defTransDur == 750000);
        JSONObject dj = d.toJson(); dj.put("defTrans", 999); dj.put("defTransDur", 99000000L);
        VProject dd = VProject.fromJson(dj); ProjectIO.sanitize(dd, new ProjectIO.Loaded());
        check("Damaged default transition is clamped", dd.defTrans == VProject.TRANS.length - 1 && dd.defTransDur == 3000000);
        check("Locked projects without markers keep format 15", locked().formatVersion() == 15);
        System.out.println(count + " passed, 0 failed");
    }

    static VProject locked() { VProject p = project(); TimelineLocks.set(p, 1, true); return p; }
}
