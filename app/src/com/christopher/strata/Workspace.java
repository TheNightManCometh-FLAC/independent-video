package com.christopher.strata;

import android.content.Intent;
import android.view.View;

/** An open project screen (photo editor or video editor), as MainActivity sees it. */
interface Workspace {
    View rootView();
    /** Back pressed: true if handled inside the workspace. */
    boolean back();
    boolean blocking();
    void onPause();
    void onResume();
    void save();
    /** Commits a preview and writes all pending data; false prevents a stale recovery snapshot. */
    boolean saveForVersion();
    void close();
    /** Runs r once pending GL work (thumbnails) has been flushed. */
    void flush(Runnable r);
    void fontImported(String key);
    void lutImported(String name);
    void mediaPicked(int req, Intent data);
}
