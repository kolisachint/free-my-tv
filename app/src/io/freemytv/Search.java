package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Search: TMDB's /search/multi (movies and shows, en-US, your region, no adult titles) plus the apps on the TV whose
 * name matches. The caller debounces (~300 ms after the last key). A new query() cuts the one still running.
 * Titles are best first: all 10 summaries land at once, the top 3 carry their ways to watch a moment later (a
 * second partial Result), the rest when no newer query is pending (the final Result; Recs.details fills any gap
 * left by a cut-off query when a title is opened). The last 20 /search/multi answers are cached, so retyping is
 * instant and never touches the network.
 */
final class Search {
    private Search() {}

    /** An installed app whose name matched. */
    static final class AppMatch {
        final String pkg, label;

        AppMatch(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    static final class Result {
        String query = "";
        List<Title> titles = new ArrayList<>();     // at most 10, best first
        List<AppMatch> apps = new ArrayList<>();    // best first
        boolean partial;                            // titles are in but their ways to watch are still coming (a final Result follows)
        boolean failed;                             // the network or TMDB failed (titles is empty then)
    }

    interface Callback { void onResult(Result result); }

    private static final int MAX_TITLES = 10, DETAILED = 3, CACHE_SIZE = 20;
    private static int generation;
    private static Net.Cancel running;
    // The last /search/multi answers by exact query (raw JSON, memory only): retyping never hits the network.
    private static final LinkedHashMap<String, String> MULTI = new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > CACHE_SIZE; }
    };

    /**
     * Runs the search in the background and calls `cb` on the main thread, unless a newer query() or cancel() came
     * first (then never). Fewer than 3 letters: answers at once with only app matches, no network.
     */
    static synchronized void query(Context c, String q, Callback cb) {
        final Context app = c.getApplicationContext();
        final String text = q == null ? "" : q.trim();
        final int mine = ++generation;
        if (running != null) running.cancel();
        final Net.Cancel cancel = running = new Net.Cancel();
        if (text.length() < 3 || !Tmdb.hasKey()) {
            Work.io(() -> {   // the package list is read off the main thread
                if (cancel.isCancelled()) return;
                Result r = new Result();
                r.query = text;
                r.apps = apps(app, text);
                r.failed = !Tmdb.hasKey() && text.length() >= 3;
                Work.MAIN.post(() -> { if (current(mine)) cb.onResult(r); });
            });
            return;
        }
        // One shared io job (never a thread per query): a newer query() cancels this one before it starts or mid-flight.
        Work.io(() -> {
            if (cancel.isCancelled()) return;
            Result r = new Result();
            r.query = text;
            r.apps = apps(app, text);
            try {
                r.titles = titles(app, text, cancel, early -> {
                    Result p = new Result();
                    p.query = text;
                    p.apps = r.apps;
                    p.titles = early;
                    p.partial = true;
                    Work.MAIN.post(() -> { if (current(mine)) cb.onResult(p); });
                });
            } catch (Throwable e) {
                if (cancel.isCancelled()) return;
                r.failed = true;
                AppLog.w("search '" + text.length() + " letters' failed: " + e);
            }
            if (cancel.isCancelled()) return;
            Work.MAIN.post(() -> { if (current(mine)) cb.onResult(r); });
        });
    }

    /** Cuts the running query (the search screen closed). */
    static synchronized void cancel() {
        generation++;
        if (running != null) running.cancel();
        running = null;
    }

    private static synchronized boolean current(int g) {
        return g == generation;
    }

    /**
     * The ranked titles (best first). Publishes twice through `early`: all summaries at once, then the top 3 with
     * their ways to watch; the rest are detailed only when no newer query is pending (the caller drops a cut-off
     * result, and Recs.details fills any gap when a title is opened). The /search/multi answer is cached per
     * query, so retyping never touches the network.
     */
    private static List<Title> titles(Context c, String text, Net.Cancel cancel, Consumer<List<Title>> early) throws Exception {
        JSONObject resp;
        String cached;
        synchronized (Search.class) { cached = MULTI.get(text); }
        if (cached != null) {
            resp = new JSONObject(cached);
        } else {
            resp = Tmdb.get("/search/multi?query=" + URLEncoder.encode(text, "UTF-8") + "&language=" + Tmdb.LANG
                    + "&region=" + Tmdb.REGION + "&include_adult=false&page=1", cancel);
            synchronized (Search.class) { MULTI.put(text, resp.toString()); }
        }
        JSONArray found = resp.getJSONArray("results");
        final List<Title> list = new ArrayList<>();
        final List<Integer> scores = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.getJSONObject(i);
            String type = o.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            Title t = Tmdb.summary(o, type);
            if (t.tmdbId == 0 || t.title.isEmpty() || t.backdropPath.isEmpty() && t.posterPath.isEmpty()) continue;
            list.add(t);
            scores.add(Fuzzy.score(text, t.title, o.optString(type.equals("movie") ? "original_title" : "original_name")));
        }
        // Best first: titles that match the words, in TMDB's own (popularity) order within the same score band; the
        // rest (TMDB matched something we don't, like a translated name) after them.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> {
            int sa = scores.get(a), sb = scores.get(b);
            if (sa > 0 != sb > 0) return sa > 0 ? -1 : 1;
            if (sa > 0 && sb > 0 && band(sa) != band(sb)) return band(sb) - band(sa);
            return a - b;   // stable: TMDB's order
        });
        final List<Title> top = new ArrayList<>();
        for (int i : order) if (top.size() < MAX_TITLES) top.add(list.get(i));
        early.accept(new ArrayList<>(top));   // wave 1: every summary at once
        detailInto(c, top, 0, Math.min(DETAILED, top.size()), cancel);
        if (cancel.isCancelled()) return top;
        if (top.size() > DETAILED) early.accept(new ArrayList<>(top));   // wave 2: the top 3 detailed, fast
        if (cancel.isCancelled()) return top;   // a newer query is pending: leave the rest as summaries
        detailInto(c, top, Math.min(DETAILED, top.size()), top.size(), cancel);
        if (cancel.isCancelled()) return top;
        for (Title t : top) Catalog.get(c).remember(t);
        return top;
    }

    /**
     * Details slots [from, to): one call each (details with watch providers and the IMDb id), 4 at a time. Each
     * job writes only its own array slot; the finished titles are copied back here, on this thread, so the fan
     * threads never touch the published list.
     */
    private static void detailInto(Context c, List<Title> top, int from, int to, Net.Cancel cancel) {
        if (from >= to) return;
        final Title[] slots = new Title[to - from];
        List<Runnable> jobs = new ArrayList<>();
        for (int i = from; i < to; i++) {
            final int slot = i;
            final Title t = top.get(i);
            jobs.add(() -> {
                if (cancel.isCancelled()) return;
                try {
                    Title n = t.copy();
                    JSONObject o = Tmdb.get("/" + t.kind + "/" + t.tmdbId + "?language=" + Tmdb.LANG
                            + "&append_to_response=watch/providers,external_ids", cancel);
                    Tmdb.details(c, n, o);
                    n.stremio = !n.imdbId.isEmpty() && Services.stremioInstalled(c);
                    slots[slot - from] = n;
                } catch (Exception e) {
                    if (!cancel.isCancelled()) AppLog.w("search: details " + t.tmdbId + ": " + e.getMessage());
                }
            });
        }
        Work.all(jobs);
        for (int i = from; i < to; i++) if (slots[i - from] != null) top.set(i, slots[i - from]);
    }

    /** Scores within 300 of each other count as the same band (so popularity order breaks the tie). */
    private static int band(int score) {
        return score / 300;
    }

    // MARK: - Apps

    private static List<String[]> launchable;
    private static long launchableAt;

    /** Reads the installed apps in the background so the first letters typed answer from the cache. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        Work.io(() -> installedApps(app));
    }

    /** Installed apps whose name matches (case and accent insensitive, word prefix or substring, one typo from 4 letters). */
    static List<AppMatch> apps(Context c, String q) {
        List<AppMatch> out = new ArrayList<>();
        if (q == null || Fuzzy.norm(q).isEmpty()) return out;
        List<String[]> all = installedApps(c);
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int s = Fuzzy.score(q, all.get(i)[1]);
            if (s > 0) scored.add(new int[] {i, s});
        }
        Collections.sort(scored, (a, b) -> b[1] != a[1] ? b[1] - a[1] : a[0] - b[0]);
        for (int[] s : scored) out.add(new AppMatch(all.get(s[0])[0], all.get(s[0])[1]));
        return out;
    }

    private static synchronized List<String[]> installedApps(Context c) {
        long now = System.currentTimeMillis();
        if (launchable == null || now - launchableAt > 60_000L || now < launchableAt) {
            List<String[]> list = new ArrayList<>();
            for (String[] a : HomeApps.launchable(c)) {
                if (a[0].equals(c.getPackageName()) || HomeApps.HIDDEN.contains(a[0])) continue;
                list.add(a);
            }
            launchable = list;
            launchableAt = now;
        }
        return launchable;
    }
}
