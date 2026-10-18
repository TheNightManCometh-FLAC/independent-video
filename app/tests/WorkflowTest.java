package com.christopher.strata;

import java.util.Arrays;

/** Actual editing/evaluation/persistence regressions; no copy of the app's editing algorithms. */
public class WorkflowTest {
    static int passed;
    static void check(String name, boolean ok) { if(!ok)throw new AssertionError(name); passed++; System.out.println("ok   "+name); }
    static void near(String name, double got, double expected) { check(name+" ("+got+")",Math.abs(got-expected)<0.0001); }
    static VProject project() {
        VProject p = new VProject(); p.nextId=10;
        VProject.Clip c = new VProject.Clip();c.id=1;c.uri="main";c.name="Main";c.srcDur=c.out=12000000;c.hasAudio=true;c.srcW=1920;c.srcH=1080;
        p.clips.add(c);
        AudioFeatures f=new AudioFeatures();f.frames=3001;f.level=new float[f.frames];f.bass=new float[f.frames];Arrays.fill(f.level,1);Arrays.fill(f.bass,1);f.bands=new byte[f.frames*AudioFeatures.BANDS];
        p.controlFx.put("main",f);p.controlFx.put("broll",f);
        Beats.Result beats=new Beats.Result();beats.beats=new float[]{0,1,2,3,4,5,6,7,8,9,10,11};p.controlBeats.put("main",beats);p.controlBeats.put("broll",beats);
        return p;
    }
    static VProject.TextItem overlay(VProject p) {
        VProject.TextItem t=new VProject.TextItem();t.id=2;t.start=2000000;t.dur=4000000;t.fadeIn=300000;t.fadeOut=300000;t.media=p.clips.get(0).copy();t.media.id=3;t.media.uri="broll";t.media.in=1000000;t.media.speed=2;t.layer=new Layer();t.layer.id=2;t.layer.type=Layer.IMAGE;t.audioMode=1;t.media.mute=false;
        t.keys.set(0,new float[]{10,20,1,0,1},1);t.keys.set(4000000,new float[]{50,60,2,40,.5f},1);
        VProject.syncOverlayOut(t);p.texts.add(t);p.syncAnchors();p.connect(t.link,t.start,true);return t;
    }
    static Layer grade(VProject.Clip c, int mode) {
        Layer l=new Layer();l.id=40;l.type=Layer.BASIC;
        Music.Link k=Music.link(Music.T_BASIC+2,mode==Music.STEP?Music.BEAT:Music.LEVEL,mode,.2f,.02f,0,1);k.audio=AudioControl.SELF;k.local=true;l.links.add(k);c.grade.add(l);return l;
    }
    static float eval(VProject p, Layer l,int owner,long start,long dur,long t) {return Music.at(l,p,t,owner,start,dur).basic[2];}
    public static void main(String[] args)throws Exception {
        VProject p=project();VProject.TextItem a=overlay(p);Layer l=grade(a.media,Music.DRIFT);
        float before=eval(p,l,3,a.start,a.dur,5500000);float[] xf=p.textXf(a,5500000);long mainDuration=p.duration();long src=VProject.overlaySrc(a,5500000);
        VProject saved=p.copy();VProject.TextItem b=Edits.splitOverlay(p,a,4000000);p.syncAnchors();
        check("B-roll splits into two independently selectable items",b!=null&&p.texts.size()==2&&a.id!=b.id&&a.media.id!=b.media.id&&a.layer.id!=b.layer.id);
        check("B-roll split leaves main footage and project duration alone",p.clips.size()==1&&p.duration()==mainDuration);
        check("B-roll cut has no timeline gap or overlap",a.start+a.dur==b.start&&b.start+b.dur==6000000);
        check("2x B-roll cut maps to exact source footage",a.media.out==5000000&&b.media.in==5000000&&b.media.out==9000000);
        check("Export audio source out matches both cut halves",a.media.out-a.media.in==a.dur*2&&b.media.out-b.media.in==b.dur*2);
        check("Source frame after cut is unchanged",VProject.overlaySrc(b,5500000)==src);
        near("B-roll Drift continues through the cut",eval(p,b.media.grade.get(0),b.media.id,b.start,b.dur,5500000),before);
        float[] after=p.textXf(b,5500000);for(int i=0;i<xf.length;i++)near("B-roll transform channel "+i+" stays on footage",after[i],xf[i]);
        check("Cut adds no second entrance or premature exit fade",a.fadeSpan==4000000&&b.fadeOffset==2000000&&a.fadeIn==300000&&b.fadeOut==300000);
        check("Connected second half stays at cut after anchor refresh",b.start==4000000&&b.link.on);
        b.media.grade.get(0).links.get(0).amount=9;near("Split grade copies are independent",a.media.grade.get(0).links.get(0).amount,.2);
        check("Undo snapshot retains original unsplit B-roll",saved.texts.size()==1&&saved.texts.get(0).dur==4000000);
        check("A B-roll edge refuses a zero-length split",Edits.splitOverlay(p,a,a.start)==null&&p.texts.size()==2);
        // Reproduce reset of Step/Drift, amount keys and fades by main-track split.
        for(int mode:new int[]{Music.FOLLOW,Music.STEP,Music.DRIFT}) {
            p=project();VProject.Clip c=p.clips.get(0);l=grade(c,mode);Music.Link k=l.links.get(0);k.startUs=500000;k.fadeIn=1;k.fadeOut=2;
            k.amountKeys.set(0,new float[]{.1f},1);k.amountKeys.set(8000000,new float[]{.4f},1);
            float expected=eval(p,l,c.id,0,c.dur(),6500000);
            float atCut=eval(p,l,c.id,0,c.dur(),2999999);
            VProject.Clip right=Edits.split(p,0,3000000);
            near("Main mode "+mode+" keeps automation phase and keys",eval(p,right.grade.get(0),right.id,p.start(1),right.dur(),6500000),expected);
            near("Main mode "+mode+" has no fade-out introduced at cut",eval(p,c.grade.get(0),c.id,0,c.dur(),2999999),atCut);
            VProject restored=VProject.fromJson(p.toJson());ProjectIO.sanitize(restored,new ProjectIO.Loaded());restored.controlFx=p.controlFx;restored.controlBeats=p.controlBeats;
            VProject.Clip rc=restored.clips.get(1);
            near("Save/open mode "+mode+" preserves edited automation",eval(restored,rc.grade.get(0),rc.id,restored.start(1),rc.dur(),6500000),expected);
            check("Edited automation retains negative local start",rc.grade.get(0).links.get(0).startUs==-2500000);
        }
        p=project();a=overlay(p);l=grade(a.media,Music.STEP);float expected=eval(p,l,a.media.id,a.start,a.dur,5500000);
        Edits.range(p,a.media,a,3000000,9000000);p.syncAnchors();
        check("Trimmed B-roll head moves start while tail stays",a.start==3000000&&a.start+a.dur==6000000);
        near("B-roll head trim retains Step phase",eval(p,a.media.grade.get(0),a.media.id,a.start,a.dur,5500000),expected);
        p=project();VProject.Clip c=p.clips.get(0);l=grade(c,Music.DRIFT);expected=eval(p,l,c.id,0,c.dur(),7000000);Edits.range(p,c,null,2000000,10000000);
        near("Main head trim retains drift on the same source frame",eval(p,c.grade.get(0),c.id,0,c.dur(),5000000),expected);
        // An explicit range already removed by trimming must stay inactive on reload.
        p=project();c=p.clips.get(0);l=grade(c,Music.FOLLOW);l.links.get(0).endUs=1000000;Edits.range(p,c,null,3000000,12000000);
        near("Fully trimmed automation range stays inactive",eval(p,c.grade.get(0),c.id,0,c.dur(),1000000),0);
        check("Removed range does not become the whole target on save",Music.Link.fromJson(c.grade.get(0).links.get(0).toJson()).endUs==0);
        // Cutting inside a fade keeps the original envelope, including the 5ms sound de-click.
        p=project();a=overlay(p);a.fadeIn=3000000;a.fadeOut=2500000;
        long[] times={2250000,2999999,3000000,3002500,4500000,5999999};
        float[] fades=new float[times.length],soundFades=new float[times.length];
        for(int i=0;i<times.length;i++){fades[i]=VProject.itemFade(a,times[i]);soundFades[i]=VProject.overlaySoundFade(a,times[i]);}
        b=Edits.splitOverlay(p,a,3000000);
        for(int i=0;i<times.length;i++){VProject.TextItem active=times[i]<b.start?a:b;near("Picture fade through cut "+i,VProject.itemFade(active,times[i]),fades[i]);near("Sound fade through cut "+i,VProject.overlaySoundFade(active,times[i]),soundFades[i]);}
        VProject fadeReload=VProject.fromJson(p.toJson());ProjectIO.sanitize(fadeReload,new ProjectIO.Loaded());
        near("Load sanitation preserves long fade on a short cut",VProject.itemFade(fadeReload.texts.get(1),4500000),fades[4]);
        // Exercise the actual export mixer on the first sample of the right B-roll half.
        b=fadeReload.texts.get(1);b.media.volume=1;
        Exporter exporter=new Exporter(null,fadeReload,0,false,null);
        Class<?> segmentType=Class.forName("com.christopher.strata.Exporter$Segment");
        java.lang.reflect.Constructor<?> constructor=segmentType.getDeclaredConstructor();constructor.setAccessible(true);Object segment=constructor.newInstance();
        java.io.File pcm=java.io.File.createTempFile("emulsion-cut-fade",".pcm");
        try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(pcm,"rw")){
            for(int i=0;i<128;i++)raf.write(new byte[]{0,32,0,32});
            String[] names={"raf","frames","startFrame","gain","overlay","fadeIn"};
            Object[] values={raf,3L*AudioDsp.RATE,3L*AudioDsp.RATE,1f,b,3L*AudioDsp.RATE};
            for(int i=0;i<names.length;i++){java.lang.reflect.Field field=segmentType.getDeclaredField(names[i]);field.setAccessible(true);field.set(segment,values[i]);}
            java.lang.reflect.Field segments=Exporter.class.getDeclaredField("segments");segments.setAccessible(true);((java.util.List)segments.get(exporter)).add(segment);
            java.lang.reflect.Method mix=Exporter.class.getDeclaredMethod("mix",long.class,int.class);mix.setAccessible(true);
            short[] mixed=(short[])mix.invoke(exporter,3L*AudioDsp.RATE,128);
            check("Actual export mixer does not restart fade or de-click at B-roll cut",mixed[0]>2700&&mixed[0]<2750);
        }finally{pcm.delete();}
        // One frame at 30fps rounds down to 33333us; it is still a valid first segment.
        p=project();a=overlay(p);check("One-frame overlay cut works at 30fps",Edits.splitOverlay(p,a,a.start+Timeline.frameTime(1,30))!=null);
        // Audio splits keep sound offsets and key timing, with no ripple into main video.
        p=project();VProject.AudioItem au=new VProject.AudioItem();au.id=4;au.uri="song";au.in=1000000;au.out=7000000;au.srcDur=10000000;au.start=2000000;au.vol.set(0,new float[]{1},1);au.vol.set(6000000,new float[]{.2f},1);p.audio.add(au);
        float gain=VProject.musicVol(au,6500000);VProject.AudioItem ar=Edits.splitAudio(p,au,4000000);
        check("Audio split preserves source range",au.out==ar.in&&ar.in==3000000&&ar.out==7000000);
        near("Audio volume keys stay on the same samples",VProject.musicVol(ar,6500000),gain);
        // The previous Key amount button used capture() on empty keys, a silent no-op.
        Music.Link key=new Music.Link();key.amount=.3f;key.amountKeys.set(1000000,new float[]{Music.amountAt(key,1000000)},16666);
        check("First amount key is actually created",key.amountKeys.size()==1);key.amountKeys.set(3000000,new float[]{.9f},16666);near("Amount key interpolation",Music.amountAt(key,2000000),.6);
        near("Decimal seconds input",EditTime.parse("1.5",30),1500000);
        near("Minute/seconds input",EditTime.parse("2:03.5",30),123500000);
        near("Frame timecode input",EditTime.parse("1:02:15",30),62500000);
        for(String invalid:new String[]{"-1","NaN","Infinity","1:60","0:00:30","abc",""}){boolean rejected=false;try{EditTime.parse(invalid,30);}catch(IllegalArgumentException e){rejected=true;}check("Reject invalid time "+invalid,rejected);}
        for(int fps:new int[]{24,25,30,60})for(long f:new long[]{0,1,2,123,9001})check("Frame-exact timecode round trip "+fps+"/"+f,EditTime.parse(Timeline.timecode(Timeline.frameTime(f,fps),fps),fps)==Timeline.frameTime(f,fps));
        System.out.println(passed+" passed, 0 failed");
    }
}
