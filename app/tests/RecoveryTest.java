package com.christopher.strata;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real filesystem regression cases: immutable assets, atomic publication and persistent recovery. */
public class RecoveryTest {
    static int count;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("ok   "+name);}
    static void write(File dir,String path,String data)throws Exception{File f=new File(dir,path);f.getParentFile().mkdirs();Files.write(f.toPath(),data.getBytes("UTF-8"));}
    static String text(File dir,String path)throws Exception{return new String(Files.readAllBytes(new File(dir,path).toPath()),"UTF-8");}
    interface Job{void run()throws Exception;}
    static void reject(String name,Job job)throws Exception{boolean rejected=false;try{job.run();}catch(Exception e){rejected=true;}check(name,rejected);}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("emulsion-recovery-").toFile();
        try {
            File p=new File(root,"p1000");p.mkdirs();
            write(p,"doc.json","{\"v\":4,\"cropW\":100,\"layers\":[{\"src\":\"src_photo.png\",\"maskFile\":\"mask_1.png\"}]}");
            write(p,"src_photo.png","original pixels");write(p,"mask_1.png","original alpha");write(p,"assets/shape.bin","nested asset");
            write(p,"doc.json.tmp","unfinished save");write(p,"doc.json.bak","old state");
            ProjectVersions.Entry first=ProjectVersions.create(p,"First look");
            check("Saved version is listed",ProjectVersions.list(p).size()==1);
            check("Named version retains its label",ProjectVersions.list(p).get(0).name.equals("First look"));
            ProjectVersions.verify(first);check("Fresh version passes integrity verification",true);
            check("Snapshot stores exact document bytes",text(first.dir,"doc.json").equals(text(p,"doc.json")));
            check("Snapshot stores local source pixels",text(first.dir,"src_photo.png").equals("original pixels"));
            check("Snapshot stores mask pixels",text(first.dir,"mask_1.png").equals("original alpha"));
            check("Snapshot includes nested local assets",text(first.dir,"assets/shape.bin").equals("nested asset"));
            check("Snapshot excludes interrupted saves",!new File(first.dir,"doc.json.tmp").exists()&&!new File(first.dir,"doc.json.bak").exists());
            check("Snapshot never contains its own version bank",!new File(first.dir,".versions").exists());
            write(p,"doc.json","{\"v\":4,\"layers\":[]}");write(p,"src_photo.png","new pixels");new File(p,"mask_1.png").delete();
            check("Editing does not mutate saved source assets",text(first.dir,"src_photo.png").equals("original pixels"));
            check("Asset cleanup cannot remove an older saved mask",new File(first.dir,"mask_1.png").isFile());
            ProjectVersions.Entry second=ProjectVersions.create(p,"Other look");
            check("Second version does not copy previous history",!new File(second.dir,".versions").exists());
            check("Two versions have distinct directories",!first.dir.equals(second.dir)&&ProjectVersions.list(p).size()==2);
            ProjectVersions.rename(first,"Before crop");
            boolean renamed=false;for(ProjectVersions.Entry e:ProjectVersions.list(p))if(e.dir.equals(first.dir))renamed=e.name.equals("Before crop");
            check("Version rename survives a fresh listing",renamed);ProjectVersions.verify(first);
            File restored=ProjectVersions.restoreCopy(root,first);
            check("Restoring publishes a separate project",!restored.equals(p)&&restored.getParentFile().equals(root));
            check("Working project remains untouched",text(p,"doc.json").contains("\"layers\":[]"));
            check("Restored copy has original document",text(restored,"doc.json").equals(text(first.dir,"doc.json")));
            check("Restored copy has a removed mask",text(restored,"mask_1.png").equals("original alpha"));
            check("Restored copy excludes version-only metadata",!new File(restored,".version.properties").exists());
            File again=ProjectVersions.restoreCopy(root,first);check("Repeated restores never overwrite a project",!again.equals(restored)&&restored.exists());
            write(second.dir,"src_photo.png","corrupted bytes");
            reject("Damaged asset blocks restore",()->ProjectVersions.restoreCopy(root,second));
            check("Failed restore leaves the original edit intact",p.isDirectory()&&text(p,"src_photo.png").equals("new pixels"));
            new File(first.dir,"mask_1.png").delete();reject("Missing saved mask blocks restore",()->ProjectVersions.verify(first));
            write(first.dir,"mask_1.png","original alpha");
            File manifest=new File(first.dir,".version.properties");Properties props=new Properties();try(InputStream in=new FileInputStream(manifest)){props.load(in);}
            props.setProperty("sha256."+"../outside","bad");try(OutputStream out=new FileOutputStream(manifest)){props.store(out,"");}
            reject("Escaping manifest paths are rejected",()->ProjectVersions.verify(first));
            reject("Empty version names are rejected",()->ProjectVersions.create(p,"  "));
            File empty=new File(root,"pEmpty");empty.mkdir();reject("Missing document blocks version creation",()->ProjectVersions.create(empty,"Bad"));
            write(empty,"doc.json.tmp","pending");reject("Recovery-only project must be opened and saved first",()->ProjectVersions.create(empty,"Pending"));
            File broken=new File(root,"pBroken");broken.mkdir();write(broken,"doc.json","{}");write(broken,".versions","not a directory");
            reject("Version storage failure cannot report success",()->ProjectVersions.create(broken,"No space"));
            File stage=new File(new File(p,".versions"),".incomplete");stage.mkdirs();write(stage,"doc.json","{}");
            check("Interrupted unpublished versions do not appear",ProjectVersions.list(p).size()==2);
            File trashed=Projects.trash(root,p);check("Deleting moves the whole project to Trash",trashed!=null&&!p.exists());
            check("Saved versions follow the project into Trash",ProjectVersions.list(trashed).size()==2);
            check("Trash can be enumerated again after restart",Projects.trashCount(root)==1&&Projects.deletedAt(trashed)>0);
            File replacement=new File(root,"p1000");replacement.mkdir();write(replacement,"doc.json","new project");
            File trashed2=Projects.trash(root,replacement);check("Deleting the same folder identity never destroys older Trash",trashed2!=null&&!trashed2.equals(trashed)&&trashed.exists());
            check("Both deleted projects remain recoverable",Projects.trashCount(root)==2);
            check("Restore returns the original folder",Projects.restore(root,trashed)&&p.exists());
            check("Saved versions survive restoration",ProjectVersions.list(p).size()==2);
            check("Restore handles a live-folder collision",Projects.restore(root,trashed2)&&p.exists()&&Projects.trashCount(root)==0);
            check("Colliding restore preserves existing project bytes",text(p,"src_photo.png").equals("new pixels"));
            File tamper=Projects.trash(root,p);Properties bad=new Properties();bad.setProperty("original","../outside");
            try(OutputStream out=new FileOutputStream(new File(tamper,".trash.properties"))){bad.store(out,"");}
            check("Trash cannot restore outside Projects",!Projects.restore(root,tamper)&&tamper.exists());
            check("Explicit permanent removal succeeds",ProjectFiles.remove(tamper)&&!tamper.exists());
            File linked=new File(root,"pLinked");linked.mkdir();write(linked,"doc.json","{}");
            Path link=new File(linked,"escape").toPath();Files.createSymbolicLink(link,root.toPath());
            reject("Recovery copying refuses a recursive linked folder",()->ProjectVersions.create(linked,"Loop"));
            check("Failed copy leaves no visible partial version",ProjectVersions.list(linked).isEmpty());
            Files.delete(link);
            File v=new File(root,"v2000");v.mkdir();write(v,"video.json","{\"v\":14,\"uri\":\"content://external/source\"}");
            ProjectVersions.Entry video=ProjectVersions.create(v,"Rough cut");File videoCopy=ProjectVersions.restoreCopy(root,video);
            check("Video versions restore with the correct project kind",videoCopy.getName().startsWith("v")&&new File(videoCopy,"video.json").isFile());
            check("Video recovery preserves external media links exactly",text(videoCopy,"video.json").equals(text(v,"video.json")));
            Path external=Files.createTempDirectory("emulsion-external-");Path shortcut=new File(root,"shortcut").toPath();Files.createSymbolicLink(shortcut,external);
            check("Permanent removal deletes a link, not its target",ProjectFiles.remove(shortcut.toFile())&&Files.exists(external));Files.delete(external);
            System.out.println(count+" passed, 0 failed");
        }finally{ProjectFiles.remove(root);}
    }
}
