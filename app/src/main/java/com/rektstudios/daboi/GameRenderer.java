package com.rektstudios.daboi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws the 3D scene; also drives {@link Game#update(float)} once per frame. */
final class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "DatBoi";

    // Main Camera: followTarget(PushWheel, offset (2.5, 1, 2.5)), fixed rotation, fov 60
    private static final float[] CAM_OFFSET = {2.5f, 1f, -2.5f};
    private static final float[] CAM_FORWARD = {-0.60413f, 0f, 0.79689f};
    // Directional Light (pointing towards the light), colour * intensity 0.7
    private static final float[] LIGHT_DIR = {0.32139f, 0.76604f, 0.55667f};
    private static final float[] LIGHT_COL = {0.992f * 0.7f, 0.941f * 0.7f, 0.733f * 0.7f};
    private static final float[] AMB_SKY = {0.29f, 0.31f, 0.36f};
    private static final float[] AMB_GROUND = {0.13f, 0.13f, 0.13f};
    private static final float[] FOG = {1f, 1f, 1f};
    private static final float TEXT_UNITS = 0.1f;     // TextMesh: world units per font pixel
    private static final float TEXT_SCALE = 0.019601386f;
    private static final float[] RED = {1f, 0f, 0f, 1f};
    private static final float[] BLACK = {0f, 0f, 0f, 1f};
    private static final float[] WHITE = {1f, 1f, 1f, 1f};

    private final Context context;
    private final Game game;
    private final Typeface textFont;

    private int litProg, skinProg, texProg, skyProg;
    private int cubeVbo, quadVbo, groundVbo;
    private int smokeTex;
    private final Map<Integer, TextTex> labels = new HashMap<>();
    private TextTex cycled;
    private String cycledShown;
    private long cycledTime;

    private final float[] proj = new float[16], view = new float[16], vp = new float[16], invVp = new float[16];
    private final float[] model = new float[16], tmp = new float[16], tmp2 = new float[16];
    private final float[] normalMat = new float[9];
    private final float[] cam = new float[3];
    private final float[] bonePalette;
    private int width = 1, height = 1;
    private long lastNanos;

    GameRenderer(Context context, Game game) {
        this.context = context;
        this.game = game;
        this.bonePalette = new float[game.model.boneCount * 16];
        Typeface tf;
        try {
            tf = Typeface.createFromAsset(context.getAssets(), "coolvetica.ttf");
        } catch (RuntimeException e) {
            tf = Typeface.SANS_SERIF;
        }
        textFont = tf;
    }

    // ------------------------------------------------------------------ setup
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        labels.clear();
        cycled = null;
        cycledShown = null;
        litProg = program(LIT_VS, LIT_FS);
        skinProg = program(SKIN_VS, LIT_FS);
        texProg = program(TEX_VS, TEX_FS);
        skyProg = program(SKY_VS, SKY_FS);
        cubeVbo = buffer(cube());
        quadVbo = buffer(new float[]{0, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 0, 0});
        groundVbo = buffer(new float[]{
                -500, 0, -500, 0, 1, 0, 500, 0, -500, 0, 1, 0, 500, 0, 500, 0, 1, 0,
                -500, 0, 500, 0, 1, 0});
        for (Model.Part p : game.model.parts) {
            int[] ids = new int[2];
            GLES20.glGenBuffers(2, ids, 0);
            p.vbo = ids[0];
            p.ibo = ids[1];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, p.vertices.capacity() * 4, p.vertices, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.indexCount * 2, p.indices, GLES20.GL_STATIC_DRAW);
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        smokeTex = texture(loadBitmap("smoke.png"));
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, width, height);
        Matrix.perspectiveM(proj, 0, 60f, (float) width / height, 0.01f, 100f);
    }

    // ------------------------------------------------------------------ frame
    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.elapsedRealtimeNanos();
        float dt = lastNanos == 0 ? 0f : (now - lastNanos) / 1e9f;
        lastNanos = now;
        game.update(dt);

        float wx = (float) game.phys.x;
        cam[0] = wx + CAM_OFFSET[0];
        cam[1] = (float) Physics.AXLE_Y + CAM_OFFSET[1];
        cam[2] = CAM_OFFSET[2];
        Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2],
                cam[0] + CAM_FORWARD[0], cam[1] + CAM_FORWARD[1], cam[2] + CAM_FORWARD[2], 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.invertM(invVp, 0, vp, 0);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        drawSky();
        drawWorld(wx);
        drawDatBoi();
        drawTexts(wx);
        drawSmoke();
    }

    private void drawSky() {
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(skyProg);
        GLES20.glUniformMatrix4fv(loc(skyProg, "uInvVP"), 1, false, invVp, 0);
        GLES20.glUniform3fv(loc(skyProg, "uCam"), 1, cam, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        int a = attr(skyProg, "aPos");
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 20, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
    }

    private void beginLit(int prog) {
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(loc(prog, "uVP"), 1, false, vp, 0);
        GLES20.glUniform3fv(loc(prog, "uLightDir"), 1, LIGHT_DIR, 0);
        GLES20.glUniform3fv(loc(prog, "uLightCol"), 1, LIGHT_COL, 0);
        GLES20.glUniform3fv(loc(prog, "uAmbSky"), 1, AMB_SKY, 0);
        GLES20.glUniform3fv(loc(prog, "uAmbGround"), 1, AMB_GROUND, 0);
        GLES20.glUniform3fv(loc(prog, "uFogColor"), 1, FOG, 0);
        GLES20.glUniform3fv(loc(prog, "uCam"), 1, cam, 0);
    }

    private void drawWorld(float wx) {
        beginLit(litProg);
        // ground: Unity Plane (10x10) scaled by 100 at the origin
        Matrix.setIdentityM(model, 0);
        drawLit(groundVbo, GLES20.GL_TRIANGLE_FAN, 4, model, WHITE);
        // wholeRuler: white bar along the first 1000 m
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 500f, 0.05f, 0.84f);
        Matrix.scaleM(model, 0, 1000f, 0.25457239f, 0.1f);
        drawLit(cubeVbo, GLES20.GL_TRIANGLES, 36, model, WHITE);
        // instantiateThis: a black tick every metre, a red one every ten
        int from = Math.max(0, (int) Math.floor(wx - 60));
        int to = Math.min(game.amountAlready, (int) Math.ceil(wx + 40));
        for (int i = from; i < to; i++) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, 0.049f + i, 0.06f, 0.8f);
            Matrix.scaleM(model, 0, 0.1f, 0.25457239f, 0.1f);
            drawLit(cubeVbo, GLES20.GL_TRIANGLES, 36, model, i % 10 == 0 ? RED : BLACK);
        }
    }

    private void drawLit(int vbo, int mode, int count, float[] m, float[] color) {
        normalMatrix(m);
        GLES20.glUniformMatrix4fv(loc(litProg, "uModel"), 1, false, m, 0);
        GLES20.glUniformMatrix3fv(loc(litProg, "uNormalMat"), 1, false, normalMat, 0);
        GLES20.glUniform4fv(loc(litProg, "uColor"), 1, color, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        int ap = attr(litProg, "aPos"), an = attr(litProg, "aNrm");
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(an);
        GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, 24, 12);
        GLES20.glDrawArrays(mode, 0, count);
    }

    private void drawDatBoi() {
        Model m = game.model;
        float f = game.animFrame();
        int f0 = ((int) Math.floor(f)) % m.frames;
        if (f0 < 0) f0 += m.frames;
        int f1 = (f0 + 1) % m.frames;
        float t = f - (float) Math.floor(f);

        beginLit(litProg);
        for (Model.Part p : m.parts) {
            if (p.skinned) continue;
            if (p.accessory != 0 && !game.accessoryActive[p.accessory]) continue;
            lerp(p.track[f0], p.track[f1], t, tmp);
            Matrix.setIdentityM(tmp2, 0);
            if (p.accessory != 0) {
                float[] o = game.accessoryOffset[p.accessory];
                Matrix.translateM(tmp2, 0, o[0], o[1], o[2]);
            }
            Matrix.multiplyMM(model, 0, tmp2, 0, tmp, 0);
            Matrix.multiplyMM(tmp2, 0, game.datBoiWorld, 0, model, 0);
            drawPart(litProg, p, tmp2);
        }

        beginLit(skinProg);
        for (int b = 0; b < m.boneCount; b++) {
            lerp(m.bones[f0][b], m.bones[f1][b], t, tmp);
            System.arraycopy(tmp, 0, bonePalette, b * 16, 16);
        }
        GLES20.glUniformMatrix4fv(loc(skinProg, "uBones"), m.boneCount, false, bonePalette, 0);
        for (Model.Part p : m.parts) {
            if (p.skinned) drawPart(skinProg, p, game.datBoiWorld);
        }
    }

    private void drawPart(int prog, Model.Part p, float[] world) {
        normalMatrix(world);
        GLES20.glUniformMatrix4fv(loc(prog, "uModel"), 1, false, world, 0);
        GLES20.glUniformMatrix3fv(loc(prog, "uNormalMat"), 1, false, normalMat, 0);
        GLES20.glUniform4fv(loc(prog, "uColor"), 1, p.color, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo);
        int stride = (p.skinned ? 14 : 6) * 4;
        int ap = attr(prog, "aPos"), an = attr(prog, "aNrm");
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(an);
        GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, stride, 12);
        int ai = -1, aw = -1;
        if (p.skinned) {
            ai = attr(prog, "aBi");
            aw = attr(prog, "aBw");
            GLES20.glEnableVertexAttribArray(ai);
            GLES20.glEnableVertexAttribArray(aw);
            GLES20.glVertexAttribPointer(ai, 4, GLES20.GL_FLOAT, false, stride, 24);
            GLES20.glVertexAttribPointer(aw, 4, GLES20.GL_FLOAT, false, stride, 40);
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
        if (p.skinned) {
            GLES20.glDisableVertexAttribArray(ai);
            GLES20.glDisableVertexAttribArray(aw);
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    // ------------------------------------------------------------------ 3D text
    private void drawTexts(float wx) {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        // distance labels (DistText clones): font size 304, rotated 180 degrees
        int from = Math.max(0, (int) Math.floor((wx - 60) / 10)) * 10;
        int to = Math.min(game.amountAlready, (int) Math.ceil(wx + 40));
        for (int i = from; i < to; i += 10) {
            TextTex tt = labels.get(i);
            if (tt == null) {
                tt = new TextTex(String.valueOf(i));
                labels.put(i, tt);
            }
            drawText(tt, 0.049f + i, 0.06f, 0.8f, -1f, 0f, 304);
        }
        Iterator<Map.Entry<Integer, TextTex>> it = labels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TextTex> e = it.next();
            if (e.getKey() < from - 20 || e.getKey() > to + 20) {
                e.getValue().delete();
                it.remove();
            }
        }
        // CycledText: world-fixed, behind the start line
        if (game.scoreVisible && Math.abs(wx - 0.15f) < 25f) {
            String s = game.cycledText;
            long now = SystemClock.uptimeMillis();
            if (!s.isEmpty() && (cycled == null || (!s.equals(cycledShown) && now - cycledTime > 66))) {
                if (cycled != null) cycled.delete();
                cycled = new TextTex(s);
                cycledShown = s;
                cycledTime = now;
            }
            if (cycled != null) drawText(cycled, 0.15f, 1.64f, 3.01f, -0.66371f, -0.74799f, 250);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Draws a TextMesh with anchor LowerCenter at (x,y,z); (rx,rz) is its right vector. */
    private void drawText(TextTex t, float x, float y, float z, float rx, float rz, int fontSize) {
        float worldPerPx = TEXT_UNITS * fontSize * TEXT_SCALE / TextTex.EM;
        float w = t.w * worldPerPx, h = t.h * worldPerPx;
        // columns: right, up, normal, origin (bottom-left corner of the quad)
        float[] m = model;
        m[0] = rx * w; m[1] = 0; m[2] = rz * w; m[3] = 0;
        m[4] = 0; m[5] = h; m[6] = 0; m[7] = 0;
        m[8] = 0; m[9] = 0; m[10] = 1; m[11] = 0;
        m[12] = x - rx * w / 2; m[13] = y; m[14] = z - rz * w / 2; m[15] = 1;
        Matrix.multiplyMM(tmp, 0, vp, 0, m, 0);
        drawTextured(t.tex, tmp, BLACK);
    }

    private void drawTextured(int tex, float[] mvp, float[] color) {
        GLES20.glUseProgram(texProg);
        GLES20.glUniformMatrix4fv(loc(texProg, "uMVP"), 1, false, mvp, 0);
        GLES20.glUniform4fv(loc(texProg, "uColor"), 1, color, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(loc(texProg, "uTex"), 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        int ap = attr(texProg, "aPos"), at = attr(texProg, "aUV");
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(at);
        GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 20, 0);
        GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 20, 12);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(at);
    }

    // ------------------------------------------------------------------ smoke
    private final float[] smokeColor = new float[4];

    private void drawSmoke() {
        if (game.smokeCount == 0) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);   // Mobile/Particles/Additive
        GLES20.glDepthMask(false);
        // camera right / up for billboards
        float rx = view[0], ry = view[4], rz = view[8];
        float ux = view[1], uy = view[5], uz = view[9];
        for (int i = 0; i < game.smokeCount; i++) {
            int k = i * 6;
            float age = game.smoke[k + 3] / game.smoke[k + 4];
            float seed = game.smoke[k + 5];
            float size = 0.15f + age * (0.9f + 0.6f * seed);
            float px = game.smokeOrigin[0] + game.smoke[k];
            float py = game.smokeOrigin[1] + game.smoke[k + 1];
            float pz = game.smokeOrigin[2] + game.smoke[k + 2];
            float[] m = model;
            m[0] = rx * size; m[1] = ry * size; m[2] = rz * size; m[3] = 0;
            m[4] = ux * size; m[5] = uy * size; m[6] = uz * size; m[7] = 0;
            m[8] = 0; m[9] = 0; m[10] = 1; m[11] = 0;
            m[12] = px - (rx + ux) * size / 2;
            m[13] = py - (ry + uy) * size / 2;
            m[14] = pz - (rz + uz) * size / 2;
            m[15] = 1;
            Matrix.multiplyMM(tmp, 0, vp, 0, m, 0);
            float fade = Math.min(1f, age * 6f) * (1f - age);
            smokeColor[0] = 0.7f + 0.3f * seed;
            smokeColor[1] = 1f;
            smokeColor[2] = 0.7f + 0.3f * seed;
            smokeColor[3] = 0.35f * fade;
            drawTextured(smokeTex, tmp, smokeColor);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ------------------------------------------------------------------ helpers
    private static void lerp(float[] a, float[] b, float t, float[] out) {
        for (int i = 0; i < 16; i++) out[i] = a[i] + (b[i] - a[i]) * t;
    }

    /** Inverse-transpose of the upper 3x3 (handles the negative-scaled blunt). */
    private void normalMatrix(float[] m) {
        float a = m[0], b = m[4], c = m[8];
        float d = m[1], e = m[5], f = m[9];
        float g = m[2], h = m[6], i = m[10];
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float D = -(b * i - c * h), E = a * i - c * g, F = -(a * h - b * g);
        float G = b * f - c * e, H = -(a * f - c * d), I = a * e - b * d;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-20f) det = 1e-20f;
        float s = 1f / det;
        // inverse = adj / det, adj = cofactor^T; inverse-transpose = cofactor / det
        normalMat[0] = A * s; normalMat[3] = D * s; normalMat[6] = G * s;
        normalMat[1] = B * s; normalMat[4] = E * s; normalMat[7] = H * s;
        normalMat[2] = C * s; normalMat[5] = F * s; normalMat[8] = I * s;
    }

    private Bitmap loadBitmap(String name) {
        try {
            InputStream in = context.getAssets().open(name);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int texture(Bitmap bmp) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return id[0];
    }

    /** Text rendered by Android into a texture, white on transparent (tinted when drawn). */
    private final class TextTex {
        static final float EM = 96f;
        final int tex, w, h;

        TextTex(String text) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(textFont);
            p.setTextSize(EM);
            p.setColor(0xffffffff);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            float lineH = fm.descent - fm.ascent;
            String[] lines = text.split("\n", -1);
            float maxW = 1;
            for (String l : lines) maxW = Math.max(maxW, p.measureText(l));
            w = (int) Math.ceil(maxW) + 8;
            h = (int) Math.ceil(lineH * lines.length) + 4;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            for (int i = 0; i < lines.length; i++) {
                c.drawText(lines[i], w / 2f, 2 - fm.ascent + i * lineH, p);
            }
            // the shared quad maps v=0 to the top row of the bitmap
            tex = texture(bmp);
        }

        void delete() {
            GLES20.glDeleteTextures(1, new int[]{tex}, 0);
        }
    }

    private static float[] cube() {
        float[] n = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        float[] out = new float[36 * 6];
        int o = 0;
        for (int f = 0; f < 6; f++) {
            float nx = n[f * 3], ny = n[f * 3 + 1], nz = n[f * 3 + 2];
            // two axes spanning the face
            float ax = ny != 0 || nz != 0 ? 1 : 0, ay = nx != 0 ? 1 : 0, az = 0;
            float bx = ny * az - nz * ay, by = nz * ax - nx * az, bz = nx * ay - ny * ax;
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
            for (float[] c : corners) {
                out[o++] = 0.5f * (nx + c[0] * ax + c[1] * bx);
                out[o++] = 0.5f * (ny + c[0] * ay + c[1] * by);
                out[o++] = 0.5f * (nz + c[0] * az + c[1] * bz);
                out[o++] = nx;
                out[o++] = ny;
                out[o++] = nz;
            }
        }
        return out;
    }

    private static int buffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, fb, GLES20.GL_STATIC_DRAW);
        return id[0];
    }

    private final Map<String, Integer> locCache = new HashMap<>();

    private int loc(int prog, String name) {
        String key = prog + name;
        Integer v = locCache.get(key);
        if (v == null) {
            v = GLES20.glGetUniformLocation(prog, name);
            locCache.put(key, v);
        }
        return v;
    }

    private int attr(int prog, String name) {
        String key = prog + "@" + name;
        Integer v = locCache.get(key);
        if (v == null) {
            v = GLES20.glGetAttribLocation(prog, name);
            locCache.put(key, v);
        }
        return v;
    }

    private int program(String vs, String fs) {
        locCache.clear();
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            Log.e(TAG, "link failed: " + log);
            throw new RuntimeException("link failed: " + log);
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            Log.e(TAG, "compile failed: " + log + "\n" + src);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return s;
    }

    // ------------------------------------------------------------------ shaders
    private static final String LIT_COMMON_VS =
            "uniform mat4 uVP;\n"
            + "uniform mat4 uModel;\n"
            + "uniform mat3 uNormalMat;\n"
            + "uniform vec3 uCam;\n"
            + "attribute vec3 aPos;\n"
            + "attribute vec3 aNrm;\n"
            + "varying vec3 vN;\n"
            + "varying vec3 vV;\n";

    private static final String LIT_VS = LIT_COMMON_VS
            + "void main() {\n"
            + "  vec4 w = uModel * vec4(aPos, 1.0);\n"
            + "  vN = uNormalMat * aNrm;\n"
            + "  vV = uCam - w.xyz;\n"
            + "  gl_Position = uVP * w;\n"
            + "}\n";

    private static final String SKIN_VS = LIT_COMMON_VS
            + "uniform mat4 uBones[19];\n"
            + "attribute vec4 aBi;\n"
            + "attribute vec4 aBw;\n"
            + "void main() {\n"
            + "  mat4 s = uBones[int(aBi.x)] * aBw.x + uBones[int(aBi.y)] * aBw.y\n"
            + "         + uBones[int(aBi.z)] * aBw.z + uBones[int(aBi.w)] * aBw.w;\n"
            + "  vec4 w = uModel * (s * vec4(aPos, 1.0));\n"
            + "  vN = uNormalMat * (s * vec4(aNrm, 0.0)).xyz;\n"
            + "  vV = uCam - w.xyz;\n"
            + "  gl_Position = uVP * w;\n"
            + "}\n";

    private static final String LIT_FS =
            "precision mediump float;\n"
            + "uniform vec4 uColor;\n"
            + "uniform vec3 uLightDir;\n"
            + "uniform vec3 uLightCol;\n"
            + "uniform vec3 uAmbSky;\n"
            + "uniform vec3 uAmbGround;\n"
            + "uniform vec3 uFogColor;\n"
            + "varying vec3 vN;\n"
            + "varying vec3 vV;\n"
            + "void main() {\n"
            + "  vec3 n = normalize(vN);\n"
            + "  vec3 v = normalize(vV);\n"
            + "  if (dot(n, v) < 0.0) n = -n;\n"
            + "  float nd = max(dot(n, uLightDir), 0.0);\n"
            + "  vec3 amb = mix(uAmbGround, uAmbSky, n.y * 0.5 + 0.5);\n"
            + "  vec3 h = normalize(uLightDir + v);\n"
            + "  float sp = nd > 0.0 ? pow(max(dot(n, h), 0.0), 24.0) * 0.18 : 0.0;\n"
            + "  vec3 c = uColor.rgb * (amb + uLightCol * nd) + uLightCol * sp;\n"
            + "  float fog = clamp(length(vV) / 300.0, 0.0, 1.0);\n"
            + "  gl_FragColor = vec4(mix(c, uFogColor, fog), uColor.a);\n"
            + "}\n";

    private static final String TEX_VS =
            "uniform mat4 uMVP;\n"
            + "attribute vec3 aPos;\n"
            + "attribute vec2 aUV;\n"
            + "varying vec2 vUV;\n"
            + "void main() { vUV = aUV; gl_Position = uMVP * vec4(aPos, 1.0); }\n";

    private static final String TEX_FS =
            "precision mediump float;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec4 uColor;\n"
            + "varying vec2 vUV;\n"
            + "void main() { gl_FragColor = texture2D(uTex, vUV) * uColor; }\n";

    // full screen quad from the shared unit quad, sky colour from the view ray
    private static final String SKY_VS =
            "uniform mat4 uInvVP;\n"
            + "uniform vec3 uCam;\n"
            + "attribute vec3 aPos;\n"
            + "varying vec3 vDir;\n"
            + "void main() {\n"
            + "  vec2 p = aPos.xy * 2.0 - 1.0;\n"
            + "  vec4 w = uInvVP * vec4(p, 1.0, 1.0);\n"
            + "  vDir = w.xyz / w.w - uCam;\n"
            + "  gl_Position = vec4(p, 0.999, 1.0);\n"
            + "}\n";

    private static final String SKY_FS =
            "precision mediump float;\n"
            + "varying vec3 vDir;\n"
            + "void main() {\n"
            + "  float y = normalize(vDir).y;\n"
            + "  vec3 zenith = vec3(0.30, 0.50, 0.82);\n"
            + "  vec3 horizon = vec3(0.80, 0.86, 0.93);\n"
            + "  vec3 ground = vec3(0.46, 0.44, 0.43);\n"
            + "  vec3 c = y > 0.0 ? mix(horizon, zenith, pow(y, 0.45)) : mix(horizon, ground, clamp(-y * 6.0, 0.0, 1.0));\n"
            + "  gl_FragColor = vec4(c, 1.0);\n"
            + "}\n";
}
