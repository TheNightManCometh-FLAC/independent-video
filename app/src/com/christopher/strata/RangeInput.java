package com.christopher.strata;

import android.app.*;
import android.content.DialogInterface;
import android.text.*;
import android.view.View;
import android.widget.*;

/** Visible, labeled source range input shared by source preview and the timeline's Times action. */
final class RangeInput {
    interface Result { void apply(long in,long out); }
    static void show(Activity act,final VProject.Clip clip,final int fps,long in,long out,final String effect,final Result result) {
        LinearLayout body=Ui.col(act);body.setPadding(Ui.dp(20),Ui.dp(8),Ui.dp(20),Ui.dp(8));
        body.addView(Ui.text(act,"Source In · first frame kept",13,Ui.DIM));
        final EditText a=new EditText(act),b=new EditText(act);
        a.setSingleLine(true);b.setSingleLine(true);a.setText(Timeline.timecode(in,fps));b.setText(Timeline.timecode(out,fps));
        a.setSelectAllOnFocus(true);b.setSelectAllOnFocus(true);
        a.setContentDescription("Source In, first frame kept");b.setContentDescription("Source Out, exclusive end time");
        body.addView(a);body.addView(Ui.text(act,"Source Out · exclusive end",13,Ui.DIM));body.addView(b);
        final TextView summary=Ui.text(act,"",13,Ui.TEXT);body.addView(summary);
        body.addView(Ui.text(act,"Seconds, m:ss.sss or m:ss:ff (project frames).\n"+effect,12,Ui.DIM));
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int be,int c){
            try{long[] v=ClipRange.checked(clip,EditTime.parse(a.getText().toString(),fps),EditTime.parse(b.getText().toString(),fps),fps);summary.setText("Output duration  "+Timeline.timecode(v[2],fps));}
            catch(IllegalArgumentException e){summary.setText(e.getMessage());}
        }public void afterTextChanged(Editable e){}};
        a.addTextChangedListener(watcher);b.addTextChangedListener(watcher);watcher.onTextChanged("",0,0,0);
        body.addView(Ui.strip(act,nudge(act,"In −",a,b,clip,fps,true,-1),nudge(act,"In +",a,b,clip,fps,true,1),nudge(act,"Out −",a,b,clip,fps,false,-1),nudge(act,"Out +",a,b,clip,fps,false,1)));
        final AlertDialog dialog=new AlertDialog.Builder(act).setTitle("Source In / Out").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface ignored){dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){public void onClick(View view){
            try{long[] v=ClipRange.checked(clip,EditTime.parse(a.getText().toString(),fps),EditTime.parse(b.getText().toString(),fps),fps);result.apply(v[0],v[1]);dialog.dismiss();}
            catch(IllegalArgumentException e){b.setError(e.getMessage());}
        }});}});dialog.show();
    }
    private static View nudge(Activity act,String label,final EditText a,final EditText b,final VProject.Clip clip,final int fps,final boolean head,final int delta){
        TextView button=Ui.chip(act,label,false,new View.OnClickListener(){public void onClick(View ignored){
            try{
                long in=EditTime.parse(a.getText().toString(),fps),out=EditTime.parse(b.getText().toString(),fps);
                if(head)in=Timeline.frameTime(Timeline.frameIndex(in,fps)+delta,fps);else out=Timeline.frameTime(Timeline.frameIndex(out,fps)+delta,fps);
                long[] range=ClipRange.checked(clip,in,out,fps);
                (head?a:b).setText(Timeline.timecode(head?range[0]:range[1],fps));a.setError(null);b.setError(null);
            }catch(IllegalArgumentException e){(head?a:b).setError(e.getMessage());}
        }});button.setContentDescription((head?"Source In":"Source Out")+(delta<0?", one project frame earlier":", one project frame later"));return button;
    }
}
