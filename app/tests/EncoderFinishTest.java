package com.christopher.strata;
import android.media.MediaCodec;
import android.media.MediaMuxer;
import java.lang.reflect.*;
import java.util.Arrays;

/** Calls the actual Exporter methods with a scripted MediaCodec/Muxer boundary. */
public final class EncoderFinishTest {
    static int pass;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);pass++;System.out.println("ok   "+name);}
    static void set(Exporter e,String name,Object value)throws Exception{Field f=Exporter.class.getDeclaredField(name);f.setAccessible(true);f.set(e,value);}
    static void drain(Exporter e,String method,boolean end)throws Exception{invoke(e,method,new Class[]{boolean.class},end);}
    static void invoke(Exporter e,String name,Class[] types,Object...args)throws Exception{Method m=Exporter.class.getDeclaredMethod(name,types);m.setAccessible(true);try{m.invoke(e,args);}catch(InvocationTargetException err){Throwable c=err.getCause();if(c instanceof Exception)throw (Exception)c;throw err;}}
    static Exporter exporter(MediaCodec audio,MediaCodec video)throws Exception{Exporter e=new Exporter(null,new VProject(),0,false,null);set(e,"aenc",audio);set(e,"venc",video);set(e,"muxer",new MediaMuxer());return e;}
    public static void main(String[] args)throws Exception{
        MediaCodec a=new MediaCodec().event(0,0,4);
        MediaCodec v=new MediaCodec().event(-1,0,0).event(0,0,4);
        Exporter e=exporter(a,v);set(e,"wantAudio",true);
        drain(e,"drainVideo",true);int polls=a.outputPolls;
        drain(e,"drainAudio",true);
        check("Audio EOS consumed while video finishes is remembered",a.outputPolls==polls);
        drain(e,"drainAudio",false);drain(e,"drainAudio",true);
        check("Repeated audio drains never ask for a second EOS",a.outputPolls==polls);
        check("The audio EOS output buffer was released exactly once",a.released==1);

        a=new MediaCodec().event(-1,0,0).event(-1,0,0).event(0,0,4);e=exporter(a,new MediaCodec());
        drain(e,"drainAudio",true);check("Final drain waits for an EOS that really has not arrived",a.outputPolls==3);
        drain(e,"drainAudio",true);check("EOS first seen in final drain is also sticky",a.outputPolls==3);

        a=new MediaCodec().event(-1,0,0).event(0,0,4);e=exporter(a,new MediaCodec());
        drain(e,"drainAudio",false);check("Nonblocking empty poll does not falsely mark audio finished",a.outputPolls==1);
        drain(e,"drainAudio",true);check("An initially empty poll can later reach EOS",a.outputPolls==2);

        v=new MediaCodec().event(0,0,4);e=exporter(new MediaCodec(),v);drain(e,"drainVideo",false);polls=v.outputPolls;drain(e,"drainVideo",true);
        check("Video EOS is remembered across nonblocking and final drains",v.outputPolls==polls&&v.released==1);

        a=new MediaCodec().event(0,4,4);e=exporter(a,new MediaCodec());MediaMuxer mux=new MediaMuxer();set(e,"muxer",mux);set(e,"muxStarted",true);set(e,"aTrack",0);
        drain(e,"drainAudio",false);check("A final AAC packet carrying EOS is written before completion",mux.samples==1&&Arrays.equals(mux.last,new byte[]{1,2,3,4}));
        drain(e,"drainAudio",true);check("Final AAC packet is neither duplicated nor dequeued again",mux.samples==1&&a.released==1&&a.outputPolls==1);

        a=new MediaCodec();for(int i=0;i<400;i++)a.event(-1,0,0);a.event(0,0,0);for(int i=0;i<400;i++)a.event(-1,0,0);a.event(0,0,4);e=exporter(a,new MediaCodec());
        drain(e,"drainAudio",true);check("Audio watchdog counts consecutive stalls, resetting on output progress",a.outputPolls==802);
        v=new MediaCodec();for(int i=0;i<400;i++)v.event(-1,0,0);v.event(0,0,0);for(int i=0;i<400;i++)v.event(-1,0,0);v.event(0,0,4);e=exporter(new MediaCodec(),v);
        drain(e,"drainVideo",true);check("Video watchdog resets on output progress",v.outputPolls==802);

        a=new MediaCodec();e=exporter(a,new MediaCodec());e.cancel.set(true);boolean cancelled=false;try{drain(e,"drainAudio",true);}catch(Exception ex){cancelled="cancelled".equals(ex.getMessage());}
        check("Cancellation interrupts audio finishing without polling the codec",cancelled&&a.outputPolls==0);
        v=new MediaCodec();e=exporter(new MediaCodec(),v);e.cancel.set(true);cancelled=false;try{drain(e,"drainVideo",true);}catch(Exception ex){cancelled="cancelled".equals(ex.getMessage());}
        check("Cancellation interrupts video finishing",cancelled&&v.outputPolls==0);

        a=new MediaCodec();a.eosOnInput=true;e=exporter(a,new MediaCodec());set(e,"totalFrames48",2048L);
        invoke(e,"pumpAudio",new Class[]{long.class},Long.MAX_VALUE);check("PCM frames are fed before exactly one input EOS",a.pcmInputs==2&&a.inputEos==1);
        drain(e,"drainAudio",false);polls=a.outputPolls;invoke(e,"pumpAudio",new Class[]{long.class},Long.MAX_VALUE);drain(e,"drainAudio",true);
        check("Input and output EOS remain separate, monotonic states",a.inputEos==1&&a.outputPolls==polls);

        a=new MediaCodec();e=exporter(a,new MediaCodec());boolean stalled=false;try{drain(e,"drainAudio",true);}catch(Exception ex){stalled=ex.getMessage().contains("audio encoder didn't finish");}
        check("A genuinely missing EOS still fails instead of producing a false success",stalled&&a.outputPolls==501);
        System.out.println(pass+" passed, 0 failed");
    }
}
