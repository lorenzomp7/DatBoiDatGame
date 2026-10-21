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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The clips that play behind dat boi: "1 MLG" -> "2 MLG" -> "3 MLG" -> "Snoop Dogg"
 * on repeat in MLG mode, and the normal-mode clip at 1000/2000/3000/10000/20000/30000 m.
 * MediaPlayer decodes into a SurfaceTexture (an external OES texture); the green is
 * keyed out in the shader so the sky stays visible behind the clip.
 *
 * Every clip has its own player and its own texture, prepared once and never released
 * or prepared again while the game runs (releasing a player and preparing a new one on
 * the same surface could hang): a clip is only started, paused and rewound. The next
 * clip is always ready, and the last frame of the old clip stays on screen until the
 * new one has a frame, so there is never a gap between clips.
 *
 * The MLG clips and "Snoop Dogg" are silent videos: a clip with sound waits about
 * half a second for the audio output with its first frame frozen on screen, so the
 * sound of "1 MLG" + "2 MLG" + "3 MLG" is one separate track ({@link #CHAIN_AUDIO}).
 * It starts first, and "1 MLG" starts as soon as it is really playing.
 *
 * Threads: the GL thread requests clips and latches frames; every MediaPlayer call
 * runs on a media thread of its own, so preparing a clip never stalls the game.
 */
final class VideoBackground {
    private static final String TAG = "DatBoi";
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    static final int NONE = 0, MLG1 = 1, NORMAL = 2, MLG2 = 3, MLG3 = 4, SNOOP = 5;
    private static final int CLIPS = 6;
    private static final String[] ASSETS = {null, "mlg1.mp4", "normal1000.mp4", "mlg2.mp4", "mlg3.mp4", "snoop.mp4"};
    /** Green screen clips get their green keyed out; "2 MLG" has a full background. */
    private static final boolean[] KEYED = {false, true, true, false, true, true};
    /**
     * "Snoop Dogg" repeats until it is replaced; every other clip plays once. The asset
     * already holds the clip 10 times over (95 s, longer than it ever stays on screen
     * between two MLG cues), because MediaPlayer stalls for half a second when it loops.
     */
    private static final boolean[] LOOP = {false, false, false, false, false, true};
    /** The clip that follows each one as soon as it ends. */
    private static final int[] AUTO_NEXT = {NONE, MLG2, NONE, MLG3, SNOOP, NONE};

    private static final String CHAIN_AUDIO = "mlgchain.ogg";

    private final Context context;
    /** Its own thread, apart from the music: a stuck video player can never hold up the songs. */
    private final Handler media = MediaThread.create("DatBoi-video");

    private static final class Clip {
        // GL thread
        SurfaceTexture surfaceTexture;
        int tex;
        final float[] texMatrix = new float[16];
        /**
         * Frames queued and not latched yet. Every one of them has to be taken: frames
         * left in the queue use up the decoder's buffers until the clip freezes.
         */
        final AtomicInteger pending = new AtomicInteger();
        // media thread
        MediaPlayer player;
        Surface surface;
        boolean used;      // played (or cut) since it was last rewound
    }

    private final Clip[] clips = new Clip[CLIPS];

    // GL thread
    private int requestedClip, requestedSeq;
    private boolean lastMute;
    private int shown = NONE, shownGen = -1;
    private boolean shownFinished;

    /**
     * Published by the media thread in one value so the GL thread sees a consistent
     * snapshot: bits 0-3 clip (0: nothing), 6 finished, 8+ start counter.
     */
    private volatile int state;

    // media thread
    private int active = NONE, gen;
    private boolean appPaused, muted, activeFinished;
    /** Watchdog: a clip whose position stops moving is skipped instead of freezing the chain. */
    private static final long WATCHDOG_MS = 500, STUCK_MS = 2000;
    private int watchedGen = -1, lastPos = -1;
    private long stuckFor;
    // the MLG chain sound
    private MediaPlayer chainAudio;
    private boolean chainAudioUsed, chainActive;
    private int chainToken;
    private static final long CHAIN_WAIT_MS = 1000;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkStuck();
        }
    };

    VideoBackground(Context context) {
        this.context = context;
        for (int i = 1; i < CLIPS; i++) clips[i] = new Clip();
    }

    /** GL thread, after (re)creating the GL context. */
    void onSurfaceCreated() {
        int[] ids = new int[CLIPS];
        GLES20.glGenTextures(CLIPS, ids, 0);
        for (int i = 1; i < CLIPS; i++) {
            final Clip clip = clips[i];
            clip.tex = ids[i];
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, clip.tex);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            final SurfaceTexture old = clip.surfaceTexture;
            clip.surfaceTexture = new SurfaceTexture(clip.tex);
            clip.pending.set(0);
            clip.surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture st) {
                    clip.pending.incrementAndGet();
                }
            });
            final Surface s = new Surface(clip.surfaceTexture);
            final int index = i;
            media.post(new Runnable() {
                @Override
                public void run() {
                    Surface previous = clip.surface;
                    clip.surface = s;
                    if (clip.player == null) prepare(index);
                    else clip.player.setSurface(s);
                    if (previous != null) previous.release();
                    if (old != null) old.release();
                }
            });
        }
        media.post(new Runnable() {
            @Override
            public void run() {
                prepareChainAudio();
            }
        });
        // the new textures are empty: wait for the next frame before showing anything
        shown = NONE;
        shownGen = -1;
    }

    /**
     * GL thread, every frame: request clip changes and latch the newest frame.
     * (Every clip is always prepared, so the game's hint about the next clip is not needed.)
     */
    void update(int clip, int seq, int hint, final boolean mute) {
        if (clip != requestedClip || seq != requestedSeq) {
            requestedClip = clip;
            requestedSeq = seq;
            final int c = clip;
            media.post(new Runnable() {
                @Override
                public void run() {
                    if (c == MLG1) startChain();
                    else play(c);
                }
            });
        }
        if (mute != lastMute) {
            lastMute = mute;
            media.post(new Runnable() {
                @Override
                public void run() {
                    muted = mute;
                    float v = mute ? 0f : 1f;
                    for (int i = 1; i < CLIPS; i++) {
                        if (clips[i].player != null) clips[i].player.setVolume(v, v);
                    }
                    if (chainAudio != null) chainAudio.setVolume(v, v);
                }
            });
        }
        int st = state;
        int playing = st & 15;
        int g = st >>> 8;
        if (playing == NONE) {
            shown = NONE;
            return;
        }
        Clip c = clips[playing];
        int n = c.pending.getAndSet(0);
        if (n > 0 && c.surfaceTexture != null) {
            // take every queued frame (each call latches the next one), keep the newest
            for (int i = 0; i < Math.min(n, 16); i++) c.surfaceTexture.updateTexImage();
            c.surfaceTexture.getTransformMatrix(c.texMatrix);
            if (g != shownGen) {
                // the new clip has a frame: switch to it (until now the old one stayed)
                shown = playing;
                shownGen = g;
            }
        }
        if (g == shownGen) shownFinished = (st & 64) != 0;
    }

    boolean visible() {
        return shown != NONE && !shownFinished;
    }

    /** Whether the clip on screen is a green screen clip. */
    boolean keyed() {
        return KEYED[shown];
    }

    int texture() {
        return shown != NONE ? clips[shown].tex : 0;
    }

    /** Texture target of {@link #texture()} (an external OES texture). */
    boolean external() {
        return true;
    }

    float[] texMatrix() {
        return clips[shown != NONE ? shown : MLG1].texMatrix;
    }

    // ---------------------------------------------------------------- media thread
    private void publish(int clip, boolean finished) {
        state = (gen << 8) | (finished ? 64 : 0) | clip;
    }

    /** Prepares a clip's player once; it is kept for the whole session. */
    private void prepare(final int index) {
        Clip clip = clips[index];
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            AssetFileDescriptor fd = context.getAssets().openFd(ASSETS[index]);
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            p.setLooping(LOOP[index]);
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    VideoBackground.this.onCompletion(index);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, ASSETS[index] + " error " + what + "/" + extra);
                    // a broken clip ends early instead of freezing the chain
                    VideoBackground.this.onCompletion(index);
                    return true;
                }
            });
            p.setSurface(clip.surface);
            p.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            p.prepare();
            clip.player = p;
            clip.used = false;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "cannot load " + ASSETS[index], e);
        }
    }

    /** Puts a clip back at its start (paused), ready to play again. */
    private void rewind(int index) {
        Clip clip = clips[index];
        if (clip.player == null || !clip.used) return;
        try {
            if (clip.player.isPlaying()) clip.player.pause();
            clip.player.seekTo(0);
            clip.used = false;
        } catch (IllegalStateException e) {
            Log.w(TAG, "cannot rewind " + ASSETS[index], e);
        }
    }

    /** Starts the MLG chain sound, then "1 MLG" once the sound is really playing. */
    private void startChain() {
        final int t = ++chainToken;
        stopChainAudio();
        prepareChainAudio();
        if (chainAudio == null) {
            play(MLG1);
            return;
        }
        chainAudioUsed = true;
        chainActive = true;
        chainAudio.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        if (!appPaused) chainAudio.start();
        final long[] waited = {0};
        media.post(new Runnable() {
            @Override
            public void run() {
                if (t != chainToken) return;
                int pos;
                try {
                    pos = chainAudio.getCurrentPosition();
                } catch (IllegalStateException e) {
                    pos = 1;
                }
                if (pos > 0 || waited[0] >= CHAIN_WAIT_MS) {
                    play(MLG1);
                } else {
                    if (!appPaused) waited[0] += 5;
                    media.postDelayed(this, 5);
                }
            }
        });
    }

    /** Keeps a fresh, prepared player for the MLG chain sound (audio only). */
    private void prepareChainAudio() {
        if (chainAudio != null && !chainAudioUsed) return;
        if (chainAudio != null) chainAudio.release();
        chainAudio = null;
        chainAudioUsed = false;
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            AssetFileDescriptor fd = context.getAssets().openFd(CHAIN_AUDIO);
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp == chainAudio) chainActive = false;
                }
            });
            p.prepare();
            chainAudio = p;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "cannot load " + CHAIN_AUDIO, e);
        }
    }

    private void stopChainAudio() {
        chainActive = false;
        if (chainAudio != null && chainAudio.isPlaying()) chainAudio.pause();
    }

    private void play(int index) {
        int old = active;
        if (index == NONE) {
            chainToken++;
            stopChainAudio();
            active = NONE;
            gen++;
            publish(NONE, false);
            if (old != NONE) rewind(old);
            prepareChainAudio();
            return;
        }
        Clip clip = clips[index];
        if (clip.player == null) {
            Log.w(TAG, ASSETS[index] + " is not ready");
            // hide the old clip rather than freezing on it
            if (old != NONE) {
                activeFinished = true;
                publish(old, true);
            }
            return;
        }
        if (clip.used) rewind(index);   // the same clip again (the normal clip, or "1 MLG" over itself)
        if (old != NONE && old != index && clips[old].player != null && clips[old].player.isPlaying()) {
            clips[old].player.pause();
        }
        // frames already queued (the first frame shown by the rewind) are taken by the GL thread
        clip.player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        if (!appPaused) clip.player.start();
        clip.used = true;
        active = index;
        activeFinished = false;
        gen++;
        publish(index, false);
        watchedGen = gen;
        lastPos = -1;
        stuckFor = 0;
        media.removeCallbacks(watchdog);
        media.postDelayed(watchdog, WATCHDOG_MS);
        // the old clip goes back to its start, ready for next time (after anything queued)
        if (old != NONE && old != index) {
            final int o = old;
            media.post(new Runnable() {
                @Override
                public void run() {
                    if (active != o) rewind(o);
                }
            });
        }
    }

    private void onCompletion(int index) {
        if (index != active || activeFinished) return;
        int next = AUTO_NEXT[index];
        if (next != NONE) {
            play(next);
        } else {
            activeFinished = true;
            publish(index, true);
        }
    }

    private void checkStuck() {
        if (active == NONE || activeFinished || watchedGen != gen) return;
        Clip clip = clips[active];
        if (!appPaused && clip.player != null) {
            int pos;
            try {
                pos = clip.player.getCurrentPosition();
            } catch (IllegalStateException e) {
                pos = lastPos;
            }
            stuckFor = pos == lastPos ? stuckFor + WATCHDOG_MS : 0;
            lastPos = pos;
            if (stuckFor >= STUCK_MS) {
                Log.w(TAG, ASSETS[active] + " stopped at " + pos + " ms, moving on");
                stuckFor = 0;
                if (LOOP[active]) {
                    play(active);   // a loop that stalls: start it over
                    return;
                }
                onCompletion(active);
                return;
            }
        }
        media.postDelayed(watchdog, WATCHDOG_MS);
    }

    /** Main thread: activity paused / resumed. */
    void onAppPause(final boolean paused) {
        media.post(new Runnable() {
            @Override
            public void run() {
                appPaused = paused;
                if (chainAudio != null && chainActive) {
                    if (paused && chainAudio.isPlaying()) chainAudio.pause();
                    else if (!paused && !chainAudio.isPlaying()) chainAudio.start();
                }
                if (active == NONE || activeFinished) return;
                MediaPlayer p = clips[active].player;
                if (p == null) return;
                if (paused && p.isPlaying()) p.pause();
                else if (!paused && !p.isPlaying()) p.start();
            }
        });
    }

    void release() {
        media.post(new Runnable() {
            @Override
            public void run() {
                active = NONE;
                media.removeCallbacks(watchdog);
                chainToken++;
                if (chainAudio != null) chainAudio.release();
                chainAudio = null;
                chainActive = false;
                for (int i = 1; i < CLIPS; i++) {
                    if (clips[i].player != null) clips[i].player.release();
                    clips[i].player = null;
                }
                media.getLooper().quitSafely();
            }
        });
    }
}
