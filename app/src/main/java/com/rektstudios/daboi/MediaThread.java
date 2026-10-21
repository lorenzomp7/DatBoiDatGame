package com.rektstudios.daboi;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * One background thread for every MediaPlayer call (music and videos). Preparing
 * a player takes a while; doing it on the GL thread froze the game and on the main
 * thread it froze the touch controls, so a run could be lost to the hiccup.
 */
final class MediaThread {
    private static Handler handler;

    private MediaThread() {
    }

    static synchronized Handler get() {
        if (handler == null) handler = create("DatBoi-media");
        return handler;
    }

    /** A separate media thread (the videos use their own). */
    static Handler create(String name) {
        HandlerThread t = new HandlerThread(name);
        t.start();
        return new Handler(t.getLooper());
    }
}
