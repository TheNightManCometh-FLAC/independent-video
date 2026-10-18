package com.christopher.strata;

import java.util.Arrays;
import org.json.JSONObject;

/** Regression cases for the user's broken audio-effects and B-roll workflows. No Android UI runtime required. */
public class AutomationTest {
    static int pass;
    static void check(String name, boolean condition) { if(!condition)throw new AssertionError(name);pass++;System.out.println("ok   "+name); }
    static void near(String name,double actual,double expected,double tolerance){check(name+" ("+actual+")",Math.abs(actual-expected)<tolerance);}
    static VProject base(){VProject p=new VProject();VProject.Clip c=new VProject.Clip();c.id=1;c.uri="main";c.name="Main";c.hasAudio=true;c.srcW=1920;c.srcH=1080;c.out=c.srcDur=10000000;p.clips.add(c);p.nextId=20;return p;}
    static AudioFeatures features(float value){AudioFeatures f=new AudioFeatures();f.frames=2001;f.bass=new float[f.frames];f.level=new float[f.frames];Arrays.fill(f.bass,value);Arrays.fill(f.level,value);f.bands=new byte[f.frames*AudioFeatures.BANDS];f.wave=new byte[AudioFeatures.WAVE_RATE*20];return f;}
    static VProject.TextItem overlay(VProject p){VProject.TextItem t=new VProject.TextItem();t.id=2;t.media=new VProject.Clip();t.media.id=3;t.media.uri="broll";t.media.name="B-roll";t.media.kind=VProject.VIDEO;t.media.hasAudio=true;t.media.out=t.media.srcDur=12000000;t.media.in=3000000;t.media.speed=2;t.start=2000000;t.dur=3000000;t.layer=new Layer();t.layer.type=Layer.IMAGE;t.layer.srcW=1920;t.layer.srcH=1080;t.fadeIn=t.fadeOut=0;t.reactAudio=AudioControl.SELF;p.texts.add(t);return t;}
    static Music.Link expose(Layer l,int audio){l.type=Layer.BASIC;Music.Link k=Music.link(Music.T_BASIC+2,Music.LEVEL,Music.FOLLOW,1,.02f,0,1);k.audio=audio;l.links.add(k);return k;}
    static float eval(Layer l,VProject p,long time,int owner,long start,long dur){return Music.at(l,p,time,owner,start,dur).basic[2];}
    public static void main(String[] args)throws Exception {
        VProject p=base();p.controlFx.put("main",features(1));VProject.Clip c=p.clips.get(0);c.reactSrc=2;c.pulse=.3f;c.flash=.8f;
        near("Embedded main audio drives pulse without adding a song",p.clipXf(0,1000000)[0],1.3,1e-6);
        near("Embedded main audio drives flash without a beat guide",p.clipXf(0,1000000)[5],.8,1e-6);
        VProject.TextItem b=overlay(p);p.controlFx.put("broll",features(.2f));b.reactSrc=2;b.pulse=.5f;
        near("B-roll follows its OWN audio instead of main",p.textXf(b,3000000)[2],1.1,1e-6);
        near("B-roll source maps trim and speed",AudioControl.resolve(p,AudioControl.SELF,3,3000000).at(3000000),5000000,1);
        near("No reaction before B-roll source starts",AudioControl.drive(p,AudioControl.SELF,3,2,false,false,1000000),0,1e-6);
        near("No reaction after B-roll source ends",AudioControl.drive(p,AudioControl.SELF,3,2,false,false,6000000),0,1e-6);
        b.media.mute=true;near("Muted B-roll can be an explicitly pre-volume control",AudioControl.drive(p,AudioControl.SELF,3,2,false,false,3000000),.2,1e-6);
        near("Follow volume suppresses muted B-roll",AudioControl.drive(p,AudioControl.SELF,3,2,false,true,3000000),0,1e-6);
        b.reactAudio=AudioControl.OFF;near("Off disables B-roll reaction",p.textXf(b,3000000)[2],1,1e-6);
        Layer main=new Layer();Music.Link mk=expose(main,AudioControl.SELF);
        Layer over=new Layer();expose(over,AudioControl.SELF);
        near("Clip grade uses clip-owned source",eval(main,p,3000000,1,0,c.dur()),1,1e-6);
        near("B-roll grade uses B-roll-owned source",eval(over,p,3000000,3,b.start,b.dur),.2,1e-6);
        VProject.AudioItem a=new VProject.AudioItem();a.id=4;a.uri="song";a.name="Song";a.out=a.srcDur=10000000;p.audio.add(a);p.controlFx.put("song",features(.7f));p.syncId=4;
        near("Changing beat guide does not change clip-owned grade",eval(main,p,3000000,1,0,c.dur()),1,1e-6);
        mk.audio=4;near("An effect can select a named independent audio track",eval(main,p,3000000,1,0,c.dur()),.7,1e-6);
        a.volume=0;near("Pre-volume ignores playback volume explicitly",eval(main,p,3000000,1,0,c.dur()),.7,1e-6);
        mk.postFader=true;near("Post-volume follows a silent control track",eval(main,p,3000000,1,0,c.dur()),0,1e-6);
        mk.postFader=false;mk.enabled=false;near("Per-link bypass restores base value",eval(main,p,3000000,1,0,c.dur()),0,1e-6);mk.enabled=true;
        mk.audio=99;near("Deleted/missing source never silently falls back to main",eval(main,p,3000000,1,0,c.dur()),0,1e-6);
        mk.audio=AudioControl.SELF;mk.startUs=2000000;mk.endUs=4000000;mk.fadeIn=mk.fadeOut=.5f;
        near("Automation is inactive before its chosen range",eval(main,p,1500000,1,0,c.dur()),0,1e-6);
        near("Automation fades in at range boundary",eval(main,p,2250000,1,0,c.dur()),.5,1e-6);
        near("Automation reaches full strength inside range",eval(main,p,3000000,1,0,c.dur()),1,1e-6);
        near("Automation fades out at range boundary",eval(main,p,3750000,1,0,c.dur()),.5,1e-6);
        near("Automation returns to base after its range",eval(main,p,4000000,1,0,c.dur()),0,1e-6);
        mk.startUs=0;mk.endUs=-1;mk.fadeIn=mk.fadeOut=0;mk.amountKeys.set(0,new float[]{0},1);mk.amountKeys.set(2000000,new float[]{1},1);
        near("Amount keys interpolate during playback/scrub",eval(main,p,1000000,1,0,c.dur()),.5,1e-6);
        near("Amount keys hold the last value",eval(main,p,4000000,1,0,c.dur()),1,1e-6);
        Layer copy=main.copy();copy.links.get(0).amountKeys.list.get(0).v[0]=.9f;
        near("Undo copies cannot mutate live amount keys",mk.amountKeys.list.get(0).v[0],0,1e-6);
        Music.Link loaded=Music.Link.fromJson(mk.toJson());check("Link persistence keeps source and amount keys",loaded.audio==mk.audio&&loaded.amountKeys.size()==2&&loaded.local);
        JSONObject oldLink=new JSONObject();oldLink.put("t",12);oldLink.put("s",4);oldLink.put("a",1);
        loaded=Music.Link.fromJson(oldLink);check("Old project links retain legacy guide routing",loaded.audio==AudioControl.GUIDE&&!loaded.local&&loaded.enabled);
        // Beat-only evaluation must not wait for spectrum analysis.
        p=base();p.beatsUri="main";p.beats=new float[]{1,2,3};main=new Layer();mk=expose(main,AudioControl.SELF);mk.src=Music.BEAT;
        near("Beat-only grade works without spectral features",eval(main,p,1000000,1,0,10000000),1,1e-6);
        mk.mode=Music.STEP;mk.smooth=.02f;
        near("Local Step starts at target/range start",eval(main,p,2100000,1,2000000,3000000),1,1e-6);
        mk.local=false;near("Song-continuous Step remains available",eval(main,p,2100000,1,2000000,3000000),2,1e-6);
        mk.local=true;mk.mode=Music.DRIFT;mk.src=Music.LEVEL;p.controlFx.put("main",features(1));
        near("Local Drift resets at target start",eval(main,p,2500000,1,2000000,3000000),.5,1e-4);
        mk.local=false;near("Continuous Drift measures source time",eval(main,p,2500000,1,2000000,3000000),2.5,1e-4);
        // Explicit, interval-scoped B-roll audio choices.
        p=base();b=overlay(p);b.audioMode=0;b.media.mute=true;
        near("Keep main preserves main sound",p.mainSoundGain(3000000),1,1e-6);
        b.audioMode=1;b.media.mute=false;
        near("Use B-roll replaces main sound inside segment",p.mainSoundGain(3000000),0,1e-6);
        near("Main sound returns outside B-roll",p.mainSoundGain(6000000),1,1e-6);
        near("Replacement crossfades instead of cutting at start",p.mainSoundGain(b.start+2500),.5,1e-6);
        b.audioMode=2;near("Mix both leaves main sound enabled",p.mainSoundGain(3000000),1,1e-6);
        b.audioMode=1;b.layer.visible=false;near("Hidden B-roll does not suppress main audio",p.mainSoundGain(3000000),1,1e-6);b.layer.visible=true;
        VProject restored=VProject.fromJson(p.toJson());check("Project round trip keeps B-roll sound mode",restored.texts.get(0).audioMode==1);
        check("Project round trip keeps reaction audio routing",restored.texts.get(0).reactAudio==AudioControl.SELF);
        p.clips.get(0).grade.add(main);restored=VProject.fromJson(p.toJson());check("Project round trip keeps independent grade routing",restored.clips.get(0).grade.get(0).links.get(0).audio==AudioControl.SELF);
        check("New project format protects routing against downgrade",p.formatVersion()==14);
        // Real analysis must distinguish silence, modest dynamics, and rhythmic pulses.
        AudioFeatures silent=AudioFeatures.compute(new float[AudioFeatures.SR*5]);
        near("Digital silence has zero level",silent.levelAt(2000000,.1f),0,1e-6);
        near("Digital silence has zero bass",silent.bassAt(2000000,.1f),0,1e-6);
        float[] bands=new float[AudioFeatures.BANDS];silent.bandsAt(2000000,.1f,bands);float peak=0;for(float v:bands)peak=Math.max(peak,v);near("Digital silence has zero spectral bands",peak,0,1e-6);
        check("Silence cannot generate a beat grid",Beats.analyze(new float[Beats.SR*5]).beats.length==0);
        float[] tone=new float[AudioFeatures.SR*8];for(int i=0;i<tone.length;i++){double t=i/(double)AudioFeatures.SR;tone[i]=(float)(.2*Math.pow(10,Math.sin(2*Math.PI*2*t)/20)*Math.sin(2*Math.PI*80*t));}
        AudioFeatures f=AudioFeatures.compute(tone);Music music=new Music(f,null);peak=0;for(int i=200;i<600;i++)peak=Math.max(peak,music.follow(music.code(Music.LEVEL,true),i*10000L,.15f,0));check("Adaptive does not kill real audio with 2 dB dynamics",peak>.3f);
        float[] pulse=new float[AudioFeatures.SR*8];for(int i=0;i<pulse.length;i++){double t=i/(double)AudioFeatures.SR;double local=t%.5;pulse[i]=(float)(.6*Math.exp(-local*55)*Math.sin(2*Math.PI*80*t));}
        f=AudioFeatures.compute(pulse);Beats.Result beats=Beats.fromFeatures(f);check("Real 120 BPM kicks produce a usable beat grid",beats.beats.length>=10&&beats.bpm>100&&beats.bpm<140);
        check("Feature-derived silent beat analysis stays empty",Beats.fromFeatures(silent).beats.length==0);
        // New generic opacity mapping must use headroom rather than add onto a full value.
        Layer opacity=new Layer();opacity.type=Layer.EFFECT;Fx.defaults(opacity,Fx.HUESHIFT);Music.Target target=Music.target(opacity,Music.T_OPACITY);
        float defaultAmount=(target.max-target.min)*(Music.get(opacity,target.id)>(target.min+target.max)*.5f?-.15f:.15f);Music.add(opacity,target,defaultAmount);near("Default opacity mapping has visible headroom",opacity.opacity,.85,1e-6);
        p=base();b=overlay(p);over=new Layer();over.id=11;mk=expose(over,AudioControl.SELF);mk.startUs=500000;mk.amountKeys.set(250000,new float[]{.5f},1);b.media.grade.add(over);
        check("Move to all footage actually moves the selected effect",p.moveGradeToFootage(3,over,b.start,b.dur)&&b.media.grade.isEmpty()&&p.pgrade.get(0)==over);
        check("Moving scope retains B-roll as the explicit source",mk.audio==3);
        check("Moving scope preserves the automation's absolute time range",mk.startUs==2500000&&mk.endUs==5000000);
        check("Moving scope preserves keyframe timeline positions",mk.amountKeys.list.get(0).t==2250000);
        // Exercise the actual export mix() method with synthetic PCM, without MediaCodec.
        p=base();b=overlay(p);b.audioMode=1;b.media.mute=false;
        Exporter exporter=new Exporter(null,p,0,false,null);
        Class<?> segmentClass=Class.forName("com.christopher.strata.Exporter$Segment");
        java.lang.reflect.Constructor<?> ctor=segmentClass.getDeclaredConstructor();ctor.setAccessible(true);Object segment=ctor.newInstance();
        java.io.File pcm=java.io.File.createTempFile("emulsion-mix-test",".pcm");
        try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(pcm,"rw")){
            raf.setLength(10L*AudioDsp.RATE*4);
            for(int second:new int[]{3,6}){raf.seek((long)second*AudioDsp.RATE*4);for(int i=0;i<128;i++){raf.write(new byte[]{0,32,0,32});}}
            String[] names={"raf","frames","gain","main"};Object[] vals={raf,10L*AudioDsp.RATE,1f,true};
            for(int i=0;i<names.length;i++){java.lang.reflect.Field field=segmentClass.getDeclaredField(names[i]);field.setAccessible(true);field.set(segment,vals[i]);}
            java.lang.reflect.Field segments=Exporter.class.getDeclaredField("segments");segments.setAccessible(true);((java.util.List)segments.get(exporter)).add(segment);
            java.lang.reflect.Method mix=Exporter.class.getDeclaredMethod("mix",long.class,int.class);mix.setAccessible(true);
            short[] replaced=(short[])mix.invoke(exporter,3L*AudioDsp.RATE,128);int max=0;for(short v:replaced)max=Math.max(max,Math.abs(v));check("Actual export mixer silences main audio during replacement",max==0);
            short[] outside=(short[])mix.invoke(exporter,6L*AudioDsp.RATE,128);check("Actual export mixer restores main audio outside replacement",outside[10]>8100);
            b.audioMode=2;short[] mixed=(short[])mix.invoke(exporter,3L*AudioDsp.RATE,128);check("Actual export mixer leaves main audio in Mix both mode",mixed[10]>8100);
        }finally{pcm.delete();}
        java.io.File cache=java.io.File.createTempFile("emulsion-feature-test",".bin");
        try{silent.write(cache);AudioFeatures cached=AudioFeatures.read(cache);near("Fresh feature cache retains silence gating",cached.levelAt(2000000,.1f),0,1e-6);
            try(java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(cache))){out.writeInt(0x454d4658);out.writeInt(1);}
            boolean rejected=false;try{AudioFeatures.read(cache);}catch(java.io.IOException expected){rejected=true;}check("Old feature caches are invalidated after analysis fix",rejected);
        }finally{cache.delete();}
        System.out.println(pass+" passed, 0 failed");
    }
}
