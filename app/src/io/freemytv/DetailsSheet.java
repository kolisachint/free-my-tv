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
 * The details sheet (Enter on a movie, a show, a search result or a Continue card that isn't Stremio's): backdrop,
 * poster, title, meta, overview, cast, "Play trailer" and "Watch in <app>", and the ways to watch (installed first,
 * the rest dimmed and said to be "Not installed", Stremio last). Opens at once with what the row had; Recs.details
 * adds the trailer, the cast and every provider (a spinner meanwhile; cancelled when the sheet closes).
 */
final class DetailsSheet {
    private static final float PX = 48, PY = 40, PW = 1824, PH = 1000;
    private static final float WX = 1232, WY = 48, WW = 544, WH = 904;

    private static final class Way {
        String text = "", label = "";
        boolean header, installed, stremio;
        Title.Provider provider;
        float y;
    }

    private final HomeView home;
    private final Fx fx;
    final Spring anim = new Spring(0.22f, 1f, 0);
    boolean open;
    private Title title;
    private ContinueItem cont;
    private Net.Cancel pending;
    private boolean loading;

    private String meta = "", cast = "", btn1 = "", btn2 = "";
    private StaticLayout titleLayout, overview;
    private float btn2W;
    private boolean btn2Enabled;
    private final List<Way> ways = new ArrayList<>();
    private float waysHeight, scrollTarget;
    private final Spring scroll = new Spring(0.2f, 1f, 0);
    private int idx = 1;
    private LinearGradient posterGradient;
    private Bitmap bg, poster;
    private BitmapShader bgShader, posterShader;
    private final Object tag = new Object();
    private final Shader[] fadeL = new Shader[2], fadeB = new Shader[2];
    private final String footnote, waysHead, noData;
    private float spin;

    DetailsSheet(HomeView home, Fx fx) {
        this.home = home;
        this.fx = fx;
        int[] page = {0xFFF5F5F7, 0xFF1C1C1E};
        for (int i = 0; i < 2; i++) {
            int clear = page[i] & 0xFFFFFF;
            fadeL[i] = new LinearGradient(500, 0, 1300, 0, page[i], clear, Shader.TileMode.CLAMP);
            fadeB[i] = new LinearGradient(0, 300, 0, 720, clear, page[i], Shader.TileMode.CLAMP);
        }
        footnote = Ui.tr("Where-to-watch data: JustWatch via TMDB");
        waysHead = Ui.tr("Ways to watch");
        noData = Ui.tr("No streaming data for this title.");
    }

    // MARK: - Open and close

    void open(Title t, ContinueItem from) {
        close(false);
        title = t;
        cont = from;
        open = true;
        loading = false;
        idx = 1;
        scroll.snap(0);
        rebuild();
        requestImages();
        if (t.tmdbId != 0 && !t.full) {
            loading = true;
            final Title asked = t;
            pending = Recs.get(home.context()).details(t, result -> {
                loading = false;
                pending = null;
                if (!open || title != asked) return;
                if (result != null) {
                    home.titleUpdated(asked, result);
                    title = result;
                    rebuild();
                }
                home.wake();
            });
        }
        anim.target = 1;
        home.wake();
    }

    /** The home paused: the lookup in flight is cut (the sheet keeps what it has). */
    void cancelPending() {
        if (pending != null) pending.cancel();
        pending = null;
        loading = false;
    }

    void close(boolean animate) {
        if (pending != null) pending.cancel();
        pending = null;
        loading = false;
        Images.cancel(tag);
        bg = poster = null;
        bgShader = posterShader = null;
        if (open) {
            open = false;
            anim.target = 0;
            if (!animate) anim.snap(0);
        }
    }

    boolean visible() { return open || anim.value > 0.002f; }

    boolean step(float dt) {
        boolean m = anim.step(dt) | scroll.step(dt);
        if (loading) {
            spin = (SystemClock.uptimeMillis() % 1000) / 1000f;
            m = true;
        }
        if (!open && anim.value <= 0.002f) title = null;
        return m;
    }

    private void requestImages() {
        String url = title.backdropUrl(1280);
        if (!url.isEmpty()) {
            Images.get(url, home.heroW(), home.heroH(), tag, (u, b) -> {
                if (b == null || !open) return;
                bg = b;
                bgShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                home.wake();
            });
        }
        String purl = title.posterUrl(342);
        if (!purl.isEmpty()) {
            float k = home.scale();
            Images.get(purl, Math.round(300 * k), Math.round(450 * k), tag, (u, b) -> {
                if (b == null || !open) return;
                poster = b;
                posterShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                home.wake();
            });
        }
    }

    // MARK: - Content

    private void rebuild() {
        Title t = title;
        meta = Fx.fit(t.metaLine() + (t.ratingText().isEmpty() ? "" : " \u00B7 " + (t.imdbRating ? "IMDb " : "TMDB ") + t.ratingText()), fx.sMeta, 800);
        cast = t.cast.isEmpty() ? "" : Fx.fit(Ui.trf("Cast: {names}", "names", String.join(", ", t.cast)), fx.sCast, 800);
        titleLayout = Fx.layout(t.title, fx.sTitle, 800, 2, Layout.Alignment.ALIGN_NORMAL, 68 - fx.sTitle.getFontSpacing());
        overview = Fx.layout(t.overview, fx.sOv, 800, 5, Layout.Alignment.ALIGN_NORMAL, 40 - fx.sOv.getFontSpacing());
        posterGradient = Fx.placeholder(t.title, 300, 450);
        btn1 = t.full && t.trailerYouTubeId.isEmpty() ? Ui.tr("Find trailer") : Ui.tr("Play trailer");
        String app = null;
        List<Title.Provider> inst = t.installedStreams();
        if (cont != null) app = cont.sourceName;
        else if (!inst.isEmpty()) app = inst.get(0).name;
        else if (t.stremio) app = "Stremio";
        btn2Enabled = app != null;
        btn2 = app != null ? Ui.trf("Watch in {app}", "app", app) : Ui.tr("Not on your apps");
        btn2W = Math.max(250, fx.btn.measureText(btn2) + 64);
        ways.clear();
        float y = 84;
        String[] kinds = {Title.Provider.STREAM, Title.Provider.RENT, Title.Provider.BUY};
        String[] names = {Ui.tr("Stream"), Ui.tr("Rent"), Ui.tr("Buy")};
        for (int g = 0; g < 3; g++) {
            boolean head = false;
            for (int pass = 0; pass < 2; pass++) {
                for (Title.Provider p : t.providers) {
                    if (!kinds[g].equals(p.kind) || p.installed != (pass == 0)) continue;
                    if (!head) {
                        Way h = new Way();
                        h.header = true;
                        h.text = names[g].toUpperCase(Ui.locale());
                        h.y = y;
                        ways.add(h);
                        y += 44;
                        head = true;
                    }
                    Way w = new Way();
                    w.provider = p;
                    w.text = Fx.fit(p.name, fx.wName, 250);
                    w.installed = p.installed;
                    w.label = p.installed ? Ui.tr("Installed") : Ui.tr("Not installed");
                    w.y = y;
                    ways.add(w);
                    y += 68;
                }
            }
            if (head) y += 12;
        }
        if (t.stremio) {
            Way h = new Way();
            h.header = true;
            h.text = Ui.tr("Also").toUpperCase(Ui.locale());
            h.y = y;
            ways.add(h);
            y += 44;
            Way w = new Way();
            w.stremio = true;
            w.installed = true;
            w.text = "Stremio";
            w.label = Ui.tr("via Stremio");
            w.y = y;
            ways.add(w);
            y += 68;
        }
        waysHeight = y;
        if (idx >= 2 + rowCount()) idx = 1;
    }

    private int rowCount() {
        int n = 0;
        for (Way w : ways) if (!w.header) n++;
        return n;
    }

    private Way wayAt(int index) {
        int n = 0;
        for (Way w : ways) {
            if (w.header) continue;
            if (n++ == index) return w;
        }
        return null;
    }

    // MARK: - Keys

    boolean key(int code) {
        int rows = rowCount();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (idx >= 2) idx = 1; else if (idx == 1) idx = 0; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (idx == 0) idx = 1; else if (idx == 1 && rows > 0) idx = 2; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (idx > 2) idx--; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (idx >= 2 && idx < 1 + rows) idx++; else return true;
                break;
            default:
                return false;
        }
        Way w = idx >= 2 ? wayAt(idx - 2) : null;
        if (w != null) {
            float s = scrollTarget;
            if (w.y - 8 < s + 76) s = w.y - 84;
            else if (w.y + 72 > s + WH - 16) s = w.y + 72 - (WH - 16);
            scrollTarget = Math.max(0, s);
        } else {
            scrollTarget = 0;
        }
        scroll.target = scrollTarget;
        home.click();
        home.wake();
        return true;
    }

    void activate() {
        android.os.Bundle opts = home.options(PX + 392, PY + 580, 300, 84);
        if (idx == 0) {
            if (!Launch.trailer(home.context(), title, opts)) home.showToast(Ui.tr("Couldn't open YouTube."));
            return;
        }
        if (idx == 1) {
            if (!btn2Enabled) {
                home.showToast(Ui.tr("This title isn't on your apps."));
                return;
            }
            if (cont != null) {
                if (!Launch.open(home.context(), cont, opts)) home.showToast(Ui.trf("Couldn't open {app}.", "app", cont.sourceName));
            } else {
                watch(null, opts);
            }
            return;
        }
        Way w = wayAt(idx - 2);
        if (w == null) return;
        if (!w.installed) {
            home.showToast(Ui.trf("{app} isn't installed on this TV.", "app", w.provider.name));
            return;
        }
        if (w.stremio) {
            Title.Provider p = new Title.Provider();
            p.name = "Stremio";
            p.appKey = Services.STREMIO;
            p.pkg = Services.STREMIO_PKG;
            p.kind = Title.Provider.STREAM;
            watch(p, opts);
        } else {
            watch(w.provider, opts);
        }
    }

    private void watch(Title.Provider p, android.os.Bundle opts) {
        Launch.watch(home.context(), title, p, opts, opened -> {
            if (!opened) home.showToast(Ui.tr("Couldn't open the app."));
        });
    }

    // MARK: - Drawing (design space)

    void draw(Canvas c, int w, int h) {
        float a = Ui.clamp(anim.value);
        if (a <= 0.002f || title == null) return;
        fx.fill.setColor(Fx.alpha(0xFF000000, 0.55f * a));
        c.drawRect(0, 0, 1920, 1080, fx.fill);
        int layer = -1;
        if (a < 0.999f) layer = c.saveLayerAlpha(0, 0, 1920, 1080, Math.round(255 * a));
        c.save();
        c.translate(PX, PY + (1 - a) * 40);
        int ni = fx.dark() ? 1 : 0;
        int pageColor = fx.tone(0xFFF5F5F7, 0xFF1C1C1E);
        fx.drop(c, 0, 0, PW, PH, 48, 4, 12, 0.45f);
        fx.fill.setColor(pageColor);
        fx.rect.set(0, 0, PW, PH);
        c.drawRoundRect(fx.rect, 48, 48, fx.fill);
        if (bg != null) {
            c.save();
            c.translate(560, 0);
            float s = Math.max(1264f / bg.getWidth(), 711f / bg.getHeight());
            c.scale(s, s);
            fx.shaded.setShader(bgShader);
            fx.shaded.setAlpha(Math.round(255 * 0.42f));
            c.drawRoundRect(0, 0, 1264 / s, 711 / s, 48 / s, 48 / s, fx.shaded);
            fx.shaded.setShader(null);
            c.restore();
        }
        fx.fill.setColor(0xFF000000);
        fx.fill.setShader(fadeL[ni]);
        c.drawRect(500, 0, 1300, 720, fx.fill);
        fx.fill.setShader(fadeB[ni]);
        c.drawRect(500, 300, 1824, 720, fx.fill);
        fx.fill.setShader(null);
        // Poster
        fx.drop(c, 48, 60, 348, 510, 20, 4, 12, 0.3f);
        c.save();
        c.translate(48, 60);
        if (poster != null) {
            fx.roundImage(c, poster, posterShader, 300, 450, 20, 255);
        } else {
            fx.fill.setColor(0xFF000000);
            fx.fill.setShader(posterGradient);
            fx.rect.set(0, 0, 300, 450);
            c.drawRoundRect(fx.rect, 20, 20, fx.fill);
            fx.fill.setShader(null);
        }
        c.restore();
        // Text
        fx.sTitle.setColor(fx.ink());
        c.save();
        c.translate(392, 212 - titleLayout.getHeight());
        titleLayout.draw(c);
        c.restore();
        fx.sMeta.setColor(fx.meta());
        c.drawText(meta, 392, Fx.base(224, 42, fx.sMeta), fx.sMeta);
        fx.sOv.setColor(fx.ink());
        c.save();
        c.translate(392, 284);
        overview.draw(c);
        c.restore();
        if (!cast.isEmpty()) {
            fx.sCast.setColor(fx.ink());
            c.drawText(cast, 392, Fx.base(500, 40, fx.sCast), fx.sCast);
        }
        button(c, 392, 712, btn1, true, idx == 0, true);
        button(c, 732, 732 + btn2W, btn2, false, idx == 1, btn2Enabled);
        fx.sFoot.setColor(fx.dim());
        c.drawText(footnote, 48, Fx.base(930, 34, fx.sFoot), fx.sFoot);
        drawWays(c);
        c.restore();
        if (layer >= 0) c.restoreToCount(layer);
    }

    private void button(Canvas c, float l, float r, String text, boolean secondary, boolean focused, boolean enabled) {
        float t = 580, b = 664, f = focused ? 1 : 0, mx = (l + r) / 2, my = (t + b) / 2;
        c.save();
        if (focused) {
            c.translate(mx, my);
            c.scale(1.05f, 1.05f);
            c.translate(-mx, -my);
        }
        float a = enabled ? 1 : 0.5f;
        if (secondary) {
            fx.fill.setColor(fx.tone(0xFFFFFFFF, 0xFF2C2C2E));
            fx.rect.set(l, t, r, b);
            c.drawRoundRect(fx.rect, 42, 42, fx.fill);
            fx.stroke.setStrokeWidth(3);
            fx.stroke.setColor(fx.tone(Fx.INK, Fx.WHITE));
            fx.rect.inset(1.5f, 1.5f);
            c.drawRoundRect(fx.rect, 40.5f, 40.5f, fx.stroke);
            fx.btn.setColor(fx.tone(Fx.INK, Fx.WHITE));
        } else {
            fx.fill.setColor(Fx.alpha(fx.tone(Fx.INK, Fx.WHITE), a));
            fx.rect.set(l, t, r, b);
            c.drawRoundRect(fx.rect, 42, 42, fx.fill);
            fx.btn.setColor(fx.tone(0xFFFFFFFF, Fx.INK));
        }
        c.drawText(text, mx, Fx.base(t, b - t, fx.btn), fx.btn);
        if (focused) fx.ring(c, l, t, r, b, 42, 1);
        c.restore();
    }

    private void drawWays(Canvas c) {
        float x = WX - PX, y = WY - PY;
        fx.drop(c, x, y, x + WW, y + WH, 36, 4, 12, 0.1f);
        fx.glass(c, x, y, x + WW, y + WH, 36, 1);
        fx.wHead.setColor(fx.ink());
        c.drawText(waysHead, x + 28, Fx.base(y + 20, 52, fx.wHead), fx.wHead);
        c.save();
        c.clipRect(x, y + 76, x + WW, y + WH - 16);
        float oy = y - scroll.value;
        if (ways.isEmpty()) {
            if (loading) {
                fx.stroke.setStrokeWidth(5);
                fx.stroke.setColor(fx.dim());
                Ui.spinner(c, x + WW / 2, y + 300, 26, spin, fx.stroke);
            } else {
                fx.sFoot.setColor(fx.dim());
                c.drawText(noData, x + 28, y + 130, fx.sFoot);
            }
        }
        int row = 0;
        for (Way w : ways) {
            float wy = oy + w.y;
            if (wy > y + WH || wy + 70 < y) {
                if (!w.header) row++;
                continue;
            }
            if (w.header) {
                fx.wGroup.setColor(fx.dim());
                c.drawText(w.text, x + 28, Fx.base(wy, 36, fx.wGroup), fx.wGroup);
                fx.fill.setColor(0xFF8E8E93);
                c.drawRect(x + 28, wy + 34, x + 508, wy + 36, fx.fill);
                continue;
            }
            boolean focused = idx == 2 + row++;
            float dim = w.installed || focused ? 1 : 0.62f;
            if (focused) {
                fx.fill.setColor(fx.tone(0xE6FFFFFF, 0x24FFFFFF));
                fx.rect.set(x + 20, wy, x + 524, wy + 64);
                c.drawRoundRect(fx.rect, 18, 18, fx.fill);
                fx.ringThin(c, x + 20, wy, x + 524, wy + 64, 18, 1);
            }
            int ink = Fx.alpha(fx.ink(), dim);
            if (w.installed) fx.symbol(c, "check", x + 20 + 28, wy + 32, 23, ink);
            fx.wName.setColor(ink);
            c.drawText(w.text, x + 20 + 52, Fx.base(wy, 64, fx.wName), fx.wName);
            fx.wLabel.setColor(ink);
            c.drawText(w.label, x + 20 + 504 - 12, Fx.base(wy, 64, fx.wLabel), fx.wLabel);
        }
        c.restore();
    }
}
