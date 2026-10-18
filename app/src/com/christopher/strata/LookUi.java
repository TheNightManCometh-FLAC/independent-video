package com.christopher.strata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Dialogs and sheets for saved looks, shared by both editors. */
final class LookUi {
    interface Apply { void apply(ArrayList<Layer> layers, String name); }

    /** 3.14: renders a look on the current picture for a tile; null in editors without thumbnails. */
    interface Thumbs { void request(ArrayList<Layer> layers, View disc); }

    static void pick(final Activity act, final boolean forVideo, final Apply cb) { pick(act, forVideo, cb, null); }

    /**
     * Name the look and choose which adjustments go in it. candidates are bottom first; the list
     * shows them top first like the layer stack. Masked layers are noted (the mask isn't saved).
     */
    static void saveDialog(final Activity act, final List<Layer> candidates, String suggested) {
        if (candidates.isEmpty()) {
            Toast.makeText(act, "There are no adjustments to save yet.", Toast.LENGTH_LONG).show();
            return;
        }
        final int n = candidates.size();
        final String[] labels = new String[n];
        final boolean[] on = new boolean[n];
        for (int i = 0; i < n; i++) {
            Layer l = candidates.get(n - 1 - i);
            labels[i] = l.name + (l.visible ? "" : "  (hidden)") + (l.mask != null ? "  (mask not saved)" : "");
            on[i] = l.visible;
        }
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        final EditText name = new EditText(act);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(suggested);
        name.setSelectAllOnFocus(true);
        name.setHint("Look name");
        box.addView(name);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save look")
                .setView(box)
                .setMultiChoiceItems(labels, on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) { on[which] = checked; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ArrayList<Layer> chosen = new ArrayList<Layer>();
                        for (int i = n - 1; i >= 0; i--) if (on[i]) chosen.add(candidates.get(n - 1 - i));   // back to bottom first
                        String nm = name.getText().toString().trim();
                        if (nm.isEmpty()) nm = "Look";
                        try {
                            Looks.save(nm, chosen);
                            Toast.makeText(act, "Saved look “" + nm + "” (" + Looks.describe(chosen) + ")", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Pick a saved look; forVideo marks looks whose Detail layers will be skipped. */
    static void pick(final Activity act, final boolean forVideo, final Apply cb, final Thumbs thumbs) {
        ArrayList<Looks.Look> all = new ArrayList<Looks.Look>(Looks.builtIns());
        int nBuilt = all.size();
        ArrayList<Looks.Look> mine = Looks.list();
        all.addAll(mine);
        Sheet sh = new Sheet(act, "Apply a look");
        final Catalog cat = new Catalog(act, "looks");   // shared by the photo and video editors
        sh.note("Hold a look to add it to Favorites.");
        boolean any = false;
        for (Looks.Look k : all) if (cat.fav(lookKey(k))) { if (!any) sh.section("Favorites"); any = true; lookTile(act, sh, cat, k, forVideo, cb, thumbs); }
        any = false;
        for (String key : cat.recent) for (Looks.Look k : all) if (lookKey(k).equals(key)) {
            if (!any) sh.section("Recent"); any = true; lookTile(act, sh, cat, k, forVideo, cb, thumbs);
        }
        sh.section("Built in");
        for (int i = 0; i < nBuilt; i++) lookTile(act, sh, cat, all.get(i), forVideo, cb, thumbs);
        sh.section("Your looks");
        if (mine.isEmpty()) {
            sh.note("Your own looks appear here: use \u201cSave look\u201d to keep a set of adjustments.");
            sh.show();
            return;
        }
        for (Looks.Look k : mine) lookTile(act, sh, cat, k, forVideo, cb, thumbs);
        sh.item("pencil", "Rename or delete looks…", new Runnable() { @Override public void run() { manage(act); } });
        sh.show();
    }

    /** A look's catalog key is its file name (stable across renames of the display name). */
    static String lookKey(Looks.Look k) { return k.asset != null ? "builtin:" + k.asset : k.file == null ? k.name : k.file.getName(); }

    /** A look as a tile: rendered on the current picture where the editor can (video), an icon otherwise. */
    private static void lookTile(final Activity act, Sheet sh, final Catalog cat, final Looks.Look k, boolean forVideo, final Apply cb, Thumbs thumbs) {
        final String key = lookKey(k);
        sh.tile(cat.fav(key) ? "star_on" : "star", (cat.fav(key) ? "\u2605 " : "") + k.name, new Runnable() {
            @Override public void run() {
                cat.used(key);
                try { cb.apply(Looks.load(k), k.name); }
                catch (Exception e) { Toast.makeText(act, "Couldn't open that look: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
        }, new Runnable() {
            @Override public void run() {
                Toast.makeText(act, cat.toggle(key) ? "“" + k.name + "” added to Favorites" : "“" + k.name + "” removed from Favorites", Toast.LENGTH_SHORT).show();
            }
        });
        if (thumbs != null) try { thumbs.request(Looks.load(k), sh.lastTileDisc()); } catch (Exception ignored) {}
    }

    static void manage(final Activity act) {
        ArrayList<Looks.Look> ls = Looks.list();
        if (ls.isEmpty()) { Toast.makeText(act, "No saved looks.", Toast.LENGTH_SHORT).show(); return; }
        Sheet sh = new Sheet(act, "Saved looks");
        for (final Looks.Look k : ls) {
            sh.item("star", k.name, k.summary, new Runnable() {
                @Override public void run() {
                    Sheet one = new Sheet(act, k.name);
                    final Catalog cat = new Catalog(act, "looks");
                    one.item(cat.fav(lookKey(k)) ? "star_on" : "star", cat.fav(lookKey(k)) ? "Remove from Favorites" : "Add to Favorites", new Runnable() {
                        @Override public void run() { cat.toggle(lookKey(k)); }
                    });
                    one.item("pencil", "Rename", new Runnable() {
                        @Override public void run() {
                            Pickers.text(act, "Rename look", k.name, false, new Pickers.TextCb() {
                                @Override public void text(String s) {
                                    try { Looks.rename(k, s.trim().isEmpty() ? k.name : s.trim()); }
                                    catch (Exception e) { Toast.makeText(act, "Couldn't rename: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                                }
                            });
                        }
                    });
                    one.danger("trash", "Delete", new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Delete “" + k.name + "”?")
                                    .setMessage("Projects that used it keep their adjustments.")
                                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) { Looks.delete(k); }
                                    })
                                    .setNegativeButton("Cancel", null).show();
                        }
                    });
                    one.show();
                }
            });
        }
        sh.show();
    }
}
