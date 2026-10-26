package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The search screen (the mock's search.js): the query field and a mic chip on top, an on-screen keyboard on the left and
 * the results on the right as a vertical list of large tiles (the first is the "Best match"; apps are compact tiles at the
 * end). Live results from 3 letters with a 300 ms pause (Search.query, the one before cancelled). Before typing: your last
 * 5 searches and what is trending.
 */
final class SearchScreen {
    private static final int FIELD = 0, KEYS = 1, RESULTS = 2;
    private static final int IDLE = 0, LOADING = 1, DONE = 2;
    private static final int MODE_IDLE = 0, MODE_EMPTY = 1, MODE_LIST = 2;
    private static final float RX = 720, RY = 190, TILE_W = 1104, TILE_H = 340, APP_H = 120, GAP = 32;
    /** How far the screen ducks down while the recognizer overlay holds the top. */
    private static final float VOICE_DY = 220;
    private static final long DEBOUNCE_MS = 300;

    private static final class Key {
        final String label, value;
        final int r, c, span;
        Key(String label, String value, int r, int c, int span) { this.label = label; this.value = value; this.r = r; this.c = c; this.span = span; }
    }

    private static final class Chip {
        String text;
        float x, y, w;
    }

    private static final class Label {
        String text;
        float y;
        int style;   // 0 = row label, 1 = note, 2 = the empty-state headline
    }

    private static final class Tile {
        Title title;
        Search.AppMatch app;
        String tag = "", name = "", meta = "", url = "";
        StaticLayout overview;
        final List<String> chips = new ArrayList<>();
        final List<Float> chipW = new ArrayList<>();
        float y, h;
        LinearGradient gradient;
        Bitmap bmp;
        BitmapShader shader;
        boolean want, loading;
        final Spring focus = new Spring(0.2f, 1f, 0);
    }

    private final HomeView home;
    private final Fx fx;
    final Spring anim = new Spring(0.14f, 1f, 0);
    boolean open;
    private String query = "", shown = "";
    private int area = KEYS, kr, kc, resRow, resIdx;
    private int mode = MODE_IDLE;
    private int state = IDLE;
    private boolean hasLast, focusFirst;
    private final List<Key> keys = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<List<Chip>> chipRows = new ArrayList<>();
    private final List<Tile> tiles = new ArrayList<>();
    private final Spring listY = new Spring(0.2f, 1f, 0);
    private boolean caret = true;
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (!open) return;
            caret = !caret;
            home.postInvalidate();
            home.handler().postDelayed(this, 530);
        }
    };
    private final Runnable run = this::runQuery;
    private List<String> trending = new ArrayList<>();
    private String notice = "", counter = "";
    private final Shader shade = new LinearGradient(0, 0, TILE_W, 0, new int[] {0xEB000000, 0xD1000000, 0x40000000}, new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
    private final String placeholder, micLabel;
    private final String tagBest, tagMovie, tagShow, tagApp, openApp;

    SearchScreen(HomeView home, Fx fx) {
        this.home = home;
        this.fx = fx;
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 36; i++) keys.add(new Key(String.valueOf(chars.charAt(i)).toUpperCase(), String.valueOf(chars.charAt(i)), i / 6, i % 6, 1));
        keys.add(new Key(Ui.tr("Space").toUpperCase(Ui.locale()), " ", 6, 0, 2));
        keys.add(new Key(Ui.tr("Delete").toUpperCase(Ui.locale()), "del", 6, 2, 2));
        keys.add(new Key(Ui.tr("Clear").toUpperCase(Ui.locale()), "clear", 6, 4, 2));
        placeholder = Ui.tr("Search movies, shows and apps");
        micLabel = Ui.tr("Mic");
        tagBest = Ui.tr("Best match");
        tagMovie = Ui.tr("Movie");
        tagShow = Ui.tr("Show");
        tagApp = Ui.tr("App");
        openApp = Ui.tr("Open app");
    }

    // MARK: - Open and close

    void open(List<String> trendingTitles) {
        open = true;
        Search.warm(home.context());
        trending = trendingTitles;
        query = "";
        shown = "";
        state = IDLE;
        hasLast = focusFirst = false;
        area = KEYS;
        kr = kc = 0;
        resRow = resIdx = 0;
        notice = "";
        anim.target = 1;
        home.handler().removeCallbacks(blink);
        home.handler().postDelayed(blink, 530);
        layoutResults(null);
        home.wake();
    }

    void close() {
        open = false;
        anim.target = 0;
        home.handler().removeCallbacks(run);
        home.handler().removeCallbacks(blink);
        Search.cancel();
        releaseTiles();
        tiles.clear();
        home.wake();
    }

    boolean visible() { return open || anim.value > 0.002f; }

    boolean step(float dt) {
        boolean m = anim.step(dt) | listY.step(dt);
        for (int i = 0; i < tiles.size(); i++) m |= tiles.get(i).focus.step(dt);
        return m;
    }

    /** True when something other than the search is hidden behind it (the home isn't drawn then). */
    boolean covers() { return anim.value > 0.999f && open; }

    // MARK: - Query

    /** A key typed on the TV's keyboard or the on-screen one. */
    void type(String value) {
        String q = query;
        if (value.equals("del")) {
            if (!q.isEmpty()) q = q.substring(0, q.length() - 1);
        } else if (value.equals("clear")) {
            q = "";
        } else if (q.length() < 40) {
            q = q + value;
        }
        setQuery(q, false);
    }

    boolean hasQuery() { return !query.isEmpty(); }

    /** Voice result: fills the query, searches now and puts the focus on the first result when it arrives. */
    void voice(String text) {
        focusFirst = true;
        setQuery(text, true);
    }

    /** No speech recognizer on the TV: the cursor goes to the field so the person types instead. */
    void focusField() {
        area = FIELD;
        retarget();
        home.wake();
    }

    /** The one way the query changes (keys, hardware keyboard, voice, chips): schedules the search (now, or after 300 ms). */
    private void setQuery(String text, boolean now) {
        query = text.length() > 40 ? text.substring(0, 40) : text;
        shown = Fx.fit(query, fx.sq, 1040);
        home.handler().removeCallbacks(run);
        // 3+ letters search the network; 1-2 letters match only apps on the TV (Search.query answers
        // those without touching /search/multi).
        if (query.trim().length() >= 1) {
            state = LOADING;
            notice = "";
            if (!hasLast) layoutResults(null);   // nothing to keep on screen: show "Searching\u2026"
            if (now) runQuery(); else home.handler().postDelayed(run, DEBOUNCE_MS);
        } else {
            state = IDLE;
            hasLast = false;
            Search.cancel();
            notice = "";
            layoutResults(null);
        }
        home.wake();
    }

    private void runQuery() {
        final String asked = query.trim();
        if (asked.length() < 1) return;
        state = LOADING;
        Search.query(home.context(), asked, result -> {
            if (!open || !asked.equals(query.trim())) return;
            hasLast = true;
            state = result.partial ? LOADING : DONE;
            notice = result.failed ? Ui.tr("Can't search right now. Check the internet.") : "";
            layoutResults(result);
            if (!result.partial && !result.failed && asked.length() >= 3 && (!result.titles.isEmpty() || !result.apps.isEmpty())) Prefs.addRecentSearch(asked);
            if (focusFirst && (!result.titles.isEmpty() || !result.apps.isEmpty())) {
                focusFirst = false;
                area = RESULTS;
                resRow = resIdx = 0;
                retarget();
            }
            home.wake();
        });
    }

    // MARK: - Results layout

    private void releaseTiles() {
        for (Tile t : tiles) {
            if (t.loading || t.bmp != null) Images.cancel(t);
            t.bmp = null;
            t.shader = null;
            t.loading = t.want = false;
        }
    }

    private void layoutResults(Search.Result r) {
        releaseTiles();
        tiles.clear();
        labels.clear();
        chipRows.clear();
        int len = query.trim().length();
        if (len < 3 && (r == null || (r.titles.isEmpty() && r.apps.isEmpty()))) {
            mode = MODE_IDLE;
            float y = 0;
            if (len == 1) {
                note(Ui.tr("Type 3 or more letters to search."), 0);
                y = 60;
            }
            label(Ui.tr("Recent searches"), y, 0);
            List<String> rec = Prefs.recentSearches();
            if (!rec.isEmpty()) {
                y = chips(rec, y + 64) + 24;
            } else {
                note(Ui.tr("Nothing yet. Your last 5 searches show up here."), y + 60);
                y += 150;
            }
            label(Ui.tr("Trending"), y, 0);
            chips(trending, y + 64);
        } else if (r == null) {
            mode = MODE_EMPTY;
            label(Ui.tr("Searching\u2026"), 0, 2);
        } else if (r.titles.isEmpty() && r.apps.isEmpty() && r.partial) {
            mode = MODE_EMPTY;
            label(Ui.tr("Searching\u2026"), 0, 2);
        } else if (r.titles.isEmpty() && r.apps.isEmpty()) {
            mode = MODE_EMPTY;
            label(Ui.trf("No results for \u2018{q}\u2019.", "q", Fx.fit(query.trim(), fx.sEmpty, 700)), 0, 2);
            note(notice.isEmpty() ? Ui.tr("Try a shorter word.") : notice, 90);
            label(Ui.tr("Trending"), 190, 0);
            chips(trending, 254);
        } else {
            mode = MODE_LIST;
            float y = 12;
            List<Object> list = new ArrayList<>();
            if (r.titles.isEmpty()) list.addAll(r.apps); else { list.addAll(r.titles); list.addAll(r.apps); }
            for (int i = 0; i < list.size(); i++) {
                Tile t = new Tile();
                Object o = list.get(i);
                if (o instanceof Title) makeTitle(t, (Title) o, i == 0); else makeApp(t, (Search.AppMatch) o);
                t.y = y;
                y += t.h + GAP;
                tiles.add(t);
            }
            if (area != RESULTS) listY.snap(0);
        }
        if (area == RESULTS && !hasResults()) area = KEYS;
        resRow = Math.min(resRow, Math.max(0, rowCount() - 1));
        resIdx = Math.min(resIdx, Math.max(0, rowLen(resRow) - 1));
        retarget();
    }

    private void makeTitle(Tile t, Title title, boolean best) {
        t.title = title;
        t.h = TILE_H;
        String kind = title.isMovie() ? tagMovie : tagShow;
        t.tag = best ? tagBest + " \u00B7 " + kind : kind;
        t.name = Fx.fit(title.title, fx.shTitle, 900);
        t.meta = Fx.fit(title.metaLine() + (title.ratingText().isEmpty() ? "" : " \u00B7 " + (title.imdbRating ? "IMDb " : "TMDB ") + title.ratingText()), fx.shMeta, 900);
        t.overview = Fx.layout(title.overview, fx.shOv, 760, 2, Layout.Alignment.ALIGN_NORMAL, 40 - fx.shOv.getFontSpacing());
        t.url = title.backdropUrl(1280);
        t.gradient = Fx.placeholder(title.title, TILE_W, TILE_H);
        List<String> seen = new ArrayList<>();
        for (Title.Provider p : title.providers) if (p.installed && Title.Provider.STREAM.equals(p.kind) && !"Stremio".equals(p.name) && !seen.contains(p.name) && seen.size() < 3) seen.add(p.name);
        t.chips.addAll(seen);
        if (title.stremio) t.chips.add("Stremio");
        for (String n : t.chips) t.chipW.add(54 + fx.chip.measureText(n) + 22);
    }

    private void makeApp(Tile t, Search.AppMatch app) {
        t.app = app;
        t.h = APP_H;
        t.tag = tagApp;
        t.name = Fx.fit(app.label, fx.shTitle, 420);
        t.meta = openApp;
        t.gradient = Fx.placeholder(app.label, TILE_W, APP_H);
    }

    private void label(String text, float y, int style) {
        Label l = new Label();
        l.text = text;
        l.y = y;
        l.style = style;
        labels.add(l);
    }

    private void note(String text, float y) { label(text, y, 1); }

    private float chips(List<String> list, float y) {
        float x = 0;
        List<Chip> line = new ArrayList<>();
        for (String s : list) {
            Chip c = new Chip();
            c.text = Fx.fit(s, fx.schip, 600);
            c.w = fx.schip.measureText(c.text) + 64;
            if (x + c.w > 1100 && !line.isEmpty()) {
                chipRows.add(line);
                line = new ArrayList<>();
                x = 0;
                y += 80;
            }
            c.x = x;
            c.y = y;
            line.add(c);
            x += c.w + 16;
        }
        if (!line.isEmpty()) chipRows.add(line);
        return y + 80;
    }

    private boolean hasResults() { return mode == MODE_LIST ? !tiles.isEmpty() : !chipRows.isEmpty(); }

    private int rowCount() { return mode == MODE_LIST ? tiles.size() : chipRows.size(); }

    private int rowLen(int r) { return mode == MODE_LIST ? 1 : (r < chipRows.size() ? chipRows.get(r).size() : 0); }

    // MARK: - Keys

    private Key keyAt(int r, int c) {
        for (Key k : keys) if (k.r == r && c >= k.c && c < k.c + k.span) return k;
        return null;
    }

    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            default: return false;
        }
    }

    private void move(int dx, int dy) {
        int before = area * 10000 + kr * 100 + kc + resRow * 7 + resIdx * 3;
        if (area == FIELD) {
            if (dy > 0) area = KEYS;
        } else if (area == KEYS) {
            Key k = keyAt(kr, kc);
            if (dx < 0) {
                Key n = keyAt(kr, k.c - 1);
                if (n != null) kc = n.c;
            } else if (dx > 0) {
                Key n = keyAt(kr, k.c + k.span);
                if (n != null) kc = n.c;
                else if (hasResults()) { area = RESULTS; resRow = 0; resIdx = 0; }
            } else if (dy < 0) {
                if (kr == 0) area = FIELD; else { kr--; kc = keyAt(kr, k.c).c; }
            } else if (kr < 6) {
                kr++;
                kc = keyAt(kr, k.c).c;
            }
        } else if (area == RESULTS) {
            if (dx < 0) { if (resIdx > 0) resIdx--; else area = KEYS; }
            else if (dx > 0) resIdx = Math.min(rowLen(resRow) - 1, resIdx + 1);
            else if (dy < 0) {
                if (resRow > 0) { resRow--; resIdx = Math.min(resIdx, rowLen(resRow) - 1); } else area = FIELD;
            } else if (resRow < rowCount() - 1) {
                resRow++;
                resIdx = Math.min(resIdx, rowLen(resRow) - 1);
            }
        }
        int after = area * 10000 + kr * 100 + kc + resRow * 7 + resIdx * 3;
        if (after != before) home.click();
        retarget();
        home.wake();
    }

    private void retarget() {
        counter = Ui.trf("{i} / {n}", "i", resRow + 1, "n", tiles.size());
        for (int i = 0; i < tiles.size(); i++) tiles.get(i).focus.target = mode == MODE_LIST && area == RESULTS && i == resRow ? 1 : 0;
        if (mode == MODE_LIST && !tiles.isEmpty()) {
            int f = area == RESULTS ? resRow : 0;
            Tile cur = tiles.get(f);
            float top = cur.y - 12, bot = cur.y + cur.h + 24;
            if (area == RESULTS) listY.target = f == 0 ? 0 : Math.min(top, bot - 790);
            for (int i = 0; i < tiles.size(); i++) want(tiles.get(i), Math.abs(i - f) <= 2);
        }
    }

    private void want(Tile t, boolean on) {
        if (on == t.want) return;
        t.want = on;
        if (!on) {
            if (t.loading || t.bmp != null) Images.cancel(t);
            t.bmp = null;
            t.shader = null;
            t.loading = false;
            return;
        }
        if (t.url.isEmpty() || t.bmp != null || t.loading) return;
        t.loading = true;
        final Tile tile = t;
        float k = home.scale();
        if (!Images.get(t.url, Math.round(TILE_W * k), Math.round(TILE_H * k), t, (u, b) -> {
            tile.loading = false;
            if (!tile.want || b == null) return;
            tile.bmp = b;
            tile.shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            home.wake();
        })) t.loading = false;
    }

    int loaded() {
        int n = 0;
        for (Tile t : tiles) if (t.bmp != null) n++;
        return n;
    }

    void activate() {
        if (area == FIELD) {
            home.startVoice();
        } else if (area == KEYS) {
            Key k = keyAt(kr, kc);
            if (k != null) type(k.value);
        } else if (mode == MODE_LIST) {
            if (resRow >= tiles.size()) return;
            Tile t = tiles.get(resRow);
            if (t.app != null) {
                Prefs.addRecentSearch(query);
                if (!Launch.app(home.context(), t.app.pkg, home.options(RX, RY + t.y - listY.value, TILE_W, t.h))) {
                    home.showToast(Ui.trf("Couldn't open {app}.", "app", t.app.label));
                }
            } else {
                Prefs.addRecentSearch(query);
                home.openDetails(t.title, null);
            }
        } else if (resRow < chipRows.size() && resIdx < chipRows.get(resRow).size()) {
            String text = chipRows.get(resRow).get(resIdx).text;
            setQuery(text, true);   // saved to recents by runQuery, but only if the search works and finds something
        }
    }

    // MARK: - Drawing (design space)

    void draw(Canvas c) {
        float a = Ui.clamp(anim.value);
        if (a <= 0.002f) return;
        int layer = -1;
        if (a < 0.999f) layer = c.saveLayerAlpha(0, 0, 1920, 1080, Math.round(255 * a));
        // The recognizer's overlay takes the top while listening: the whole screen ducks down under it.
        boolean duck = home.voiceActive();
        if (duck) { c.save(); c.translate(0, VOICE_DY); }
        // Field and mic chip
        fx.glass(c, 96, 40, 1276, 136, 48, 1);
        fx.magnifier(c, 152, 90, fx.ink());
        if (query.isEmpty()) {
            fx.sqPh.setColor(fx.dim());
            c.drawText(placeholder, 196, Fx.base(40, 96, fx.sqPh), fx.sqPh);
            if (caret && area != FIELD) {
                fx.fill.setColor(fx.ink());
                c.drawRect(190, 59, 194, 117, fx.fill);
            }
        } else {
            fx.sq.setColor(fx.ink());
            c.drawText(shown, 196, Fx.base(40, 96, fx.sq), fx.sq);
            if (caret) {
                fx.fill.setColor(fx.ink());
                float x = 196 + fx.sq.measureText(shown) + 4;
                c.drawRect(x, 59, x + 4, 117, fx.fill);
            }
        }
        boolean mf = area == FIELD;
        float ms = mf ? 1.06f : 1;
        c.save();
        c.translate(1434, 88);
        c.scale(ms, ms);
        c.translate(-1434, -88);
        if (mf) fx.lit(c, 1304, 40, 1564, 136, 48, 1); else fx.glass(c, 1304, 40, 1564, 136, 48, 1);
        int fg = mf ? Fx.INK : fx.ink();
        fx.microphone(c, 1334 + 20, 88 + 2, fg);
        fx.mic.setColor(fg);
        fx.mic.setTextAlign(android.graphics.Paint.Align.LEFT);
        c.drawText(micLabel, 1396, Fx.base(40, 96, fx.mic), fx.mic);
        if (mf) fx.ring(c, 1304, 40, 1564, 136, 48, 1);
        c.restore();
        keyboard(c);
        c.save();
        c.translate(RX, RY);
        results(c);
        c.restore();
        if (duck) c.restore();
        if (layer >= 0) c.restoreToCount(layer);
    }

    private void keyboard(Canvas c) {
        for (Key k : keys) {
            float x = 96 + k.c * 92, y = 196 + k.r * 92, w = k.span * 84 + (k.span - 1) * 8, h = 84;
            boolean f = area == KEYS && k.r == kr && k.c == keyAt(kr, kc).c;
            if (f) continue;
            drawKey(c, k, x, y, w, h, false);
        }
        Key k = keyAt(kr, kc);
        if (area == KEYS && k != null) drawKey(c, k, 96 + k.c * 92, 196 + k.r * 92, k.span * 84 + (k.span - 1) * 8, 84, true);
    }

    private void drawKey(Canvas c, Key k, float x, float y, float w, float h, boolean f) {
        c.save();
        if (f) {
            c.translate(x + w / 2, y + h / 2);
            c.scale(1.1f, 1.1f);
            c.translate(-(x + w / 2), -(y + h / 2));
            fx.lit(c, x, y, x + w, y + h, 20, 1);
        } else {
            fx.drop(c, x, y, x + w, y + h, 20, 4, 12, fx.tone(0.1f, 0.3f));
            fx.glass(c, x, y, x + w, y + h, 20, 1);
        }
        android.text.TextPaint p = k.span > 1 ? fx.keySmall : fx.key;
        p.setColor(f ? Fx.INK : fx.ink());
        c.drawText(k.label, x + w / 2, Fx.base(y, h, p), p);
        if (f) fx.ring(c, x, y, x + w, y + h, 20, 1);
        c.restore();
    }

    private void results(Canvas c) {
        for (Label l : labels) {
            android.text.TextPaint p = l.style == 0 ? fx.rowLabel : l.style == 1 ? fx.sNote : fx.sEmpty;
            p.setColor(l.style == 1 ? fx.meta() : fx.ink());
            float h = l.style == 0 ? 48 : l.style == 1 ? 40 : 80;
            c.drawText(l.text, 0, Fx.base(l.y, h, p), p);
        }
        for (int r = 0; r < chipRows.size(); r++) {
            List<Chip> row = chipRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                Chip ch = row.get(i);
                boolean f = area == RESULTS && mode != MODE_LIST && r == resRow && i == resIdx;
                c.save();
                if (f) {
                    c.translate(ch.x + ch.w / 2, ch.y + 32);
                    c.scale(1.06f, 1.06f);
                    c.translate(-(ch.x + ch.w / 2), -(ch.y + 32));
                    fx.lit(c, ch.x, ch.y, ch.x + ch.w, ch.y + 64, 32, 1);
                } else {
                    fx.glass(c, ch.x, ch.y, ch.x + ch.w, ch.y + 64, 32, 1);
                }
                fx.schip.setColor(f ? Fx.INK : fx.ink());
                c.drawText(ch.text, ch.x + ch.w / 2, Fx.base(ch.y, 64, fx.schip), fx.schip);
                if (f) fx.ring(c, ch.x, ch.y, ch.x + ch.w, ch.y + 64, 32, 1);
                c.restore();
            }
        }
        if (mode != MODE_LIST || tiles.isEmpty()) return;
        if (area == RESULTS) {
            fx.count.setColor(fx.ink());
            c.drawText(counter, 1104, Fx.base(-54, 44, fx.count), fx.count);
        }
        c.save();
        c.clipRect(-40, 0, 1144, 800);
        c.translate(0, -listY.value);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            float sy = t.y - listY.value;
            if (sy > 800 || sy + t.h < 0 || i == resRow) continue;
            drawTile(c, t, i == 0);
        }
        if (resRow < tiles.size()) {
            Tile t = tiles.get(resRow);
            float sy = t.y - listY.value;
            if (sy <= 800 && sy + t.h >= 0) drawTile(c, t, resRow == 0);
        }
        c.restore();
    }

    private void drawTile(Canvas c, Tile t, boolean best) {
        float w = TILE_W, h = t.h, f = t.focus.value, s = 1 + 0.03f * f, rad = 28;
        c.save();
        c.translate(w / 2, t.y + h / 2);
        c.scale(s, s);
        c.translate(-w / 2, -h / 2);
        fx.drop(c, 0, 0, w, h, rad, 4, 12, 0.3f);
        if (t.bmp != null) {
            fx.roundImage(c, t.bmp, t.shader, w, h, rad, 255);
        } else {
            fx.fill.setColor(0xFF000000);
            fx.fill.setShader(t.gradient);
            fx.rect.set(0, 0, w, h);
            c.drawRoundRect(fx.rect, rad, rad, fx.fill);
            fx.fill.setShader(null);
        }
        fx.fill.setShader(t.title != null ? shade : null);
        fx.fill.setColor(t.title != null ? 0xFF000000 : 0x66000000);
        c.drawRoundRect(0, 0, w, h, rad, rad, fx.fill);
        fx.fill.setShader(null);
        // Tag
        float tw = fx.shTag.measureText(t.tag) + 36;
        fx.fill.setColor(0xF2FFFFFF);
        fx.rect.set(40, t.app != null ? 40 : 22, 40 + tw, (t.app != null ? 40 : 22) + 40);
        c.drawRoundRect(fx.rect, 20, 20, fx.fill);
        fx.shTag.setColor(Fx.INK);
        c.drawText(t.tag, 58, Fx.base(t.app != null ? 40 : 22, 40, fx.shTag), fx.shTag);
        fx.shTitle.setColor(0xFFFFFFFF);
        if (t.app != null) {
            c.drawText(t.name, 260, Fx.base(30, 60, fx.shTitle), fx.shTitle);
            fx.shMeta.setColor(0xFFFFFFFF);
            fx.shMeta.setTextAlign(android.graphics.Paint.Align.RIGHT);
            c.drawText(t.meta, 1060, Fx.base(30, 60, fx.shMeta), fx.shMeta);
            fx.shMeta.setTextAlign(android.graphics.Paint.Align.LEFT);
        } else {
            c.drawText(t.name, 40, Fx.base(70, 64, fx.shTitle), fx.shTitle);
            fx.shMeta.setColor(0xFFF0F0F2);
            c.drawText(t.meta, 40, Fx.base(136, 36, fx.shMeta), fx.shMeta);
            fx.shOv.setColor(0xFFFFFFFF);
            c.save();
            c.translate(40, 182);
            t.overview.draw(c);
            c.restore();
            float x = 40;
            for (int i = 0; i < t.chips.size(); i++) {
                float cw = t.chipW.get(i);
                fx.fill.setColor(0xFFFFFFFF);
                fx.rect.set(x, 274, x + cw, 330);
                c.drawRoundRect(fx.rect, 28, 28, fx.fill);
                fx.symbol(c, "check", x + 30, 302, 23, Fx.INK);
                fx.chip.setColor(Fx.INK);
                c.drawText(t.chips.get(i), x + 54, Fx.base(274, 56, fx.chip), fx.chip);
                x += cw + 14;
            }
        }
        if (f > 0.01f) fx.ring(c, 0, 0, w, h, rad, Ui.clamp(f));
        c.restore();
    }
}
