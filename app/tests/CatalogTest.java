package com.christopher.strata;

import java.util.Arrays;
import java.util.List;

/** 3.13: catalog Recent list and the stored key format. */
public class CatalogTest {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("ok   " + name); }

    public static void main(String[] args) {
        List<String> r = Catalog.pushRecent(Arrays.asList("17", "20", "3"), "20", 8);
        check("A re-used entry moves to the front without a duplicate", r.equals(Arrays.asList("20", "17", "3")));
        List<String> full = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8");
        List<String> p = Catalog.pushRecent(full, "9", Catalog.RECENT_MAX);
        check("Recent keeps the last 8", p.size() == 8 && p.get(0).equals("9") && !p.contains("8"));
        check("Keys round-trip through storage", Catalog.split(Catalog.join(Arrays.asList("Kick flash", "look_17.json", "3"))).equals(Arrays.asList("Kick flash", "look_17.json", "3")));
        check("Empty storage reads as an empty list", Catalog.split("").isEmpty() && Catalog.split(null).isEmpty());
        check("Damaged storage drops empties and duplicates", Catalog.split("a\n\na\nb").equals(Arrays.asList("a", "b")));
        check("A key containing a newline is never stored", Catalog.join(Arrays.asList("ok", "bad\nkey")).equals("ok"));
        System.out.println(count + " passed, 0 failed");
    }
}
