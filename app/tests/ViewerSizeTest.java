package com.christopher.strata;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.9: dragging the bar under the video viewer. VideoEditor.split is pure: panel and timeline
 * heights after a drag of dy px (down = bigger viewer).
 * Usage: bash tests/run.sh ViewerSizeTest
 */
public class ViewerSizeTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) throws Exception {
        // a phone-ish budget in px: 2000 px for viewer + panel + timeline, viewer min 300
        int avail = 2000, minV = 300, pMin = 250, tMin = 200, tNat = 600;
        int[] r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, 0);
        check("No drag, no change", r[0] == 700 && r[1] == 600);
        r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, 300);
        check("Bigger viewer takes from the panel first", r[0] == 400 && r[1] == 600);
        r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, 600);
        check("...then from the timeline once the panel is at its minimum", r[0] == pMin && r[1] == 600 - (600 - (700 - pMin)));
        r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, 5000);
        check("Never below the panel minimum or the main track", r[0] == pMin && r[1] == tMin);
        r = VideoEditor.split(pMin, tMin, tNat, tMin, pMin, avail, minV, -300);
        check("Smaller viewer gives back to a squeezed timeline first", r[0] == pMin && r[1] == tMin + 300);
        r = VideoEditor.split(pMin, tMin, tNat, tMin, pMin, avail, minV, -700);
        check("...then grows the panel", r[1] == tNat && r[0] == pMin + 300);
        r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, -5000);
        check("The viewer keeps its minimum height", r[0] + r[1] == avail - minV && r[1] == 600);
        r = VideoEditor.split(700, 600, tNat, tMin, pMin, avail, minV, 120.6f);
        check("Fractional drags round to whole pixels", r[0] == 579);

        String ed = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/VideoEditor.java")), "UTF-8");
        check("Sideways turns on the full-screen viewer", ed.contains("if (land) { setFullView(true); }"));
        check("Upright again leaves it", ed.contains("else { if (fullView) setFullView(false); }"));
        check("Rotation follows the phone's auto-rotate setting", ed.contains("SCREEN_ORIENTATION_FULL_USER"));
        check("Closing the editor returns the app to portrait", ed.contains("act.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);"));
        check("Back restores hidden toolbars", ed.contains("if (barsHidden) { setBarsHidden(false); return true; }"));
        String main = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/MainActivity.java")), "UTF-8");
        check("Activity forwards rotation to the video editor", main.contains("((VideoEditor) ws).orientationChanged("));
        String mf = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), "UTF-8");
        check("Manifest keeps orientation changes in-process (no restart mid-edit)", mf.contains("orientation|screenSize"));
        System.out.println(count + " passed, 0 failed");
    }
}
