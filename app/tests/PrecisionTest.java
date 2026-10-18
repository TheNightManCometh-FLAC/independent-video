package com.christopher.strata;

import java.util.Arrays;

/** Production range validation and magnet target behavior, without Android widgets. */
public class PrecisionTest {
    static int count;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("ok   "+name);}
    static boolean has(long[] values,long value){for(long x:values)if(x==value)return true;return false;}
    static VProject.Clip clip(int id,long in,long out){VProject.Clip c=new VProject.Clip();c.id=id;c.kind=VProject.VIDEO;c.srcDur=20000000;c.in=in;c.out=out;return c;}
    static void reject(String name,VProject.Clip c,long in,long out){boolean bad=false;try{ClipRange.checked(c,in,out,30);}catch(IllegalArgumentException e){bad=true;}check(name,bad);}
    public static void main(String[] args){
        VProject.Clip c=clip(1,1000000,4000000);
        check("Trim start keeps the playhead frame",ClipRange.playheadBoundary(1000000,30,true)==1000000);
        check("Trim end keeps the playhead frame with exclusive Out",ClipRange.playheadBoundary(1000000,30,false)==1033333);
        long[] range=ClipRange.checked(c,2000000,5000000,30);
        check("Exact marks preserve exclusive Out",range[0]==2000000&&range[1]==5000000&&range[2]==3000000);
        check("Validation leaves original marks unchanged",c.in==1000000&&c.out==4000000);
        c.speed=2;range=ClipRange.checked(c,2000000,5000000,30);check("Duration preview reflects speed",range[2]==1500000);
        reject("Fast cut must retain one output frame",c,0,33333);
        reject("Reversed marks rejected",c,5000000,4000000);
        reject("Negative source In rejected",c,-1,1000000);
        reject("Source Out beyond file rejected",c,0,20033334);
        reject("Zero-length range rejected",c,1000000,1000000);
        c.speed=1;range=ClipRange.checked(c,0,20000001,30);check("Rounded endpoint fits actual source duration",range[1]==20000000);
        for(int fps:new int[]{24,25,30,60}){
            long one=Timeline.frameTime(1,fps);check("One project frame valid at "+fps,ClipRange.checked(c,0,one,fps)[2]==one);
            check("Frame time input round-trips at "+fps,EditTime.parse(Timeline.timecode(12345678,fps),fps)==Timeline.quant(12345678,fps));
        }
        VProject p=new VProject();p.clips.add(clip(1,0,4000000));p.clips.add(clip(2,0,6000000));
        VProject.TextItem t=new VProject.TextItem();t.id=3;t.start=2000000;t.dur=3000000;p.texts.add(t);
        VProject.AudioItem a=new VProject.AudioItem();a.id=4;a.start=7000000;a.in=0;a.out=2000000;a.srcDur=2000000;p.audio.add(a);
        long[] all=TimelineSnapping.targets(p,true,true,false,TimelineView.SEL_TEXT,3,1230000);
        check("Magnets include zero and playhead",has(all,0)&&has(all,1230000));
        check("Magnet targets include other clip edges",has(all,4000000)&&has(all,10000000));
        check("Magnet targets include other audio edges",has(all,7000000)&&has(all,9000000));
        check("Selected overlay does not snap to itself",!has(all,2000000)&&!has(all,5000000));
        long[] off=TimelineSnapping.targets(p,false,true,true,TimelineView.SEL_TEXT,3,1230000);
        check("Snap off has no magnetic targets",off.length==0);
        check("Snap off keeps arbitrary frame-aligned moves",Timeline.snap(1990000,off,50000)==1990000);
        long[] noEdges=TimelineSnapping.targets(p,true,false,false,TimelineView.SEL_NONE,-1,1230000);
        check("Edge targets can be disabled independently",noEdges.length==2&&!has(noEdges,4000000));
        long[] selectedAudio=TimelineSnapping.targets(p,true,true,false,TimelineView.SEL_AUDIO,4,1230000);
        check("Selected audio is excluded",!has(selectedAudio,7000000)&&!has(selectedAudio,9000000));
        p.syncId=4;p.beats=new float[]{.5f,1f};p.beatsUri=a.uri;a.uri="content://song";p.beatsUri=a.uri;
        long[] beats=TimelineSnapping.targets(p,true,false,true,TimelineView.SEL_NONE,-1,0);
        check("Enabled beat guide supplies music magnets",has(beats,7500000)&&has(beats,8000000));
        check("Beat magnets can be disabled",!has(TimelineSnapping.targets(p,true,false,false,TimelineView.SEL_NONE,-1,0),7500000));
        long[] before=p.snapTargets();TimelineSnapping.targets(p,true,true,true,TimelineView.SEL_CLIP,1,0);check("Building magnets never mutates the project",Arrays.equals(before,p.snapTargets()));
        System.out.println(count+" passed, 0 failed");
    }
}
