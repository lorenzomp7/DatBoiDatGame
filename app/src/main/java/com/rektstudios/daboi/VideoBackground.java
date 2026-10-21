package com.rektstudios.daboi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

/**
 * The clips that play once behind dat boi: "1 MLG" 48 s into MLG mode followed by
 * "2 MLG", and the normal-mode clip at 1000/2000/3000/10000/20000/30000 m.
 * MediaPlayer decodes into a SurfaceTexture (an external OES texture); the green is
 * keyed out in the shader so the sky stays visible behind the clip.
 */
final class VideoBackground {
    private static final String TAG = "DatBoi";
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    static final int NONE = 0, MLG1 = 1, NORMAL = 2, MLG2 = 3;
    private static final String[] ASSETS = {null, "mlg1.mp4", "normal1000.mp4", "mlg2.mp4"};
    /** Green screen clips get their green keyed out; "2 MLG" has a full background. */
    private static final boolean[] KEYED = {false, true, true, false};

    private final Context context;
    private MediaPlayer player;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private int tex;
    private volatile boolean frameAvailable, hasFrame;
    /** The clip plays once per run: true once it reached its end. */
    private volatile boolean finished;
    private boolean appPaused, playing, muted;
    private int current = NONE, currentSeq;
    private final float[] texMatrix = new float[16];

    VideoBackground(Context context) {
        this.context = context;
    }

    /** GL thread, after (re)creating the GL context. */
    synchronized void onSurfaceCreated() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        tex = ids[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (surface != null) surface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        surfaceTexture = new SurfaceTexture(tex);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture st) {
                frameAvailable = true;
            }
        });
        surface = new Surface(surfaceTexture);
        hasFrame = false;
        if (player != null) player.setSurface(surface);
    }

    /** GL thread, every frame: start/stop the clip and latch the newest frame. */
    synchronized void update(int clip, int seq, boolean mute) {
        if (clip != current || seq != currentSeq) {
            if (playing) stop();
            current = clip;
            currentSeq = seq;
            if (clip != NONE) start(ASSETS[clip]);
        }
        if (player != null && mute != muted) {
            muted = mute;
            float v = mute ? 0f : 1f;
            player.setVolume(v, v);
        }
        if (frameAvailable && surfaceTexture != null) {
            frameAvailable = false;
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
            hasFrame = true;
        }
    }

    boolean visible() {
        return playing && hasFrame && !finished;
    }

    /** Whether the current clip is a green screen clip. */
    boolean keyed() {
        return KEYED[current];
    }

    /** The clip that just played to its end (until another one is requested), or NONE. */
    int finishedClip() {
        return playing && finished ? current : NONE;
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

    private void start(String asset) {
        playing = true;
        hasFrame = false;
        finished = false;
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
            player.setLooping(false);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    finished = true;
                }
            });
            player.setSurface(surface);
            player.prepare();
            float v = muted ? 0f : 1f;
            player.setVolume(v, v);
            if (!appPaused) player.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot play " + asset, e);
            playing = false;
        }
    }

    private void stop() {
        playing = false;
        hasFrame = false;
        finished = false;
        if (player != null && player.isPlaying()) player.pause();
    }

    /** Main thread: activity paused / resumed. */
    synchronized void onAppPause(boolean paused) {
        appPaused = paused;
        if (player == null || !playing || finished) return;
        if (paused && player.isPlaying()) player.pause();
        else if (!paused && !player.isPlaying()) player.start();
    }

    synchronized void release() {
        playing = false;
        current = NONE;
        if (player != null) player.release();
        player = null;
    }
}
