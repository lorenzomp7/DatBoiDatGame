package com.rektstudios.daboi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity implements HudView.Actions {
    private GLSurfaceView glView;
    private HudView hud;
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2;

    private Game game;
    private Sound sound;
    private VideoBackground video;
    private Save save;

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
        save = new Save(this);
        game = new Game(model, sound, save);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setPreserveEGLContextOnPause(true);
        video = new VideoBackground(this);
        glView.setRenderer(new GameRenderer(this, game, video));

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
        video.onAppPause(false);
        sound.onAppPause(false);
    }

    @Override
    protected void onPause() {
        sound.onAppPause(true);
        video.onAppPause(true);
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sound.release();
        video.release();
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

    // export / import of the saved progress (Storage Access Framework, no permissions)
    @Override
    public void exportProgress() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "datboi-progress.json");
        startForResult(i, REQ_EXPORT);
    }

    @Override
    public void importProgress() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startForResult(i, REQ_IMPORT);
    }

    private void startForResult(Intent i, int code) {
        try {
            startActivityForResult(i, code);
        } catch (RuntimeException e) {
            toast("no file manager found");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                try {
                    out.write(save.exportJson().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                toast("progress exported");
            } else if (requestCode == REQ_IMPORT) {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0 && buf.size() < 65536) buf.write(b, 0, n);
                } finally {
                    in.close();
                }
                float record = save.importJson(buf.toString("UTF-8"));
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onProgressImported();
                    }
                });
                toast("progress imported! ur record: " + Game.cropDistance(record));
            }
        } catch (Exception e) {
            toast(requestCode == REQ_EXPORT ? "export failed" : "dat file is not dat boi progress");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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
