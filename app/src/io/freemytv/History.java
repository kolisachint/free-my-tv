package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Titles opened from this home screen (never plain app opens), newest first, one entry per title, 20 at most. Saved in
 * SharedPreferences as JSON. Use record() when the user presses Watch (Launch does it).
 */
final class History {
    private History() {}

    static final int MAX = 20;
    /** Bumped by every record(): Recs.continueItems caches by it. */
    static volatile int version;

    static final class Entry {
        int tmdbId;
        String imdbId = "", kind = "movie", title = "", backdropPath = "", posterPath = "", appKey = "";
        int year;
        long at;
    }

    private static SharedPreferences sp() {
        return Prefs.context().getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    /** Remembers that `t` was opened in the app `appKey` just now. */
    static synchronized void record(Title t, String appKey) {
        if (t == null || t.tmdbId == 0) return;
        List<Entry> list = all();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).tmdbId == t.tmdbId && list.get(i).kind.equals(t.kind)) list.remove(i);
        Entry e = new Entry();
        e.tmdbId = t.tmdbId;
        e.imdbId = t.imdbId;
        e.kind = t.kind;
        e.title = t.title;
        e.year = t.year;
        e.backdropPath = t.backdropPath;
        e.posterPath = t.posterPath;
        e.appKey = appKey == null ? "" : appKey;
        e.at = System.currentTimeMillis();
        list.add(0, e);
        while (list.size() > MAX) list.remove(list.size() - 1);
        write(list);
        version++;
        Catalog.get(Prefs.context()).notifyContinue();
    }

    /** Newest first. */
    static synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.tmdbId = o.optInt("id");
                e.imdbId = o.optString("imdb");
                e.kind = o.optString("kind", "movie");
                e.title = o.optString("title");
                e.year = o.optInt("year");
                e.backdropPath = o.optString("backdrop");
                e.posterPath = o.optString("poster");
                e.appKey = o.optString("app");
                e.at = o.optLong("at");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void clear() {
        sp().edit().remove("list").apply();
        version++;
    }

    private static void write(List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                a.put(new JSONObject().put("id", e.tmdbId).put("imdb", e.imdbId).put("kind", e.kind).put("title", e.title)
                        .put("year", e.year).put("backdrop", e.backdropPath).put("poster", e.posterPath).put("app", e.appKey).put("at", e.at));
            }
            sp().edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** The newest `n` as Continue cards. */
    static List<ContinueItem> items(int n, long now) {
        List<ContinueItem> out = new ArrayList<>();
        for (Entry e : all()) {
            if (out.size() >= n) break;
            ContinueItem c = new ContinueItem();
            c.title = e.title;
            c.kind = e.kind;
            c.source = e.appKey;
            c.sourceName = Services.NAME.containsKey(e.appKey) ? Services.NAME.get(e.appKey) : e.appKey;
            c.subtitle = ContinueItem.openedAgo(e.at, now);
            c.at = e.at;
            c.tmdbId = e.tmdbId;
            c.imdbId = e.imdbId;
            c.backdropPath = e.backdropPath;
            c.posterPath = e.posterPath;
            c.pkg = pkgOf(e.appKey);
            if (e.appKey.equals(Services.STREMIO) && !e.imdbId.isEmpty()) c.deepLink = ContinueItem.stremioLink(e.kind.equals("movie") ? "movie" : "series", e.imdbId, "");
            out.add(c);
        }
        return out;
    }

    private static String pkgOf(String appKey) {
        if (appKey.equals(Services.STREMIO)) return Services.STREMIO_PKG;
        Context c = Prefs.context();
        String first = "";
        for (String[] s : Services.TABLE) {
            if (!s[1].equals(appKey)) continue;
            if (first.isEmpty()) first = s[2];
            if (c != null && HomeApps.launchIntent(c.getPackageManager(), s[2]) != null) return s[2];
        }
        return first;
    }
}
