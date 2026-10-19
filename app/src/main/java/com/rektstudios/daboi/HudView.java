package com.rektstudios.daboi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The uGUI canvas of the original scene (CanvasScaler: 800x600 reference,
 * match width), re-created with the same anchors, sizes, rotations and texts.
 * Also hosts the CnControls "2 Ways DPad": left half = -1, right half = +1.
 */
final class HudView extends View {
    interface Actions {
        void share(String text);

        void exportProgress();

        void importProgress();
    }

    private static final float REF_WIDTH = 800f;
    private static final float REF_HEIGHT_16_9 = 450f;
    private static final int TEXT_GREY = 0xff323232;   // (0.196, 0.196, 0.196)
    private static final int ALIGN_UPPER_LEFT = 0, ALIGN_MIDDLE_LEFT = 3, ALIGN_MIDDLE_CENTER = 4;
    private static final int BG_NONE = 0, BG_BUTTON = 1, BG_PANEL = 2;

    /** A RectTransform with an optional Image and/or Text. */
    private static final class El {
        El parent;
        float aminX, aminY, amaxX, amaxY, posX, posY, sizeX, sizeY, rot;
        int bg = BG_NONE;
        int bgColor = 0xffffffff;
        String text;
        int fontSize, align, textColor = TEXT_GREY, minSize, maxSize;
        boolean bestFit;
        int action = -1;
        // layout result in canvas units (y up)
        float cx, cy, w, h;

        El(El parent, float aminX, float aminY, float amaxX, float amaxY,
           float posX, float posY, float sizeX, float sizeY, float rot) {
            this.parent = parent;
            this.aminX = aminX;
            this.aminY = aminY;
            this.amaxX = amaxX;
            this.amaxY = amaxY;
            this.posX = posX;
            this.posY = posY;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.rot = rot;
        }

        El text(String t, int size, int align) {
            text = t;
            fontSize = size;
            this.align = align;
            return this;
        }

        El bestFit(int min, int max) {
            bestFit = true;
            minSize = min;
            maxSize = max;
            return this;
        }
    }

    private static final int A_HELP = 0, A_START = 1, A_K = 2, A_RESTART = 3, A_MUTE = 4, A_SHARE = 5,
            A_EXPORT = 6, A_IMPORT = 7;

    private final Game game;
    private final Sound sound;
    private final Actions actions;
    private final El root = new El(null, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private final El scoreText, helpButton, helpText, startButton, startText;
    private final El deathPanel, faqPanel;
    private final List<El> deathEls = new ArrayList<>();
    private final List<El> faqEls = new ArrayList<>();
    private final El muteText, endScore, endRecord;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Bitmap hitmarkerBmp;
    private final Map<String, StaticLayout> layoutCache = new HashMap<>();
    private final RectF rect = new RectF();

    private float scale = 1f, canvasW = 800f, canvasH = 600f, insetLeft, insetTop;

    // touch state
    private final Map<Integer, El> pressed = new HashMap<>();
    private final List<int[]> dpad = new ArrayList<>();   // {pointerId, axis}
    private final List<float[]> hitmarkers = new ArrayList<>();   // {x, y, endTimeMillis}

    HudView(Context context, Game game, Sound sound, Actions actions) {
        super(context);
        this.game = game;
        this.sound = sound;
        this.actions = actions;
        textPaint.setTypeface(Typeface.SANS_SERIF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xffb4b4b4);
        hitmarkerBmp = loadBitmap(context, "hitmarker.png");

        // Canvas/Text: score in the top right corner
        scoreText = new El(root, 1, 1, 1, 1, -122.288f, -52.017f, 212, 73, -4.0f)
                .text("dat boi score", 30, ALIGN_UPPER_LEFT);
        // Canvas/Button (1): "help"
        helpButton = new El(root, 0, 1, 0, 1, 68.6f, -30.517f, 96, 30, -0.4f);
        helpButton.bg = BG_BUTTON;
        helpButton.action = A_HELP;
        helpText = new El(helpButton, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 160, 30, 0).text("help", 25, ALIGN_MIDDLE_CENTER);
        helpText.textColor = 0xffff0000;

        // Canvas/Panel: the "o shit u diededed" screen
        deathPanel = new El(root, 0, 0, 1, 1, 0, 0, 0, 0, 0);
        deathPanel.bg = BG_PANEL;
        deathPanel.bgColor = 0x9affffff;   // alpha 0.603
        muteText = button(deathEls, deathPanel, 0, 1, 196.3f, -37.6f, 188.7f, 25.9f, 2.5f, A_MUTE)
                .text("Disable dank sounds", 30, ALIGN_MIDDLE_CENTER).bestFit(3, 100);
        button(deathEls, deathPanel, 0.5f, 0.5f, 90f, -1f, 610f, 183f, -0.1f, A_RESTART)
                .text("restert?", 163, ALIGN_MIDDLE_CENTER);
        deathEls.add(new El(deathPanel, 1, 0, 1, 0, -127f, 33f, 242f, 49f, -0.2f)
                .text("(U dont hav frienz tho)", 24, ALIGN_MIDDLE_CENTER));
        button(deathEls, deathPanel, 1, 0, -193f, 76.2f, 386f, 47f, 1.3f, A_SHARE)
                .text("share on dat facebuk?", 35, ALIGN_MIDDLE_CENTER);
        deathEls.add(new El(deathPanel, 0, 1, 0, 1, 212f, -139f, 390f, 113f, -8.2f)
                .text("O shit u diededed", 44, ALIGN_MIDDLE_CENTER));
        endScore = new El(deathPanel, 1, 1, 1, 1, -249.8f, -79.4f, 466.4f, 119f, -0.9f)
                .text("Dis run:", 53, ALIGN_MIDDLE_LEFT);
        deathEls.add(endScore);
        endRecord = new El(deathPanel, 0, 0, 0, 0, 223.6f, 76.2f, 360.5f, 95f, 3.6f)
                .text("Ur Rekord is:", 36, ALIGN_MIDDLE_LEFT);
        deathEls.add(endRecord);

        // Canvas/Button: "Start da Gaem"
        startButton = new El(root, 0.5f, 0.5f, 0.5f, 0.5f, 0f, 4.3f, 224f, 54f, 0f);
        startButton.action = A_START;
        startText = new El(startButton, 0, 0, 1, 1, 0, 0, 0, 0, 0).text("Start da Gaem", 30, ALIGN_MIDDLE_CENTER).bestFit(3, 100);

        // Canvas/Panel (1): FAQ band
        faqPanel = new El(root, 0, 0.5f, 1, 0.5f, 0f, 3.65f, 0f, 191f, 0f);
        faqPanel.bg = BG_PANEL;
        El k = new El(faqPanel, 0.5f, 0.5f, 0.5f, 0.5f, 354f, -73f, 79.6f, 34.6f, 0f);
        k.bg = BG_BUTTON;
        k.action = A_K;
        faqEls.add(k);
        // not in the original: move your record between installs / phones
        faqButton(-320f, -20f, A_EXPORT, "export ur progress");
        faqButton(-320f, -73f, A_IMPORT, "import ur progress");
        faqEls.add(new El(k, 0, 0, 1, 1, 0, 0, 0, 0, 0).text("K play da gaem", 14, ALIGN_MIDDLE_CENTER).bestFit(10, 40));
        faqEls.add(new El(faqPanel, 0.5f, 0, 0.5f, 1, 1f, 0f, 357f, -1f, 0f).text(
                "FAQ\nControls?\nTouch left to lean more right, touch right lean more left.\n\n"
                        + "Why does the UI look like cancer?\nBecause DAT BOI doesn't need a nice UI.\n\n"
                        + "How can I turn on music again?\nRestart the game.\n", 14, ALIGN_MIDDLE_CENTER));
        setFocusable(true);
    }

    private El button(List<El> list, El parent, float ax, float ay, float px, float py, float w, float h, float rot, int action) {
        El b = new El(parent, ax, ay, ax, ay, px, py, w, h, rot);
        b.action = action;
        list.add(b);
        El t = new El(b, 0, 0, 1, 1, 0, 0, 0, 0, 0);
        list.add(t);
        return t;
    }

    private float insetRight;

    private void faqButton(float x, float y, int action, String label) {
        El b = new El(faqPanel, 0.5f, 0.5f, 0.5f, 0.5f, x, y, 140f, 34.6f, 0f);
        b.bg = BG_BUTTON;
        b.action = action;
        faqEls.add(b);
        faqEls.add(new El(b, 0, 0, 1, 1, 0, 0, 0, 0, 0).text(label, 14, ALIGN_MIDDLE_CENTER).bestFit(10, 40));
    }

    void setInsets(int left, int top, int right) {
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        invalidate();
    }

    // ---------------------------------------------------------------- layout
    private void layout(El e) {
        if (e.parent == null) return;
        El p = e.parent;
        float px0 = p.cx - p.w / 2, py0 = p.cy - p.h / 2;
        float ax0 = px0 + p.w * e.aminX, ax1 = px0 + p.w * e.amaxX;
        float ay0 = py0 + p.h * e.aminY, ay1 = py0 + p.h * e.amaxY;
        e.w = (ax1 - ax0) + e.sizeX;
        e.h = (ay1 - ay0) + e.sizeY;
        e.cx = ax0 + (ax1 - ax0) * 0.5f + e.posX;   // pivot is always (0.5, 0.5)
        e.cy = ay0 + (ay1 - ay0) * 0.5f + e.posY;
    }

    @Override
    protected void onDraw(Canvas c) {
        float usableW = Math.max(1f, getWidth() - insetLeft - insetRight);
        float usableH = Math.max(1f, getHeight() - insetTop);
        // The original matched the 800 unit width, which was laid out for 16:9 screens.
        // On wider (modern 19.5:9 / 20:9) phones keep the 16:9 height instead, so the
        // elements keep their original spacing rather than piling on top of each other.
        scale = Math.min(usableW / REF_WIDTH, usableH / REF_HEIGHT_16_9);
        canvasW = usableW / scale;
        canvasH = usableH / scale;
        root.w = canvasW;
        root.h = canvasH;
        root.cx = canvasW / 2;
        root.cy = canvasH / 2;

        if (game.scoreVisible) {
            scoreText.text = game.scoreText;
            draw(c, scoreText);
        }
        draw(c, helpButton);
        draw(c, helpText);
        if (game.panelVisible) {
            muteText.text = Game.muted ? "Enable dank sounds" : "Disable dank sounds";
            endScore.text = game.endScoreText;
            endRecord.text = game.endRecordText;
            draw(c, deathPanel);
            for (El e : deathEls) draw(c, e);
        }
        if (game.startVisible) {
            draw(c, startButton);
            draw(c, startText);
        }
        if (game.faqVisible) {
            draw(c, faqPanel);
            for (El e : faqEls) draw(c, e);
        }
        // hitmarkers (destroyafter 0.78 s, real time)
        long now = SystemClock.uptimeMillis();
        float hs = 156.49f * scale;
        for (int i = hitmarkers.size() - 1; i >= 0; i--) {
            float[] h = hitmarkers.get(i);
            if (now >= h[2]) {
                hitmarkers.remove(i);
                continue;
            }
            rect.set(h[0] - hs / 2, h[1] - hs / 2, h[0] + hs / 2, h[1] + hs / 2);
            c.drawBitmap(hitmarkerBmp, null, rect, bitmapPaint);
        }
        postInvalidateOnAnimation();
    }

    private float toPxX(float x) {
        return insetLeft + x * scale;
    }

    private float toPxY(float y) {
        return insetTop + (canvasH - y) * scale;
    }

    private void draw(Canvas c, El e) {
        layout(e);
        float cxp = toPxX(e.cx), cyp = toPxY(e.cy);
        float wp = e.w * scale, hp = e.h * scale;
        c.save();
        c.rotate(-e.rot, cxp, cyp);   // Unity rotates counter-clockwise
        if (e.bg != BG_NONE) {
            int color = e.bgColor;
            if (e.action >= 0 && pressed.containsValue(e)) color = tint(color, 0.784f);
            fill.setColor(color);
            float r = 8 * scale;
            rect.set(cxp - wp / 2, cyp - hp / 2, cxp + wp / 2, cyp + hp / 2);
            c.drawRoundRect(rect, r, r, fill);
            if (e.bg == BG_BUTTON) {
                stroke.setStrokeWidth(Math.max(1f, scale));
                c.drawRoundRect(rect, r, r, stroke);
            }
        }
        if (e.text != null) drawText(c, e, cxp, cyp, wp, hp);
        c.restore();
    }

    private static int tint(int argb, float k) {
        int a = argb >>> 24;
        int r = (int) (((argb >> 16) & 0xff) * k), g = (int) (((argb >> 8) & 0xff) * k), b = (int) ((argb & 0xff) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawText(Canvas c, El e, float cx, float cy, float w, float h) {
        if (w <= 1 || h <= 1) return;
        textPaint.setColor(e.textColor);
        int size = e.fontSize;
        if (e.bestFit) {
            size = bestFit(e, w, h, e.minSize, e.maxSize);
        } else if (layoutFor(e.text, size * scale, (int) Math.ceil(w), e.align).getHeight() > h + 0.5f) {
            // Android's fonts are a little wider than Unity's Arial: shrink instead of losing words
            size = bestFit(e, w, h, Math.max(1, size * 6 / 10), size);
        }
        StaticLayout l = layoutFor(e.text, size * scale, (int) Math.ceil(w), e.align);
        // vertical overflow: truncate lines that do not fit
        int lines = l.getLineCount();
        while (lines > 1 && l.getLineBottom(lines - 1) > h + 0.5f) lines--;
        float textH = l.getLineBottom(lines - 1);
        float top;
        if (e.align <= 2) top = cy - h / 2;
        else if (e.align <= 5) top = cy - textH / 2;
        else top = cy + h / 2 - textH;
        c.save();
        c.translate(cx - w / 2, top);
        c.clipRect(0, 0, w, textH);
        l.draw(c);
        c.restore();
    }

    private int bestFit(El e, float w, float h, int min, int max) {
        int lo = min, hi = max, best = min;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            StaticLayout l = layoutFor(e.text, mid * scale, (int) Math.ceil(w), e.align);
            boolean fits = l.getHeight() <= h;
            for (int i = 0; fits && i < l.getLineCount(); i++) {
                if (l.getLineWidth(i) > w + 0.5f) fits = false;
                // a single word wider than the box gets broken mid-word: does not fit
                if (i > 0 && !Character.isWhitespace(e.text.charAt(Math.max(0, l.getLineStart(i) - 1)))
                        && e.text.charAt(Math.max(0, l.getLineStart(i) - 1)) != '\n') fits = false;
            }
            if (fits) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private StaticLayout layoutFor(String text, float px, int width, int align) {
        String key = text + '\u0000' + px + '\u0000' + width + '\u0000' + align;
        StaticLayout l = layoutCache.get(key);
        if (l == null) {
            if (layoutCache.size() > 200) layoutCache.clear();
            TextPaint p = new TextPaint(textPaint);
            p.setTextSize(px);
            Layout.Alignment a;
            int col = align % 3;
            if (col == 0) a = Layout.Alignment.ALIGN_NORMAL;
            else if (col == 1) a = Layout.Alignment.ALIGN_CENTER;
            else a = Layout.Alignment.ALIGN_OPPOSITE;
            l = new StaticLayout(text, p, Math.max(1, width), a, 1f, 0f, false);
            layoutCache.put(key, l);
        }
        // colour is applied at draw time through the shared paint
        l.getPaint().setColor(textPaint.getColor());
        return l;
    }

    // ---------------------------------------------------------------- touch
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = ev.getPointerId(idx);
                float x = ev.getX(idx), y = ev.getY(idx);
                El b = buttonAt(x, y);
                if (b != null) {
                    pressed.put(id, b);
                } else if (dpadEnabled(x, y)) {
                    dpad.add(new int[]{id, axisFor(x)});
                    applyAxis();
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    for (int[] d : dpad) {
                        if (d[0] == id) d[1] = axisFor(ev.getX(i));
                    }
                }
                applyAxis();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = action == MotionEvent.ACTION_CANCEL;
                if (cancel) {
                    pressed.clear();
                    dpad.clear();
                    applyAxis();
                    break;
                }
                int id = ev.getPointerId(idx);
                float x = ev.getX(idx), y = ev.getY(idx);
                El b = pressed.remove(id);
                for (int i = dpad.size() - 1; i >= 0; i--) {
                    if (dpad.get(i)[0] == id) dpad.remove(i);
                }
                applyAxis();
                // OnTouch: a hitmarker + sound on every release
                hitmarkers.add(new float[]{x, y, SystemClock.uptimeMillis() + 780});
                sound.playHitmarker();
                if (b != null && b == buttonAt(x, y)) click(b);
                break;
            }
            default:
                break;
        }
        return true;
    }

    private int axisFor(float x) {
        return x < getWidth() / 2f ? -1 : 1;
    }

    private void applyAxis() {
        game.touchAxis = dpad.isEmpty() ? 0 : dpad.get(dpad.size() - 1)[1];
    }

    private boolean dpadEnabled(float x, float y) {
        if (game.panelVisible) return false;
        if (game.faqVisible && contains(faqPanel, x, y)) return false;
        return true;
    }

    private El buttonAt(float x, float y) {
        if (game.faqVisible) {
            for (El e : faqEls) if (e.action >= 0 && contains(e, x, y)) return e;
        }
        if (game.startVisible && contains(startButton, x, y)) return startButton;
        if (game.panelVisible) {
            for (int i = deathEls.size() - 1; i >= 0; i--) {
                El e = deathEls.get(i);
                if (e.action >= 0 && contains(e, x, y)) return e;
            }
        }
        if (contains(helpButton, x, y)) return helpButton;
        return null;
    }

    private boolean contains(El e, float x, float y) {
        layout(e);
        float cx = toPxX(e.cx), cy = toPxY(e.cy);
        // undo the element rotation
        double a = Math.toRadians(e.rot);
        float dx = x - cx, dy = y - cy;
        float lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
        float ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
        return Math.abs(lx) <= e.w * scale / 2 && Math.abs(ly) <= e.h * scale / 2;
    }

    private void click(El b) {
        switch (b.action) {
            case A_HELP:
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onHelp();
                    }
                });
                break;
            case A_START:
                game.startVisible = false;
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onStart();
                    }
                });
                break;
            case A_K:
                game.faqVisible = false;
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onK();
                    }
                });
                break;
            case A_RESTART:
                game.panelVisible = false;
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onRestart();
                    }
                });
                break;
            case A_MUTE:
                game.post(new Runnable() {
                    @Override
                    public void run() {
                        game.onToggleMute();
                    }
                });
                break;
            case A_EXPORT:
                actions.exportProgress();
                break;
            case A_IMPORT:
                actions.importProgress();
                break;
            case A_SHARE:
                actions.share(game.shareText());
                break;
            default:
                break;
        }
    }

    private static Bitmap loadBitmap(Context c, String name) {
        try {
            InputStream in = c.getAssets().open(name);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
