package com.christopher.strata;

import java.util.*;
import org.json.JSONObject;

/** Actual group commands and timing locks, including indirect ripple, copied routing and persistence. */
public class BatchTest {
    static int count;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("ok   "+name);}
    interface Work { void run()throws Exception; }
    static void reject(String name,Work work)throws Exception{boolean bad=false;try{work.run();}catch(IllegalArgumentException e){bad=true;}check(name,bad);}
    static Set<Integer> ids(Integer... values){return new LinkedHashSet<Integer>(Arrays.asList(values));}
    static VProject.Clip clip(int id){VProject.Clip c=new VProject.Clip();c.id=id;c.kind=VProject.VIDEO;c.uri="content://clip"+id;c.name="Clip "+id;c.srcW=640;c.srcH=480;c.srcDur=20000000;c.in=0;c.out=4000000;c.hasAudio=true;return c;}
    static VProject project(){
        VProject p=new VProject();p.nextId=100;for(int i=1;i<=3;i++)p.clips.add(clip(i));
        VProject.TextItem t=new VProject.TextItem();t.id=10;t.layer=new Layer();t.layer.id=10;t.layer.type=Layer.IMAGE;t.media=clip(20);t.media.in=1000000;t.media.out=3000000;t.start=2000000;t.dur=2000000;t.fadeIn=300000;t.fadeOut=500000;t.fadeOffset=100000;t.fadeSpan=6000000;
        t.keys.set(1000000,new float[]{2,3,1,0,1},1);t.reactAudio=11;
        Layer effect=new Layer();effect.id=30;effect.type=Layer.EFFECT;Music.Link link=new Music.Link();link.audio=11;link.phaseUri="content://song";link.phaseUs=500000;link.amountKeys.set(1000000,new float[]{.5f},1);effect.links.add(link);t.media.grade.add(effect);p.texts.add(t);
        VProject.AudioItem a=new VProject.AudioItem();a.id=11;a.uri="content://song";a.name="Music";a.srcDur=10000000;a.start=3000000;a.in=500000;a.out=2500000;a.fadeIn=250000;a.vol.set(500000,new float[]{.25f},1);p.audio.add(a);
        p.syncAnchors();return p;
    }
    public static void main(String[] args)throws Exception{
        VProject p=project();Set<Integer> pair=ids(10,11);
        check("Selection resolves main, B-roll and audio kinds",TimelineBatch.kind(p,1)==TimelineView.SEL_CLIP&&TimelineBatch.kind(p,10)==TimelineView.SEL_MEDIA&&TimelineBatch.kind(p,11)==TimelineView.SEL_AUDIO);
        check("Select all includes only timeline items",TimelineBatch.all(p).size()==5&&!TimelineBatch.all(p).contains(20));
        check("Selection span includes duration and gaps",Arrays.equals(TimelineBatch.span(p,pair),new long[]{2000000,5000000}));
        TimelineBatch.Result moved=TimelineBatch.move(p,pair,2000000,new long[0],0);
        check("Group move preserves relative spacing",moved.project.textById(10).start==4000000&&moved.project.audioById(11).start==5000000);
        check("Move preserves B-roll source In/Out",moved.project.textById(10).media.in==1000000&&moved.project.textById(10).media.out==3000000);
        check("Move preserves audio source In/Out",moved.project.audioById(11).in==500000&&moved.project.audioById(11).out==2500000);
        check("Move preserves local motion keys",moved.project.textById(10).keys.list.get(0).t==1000000);
        check("Move preserves volume key timing",moved.project.audioById(11).vol.list.get(0).t==500000);
        check("Move preserves fade envelopes",moved.project.textById(10).fadeOffset==100000&&moved.project.textById(10).fadeSpan==6000000);
        check("Move preserves audio-control phase",moved.project.textById(10).media.grade.get(0).links.get(0).phaseUs==500000);
        check("Candidate construction leaves source project unchanged",p.textById(10).start==2000000&&p.audioById(11).start==3000000);
        VProject clamped=TimelineBatch.move(p,pair,-10000000,new long[0],0).project;
        check("Left boundary clamps the whole group",clamped.textById(10).start==0&&clamped.audioById(11).start==1000000);
        VProject snapped=TimelineBatch.move(p,pair,1980000,new long[]{4000000},50000).project;
        check("Group snaps without changing its spacing",snapped.textById(10).start==4000000&&snapped.audioById(11).start==5000000);
        reject("Absolute group movement rejects main footage",()->TimelineBatch.move(p,ids(1,10),1000000,new long[0],0));
        reject("Stale selection cannot partially edit",()->TimelineBatch.move(p,ids(10,999),1000000,new long[0],0));
        reject("Empty selection rejected",()->TimelineBatch.delete(p,ids()));
        reject("Overlong placement rejected atomically",()->TimelineBatch.move(p,pair,86400000000L,new long[0],0));
        p.connect(p.textById(10).link,2000000,true);
        VProject connected=TimelineBatch.move(p,pair,3000000,new long[0],0).project;
        check("Moved connected items re-pin to the new source frame",connected.textById(10).link.clip==2&&connected.anchorTime(connected.textById(10).link)==5000000);
        connected.syncAnchors();check("Repeated anchor synchronization cannot undo the move",connected.textById(10).start==5000000);
        TimelineBatch.Result copy=TimelineBatch.duplicate(p,pair);
        check("Duplicating a pair selects exactly the copies",copy.ids.size()==2&&!copy.ids.contains(10)&&!copy.ids.contains(11));
        VProject.TextItem newText=null;VProject.AudioItem newAudio=null;for(int id:copy.ids){if(copy.project.textById(id)!=null)newText=copy.project.textById(id);if(copy.project.audioById(id)!=null)newAudio=copy.project.audioById(id);}
        check("Copies are placed after the selected range",newText.start==5000000&&newAudio.start==6000000);
        check("Copied B-roll gets distinct item, layer and media IDs",newText.id==newText.layer.id&&newText.id!=10&&newText.media.id!=20&&newText.media.id!=newText.id);
        check("Copied reactions use the copied audio source",newText.reactAudio==newAudio.id);
        check("Copied effect links use the copied audio source",newText.media.grade.get(0).links.get(0).audio==newAudio.id);
        check("Copied amount keys are independent",newText.media.grade.get(0).links.get(0).amountKeys!=p.textById(10).media.grade.get(0).links.get(0).amountKeys);
        newText.keys.list.get(0).t=7;check("Copied motion keys cannot mutate originals",p.textById(10).keys.list.get(0).t==1000000);
        check("Copied anchors are aligned to their new placement",copy.project.anchorTime(newText.link)==newText.start);
        TimelineBatch.Result mixed=TimelineBatch.duplicate(p,ids(1,10,11));
        VProject.Clip copiedMain=mixed.project.clips.get(1);VProject.TextItem copiedB=null;VProject.AudioItem copiedA=null;
        for(int id:mixed.ids){if(mixed.project.textById(id)!=null)copiedB=mixed.project.textById(id);if(mixed.project.audioById(id)!=null)copiedA=mixed.project.audioById(id);}
        check("Main block copies are inserted after the originals",mixed.project.clips.size()==4&&mixed.project.clips.get(0).id==1&&copiedMain.id!=1&&mixed.project.clips.get(2).id==2);
        check("Mixed group keeps all copied time offsets",copiedB.start==mixed.project.start(1)+2000000&&copiedA.start==mixed.project.start(1)+3000000);
        check("Copied connection follows the copied main clip",copiedB.link.clip==copiedMain.id&&mixed.project.anchorTime(copiedB.link)==copiedB.start);
        check("Copied audio IDs are not assigned as the global beat guide",mixed.project.syncId==p.syncId);
        VProject ordered=project();ordered.textById(10).start=6000000;ordered.connect(ordered.textById(10).link,6000000,true);ordered.audioById(11).start=7000000;
        VProject earlier=TimelineBatch.reorder(ordered,ids(2,3,10,11),-1).project;
        check("Adjacent main clips reorder as a stable block",earlier.clips.get(0).id==2&&earlier.clips.get(1).id==3&&earlier.clips.get(2).id==1);
        check("Mixed graphics/audio follow the block offset",earlier.textById(10).start==2000000&&earlier.audioById(11).start==3000000);
        check("Relative source mapping survives reordering",earlier.textById(10).media.in==1000000&&earlier.textById(10).keys.list.get(0).t==1000000);
        reject("Nonadjacent main clips cannot silently collapse gaps",()->TimelineBatch.reorder(p,ids(1,3),1));
        reject("Nonadjacent duplication explains its limitation",()->TimelineBatch.duplicate(p,ids(1,3)));
        reject("Main block cannot move past the timeline edge",()->TimelineBatch.reorder(p,ids(1),-1));
        VProject deleted=TimelineBatch.delete(p,pair).project;
        check("Mixed delete removes every selected item",deleted.textById(10)==null&&deleted.audioById(11)==null&&deleted.clips.size()==3);
        check("Delete leaves original project available for undo",p.textById(10)!=null&&p.audioById(11)!=null);
        check("Deleting all main clips is an atomic candidate",TimelineBatch.delete(p,ids(1,2,3)).project.clips.isEmpty());
        VProject locks=project();TimelineLocks.set(locks,10,true);TimelineLocks.set(locks,11,true);TimelineLocks.set(locks,3,true);
        check("Timing locks have independent item state",TimelineLocks.locked(locks,10)&&TimelineLocks.locked(locks,11)&&TimelineLocks.locked(locks,3)&&!TimelineLocks.locked(locks,1));
        check("Lock state survives undo copies",locks.copy().textById(10).timingLocked&&locks.copy().audioById(11).timingLocked&&locks.copy().clipById(3).timingLocked);
        check("Locked projects use a downgrade-protected format",locks.formatVersion()==15&&ProjectIO.VIDEO_VERSION>=15);
        check("Unlocked projects retain the compatible older format",project().formatVersion()==14);
        reject("Locked selected items cannot move",()->TimelineBatch.move(locks,pair,1000000,new long[0],0));
        reject("Locked selected items cannot be deleted",()->TimelineBatch.delete(locks,pair));
        reject("Deleting upstream footage cannot ripple a locked clip",()->TimelineBatch.delete(locks,ids(1)));
        reject("Inserting copies cannot ripple a locked clip",()->TimelineBatch.duplicate(locks,ids(1)));
        check("Reordering equal-length unlocked clips can leave a lock untouched",TimelineBatch.reorder(locks,ids(1),1).project.start(2)==8000000);
        reject("Reordering cannot ripple a locked neighbor",()->TimelineBatch.reorder(locks,ids(2),1));
        VProject indirect=locks.copy();indirect.clipById(1).out+=1000000;indirect.syncAnchors();check("Changing an unlocked clip detects movement of a locked later clip",TimelineLocks.violation(locks,indirect)!=null);
        VProject picture=locks.copy();picture.clipById(3).scale=2;picture.clipById(3).opacity=.2f;picture.clipById(3).volume=.5f;picture.textById(10).layer.cx=400;picture.audioById(11).volume=.2f;
        check("Timing protection permits transforms, color and levels",TimelineLocks.violation(locks,picture)==null);
        VProject source=locks.copy();source.textById(10).media.in+=100000;check("Timing lock protects B-roll source range",TimelineLocks.violation(locks,source)!=null);
        source=locks.copy();source.clipById(3).speed=2;source.syncAnchors();check("Timing lock protects speed",TimelineLocks.violation(locks,source)!=null);
        source=locks.copy();source.clipById(3).uri="replacement";check("Timing lock protects source replacement",TimelineLocks.violation(locks,source)!=null);
        source=locks.copy();source.textById(10).dur+=100;check("Timing lock protects overlay duration",TimelineLocks.violation(locks,source)!=null);
        source=locks.copy();source.audioById(11).in+=100;check("Timing lock protects audio trim",TimelineLocks.violation(locks,source)!=null);
        VProject anchorLock=project();anchorLock.connect(anchorLock.textById(10).link,2000000,true);TimelineLocks.set(anchorLock,10,true);VProject detached=anchorLock.copy();detached.connect(detached.textById(10).link,2000000,false);
        check("Timing lock protects anchor relationships",TimelineLocks.violation(anchorLock,detached)!=null);
        VProject unlocked=locks.copy();TimelineLocks.set(unlocked,10,false);check("Explicit unlocking remains possible",TimelineLocks.violation(locks,unlocked)==null);
        TimelineBatch.Result copiesOfLocked=TimelineBatch.duplicate(locks,ids(10,11));
        boolean unlockedCopies=true;for(int id:copiesOfLocked.ids)unlockedCopies&=!TimelineLocks.locked(copiesOfLocked.project,id);
        check("Duplicate creates editable copies without unlocking originals",unlockedCopies&&TimelineLocks.locked(copiesOfLocked.project,10)&&TimelineLocks.locked(copiesOfLocked.project,11));
        VProject saved=VProject.fromJson(locks.toJson());check("Locks survive production JSON mapping",saved.clipById(3).timingLocked&&saved.textById(10).timingLocked&&saved.audioById(11).timingLocked);
        JSONObject old=project().toJson();old.put("v",14);VProject oldProject=VProject.fromJson(old);check("Older projects default to unlocked items",!TimelineLocks.anyLocked(oldProject));
        long[] targets=TimelineSnapping.targets(p,true,true,false,pair,1230000);
        check("Group magnets exclude every selected item",!contains(targets,2000000)&&!contains(targets,3000000)&&!contains(targets,5000000)&&contains(targets,8000000));
        VProject reactive=project();AudioFeatures features=new AudioFeatures();features.frames=2001;features.level=new float[features.frames];features.bass=new float[features.frames];Arrays.fill(features.level,.7f);Arrays.fill(features.bass,.7f);features.bands=new byte[features.frames*AudioFeatures.BANDS];features.wave=new byte[20*AudioFeatures.WAVE_RATE];reactive.controlFx.put("content://song",features);
        Layer grade=reactive.textById(10).media.grade.get(0);grade.type=Layer.BASIC;grade.links.clear();Music.Link drive=Music.link(Music.T_BASIC+2,Music.LEVEL,Music.FOLLOW,1,.02f,0,1);drive.audio=11;grade.links.add(drive);
        float beforeValue=evaluate(reactive,reactive.textById(10),3500000);
        check("Reactive fixture has a nonzero evaluated effect",beforeValue>.6f);
        VProject reactiveMoved=TimelineBatch.move(reactive,pair,2000000,new long[0],0).project;
        check("Audio-driven effect evaluates identically after group movement",Math.abs(evaluate(reactiveMoved,reactiveMoved.textById(10),5500000)-beforeValue)<1e-5);
        TimelineBatch.Result reactiveCopy=TimelineBatch.duplicate(reactive,pair);VProject.TextItem drivenCopy=null;for(int id:reactiveCopy.ids)if(reactiveCopy.project.textById(id)!=null)drivenCopy=reactiveCopy.project.textById(id);
        check("Audio-driven effect evaluates identically in the copied group",Math.abs(evaluate(reactiveCopy.project,drivenCopy,6500000)-beforeValue)<1e-5);
        drive.postFader=true;float post=evaluate(reactive,reactive.textById(10),3500000);VProject postMoved=TimelineBatch.move(reactive,pair,2000000,new long[0],0).project;
        check("Volume-driven automation remains aligned after group movement",post>0&&Math.abs(evaluate(postMoved,postMoved.textById(10),5500000)-post)<1e-5);
        VProject ramp=project();ramp.clipById(1).setCurve(SpeedCurve.preset(0));ramp.syncAnchors();VProject rampCopy=TimelineBatch.duplicate(ramp,ids(1)).project;VProject.Clip rampOriginal=ramp.clipById(1),rampDuplicate=rampCopy.clips.get(1);
        check("Copied ramp retains its exact playback length",rampDuplicate.dur()==rampOriginal.dur());
        check("Copied ramp maps the same source frame",rampDuplicate.srcAt(rampDuplicate.dur()/2)==rampOriginal.srcAt(rampOriginal.dur()/2));
        check("Copied ramp remains independently editable",rampDuplicate.curve!=rampOriginal.curve);
        System.out.println(count+" passed, 0 failed");
    }
    static boolean contains(long[] values,long value){for(long x:values)if(x==value)return true;return false;}
    static float evaluate(VProject p,VProject.TextItem t,long at){return Music.at(t.media.grade.get(0),p,at,t.media.id,t.start,t.dur).basic[2];}
}
