package com.christopher.strata;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * The project library on disk: one folder per project holding doc.json (photo) or video.json
 * (video), thumb.jpg and meta.json {name, created}. Deleting moves a folder into .trash so it
 * can be undone or restored after restarting. Only an explicit permanent deletion removes it.
 */
final class Projects {
    static final class Entry {
        File dir;
        boolean video;
        String name, detail;
        long modified, created;
    }

    /** The project's data file; falls back to a leftover .tmp or .bak so a damaged project still lists (and opens via recovery). */
    static File dataFile(File dir) {
        for (String n : new String[]{"video.json", "doc.json", "video.json.tmp", "doc.json.tmp", "video.json.bak", "doc.json.bak"}) {
            File f = new File(dir, n);
            if (f.exists() && f.length() > 0) return f;
        }
        return null;
    }

    private static JSONObject meta(File dir) {
        try { return new JSONObject(Loader.readText(new File(dir, "meta.json"))); } catch (Exception e) { return new JSONObject(); }
    }

    private static void writeMeta(File dir, JSONObject o) {
        try { Loader.writeText(new File(dir, "meta.json"), o.toString()); } catch (Exception ignored) {}
    }

    /** Creation time: from meta, else the folder name (p/v + millis), else the data file. */
    static long created(File dir) {
        long c = meta(dir).optLong("created", 0);
        if (c > 0) return c;
        try { return Long.parseLong(dir.getName().substring(1).replaceAll("[^0-9].*$", "")); } catch (Exception ignored) {}
        File f = dataFile(dir);
        return f == null ? 0 : f.lastModified();
    }

    static String name(File dir) {
        String n = meta(dir).optString("name", "");
        if (!n.isEmpty()) return n;
        boolean video = new File(dir, "video.json").exists();
        return (video ? "Video" : "Photo") + " · " + new SimpleDateFormat("MMM d", Locale.US).format(new Date(created(dir)));
    }

    static void setName(File dir, String name) {
        JSONObject o = meta(dir);
        try {
            o.put("name", name.trim());
            if (!o.has("created")) o.put("created", created(dir));
        } catch (Exception ignored) {}
        writeMeta(dir, o);
    }

    /** All projects (not the trash), with display facts read from their files. */
    static ArrayList<Entry> list(File root) {
        return list(root,false);
    }

    static ArrayList<Entry> listTrash(File root) { return list(trashDir(root),true); }

    private static ArrayList<Entry> list(File root,boolean includeDamaged) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            File f = dataFile(d);
            if (f == null && !includeDamaged && ProjectVersions.list(d).isEmpty()) continue;
            Entry e = new Entry();
            e.dir = d;
            e.video = f == null ? d.getName().startsWith("v") : f.getName().startsWith("video.json");
            e.name = name(d);
            e.modified = f == null ? d.lastModified() : f.lastModified();
            e.created = created(d);
            e.detail = f == null ? "Working file missing · recover from Versions" : "";
            try {
                JSONObject o = new JSONObject(Loader.readText(f));
                if (e.video) {
                    VProject vp = VProject.fromJson(o);
                    e.detail = VProject.ASPECT_NAMES[vp.aspectIdx] + " · " + VProject.RES_NAMES[vp.resIdx] + " · " + TimelineView.fmtDur(vp.duration());
                } else e.detail = Math.round(o.getDouble("cropW")) + " × " + Math.round(o.getDouble("cropH"));
            } catch (Exception ignored) {}
            out.add(e);
        }
        return out;
    }

    static File trashDir(File root) {
        File t = new File(root, ".trash");
        t.mkdirs();
        return t;
    }

    /** Moves a project into the trash; returns where it went (for undo), or null. */
    static File trash(File root, File dir) {
        try {
            File dest = new File(trashDir(root), dir.getName());
            if (dest.exists()) dest = ProjectFiles.unique(trashDir(root),dir.getName()+"_");
            java.util.Properties p=new java.util.Properties();p.setProperty("original",dir.getName());p.setProperty("deleted",Long.toString(System.currentTimeMillis()));
            File info=new File(dir,".trash.properties");
            try(FileOutputStream out=new FileOutputStream(info)){p.store(out,"Independent Video Trash");out.getFD().sync();}
            if(dir.renameTo(dest))return dest;
            info.delete();
        }catch(IOException ignored){}
        return null;
    }

    static boolean restore(File root, File trashed) {
        try {
            String original=trashed.getName();java.util.Properties p=new java.util.Properties();
            File info=new File(trashed,".trash.properties");
            if(info.exists())try(InputStream in=new FileInputStream(info)){p.load(in);original=p.getProperty("original",original);}
            if(!original.matches("[pv][A-Za-z0-9_-]+"))return false;
            File dest = new File(root, original);
            if(dest.exists())dest=ProjectFiles.unique(root,original.startsWith("v")?"v":"p");
            if(!trashed.renameTo(dest))return false;
            new File(dest,".trash.properties").delete();return true;
        }catch(IOException e){return false;}
    }

    static long deletedAt(File dir) {
        try{java.util.Properties p=new java.util.Properties();try(InputStream in=new FileInputStream(new File(dir,".trash.properties"))){p.load(in);}return Long.parseLong(p.getProperty("deleted","0"));}
        catch(Exception e){return dir.lastModified();}
    }

    static int trashCount(File root) { File[] files=new File(root,".trash").listFiles();int n=0;if(files!=null)for(File f:files)if(f.isDirectory()&&!f.getName().startsWith("."))n++;return n; }

    /** Full copy under a new folder named like the original kind; returns the copy. */
    static File duplicate(File root, File dir) throws IOException {
        File data=dataFile(dir);
        String prefix = data!=null&&data.getName().startsWith("video") ? "v" : "p";
        File dest = ProjectFiles.unique(root,prefix), stage=new File(root,"."+dest.getName());
        try{ProjectFiles.copy(dir,stage);if(!stage.renameTo(dest))throw new IOException("Can't finish duplicated project");}
        catch(IOException e){ProjectFiles.remove(stage);throw e;}
        JSONObject o = meta(dest);
        try {
            o.put("name", name(dir) + " copy");
            o.put("created", System.currentTimeMillis());
        } catch (Exception ignored) {}
        writeMeta(dest, o);
        // bump the data file so the copy sorts as just edited
        File f = dataFile(dest);
        if (f != null) f.setLastModified(System.currentTimeMillis());
        return dest;
    }

    /** Sets name and creation time for a new project. */
    static void init(File dir, String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("created", System.currentTimeMillis());
        } catch (Exception ignored) {}
        writeMeta(dir, o);
    }

    /** "IMG_2041.HEIC" -> "IMG_2041". */
    static String baseName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
