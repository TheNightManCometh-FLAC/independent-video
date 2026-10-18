package com.christopher.strata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 3.13: Recent and Favorites for a catalog sheet (effects, audio-driven presets, looks). Alight
 * Motion lets you save favourite elements for re-use; here every catalog remembers the last 8
 * picks and any entry you hold to favourite. Stored per catalog kind in SharedPreferences, so it is
 * an app preference, never project data.
 */
final class Catalog {
    static final int RECENT_MAX = 8;
    private final android.content.SharedPreferences prefs;
    private final String kind;
    final ArrayList<String> recent = new ArrayList<String>();
    final LinkedHashSet<String> favorites = new LinkedHashSet<String>();

    Catalog(android.content.Context c, String kind) {
        this.kind = kind;
        prefs = c.getSharedPreferences("catalogs", 0);
        recent.addAll(split(prefs.getString(kind + ".recent", "")));
        favorites.addAll(split(prefs.getString(kind + ".fav", "")));
    }

    boolean fav(String key) { return favorites.contains(key); }

    /** Toggles a favourite; returns the new state. */
    boolean toggle(String key) {
        boolean on = !favorites.remove(key);
        if (on) favorites.add(key);
        prefs.edit().putString(kind + ".fav", join(favorites)).apply();
        return on;
    }

    void used(String key) {
        List<String> r = pushRecent(recent, key, RECENT_MAX);
        recent.clear(); recent.addAll(r);
        prefs.edit().putString(kind + ".recent", join(recent)).apply();
    }

    /** Pure: key moved to the front, no duplicates, at most max entries. */
    static List<String> pushRecent(List<String> list, String key, int max) {
        ArrayList<String> o = new ArrayList<String>();
        o.add(key);
        for (String s : list) if (!s.equals(key) && o.size() < max) o.add(s);
        return o;
    }

    /** Keys are joined with a newline (catalog keys never contain one). */
    static String join(Iterable<String> keys) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) { if (k.indexOf('\n') >= 0) continue; if (b.length() > 0) b.append('\n'); b.append(k); }
        return b.toString();
    }

    static List<String> split(String s) {
        ArrayList<String> o = new ArrayList<String>();
        if (s == null || s.isEmpty()) return o;
        for (String k : s.split("\n")) if (!k.isEmpty() && !o.contains(k)) o.add(k);
        return o;
    }
}
