package com.rektstudios.daboi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

/**
 * The clips that play once behind dat boi: "1 MLG" 48 s into MLG mode followed by
 * "2 MLG" and "3 MLG", then "Snoop Dogg" on repeat; and the normal-mode clip at
 * 1000/2000/3000/10000/20000/30000 m.
 * MediaPlayer decodes into a SurfaceTexture (an external OES texture); the green is
 * keyed out in the shader so the sky stays visible behind the clip.
 *
 * Threads: the GL thread requests clips and latches frames; every MediaPlayer call
 * runs on {@link MediaThread}, so preparing a clip never stalls the game.
 */
final class VideoBackground {
    private static final String TAG = "DatBoi";
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    static final int NONE = 0, MLG1 = 1, NORMAL = 2, MLG2 = 3, MLG3 = 4, SNOOP = 5;
    private static final String[] ASSETS = {null, "mlg1.mp4", "normal1000.mp4", "mlg2.mp4", "mlg3.mp4", "snoop.mp4"};
    /** Green screen clips get their green keyed out; "2 MLG" has a full background. */
    private static final boolean[] KEYED = {false, true, true, false, true, true};
    /** "Snoop Dogg" repeats until restart; every other clip plays once. */
    private static final boolean[] LOOP = {false, false, false, false, false, true};

    private final Context context;
    private final Handler media = MediaThread.get();

    // GL thread
    private SurfaceTexture surfaceTexture;
    private int tex;
    private int current = NONE, currentSeq;
    private boolean hasFrame, lastMute;
    private final float[] texMatrix = new float[16];

    // shared: each request gets a token; the media thread reports back with it
    private volatile int token;
    private volatile int preparedToken = -1, finishedToken = -1;
    private volatile boolean frameAvailable;

    // media thread
    private MediaPlayer player;
    private Surface surface;
    private boolean appPaused, muted, active;

    VideoBackground(Context context) {
        this.context = context;
    }

    /** GL thread, after (re)creating the GL context. */
    void onSurfaceCreated() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        tex = ids[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        final SurfaceTexture old = surfaceTexture;
        surfaceTexture = new SurfaceTexture(tex);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture st) {
                frameAvailable = true;
            }
        });
        hasFrame = false;
        final Surface s = new Surface(surfaceTexture);
        media.post(new Runnable() {
            @Override
            public void run() {
                Surface previous = surface;
                surface = s;
                if (player != null) player.setSurface(s);
                if (previous != null) previous.release();
                if (old != null) old.release();
            }
        });
    }

    /** GL thread, every frame: request clip changes and latch the newest frame. */
    void update(int clip, int seq, final boolean mute) {
        if (clip != current || seq != currentSeq) {
            current = clip;
            currentSeq = seq;
            hasFrame = false;
            final int t = ++token;
            final String asset = clip == NONE ? null : ASSETS[clip];
            final boolean loop = LOOP[clip];
            media.post(new Runnable() {
                @Override
                public void run() {
                    play(asset, loop, t);
                }
            });
        }
        if (mute != lastMute) {
            lastMute = mute;
            media.post(new Runnable() {
                @Override
                public void run() {
                    muted = mute;
                    if (player != null) player.setVolume(mute ? 0f : 1f, mute ? 0f : 1f);
                }
            });
        }
        if (frameAvailable && surfaceTexture != null) {
            frameAvailable = false;
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
            if (preparedToken == token) hasFrame = true;
        }
    }

    boolean visible() {
        return current != NONE && hasFrame && finishedToken != token;
    }

    /** Whether the current clip is a green screen clip. */
    boolean keyed() {
        return KEYED[current];
    }

    /** The clip that just played to its end (until another one is requested), or NONE. */
    int finishedClip() {
        return current != NONE && finishedToken == token ? current : NONE;
    }

    int texture() {
        return tex;
    }

    /** Texture target of {@link #texture()} (an external OES texture). */
    boolean external() {
        return true;
    }

    float[] texMatrix() {
        return texMatrix;
    }

    /** Media thread. */
    private void play(String asset, boolean loop, final int t) {
        if (t != token) return;   // a newer request is already queued
        active = asset != null;
        if (!active) {
            if (player != null && player.isPlaying()) player.pause();
            return;
        }
        try {
            if (player == null) {
                player = new MediaPlayer();
            } else {
                player.reset();
            }
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            AssetFileDescriptor fd = context.getAssets().openFd(asset);
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            player.setLooping(loop);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (t == token) finishedToken = t;
                }
            });
            player.setSurface(surface);
            player.prepare();
            if (t != token) return;
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            preparedToken = t;
            if (!appPaused) player.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot play " + asset, e);
            active = false;
        }
    }

    /** Main thread: activity paused / resumed. */
    void onAppPause(final boolean paused) {
        media.post(new Runnable() {
            @Override
            public void run() {
                appPaused = paused;
                if (player == null || !active || preparedToken != token || finishedToken == token) return;
                if (paused && player.isPlaying()) player.pause();
                else if (!paused && !player.isPlaying()) player.start();
            }
        });
    }

    void release() {
        media.post(new Runnable() {
            @Override
            public void run() {
                active = false;
                if (player != null) player.release();
                player = null;
            }
        });
    }
}
