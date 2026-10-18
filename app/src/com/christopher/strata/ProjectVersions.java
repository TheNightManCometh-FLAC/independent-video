package com.christopher.strata;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, checked local snapshots. Restore publishes a new project, preserving the current edit. */
final class ProjectVersions {
    static final class Entry {
        final File dir; final String name; final long created;
        Entry(File f, Properties p) { dir=f; name=p.getProperty("name", "Version"); created=Long.parseLong(p.getProperty("created", "0")); }
    }
    private static Properties read(File f) throws IOException {
        Properties p=new Properties();
        try (InputStream in=new FileInputStream(f)) { p.load(in); }
        return p;
    }
    private static void write(File f, Properties p) throws IOException {
        File tmp=new File(f.getPath()+".tmp");
        try (FileOutputStream out=new FileOutputStream(tmp)) { p.store(out,"Independent Video recovery version"); out.getFD().sync(); }
        if(!tmp.renameTo(f)) throw new IOException("Can't finish version metadata");
    }
    static ArrayList<Entry> list(File project) {
        ArrayList<Entry> entries=new ArrayList<Entry>();
        File[] files=new File(project,".versions").listFiles();
        if(files!=null)for(File f:files)if(f.isDirectory()&&!f.getName().startsWith("."))try {
            entries.add(new Entry(f,read(new File(f,".version.properties"))));
        }catch(Exception ignored){}
        Collections.sort(entries,new Comparator<Entry>(){public int compare(Entry a,Entry b){return Long.compare(b.created,a.created);}});
        return entries;
    }
    static Entry create(File project,String name) throws Exception {
        if(name.trim().isEmpty())throw new IOException("Give this version a name");
        File data=Projects.dataFile(project);
        if(data==null||data.getName().endsWith(".tmp")||data.getName().endsWith(".bak"))
            throw new IOException("Save or open the project before making a version");
        File bank=new File(project,".versions"), dest=ProjectFiles.unique(bank,"v"), stage=new File(bank,"."+dest.getName());
        try {
            ProjectFiles.copy(project,stage);
            Properties p=new Properties(); p.setProperty("name",name.trim()); p.setProperty("created",Long.toString(System.currentTimeMillis()));
            p.setProperty("data",data.getName()); p.setProperty("format","1");
            manifest(stage,stage,p);
            write(new File(stage,".version.properties"),p);
            if(!stage.renameTo(dest))throw new IOException("Can't finish recovery version");
            return new Entry(dest,p);
        }catch(Exception e){ProjectFiles.remove(stage);throw e;}
    }
    private static void manifest(File root,File dir,Properties p)throws Exception {
        File[] files=dir.listFiles(); if(files==null)throw new IOException("Can't read version assets");
        for(File f:files)if(f.isDirectory())manifest(root,f,p);else {
            String rel=root.toURI().relativize(f.toURI()).getPath();p.setProperty("sha256."+rel,digest(f));
        }
    }
    private static String digest(File f)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
        try(InputStream in=new FileInputStream(f)){while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x&255));return s.toString();
    }
    static void verify(Entry entry)throws Exception {
        Properties p=read(new File(entry.dir,".version.properties"));
        String data=p.getProperty("data","");
        if(!(data.equals("doc.json")||data.equals("video.json"))||!p.containsKey("sha256."+data))throw new IOException("Incomplete recovery version");
        String base=entry.dir.getCanonicalPath()+File.separator;
        for(String key:p.stringPropertyNames())if(key.startsWith("sha256.")){
            File f=new File(entry.dir,key.substring(7));
            if(!f.getCanonicalPath().startsWith(base)||!f.isFile()||!digest(f).equals(p.getProperty(key)))
                throw new IOException("Version is damaged or an asset is missing: "+f.getName());
        }
    }
    static File restoreCopy(File root,Entry entry)throws Exception {
        verify(entry);
        File data=Projects.dataFile(entry.dir);
        File dest=ProjectFiles.unique(root,data.getName().startsWith("video")?"v":"p"), stage=new File(root,"."+dest.getName());
        try {
            ProjectFiles.copy(entry.dir,stage);
            if(!stage.renameTo(dest))throw new IOException("Can't finish restored project");
            return dest;
        }catch(Exception e){ProjectFiles.remove(stage);throw e;}
    }
    static void rename(Entry entry,String name)throws Exception {
        if(name.trim().isEmpty())throw new IOException("Give this version a name");
        File f=new File(entry.dir,".version.properties");Properties p=read(f);p.setProperty("name",name.trim());write(f,p);
    }
}
