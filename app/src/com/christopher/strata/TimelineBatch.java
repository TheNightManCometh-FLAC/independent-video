package com.christopher.strata;

import java.util.*;

/** Atomic group commands: candidates are built and checked before the editor creates undo history. */
final class TimelineBatch {
    static final class Result {
        final VProject project;final LinkedHashSet<Integer> ids;
        Result(VProject p,Collection<Integer> selected){project=p;ids=new LinkedHashSet<Integer>(selected);}
    }
    static int kind(VProject p,int id) {
        if(p.clipById(id)!=null)return TimelineView.SEL_CLIP;
        VProject.TextItem t=p.textById(id);if(t!=null)return t.media!=null?TimelineView.SEL_MEDIA:t.viz!=null?TimelineView.SEL_VIZ:TimelineView.SEL_TEXT;
        return p.audioById(id)!=null?TimelineView.SEL_AUDIO:TimelineView.SEL_NONE;
    }
    static LinkedHashSet<Integer> all(VProject p) {
        LinkedHashSet<Integer> out=new LinkedHashSet<Integer>();for(VProject.Clip c:p.clips)out.add(c.id);for(VProject.TextItem t:p.texts)out.add(t.id);for(VProject.AudioItem a:p.audio)out.add(a.id);return out;
    }
    static void validate(VProject p,Collection<Integer> ids) {
        if(ids.isEmpty())throw new IllegalArgumentException("Select one or more timeline items.");
        for(int id:ids)if(kind(p,id)==TimelineView.SEL_NONE)throw new IllegalArgumentException("The selection changed. Select the items again.");
    }
    static boolean hasMain(VProject p,Collection<Integer> ids){for(int id:ids)if(p.clipById(id)!=null)return true;return false;}
    static long[] span(VProject p,Collection<Integer> ids) {
        validate(p,ids);long start=Long.MAX_VALUE,end=0;long[] starts=p.starts();
        for(int id:ids){long s,d;VProject.Clip c=p.clipById(id);VProject.TextItem t=p.textById(id);VProject.AudioItem a=p.audioById(id);
            if(c!=null){s=starts[p.clipIndex(id)];d=c.dur();}else if(t!=null){s=t.start;d=t.dur;}else{s=a.start;d=a.dur();}
            start=Math.min(start,s);end=Math.max(end,s+d);
        }return new long[]{start,end};
    }
    private static void place(VProject p,int id,long start) {
        if(start<0||start>86400000000L)throw new IllegalArgumentException("Keep the selection inside the first 24 hours.");
        VProject.TextItem t=p.textById(id);if(t!=null){t.start=start;if(t.link.on)p.pinAt(t.link,start);return;}
        VProject.AudioItem a=p.audioById(id);if(a!=null){a.start=start;if(a.link.on)p.pinAt(a.link,start);}
    }
    static Result move(VProject before,Collection<Integer> ids,long delta,long[] snaps,long tolerance) {
        validate(before,ids);
        if(hasMain(before,ids))throw new IllegalArgumentException("Main footage uses the magnetic track. Use Move earlier / later for a contiguous clip selection.");
        long[] bounds=span(before,ids);long desired=VideoEditor.moveTo(bounds[0],bounds[1]-bounds[0],delta,snaps,tolerance,before.fps());
        long shift=Math.max(-bounds[0],desired-bounds[0]);
        VProject p=before.copy();
        for(int id:ids){VProject.TextItem t=before.textById(id);VProject.AudioItem a=before.audioById(id);place(p,id,(t!=null?t.start:a.start)+shift);}
        p.syncAnchors();TimelineLocks.require(before,p);return new Result(p,ids);
    }
    private static int[] mainBlock(VProject p,Collection<Integer> ids) {
        int first=-1,last=-1,count=0;
        for(int i=0;i<p.clips.size();i++)if(ids.contains(p.clips.get(i).id)){if(first<0)first=i;last=i;count++;}
        if(count>0&&last-first+1!=count)throw new IllegalArgumentException("Select adjacent main clips to keep their order and spacing together.");
        return new int[]{first,last};
    }
    static Result reorder(VProject before,Collection<Integer> ids,int direction) {
        validate(before,ids);int[] block=mainBlock(before,ids);int first=block[0],last=block[1];
        if(first<0)throw new IllegalArgumentException("Select main footage to reorder.");
        if(direction!=-1&&direction!=1)throw new IllegalArgumentException("Choose earlier or later.");
        if(first+direction<0||last+direction>=before.clips.size())throw new IllegalArgumentException("The selection is already at this end of the main track.");
        VProject p=before.copy();ArrayList<VProject.Clip> clips=new ArrayList<VProject.Clip>(p.clips.subList(first,last+1));
        p.clips.subList(first,last+1).clear();p.clips.addAll(first+direction,clips);p.syncAnchors();
        long shift=p.start(first+direction)-before.start(first);
        for(int id:ids)if(before.clipById(id)==null){VProject.TextItem t=before.textById(id);place(p,id,(t!=null?t.start:before.audioById(id).start)+shift);}
        p.syncAnchors();TimelineLocks.require(before,p);return new Result(p,ids);
    }
    static Result delete(VProject before,Collection<Integer> ids) {
        validate(before,ids);VProject p=before.copy();
        for(Iterator<VProject.Clip> it=p.clips.iterator();it.hasNext();)if(ids.contains(it.next().id))it.remove();
        for(Iterator<VProject.TextItem> it=p.texts.iterator();it.hasNext();)if(ids.contains(it.next().id))it.remove();
        for(Iterator<VProject.AudioItem> it=p.audio.iterator();it.hasNext();)if(ids.contains(it.next().id))it.remove();
        p.syncAnchors();p.validateSync();TimelineLocks.require(before,p);return new Result(p,Collections.<Integer>emptySet());
    }
    static Result duplicate(VProject before,Collection<Integer> ids) {
        validate(before,ids);int[] block=mainBlock(before,ids);VProject p=before.copy();Map<Integer,Integer> map=new HashMap<Integer,Integer>();
        LinkedHashSet<Integer> copies=new LinkedHashSet<Integer>();ArrayList<VProject.Clip> newClips=new ArrayList<VProject.Clip>();
        if(block[0]>=0)for(int i=block[0];i<=block[1];i++){VProject.Clip old=before.clips.get(i),c=old.copy();c.id=p.nextId++;c.timingLocked=false;map.put(old.id,c.id);copies.add(c.id);newClips.add(c);}
        long shift;
        if(block[0]>=0){p.clips.addAll(block[1]+1,newClips);p.syncAnchors();shift=p.start(block[1]+1)-before.start(block[0]);}
        else{long[] bounds=span(before,ids);shift=Timeline.quant(bounds[1]-bounds[0],before.fps());}
        ArrayList<VProject.TextItem> newTexts=new ArrayList<VProject.TextItem>();
        for(VProject.TextItem old:before.texts)if(ids.contains(old.id)){
            VProject.TextItem t=old.copy();t.id=p.nextId++;t.layer.id=t.id;t.timingLocked=false;map.put(old.id,t.id);copies.add(t.id);
            if(t.media!=null){int oldId=t.media.id;t.media.id=p.nextId++;t.media.timingLocked=false;map.put(oldId,t.media.id);}
            t.start=old.start+shift;p.texts.add(t);newTexts.add(t);
        }
        ArrayList<VProject.AudioItem> newAudio=new ArrayList<VProject.AudioItem>();
        for(VProject.AudioItem old:before.audio)if(ids.contains(old.id)){VProject.AudioItem a=old.copy();a.id=p.nextId++;a.timingLocked=false;map.put(old.id,a.id);copies.add(a.id);a.start=old.start+shift;p.audio.add(a);newAudio.add(a);}
        for(VProject.Clip c:newClips)remap(c,map);
        for(VProject.TextItem t:newTexts){t.reactAudio=ref(t.reactAudio,map);if(t.media!=null)remap(t.media,map);anchor(p,t.link,t.start,map);}
        for(VProject.AudioItem a:newAudio)anchor(p,a.link,a.start,map);
        p.syncAnchors();TimelineLocks.require(before,p);return new Result(p,copies);
    }
    private static int ref(int id,Map<Integer,Integer> map){Integer copy=map.get(id);return copy==null?id:copy;}
    private static void remap(VProject.Clip c,Map<Integer,Integer> map){c.reactAudio=ref(c.reactAudio,map);for(Layer l:c.grade)for(Music.Link link:l.links)link.audio=ref(link.audio,map);}
    private static void anchor(VProject p,VProject.Anchor link,long start,Map<Integer,Integer> map){if(!link.on)return;if(map.containsKey(link.clip))link.clip=map.get(link.clip);else p.pinAt(link,start);}
}
