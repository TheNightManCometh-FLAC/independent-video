package com.christopher.strata;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 3.15: the bundled font library (assets/fonts, OFL and Apache Google Fonts, see LICENSES.txt),
 * shared by text masks and titles. Font keys stored in projects:
 *   "asset:File.ttf"  a bundled font        "file:Name.ttf"  one the user imported
 *   anything else     an Android family name (sans-serif, serif, casual...)
 * Variable fonts take a weight (wght axis); 0 means the catalog's default weight.
 */
final class Fonts {
    static AssetManager assets;
    /**
     * Bundled fonts are copied here on first use and opened as files: the APK may store assets
     * compressed, and file-backed fonts take variation settings (weight) everywhere.
     */
    static java.io.File cacheDir;
    static final String[] CATS = {"Impact", "Sans", "Serif", "Script", "Retro", "Mono", "Grunge"};

    static final class F {
        final String name, file, cat;
        final int wmin, wmax, wdef;
        F(String name, String file, String cat, int wmin, int wmax, int wdef) {
            this.name = name; this.file = file; this.cat = cat; this.wmin = wmin; this.wmax = wmax; this.wdef = wdef;
        }
        String key() { return "asset:" + file; }
        boolean variable() { return wmax > wmin; }
    }

    static final F[] ALL = {
            new F("Bebas Neue", "BebasNeue.ttf", "Impact", 0, 0, 0),
            new F("Anton", "Anton.ttf", "Impact", 0, 0, 0),
            new F("Oswald", "Oswald.ttf", "Impact", 200, 700, 700),
            new F("Archivo Black", "ArchivoBlack.ttf", "Impact", 0, 0, 0),
            new F("Alfa Slab One", "AlfaSlabOne.ttf", "Impact", 0, 0, 0),
            new F("Bowlby One", "BowlbyOne.ttf", "Impact", 0, 0, 0),
            new F("Titan One", "TitanOne.ttf", "Impact", 0, 0, 0),
            new F("Luckiest Guy", "LuckiestGuy.ttf", "Impact", 0, 0, 0),
            new F("Rubik Mono One", "RubikMonoOne.ttf", "Impact", 0, 0, 0),
            new F("Black Ops One", "BlackOpsOne.ttf", "Impact", 0, 0, 0),
            new F("Big Shoulders", "BigShoulders.ttf", "Impact", 100, 900, 700),
            new F("Dela Gothic One", "DelaGothicOne.ttf", "Impact", 0, 0, 0),
            new F("Inter", "Inter.ttf", "Sans", 100, 900, 400),
            new F("Montserrat", "Montserrat.ttf", "Sans", 100, 900, 400),
            new F("Space Grotesk", "SpaceGrotesk.ttf", "Sans", 300, 700, 400),
            new F("DM Sans", "DMSans.ttf", "Sans", 100, 1000, 400),
            new F("Manrope", "Manrope.ttf", "Sans", 200, 800, 400),
            new F("Unbounded", "Unbounded.ttf", "Sans", 200, 900, 400),
            new F("Outfit", "Outfit.ttf", "Sans", 100, 900, 400),
            new F("Syne", "Syne.ttf", "Sans", 400, 800, 400),
            new F("Archivo", "Archivo.ttf", "Sans", 100, 900, 400),
            new F("Lexend", "Lexend.ttf", "Sans", 100, 900, 400),
            new F("Familjen Grotesk", "FamiljenGrotesk.ttf", "Sans", 400, 700, 400),
            new F("Bricolage Grotesque", "BricolageGrotesque.ttf", "Sans", 200, 800, 400),
            new F("Playfair Display", "PlayfairDisplay.ttf", "Serif", 400, 900, 400),
            new F("DM Serif Display", "DMSerifDisplay.ttf", "Serif", 0, 0, 0),
            new F("Fraunces", "Fraunces.ttf", "Serif", 100, 900, 400),
            new F("Bodoni Moda", "BodoniModa.ttf", "Serif", 400, 900, 400),
            new F("Cormorant Garamond", "CormorantGaramond.ttf", "Serif", 300, 700, 400),
            new F("Instrument Serif", "InstrumentSerif.ttf", "Serif", 0, 0, 0),
            new F("Abril Fatface", "AbrilFatface.ttf", "Serif", 0, 0, 0),
            new F("Cinzel", "Cinzel.ttf", "Serif", 400, 900, 400),
            new F("Libre Baskerville", "LibreBaskerville.ttf", "Serif", 400, 700, 400),
            new F("Young Serif", "YoungSerif.ttf", "Serif", 0, 0, 0),
            new F("Pacifico", "Pacifico.ttf", "Script", 0, 0, 0),
            new F("Lobster", "Lobster.ttf", "Script", 0, 0, 0),
            new F("Dancing Script", "DancingScript.ttf", "Script", 400, 700, 400),
            new F("Great Vibes", "GreatVibes.ttf", "Script", 0, 0, 0),
            new F("Caveat", "Caveat.ttf", "Script", 400, 700, 400),
            new F("Permanent Marker", "PermanentMarker.ttf", "Script", 0, 0, 0),
            new F("Satisfy", "Satisfy.ttf", "Script", 0, 0, 0),
            new F("Yellowtail", "Yellowtail.ttf", "Script", 0, 0, 0),
            new F("Rock Salt", "RockSalt.ttf", "Script", 0, 0, 0),
            new F("Kaushan Script", "KaushanScript.ttf", "Script", 0, 0, 0),
            new F("Shrikhand", "Shrikhand.ttf", "Script", 0, 0, 0),
            new F("Mrs Saint Delafield", "MrsSaintDelafield.ttf", "Script", 0, 0, 0),
            new F("Monsieur La Doulaise", "MonsieurLaDoulaise.ttf", "Script", 0, 0, 0),
            new F("Gochi Hand", "GochiHand.ttf", "Script", 0, 0, 0),
            new F("Monoton", "Monoton.ttf", "Retro", 0, 0, 0),
            new F("Righteous", "Righteous.ttf", "Retro", 0, 0, 0),
            new F("Bungee", "Bungee.ttf", "Retro", 0, 0, 0),
            new F("Bungee Shade", "BungeeShade.ttf", "Retro", 0, 0, 0),
            new F("Bungee Outline", "BungeeOutline.ttf", "Retro", 0, 0, 0),
            new F("Faster One", "FasterOne.ttf", "Retro", 0, 0, 0),
            new F("Audiowide", "Audiowide.ttf", "Retro", 0, 0, 0),
            new F("Orbitron", "Orbitron.ttf", "Retro", 400, 900, 400),
            new F("Tilt Warp", "TiltWarp.ttf", "Retro", 0, 0, 0),
            new F("Vast Shadow", "VastShadow.ttf", "Retro", 0, 0, 0),
            new F("Sancreek", "Sancreek.ttf", "Retro", 0, 0, 0),
            new F("Fredoka", "Fredoka.ttf", "Retro", 300, 700, 400),
            new F("Space Mono", "SpaceMono.ttf", "Mono", 0, 0, 0),
            new F("JetBrains Mono", "JetBrainsMono.ttf", "Mono", 100, 800, 400),
            new F("IBM Plex Mono", "IBMPlexMono.ttf", "Mono", 0, 0, 0),
            new F("VT323", "VT323.ttf", "Mono", 0, 0, 0),
            new F("Press Start 2P", "PressStart2P.ttf", "Mono", 0, 0, 0),
            new F("Silkscreen", "Silkscreen.ttf", "Mono", 0, 0, 0),
            new F("Major Mono Display", "MajorMonoDisplay.ttf", "Mono", 0, 0, 0),
            new F("Share Tech Mono", "ShareTechMono.ttf", "Mono", 0, 0, 0),
            new F("Creepster", "Creepster.ttf", "Grunge", 0, 0, 0),
            new F("Nosifer", "Nosifer.ttf", "Grunge", 0, 0, 0),
            new F("Eater", "Eater.ttf", "Grunge", 0, 0, 0),
            new F("Butcherman", "Butcherman.ttf", "Grunge", 0, 0, 0),
            new F("Metal Mania", "MetalMania.ttf", "Grunge", 0, 0, 0),
            new F("UnifrakturMaguntia", "UnifrakturMaguntia.ttf", "Grunge", 0, 0, 0),
            new F("Pirata One", "PirataOne.ttf", "Grunge", 0, 0, 0),
            new F("New Rocker", "NewRocker.ttf", "Grunge", 0, 0, 0),
            new F("Rubik Glitch", "RubikGlitch.ttf", "Grunge", 0, 0, 0),
            new F("Rubik Wet Paint", "RubikWetPaint.ttf", "Grunge", 0, 0, 0),
            new F("Special Elite", "SpecialElite.ttf", "Grunge", 0, 0, 0),
            new F("Rubik Distressed", "RubikDistressed.ttf", "Grunge", 0, 0, 0),
            new F("Frijole", "Frijole.ttf", "Grunge", 0, 0, 0),    };

    private Fonts() {}

    /** Copies the whole library out of the APK in the background (first launch), so the browser opens fast. */
    static void warm() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { for (F f : ALL) file(f); }
        }, "font-warm");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    static F byKey(String key) {
        if (key == null || !key.startsWith("asset:")) return null;
        String f = key.substring(6);
        for (F x : ALL) if (x.file.equals(f)) return x;
        return null;
    }

    static ArrayList<F> inCat(String cat) {
        ArrayList<F> o = new ArrayList<F>();
        for (F x : ALL) if (x.cat.equals(cat)) o.add(x);
        return o;
    }

    /** The weight a variable font is drawn at (0 = its default), clamped to its axis. */
    static int weightFor(F f, int weight) {
        if (f == null || !f.variable()) return 0;
        int w = weight <= 0 ? f.wdef : weight;
        return Math.max(f.wmin, Math.min(f.wmax, w));
    }

    /** The bundled font as a file in cacheDir (copied from assets once), or null if that fails. */
    static synchronized java.io.File file(F f) {
        if (cacheDir == null || assets == null) return null;
        java.io.File out = new java.io.File(cacheDir, f.file);
        if (out.length() > 0) return out;
        try {
            cacheDir.mkdirs();
            java.io.File tmp = new java.io.File(cacheDir, f.file + ".tmp");
            java.io.InputStream in = assets.open("fonts/" + f.file);
            java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            o.getFD().sync();
            o.close();
            return tmp.renameTo(out) ? out : null;
        } catch (Exception e) { return null; }
    }

    private static final HashMap<String, Typeface> cache = new HashMap<String, Typeface>();

    /**
     * Typeface for a bundled font at a weight; italic is the platform's slant (the library has
     * upright files only). Null if the key isn't a bundled font or the file can't be read.
     */
    static synchronized Typeface typeface(String key, int weight, boolean italic) {
        F f = byKey(key);
        if (f == null || assets == null) return null;
        int w = weightFor(f, weight);
        String ck = f.file + "|" + w + "|" + italic;
        Typeface t = cache.get(ck);
        if (t != null) return t;
        java.io.File file = file(f);
        try {
            Typeface.Builder b = file != null ? new Typeface.Builder(file) : new Typeface.Builder(assets, "fonts/" + f.file);
            if (w > 0) b.setFontVariationSettings("'wght' " + w);
            t = b.build();
        } catch (Throwable e) { t = null; }
        if (t == null) try { t = file != null ? Typeface.createFromFile(file) : Typeface.createFromAsset(assets, "fonts/" + f.file); } catch (Throwable e) { t = null; }
        if (t == null) return null;
        if (italic) t = Typeface.create(t, Typeface.ITALIC);
        cache.put(ck, t);
        return t;
    }
}
