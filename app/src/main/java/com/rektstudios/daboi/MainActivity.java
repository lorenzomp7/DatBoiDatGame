package com.rektstudios.daboi;

import android.app.Activity;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;

public final class MainActivity extends Activity implements HudView.Actions {
    private GLSurfaceView glView;
    private HudView hud;
    private Game game;
    private Sound sound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            // let the game draw under display cutouts; the HUD is inset instead
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            try {
                lp.getClass().getField("layoutInDisplayCutoutMode").setInt(lp, 1 /* SHORT_EDGES */);
                getWindow().setAttributes(lp);
            } catch (ReflectiveOperationException ignored) {
                // not available
            }
        }

        Model model;
        try {
            model = Model.load(getAssets());
        } catch (IOException e) {
            throw new RuntimeException("cannot load model", e);
        }
        sound = new Sound(this);
        game = new Game(model, sound, new Save(this));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(new GameRenderer(this, game));

        hud = new HudView(this, game, sound, this);
        FrameLayout root = new FrameLayout(this);
        root.addView(glView);
        root.addView(hud);
        setContentView(root);

        hud.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                applyCutout(insets);
                return insets;
            }
        });
        hideSystemUi();
    }

    /** Keeps the HUD out of camera notches (WindowInsets#getDisplayCutout, API 28). */
    private void applyCutout(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            Object cutout = insets.getClass().getMethod("getDisplayCutout").invoke(insets);
            if (cutout == null) {
                hud.setInsets(0, 0, 0);
                return;
            }
            int l = (Integer) cutout.getClass().getMethod("getSafeInsetLeft").invoke(cutout);
            int t = (Integer) cutout.getClass().getMethod("getSafeInsetTop").invoke(cutout);
            int r = (Integer) cutout.getClass().getMethod("getSafeInsetRight").invoke(cutout);
            hud.setInsets(l, t, r);
        } catch (ReflectiveOperationException ignored) {
            // keep zero insets
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        sound.onAppPause(false);
    }

    @Override
    protected void onPause() {
        sound.onAppPause(true);
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sound.release();
        super.onDestroy();
    }

    // Input.GetAxis("Horizontal") from a keyboard / gamepad
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int dir = keyDir(keyCode);
        if (dir != 0) {
            postKey(dir, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int dir = keyDir(keyCode);
        if (dir != 0) {
            postKey(dir, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static int keyDir(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                return -1;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                return 1;
            default:
                return 0;
        }
    }

    private void postKey(final int dir, final boolean down) {
        game.post(new Runnable() {
            @Override
            public void run() {
                game.onKey(dir, down);
            }
        });
    }

    // shareonfb: the Facebook SDK is replaced by the system share sheet
    @Override
    public void share(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "share on dat facebuk?"));
        } catch (RuntimeException ignored) {
            // no app can handle it
        }
    }
}
