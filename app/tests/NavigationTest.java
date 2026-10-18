package com.christopher.strata;

import java.util.Arrays;
import java.util.List;

/** Regression tests for actual navigation state, source edits, numeric input and caption code. */
public class NavigationTest {
    static int count;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("ok   "+name);}
    static void near(String name,double got,double expected){check(name,Math.abs(got-expected)<.0001);}
    public static void main(String[] args)throws Exception {
        InspectorState state=new InspectorState();
        state.open("clip1",0);check("New inspector uses first available task",state.available(Arrays.asList("Timing","Picture","Motion")).equals("Timing"));
        state.choose("Picture",240);state.open("clip1",240);check("New task opens at its own top",state.scroll()==0);
        state.open("clip1",90);check("Same-task rebuild retains its scroll",state.scroll()==90);
        state.choose("Timing",90);state.open("clip1",90);check("Returning to task restores its prior scroll",state.scroll()==240);
        state.open("audio",240);state.available(Arrays.asList("Volume","Keys"));check("Unrelated menu does not inherit clip scroll",state.scroll()==0);
        state.open("clip1",45);state.available(Arrays.asList("Timing","Picture","Motion"));check("Returning to clip restores its task and scroll",state.section().equals("Timing")&&state.scroll()==240);
        state.focus("clip1","Motion");state.open("clip1",240);state.available(Arrays.asList("Timing","Picture","Motion"));check("Explicit navigation opens target task at top",state.section().equals("Motion")&&state.scroll()==0);
        state.choose("Removed task",70);state.open("clip1",70);check("Unavailable task falls back to valid controls",state.available(Arrays.asList("Timing","Picture")).equals("Timing"));
        state.open("clip2",120);state.available(Arrays.asList("Timing","Picture"));check("Different item starts in a predictable task",state.section().equals("Timing")&&state.scroll()==0);
        state.open("clip1",10);state.available(Arrays.asList("Timing","Picture"));check("Each item retains independent scroll",state.scroll()==120);
        for(String text:new String[]{"NaN","Infinity","-Infinity","1e100","","  ","nope"}){boolean rejected=false;try{NumberInput.parse(text);}catch(IllegalArgumentException e){rejected=true;}check("Reject invalid exact number: "+text,rejected);}
        near("Finite decimal number",NumberInput.parse(" 12.75 "),12.75);near("Negative value remains available",NumberInput.parse("-4.25"),-4.25);near("Scientific numeric input",NumberInput.parse("1e-2"),.01);
        VProject.TextItem item=new VProject.TextItem();item.media=new VProject.Clip();item.media.in=1000000;item.media.out=1100000;item.media.srcDur=20000000;item.start=3000000;item.dur=100000;
        Edits.overlaySpeed(item,4);
        check("Fast B-roll stays shorter than 200ms instead of expanding",item.dur==25000);
        check("Speed retains exact source In and Out",item.media.in==1000000&&item.media.out==1100000);
        for(int i=0;i<100;i++)Edits.overlaySpeed(item,.25f+(i%16)*.25f);
        check("Repeated live speed changes cannot drift source range",item.media.in==1000000&&item.media.out==1100000&&item.start==3000000);
        Edits.overlaySpeed(item,.25f);check("Slow speed retains footage and correct duration",item.dur==400000);
        for(float speed:new float[]{0,Float.NaN,Float.POSITIVE_INFINITY,11}){boolean rejected=false;try{Edits.overlaySpeed(item,speed);}catch(IllegalArgumentException e){rejected=true;}check("Invalid overlay speed rejected: "+speed,rejected);}
        List<Srt.Cue> cues=Srt.parse("1\n00:00:01,000 --> 00:00:02,000\nGood\n\n2\n9999999999999999999999:00:00,000 --> 00:00:03,000\nBad\n\n3\n00:00:04,000 --> 00:00:05,000\nRecovered\n");
        check("One damaged caption cannot abort the rest of the import",cues.size()==2&&cues.get(1).text.equals("Recovered"));
        check("Overflowing caption time is skipped safely",Srt.parse("999999999999999:00:00,000 --> 999999999999999:00:01,000\nBad").isEmpty());
        check("Invalid minutes rejected",Srt.parse("00:65:00,000 --> 01:10:01,000\nBad").isEmpty());
        check("Valid long-hour captions are retained",Srt.parse("125:01:00,000 --> 125:01:01,000\nGood").size()==1);
        Srt.Cue later=new Srt.Cue(4000000,5000000,"Later"),earlier=new Srt.Cue(1000000,2000000,"First\r\nSecond");
        java.util.ArrayList<Srt.Cue> input=new java.util.ArrayList<Srt.Cue>(Arrays.asList(later,earlier));String written=Srt.write(input);
        check("Caption export is chronological after timeline edits",written.indexOf("First")<written.indexOf("Later"));
        check("Caption export does not mutate project cue order",input.get(0)==later);
        check("Windows caption lines are not double-converted",!written.contains("\r\r\n"));
        check("Sub-millisecond cue remains valid on export",Srt.parse(Srt.write(Arrays.asList(new Srt.Cue(1000100,1000200,"Tiny")))).size()==1);
        check("Menu search handles mixed case",MenuSearch.matches("BASS","Bass zoom blur"));
        check("Menu search matches words in any order",MenuSearch.matches("blur bass","Bass zoom blur"));
        check("Menu search trims repeated whitespace",MenuSearch.matches("  video  main ","Main video audio"));
        check("Empty menu search returns all items",MenuSearch.matches("","Chromatic aberration"));
        check("All query words are required",!MenuSearch.matches("bass color","Bass zoom blur"));
        System.out.println(count+" passed, 0 failed");
    }
}
