package com.christopher.strata;

import java.util.HashMap;

/** Navigation state only: changing a panel or section must never change the document. */
final class InspectorState {
    private final HashMap<String,String> sections = new HashMap<String,String>();
    private final HashMap<String,Integer> scroll = new HashMap<String,Integer>();
    String context;
    private boolean restoring;

    void open(String next, int currentScroll) {
        if (context != null && !restoring) scroll.put(key(), Math.max(0,currentScroll));
        context = next; restoring = false;
    }
    String section() { return sections.get(context); }
    String available(java.util.Collection<String> names) {
        String current = section();
        if (!names.contains(current)) { current=names.isEmpty()?null:names.iterator().next(); sections.put(context,current); }
        return current;
    }
    void choose(String name, int currentScroll) {
        scroll.put(key(),Math.max(0,currentScroll)); sections.put(context,name); restoring=true;
    }
    void focus(String targetContext, String name) {
        sections.put(targetContext,name);scroll.put(targetContext+"/"+name,0);
        if(targetContext.equals(context))restoring=true;
    }
    int scroll() { Integer y=scroll.get(key());return y==null?0:y; }
    private String key() { return context+"/"+section(); }
}
