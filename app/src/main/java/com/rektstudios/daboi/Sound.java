package com.rektstudios.daboi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
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
    private MediaPlayer music;         // main thread only
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean musicStarted;      // static playingmusic in the original
    private float musicTimer = -1f;
    private volatile boolean paused, appPaused;

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
                main.post(new Runnable() {
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
            if (!paused && !appPaused) music.start();
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
        main.post(new Runnable() {
            @Override
            public void run() {
                if (music == null) return;
                boolean silentNow = paused || appPaused;
                if (silentNow && music.isPlaying()) music.pause();
                else if (!silentNow && !music.isPlaying()) music.start();
            }
        });
    }

    /** Main thread. */
    void release() {
        pool.release();
        if (music != null) music.release();
        music = null;
    }
}
