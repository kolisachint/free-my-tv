package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The top ~600 px: the focused item's backdrop (w1280, faded into the page by one small pre-made gradient, never
 * blurred per frame), its title, meta line, two-line overview and the "Watch on" chips (installed apps, then Stremio).
 * A change crossfades both slides (alpha only, 150 ms). The picture is asked for ~250 ms after the focus settles, so
 * scrolling fast along a row loads nothing in between.
 */
final class HomeHero {
    private static final long FADE_MS = 150, DEBOUNCE_MS = 250;
    private static final Object TAG = new Object();
    static final float X = 560, W = 1360, H = 765;

    private static final class Slide {
        String key = "", title = "", meta = "", url = "";
        StaticLayout overview;
        final List<String> chips = new ArrayList<>();
        final List<Float> chipW = new ArrayList<>();
        float chipsStart;
        Bitmap img;
        long imgAt;
    }

    private final HomeView home;
    private final Fx fx;
    private Slide cur = new Slide(), old;
    private long fadeAt;
    private final android.util.LruCache<String, StaticLayout> overviews = new android.util.LruCache<>(24);
    private final Runnable load = this::loadNow;
    private int imgW = 1360, imgH = 765;
    private float checkW = 28;

    HomeHero(HomeView home, Fx fx) {
        this.home = home;
        this.fx = fx;
    }

    void size(float k) {
        imgW = Math.round(W * k);
        imgH = Math.round(H * k);
    }

    /** Shows the item the focus is on (a card of the rows); `title` is its full data when we have it. */
    void show(HomeRows.Card card, Title match) {
        if (card == null) return;
        String key = card.key();
        if (key.equals(cur.key)) return;
        Slide s = new Slide();
        s.key = key;
        Title t = card.title != null ? card.title : match;
        s.title = Fx.fit(t != null ? t.title : card.cont != null ? card.cont.title : card.name, fx.heroTitle, 940);
        if (t != null) {
            s.meta = Fx.fit(t.metaLine() + (t.ratingText().isEmpty() ? "" : " \u00B7 " + (t.imdbRating ? "IMDb " : "TMDB ") + t.ratingText()), fx.heroMeta, 940);
            s.overview = overviews.get(key);
            if (s.overview == null) {
                s.overview = Fx.layout(t.overview, fx.heroOv, 900, 2, Layout.Alignment.ALIGN_NORMAL, 45 - fx.heroOv.getFontSpacing());
                overviews.put(key, s.overview);
            }
            s.url = t.backdropUrl(1280);
            List<String> seen = new ArrayList<>();
            for (Title.Provider p : t.providers) {
                if (p.installed && Title.Provider.STREAM.equals(p.kind) && !seen.contains(p.name) && !"Stremio".equals(p.name) && seen.size() < 3) seen.add(p.name);
            }
            for (String n : seen) s.chips.add(n);
            if (t.stremio) s.chips.add("Stremio");
        } else if (card.cont != null) {
            s.meta = Fx.fit(card.cont.subtitle, fx.heroMeta, 940);
            s.url = card.cont.backdropUrl(1280);
            s.chips.add(card.cont.sourceName);
        }
        float x = 96 + fx.heroWt.measureText(Ui.tr("Watch on")) + 18;
        s.chipsStart = x;
        for (String n : s.chips) s.chipW.add(54 + fx.chip.measureText(n) + 22);
        old = cur;
        cur = s;
        fadeAt = SystemClock.uptimeMillis();
        home.handler().removeCallbacks(load);
        Images.cancel(TAG);
        Bitmap hit = s.url.isEmpty() ? null : Images.peek(s.url, imgW, imgH);
        if (hit != null) {
            s.img = hit;
            s.imgAt = fadeAt - FADE_MS;
        } else if (!s.url.isEmpty()) {
            home.handler().postDelayed(load, DEBOUNCE_MS);
        }
        home.wake();
    }

    private void loadNow() {
        final Slide s = cur;
        if (s.url.isEmpty() || s.img != null) return;
        Images.get(s.url, imgW, imgH, TAG, (url, b) -> {
            if (b == null || s != cur) return;
            s.img = b;
            s.imgAt = SystemClock.uptimeMillis();
            home.wake();
        });
    }

    /** Frees the pictures (home paused or memory low); the next show() asks again. */
    void drop() {
        home.handler().removeCallbacks(load);
        Images.cancel(TAG);
        cur.img = null;
        old = null;
        overviews.evictAll();
        cur.key = "";
    }

    int loaded() { return (cur.img != null ? 1 : 0) + (old != null && old.img != null ? 1 : 0); }

    /** True while a fade runs (the home keeps drawing). */
    boolean moving(long now) {
        boolean m = now - fadeAt < FADE_MS || (cur.img != null && now - cur.imgAt < FADE_MS);
        if (!m && old != null) old = null;
        return m;
    }

    void draw(Canvas c, float alpha) {
        long now = SystemClock.uptimeMillis();
        float t = ease((now - fadeAt) / (float) FADE_MS);
        if (old != null && t >= 1) old = null;
        if (old != null) image(c, old, (1 - t) * alpha, now);
        image(c, cur, (old == null ? 1 : t) * alpha, now);
        // The page colors over the picture's left and bottom edges.
        boolean night = fx.night > 0.001f;
        float a = Math.min(1, alpha > 0.999f ? 1 : alpha);
        fx.bitmap.setAlpha(Math.round(255 * (1 - fx.night) * a));
        fx.rect2.set(X, 0, X + W, H);
        if (fx.night < 0.999f) c.drawBitmap(fx.heroFade(false), null, fx.rect2, fx.bitmap);
        if (night) {
            fx.bitmap.setAlpha(Math.round(255 * fx.night * a));
            c.drawBitmap(fx.heroFade(true), null, fx.rect2, fx.bitmap);
        }
        if (old != null) text(c, old, (1 - t) * alpha);
        text(c, cur, (old == null ? 1 : t) * alpha);
    }

    private void image(Canvas c, Slide s, float a, long now) {
        if (s.img == null || a <= 0.004f) return;
        float f = ease((now - s.imgAt) / (float) FADE_MS);
        fx.coverImage(c, s.img, X, 0, X + W, H, Math.round(255 * a * f));
    }

    private void text(Canvas c, Slide s, float a) {
        if (a <= 0.004f || s.title.isEmpty()) return;
        fx.heroTitle.setColor(Fx.alpha(fx.ink(), a));
        c.drawText(s.title, 96, Fx.base(170, 86, fx.heroTitle), fx.heroTitle);
        fx.heroMeta.setColor(Fx.alpha(fx.tone(0xFF3A3A3F, 0xFFD8D8DC), a));
        c.drawText(s.meta, 96, Fx.base(268, 40, fx.heroMeta), fx.heroMeta);
        if (s.overview != null) {
            fx.heroOv.setColor(Fx.alpha(fx.tone(0xFF2A2A2E, 0xFFE8E8EC), a));
            c.save();
            c.translate(96, 326);
            s.overview.draw(c);
            c.restore();
        }
        if (s.chips.isEmpty()) return;
        fx.heroWt.setColor(Fx.alpha(fx.tone(0xFF3A3A3F, 0xFFD8D8DC), a));
        c.drawText(Ui.tr("Watch on"), 96, Fx.base(446, 56, fx.heroWt), fx.heroWt);
        float x = s.chipsStart;
        for (int i = 0; i < s.chips.size(); i++) {
            float w = s.chipW.get(i);
            fx.fill.setColor(Fx.alpha(fx.tone(Fx.INK, Fx.WHITE), a));
            fx.rect.set(x, 446, x + w, 502);
            c.drawRoundRect(fx.rect, 28, 28, fx.fill);
            int fg = fx.tone(0xFFFFFFFF, Fx.INK);
            fx.symbol(c, "check", x + 30, 474, 23, Fx.alpha(fg, a));
            fx.chip.setColor(Fx.alpha(fg, a));
            c.drawText(s.chips.get(i), x + 54, Fx.base(446, 56, fx.chip), fx.chip);
            x += w + 14;
        }
    }

    static float ease(float t) {
        t = Ui.clamp(t);
        return t * t * (3 - 2 * t);
    }
}
