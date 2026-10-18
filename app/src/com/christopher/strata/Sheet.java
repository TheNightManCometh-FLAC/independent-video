package com.christopher.strata;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Bottom sheet menu (the pattern Lightroom, Snapseed and Google Photos use for "more" actions):
 * slides up from the bottom, rows with an icon and a label, an optional grid of tiles, tap
 * outside or Back to dismiss. Picking an item dismisses the sheet first, then runs it.
 */
final class Sheet {
    private final Activity a;
    private final Dialog d;
    private final String title;
    /** 1.0: the sheet whose row is running right now (UI thread). A sheet shown while it runs
     *  was opened FROM it, so it gets a back arrow and Back returns there instead of closing. */
    private static Sheet opening;
    private Sheet parent;
    private final LinearLayout body, header;
    private String currentSection="";
    private final java.util.IdentityHashMap<View,String> searchText=new java.util.IdentityHashMap<View,String>();
    private final java.util.HashSet<View> headings=new java.util.HashSet<View>();
    private TextView empty;
    private LinearLayout gridRow;
    private int gridCount;
    private static final int GRID_COLS = 4;

    Sheet(Activity a, String title) {
        this.a = a;
        this.title = title;
        d = new Dialog(a) {
            @Override public void onBackPressed() {
                if (parent != null) { dismiss(); parent.show(); } else super.onBackPressed();
            }
        };
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=Ui.col(a);header=Ui.col(a);
        body = Ui.col(a);
        body.setPadding(0, Ui.dp(8), 0, Ui.dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL);
        float r = Ui.dp(18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.addView(header);
        View grip = new View(a);
        grip.setBackground(Ui.round(Ui.LINE, 3, 0));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = Ui.dp(6);
        header.addView(grip, glp);
        if (title != null) {
            TextView t = Ui.text(a, title, 13, Ui.DIM);
            t.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(8));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(t);
        }
        ScrollView sv = new ScrollView(a) {
            @Override protected void onMeasure(int width,int height) {
                int cap=(int)(getResources().getDisplayMetrics().heightPixels*.65f);
                super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));
            }
        };
        sv.addView(body);
        // edge-to-edge (Android 15+): keep the last row above the navigation bar
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int nav = android.os.Build.VERSION.SDK_INT >= 30
                        ? in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                        : in.getSystemWindowInsetBottom();
                body.setPadding(0, Ui.dp(8), 0, Ui.dp(14) + nav);
                return in;
            }
        });
        root.addView(sv);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
        }
    }

    private void run(final Runnable r) {
        d.dismiss();
        if (r != null) a.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() {
                opening = Sheet.this;
                try { r.run(); } finally { opening = null; }
            }
        });
    }

    private void addBackArrow() {
        LinearLayout r = Ui.row(a);
        r.setPadding(Ui.dp(12), 0, Ui.dp(20), 0);
        r.setMinimumHeight(Ui.dp(40));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), null, new ColorDrawable(0xffffffff)));
        r.addView(Ui.icon(a, "back", Ui.ACCENT, 20));
        TextView t = Ui.text(a, parent.title != null ? parent.title : "Back", 13, Ui.ACCENT);
        t.setPadding(Ui.dp(8), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, Ui.lp(0, -2, 1f));
        r.setContentDescription("Back to " + (parent.title != null ? parent.title : "the previous menu"));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); parent.show(); } });
        header.addView(r, 1);   // under the grip, above the title
    }

    Sheet item(String icon, String label, Runnable r) { return row(icon, label, null, Ui.TEXT, r); }

    Sheet item(String icon, String label, String sub, Runnable r) { return row(icon, label, sub, Ui.TEXT, r); }

    /** 3.13: a row that also reacts to a long press (catalog favourites). The press doesn't close the sheet. */
    Sheet item(String icon, String label, String sub, Runnable r, final Runnable longPress) {
        row(icon, label, sub, Ui.TEXT, r);
        lastAdded.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { longPress.run(); return true; } });
        return this;
    }

    Sheet tile(String icon, String label, Runnable r, final Runnable longPress) {
        tile(icon, label, r);
        lastAdded.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { longPress.run(); return true; } });
        return this;
    }

    /** A short dim line under the title (e.g. how favourites work). */
    Sheet note(String text) {
        closeGrid();
        TextView t = Ui.text(a, text, 12, Ui.DIM);
        t.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(6));
        body.addView(t);
        return this;
    }

    private View lastAdded;
    private LinearLayout lastDisc;

    /** 3.14: the picture area of the tile just added, to receive a thumbnail later (setThumb). */
    View lastTileDisc() { return lastDisc; }

    /** Replaces a tile's icon with a picture (a look or effect rendered on the current frame). */
    static void setThumb(View disc, android.graphics.Bitmap b) {
        if (!(disc instanceof LinearLayout) || b == null) return;
        LinearLayout d = (LinearLayout) disc;
        d.removeAllViews();
        android.widget.ImageView iv = new android.widget.ImageView(d.getContext());
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
        d.addView(iv, new LinearLayout.LayoutParams(-1, -1));
        d.setClipToOutline(true);
    }

    Sheet danger(String icon, String label, Runnable r) { return row(icon, label, null, Ui.DANGER, r); }

    private Sheet row(String icon, String label, String sub, int color, final Runnable r) {
        closeGrid();
        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        row.setMinimumHeight(Ui.dp(52));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), null, new ColorDrawable(0xffffffff)));
        android.widget.ImageView i = Ui.icon(a, icon, color, 22);
        row.addView(i);
        LinearLayout txt = Ui.col(a);
        txt.setPadding(Ui.dp(18), Ui.dp(8), 0, Ui.dp(8));
        txt.addView(Ui.text(a, label, 15, color));
        if (sub != null) txt.addView(Ui.text(a, sub, 12, Ui.DIM));
        row.addView(txt, Ui.lp(0, -2, 1f));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run(r); } });
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        lastAdded = row;
        searchText.put(row,currentSection+" "+label+" "+(sub==null?"":sub));
        return this;
    }

    /** A tile in a 4-wide grid (consecutive tiles share rows). */
    Sheet tile(String icon, String label, final Runnable r) {
        if (gridRow == null || gridCount == GRID_COLS) {
            gridRow = Ui.row(a);
            gridRow.setPadding(Ui.dp(10), Ui.dp(2), Ui.dp(10), Ui.dp(2));
            gridRow.setGravity(Gravity.TOP);
            body.addView(gridRow, new LinearLayout.LayoutParams(-1, -2));
            gridCount = 0;
        }
        LinearLayout t = Ui.col(a);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), null, new ColorDrawable(0xffffffff)));
        LinearLayout disc = Ui.col(a);
        disc.setGravity(Gravity.CENTER);
        disc.setBackground(Ui.round(Ui.PANEL2, 14, Ui.LINE));
        disc.addView(Ui.icon(a, icon, Ui.TEXT, 24));
        t.addView(disc, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
        lastDisc = disc;
        TextView l = Ui.text(a, label, 11.5f, Ui.TEXT);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(6), 0, 0);
        t.addView(l, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run(r); } });
        gridRow.addView(t, Ui.lp(0, -2, 1f));
        lastAdded = t;
        searchText.put(t,currentSection+" "+label);
        gridCount++;
        if (gridCount == GRID_COLS) gridRow = null;
        return this;
    }

    /** Pads an unfinished grid row so its tiles keep their width. */
    private void closeGrid() {
        if (gridRow != null) while (gridCount++ < GRID_COLS) gridRow.addView(new View(a), Ui.lp(0, 1, 1f));
        gridRow = null;
    }

    Sheet section(String label) {
        closeGrid();
        currentSection=label;
        View heading=Ui.label(a,label);headings.add(heading);body.addView(heading);
        return this;
    }

    Sheet searchable(String hint) {
        android.widget.EditText input=new android.widget.EditText(a);
        input.setSingleLine(true);input.setTextSize(14);input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.DIM);input.setHint(hint);
        input.setPadding(Ui.dp(20),Ui.dp(8),Ui.dp(20),Ui.dp(8));header.addView(input);
        input.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){filter(s.toString());}
            @Override public void afterTextChanged(android.text.Editable e){}
        });
        return this;
    }
    private void filter(String query) {
        int count=0;
        for(View view:searchText.keySet()){
            boolean match=MenuSearch.matches(query,searchText.get(view));view.setVisibility(match?View.VISIBLE:View.GONE);if(match)count++;
        }
        // A heading is visible only when its following rows contain a match.
        View heading=null;boolean visible=false;
        for(int i=0;i<body.getChildCount();i++){
            View child=body.getChildAt(i);
            if(headings.contains(child)){
                if(heading!=null)heading.setVisibility(visible?View.VISIBLE:View.GONE);
                heading=child;visible=false;
            }else if(searchText.containsKey(child))visible|=child.getVisibility()==View.VISIBLE;
            else if(child instanceof LinearLayout){
                LinearLayout row=(LinearLayout)child;boolean any=false;
                for(int j=0;j<row.getChildCount();j++){View tile=row.getChildAt(j);if(searchText.containsKey(tile)&&tile.getVisibility()==View.VISIBLE)any=true;}
                child.setVisibility(any?View.VISIBLE:View.GONE);visible|=any;
            }
        }
        if(heading!=null)heading.setVisibility(visible?View.VISIBLE:View.GONE);
        if(empty==null){empty=Ui.text(a,"No matching items",14,Ui.DIM);empty.setPadding(Ui.dp(20),Ui.dp(16),Ui.dp(20),Ui.dp(16));body.addView(empty);}
        empty.setVisibility(count==0?View.VISIBLE:View.GONE);
    }

    void show() {
        closeGrid();
        if (parent == null && opening != null && opening != this && opening.a == a) { parent = opening; addBackArrow(); }
        d.show();
        if(d.getWindow()!=null)d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if(d.getWindow()!=null)d.getWindow().setLayout(-1,-2);
    }
}
