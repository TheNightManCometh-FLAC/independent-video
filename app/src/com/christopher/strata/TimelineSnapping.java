package com.christopher.strata;

import java.util.ArrayList;

/** Magnet targets exclude the dragged item itself; frame quantization remains active when magnets are off. */
final class TimelineSnapping {
    static long[] targets(VProject p,boolean enabled,boolean edges,boolean beats,int kind,int id,long playhead) {
        return targets(p,enabled,edges,beats,java.util.Collections.singleton(id),playhead);
    }
    static long[] targets(VProject p,boolean enabled,boolean edges,boolean beats,java.util.Collection<Integer> excluded,long playhead) {
        return targets(p,enabled,edges,beats,false,excluded,playhead);
    }
    /** 3.13: markers are a magnet target of their own (they never belong to the dragged item). */
    static long[] targets(VProject p,boolean enabled,boolean edges,boolean beats,boolean markers,java.util.Collection<Integer> excluded,long playhead) {
        if(!enabled)return new long[0];
        ArrayList<Long> points=new ArrayList<Long>();points.add(0L);points.add(playhead);
        if(edges){
            long[] starts=p.starts();
            for(int i=0;i<p.clips.size();i++){VProject.Clip c=p.clips.get(i);if(excluded.contains(c.id))continue;points.add(starts[i]);points.add(starts[i]+c.dur());}
            for(VProject.TextItem t:p.texts){if(excluded.contains(t.id))continue;points.add(t.start);points.add(t.start+t.dur);}
            for(VProject.AudioItem a:p.audio){if(excluded.contains(a.id))continue;points.add(a.start);points.add(a.start+a.dur());}
        }
        if(beats)for(long t:p.beatTimes())points.add(t);
        if(markers)for(VProject.Marker m:p.markers)points.add(m.t);
        long[] out=new long[points.size()];for(int i=0;i<out.length;i++)out[i]=points.get(i);return out;
    }
}
