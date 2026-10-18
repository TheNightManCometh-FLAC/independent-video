package com.christopher.strata;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;

/**
 * 3.16: the selected-item bar does things (split, delete, key, mute, copy / paste the look,
 * fades) instead of opening the inspector sections the tabs already open. Every id must be
 * handled by runBarAction, every icon must exist, and no button may be a plain jump to a panel.
 */
public class ToolbarTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    static String ids(List<VideoEditor.BarAction> b) {
        StringBuilder s = new StringBuilder();
        for (VideoEditor.BarAction a : b) s.append(s.length() == 0 ? "" : " ").append(a.id);
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "src/com/christopher/strata/VideoEditor.java")), "UTF-8");
        int run = src.indexOf("private void runBarAction(String id)");
        String body = src.substring(run, src.indexOf("\n    }\n", run));
        int[] kinds = {TimelineView.SEL_CLIP, TimelineView.SEL_MEDIA, TimelineView.SEL_TEXT, TimelineView.SEL_VIZ, TimelineView.SEL_AUDIO};
        String[] names = {"main clip", "B-roll", "title", "visualizer", "audio"};

        check("Video clip with sound", ids(VideoEditor.barActionsFor(TimelineView.SEL_CLIP, VProject.VIDEO, false, true, false))
                .equals("split delete duplicate freeze key mute copyfx pastefx trim more"));
        check("Silent video clip has no Mute", ids(VideoEditor.barActionsFor(TimelineView.SEL_CLIP, VProject.VIDEO, false, false, false))
                .equals("split delete duplicate freeze key copyfx pastefx trim more"));
        check("Photo / freeze frame: no Freeze, no Mute", ids(VideoEditor.barActionsFor(TimelineView.SEL_CLIP, VProject.IMAGE, true, false, false))
                .equals("split delete duplicate key copyfx pastefx trim more"));
        check("Video B-roll", ids(VideoEditor.barActionsFor(TimelineView.SEL_MEDIA, -1, false, true, false))
                .equals("split delete duplicate key mute copyfx pastefx fadein fadeout trim more"));
        check("Title", ids(VideoEditor.barActionsFor(TimelineView.SEL_TEXT, -1, false, false, false))
                .equals("edittext split delete duplicate key fadein fadeout trim more"));
        check("Visualizer", ids(VideoEditor.barActionsFor(TimelineView.SEL_VIZ, -1, false, false, false))
                .equals("split delete duplicate key vizpreset fadein fadeout trim more"));
        check("Audio", ids(VideoEditor.barActionsFor(TimelineView.SEL_AUDIO, -1, false, false, false))
                .equals("split delete duplicate fadein fadeout trim more"));
        check("Nothing selected: no item actions", VideoEditor.barActionsFor(TimelineView.SEL_NONE, -1, false, false, false).isEmpty());
        check("Mute shows the state", VideoEditor.barActionsFor(TimelineView.SEL_CLIP, VProject.VIDEO, false, true, true).get(5).label.equals("Unmute"));

        for (int k = 0; k < kinds.length; k++) {
            HashSet<String> icons = new HashSet<String>();
            boolean distinct = true;
            for (VideoEditor.BarAction a : VideoEditor.barActionsFor(kinds[k], k == 0 ? VProject.VIDEO : -1, false, true, false)) {
                if (!icons.add(a.icon)) distinct = false;
                check(names[k] + ": icon \"" + a.icon + "\" exists", Icons.has(a.icon));
                check(names[k] + ": " + a.id + " is handled", body.contains("\"" + a.id + "\""));
            }
            check(names[k] + ": every button has its own icon", distinct);
        }
        check("No button just opens a panel (no openSection / setTab in the bar)", !body.contains("openSection") && !body.contains("setTab"));
        System.out.println(count + " passed, 0 failed");
    }
}
