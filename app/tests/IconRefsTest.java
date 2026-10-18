package com.christopher.strata;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3.16.1: "More" on a main clip crashed because its "Move later" row asked for an icon ("next")
 * that Icons never defined. ToolbarTest only checked the bar's own buttons, not the menus they
 * open. This reads every source file and checks each icon name written as a literal in an
 * icon-taking call (Sheet rows and tiles, IconDrawable, Ui.icon / iconBtn / tab / setIcon /
 * chip-with-icon, BarAction icons, the *_ICONS arrays, local helpers that forward an icon).
 * Usage: bash tests/run.sh IconRefsTest [src/com/christopher/strata]
 */
public class IconRefsTest {
    static int count, failed;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "src/com/christopher/strata");
        Pattern[] calls = {
            // first argument is the icon (optionally after a Context / view argument)
            Pattern.compile("(?:\\.item|\\.danger|\\.tile|barBtn|new IconDrawable|catChip|markBtn|actionView|createTile)\\s*\\(\\s*\"([^\"]*)\"", Pattern.DOTALL),
            // Ui helpers whose first argument is a Context or view
            Pattern.compile("(?:Ui\\.icon|Ui\\.iconBtn|Ui\\.tab|Ui\\.setIcon)\\s*\\(\\s*[A-Za-z_.]+(?:\\(\\))?\\s*,\\s*\"([^\"]*)\"", Pattern.DOTALL),
            // Ui.chip(context, icon, label, ...) — only the four/five-argument form has an icon
            Pattern.compile("Ui\\.chip\\s*\\(\\s*[A-Za-z_.()]+\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"", Pattern.DOTALL),
            // new BarAction(id, icon, label)
            Pattern.compile("new BarAction\\s*\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]*)\"", Pattern.DOTALL),
            // icon chosen by a ternary: "a" : "b" right before an icon call's comma, e.g. String icon = x ? "music" : "image"
            Pattern.compile("String\\s+icon\\s*=([^;]*);"),
        };
        Pattern arrays = Pattern.compile("_ICONS\\s*=\\s*\\{([^}]*)\\}");
        Pattern lit = Pattern.compile("\"([a-z][a-z0-9_]*)\"");
        File[] files = dir.listFiles();
        if (files == null) throw new AssertionError("no source folder " + dir);
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            for (int k = 0; k < calls.length; k++) {
                Matcher m = calls[k].matcher(s);
                while (m.find()) {
                    if (k == 4) { Matcher l = lit.matcher(m.group(1)); while (l.find()) check(f, s, m.start(), l.group(1)); }
                    else check(f, s, m.start(), m.group(1));
                }
            }
            Matcher a = arrays.matcher(s);
            while (a.find()) { Matcher l = lit.matcher(a.group(1)); while (l.find()) check(f, s, a.start(), l.group(1)); }
        }
        if (count < 200) throw new AssertionError("only " + count + " icon references found: the scanner patterns no longer match the code");
        if (failed > 0) throw new AssertionError(failed + " missing icon(s)");
        System.out.println(count + " icon references checked, 0 missing");
    }

    static void check(File f, String s, int at, String name) {
        count++;
        if (Icons.has(name)) return;
        int line = 1; for (int i = 0; i < at; i++) if (s.charAt(i) == '\n') line++;
        failed++;
        System.out.println("FAIL " + f.getName() + ":" + line + " uses icon \"" + name + "\", which Icons does not define");
    }
}
