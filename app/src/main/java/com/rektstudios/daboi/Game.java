package com.rektstudios.daboi;

import android.opengl.Matrix;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Port of gameControllerScript / controlWheel / settime / MoveTo and friends.
 * Runs on the GL thread; the HUD talks to it through {@link #post(Runnable)} and
 * reads the volatile UI fields.
 */
final class Game {
    /** Survive "scene reloads" (restart) like the C# statics did. */
    static boolean firstLaunch = true;
    static boolean muted;

    private static final float SHADES_AT = 10f, HAT_AT = 20f, BLUNT_AT = 30f;
    private static final float MOVE_SPEED = 4f / 0.009f;   // MoveTo: 4 m/s, in Dat Boi local units
    private static final float ANIM_SPEED = -0.65f;        // DatBoiController state speed * AnimSpeed
    private static final float DIE_HEIGHT = 0.4f;
    private static final float MAX_GAME_STEP = 1f / 20f;

    final Model model;
    final Sound sound;
    final Save save;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();

    Physics phys;
    float timeScale;
    boolean ded;

    // input
    volatile int touchAxis;
    private float keyTarget, keyAxis;

    /** instantiateThis.amountAlready: ruler ticks [0, amountAlready) exist. */
    int amountAlready;

    // gameControllerScript state
    float currentScore, maxScore, recordScore, distanceUnicycled;
    /**
     * Not in the original: the MLG record works like "ur record" (farthest forward),
     * but only runs that went back to -10 m count (and those runs no longer count
     * for "ur record"); the first such run unlocks it.
     */
    float minScore, mlgRecord;
    boolean mlgUnlocked;
    /**
     * Not in the original: past -30 m an MLG run is in Backflip mode, scored backwards
     * (its record is the farthest you went back); back on the near side of -30 m it is
     * in MLG mode again. The mode you die in decides which record the run counts for.
     */
    static final float BACKFLIP_AT = -30f;
    /** Half a metre of slack so wobbling right on the -30 m line does not flip the mode every frame. */
    private static final float BACKFLIP_SLACK = 0.5f;
    static final int MODE_NORMAL = 0, MODE_MLG = 1, MODE_BACKFLIP = 2;
    int mode;
    float backflipRecord;
    boolean backflipUnlocked;
    /**
     * Not in the original: the top speed (m/s) ever reached in the runs of each kind,
     * indexed by mode; a run counts for the same kind as its record. Like the records,
     * normal and MLG runs are timed going forwards, Backflip runs going backwards.
     */
    final float[] topSpeed = new float[3];
    /**
     * Not in the original: in Backflip mode the whole game (picture, clip and UI) is
     * mirrored left-right each time the -10 m song reaches 0:48 and upside down each
     * time it reaches 2:28; each cue flips it again, so it adds up over the repeats.
     * Leaving Backflip mode or restarting puts it back the right way.
     */
    volatile boolean mirrorX, mirrorY;
    private float runTopForward, runTopBackward;
    private float distanceLast;
    private boolean pastTen, pastTwen, pastThir;
    /**
     * Not in the original: the MLG clips ("1 MLG", "2 MLG", "3 MLG", then "Snoop Dogg"
     * on repeat) start over each time the -10 m song reaches 0:48 and 2:28, on every
     * repetition of the song.
     */
    static final int[] MLG_VIDEO_AT_SONG_MS = {48000, 148000};
    /** A backwards jump of the song position bigger than this is the song looping. */
    private static final int SONG_LOOP_JUMP_MS = 5000;
    /** Not in the original: a normal run (never at -10 m) plays its own clip at these marks. */
    static final float[] NORMAL_VIDEO_AT = {1000f, 2000f, 3000f, 10000f, 20000f, 30000f};
    /**
     * Clip to start (VideoBackground.NONE / MLG1 / NORMAL; "1 MLG" carries on by itself),
     * a counter bumped on every request, and the clip that may be asked for next.
     */
    volatile int videoClip, videoSeq, videoHint;
    private int lastSongMs;
    /** Repeats of the -10 m song so far (1 = first time through). */
    private int songLoop;

    // admin build only (Flavor.ADMIN): review tools
    /** The pole never brings dat boi down: he is stood back up instead. */
    volatile boolean invincible;
    /** The clip on screen, set by the renderer. */
    volatile int playingClip;
    /** One line with song time, repeat, mode and clip. */
    volatile String adminInfo = "";
    /** Song position the song was just made to jump to (ignored until it lands there). */
    private int seekingTo = -1;
    private int nextNormalVideo;
    /** Not in the original: going back to -10 m switches the music. */
    static final float SPECIAL_MUSIC_AT = -10f;
    private float ohShitCooldown;

    // animation / transforms (GL thread only)
    float animTime;
    private float animSpeed;
    final float[] poleWorld = new float[16];
    final float[] datBoiWorld = new float[16];
    final boolean[] accessoryActive = new boolean[4];
    final float[][] accessoryOffset = new float[4][3];
    private final float[] tmp = new float[16];
    private final float[] tmpV = new float[4];

    // blunt smoke (positions are relative to the emitter, like a local-space system)
    static final int MAX_SMOKE = 60;
    final float[] smoke = new float[MAX_SMOKE * 6];   // dx dy dz age life seed
    int smokeCount;
    private float smokeEmit;
    final float[] smokeOrigin = new float[3];

    // HUD state
    volatile boolean startVisible, faqVisible, panelVisible, scoreVisible;
    volatile String scoreText = "dat boi score";
    volatile String endScoreText = "Dis run:";
    volatile String endRecordText = "Ur Rekord is:";
    /** Empty until the MLG record is unlocked. */
    volatile String endMlgText = "";
    volatile String endBackflipText = "";
    /** Not in the original: a line shown on screen for a few seconds (real time). */
    volatile String bannerText = "";
    volatile long bannerUntil;
    static final long BANNER_MS = 4000;
    /** Shown every time the -10 m song ends. */
    private static final String[] SONG_END_LINES = {
            "o shit waddup!!1! da song endded but dat boi keeps cyclin",
            "u survivd da whole song m8\nMOM GET DA CAMERA",
            "DAT BOI STILL HOLDIN ON\n(his hope will nevr die)",
            "1 more time 4 da boiz",
            "da song is ovr but u didnt diededed yet?!?!",
            "2 dank 4 da song\nhere it comes again",
    };
    private int lastSongLine = -1;
    volatile String cycledText = "";

    Game(Model model, Sound sound, Save save) {
        this.model = model;
        this.sound = sound;
        this.save = save;
        loadScene();
    }

    void post(Runnable r) {
        queue.add(r);
    }

    /** SceneManager.LoadScene(0): Awake() + Start() of everything in the scene. */
    void loadScene() {
        phys = new Physics();
        recordScore = save.record();
        mlgUnlocked = save.mlgUnlocked();
        mlgRecord = save.mlgRecord();
        backflipUnlocked = save.backflipUnlocked();
        backflipRecord = save.backflipRecord();
        mode = MODE_NORMAL;
        bannerUntil = 0;
        distanceUnicycled = save.distance();
        distanceLast = 0;
        currentScore = maxScore = minScore = 0;
        runTopForward = runTopBackward = 0;
        mirrorX = mirrorY = false;
        for (int i = 0; i < 3; i++) topSpeed[i] = save.topSpeed(i);
        pastTen = pastTwen = pastThir = false;
        videoClip = VideoBackground.NONE;
        lastSongMs = -1;
        songLoop = 1;
        seekingTo = -1;
        nextNormalVideo = 0;
        sound.setSpecialMusic(false);
        animTime = 0;
        animSpeed = 0;
        amountAlready = 10;
        smokeCount = 0;
        for (int i = 0; i < 4; i++) accessoryActive[i] = false;
        for (Model.Accessory a : model.accessories) {
            float[] o = accessoryOffset[a.id];
            o[0] = o[1] = o[2] = 0;
        }
        ded = false;
        panelVisible = false;
        faqVisible = false;
        scoreVisible = true;
        scoreText = "dat boi score";
        endScoreText = "Dis run:";
        endRecordText = "Ur Rekord is:";
        endMlgText = "";
        endBackflipText = "";
        if (firstLaunch) {
            sound.playOhShit();
            timeScale = 0f;
            startVisible = true;
        } else {
            startVisible = false;   // settime.Awake hides everything not tagged "help"
        }
        sound.scheduleMusic();
        updateTransforms();
    }

    // ------------------------------------------------------------ UI actions
    void onStart() {           // "Start da Gaem"
        timeScale = 1f;
        startVisible = false;
    }

    void onHelp() {            // "help"
        timeScale = 0f;
        faqVisible = true;
    }

    void onK() {               // "K play da gaem"
        if (!ded || !firstLaunch) timeScale = 1f;
        if (timeScale != 0f) startVisible = false;   // disableingame
        sound.playK();
        faqVisible = false;
    }

    void onRestart() {
        loadScene();
    }

    void onToggleMute() {
        muted = !muted;
        sound.setPaused(muted);
    }

    /** Progress was imported from a file. */
    void onProgressImported() {
        recordScore = Math.max(recordScore, save.record());
        if (save.mlgUnlocked()) {
            mlgRecord = mlgUnlocked ? Math.max(mlgRecord, save.mlgRecord()) : save.mlgRecord();
            mlgUnlocked = true;
        }
        if (save.backflipUnlocked()) {
            backflipRecord = backflipUnlocked ? Math.max(backflipRecord, save.backflipRecord()) : save.backflipRecord();
            backflipUnlocked = true;
        }
        for (int i = 0; i < 3; i++) topSpeed[i] = Math.max(topSpeed[i], save.topSpeed(i));
        distanceUnicycled = Math.max(distanceUnicycled, save.distance());
    }

    private void playVideo(int clip) {
        videoClip = clip;
        videoSeq++;
    }

    /**
     * Restarts the MLG clips when the -10 m song crosses one of its cue points.
     * After death the song plays on but nothing else happens: the player must not
     * get to see what comes later without reaching it.
     */
    private void cueMlgVideos() {
        int pos = sound.specialMusicPositionMs();
        if (pos < 0) return;
        if (seekingTo >= 0) {
            // an admin jump: wait until the song is there, without firing anything on the way
            if (Math.abs(pos - seekingTo) > 1500) return;
            seekingTo = -1;
            lastSongMs = pos;
            return;
        }
        int prev = lastSongMs;
        if (prev - pos > SONG_LOOP_JUMP_MS) {   // the song looped
            prev = -1;
            songLoop++;
            if (!ded) onSongEnded();
        }
        lastSongMs = Math.max(prev, pos);
        if (ded) return;
        for (int i = 0; i < MLG_VIDEO_AT_SONG_MS.length; i++) {
            int cue = MLG_VIDEO_AT_SONG_MS[i];
            if (prev < cue && pos >= cue) {
                // the MLG clips are for MLG mode only; Backflip mode mirrors the game instead
                if (mode == MODE_BACKFLIP) {
                    if (i == 0) mirrorX = !mirrorX;
                    else mirrorY = !mirrorY;
                } else {
                    playVideo(VideoBackground.MLG1);
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------ admin build
    /** Jumps the -10 m song (only while it plays). */
    void adminSeek(int ms) {
        if (sound.specialMusicPositionMs() < 0) return;
        seekingTo = ms;
        sound.seekSpecialMusic(ms);
    }

    /** Puts dat boi upright at a distance (-11 m: MLG, -31 m: Backflip), standing still. */
    void adminTeleport(float metres) {
        if (ded) return;
        double delta = metres - currentScore;
        phys.x += delta;
        phys.v = 0;
        phys.wheelSpin = 0;
        phys.theta = 0;
        phys.omega = 0;
        distanceLast += (float) delta;   // a jump is not distance cycled
    }

    private void updateAdminInfo() {
        int pos = sound.specialMusicPositionMs();
        String song = pos < 0 ? "canzone -10 m: no"
                : String.format(java.util.Locale.US, "canzone %d:%04.1f giro %d", pos / 60000, (pos % 60000) / 1000f, songLoop);
        String m = mode == MODE_BACKFLIP ? "Backflip" : mode == MODE_MLG ? "MLG" : "normale";
        adminInfo = "ADMIN · " + song + "\n" + m + (ded ? " (morto)" : "") + " · "
                + VideoBackground.clipName(playingClip) + (mirrorX ? " · specchio" : "") + (mirrorY ? " · capovolto" : "");
    }

    private void onSongEnded() {
        int i = random.nextInt(SONG_END_LINES.length - 1);
        if (i >= lastSongLine && lastSongLine >= 0) i++;   // never the same line twice in a row
        lastSongLine = i;
        showBanner(SONG_END_LINES[i]);
    }

    private void showBanner(String text) {
        bannerText = text;
        bannerUntil = android.os.SystemClock.uptimeMillis() + BANNER_MS;
    }

    void onKey(int dir, boolean down) {
        if (down) keyTarget = dir;
        else if (keyTarget == dir) keyTarget = 0;
    }

    // ------------------------------------------------------------ frame
    void update(float realDt) {
        Runnable r;
        while ((r = queue.poll()) != null) r.run();

        realDt = Math.min(realDt, 1f / 3f);   // Unity "Maximum Allowed Timestep"
        // Unlike Unity, a slow frame (a hiccup) is not caught up in one big step with
        // stale input: game time slows down instead, so a stutter can't cost the run.
        float dt = Math.min(realDt, MAX_GAME_STEP) * timeScale;
        sound.update(realDt);

        // Input.GetAxis("Horizontal") keyboard smoothing (sensitivity 3, gravity 3, snap)
        if (keyTarget != 0 && Math.signum(keyAxis) == -keyTarget) keyAxis = 0;
        float stepK = 3f * dt;
        if (keyAxis < keyTarget) keyAxis = Math.min(keyTarget, keyAxis + stepK);
        else if (keyAxis > keyTarget) keyAxis = Math.max(keyTarget, keyAxis - stepK);

        float axis = ded ? 0f : keyAxis + touchAxis;
        phys.advance(dt, axis);

        if (!ded) {
            // controlWheel: anim.SetFloat("AnimSpeed", angularVelocity.z); Unity +z spins backwards
            animSpeed = (float) -phys.wheelSpin;
        }
        animTime += dt * ANIM_SPEED * animSpeed;
        animTime %= model.clipLength;
        if (animTime < 0) animTime += model.clipLength;

        while (phys.x >= amountAlready - 10) amountAlready += 10;
        cueMlgVideos();
        if (Flavor.ADMIN) updateAdminInfo();
        moveAccessories(dt);
        updateTransforms();
        updateSmoke(dt);

        if (ded) return;

        currentScore = progressX();
        distanceUnicycled += Math.abs(currentScore - distanceLast);
        distanceLast = currentScore;
        if (currentScore >= maxScore) maxScore = currentScore;
        if (currentScore <= minScore) minScore = currentScore;
        runTopForward = Math.max(runTopForward, (float) phys.v);
        runTopBackward = Math.max(runTopBackward, (float) -phys.v);
        updateMode();
        boolean mlgRun = mode != MODE_NORMAL;
        videoHint = mlgRun ? VideoBackground.MLG1
                : nextNormalVideo < NORMAL_VIDEO_AT.length ? VideoBackground.NORMAL : VideoBackground.NONE;
        if (!mlgRun && nextNormalVideo < NORMAL_VIDEO_AT.length
                && currentScore > NORMAL_VIDEO_AT[nextNormalVideo]) {
            while (nextNormalVideo < NORMAL_VIDEO_AT.length && currentScore > NORMAL_VIDEO_AT[nextNormalVideo]) {
                nextNormalVideo++;
            }
            playVideo(VideoBackground.NORMAL);
        }
        // the normal mode clip is not for MLG runs
        if (mlgRun && videoClip == VideoBackground.NORMAL) videoClip = VideoBackground.NONE;
        if (currentScore > SHADES_AT && !pastTen) {
            pastTen = true;
            accessoryActive[1] = true;
        }
        if (currentScore > HAT_AT && !pastTwen) {
            pastTwen = true;
            accessoryActive[2] = true;
        }
        if (currentScore <= SPECIAL_MUSIC_AT) sound.setSpecialMusic(true);
        if (currentScore > BLUNT_AT && !pastThir) {
            pastThir = true;
            accessoryActive[3] = true;
        }
        // Sound_DatBoi.Play() every frame around a milestone (restarts the clip).
        ohShitCooldown -= realDt;
        if (currentScore > 5f && isOhShitMark(Math.round(currentScore)) && ohShitCooldown <= 0) {
            sound.playOhShit();
            ohShitCooldown = 1f / 30f;
        }
        scoreText = cropDistance(currentScore);
        cycledText = "Ur boi cycled \n" + cropDistance(distanceUnicycled) + " in total";
        if (phys.poleCenterY() <= DIE_HEIGHT) {
            if (Flavor.ADMIN && invincible) {
                phys.theta = 0;   // stood back up
                phys.omega = 0;
            } else {
                die();
            }
        }
    }

    /** Normal -> MLG at -10 m; then Backflip past -30 m and MLG again on this side of it. */
    private void updateMode() {
        if (mode == MODE_NORMAL && minScore <= SPECIAL_MUSIC_AT) mode = MODE_MLG;
        if (mode == MODE_MLG && currentScore < BACKFLIP_AT) {
            mode = MODE_BACKFLIP;
            if (videoClip != VideoBackground.NONE) playVideo(VideoBackground.NONE);
            showBanner("BACKFLIP MODE\nACTIVATED!!1!");
        } else if (mode == MODE_BACKFLIP && currentScore > BACKFLIP_AT + BACKFLIP_SLACK) {
            mode = MODE_MLG;
            mirrorX = mirrorY = false;
            showBanner("MLG MODE\nACTIVATED!!1!");
        }
    }

    /** 10, 20, 30, 100, 200, 300, 1000, 2000, 3000, ... (the original played it every 10 m). */
    static boolean isOhShitMark(int metres) {
        if (metres < 10) return false;
        int zeros = 0;
        while (metres % 10 == 0) {
            metres /= 10;
            zeros++;
        }
        return zeros >= 1 && metres >= 1 && metres <= 3;
    }

    private void die() {
        ded = true;
        panelVisible = true;
        // only "Snoop Dogg" behind a dead MLG run (its clip sound stops, the song goes
        // on); a dead normal run stops its clip; a dead Backflip run keeps its mirroring
        if (mode == MODE_MLG) playVideo(VideoBackground.SNOOP);
        else if (mode == MODE_NORMAL && videoClip != VideoBackground.NONE) playVideo(VideoBackground.NONE);
        // A run is only one of normal, MLG or Backflip: a run that went back to -10 m
        // counts for the MLG record (scored forwards), one that ends past -30 m
        // in Backflip mode for the Backflip record (scored backwards), every other
        // run only for "ur record".
        if (mode == MODE_BACKFLIP) {
            if (!backflipUnlocked || -minScore > backflipRecord) backflipRecord = -minScore;
            backflipUnlocked = true;
            save.storeBackflip(backflipRecord);
        } else if (mode == MODE_MLG) {
            if (!mlgUnlocked || maxScore > mlgRecord) mlgRecord = maxScore;
            mlgUnlocked = true;
            save.storeMlg(mlgRecord);
        } else if (maxScore > recordScore) {
            recordScore = maxScore;
        }
        float runTop = mode == MODE_BACKFLIP ? runTopBackward : runTopForward;
        if (runTop > topSpeed[mode]) {
            topSpeed[mode] = runTop;
            save.storeTopSpeed(mode, runTop);
        }
        firstLaunch = false;
        save.store(recordScore, distanceUnicycled);
        endRecordText = "ur record: " + cropDistance(recordScore) + speedText(MODE_NORMAL);
        if (mlgUnlocked) endMlgText = "MLG: " + cropDistance(mlgRecord) + speedText(MODE_MLG);
        if (backflipUnlocked) endBackflipText = "Backflip: " + cropDistance(backflipRecord) + speedText(MODE_BACKFLIP);
        endScoreText = "Dat Run: " + cropDistance(currentScore);
        scoreVisible = false;
    }

    /** "  (12.3 km/h)", or nothing before the first run of that kind. */
    private String speedText(int mode) {
        float s = topSpeed[mode];
        return s > 0 ? String.format(java.util.Locale.US, "  (%.1f km/h)", s * 3.6f) : "";
    }

    String shareText() {
        return "O shit my boiiii got " + cropDistance(recordScore) + " far!!!!!!!!1!! "
                + "Can you beat dat boiiiii???!!??!? Control dat boi with yer touchscreen and get some o shit moments! "
                + "If iz too hardcore 4 u are not da real boiii.";
    }

    // ------------------------------------------------------------ helpers
    private void moveAccessories(float dt) {
        float maxStep = MOVE_SPEED * dt;
        for (Model.Accessory a : model.accessories) {
            if (!accessoryActive[a.id]) continue;
            float[] o = accessoryOffset[a.id];
            float dx = a.target[0] - (a.start[0] + o[0]);
            float dy = a.target[1] - (a.start[1] + o[1]);
            float dz = a.target[2] - (a.start[2] + o[2]);
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= maxStep || d == 0) {
                o[0] += dx;
                o[1] += dy;
                o[2] += dz;
            } else {
                float k = maxStep / d;
                o[0] += dx * k;
                o[1] += dy * k;
                o[2] += dz * k;
            }
        }
    }

    private void updateTransforms() {
        // balanceThing: hinge at the wheel centre, tilted by theta towards +x
        Matrix.setIdentityM(poleWorld, 0);
        Matrix.translateM(poleWorld, 0, (float) phys.poleCenterX(), (float) phys.poleCenterY(), 0f);
        Matrix.rotateM(poleWorld, 0, (float) -Math.toDegrees(phys.theta), 0f, 0f, 1f);
        Matrix.scaleM(poleWorld, 0, (float) Physics.POLE_W, (float) Physics.POLE_H, 0.13654594123363495f);
        // Dat Boi hangs off the pole through a FixedJoint
        Matrix.multiplyMM(datBoiWorld, 0, poleWorld, 0, model.poleToDatBoi, 0);
        // the smoke emitter sits on the blunt tip, which flies in like the other accessories
        Model.Accessory blunt = accessory(3);
        float[] o = accessoryOffset[3];
        float[] s = model.smokeOrigin;
        transform(datBoiWorld,
                s[0] + blunt.start[0] + o[0] - blunt.target[0],
                s[1] + blunt.start[1] + o[1] - blunt.target[1],
                s[2] + blunt.start[2] + o[2] - blunt.target[2], smokeOrigin);
    }

    float progressX() {
        float[] p = model.progressMarker;
        transform(datBoiWorld, p[0], p[1], p[2], tmpV);
        return tmpV[0];
    }

    private void transform(float[] m, float x, float y, float z, float[] out) {
        tmpV[0] = x;
        tmpV[1] = y;
        tmpV[2] = z;
        tmpV[3] = 1;
        Matrix.multiplyMV(tmp, 0, m, 0, tmpV, 0);
        out[0] = tmp[0];
        out[1] = tmp[1];
        out[2] = tmp[2];
    }

    private void updateSmoke(float dt) {
        // age / move existing puffs
        for (int i = 0; i < smokeCount; ) {
            int k = i * 6;
            smoke[k + 3] += dt;
            if (smoke[k + 3] >= smoke[k + 4]) {
                smokeCount--;
                System.arraycopy(smoke, smokeCount * 6, smoke, k, 6);
                continue;
            }
            float seed = smoke[k + 5];
            smoke[k] += (0.05f + 0.1f * seed) * dt;
            smoke[k + 1] += (0.35f + 0.2f * seed) * dt;
            smoke[k + 2] += (seed - 0.5f) * 0.2f * dt;
            i++;
        }
        if (!accessoryActive[3]) return;
        smokeEmit += dt * 10.76f;
        while (smokeEmit >= 1f && smokeCount < MAX_SMOKE) {
            smokeEmit -= 1f;
            int k = smokeCount * 6;
            smoke[k] = (random.nextFloat() - 0.5f) * 0.04f;
            smoke[k + 1] = 0;
            smoke[k + 2] = (random.nextFloat() - 0.5f) * 0.04f;
            smoke[k + 3] = 0;
            smoke[k + 4] = 3.5f + random.nextFloat();
            smoke[k + 5] = random.nextFloat();
            smokeCount++;
        }
        if (smokeEmit > 1f) smokeEmit = 1f;
    }

    Model.Accessory accessory(int id) {
        for (Model.Accessory a : model.accessories) if (a.id == id) return a;
        throw new IllegalArgumentException("accessory " + id);
    }

    /** Model frame (with blend factor) for the pedalling clip. */
    float animFrame() {
        return animTime / model.clipLength * model.frames;
    }

    /** C#: (float)Math.Round(dist, 2) + "m" (float.ToString keeps 7 significant digits). */
    static String cropDistance(float dist) {
        if (Float.isNaN(dist) || Float.isInfinite(dist)) return dist + "m";
        BigDecimal d = new BigDecimal(Math.round((double) dist * 100.0) / 100.0)
                .round(new MathContext(7, RoundingMode.HALF_EVEN))
                .setScale(2, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
        if (d.signum() == 0) return "0m";
        return d.toPlainString() + "m";
    }
}
