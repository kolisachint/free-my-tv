package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The home rows: up to 15 movies and 15 shows that can be played on this TV now. Candidates come from TMDB (trending
 * this week, plus recent releases on the TV's apps) and Stremio's Cinemeta (popular), are looked up on TMDB, and kept
 * only when an installed streaming app has them in the region or (Cinemeta ones) Stremio does. Fetched once a day
 * on a background thread, saved to disk so the next start paints at once. Use it through Recs.
 */
final class Catalog {
    private static final long EVERY = 24 * 3600_000L, RETRY = 10 * 60_000L;
    private static final int MAX = 15, CANDIDATES = 40, CHUNK = 8, CACHE_VERSION = 1;
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/catalog/";

    private static Catalog instance;

    static synchronized Catalog get(Context c) {
        if (instance == null) instance = new Catalog(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final File file;
    private final List<Recs.DataListener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<Title> movies = Collections.emptyList(), shows = Collections.emptyList();
    private volatile long at, stamp, failedAt;
    private volatile boolean fetching, active = true;
    private volatile Net.Cancel cancel;
    private volatile String error = "";
    /** Titles looked up for the details sheet (and search results), newest last; kept small. */
    private final Map<String, Title> looked = new LinkedHashMap<String, Title>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Title> e) { return size() > 60; }
    };

    private Catalog(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "catalog.json");
        try (FileInputStream in = new FileInputStream(file)) {
            JSONObject o = new JSONObject(new String(Net.readAll(in), StandardCharsets.UTF_8));
            if (o.optInt("version") == CACHE_VERSION) {
                movies = Title.readList(o.optJSONArray("movies"));
                shows = Title.readList(o.optJSONArray("shows"));
                at = o.optLong("at");
                stamp = o.optLong("stamp");
            }
        } catch (Exception ignored) {
        }
        if (!movies.isEmpty() || !shows.isEmpty()) {
            // Disk never keeps installed flags or trailers: work them out before the first tap, not on it.
            Work.io(() -> {
                try {
                    installedStamp = Services.installedStamp(context);
                    for (Title t : movies) Tmdb.refreshInstalled(context, t);
                    for (Title t : shows) Tmdb.refreshInstalled(context, t);
                    notifyRows();
                    Net.Cancel warm = new Net.Cancel();
                    Title dm = movies.isEmpty() ? null : lookUp(movies.get(0), warm);
                    if (dm != null) remember(dm);
                    Title ds = shows.isEmpty() ? null : lookUp(shows.get(0), warm);
                    if (ds != null) remember(ds);
                } catch (Exception e) {
                    AppLog.w("rows: warm " + e.getMessage());
                }
            });
        }
    }

    void addListener(Recs.DataListener l) { listeners.add(l); }
    void removeListener(Recs.DataListener l) { listeners.remove(l); }

    List<Title> movies() { return movies; }

    List<Title> shows() { return shows; }

    String error() { return error; }

    /**
     * false while the home screen is paused: nothing new starts. A fetch already running is NOT cut: on a slow TV the
     * first request alone takes ~25 s, and cutting it every time the person opens an app meant the rows never arrived.
     */
    void setActive(boolean on) {
        active = on;
    }

    /** A short SHA-256 of the key, so the saved stamp doesn't carry anything of it. */
    private static long keyDigest() {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(Config.TMDB_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFF);
            return v;
        } catch (java.security.NoSuchAlgorithmException e) {
            return 0;
        }
    }

    private long sourceStamp() {
        long s = Tmdb.REGION.hashCode() * 31L + keyDigest();
        s = s * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        return s * 31L + Services.installedStamp(context);
    }

    /** Fetches when the rows are older than 24 hours, empty, or the apps/region/settings changed. Cheap otherwise. */
    void refreshIfStale() {
        if (!active || fetching || !Tmdb.hasKey()) return;
        long now = System.currentTimeMillis();
        long s = sourceStamp();
        boolean changed = s != stamp;
        if (!changed && failedAt != 0 && now >= failedAt && now - failedAt < RETRY) return;
        boolean stale = changed || movies.isEmpty() && shows.isEmpty() || now - at > EVERY || now < at;
        if (!stale) return;
        fetching = true;
        Net.Cancel mine = cancel = new Net.Cancel();
        Work.io(() -> {
            try {
                run(mine, s);
            } finally {
                fetching = false;
            }
        });
    }

    private void run(Net.Cancel c, long s) {
        List<Title> m = null, t = null;
        String problem = "";
        try {
            // One scan fills the installed snapshot: the ~1k lookups below then skip PackageManager.
            Services.installedStamp(context);
            Set<Integer> providerIds = providerIds(c);
            m = build("movie", providerIds, c);
            t = build("tv", providerIds, c);
        } catch (Exception e) {
            problem = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        if (c.isCancelled()) return;
        if (m != null && t != null) {
            movies = Collections.unmodifiableList(m);
            shows = Collections.unmodifiableList(t);
            at = System.currentTimeMillis();
            stamp = s;
            failedAt = 0;
            error = "";
            save();
            notifyRows();
        } else {
            failedAt = System.currentTimeMillis();
            error = problem;
            AppLog.w("rows: " + problem);
        }
    }

    /** TMDB ids of the region's providers that map to an installed app (for "recent releases on your apps"). */
    private Set<Integer> providerIds(Net.Cancel c) throws Exception {
        Set<Integer> ids = new HashSet<>();
        JSONArray list = Tmdb.get("/watch/providers/movie?language=" + Tmdb.LANG + "&watch_region=" + Tmdb.REGION, c).getJSONArray("results");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String[] s = Services.match(context, o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && Services.installed(context, s[2])) ids.add(o.getInt("provider_id"));
        }
        return ids;
    }

    private static final class Candidate {
        int id;
        String imdb = "";
        boolean fromStremio;
        double imdbRating;
        Title summary;
    }

    private List<Title> build(String kind, Set<Integer> providerIds, Net.Cancel c) throws Exception {
        List<Candidate> trending = new ArrayList<>(), cinemeta = new ArrayList<>(), recent = new ArrayList<>();
        // 1. Trending this week.
        JSONArray hot = Tmdb.get("/trending/" + kind + "/week?language=" + Tmdb.LANG, c).getJSONArray("results");
        for (int i = 0; i < hot.length() && i < 20; i++) addCandidate(trending, hot.getJSONObject(i), kind);
        // 2. Recent releases on the TV's apps: movies of the past year, shows with an episode in the past 3 months.
        if (!providerIds.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (int id : providerIds) ids.append(ids.length() == 0 ? "" : "|").append(id);
            String common = "&language=" + Tmdb.LANG + "&watch_region=" + Tmdb.REGION + "&with_watch_monetization_types=flatrate|free|ads"
                    + "&include_adult=false&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                    + (Config.HIDE_ANIMATION ? "&without_genres=" + Tmdb.ANIMATION : "");
            String q = kind.equals("movie")
                    ? "/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365) + "&primary_release_date.lte=" + day(0) + common
                    : "/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0) + common;
            JSONArray found = Tmdb.get(q, c).getJSONArray("results");
            for (int i = 0; i < found.length() && i < 20; i++) addCandidate(recent, found.getJSONObject(i), kind);
        }
        // 3. Stremio's popular list (only when Stremio is on the TV): IMDb ids mapped to TMDB.
        if (Services.stremioInstalled(context)) cinemeta = cinemeta(kind, c);
        if (c.isCancelled()) throw new IOException("cancelled");

        // Merge: one of each source in turn, without repeats (a title in Cinemeta is playable in Stremio wherever it came from).
        Map<Integer, Candidate> stremio = new HashMap<>();
        for (Candidate x : cinemeta) stremio.put(x.id, x);
        List<Candidate> order = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 20 && order.size() < CANDIDATES; i++) {
            for (List<Candidate> src : java.util.Arrays.asList(trending, cinemeta, recent)) {
                if (i < src.size() && order.size() < CANDIDATES && seen.add(src.get(i).id)) {
                    Candidate x = src.get(i);
                    Candidate s = stremio.get(x.id);
                    if (s != null) {
                        x.fromStremio = true;
                        x.imdb = s.imdb;
                        x.imdbRating = s.imdbRating;
                    }
                    order.add(x);
                }
            }
        }
        // Details, 4 at a time, a chunk at a time until there are enough that can be played.
        List<Title> out = new ArrayList<>();
        // Every detail runs on its own thread: a dead network fails all of them, so count that
        // separately from titles TMDB skips (null) or the TV can't play.
        final AtomicInteger tried = new AtomicInteger(), failed = new AtomicInteger();
        final AtomicReference<String> lastProblem = new AtomicReference<>("");
        for (int from = 0; from < order.size() && out.size() < MAX; from += CHUNK) {
            List<Candidate> chunk = order.subList(from, Math.min(order.size(), from + CHUNK));
            Title[] done = new Title[chunk.size()];
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                final int slot = i;
                final Candidate x = chunk.get(i);
                jobs.add(() -> {
                    tried.incrementAndGet();
                    try {
                        done[slot] = fetch(x, kind, c);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        lastProblem.set(e.getMessage() == null ? e.toString() : e.getMessage());
                        if (!c.isCancelled()) AppLog.w("rows: title " + x.id + ": " + e.getMessage());
                    }
                });
            }
            Work.all(jobs);
            if (c.isCancelled()) throw new IOException("cancelled");
            for (Title t : done) if (t != null && t.playable() && out.size() < MAX) out.add(t);
        }
        if (out.isEmpty() && !order.isEmpty()) {
            if (tried.get() > 0 && failed.get() == tried.get()) throw new IOException(lastProblem.get());
            throw new IOException(Recs.NO_APPS);
        }
        return out;
    }

    private void addCandidate(List<Candidate> list, JSONObject o, String kind) {
        Title t = Tmdb.summary(o, kind);
        if (t.backdropPath.isEmpty() || t.tmdbId == 0) return;
        if (Config.HIDE_ANIMATION && Tmdb.animated(o)) return;
        Candidate x = new Candidate();
        x.id = t.tmdbId;
        x.summary = t;
        list.add(x);
    }

    /** The details of one candidate: genres, runtime, rating, overview and where to watch it. Null when TMDB has nothing. */
    private Title fetch(Candidate x, String kind, Net.Cancel c) throws Exception {
        JSONObject o = Tmdb.get("/" + kind + "/" + x.id + "?language=" + Tmdb.LANG + "&append_to_response=watch/providers,external_ids", c);
        Title t = x.summary != null ? x.summary : new Title();
        t.kind = kind;
        t.tmdbId = x.id;
        if (x.imdbRating > 0) {
            t.rating = x.imdbRating;
            t.imdbRating = true;
        }
        if (!x.imdb.isEmpty()) t.imdbId = x.imdb;
        Tmdb.details(context, t, o);
        if (Config.HIDE_ANIMATION && Tmdb.animated(o)) return null;
        if (t.backdropPath.isEmpty()) return null;
        t.stremio = x.fromStremio && Services.stremioInstalled(context);
        return t;
    }

    /** Cinemeta's top list of the kind, each IMDb id found on TMDB (4 at a time). */
    private List<Candidate> cinemeta(String kind, Net.Cancel c) {
        List<Candidate> out = new ArrayList<>();
        try {
            JSONArray metas = new JSONObject(Net.get(CINEMETA + (kind.equals("movie") ? "movie" : "series") + "/top.json", "Cinemeta", c))
                    .getJSONArray("metas");
            int n = Math.min(metas.length(), 20);
            Candidate[] found = new Candidate[n];
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int slot = i;
                final JSONObject meta = metas.getJSONObject(i);
                final String imdb = meta.optString("imdb_id", meta.optString("id"));
                if (!imdb.startsWith("tt")) continue;
                jobs.add(() -> {
                    try {
                        JSONObject r = Tmdb.get("/find/" + imdb + "?external_source=imdb_id&language=" + Tmdb.LANG, c);
                        JSONArray list = r.optJSONArray(kind.equals("movie") ? "movie_results" : "tv_results");
                        if (list == null || list.length() == 0) return;
                        JSONObject first = list.getJSONObject(0);
                        Title t = Tmdb.summary(first, kind);
                        if (t.tmdbId == 0 || t.backdropPath.isEmpty()) return;
                        if (Config.HIDE_ANIMATION && Tmdb.animated(first)) return;
                        Candidate x = new Candidate();
                        x.id = t.tmdbId;
                        x.imdb = imdb;
                        x.fromStremio = true;
                        x.imdbRating = parseDouble(meta.optString("imdbRating"));
                        x.summary = t;
                        found[slot] = x;
                    } catch (Exception e) {
                        if (!c.isCancelled()) AppLog.w("rows: find " + imdb + ": " + e.getMessage());
                    }
                });
            }
            Work.all(jobs);
            for (Candidate x : found) if (x != null) out.add(x);
        } catch (Exception e) {
            if (!c.isCancelled()) AppLog.w("rows: Cinemeta: " + e.getMessage());
        }
        return out;
    }

    static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The date `offset` days from today, as "2026-10-19". */
    private static String day(int offset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, offset);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH));
    }

    // MARK: - On demand: trailer, cast, fresh providers

    /** Looks the title up again with its videos and credits, then the Cinemeta fallback for the trailer. Blocking. */
    Title lookUp(Title t, Net.Cancel c) throws Exception {
        synchronized (looked) {
            Title known = looked.get(t.key());
            if (known != null && known.full) {
                Title k = known.copy();
                Tmdb.refreshInstalled(context, k);   // flags are never trusted from the cache
                return k;
            }
        }
        Title n = t.copy();
        n.trailerYouTubeId = "";   // TMDB's trailer first, then Cinemeta's; never a stale one from the row
        JSONObject o = Tmdb.get("/" + t.kind + "/" + t.tmdbId + "?language=" + Tmdb.LANG
                + "&append_to_response=watch/providers,external_ids,videos,credits&include_video_language=en,null", c);
        Tmdb.details(context, n, o);
        Tmdb.extras(n, o);
        if (n.trailerYouTubeId.isEmpty() && !n.imdbId.isEmpty()) n.trailerYouTubeId = cinemetaTrailer(n, c);
        n.stremio = (t.stremio || !n.imdbId.isEmpty()) && Services.stremioInstalled(context);
        n.full = true;
        synchronized (looked) { looked.put(n.key(), n); }
        replaceInRows(n);
        return n;
    }

    private String cinemetaTrailer(Title t, Net.Cancel c) {
        try {
            JSONObject meta = new JSONObject(Net.get(CINEMETA.replace("/catalog/", "/meta/") + t.stremioKind() + "/" + t.imdbId + ".json",
                    "Cinemeta", c)).optJSONObject("meta");
            if (meta == null) return "";
            JSONArray trailers = meta.optJSONArray("trailers");
            for (int i = 0; trailers != null && i < trailers.length(); i++) {
                String src = trailers.getJSONObject(i).optString("source");
                if (!src.isEmpty()) return src;
            }
            JSONArray streams = meta.optJSONArray("trailerStreams");
            for (int i = 0; streams != null && i < streams.length(); i++) {
                String id = streams.getJSONObject(i).optString("ytId");
                if (!id.isEmpty()) return id;
            }
        } catch (Exception e) {
            if (!c.isCancelled()) AppLog.w("trailer: " + e.getMessage());
        }
        return "";
    }

    /** A title in a row that was looked up gets the new instance (and the file is rewritten). */
    private synchronized void replaceInRows(Title n) {
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {
            List<Title> list = pass == 0 ? movies : shows;
            List<Title> next = null;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).key().equals(n.key())) {
                    if (next == null) next = new ArrayList<>(list);
                    next.set(i, n);
                }
            }
            if (next != null) {
                changed = true;
                if (pass == 0) movies = Collections.unmodifiableList(next);
                else shows = Collections.unmodifiableList(next);
            }
        }
        if (changed) save();
    }

    /** Remembers titles that came from somewhere else (search) so a second look is instant. */
    void remember(Title t) {
        synchronized (looked) {
            Title known = looked.get(t.key());
            if (known == null || !known.full) looked.put(t.key(), t);
        }
    }

    /** The app set changed (installed or removed): flags and the order of the providers are worked out again. */
    void recheckInstalled() {
        if (rechecking) return;
        rechecking = true;
        Work.io(() -> {   // package lookups: never on the main thread
            try {
                long now = Services.installedStamp(context);
                if (now == installedStamp) return;
                installedStamp = now;
                movies = recheck(movies);
                shows = recheck(shows);
                notifyRows();
            } finally {
                rechecking = false;
            }
        });
    }

    private volatile boolean rechecking;

    private volatile long installedStamp;

    private List<Title> recheck(List<Title> list) {
        List<Title> out = new ArrayList<>();
        boolean stremioOn = Services.stremioInstalled(context);
        for (Title t : list) {
            Title n = t.copy();
            Tmdb.refreshInstalled(context, n);
            if (!stremioOn) n.stremio = false;
            if (n.playable()) out.add(n);
        }
        return Collections.unmodifiableList(out);
    }

    // MARK: - Disk

    private synchronized void save() {
        try {
            JSONArray m = new JSONArray(), s = new JSONArray();
            for (Title t : movies) m.put(t.toJson());
            for (Title t : shows) s.put(t.toJson());
            byte[] bytes = new JSONObject().put("version", CACHE_VERSION).put("at", at).put("stamp", stamp)
                    .put("movies", m).put("shows", s).toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
            if (!tmp.renameTo(file)) AppLog.w("rows: couldn't save the list");
        } catch (Exception e) {
            AppLog.w("rows: couldn't save the list: " + e.getMessage());
        }
    }

    void notifyContinue() {
        Work.MAIN.post(() -> { for (Recs.DataListener l : listeners) l.onContinue(); });
    }

    void notifyRows() {
        Work.MAIN.post(() -> { for (Recs.DataListener l : listeners) l.onRows(); });
    }
}
