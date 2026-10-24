package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * TMDB: the request helper (key from config.properties, never logged), image addresses, and the parsing that turns a
 * TMDB answer into a Title. English (en-US) and the configured region.
 */
final class Tmdb {
    private Tmdb() {}

    static final String API = "https://api.themoviedb.org/3", IMAGES = "https://image.tmdb.org/t/p/";
    static final String LANG = "en-US";
    static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    /** TMDB's Animation genre (cartoons and anime), left out of the rows with hide_animation=true. */
    static final int ANIMATION = 16;

    static boolean hasKey() {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    static JSONObject get(String path, Net.Cancel cancel) throws IOException, JSONException {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        try {
            return new JSONObject(bearer ? Net.get(url, "TMDB", cancel, "Authorization", "Bearer " + key)
                    : Net.get(url, "TMDB", cancel));
        } catch (Net.HttpError e) {
            if (e.code == 401) throw new IOException("TMDB rejected the key");
            throw e;
        }
    }

    /**
     * A backdrop (or still, or logo) address: the smallest of TMDB's w300, w500, w780 and w1280 that is at least
     * `widthPx` wide (w1280 above that). Never "original". `path` is "/abc.jpg"; a full address passes through.
     */
    static String image(String path, int widthPx) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return IMAGES + (widthPx <= 300 ? "w300" : widthPx <= 500 ? "w500" : widthPx <= 780 ? "w780" : "w1280") + path;
    }

    /** A poster address: TMDB's posters come in w185, w342, w500 and w780 (w300 doesn't exist for them). */
    static String poster(String path, int widthPx) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return IMAGES + (widthPx <= 185 ? "w185" : widthPx <= 342 ? "w342" : widthPx <= 500 ? "w500" : "w780") + path;
    }

    // MARK: - Parsing

    /** A title from a list entry (trending, discover, search, find): what the list says, nothing else. */
    static Title summary(JSONObject o, String kind) {
        Title t = new Title();
        t.kind = kind;
        t.tmdbId = o.optInt("id");
        t.title = o.optString(kind.equals("movie") ? "title" : "name");
        t.year = year(o.optString(kind.equals("movie") ? "release_date" : "first_air_date"));
        t.overview = o.optString("overview");
        t.backdropPath = path(o, "backdrop_path");
        t.posterPath = path(o, "poster_path");
        if (o.optInt("vote_count") > 0) t.rating = Math.round(o.optDouble("vote_average", 0) * 10) / 10.0;
        JSONArray g = o.optJSONArray("genre_ids");
        for (int i = 0; g != null && i < g.length() && t.genres.size() < 2; i++) {
            String name = genre(g.optInt(i));
            if (name != null) t.genres.add(name);
        }
        return t;
    }

    static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        JSONArray full = o.optJSONArray("genres");
        for (int i = 0; full != null && i < full.length(); i++) if (full.optJSONObject(i) != null && full.optJSONObject(i).optInt("id") == ANIMATION) return true;
        return false;
    }

    /**
     * Fills `t` from /movie/{id} or /tv/{id} answered with append_to_response=watch/providers,external_ids
     * (and videos, credits when asked for). Providers are the region's, with `installed` set for this TV.
     */
    static void details(Context c, Title t, JSONObject o) {
        boolean movie = t.isMovie();
        String name = o.optString(movie ? "title" : "name");
        if (!name.isEmpty()) t.title = name;
        if (!o.optString("overview").isEmpty()) t.overview = o.optString("overview");
        if (!path(o, "backdrop_path").isEmpty()) t.backdropPath = path(o, "backdrop_path");
        if (!path(o, "poster_path").isEmpty()) t.posterPath = path(o, "poster_path");
        int y = year(o.optString(movie ? "release_date" : "first_air_date"));
        if (y > 0) t.year = y;
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null) {
            t.genres = new java.util.ArrayList<>();
            for (int i = 0; i < genres.length() && t.genres.size() < 2; i++) {
                String g = genres.optJSONObject(i) == null ? "" : genres.optJSONObject(i).optString("name");
                if (!g.isEmpty()) t.genres.add(g);
            }
        }
        if (movie) t.runtime = o.optInt("runtime", t.runtime);
        else t.seasons = o.optInt("number_of_seasons", t.seasons);
        if (o.optInt("vote_count") > 0 && !t.imdbRating) t.rating = Math.round(o.optDouble("vote_average", 0) * 10) / 10.0;
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = ids == null ? o.optString("imdb_id") : ids.optString("imdb_id");
        if (imdb != null && imdb.startsWith("tt")) t.imdbId = imdb;
        JSONObject wp = o.optJSONObject("watch/providers");
        if (wp != null) t.providers = providers(c, wp.optJSONObject("results"));
        refreshInstalled(c, t);
        t.detailed = true;
    }

    /** Videos and credits (asked for with append_to_response=videos,credits): the trailer and the first three actors. */
    static void extras(Title t, JSONObject o) {
        JSONObject v = o.optJSONObject("videos");
        JSONArray list = v == null ? null : v.optJSONArray("results");
        String best = "";
        int bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null || !"YouTube".equals(x.optString("site")) || !"Trailer".equals(x.optString("type"))) continue;
            String key = x.optString("key");
            if (key.isEmpty()) continue;
            int score = (x.optBoolean("official") ? 2 : 0) + ("en".equals(x.optString("iso_639_1")) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        if (!best.isEmpty()) t.trailerYouTubeId = best;
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        t.cast = new java.util.ArrayList<>();
        for (int i = 0; cast != null && i < cast.length() && t.cast.size() < 3; i++) {
            String n = cast.optJSONObject(i) == null ? "" : cast.optJSONObject(i).optString("name");
            if (!n.isEmpty()) t.cast.add(n);
        }
    }

    /** The region's providers as Provider lists: streaming (flatrate, free, ads), rent, buy; up to 12. */
    static java.util.List<Title.Provider> providers(Context c, JSONObject results) {
        java.util.List<Title.Provider> out = new java.util.ArrayList<>();
        JSONObject region = results == null ? null : results.optJSONObject(REGION);
        if (region == null) return out;
        String[] groups = {"flatrate", "free", "ads", "rent", "buy"};
        for (String g : groups) {
            JSONArray list = region.optJSONArray(g);
            String kind = g.equals("rent") ? Title.Provider.RENT : g.equals("buy") ? Title.Provider.BUY : Title.Provider.STREAM;
            for (int i = 0; list != null && i < list.length() && out.size() < 12; i++) {
                JSONObject p = list.optJSONObject(i);
                if (p == null) continue;
                String raw = p.optString("provider_name");
                String[] s = Services.match(c, raw, p.optInt("provider_id"));
                Title.Provider pr = new Title.Provider();
                pr.kind = kind;
                pr.price = "";
                if (s != null) {
                    pr.appKey = s[1];
                    pr.pkg = s[2];
                    pr.name = Services.NAME.get(s[1]);
                } else {
                    pr.appKey = "";
                    pr.name = raw.replaceAll(" (with Ads|Amazon Channel)$", "").trim();
                }
                boolean dup = false;
                for (Title.Provider q : out) dup |= q.kind.equals(pr.kind) && q.name.equals(pr.name);
                if (!dup && !pr.name.isEmpty()) out.add(pr);
            }
        }
        return out;
    }

    /** Sets `installed` on every provider (and Stremio's flag stays as it is) and puts the installed ones first in each kind. */
    static void refreshInstalled(Context c, Title t) {
        java.util.Map<String, Boolean> have = new java.util.HashMap<>();
        for (Title.Provider p : t.providers) {
            if (p.pkg == null) {
                p.installed = false;
                continue;
            }
            Boolean b = have.get(p.pkg);
            if (b == null) {
                b = Services.installed(c, p.pkg);
                have.put(p.pkg, b);
            }
            p.installed = b;
        }
        // The right package when a service has several (Prime Video, Apple TV): the installed one.
        for (Title.Provider p : t.providers) {
            if (p.installed || p.pkg == null) continue;
            for (String[] s : Services.TABLE) {
                if (s[1].equals(p.appKey) && Services.installed(c, s[2])) {
                    p.pkg = s[2];
                    p.installed = true;
                    break;
                }
            }
        }
        java.util.List<Title.Provider> sorted = new java.util.ArrayList<>();
        for (String kind : new String[] {Title.Provider.STREAM, Title.Provider.RENT, Title.Provider.BUY}) {
            for (Title.Provider p : t.providers) if (p.kind.equals(kind) && p.installed) sorted.add(p);
            for (Title.Provider p : t.providers) if (p.kind.equals(kind) && !p.installed) sorted.add(p);
        }
        t.providers = sorted;
    }

    private static String path(JSONObject o, String field) {
        String p = o.optString(field, "");
        return p.isEmpty() || "null".equals(p) ? "" : p;
    }

    static int year(String date) {
        if (date == null || date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** TMDB's genre ids (movies and shows) → English names, for lists that give ids only. */
    static String genre(int id) {
        switch (id) {
            case 28: return "Action";
            case 12: return "Adventure";
            case 16: return "Animation";
            case 35: return "Comedy";
            case 80: return "Crime";
            case 99: return "Documentary";
            case 18: return "Drama";
            case 10751: return "Family";
            case 14: return "Fantasy";
            case 36: return "History";
            case 27: return "Horror";
            case 10402: return "Music";
            case 9648: return "Mystery";
            case 10749: return "Romance";
            case 878: return "Science Fiction";
            case 53: return "Thriller";
            case 10752: return "War";
            case 37: return "Western";
            case 10759: return "Action & Adventure";
            case 10762: return "Kids";
            case 10763: return "News";
            case 10764: return "Reality";
            case 10765: return "Sci-Fi & Fantasy";
            case 10766: return "Soap";
            case 10767: return "Talk";
            case 10768: return "War & Politics";
            default: return null;
        }
    }
}
