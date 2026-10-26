package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.speech.RecognizerIntent;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, drawn by hand on one Canvas in a 1920 x 1080 design space (the canvas is scaled by k = width / 1920;
 * the shelf and All Apps work in real pixels so their logos stay pixel for pixel). Top to bottom:
 *  - the clock at the top left and four round glass buttons at the top right (Search, Inputs, Wi-Fi, Settings), each
 *    with its name under it;
 *  - the hero (HomeHero) that follows the focused card, then the rows (HomeRows): Continue, Movies, Shows, and the app
 *    shelf; the rows scroll up as the focus goes down;
 *  - Down from the shelf: the drawer (HDMI inputs and shortcuts); Enter on a title: the details sheet (DetailsSheet);
 *    the Search button: the search screen (SearchScreen); All Apps is the sheet you know;
 *  - from sunset to sunrise a night look.
 * It draws only while something moves (Choreographer), with no allocation in onDraw and no blur or shadow computed per
 * frame (Shadows are cached bitmaps).
 */
final class HomeView extends View implements Choreographer.FrameCallback {
    static final int REQ_VOICE = 4711;

    private static final float MARGIN = 80, CONTENT_R = 1840;
    private static final float SHELF_PAD = 40, TILE_GAP = 48, TILE_RADIUS = 22, L_TILE_H = 126;
    private static final float FOCUS_GROW = 0.2f;
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final long TOAST_MS = 4000, NIGHT_MS = 2000;
    private static final int Z_TOP = 0, Z_ROWS = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final int TOP_SEARCH = 0, TOP_INPUTS = 1, TOP_WIFI = 2, TOP_SETTINGS = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final long BURST_MS = 1500, APPS_MAX_AGE_MS = 15 * 60_000;

    private final Activity activity;
    private final Fx fx;
    private final HomeRows rows;
    private final HomeHero hero;
    private final DetailsSheet details;
    private final SearchScreen search;
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final LauncherApps launcherApps;
    private final Wifi wifi;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();

    private int zone = Z_ROWS;
    private int topIndex, focusRow;
    private boolean userMoved;

    // Night look: darker from sunset to sunrise, with a 2 s transition.
    private boolean nightTarget;
    private float night, nightFrom;
    private long nightAt;
    private Shader pageDay, pageNight;

    // Top bar
    private final Spring[] topFocus = new Spring[4];
    private final String[] topLabel = new String[4];
    private Wifi.State wifiState = new Wifi.State();
    private String wifiLabel = "";
    private String clockText = "", dateText = "";
    private long clockMinute = -1;

    // Rows and shelf
    private final Spring rowsY = new Spring(0.3f, 1f, 0);
    private String shelfLabel = "", shelfCounter = "";
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex, shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (hold OK, then ← → move it, OK drops it, Back cancels).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private String[] drawerName = new String[0], drawerStatus = new String[0];
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;
    private final TextPaint pxText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private String toast;
    private float toastW;
    private long toastAt;

    // Frames
    private boolean running, dirty = true, idle;
    private boolean tilesDirty, drawerDirty, gridDirty, motionDirty;
    private long lastNanos, resumedAt;
    private int w, h;
    private float k = 1;
    private boolean okDown, backDown;
    private boolean voiceAsked;

    /** The recognizer overlay is up: the search screen ducks down so it keeps the top. */
    boolean voiceActive() { return voiceAsked; }

    // Debug overlay (MENU three times, or the "debug" preference): bitmaps held, cache size and frame times.
    private boolean debug;
    private int menuPresses;
    private long menuAt, perfLogAt, lastFrameNanos;
    private float frameMs, drawMs;
    private final float[] perfMs = new float[1024];
    private int perfN;
    private int jankFrames, frames;
    private String debugText = "";

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // Color only: the blur comes from the Shadows cache at draw time, so a MaskFilter here would only allocate.
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows;
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), gridCell = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private TextPaint gridLabel;
    private Paint sheetTitle;
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;

    // MARK: - Setup

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        fx = new Fx(a);
        shadows = fx.shadows;
        rows = new HomeRows(this, fx);
        hero = new HomeHero(this, fx);
        details = new DetailsSheet(this, fx);
        search = new SearchScreen(this, fx);
        wifi = new Wifi(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        debug = ui.getBoolean("debug", false);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        setContentDescription("Free My TV");
        // Hardware-accelerated canvas: the display list is replayed on the GPU; drawing is only asked for when something moves.
        setLayerType(LAYER_TYPE_NONE, null);
        for (int i = 0; i < topFocus.length; i++) topFocus[i] = new Spring(0.2f, 1f, 0);
        pageDay = Ui.gradient(1080, 0xFFF5F5F7, 0xFFE2E2E7);
        pageNight = Ui.gradient(1080, 0xFF1C1C1E, 0xFF0B0B0D);
        labels();
        checkNight(false);
        border.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setDrawerItems(drawerList(a));
        refreshRows();
        if (!rows.has(focusRow)) {
            focusRow = rows.first();
            if (focusRow < 0) zone = Z_SHELF;
        }
    }

    private void labels() {
        topLabel[TOP_SEARCH] = Ui.tr("Search");
        topLabel[TOP_INPUTS] = Ui.tr("Inputs");
        topLabel[TOP_SETTINGS] = Ui.tr("Settings");
        shelfLabel = Ui.tr("Apps");
        rows.relabel();
        setWifi(wifiState);
    }

    private void setWifi(Wifi.State s) {
        wifiState = s;
        String text = s.connected ? s.label : Ui.tr("No internet");
        wifiLabel = Fx.fit(text, fx.tip, 116);
        topLabel[TOP_WIFI] = wifiLabel;
        dirty = true;
    }

    // MARK: - Used by the parts

    Context context() { return getContext(); }
    Handler handler() { return handler; }
    float scale() { return k; }
    int heroW() { return Math.round(HomeHero.W * k); }
    int heroH() { return Math.round(HomeHero.H * k); }
    void click() { click.move(); }

    /** The system's zoom-out of the app from a place on screen (design px). */
    Bundle options(float l, float t, float width, float height) {
        try {
            return ActivityOptions.makeScaleUpAnimation(this, Math.round(l * k), Math.round(t * k),
                    Math.max(1, Math.round(width * k)), Math.max(1, Math.round(height * k))).toBundle();
        } catch (RuntimeException e) {
            return null;
        }
    }

    void titleUpdated(Title old, Title now) {
        rows.replace(old, now);
        dirty = true;
    }

    void openDetails(Title t, ContinueItem from) {
        click.open();
        details.open(t, from);
        spoken.focusMoved();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = lastFrameNanos = 0;
        recs.setActive(true);
        recs.addDataListener(dataListener);
        recs.refresh();
        data.refreshIfStale();
        wifi.start(state -> { setWifi(state); wake(); });
        Photos.importInbox(getContext(), () -> { });   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        refreshRows();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        retarget(true);
        dirty = true;
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        recs.removeDataListener(dataListener);
        recs.setActive(false);
        details.cancelPending();
        wifi.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        // Nothing is on screen: only what a visible screen needs (the open details sheet's two pictures) stays.
        rows.dropAll();
        hero.drop();
    }

    void destroy() {
        pause();
        search.close();
        details.close(false);
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Memory is short: every picture goes (the visible ones come back from the disk cache). */
    void trimMemory(int level) {
        Images.trim();
        rows.dropAll();
        hero.drop();
        if (running) retarget(false);
    }

    /** Starting state (Home pressed while the home screen is showing): the first card focused, everything closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        if (details.open) details.close(false);
        if (search.open) search.close();
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        for (HomeRows.Row row : rows.rows) row.col = 0;
        focusRow = rows.first();
        zone = focusRow >= 0 ? Z_ROWS : Z_SHELF;
        shelfIndex = 0;
        retarget(!animated);
    }

    private final Recs.DataListener dataListener = new Recs.DataListener() {
        @Override public void onRows() { refreshRows(); wake(); }
        @Override public void onContinue() { refreshRows(); wake(); }
    };

    /** The rows' cards from the data layer (the cards are made again; each row keeps its column). */
    private List<ContinueItem> shownContinue;
    private List<Title> shownMovies, shownShows;

    private static boolean sameTitles(List<Title> a, List<Title> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).key().equals(b.get(i).key()) || a.get(i).stremio != b.get(i).stremio) return false;
        }
        return true;
    }

    private void refreshRows() {
        List<ContinueItem> cont = recs.continueItems();
        List<Title> movies = recs.movies(), shows = recs.shows();
        if (shownContinue != null && Stremio.same(shownContinue, cont) && sameTitles(shownMovies, movies) && sameTitles(shownShows, shows)) return;
        shownContinue = cont;
        shownMovies = movies;
        shownShows = shows;
        rows.setData(cont, movies, shows);
        if (zone == Z_ROWS && !rows.has(focusRow)) {
            focusRow = rows.first();
            if (focusRow < 0) zone = Z_SHELF;
        } else if (zone == Z_SHELF && !userMoved && rows.first() >= 0) {
            zone = Z_ROWS;     // the rows came in after the first frame: the focus goes where the eyes are
            focusRow = rows.first();
        }
        hero.drop();
        retarget(true);
        spoken.focusMoved();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            // Searching has the network: the rows wait until it closes.
            if (!search.open) recs.refresh();
            if (rows.first() < 0) dirty = true;   // the empty-state message may have changed
            checkNotices();
            checkNight(true);
            handler.postDelayed(this, 15_000);
        }
    };

    private int bitmaps() {
        return rows.loaded() + hero.loaded() + search.loaded();
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private final Runnable reloadSoon = this::reload;

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    // MARK: - Size

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        hero.size(k);
        clockMinute = -1;
        updateClock();
        border.setStrokeWidth(2 * k);
        rim.setStrokeWidth(2.5f * k);
        gridLabel.setTextSize(24 * k);
        sheetTitle.setTextSize(40 * k);
        gridLabels = null;
        layoutShelf();
        layoutDrawer();
        for (String s : new String[] {"hdmi", "settings", "check"}) Symbols.warm(getContext(), s, 27 * k);
        fx.heroFade(false);
        fx.heroFade(true);
        retarget(true);
        dirty = true;
        wake();
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        if (lastFrameNanos != 0 && frameTimeNanos - lastFrameNanos < 100_000_000L) {
            frameMs = (frameTimeNanos - lastFrameNanos) / 1e6f;
            frames++;
            if (frameMs > 20.5f) jankFrames++;
            if (debug) {
                if (perfN < perfMs.length) perfMs[perfN++] = frameMs;
                long nowMs = SystemClock.uptimeMillis();
                if (nowMs - perfLogAt >= 5000) {
                    perfLogAt = nowMs;
                    float p95 = 0;
                    if (perfN > 0) {
                        java.util.Arrays.sort(perfMs, 0, perfN);
                        p95 = perfMs[Math.min(perfN - 1, (int) (perfN * 0.95f))];
                    }
                    AppLog.w("perf frames=" + perfN + " p95=" + Math.round(p95) + "ms nativeMB=" + (android.os.Debug.getNativeHeapAllocatedSize() >> 20)
                            + " bitmaps=" + bitmaps() + " cacheMB=" + (Images.cacheBytes() >> 20));
                    perfN = 0;
                }
            }
        }
        lastFrameNanos = frameTimeNanos;
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean moving = rows.step(dt);
        moving |= rowsY.step(dt);
        for (Spring s : topFocus) moving |= s.step(dt);
        moving |= step(tileFocus, dt);
        if (reorderAnim) moving |= stepReorder(dt, up);
        moving |= step(drawerFocus, dt);
        moving |= step(gridFocus, dt) | scroll.step(dt);
        moving |= drawer.step(dt);
        moving |= sheet.step(dt);
        moving |= details.step(dt);
        moving |= search.step(dt);
        moving |= hero.moving(up);
        moving |= tickToast(up);
        moving |= tickNight(up);
        if (updateClock()) dirty = true;

        boolean changed = moving || dirty || tilesDirty || drawerDirty || gridDirty || motionDirty;
        dirty = tilesDirty = drawerDirty = gridDirty = motionDirty = false;
        if (changed) invalidate();
        boolean busy = moving || sheetOpen || drawerOpen || sheet.value > 0.001f || drawer.value > 0.001f || toast != null
                || !toasts.isEmpty() || debug;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    void wake() {
        dirty = true;
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.datePattern("format|EEE, MMM d").format(now);
        return true;
    }

    // MARK: - Night look

    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
        }
        dirty = true;
    }

    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / (float) NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        return true;
    }

    private float dark() { return night; }

    private int tone(int onLight, int onDark) { return Fx.blend(onLight, onDark, night); }

    private float tone(float onLight, float onDark) { return onLight + (onDark - onLight) * night; }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        long t0 = System.nanoTime();
        fx.night = night;
        c.save();
        c.scale(k, k);
        drawPage(c);
        if (!search.covers()) drawHome(c);
        if (search.visible()) search.draw(c);
        if (details.visible()) details.draw(c, w, h);
        c.restore();
        if (sheet.value > 0.001f || sheetOpen) drawSheetLayer(c);
        c.save();
        c.scale(k, k);
        drawToast(c);
        if (debug) drawDebug(c);
        c.restore();
        if (debug) drawMs = (System.nanoTime() - t0) / 1e6f;
    }

    private void drawPage(Canvas c) {
        fx.fill.setColor(0xFF000000);
        if (night < 0.999f) {
            fx.fill.setShader(pageDay);
            c.drawRect(0, 0, 1920, 1080, fx.fill);
        }
        if (night > 0.001f) {
            fx.fill.setShader(pageNight);
            fx.fill.setAlpha(Math.round(255 * night));
            c.drawRect(0, 0, 1920, 1080, fx.fill);
        }
        fx.fill.setShader(null);
        fx.fill.setAlpha(255);
    }

    private void drawHome(Canvas c) {
        float d = Ui.clamp(drawer.value);
        c.save();
        c.translate(0, -110 * d);
        hero.draw(c, 1);
        if (rows.first() < 0) drawEmpty(c);
        drawTop(c);
        c.save();
        c.clipRect(0, 596, 1920, 1200);
        float base = 620 - rowsY.value;
        for (int r = 0; r < HomeRows.COUNT; r++) rows.drawRow(c, r, base + rows.top(r), zone == Z_ROWS ? focusRow : -1, 1);
        drawShelfRow(c, base + rows.top(HomeRows.COUNT));
        c.restore();
        c.restore();
        if (d > 0.002f) {
            // The drawer opens over a home that fades: the hero and the buttons to a third, the rows away.
            fx.fill.setColor(0xFF000000);
            fx.fill.setShader(night > 0.5f ? pageNight : pageDay);
            fx.fill.setAlpha(Math.round(255 * 0.65f * d));
            c.drawRect(0, 0, 1920, 596 - 110 * d, fx.fill);
            fx.fill.setAlpha(Math.round(255 * Math.min(1, 3 * d)));
            c.drawRect(0, 596 - 110 * d, 1920, 1080, fx.fill);
            fx.fill.setShader(null);
            fx.fill.setAlpha(255);
            drawDrawer(c, d);
        }
    }

    // MARK: - Top bar: clock and four round buttons

    /** The hero area while there are no rows: says why (no key, loading, or the error), never a blank page. */
    private void drawEmpty(Canvas c) {
        if (emptyTitle == null) {
            emptyTitle = Ui.text(Ui.displaySemibold, 52, 0xFFFFFFFF);
            emptyNote = Ui.text(Ui.medium, 30, 0xFFFFFFFF);
        }
        String title, note;
        String err = recs.rowsError();
        if (!Recs.hasKey(getContext())) {
            title = Ui.tr("No recommendations yet");
            note = Ui.tr("Add a free TMDB key in config.properties and build again.");
        } else if (!err.isEmpty()) {
            title = Ui.tr("Couldn't load recommendations");
            note = Ui.tr("Check the TMDB key and the internet connection. It tries again soon.");
        } else {
            title = Ui.tr("Loading recommendations…");
            note = Ui.tr("The first time can take a minute on this TV.");
        }
        emptyTitle.setColor(tone(INK, WHITE));
        emptyNote.setColor(Fx.alpha(tone(INK, WHITE), 0.7f));
        c.drawText(title, 96, 330, emptyTitle);
        c.drawText(note, 96, 392, emptyNote);
    }

    private Paint emptyTitle, emptyNote;

    private void drawTop(Canvas c) {
        fx.clockT.setColor(fx.ink());
        c.drawText(clockText, 96, Fx.base(46, 56, fx.clockT), fx.clockT);
        fx.clockD.setColor(fx.dim());
        c.drawText(dateText, 96, Fx.base(104, 34, fx.clockD), fx.clockD);
        int top = -1;
        for (int i = 0; i < 4; i++) if (topFocus[i].value > 0.02f && (top < 0 || topFocus[i].value > topFocus[top].value)) top = i;
        for (int i = 0; i < 4; i++) if (i != top) drawRound(c, i);
        if (top >= 0) drawRound(c, top);
    }

    private void drawRound(Canvas c, int i) {
        float x = 1324 + i * 144, y = 48, f = topFocus[i].value, fc = Ui.clamp(f), s = 1 + 0.1f * f;
        float cx = x + 34, cy = y + 34;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        c.translate(-cx, -cy);
        fx.drop(c, x, y, x + 68, y + 68, 34, 4, 12, tone(0.1f, 0.3f));
        fx.glass(c, x, y, x + 68, y + 68, 34, 1 - fc);
        if (fc > 0.01f) fx.lit(c, x, y, x + 68, y + 68, 34, fc);
        int ink = Fx.blend(fx.ink(), Fx.INK, fc);
        switch (i) {
            case TOP_SEARCH: fx.magnifier(c, cx + 2, cy, ink); break;
            case TOP_INPUTS: fx.symbol(c, "hdmi", cx, cy, 27, ink); break;
            case TOP_WIFI:
                fx.wifi(c, cx, cy, wifiState.connected && !wifiState.wifi ? 4 : wifiState.level, !wifiState.connected, ink,
                        Fx.blend(0xFFFFFFFF, 0xFF1D1D1F, night));
                break;
            default: fx.symbol(c, "settings", cx, cy, 27, ink); break;
        }
        // The name, always visible, in a pill under the button.
        float py = y + 76;
        fx.fill.setColor(Fx.blend(Fx.blend(0xD1FFFFFF, 0x8C000000, night), 0xFFFFFFFF, fc));
        fx.rect.set(x - 28, py, x + 96, py + 32);
        c.drawRoundRect(fx.rect, 16, 16, fx.fill);
        fx.tip.setColor(Fx.blend(fx.tone(Fx.INK, WHITE), Fx.INK, fc));
        c.drawText(topLabel[i], cx, Fx.base(py, 32, fx.tip), fx.tip);
        if (fc > 0.01f) fx.ring(c, x, y, x + 68, y + 68, 34, fc);
        c.restore();
    }

    // MARK: - The shelf is the last row

    private void drawShelfRow(Canvas c, float y) {
        if (shelf.isEmpty() || y > 1100 || y < 300) return;
        fx.rowLabel.setColor(fx.ink());
        c.drawText(shelfLabel, 96, Fx.base(y, HomeRows.LABEL_H, fx.rowLabel), fx.rowLabel);
        if (zone == Z_SHELF) {
            fx.rowCount.setColor(fx.meta());
            c.drawText(shelfCounter, 1824, Fx.base(y, HomeRows.LABEL_H, fx.rowCount), fx.rowCount);
        }
        c.save();
        c.scale(1 / k, 1 / k);
        c.translate(0, (y + 72) * k);
        drawPlatter(c);
        drawTiles(c);
        c.restore();
    }

    /** The glass bar under the app tiles (pixel space; the canvas is at the platter's top). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        fx.drop(c, platterRect.left, platterRect.top, platterRect.right, platterRect.bottom, radius, 4 * k, 12 * k, tone(0.1f, 0.3f));
        fx.glass(c, platterRect.left, platterRect.top, platterRect.right, platterRect.bottom, radius, 1);
    }

    // MARK: - Drawer: inputs and shortcuts

    private void drawDrawer(Canvas c, float d) {
        if (drawerItems.isEmpty()) return;
        float a = HomeHero.ease((d - 0.1f) / 0.5f);
        if (a <= 0.004f) return;
        int layer = -1;
        if (a < 0.999f) layer = c.saveLayerAlpha(0, 400, 1920, 1080, Math.round(255 * a));
        c.translate(0, (1 - Ui.clamp(d)) * 420);
        fx.fill.setColor(fx.tone(0xF2FAFAFC, 0xF51E1E21));
        fx.rect.set(0, 430, 1920, 1200);
        c.drawRoundRect(fx.rect, 62, 62, fx.fill);
        fx.dLabel.setColor(fx.ink());
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), 96, Fx.base(470, 44, fx.dLabel), fx.dLabel);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), 96, Fx.base(drawerInputs > 0 ? 730 : 470, 44, fx.dLabel), fx.dLabel);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        c.translate(0, -(1 - Ui.clamp(d)) * 420);
        if (layer >= 0) c.restoreToCount(layer);
    }

    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        RectF r = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.06f * f;
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        c.save();
        c.translate(r.centerX(), r.centerY());
        c.scale(s, s);
        c.translate(-r.centerX(), -r.centerY());
        if (f < 0.99f) {
            if (!off) fx.drop(c, r.left, r.top, r.right, r.bottom, 28, 4, 12, tone(0.1f, 0.3f) * (1 - f));
            fx.glass(c, r.left, r.top, r.right, r.bottom, 28, 1 - f);
        }
        if (f > 0.01f) fx.lit(c, r.left, r.top, r.right, r.bottom, 28, f);
        if (off) {
            fx.dashed.setColor(Fx.alpha(fx.tone(0xFF55555A, 0xFFB8B8BE), 1 - f));
            fx.rect.set(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f);
            c.drawRoundRect(fx.rect, 27, 27, fx.dashed);
        }
        int ink = Fx.blend(fx.ink(), Fx.INK, f);
        float dim = off ? 0.55f : 1;
        fx.symbol(c, it.symbol, r.left + 54, r.top + 66, 43, Fx.alpha(ink, dim));
        boolean status = !drawerStatus[i].isEmpty();
        TextPaint np = fx.dName;
        np.setTextSize(drawerName[i].length() > 11 ? 26 : 30);
        np.setColor(Fx.alpha(ink, dim));
        c.drawText(drawerName[i], r.left + 100, status ? Fx.base(r.top + 34, 40, np) : Fx.base(r.top + 55, 40, np), np);
        if (status) {
            fx.dStatus.setColor(Fx.blend(fx.dim(), 0xFF55555A, f));
            c.drawText(drawerStatus[i], r.left + 100, Fx.base(r.top + 78, 34, fx.dStatus), fx.dStatus);
        }
        if (f > 0.01f) fx.ring(c, r.left, r.top, r.right, r.bottom, 28, f);
        c.restore();
    }

    private void drawSheetLayer(Canvas c) {
        float s = Ui.clamp(sheet.value);
        if (s <= 0.001f) return;
        int layer = s < 0.999f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * s)) : -1;
        float scale = 1.04f - 0.04f * s;
        c.save();
        c.scale(scale, scale, w / 2f, h / 2f);
        drawSheet(c);
        c.restore();
        if (layer >= 0) c.restoreToCount(layer);
    }

    // MARK: - Toasts: a pill at the bottom

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; true while a toast fades in or out. */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            dirty = true;
        }
        if (toast == null) {
            String next = toasts.poll();
            if (next != null) {
                toast = Fx.fit(next, fx.toast, 880);
                toastW = fx.toast.measureText(toast) + 80;
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        return age < 250 || age > TOAST_MS - 300;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.2f), Ui.clamp((TOAST_MS / 1000f - age) / 0.25f));
        if (a <= 0) return;
        float l = (1920 - toastW) / 2, t = 960 + (1 - a) * 16;
        fx.fill.setColor(Fx.alpha(fx.tone(Fx.INK, WHITE), a));
        fx.rect.set(l, t, l + toastW, t + 76);
        c.drawRoundRect(fx.rect, 38, 38, fx.fill);
        fx.toast.setColor(Fx.alpha(fx.tone(0xFFFFFFFF, Fx.INK), a));
        c.drawText(toast, 960, Fx.base(t, 76, fx.toast), fx.toast);
    }

    // MARK: - Debug overlay

    private void drawDebug(Canvas c) {
        if ((frames & 15) == 0 || debugText.isEmpty()) {
            debugText = "bitmaps " + bitmaps() + "  cache " + String.format(java.util.Locale.US, "%.1f MB", Images.cacheBytes() / 1048576f)
                    + "  (" + Images.cacheCount() + ")\nframe " + String.format(java.util.Locale.US, "%.1f", frameMs)
                    + " ms  draw " + String.format(java.util.Locale.US, "%.1f", drawMs) + " ms  janky " + jankFrames + "/" + frames;
        }
        fx.debug.setColor(0xFFFFFFFF);
        fx.fill.setColor(0xE0000000);
        fx.rect.set(24, 940, 760, 1050);
        c.drawRoundRect(fx.rect, 16, 16, fx.fill);
        int nl = debugText.indexOf('\n');
        c.drawText(debugText.substring(0, nl), 44, 985, fx.debug);
        c.drawText(debugText.substring(nl + 1), 44, 1025, fx.debug);
    }

    private void toggleDebug() {
        debug = !debug;
        ui.edit().putBoolean("debug", debug).apply();
        jankFrames = frames = 0;
        perfLogAt = SystemClock.uptimeMillis();
        showToast(debug ? "Debug overlay on" : "Debug overlay off");
        wake();
    }

    // MARK: - Voice

    /** The remote's mic key or the mic chip: Android's speech recognizer; the answer comes back in onVoice. */
    void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Say a movie, a show or an app"));
        try {
            ComponentName target = voiceTarget(i);
            if (target != null) i.setComponent(target);   // no "Complete action using" chooser
            voiceAsked = true;
            activity.startActivityForResult(i, REQ_VOICE);
            // The recognizer sometimes never answers (cold speech service): stop waiting and say so.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (voiceAsked) {
                    voiceAsked = false;
                    if (search.open) search.heardNothing();
                    else showToast(Ui.tr("Didn't catch that. Try the mic again."));
                }
            }, 12_000);
        } catch (ActivityNotFoundException | SecurityException e) {
            voiceAsked = false;
            if (search.open) search.focusField();   // no recognizer: type instead
            showToast(Ui.tr("Voice search isn't available on this TV."));
        }
    }

    /** The one speech recognizer to use: the Google app on TV (katniss), else the default, else the first that answers. */
    private ComponentName voiceTarget(Intent i) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            List<android.content.pm.ResolveInfo> all = pm.queryIntentActivities(i, 0);
            for (android.content.pm.ResolveInfo r : all) {
                if (r.activityInfo != null && "com.google.android.katniss".equals(r.activityInfo.packageName)) {
                    return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                }
            }
            android.content.pm.ResolveInfo def = pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
                return new ComponentName(def.activityInfo.packageName, def.activityInfo.name);
            }
            for (android.content.pm.ResolveInfo r : all) {
                if (r.activityInfo != null && r.activityInfo.exported) return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        } catch (RuntimeException e) {
            AppLog.w("voice: " + e);
        }
        return null;
    }

    /** What was said: an "open/play" command launches the app, anything else searches with the transcript. */
    void onVoice(String text) {
        voiceAsked = false;
        if (text == null || text.trim().isEmpty()) {
            if (search.open) search.heardNothing();
            return;
        }
        if (handleVoiceCommand(text.trim())) {
            if (search.open) closeSearch();
            return;
        }
        if (details.open) details.close(false);
        if (!search.open) openSearch();
        // "X on TV" means our own search for X: drop the tail before searching.
        String query = text.trim();
        String lower = query.toLowerCase(Locale.ROOT);
        for (String tail : new String[] {" on tv", " in tv", " on the tv", " in the tv",
                " on free my tv", " in free my tv", " on freemytv", " in freemytv"}) {
            if (lower.endsWith(tail) && query.length() > tail.length()) {
                query = query.substring(0, query.length() - tail.length()).trim();
                break;
            }
        }
        search.voice(query.isEmpty() ? text.trim() : query);
    }

    /**
     * "Open Netflix", "Play Panchayat on Netflix": launches the installed app (the title in the app's own search
     * when the command names both, else the app's home). Anything else is not a command (false: the transcript
     * searches, as before). No new permissions, no recognizer changes.
     */
    private boolean handleVoiceCommand(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String rest = null;
        if (lower.startsWith("play ")) rest = text.substring(5).trim();
        else if (lower.startsWith("open ")) rest = text.substring(5).trim();
        if (rest == null || rest.isEmpty()) return false;
        String restLower = rest.toLowerCase(Locale.ROOT);
        int sep = restLower.lastIndexOf(" on ");
        if (sep < 0) sep = restLower.lastIndexOf(" in ");
        String titlePart, appPart;
        if (sep >= 0) {
            titlePart = rest.substring(0, sep).trim();
            appPart = rest.substring(sep + 4).trim();
        } else {
            titlePart = rest;
            appPart = null;
        }
        List<Search.AppMatch> apps = Search.apps(activity, appPart != null ? appPart : titlePart);
        if (apps.isEmpty()) return false;
        if (appPart != null) {
            if (!titlePart.isEmpty()) return searchInApp(apps.get(0), titlePart);
            return Launch.app(activity, apps.get(0).pkg, null);
        }
        // Bare "Open <app>": only when the words are the app itself, so "Play Panchayat" still searches.
        for (Search.AppMatch a : apps) {
            if (a.label.equalsIgnoreCase(titlePart) || Fuzzy.norm(a.label).equals(Fuzzy.norm(titlePart))) {
                return Launch.app(activity, a.pkg, null);
            }
        }
        return false;
    }

    /** The app's own search page for the title, where one is known to open (verified on device). */
    private static String appSearchUrl(String pkg, String title) {
        if (!"com.google.android.youtube.tv".equals(pkg)) return null;
        try {
            return "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(title, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /** The title in the app's own search (its home when the app takes no query). */
    private boolean searchInApp(Search.AppMatch target, String title) {
        String url = appSearchUrl(target.pkg, title);
        if (url != null) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(target.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("voice search page " + target.pkg + ": " + e.getClass().getSimpleName());
            }
        }
        Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(target.pkg)
                .putExtra(SearchManager.QUERY, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("voice search in " + target.pkg + ": " + e.getClass().getSimpleName());
        }
        return Launch.app(activity, target.pkg, null);
    }

    private void openSearch() {
        click.open();
        if (drawerOpen) closeDrawer();
        rows.dropAll();
        hero.drop();
        List<String> trending = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (i < recs.movies().size()) trending.add(recs.movies().get(i).title);
            if (i < recs.shows().size()) trending.add(recs.shows().get(i).title);
        }
        search.open(trending);
        spoken.focusMoved();
    }

    private void closeSearch() {
        search.close();
        zone = Z_TOP;
        topIndex = TOP_SEARCH;
        retarget(false);
    }

    // MARK: - Shelf (kept from before: tiles in brand colors, logos pixel for pixel, move mode)

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = 0;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }


    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
        // Focus is a thick ring as well as the growth and the shadow, so it never relies on size or color alone.
        if (fc > 0.01f) fx.ring(c, cell.left, cell.top, cell.right, cell.bottom, r, fc);
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }


    // MARK: - Drawer: layout and opening

    private void layoutDrawer() {
        int n = drawerItems.size();
        int shorts = n - drawerInputs;
        int per = Math.max(1, Math.max(drawerInputs, shorts));
        float pitch = per > 1 ? Math.min(328, (1920 - 96 - 96 - 300) / (float) (per - 1)) : 328;
        for (int i = 0; i < n; i++) {
            boolean input = i < drawerInputs;
            int col = input ? i : i - drawerInputs;
            float x = 96 + col * pitch, y = input ? 530 : (drawerInputs > 0 ? 790 : 530);
            drawerRects[i].set(x, y, x + 300, y + 150);
        }
        drawerName = new String[n];
        drawerStatus = new String[n];
        for (int i = 0; i < n; i++) {
            HomeApps.Item it = drawerItems.get(i);
            String name = it.label == null ? "" : it.label, status = "";
            if (it.kind == HomeApps.INPUT) {
                String port = "HDMI " + it.number;
                status = !it.connected ? Ui.tr("No signal") : name.equals(port) ? Ui.tr("Connected") : name;
                name = port;
            }
            fx.dName.setTextSize(name.length() > 11 ? 26 : 30);
            drawerName[i] = Fx.fit(name, fx.dName, 190);
            fx.dName.setTextSize(30);
            drawerStatus[i] = Fx.fit(status, fx.dStatus, 190);
            Symbols.warm(getContext(), it.symbol, 43 * k);
        }
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = 0;
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        wake();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);
        wake();
    }

    // MARK: - Focus

    /** Sets every spring's target from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        boolean live = !sheetOpen && !details.open && !search.open;
        aim(tileFocus, live && zone == Z_SHELF ? shelfIndex : -1, snap);
        aim(drawerFocus, live && zone == Z_DRAWER ? drawerIndex : -1, snap);
        aim(topFocus, live && zone == Z_TOP ? topIndex : -1, snap);
        rows.aim(focusRow, live && zone == Z_ROWS);
        if (zone == Z_ROWS && rows.has(focusRow)) rowsY.target = rows.top(focusRow);
        else if (zone == Z_SHELF) rowsY.target = rows.top(HomeRows.COUNT);
        if (snap) {
            rowsY.snap(rowsY.target);
            rows.snapFocus();
        }
        if (!search.open && w > 0 && rows.has(focusRow)) {
            HomeRows.Card card = rows.card(focusRow, rows.rows[focusRow].col);
            hero.show(card, card != null && card.cont != null ? rows.match(card.cont) : null);
            rows.window(zone == Z_SHELF ? HomeRows.COUNT : focusRow, k);
        }
        shelfCounter = Ui.trf("{i} / {n}", "i", shelfIndex + 1, "n", shelf.size());
        tilesDirty = drawerDirty = true;
        wake();
        spoken.focusMoved();
    }

    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            handler.post(() -> {
                if (s != null) setShelf(s);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
                if (apps) labels();
            });
        });
    }

    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    // MARK: - Keys: the arrows, OK and Back, typing in the search; system keys pass through to the system

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (down && e.getRepeatCount() == 0 && !sheetOpen) {
                if (details.open) details.close(false);
                if (!search.open) openSearch();
                startVoice();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && e.getRepeatCount() == 0) {
                long now = SystemClock.uptimeMillis();
                menuPresses = now - menuAt < 1500 ? menuPresses + 1 : 1;
                menuAt = now;
                if (menuPresses >= 3) {
                    menuPresses = 0;
                    toggleDebug();
                }
            }
            return false;
        }
        if (search.open && !details.visible() && !sheetOpen && down && typed(e)) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing. Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** A letter, digit or space (or Backspace) on a keyboard, while the search is open. */
    private boolean typed(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DEL) {
            if (search.hasQuery()) search.type("del"); else closeSearch();
            return true;
        }
        if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) return false;
        int u = e.getUnicodeChar();
        if (code == KeyEvent.KEYCODE_SPACE) u = ' ';
        if (u == 0 || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) return false;
        char ch = Character.toLowerCase((char) u);
        if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == ' ') {
            search.type(String.valueOf(ch));
            return true;
        }
        return false;
    }

    private void back() {
        if (details.open) {
            details.close(true);
            retarget(false);
        } else if (search.open) {
            closeSearch();
        } else if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone == Z_TOP && (rows.first() >= 0 || !shelf.isEmpty())) {
            userMoved = true;
            if (rows.has(focusRow)) zone = Z_ROWS; else if (rows.first() >= 0) { focusRow = rows.first(); zone = Z_ROWS; } else zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private int nextRow(int from, int dir) {
        for (int r = from + dir; r >= 0 && r < HomeRows.COUNT; r += dir) if (rows.has(r)) return r;
        return -1;
    }

    private void move(int dx, int dy) {
        if (details.open) {
            details.key(dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
            return;
        }
        if (search.open) {
            search.key(dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        userMoved = true;
        switch (zone) {
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(3, topIndex + dx));
                    if (next == topIndex) return;
                    topIndex = next;
                } else if (dy > 0) {
                    if (rows.first() >= 0) { focusRow = rows.first(); zone = Z_ROWS; }
                    else if (!shelf.isEmpty()) zone = Z_SHELF;
                    else return;
                } else {
                    return;
                }
                break;
            case Z_ROWS: {
                HomeRows.Row row = rows.rows[focusRow];
                if (dx != 0) {
                    int next = Math.max(0, Math.min(row.cards.size() - 1, row.col + dx));
                    if (next == row.col) return;
                    row.col = next;
                } else if (dy < 0) {
                    int p = nextRow(focusRow, -1);
                    if (p >= 0) focusRow = p; else zone = Z_TOP;
                } else {
                    int n = nextRow(focusRow, 1);
                    if (n >= 0) focusRow = n;
                    else if (!shelf.isEmpty()) { zone = Z_SHELF; shelfIndex = Math.min(shelfIndex, shelf.size() - 1); }
                    else return;
                }
                break;
            }
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (rows.last() >= 0) { focusRow = rows.last(); zone = Z_ROWS; } else zone = Z_TOP;
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (dx < 0 && drawerIndex == drawerInputs && drawerInputs > 0) return;   // not from Shortcuts into Inputs
                    if (dx > 0 && drawerIndex == drawerInputs - 1) return;
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    if (drawerIndex >= drawerInputs && drawerInputs > 0) drawerIndex = Math.min(drawerInputs - 1, drawerIndex - drawerInputs);
                    else closeDrawer();
                } else if (drawerIndex < drawerInputs && drawerInputs < drawerItems.size()) {
                    drawerIndex = Math.min(drawerItems.size() - 1, drawerInputs + drawerIndex);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (details.open) {
            details.activate();
            return;
        }
        if (search.open) {
            search.activate();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_TOP) {
            click.open();
            switch (topIndex) {
                case TOP_SEARCH: openSearch(); return;
                case TOP_INPUTS:
                    openDrawer();
                    for (int i = 0; i < drawerItems.size(); i++) {
                        if (drawerItems.get(i).kind == HomeApps.INPUT || drawerItems.get(i).kind == HomeApps.LIVE_TV) { drawerIndex = i; break; }
                    }
                    retarget(false);
                    return;
                case TOP_WIFI:
                    if (!Launch.wifiSettings(getContext(), options(1324 + 2 * 144, 48, 68, 68))) showToast(Ui.tr("Couldn't open the settings."));
                    return;
                default:
                    it = HomeApps.settings();
                    tmp.set(1324 + 3 * 144, 48, 1324 + 3 * 144 + 68, 116);
                    tmp.set(tmp.left * k, tmp.top * k, tmp.right * k, tmp.bottom * k);
                    r = tmp;
            }
        } else if (zone == Z_ROWS) {
            HomeRows.Row row = rows.rows[focusRow];
            HomeRows.Card card = rows.card(focusRow, row.col);
            if (card == null) return;
            click.open();
            if (card.cont != null) {
                ContinueItem ci = card.cont;
                if (ContinueItem.SOURCE_STREMIO.equals(ci.source)) {
                    float x = 96 + row.col * row.pitch + HomeRows.stripX(row, row.col);
                    if (!Launch.open(getContext(), ci, options(x, 620 + 72, row.w, row.h))) showToast(Ui.trf("Couldn't open {app}.", "app", ci.sourceName));
                } else {
                    Title t = rows.match(ci);
                    openDetails(t != null ? t : fromContinue(ci), ci);
                }
            } else {
                openDetails(card.title, null);
            }
            return;
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = tmp;
            r.set(drawerRects[drawerIndex].left * k, (drawerRects[drawerIndex].top - 0) * k, drawerRects[drawerIndex].right * k, drawerRects[drawerIndex].bottom * k);
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tmp;
            r.set(tileRects[shelfIndex]);
            r.offset(0, (620 + rows.top(HomeRows.COUNT) - rowsY.value + 72) * k);
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    /** A Continue card with no match in the rows: a bare title the sheet fills in with Recs.details. */
    private static Title fromContinue(ContinueItem c) {
        Title t = new Title();
        t.tmdbId = c.tmdbId;
        t.kind = c.kind;
        t.title = c.title;
        t.imdbId = c.imdbId;
        t.backdropPath = c.backdropPath;
        t.posterPath = c.posterPath;
        return t;
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }


    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }


    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }


    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (details.open || search.open) return;   // their buttons are read from the screen as a whole
        for (int i = 0; i < 4; i++) out.add(a11yId(Z_TOP, i));
        if (rows.has(focusRow)) for (int j = 0; j < rows.rows[focusRow].cards.size(); j++) out.add(a11yId(Z_ROWS, focusRow * 100 + j));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP: return topLabel[Math.min(3, i)];
            case Z_ROWS: {
                HomeRows.Card c = rows.card(i / 100, i % 100);
                if (c == null) return "";
                return c.name + (c.line2.isEmpty() ? "" : ", " + c.line2) + ". " + Ui.trf("{i} of {n}", "i", i % 100 + 1, "n", rows.rows[i / 100].cards.size());
            }
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            default: return "";
        }
    }

    /** Where the item is now (pixels); items off screen get a thin strip at that edge so TalkBack's arrows still reach them. */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                break;
            }
            case Z_TOP:
                a11yRect.set((1324 + Math.min(3, i) * 144) * k, 48 * k, (1324 + Math.min(3, i) * 144 + 68) * k, 116 * k);
                break;
            case Z_ROWS: {
                int r = i / 100, c = i % 100;
                if (!rows.has(r) || c >= rows.rows[r].cards.size()) return false;
                HomeRows.Row row = rows.rows[r];
                float x = 96 + c * row.pitch + HomeRows.stripX(row, row.col);
                float y = 620 + rows.top(r) - rowsY.target + HomeRows.CARD_TOP;
                a11yRect.set(x * k, y * k, (x + row.w) * k, (y + row.h) * k);
                break;
            }
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                a11yRect.offset(0, (620 + rows.top(HomeRows.COUNT) - rowsY.target + 72) * k);
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i].left * k, drawerRects[i].top * k, drawerRects[i].right * k, drawerRects[i].bottom * k);
                if (!drawerOpen) a11yRect.offset(0, h);
                break;
            default:
                return false;
        }
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        if (details.open || search.open) return -1;
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_ROWS: return rows.has(focusRow) ? a11yId(Z_ROWS, focusRow * 100 + rows.rows[focusRow].col) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering || details.open || search.open) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case Z_TOP:
                if (drawerOpen) closeDrawer();
                topIndex = Math.min(3, i);
                break;
            case Z_ROWS:
                if (!rows.has(i / 100)) return;
                if (drawerOpen) closeDrawer();
                focusRow = i / 100;
                rows.rows[focusRow].col = Math.min(i % 100, rows.rows[focusRow].cards.size() - 1);
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static int blend(int a, int b, float f) {
        return Fx.blend(a, b, f);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
