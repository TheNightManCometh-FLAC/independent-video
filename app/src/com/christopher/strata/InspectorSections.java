package com.christopher.strata;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Group inspector controls once, keeping the current task's controls visible. */
final class InspectorSections {
    private static final class Marker { final String name, back; Marker(String n,String b){name=n;back=b;} }
    static void mark(LinearLayout panel, String name) { mark(panel, name, null); }
    /**
     * 1.0: a section that LISTS things (the effect stack, the layer list, the effect browser).
     * The other sections of the same panel show one item's details, so they get a "‹ back" row
     * at the top that returns here; the section chips alone were too easy to miss.
     */
    static void markList(LinearLayout panel, String name, String backLabel) { mark(panel, name, backLabel); }
    private static void mark(LinearLayout panel, String name, String back) {
        View marker=new View(panel.getContext());marker.setTag(new Marker(name,back));
        panel.addView(marker,new LinearLayout.LayoutParams(0,0));
    }
    static void organize(final LinearLayout panel, final ScrollView scroll, final InspectorState state, final Runnable rebuild) {
        ArrayList<View> prefix=new ArrayList<View>();
        LinkedHashMap<String,LinearLayout> groups=new LinkedHashMap<String,LinearLayout>();
        String listName=null,backLabel=null;
        ArrayList<View> children=new ArrayList<View>();
        for(int i=0;i<panel.getChildCount();i++)children.add(panel.getChildAt(i));
        panel.removeAllViews();LinearLayout group=null;
        for(View child:children) {
            if(child.getTag() instanceof Marker) {
                Marker mk=(Marker)child.getTag();String name=mk.name;group=groups.get(name);
                if(mk.back!=null){listName=name;backLabel=mk.back;}
                if(group==null){group=Ui.col(panel.getContext());groups.put(name,group);}
            } else if(group==null)prefix.add(child);
            else group.addView(child);
        }
        for(View v:prefix)panel.addView(v);
        java.util.Iterator<String> names=groups.keySet().iterator();
        while(names.hasNext())if(groups.get(names.next()).getChildCount()==0)names.remove();
        if(groups.isEmpty())return;
        String selected=state.available(groups.keySet());
        if(groups.size()>1) {
            ArrayList<View> tabs=new ArrayList<View>();
            for(final String name:groups.keySet())tabs.add(Ui.chip(panel.getContext(),name,name.equals(selected),new View.OnClickListener(){
                @Override public void onClick(View v){state.choose(name,scroll.getScrollY());rebuild.run();}
            }));
            panel.addView(Ui.strip(panel.getContext(),tabs.toArray(new View[0])));
        }
        if(listName!=null&&groups.containsKey(listName)&&!listName.equals(selected)&&groups.containsKey(selected)) {
            final String target=listName;
            groups.get(selected).addView(backRow(panel.getContext(),backLabel,new View.OnClickListener(){
                @Override public void onClick(View v){state.choose(target,scroll.getScrollY());rebuild.run();}
            }),0);
        }
        for(String name:groups.keySet()) {
            LinearLayout controls=groups.get(name);controls.setVisibility(name.equals(selected)?View.VISIBLE:View.GONE);panel.addView(controls);
        }
    }

    /** "‹ Effects list": a full-width 44 dp row, so it's an easy target. */
    static View backRow(android.content.Context c, String label, View.OnClickListener l) {
        LinearLayout r=Ui.row(c);
        r.setPadding(Ui.dp(10),0,Ui.dp(14),0);
        r.setMinimumHeight(Ui.dp(44));
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff),null,new android.graphics.drawable.ColorDrawable(0xffffffff)));
        r.addView(Ui.icon(c,"back",Ui.ACCENT,20));
        android.widget.TextView t=Ui.text(c,label,14,Ui.ACCENT);
        t.setPadding(Ui.dp(8),0,0,0);
        r.addView(t);
        r.setContentDescription("Back to "+label);
        r.setOnClickListener(l);
        return r;
    }
}
