package com.christopher.strata;

import java.util.HashMap;

/**
 * The app's icon set: SVG path data on a 24 x 24 grid, drawn as 1.75-unit round strokes (prefix
 * "F|" = filled). Dots are zero-length segments ("h.01"), which round caps turn into dots.
 * The same strings are rendered in Chromium for review, so what's checked is what ships.
 */
final class Icons {
    static final HashMap<String, String> P = new HashMap<String, String>();

    static {
        // navigation + generic
        P.put("back", "M15 5l-7 7 7 7");
        P.put("next", "M9 5l7 7-7 7");
        // 1.0: support + contact on the home screen / app menu
        P.put("heart", "M12 20s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 7.3a4.2 4.2 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z");
        P.put("mail", "M3.5 6h17v12h-17zM3.5 7l8.5 6.5L20.5 7");   // 3.16.1: "Move later" asked for it; back mirrored
        P.put("close", "M6 6l12 12M18 6L6 18");
        // three-point editing (3.7)
        P.put("mark_in", "M10 4H6v16h4M14.5 8.5v7");
        P.put("mark_out", "M14 4h4v16h-4M9.5 8.5v7");
        P.put("insert", "M3 7h5v10H3zM16 7h5v10h-5zM12 3.5v9M9.5 10L12 12.5 14.5 10");
        P.put("overwrite", "M4 11h16v9H4zM12 3v9.5M9.5 10L12 12.5 14.5 10");
        P.put("append", "M3 7h10v10H3zM16.5 12h5M19 9.5v5");
        P.put("replace", "M4 9h13l-3.5-3.5M20 15H7l3.5 3.5");
        P.put("freeze", "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9");
        P.put("plus", "M12 5v14M5 12h14");
        P.put("select", "M4 7V4h3M10 4h4M17 4h3v3M20 10v4M20 17v3h-3M14 20h-4M7 20H4v-3M4 14v-4");
        P.put("paste", "M9 3.5h6v3H9zM15.5 5H17.5A1.5 1.5 0 0119 6.5v13A1.5 1.5 0 0117.5 21h-11A1.5 1.5 0 015 19.5v-13A1.5 1.5 0 016.5 5H8.5M9 12h6M9 16h4");
        P.put("more", "M12 3.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2zM12 10.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2zM12 17.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2z");
        P.put("check", "M5 12.5l4.5 4.5L19 7");
        P.put("trash", "M4 7h16M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10 11v5.5M14 11v5.5");
        P.put("copy", "M9 9h11v11H9zM15 9V4H4v11h5");
        P.put("pencil", "M4 20l1.2-4.6L15.8 4.8a2.1 2.1 0 0 1 3 3L8.2 18.4zM13.8 6.8l3 3");
        P.put("sort", "M7 4v16M3.5 16.5L7 20l3.5-3.5M13 6h8M13 11h6M13 16h4");
        P.put("share", "M12 15V3.5M7.5 8L12 3.5 16.5 8M5 12v8h14v-8");
        P.put("open", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6");
        P.put("folder", "M3 6.5h6.5l2 2H21V19H3z");
        P.put("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v6M12 7.5h.01");
        P.put("pulse", "M3 12h4l3-8 4 16 3-8h4");
        P.put("fit", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5");
        P.put("up", "M12 19V5M6 11l6-6 6 6");
        P.put("down", "M12 5v14M6 13l6 6 6-6");
        P.put("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM16 16l4.5 4.5");
        P.put("lock", "M6 10h12v11H6zM8 10V7a4 4 0 0 1 8 0v3M12 14v3");
        // media kinds
        P.put("image", "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M17 8.5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z");
        P.put("canvas", "M4 7.5V4h3.5M16.5 4H20v3.5M20 16.5V20h-3.5M7.5 20H4v-3.5M8.5 8.5h7v7h-7z");
        P.put("video", "M3 6.5h12.5v11H3zM15.5 10.5L21 7v10l-5.5-3.5");
        P.put("film", "M4 3h16v18H4zM8 3v18M16 3v18M4 7.5h4M4 12h4M4 16.5h4M16 7.5h4M16 12h4M16 16.5h4");
        // editor chrome
        P.put("undo", "M8.5 13.5L4 9l4.5-4.5M4 9h10.5a5.5 5.5 0 0 1 0 11H10");
        P.put("redo", "M15.5 13.5L20 9l-4.5-4.5M20 9H9.5a5.5 5.5 0 0 0 0 11H14");
        P.put("compare", "M4 5h16v14H4zM12 3v18M5.5 15.5l5-5M5.5 11l3.5-3.5M7.5 18.5l3-3");
        P.put("export", "M12 3.5V15M7.5 10.5L12 15l4.5-4.5M5 17v3h14v-3");
        // photo tabs
        P.put("layers", "M12 3l9 4.8-9 4.8-9-4.8zM3 12.2l9 4.8 9-4.8M3 16.4l9 4.8 9-4.8");
        P.put("sliders", "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M15 4v4M9 10v4M17 16v4");
        P.put("heal", "M5.6 14.2l8.6-8.6a3 3 0 0 1 4.2 4.2l-8.6 8.6a3 3 0 0 1-4.2-4.2zM10.5 10.5h.01M13.5 13.5h.01M13.5 10.5h.01M10.5 13.5h.01");
        P.put("link", "M10 13.5a4.2 4.2 0 0 0 6 .4l3-3a4.2 4.2 0 0 0-6-6l-1.6 1.6M14 10.5a4.2 4.2 0 0 0-6-.4l-3 3a4.2 4.2 0 0 0 6 6l1.6-1.6");
        P.put("mask", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 3v18M12 7.5h5M12 12h6.5M12 16.5h5");
        P.put("crop", "M6 2.5V18h15.5M2.5 6H18v15.5");
        // video tabs
        P.put("grade", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 5.9a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4zM8.2 12.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4zM15.8 12.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z");
        P.put("text", "M5 7V4.5h14V7M12 4.5v15M9 19.5h6");
        P.put("bars", "M4.5 20v-6M8.5 20V8M12.5 20v-9M16.5 20V5M20 20v-8");
        P.put("music", "M9 17.5V5.5l11-2v12M9 17.5a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM20 15.5a3 3 0 1 1-6 0a3 3 0 1 1 6 0z");
        P.put("aspect", "M3 6h18v12H3zM6.5 11V9h2.5M17.5 13v2H15");
        // layers
        P.put("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z");
        P.put("eye_off", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM4 4l16 16");
        P.put("drag", "M5 9h14M5 15h14");
        P.put("fill", "M4 4h16v16H4zM4 12l8-8M4 20L20 4M12 20l8-8");
        P.put("light", "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8zM12 2.5v2M12 19.5v2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M2.5 12h2M19.5 12h2M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4");
        P.put("curves", "M4 4v16h16M4 20C11 20 13 4 20 4");
        P.put("levels", "M3 20h18M5.5 20v-4M8.5 20v-8M11.5 20V7M14.5 20v-6M17.5 20v-9M20 20v-3");
        P.put("hsl", "M12 3s6.5 6.6 6.5 11.2a6.5 6.5 0 0 1-13 0C5.5 9.6 12 3 12 3z");
        P.put("lut", "M12 2.5l8.5 4.8v9.4L12 21.5l-8.5-4.8V7.3zM3.5 7.3l8.5 4.8 8.5-4.8M12 12.1v9.4");
        P.put("detail", "M3 12h4l2-5.5 3 11 3-8.5 2 3h4");
        P.put("move", "M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3");
        P.put("blend", "M9 6a6 6 0 1 1 0 12a6 6 0 1 1 0-12zM15 6a6 6 0 1 1 0 12a6 6 0 1 1 0-12z");
        // video clip actions
        P.put("split", "M6 9.5a3 3 0 1 1 0-6a3 3 0 1 1 0 6zM6 20.5a3 3 0 1 1 0-6a3 3 0 1 1 0 6zM8.4 8.2L20 19M8.4 15.8L20 5");
        P.put("speed", "M3.5 17a8.5 8.5 0 0 1 17 0M12 17l4.5-5.5");
        P.put("mute", "M4 9.5h4l5-4v13l-5-4H4zM16 9.5l5 5M21 9.5l-5 5");
        P.put("fade_in", "M3 19L13 7h8M3 19h18");
        P.put("fade_out", "M3 7h8l10 12M3 19h18");
        P.put("volume", "M4 9.5h4l5-4v13l-5-4H4zM16.5 9.5a3.5 3.5 0 0 1 0 5M19 7a7 7 0 0 1 0 10");
        // transport
        P.put("play", "F|M7.5 4.5l12 7.5-12 7.5z");
        P.put("pause", "F|M6.5 5h3.5v14H6.5zM14 5h3.5v14H14z");
        P.put("start", "M6 5v14M19 5l-9.5 7 9.5 7z");
        P.put("frame_back", "M8 6v12M18 6l-7 6 7 6");
        P.put("frame_fwd", "M16 6v12M6 6l7 6-7 6");
        P.put("fx", "M10 3.5l1.7 4.8 4.8 1.7-4.8 1.7L10 16.5l-1.7-4.8L3.5 10l4.8-1.7zM18 14v6M15 17h6M17.5 3.5v3M16 5h3");
        P.put("star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9-4.3-4.1 5.9-.8z");
        P.put("star_on", "F|M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9-4.3-4.1 5.9-.8z");
        P.put("reset", "M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4");
        P.put("mirror", "M12 3v2.5M12 8.5v2.5M12 14v2.5M12 19.5V21M9 6.5L3.5 17.5H9zM15 6.5l5.5 11H15z");
        P.put("spiral", "M12 12a1.5 1.5 0 0 1 3 0a3 3 0 0 1-6 0a4.5 4.5 0 0 1 9 0a6 6 0 0 1-12 0a7.5 7.5 0 0 1 15 0");
        P.put("keyframe", "M12 4l8 8-8 8-8-8z");
        P.put("marker", "M7 3h10v11l-5 6-5-6z");
        P.put("fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5");
        P.put("guides", "M4 4h16v16H4zM9.33 4v16M14.67 4v16M4 9.33h16M4 14.67h16");
        P.put("camera", "M4 8h3.5l2-3h5l2 3H20v11H4zM12 10.5a3 3 0 1 1 0 6a3 3 0 1 1 0-6");
    }

    static boolean has(String name) { return P.containsKey(name); }

    static String get(String name) {
        String s = P.get(name);
        if (s == null) throw new IllegalArgumentException("no icon " + name);
        return s;
    }
}
