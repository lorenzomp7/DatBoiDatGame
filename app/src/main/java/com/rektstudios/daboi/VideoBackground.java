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
 * The clips that play behind dat boi: "1 MLG" -> "2 MLG" -> "3 MLG" -> "Snoop Dogg"
 * on repeat in MLG mode, and the normal-mode clip at 1000/2000/3000/10000/20000/30000 m.
 * MediaPlayer decodes into a SurfaceTexture (an external OES texture); the green is
 * keyed out in the shader so the sky stays visible behind the clip.
 *
 * Two players take turns: while one clip plays, the clip that comes next is already
 * prepared in the other one and starts the moment it is needed, and the last frame of
 * the old clip stays on screen until the new one has its first frame, so there is
 * never a gap between clips.
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
    /** "Snoop Dogg" repeats until it is replaced; every other clip plays once. */
    private static final boolean[] LOOP = {false, false, false, false, false, true};
    /** The clip that follows each one as soon as it ends. */
    private static final int[] AUTO_NEXT = {NONE, MLG2, NONE, MLG3, SNOOP, NONE};
    /** The clip to keep ready while each one plays (NONE: the game's hint). "Snoop Dogg" is cut by "1 MLG". */
    private static final int[] PRELOAD_AFTER = {NONE, MLG2, NONE, MLG3, SNOOP, MLG1};

    private final Context context;
    private final Handler media = MediaThread.get();

    private static final class Slot {
        // GL thread
        SurfaceTexture surfaceTexture;
        int tex;
        final float[] texMatrix = new float[16];
        volatile boolean frameAvailable;
        // media thread
        MediaPlayer player;
        Surface surface;
        int clip = NONE;
        boolean ready;     // prepared and not started yet
    }

    private final Slot[] slots = {new Slot(), new Slot()};

    // GL thread
    private int requestedClip, requestedSeq, lastHint = -1;
    private boolean lastMute;
    private int shown = -1, shownClip = NONE, shownGen = -1;
    private boolean shownFinished;

    /**
     * Published by the media thread in one value so the GL thread sees a consistent
     * snapshot: bits 0-3 clip, 4-5 slot + 1 (0: nothing), 6 finished, 8+ start counter.
     */
    private volatile int state;

    // media thread
    private int active = -1, gen, hint = NONE;
    private boolean appPaused, muted, activeFinished;
    /** Watchdog: a clip whose position stops moving is skipped instead of freezing the chain. */
    private static final long WATCHDOG_MS = 500, STUCK_MS = 2000;
    private int watchedGen = -1, lastPos = -1;
    private long stuckFor;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkStuck();
        }
    };

    VideoBackground(Context context) {
        this.context = context;
    }

    /** GL thread, after (re)creating the GL context. */
    void onSurfaceCreated() {
        int[] ids = new int[2];
        GLES20.glGenTextures(2, ids, 0);
        for (int i = 0; i < 2; i++) {
            final Slot slot = slots[i];
            slot.tex = ids[i];
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, slot.tex);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            final SurfaceTexture old = slot.surfaceTexture;
            slot.surfaceTexture = new SurfaceTexture(slot.tex);
            slot.surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture st) {
                    slot.frameAvailable = true;
                }
            });
            final Surface s = new Surface(slot.surfaceTexture);
            media.post(new Runnable() {
                @Override
                public void run() {
                    Surface previous = slot.surface;
                    slot.surface = s;
                    if (slot.player != null) slot.player.setSurface(s);
                    if (previous != null) previous.release();
                    if (old != null) old.release();
                }
            });
        }
        // the new textures are empty: wait for the next frame before showing anything
        shown = -1;
        shownGen = -1;
    }

    /**
     * GL thread, every frame: request clip changes and latch the newest frame.
     * {@code hint} is the clip the game may ask for next, kept prepared when nothing
     * else has to be.
     */
    void update(int clip, int seq, final int hint, final boolean mute) {
        if (clip != requestedClip || seq != requestedSeq) {
            requestedClip = clip;
            requestedSeq = seq;
            final int c = clip;
            media.post(new Runnable() {
                @Override
                public void run() {
                    play(c);
                }
            });
        }
        if (hint != lastHint) {
            lastHint = hint;
            media.post(new Runnable() {
                @Override
                public void run() {
                    VideoBackground.this.hint = hint;
                    preloadSpare();
                }
            });
        }
        if (mute != lastMute) {
            lastMute = mute;
            media.post(new Runnable() {
                @Override
                public void run() {
                    muted = mute;
                    for (Slot s : slots) {
                        if (s.player != null) s.player.setVolume(mute ? 0f : 1f, mute ? 0f : 1f);
                    }
                }
            });
        }
        int st = state;
        int playing = ((st >> 4) & 3) - 1;
        int g = st >>> 8;
        if (playing < 0) {
            shown = -1;
            return;
        }
        Slot s = slots[playing];
        if (s.frameAvailable && s.surfaceTexture != null) {
            s.frameAvailable = false;
            s.surfaceTexture.updateTexImage();
            s.surfaceTexture.getTransformMatrix(s.texMatrix);
            if (g != shownGen) {
                // the new clip has its first frame: switch to it (until now the old one stayed)
                shown = playing;
                shownGen = g;
                shownClip = st & 15;
            }
        }
        if (g == shownGen) shownFinished = (st & 64) != 0;
    }

    boolean visible() {
        return shown >= 0 && !shownFinished;
    }

    /** Whether the clip on screen is a green screen clip. */
    boolean keyed() {
        return KEYED[shownClip];
    }

    int texture() {
        return slots[Math.max(shown, 0)].tex;
    }

    /** Texture target of {@link #texture()} (an external OES texture). */
    boolean external() {
        return true;
    }

    float[] texMatrix() {
        return slots[Math.max(shown, 0)].texMatrix;
    }

    // ---------------------------------------------------------------- media thread
    private void publish(int slot, int clip, boolean finished) {
        state = (gen << 8) | (finished ? 64 : 0) | ((slot + 1) << 4) | clip;
    }

    private void play(int clip) {
        if (clip == NONE) {
            if (active >= 0) pause(slots[active]);
            active = -1;
            gen++;
            publish(-1, NONE, false);
            preloadSpare();
            return;
        }
        int s = -1;
        for (int i = 0; i < 2; i++) {
            if (slots[i].ready && slots[i].clip == clip) s = i;
        }
        if (s < 0) {
            s = active >= 0 ? 1 - active : 0;
            if (!load(slots[s], clip, s)) {
                // the clip cannot play: hide the old one rather than freezing on it
                if (active >= 0) {
                    pause(slots[active]);
                    activeFinished = true;
                    publish(active, slots[active].clip, true);
                }
                return;
            }
        }
        if (active >= 0 && active != s) pause(slots[active]);
        Slot slot = slots[s];
        slot.ready = false;
        slot.frameAvailable = false;   // anything queued so far is not the new clip
        slot.player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        if (!appPaused) slot.player.start();
        active = s;
        activeFinished = false;
        gen++;
        publish(s, clip, false);
        watchedGen = gen;
        lastPos = -1;
        stuckFor = 0;
        media.removeCallbacks(watchdog);
        media.postDelayed(watchdog, WATCHDOG_MS);
        // prepare the clip after this one in the other player, after anything queued
        media.post(new Runnable() {
            @Override
            public void run() {
                preloadSpare();
            }
        });
    }

    private void onCompletion(int s) {
        if (s != active) return;
        int next = AUTO_NEXT[slots[s].clip];
        if (next != NONE) {
            play(next);
        } else {
            activeFinished = true;
            publish(s, slots[s].clip, true);
            preloadSpare();
        }
    }

    private void checkStuck() {
        if (active < 0 || activeFinished || watchedGen != gen) return;
        Slot slot = slots[active];
        if (!appPaused && slot.player != null) {
            int pos;
            try {
                pos = slot.player.getCurrentPosition();
            } catch (IllegalStateException e) {
                pos = lastPos;
            }
            stuckFor = pos == lastPos ? stuckFor + WATCHDOG_MS : 0;
            lastPos = pos;
            if (stuckFor >= STUCK_MS) {
                Log.w(TAG, ASSETS[slot.clip] + " stopped at " + pos + " ms, moving on");
                stuckFor = 0;
                if (LOOP[slot.clip]) {
                    // a loop that stalls: start it over in a fresh player
                    slot.ready = false;
                    int clip = slot.clip;
                    activeFinished = true;
                    if (load(slot, clip, active)) play(clip);
                    return;
                }
                onCompletion(active);
                return;
            }
        }
        media.postDelayed(watchdog, WATCHDOG_MS);
    }

    /** Keeps the clip that may be needed next prepared in the player that is not playing. */
    private void preloadSpare() {
        int spare = active >= 0 ? 1 - active : 0;
        int want = active >= 0 && !activeFinished ? PRELOAD_AFTER[slots[active].clip] : NONE;
        if (want == NONE) want = hint;
        if (want == NONE) return;
        for (Slot s : slots) {
            if (s.ready && s.clip == want) return;
        }
        load(slots[spare], want, spare);
    }

    private boolean load(Slot slot, int clip, final int index) {
        try {
            // always a fresh player: reusing one that was cut off mid-clip (the looping
            // "Snoop Dogg" at 2:28) could leave the next clip stuck on its first frames
            if (slot.player != null) slot.player.release();
            slot.player = null;
            slot.ready = false;
            slot.clip = NONE;
            slot.player = new MediaPlayer();
            slot.player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            AssetFileDescriptor fd = context.getAssets().openFd(ASSETS[clip]);
            slot.player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            slot.player.setLooping(LOOP[clip]);
            slot.player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp == slots[index].player) VideoBackground.this.onCompletion(index);
                }
            });
            slot.player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "video error " + what + "/" + extra);
                    if (mp != slots[index].player) return true;
                    slots[index].ready = false;
                    // a broken clip ends early instead of freezing the chain
                    VideoBackground.this.onCompletion(index);
                    return true;
                }
            });
            slot.player.setSurface(slot.surface);
            slot.player.prepare();
            slot.clip = clip;
            slot.ready = true;
            return true;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "cannot load " + ASSETS[clip], e);
            return false;
        }
    }

    private static void pause(Slot slot) {
        if (slot.player != null && slot.player.isPlaying()) slot.player.pause();
    }

    /** Main thread: activity paused / resumed. */
    void onAppPause(final boolean paused) {
        media.post(new Runnable() {
            @Override
            public void run() {
                appPaused = paused;
                if (active < 0 || activeFinished) return;
                MediaPlayer p = slots[active].player;
                if (paused && p.isPlaying()) p.pause();
                else if (!paused && !p.isPlaying()) p.start();
            }
        });
    }

    void release() {
        media.post(new Runnable() {
            @Override
            public void run() {
                active = -1;
                media.removeCallbacks(watchdog);
                for (Slot s : slots) {
                    if (s.player != null) s.player.release();
                    s.player = null;
                    s.ready = false;
                    s.clip = NONE;
                }
            }
        });
    }
}
