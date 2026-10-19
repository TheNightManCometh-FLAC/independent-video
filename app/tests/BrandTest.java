package com.christopher.strata;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.9 (Independent Video): the wordmark's motion, the brand assets, the two-mode home.
 * Usage: bash tests/run.sh BrandTest   (from the project root)
 */
public class BrandTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) throws Exception {
        float eps = 1e-6f;
        check("Wordmark starts wide apart", Math.abs(WordmarkView.shiftAt(0) - WordmarkView.OPEN) < eps);
        check("Converges to rest at 1.1 s", Math.abs(WordmarkView.shiftAt(1100) - WordmarkView.REST) < 1e-4f);
        boolean mono = true; float prev = WordmarkView.shiftAt(0);
        for (int ms = 10; ms <= 1100; ms += 10) { float v = WordmarkView.shiftAt(ms); if (v > prev + eps) mono = false; prev = v; }
        check("Convergence never springs back open", mono);
        float lo = 1, hi = 0;
        for (int ms = 1100; ms < 60000; ms += 33) { float v = WordmarkView.shiftAt(ms); lo = Math.min(lo, v); hi = Math.max(hi, v); }
        check("Drift stays near rest (never closes to a flat white)", lo > 0.002f && hi < 0.0075f);
        check("Drift is continuous at the handover", Math.abs(WordmarkView.shiftAt(1101) - WordmarkView.shiftAt(1099)) < 1e-4f);

        for (String f : new String[]{"wm_red.png", "wm_blue.png", "wm_core.png", "bam_logo.png"})
            check("asset brand/" + f, new File("assets/brand/" + f).length() > 1000);
        java.awt.image.BufferedImage r = javax.imageio.ImageIO.read(new File("assets/brand/wm_red.png"));
        java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(new File("assets/brand/wm_blue.png"));
        java.awt.image.BufferedImage c = javax.imageio.ImageIO.read(new File("assets/brand/wm_core.png"));
        check("Wordmark layers share one size", r.getWidth() == c.getWidth() && b.getWidth() == c.getWidth() && r.getHeight() == c.getHeight() && b.getHeight() == c.getHeight());

        String main = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/MainActivity.java")), "UTF-8");
        check("Canvas mode removed", !main.contains("newCanvasDialog") && !main.contains("createCanvas"));
        check("Home has the Video and Photo cards", main.contains("modeCard(\"video\", \"Video\"") && main.contains("modeCard(\"image\", \"Photo\""));
        check("Back from Projects returns home", main.contains("else if (library) { library = false; showStart(); }"));
        String mf = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), "UTF-8");
        check("New application id", mf.contains("package=\"com.betterappmafia.independentvideo\""));
        check("Activity named by its Java class, not the id", mf.contains("android:name=\"com.christopher.strata.MainActivity\""));
        check("Label is Independent Video", new String(Files.readAllBytes(Paths.get("res/values/strings.xml")), "UTF-8").contains(">Independent Video<"));
        // 1.0
        check("Video card isn't music-only", !main.contains("Cut your clips to music.") && main.contains("Cut, color, retime and title your footage."));
        int hs = main.indexOf("private View homeScreen("), he = main.indexOf("static final class FitColumn");
        String home = main.substring(hs, he);
        check("Home doesn't scroll (a FitColumn, no ScrollView)", home.contains("FitColumn col = new FitColumn(this);") && !home.contains("new ScrollView("));
        check("Home is true black", home.contains("col.setBackgroundColor(Brand.BLACK)") && new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/Brand.java")), "UTF-8").contains("BLACK = 0xff000000"));
        check("Title sits in the viewfinder frame", home.contains("frame.setBackground(new Brand.Viewfinder())"));
        check("Recent drops first when the screen is short", home.contains("col.optional(recentBlock, 1)"));
        String brand = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/Brand.java")), "UTF-8");
        check("Contact address", brand.contains("CONTACT = \"cporter31415@gmail.com\""));
        check("Ko-fi link only when the build ships brand/donate.txt", brand.contains("a.open(\"brand/donate.txt\")") && main.contains("if (donate != null)"));
        check("donate.txt holds the Ko-fi page", new String(Files.readAllBytes(Paths.get("assets/brand/donate.txt")), "UTF-8").trim().equals("https://ko-fi.com/betterappmafia"));
        check("Version is 1.0 or later, versionCode 2 or more", java.util.regex.Pattern.compile("android:versionName=\"[1-9][0-9]*(\\.[0-9]+){1,2}\"").matcher(mf).find()
            && java.util.regex.Pattern.compile("android:versionCode=\"([2-9]|[1-9][0-9]+)\"").matcher(mf).find());
        String sec = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/InspectorSections.java")), "UTF-8");
        check("Detail sections get a back row to their list", sec.contains("markList") && sec.contains("backRow("));
        String ed = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/VideoEditor.java")), "UTF-8");
        String ph = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/Editor.java")), "UTF-8");
        check("Video effects stack is a list section", ed.contains("markList(panel, \"Stack\", \"Effects list\")"));
        check("Photo layers and effect browser are list sections", ph.contains("markList(panel, \"Stack\", \"Layers\")") && ph.contains("markList(panel, \"Browse\", \"Effect list\")"));
        String sh = new String(Files.readAllBytes(Paths.get("src/com/christopher/strata/Sheet.java")), "UTF-8");
        check("A sheet opened from a sheet can go back", sh.contains("parent = opening; addBackArrow();") && sh.contains("if (parent != null) { dismiss(); parent.show(); }"));
        System.out.println(count + " passed, 0 failed");
    }
}
