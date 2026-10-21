package com.rektstudios.daboi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;

/** The four AudioSources of the original scene. */
final class Sound {
    private static final String TAG = "DatBoi";
    private static final float MUSIC_DELAY = 1.516f;   // gameControllerScript.musictimer

    private final SoundPool pool;
    private final int hitmarker, ohShit, k;
    private int ohShitStream;
    private volatile boolean ohShitLoaded, ohShitPending;
    private MediaPlayer music;         // media thread only
    private final Context context;
    /** Every MediaPlayer call runs here, so preparing a track never blocks the game or the touches. */
    private final Handler media = MediaThread.get();
    private boolean musicStarted;      // static playingmusic in the original
    private float musicTimer = -1f;
    private volatile boolean paused, appPaused;
    private volatile MediaPlayer special;   // created/released on the media thread: the -10 m track
    private volatile boolean specialOn;

    Sound(Context context) {
        this.context = context;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (sampleId == ohShit && status == 0) {
                    ohShitLoaded = true;
                    // the very first "oh shit waddup" is requested before loading finishes
                    if (ohShitPending) playOhShit();
                }
            }
        });
        hitmarker = load("hitmarker.ogg");
        ohShit = load("ohshit.ogg");
        k = load("k.ogg");
    }

    private int load(String name) {
        try {
            AssetFileDescriptor fd = context.getAssets().openFd(name);
            int id = pool.load(fd, 1);
            fd.close();
            return id;
        } catch (IOException e) {
            Log.e(TAG, "cannot load " + name, e);
            return 0;
        }
    }

    /** Called on every scene load; music only ever starts once per process. */
    void scheduleMusic() {
        if (!musicStarted && musicTimer < 0) musicTimer = MUSIC_DELAY;
    }

    void update(float realDt) {
        if (musicTimer >= 0 && !musicStarted) {
            musicTimer -= realDt;
            if (musicTimer < 0) {
                musicStarted = true;
                media.post(new Runnable() {
                    @Override
                    public void run() {
                        startMusic();
                    }
                });
            }
        }
    }

    private void startMusic() {
        try {
            AssetFileDescriptor fd = context.getAssets().openFd("music.ogg");
            music = new MediaPlayer();
            music.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            music.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            music.setLooping(true);
            music.prepare();
            applyMusic();
        } catch (IOException e) {
            Log.e(TAG, "cannot start music", e);
        }
    }

    void playHitmarker() {
        if (!paused) pool.play(hitmarker, 1f, 1f, 1, 0, 1f);
    }

    void playK() {
        if (!paused) pool.play(k, 1f, 1f, 1, 0, 1f);
    }

    /** AudioSource.Play() on an already playing source restarts it. */
    synchronized void playOhShit() {
        if (paused) return;
        if (!ohShitLoaded) {
            ohShitPending = true;
            return;
        }
        ohShitPending = false;
        if (ohShitStream != 0) pool.stop(ohShitStream);
        ohShitStream = pool.play(ohShit, 0.6f, 0.6f, 1, 0, 0.97f);
    }

    /**
     * Not in the original: at -10 m the background music is swapped for the
     * "I Keep Holding On" track until the next restart.
     */
    void setSpecialMusic(boolean on) {
        if (specialOn == on) return;
        specialOn = on;
        media.post(new Runnable() {
            @Override
            public void run() {
                if (specialOn && special == null) special = createPlayer("minus10.ogg");
                if (!specialOn && special != null) {
                    special.pause();
                    special.seekTo(0);
                }
                applyMusic();
            }
        });
    }

    /** Playback position of the -10 m song, or -1 when it is not playing. */
    int specialMusicPositionMs() {
        MediaPlayer p = special;
        if (!specialOn || p == null) return -1;
        try {
            return p.getCurrentPosition();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    private MediaPlayer createPlayer(String asset) {
        try {
            AssetFileDescriptor fd = context.getAssets().openFd(asset);
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            p.setLooping(true);
            p.prepare();
            return p;
        } catch (IOException e) {
            Log.e(TAG, "cannot load " + asset, e);
            return null;
        }
    }

    /** Media thread: play exactly one of the two music tracks (or none when silent). */
    private void applyMusic() {
        boolean silent = paused || appPaused;
        boolean useSpecial = specialOn && special != null;
        if (music != null) {
            boolean want = !silent && !useSpecial;
            if (want && !music.isPlaying()) music.start();
            else if (!want && music.isPlaying()) music.pause();
        }
        if (special != null) {
            // muted, the song keeps running silently so the 0:48 video cue still fires
            boolean want = !appPaused && useSpecial;
            float v = paused ? 0f : 1f;
            special.setVolume(v, v);
            if (want && !special.isPlaying()) special.start();
            else if (!want && special.isPlaying()) special.pause();
        }
    }

    /** AudioListener.pause */
    void setPaused(boolean p) {
        paused = p;
        apply();
    }

    void onAppPause(boolean p) {
        appPaused = p;
        apply();
    }

    private void apply() {
        boolean silent = paused || appPaused;
        if (silent) pool.autoPause(); else pool.autoResume();
        media.post(new Runnable() {
            @Override
            public void run() {
                applyMusic();
            }
        });
    }

    void release() {
        pool.release();
        media.post(new Runnable() {
            @Override
            public void run() {
                if (music != null) music.release();
                if (special != null) special.release();
                music = null;
                special = null;
            }
        });
    }
}
