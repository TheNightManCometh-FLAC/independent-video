package com.christopher.strata;

/** Source Out is exclusive. Validation never mutates the clip or creates an undo step. */
final class ClipRange {
    static long playheadBoundary(long time,int fps,boolean head) {
        return Timeline.frameTime(Timeline.frameIndex(time,fps)+(head?0:1),fps);
    }
    static long[] checked(VProject.Clip clip,long in,long out,int fps) {
        long frame=Timeline.frameTime(1,fps);
        if(out>clip.srcDur&&out-clip.srcDur<=frame/2)out=clip.srcDur;
        if(in<0||out<=in||out>clip.srcDur||out-in<frame)throw new IllegalArgumentException("Choose at least one frame inside this source file.");
        VProject.Clip trial=clip.copy();trial.in=in;trial.out=out;
        if(trial.dur()<frame-1)throw new IllegalArgumentException("Leave at least one output frame at this clip's speed.");
        return new long[]{in,out,trial.dur()};
    }
}
