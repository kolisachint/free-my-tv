package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * What the home screen's parts share: the day and night colors, every Paint (made once; sizes are in the 1920 x 1080
 * design space, the canvas is scaled), the ring that marks focus, glass, shadows and a few icons. Nothing here
 * allocates while drawing.
 */
final class Fx {
    static final int INK = 0xFF1D1D1F, WHITE = 0xFFF5F5F7;
    static final float PAGE_H = 1080;

    final Context ctx;
    /** 0 = day, 1 = night (in between during the transition). */
    float night;
    final Shadows shadows = new Shadows();
    final boolean shadowOn = !Ui.LEGACY;

    final RectF rect = new RectF(), rect2 = new RectF();
    final Rect src = new Rect();
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bitmap = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // Text (design px). All TextPaint: layouts need them.
    final TextPaint clockT = tp(Ui.displaySemibold, 52, -0.02f), clockD = tp(Ui.medium, 26, 0), tip = tp(Ui.bold, 22, 0);
    final TextPaint heroTitle = tp(Ui.displayBold, 76, -0.03f), heroMeta = tp(Ui.semibold, 30, 0), heroOv = tp(Ui.regular, 30, 0);
    final TextPaint heroWt = tp(Ui.semibold, 26, 0), chip = tp(Ui.bold, 26, 0);
    final TextPaint rowLabel = tp(Ui.displayBold, 38, -0.02f), rowCount = tp(Ui.semibold, 30, 0);
    final TextPaint cardT = tp(Ui.bold, 28, 0), cardM = tp(Ui.medium, 24, 0), cardPh = tp(Ui.bold, 32, 0);
    final TextPaint dLabel = tp(Ui.bold, 34, 0), dName = tp(Ui.bold, 30, 0), dStatus = tp(Ui.semibold, 24, 0);
    final TextPaint sTitle = tp(Ui.displayBold, 60, -0.03f), sMeta = tp(Ui.semibold, 30, 0), sOv = tp(Ui.regular, 28, 0);
    final TextPaint sCast = tp(Ui.semibold, 28, 0), btn = tp(Ui.bold, 30, 0), sFoot = tp(Ui.regular, 26, 0);
    final TextPaint wHead = tp(Ui.bold, 36, 0), wGroup = tp(Ui.bold, 24, 0.1f), wName = tp(Ui.bold, 30, 0);
    final TextPaint wSmall = tp(Ui.medium, 24, 0), wLabel = tp(Ui.bold, 24, 0);
    final TextPaint sq = tp(Ui.semibold, 52, 0), sqPh = tp(Ui.medium, 44, 0), mic = tp(Ui.bold, 32, 0);
    final TextPaint key = tp(Ui.bold, 42, 0), keySmall = tp(Ui.bold, 30, 0), schip = tp(Ui.bold, 30, 0);
    final TextPaint shTag = tp(Ui.bold, 26, 0), shTitle = tp(Ui.displayBold, 54, -0.03f), shMeta = tp(Ui.semibold, 28, 0);
    final TextPaint shOv = tp(Ui.regular, 28, 0), count = tp(Ui.bold, 32, 0), sEmpty = tp(Ui.displayBold, 56, -0.02f);
    final TextPaint sNote = tp(Ui.medium, 30, 0), toast = tp(Ui.semibold, 30, 0), debug = tp(Ui.medium, 22, 0);

    private final float[] mid = new float[1];
    private Bitmap fadeDay, fadeNight;

    Fx(Context c) {
        ctx = c;
        stroke.setStyle(Paint.Style.STROKE);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(3);
        dashed.setPathEffect(new DashPathEffect(new float[] {12, 9}, 0));
        shadows.always = true;
        for (TextPaint p : new TextPaint[] {rowCount, count, wLabel}) p.setTextAlign(Paint.Align.RIGHT);
        for (TextPaint p : new TextPaint[] {tip, cardPh, btn, key, keySmall, schip, toast}) p.setTextAlign(Paint.Align.CENTER);
    }

    private static TextPaint tp(android.graphics.Typeface face, float size, float spacing) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setLetterSpacing(spacing);
        return p;
    }

    // MARK: - Colors

    boolean dark() { return night > 0.5f; }

    int tone(int day, int nightColor) { return blend(day, nightColor, night); }

    float tone(float day, float nightValue) { return day + (nightValue - day) * night; }

    int ink() { return tone(INK, WHITE); }

    int dim() { return tone(0xFF55555A, 0xFFB8B8BE); }

    int meta() { return tone(0xFF3A3A3F, 0xFFD8D8DC); }

    static int blend(int a, int b, float f) {
        if (f <= 0) return a;
        if (f >= 1) return b;
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    static int alpha(int color, float a) {
        return Ui.alpha(color, a);
    }

    /** A page-gradient pair for the screen of height `h` px (day, night). */
    Shader[] pageShaders(int h) {
        return new Shader[] {Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7), Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D)};
    }

    // MARK: - Text

    /** Baseline that centers one line of `p` in a box from `top` of height `height` (like CSS line-height). */
    static float base(float top, float height, Paint p) {
        return top + height / 2f - (p.ascent() + p.descent()) / 2f;
    }

    static String fit(String s, TextPaint p, float width) {
        if (s == null) return "";
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    static StaticLayout layout(String s, TextPaint p, int width, int lines, Layout.Alignment align, float add) {
        String t = s == null ? "" : s;
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(1, width))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setAlignment(align)
                .setLineSpacing(add, 1f).setIncludePad(false).build();
    }

    // MARK: - Shapes

    /** Soft drop shadow (a cached nine-slice bitmap; none on older TVs). */
    void drop(Canvas c, float l, float t, float r, float b, float radius, float dy, float blur, float a) {
        if (!shadowOn || a <= 0.004f) return;
        rect.set(l, t + dy, r, b + dy);
        shadow.setColor(alpha(0xFF000000, a));
        shadows.dither = dark();
        shadows.draw(c, rect, radius, blur, shadow);
    }

    /** The glass of the round buttons, chips and keys: white by day, smoke at night. `a` fades it. */
    void glass(Canvas c, float l, float t, float r, float b, float radius, float a) {
        rect.set(l, t, r, b);
        fill.setColor(alpha(tone(0xFFFFFFFF, 0xFF242427), a * tone(0.66f, 0.82f)));
        c.drawRoundRect(rect, radius, radius, fill);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(alpha(tone(0xFFFFFFFF, 0xFFFFFFFF), a * tone(0.8f, 0.1f)));
        rect.inset(0.75f, 0.75f);
        c.drawRoundRect(rect, radius, radius, stroke);
    }

    /** Solid white (or smoke) fill: the focused button, key, chip. */
    void lit(Canvas c, float l, float t, float r, float b, float radius, float a) {
        rect.set(l, t, r, b);
        fill.setColor(alpha(tone(0xFFFFFFFF, WHITE), a));
        c.drawRoundRect(rect, radius, radius, fill);
    }

    /**
     * The focus ring: a thick border and a thin outline of the opposite color, so focus never relies on color or on the
     * scale alone. The shape is l,t,r,b; the ring sits 3 to 9 px outside it (outline 9 to 12). `radius` is the shape's.
     */
    void ring(Canvas c, float l, float t, float r, float b, float radius, float a) {
        if (a <= 0.01f) return;
        stroke.setStrokeWidth(6);
        stroke.setColor(alpha(tone(INK, 0xFFFFFFFF), a));
        rect.set(l - 6, t - 6, r + 6, b + 6);
        c.drawRoundRect(rect, radius + 8, radius + 8, stroke);
        stroke.setStrokeWidth(3);
        stroke.setColor(alpha(tone(0xFFFFFFFF, 0xFF000000), a));
        rect.set(l - 10.5f, t - 10.5f, r + 10.5f, b + 10.5f);
        c.drawRoundRect(rect, radius + 12, radius + 12, stroke);
    }

    /**
     * A ring that grows out of the shape and fades as `phase` goes 0 to 1 (the mic chip while it listens): motion plus a
     * shape, so "listening" never rests on color. One stroked round rect per frame, nothing allocated.
     */
    void pulse(Canvas c, float l, float t, float r, float b, float radius, float phase) {
        float g = 8 + 22 * phase;
        stroke.setStrokeWidth(5);
        stroke.setColor(alpha(ink(), 1 - phase));
        rect.set(l - g, t - g, r + g, b + g);
        c.drawRoundRect(rect, radius + g, radius + g, stroke);
    }

    /** The same ring for a row's thin button (an item in a list: 5 px border, 4 px away). */
    void ringThin(Canvas c, float l, float t, float r, float b, float radius, float a) {
        stroke.setStrokeWidth(5);
        stroke.setColor(alpha(tone(INK, 0xFFFFFFFF), a));
        rect.set(l - 1.5f, t - 1.5f, r + 1.5f, b + 1.5f);
        c.drawRoundRect(rect, radius + 4, radius + 4, stroke);
    }

    // MARK: - Pictures

    /**
     * `image` covering w x h at the origin with rounded corners `r`: the anti-aliased rounded rectangle of its shader
     * (a clipPath isn't anti-aliased on some GPUs). The canvas does the scaling; nothing is allocated.
     */
    void roundImage(Canvas c, Bitmap image, BitmapShader shader, float w, float h, float r, int alpha) {
        float s = Math.max(w / image.getWidth(), h / image.getHeight());
        float dx = (w - image.getWidth() * s) / 2f, dy = (h - image.getHeight() * s) / 2f;
        c.save();
        c.translate(dx, dy);
        c.scale(s, s);
        shaded.setShader(shader);
        shaded.setAlpha(alpha);
        c.drawRoundRect(-dx / s, -dy / s, (w - dx) / s, (h - dy) / s, r / s, r / s, shaded);
        shaded.setShader(null);
        c.restore();
    }

    /** `image` covering the box l,t,r,b (cropped, not stretched), square corners. */
    void coverImage(Canvas c, Bitmap image, float l, float t, float r, float b, int alpha) {
        float bw = r - l, bh = b - t, iw = image.getWidth(), ih = image.getHeight();
        float s = Math.max(bw / iw, bh / ih);
        float cw = bw / s, ch = bh / s;
        int x = Math.round((iw - cw) / 2f), y = Math.round((ih - ch) / 2f);
        src.set(x, y, Math.round(x + cw), Math.round(y + ch));
        rect2.set(l, t, r, b);
        bitmap.setAlpha(alpha);
        c.drawBitmap(image, src, rect2, bitmap);
    }

    /** hsl in 0..360, 0..1, 0..1 to an opaque color. */
    static int hsl(float h, float s, float l) {
        float cc = (1 - Math.abs(2 * l - 1)) * s, hp = (h % 360) / 60f;
        float x = cc * (1 - Math.abs(hp % 2 - 1)), r = 0, g = 0, b = 0;
        if (hp < 1) { r = cc; g = x; } else if (hp < 2) { r = x; g = cc; } else if (hp < 3) { g = cc; b = x; }
        else if (hp < 4) { g = x; b = cc; } else if (hp < 5) { r = x; b = cc; } else { r = cc; b = x; }
        float m = l - cc / 2;
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** The gradient behind a title with no picture: always the same two hues for the same title. */
    static LinearGradient placeholder(String title, float w, float h) {
        int hue = Math.abs((title == null ? "" : title).hashCode()) % 360;
        return new LinearGradient(0, 0, w, h, hsl(hue, 0.45f, 0.34f), hsl(hue + 50, 0.5f, 0.16f), Shader.TileMode.CLAMP);
    }

    /**
     * Hero backdrop fade: the page color over the picture, opaque on the left and at the bottom (the mock's mask:
     * left 0 → .85 at 40 % → 1 at 75 %, bottom 1 at 38 % → .7 at 62 % → 0 at 84 %). Small, drawn stretched, made once.
     */
    Bitmap heroFade(boolean night) {
        Bitmap cached = night ? fadeNight : fadeDay;
        if (cached != null) return cached;
        int w = 136, h = 77;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float v = (y + 0.5f) / h, my = v < 0.38f ? 1 : v < 0.62f ? 1 - 0.3f * (v - 0.38f) / 0.24f : v < 0.84f ? 0.7f * (0.84f - v) / 0.22f : 0;
            float page = (v * 765f) / PAGE_H;
            int bg = night ? blend(0xFF1C1C1E, 0xFF0B0B0D, page) : blend(0xFFF5F5F7, 0xFFE2E2E7, page);
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w, mx = u < 0.4f ? 0.85f * u / 0.4f : u < 0.75f ? 0.85f + 0.15f * (u - 0.4f) / 0.35f : 1;
                int a = Math.round(255 * (1 - mx * my));
                px[y * w + x] = (a << 24) | (bg & 0xFFFFFF);
            }
        }
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        if (night) fadeNight = b; else fadeDay = b;
        return b;
    }

    // MARK: - Icons

    /** Magnifier in a 40 px box centered at cx, cy. */
    void magnifier(Canvas c, float cx, float cy, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(5);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(cx - 4, cy - 4, 10, stroke);
        c.drawLine(cx + 3.5f, cy + 3.5f, cx + 14, cy + 14, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Microphone centered at cx, cy (about 40 x 48). */
    void microphone(Canvas c, float cx, float cy, int color) {
        fill.setColor(color);
        rect.set(cx - 8, cy - 24, cx + 8, cy + 4);
        c.drawRoundRect(rect, 8, 8, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(4);
        rect.set(cx - 15, cy - 14, cx + 15, cy + 8);
        c.drawArc(rect, 0, 180, false, stroke);
        c.drawRect(cx - 2, cy + 9, cx + 2, cy + 16, fill);
        rect.set(cx - 9, cy + 16, cx + 9, cy + 20);
        c.drawRoundRect(rect, 2, 2, fill);
    }

    /** Wi-Fi as four signal bars: `level` lit (0..4); `off` = faint with a slash; `all` = Ethernet (all lit). */
    void wifi(Canvas c, float cx, float cy, int level, boolean off, int color, int halo) {
        float x0 = cx - 17, y1 = cy + 17 - 3;
        float[] h = {8, 14, 20, 26};
        for (int i = 0; i < 4; i++) {
            boolean on = !off && i < level;
            fill.setColor(alpha(color, on ? 1 : 0.3f));
            float l = x0 + 2 + i * 8;
            rect.set(l, y1 - h[i], l + 6, y1);
            c.drawRoundRect(rect, 2, 2, fill);
        }
        if (off) {
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(9);
            stroke.setColor(halo);
            c.drawLine(cx - 15, cy - 13, cx + 15, cy + 17, stroke);
            stroke.setStrokeWidth(5);
            stroke.setColor(color);
            c.drawLine(cx - 15, cy - 13, cx + 15, cy + 17, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    void symbol(Canvas c, String name, float cx, float cy, float size, int color) {
        Symbols.draw(ctx, c, name, cx, cy, size, color);
    }
}
