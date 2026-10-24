package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows under the hero: Continue (landscape cards, up to 4), Movies and Shows (up to 15 each). Geometry is the mock's
 * (1920 x 1080 design space, mock.css). Only the cards on screen, and one more each side, hold a picture; the rows
 * next to the focused one keep two; every other card draws a gradient and its title (windowed loading, cancelled by
 * the card as its tag).
 */
final class HomeRows {
    static final int CONTINUE = 0, MOVIES = 1, SHOWS = 2, COUNT = 3;
    static final float LABEL_H = 48, CARD_TOP = 72, GAP_BLOCK = 0;
    /** Space each row takes (label, cards, two caption lines). */
    static final float[] BLOCK = {430, 400, 400};
    static final float SHELF_BLOCK = 330;

    static final class Card {
        Title title;
        ContinueItem cont;
        String name = "", line2 = "", badge = "", left = "";
        float progress, badgeW;
        float w, h;
        LinearGradient gradient;
        StaticLayout phText;
        String url = "";
        Bitmap bmp;
        BitmapShader shader;
        boolean want, loading;
        final Spring focus = new Spring(0.2f, 1f, 0);

        String key() { return title != null ? title.key() : cont != null ? "c:" + cont.title + cont.at : ""; }
    }

    static final class Row {
        String label = "";
        final List<Card> cards = new ArrayList<>();
        float w, h, pitch;
        int col;
        String counter = "";
        int counterCol = -1, counterN = -1;
        final Spring x = new Spring(0.24f, 1f, 0);
    }

    private final HomeView home;
    private final Fx fx;
    final Row[] rows = new Row[COUNT];

    HomeRows(HomeView home, Fx fx) {
        this.home = home;
        this.fx = fx;
        for (int i = 0; i < COUNT; i++) rows[i] = new Row();
        rows[CONTINUE].w = 412; rows[CONTINUE].h = 232; rows[CONTINUE].pitch = 440;
        rows[MOVIES].w = 360; rows[MOVIES].h = 203; rows[MOVIES].pitch = 392;
        rows[SHOWS].w = 360; rows[SHOWS].h = 203; rows[SHOWS].pitch = 392;
        relabel();
    }

    /** Row names in the current language (called again when the language changes). */
    void relabel() {
        rows[CONTINUE].label = Ui.tr("Continue");
        rows[MOVIES].label = Ui.tr("Movies");
        rows[SHOWS].label = Ui.tr("Shows");
    }

    boolean has(int r) { return r >= 0 && r < COUNT && !rows[r].cards.isEmpty(); }

    int first() {
        for (int r = 0; r < COUNT; r++) if (has(r)) return r;
        return -1;
    }

    int last() {
        for (int r = COUNT - 1; r >= 0; r--) if (has(r)) return r;
        return -1;
    }

    /** Where row `r` (or the shelf, r == COUNT) starts, in a column that has only the rows that exist. */
    float top(int r) {
        float y = 0;
        for (int i = 0; i < Math.min(r, COUNT); i++) if (has(i)) y += BLOCK[i];
        return y;
    }

    // MARK: - Data

    /** New lists: the cards are made again (their pictures cancelled); each row keeps its column if it can. */
    void setData(List<ContinueItem> cont, List<Title> movies, List<Title> shows) {
        for (Row row : rows) for (Card c : row.cards) release(c);
        fill(rows[CONTINUE], cont, null);
        fill(rows[MOVIES], null, movies);
        fill(rows[SHOWS], null, shows);
        for (Row row : rows) {
            row.col = Math.max(0, Math.min(row.col, row.cards.size() - 1));
            row.x.snap(stripX(row, row.col));
        }
    }

    private void fill(Row row, List<ContinueItem> cont, List<Title> titles) {
        row.cards.clear();
        int n = cont != null ? cont.size() : titles.size();
        for (int i = 0; i < n; i++) {
            Card c = new Card();
            c.w = row.w;
            c.h = row.h;
            if (cont != null) {
                ContinueItem it = cont.get(i);
                c.cont = it;
                c.name = it.title;
                c.badge = it.sourceName;
                c.badgeW = fx.cardTag.measureText(c.badge) + 32;
                c.progress = it.progress;
                String sub = it.subtitle == null ? "" : it.subtitle;
                c.line2 = sub;
                if (it.progress > 0) {
                    c.left = sub;
                    int dot = sub.lastIndexOf(" \u00B7 ");
                    if (dot >= 0) c.line2 = sub.substring(dot + 3);
                }
                c.url = it.backdropUrl(Math.round(c.w));
            } else {
                Title t = titles.get(i);
                c.title = t;
                c.name = t.title;
                c.line2 = t.cardLine();
                c.url = t.backdropUrl(300);   // w300: the smallest TMDB size, ~1.2x up on a 360 px card, a third less memory than w500
            }
            c.name = Fx.fit(c.name, fx.cardT, c.w);
            c.line2 = Fx.fit(c.line2, fx.cardM, c.w);
            c.left = Fx.fit(c.left, fx.cardLeft, c.w - 48);
            String full = cont != null ? cont.get(i).title : titles.get(i).title;
            c.gradient = Fx.placeholder(full, c.w, c.h);
            c.phText = Fx.layout(full, fx.cardPh, Math.round(c.w - 40), 2, Layout.Alignment.ALIGN_CENTER, 8);
            row.cards.add(c);
        }
    }

    /** Matches a Continue item to a title of the rows (for the hero's overview and ways to watch). */
    Title match(ContinueItem it) {
        if (it == null) return null;
        for (int r = MOVIES; r <= SHOWS; r++) {
            for (Card c : rows[r].cards) {
                Title t = c.title;
                if (t == null) continue;
                if ((it.tmdbId != 0 && t.tmdbId == it.tmdbId && t.kind.equals(it.kind))
                        || (!it.imdbId.isEmpty() && it.imdbId.equals(t.imdbId))) return t;
            }
        }
        return null;
    }

    /** A title got newer data (details): the card's text is made again, the picture kept. */
    void replace(Title old, Title now) {
        for (int r = MOVIES; r <= SHOWS; r++) {
            for (Card c : rows[r].cards) {
                if (c.title == old || (c.title != null && c.title.key().equals(old.key()))) {
                    c.title = now;
                    c.line2 = Fx.fit(now.cardLine(), fx.cardM, c.w);
                }
            }
        }
    }

    Card card(int r, int c) {
        if (!has(r)) return null;
        Row row = rows[r];
        return row.cards.get(Math.max(0, Math.min(c, row.cards.size() - 1)));
    }

    // MARK: - Windowed pictures

    private void release(Card c) {
        if (c.loading || c.bmp != null) Images.cancel(c);
        c.bmp = null;
        c.shader = null;
        c.loading = false;
        c.want = false;
    }

    private void want(Card c, boolean on, float k) {
        if (on == c.want) return;
        c.want = on;
        if (!on) {
            release(c);
            return;
        }
        if (c.url.isEmpty() || c.bmp != null || c.loading) return;
        c.loading = true;
        final Card card = c;
        boolean sync = Images.get(c.url, Math.round(c.w * k), Math.round(c.h * k), c, (url, b) -> {
            card.loading = false;
            if (!card.want || b == null) return;
            card.bmp = b;
            card.shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            home.wake();
        });
        if (!sync) c.loading = false;
    }

    /**
     * Focused row: every card on screen (where the strip is going) and one either side; the rows next to it: the focused
     * column and the next; the rest none. `focusRow` -1 (or beyond the rows) = the shelf or another screen; `all` false
     * with `focusRow` == -99 drops everything.
     */
    void window(int focusRow, float k) {
        for (int r = 0; r < COUNT; r++) {
            Row row = rows[r];
            int n = row.cards.size(), lo = 0, hi = -1, dist = focusRow == -99 ? 99 : Math.abs(r - focusRow);
            if (dist == 0) {
                float tx = stripX(row, row.col);
                lo = n;
                hi = -1;
                for (int j = 0; j < n; j++) {
                    float x0 = 96 + j * row.pitch + tx;
                    if (x0 + row.w > 0 && x0 < 1920) {
                        lo = Math.min(lo, j);
                        hi = Math.max(hi, j);
                    }
                }
                lo = Math.max(0, lo - 1);
                hi = Math.min(n - 1, hi + 1);
            } else if (dist == 1) {
                lo = row.col;
                hi = Math.min(n - 1, row.col + 1);
            }
            for (int j = 0; j < n; j++) want(row.cards.get(j), j >= lo && j <= hi, k);
        }
    }

    /** Frees every picture (low memory, or leaving the home screen). */
    void dropAll() {
        for (Row row : rows) for (Card c : row.cards) release(c);
    }

    int loaded() {
        int n = 0;
        for (Row row : rows) for (Card c : row.cards) if (c.bmp != null) n++;
        return n;
    }

    // MARK: - Motion

    static float stripX(Row row, int col) {
        float max = Math.max(0, 96 + row.cards.size() * row.pitch - 28 - 1920 + 96);
        return -Math.min(max, Math.max(0, col - 1) * row.pitch);
    }

    /** Aims the focus springs: only (`fr`, `fc`) at 1 (when `active`), and the strips. Returns nothing; stepping is separate. */
    void aim(int fr, boolean active) {
        for (int r = 0; r < COUNT; r++) {
            Row row = rows[r];
            row.x.target = stripX(row, row.col);
            if (row.counterCol != row.col || row.counterN != row.cards.size()) {
                row.counterCol = row.col;
                row.counterN = row.cards.size();
                row.counter = Ui.trf("{i} / {n}", "i", row.col + 1, "n", row.cards.size());
            }
            for (int j = 0; j < row.cards.size(); j++) row.cards.get(j).focus.target = active && r == fr && j == row.col ? 1 : 0;
        }
    }

    void snapFocus() {
        for (Row row : rows) {
            row.x.snap(row.x.target);
            for (Card c : row.cards) c.focus.snap(c.focus.target);
        }
    }

    boolean step(float dt) {
        boolean moving = false;
        for (Row row : rows) {
            moving |= row.x.step(dt);
            for (int j = 0; j < row.cards.size(); j++) moving |= row.cards.get(j).focus.step(dt);
        }
        return moving;
    }

    // MARK: - Drawing (design space; `y` is where the row starts on screen)

    void drawRow(Canvas c, int r, float y, int focusRow, float alphaMul) {
        Row row = rows[r];
        if (row.cards.isEmpty()) return;
        float a = alphaMul * Ui.clamp((y - 540) / 80f);
        if (a <= 0.01f) return;
        int ia = Math.round(255 * a);
        fx.rowLabel.setColor(Fx.alpha(fx.ink(), a));
        c.drawText(row.label, 96, Fx.base(y, LABEL_H, fx.rowLabel), fx.rowLabel);
        if (r == focusRow) {
            fx.rowCount.setColor(Fx.alpha(fx.meta(), a));
            c.drawText(row.counter, 1824, Fx.base(y, LABEL_H, fx.rowCount), fx.rowCount);
        }
        float cardY = y + CARD_TOP, tx = row.x.value;
        int n = row.cards.size();
        for (int j = 0; j < n; j++) {
            if (j == row.col) continue;
            float x = 96 + j * row.pitch + tx;
            if (x + row.w < -40 || x > 1960) continue;
            drawCard(c, row, row.cards.get(j), x, cardY, ia, a);
        }
        float x = 96 + row.col * row.pitch + tx;
        if (x + row.w >= -40 && x <= 1960) drawCard(c, row, row.cards.get(row.col), x, cardY, ia, a);
    }

    private void drawCard(Canvas c, Row row, Card cd, float x, float y, int ia, float a) {
        float w = cd.w, h = cd.h, f = cd.focus.value, fc = Ui.clamp(f), s = 1 + 0.05f * f, rad = 20;
        // Captions: not scaled.
        fx.cardT.setColor(Fx.alpha(fx.ink(), a));
        fx.cardM.setColor(Fx.alpha(fx.dim(), a));
        c.drawText(cd.name, x, Fx.base(y + h + 22, 34, fx.cardT), fx.cardT);
        c.drawText(cd.line2, x, Fx.base(y + h + 60, 30, fx.cardM), fx.cardM);
        c.save();
        c.translate(x + w / 2, y + h / 2);
        c.scale(s, s);
        c.translate(-w / 2, -h / 2);
        fx.drop(c, 0, 0, w, h, rad, 4, 12, 0.18f * a);
        if (cd.bmp != null) {
            fx.roundImage(c, cd.bmp, cd.shader, w, h, rad, ia);
        } else {
            fx.fill.setShader(cd.gradient);
            fx.fill.setAlpha(ia);
            fx.rect.set(0, 0, w, h);
            c.drawRoundRect(fx.rect, rad, rad, fx.fill);
            fx.fill.setShader(null);
            cd.phText.getPaint().setColor(Fx.alpha(0xFFFFFFFF, a));
            c.save();
            c.translate(20, (h - cd.phText.getHeight()) / 2f);
            cd.phText.draw(c);
            c.restore();
        }
        if (cd.cont != null) drawContinue(c, cd, w, h, rad, ia, a);
        if (fc > 0.01f) fx.ring(c, 0, 0, w, h, rad, fc * a);
        c.restore();
    }

    private void drawContinue(Canvas c, Card cd, float w, float h, float rad, int ia, float a) {
        Fx f = fx;
        if (cd.progress > 0) {
            // Shade under the text and the bar along the bottom edge (cut by a clip so the rounded corners stay).
            f.fill.setColor(Fx.alpha(0xFF000000, 0.55f * a));
            c.save();
            c.clipRect(0, h - 120, w, h);
            f.rect.set(0, h - 140, w, h);
            c.drawRoundRect(f.rect, rad, rad, f.fill);
            c.restore();
            f.cardLeft.setColor(Fx.alpha(0xFFFFFFFF, a));
            c.drawText(cd.left, 24, Fx.base(h - 34 - 40, 40, f.cardLeft), f.cardLeft);
            c.save();
            c.clipRect(0, h - 12, w, h);
            f.fill.setColor(Fx.alpha(0xFFFFFFFF, 0.4f * a));
            f.rect.set(0, h - 40, w, h);
            c.drawRoundRect(f.rect, rad, rad, f.fill);
            c.restore();
            c.save();
            c.clipRect(0, h - 12, w * Math.min(1, cd.progress), h);
            f.fill.setColor(Fx.alpha(0xFFFFFFFF, a));
            c.drawRoundRect(f.rect, rad, rad, f.fill);
            c.restore();
        }
        if (!cd.badge.isEmpty()) {
            f.fill.setColor(Fx.alpha(0xFF000000, 0.78f * a));
            f.rect.set(16, 16, 16 + cd.badgeW, 56);
            c.drawRoundRect(f.rect, 20, 20, f.fill);
            f.cardTag.setColor(Fx.alpha(0xFFFFFFFF, a));
            c.drawText(cd.badge, 32, Fx.base(16, 40, f.cardTag), f.cardTag);
        }
    }
}
