package com.christopher.strata;

import java.util.Objects;

/** Timing protection is independent of color, transforms, sound levels and selection. */
final class TimelineLocks {
    static boolean locked(VProject p,int id) {
        VProject.Clip c=p.clipById(id);if(c!=null)return c.timingLocked;
        VProject.TextItem t=p.textById(id);if(t!=null)return t.timingLocked;
        VProject.AudioItem a=p.audioById(id);return a!=null&&a.timingLocked;
    }
    static boolean anyLocked(VProject p) {
        for(VProject.Clip c:p.clips)if(c.timingLocked)return true;
        for(VProject.TextItem t:p.texts)if(t.timingLocked)return true;
        for(VProject.AudioItem a:p.audio)if(a.timingLocked)return true;
        return false;
    }
    static void set(VProject p,int id,boolean locked) {
        VProject.Clip c=p.clipById(id);if(c!=null){c.timingLocked=locked;return;}
        VProject.TextItem t=p.textById(id);if(t!=null){t.timingLocked=locked;return;}
        VProject.AudioItem a=p.audioById(id);if(a!=null)a.timingLocked=locked;
    }
    private static boolean sameSource(VProject.Clip a,VProject.Clip b) {
        return a.kind==b.kind&&Objects.equals(a.uri,b.uri)&&a.in==b.in&&a.out==b.out&&a.speed==b.speed
                &&a.frameUs==b.frameUs&&a.curveIn==b.curveIn&&a.curveOut==b.curveOut
                &&(a.curve==null?b.curve==null:b.curve!=null&&a.curve.hash()==b.curve.hash());
    }
    private static boolean sameLink(VProject.Anchor a,VProject.Anchor b) {
        return a.on==b.on&&(!a.on||a.clip==b.clip&&a.src==b.src&&a.off==b.off);
    }
    /** Caller synchronizes anchors before checking. Unlocking is an explicit allowed edit. */
    static String violation(VProject before,VProject after) {
        long[] oldStarts=before.starts(),newStarts=after.starts();
        for(int i=0;i<before.clips.size();i++){
            VProject.Clip a=before.clips.get(i);if(!a.timingLocked)continue;
            VProject.Clip b=after.clipById(a.id);
            if(b==null)return message(a.name);
            if(b.timingLocked&&(!sameSource(a,b)||oldStarts[i]!=newStarts[after.clipIndex(b.id)]||a.trans!=b.trans||a.transDur!=b.transDur))return message(a.name);
        }
        for(VProject.TextItem a:before.texts)if(a.timingLocked){
            VProject.TextItem b=after.textById(a.id);
            if(b==null)return message(a.media!=null?a.media.name:"Title / graphic");
            if(b.timingLocked&&(a.start!=b.start||a.dur!=b.dur||!sameLink(a.link,b.link)
                    ||(a.media==null?b.media!=null:b.media==null||!sameSource(a.media,b.media))))return message(a.media!=null?a.media.name:"Title / graphic");
        }
        for(VProject.AudioItem a:before.audio)if(a.timingLocked){
            VProject.AudioItem b=after.audioById(a.id);
            if(b==null)return message(a.name);
            if(b.timingLocked&&(a.start!=b.start||a.in!=b.in||a.out!=b.out||!Objects.equals(a.uri,b.uri)||!sameLink(a.link,b.link)))return message(a.name);
        }
        return null;
    }
    private static String message(String name){return "Unlock timing on “"+(name==null?"this item":name)+"” first. This edit would move, retime or remove it.";}
    static void require(VProject before,VProject after) {
        String error=violation(before,after);if(error!=null)throw new IllegalArgumentException(error);
    }
}
