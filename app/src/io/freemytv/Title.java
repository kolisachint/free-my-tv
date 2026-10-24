package io.freemytv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A movie or a show for the home screen. Treat it as read-only: Recs/Search hand out new instances when they learn
 * more (see Recs.details). Texts are English (TMDB en-US). Images are TMDB paths: ask Images with backdropUrl/posterUrl.
 */
final class Title {
    /** One way to watch. */
    static final class Provider {
        static final String STREAM = "stream", RENT = "rent", BUY = "buy";
        String name;        // "Netflix", "JioHotstar", "Zee5"
        String kind;        // STREAM, RENT or BUY
        String appKey;      // our key for the service ("netflix"), or "" when we have no app for it
        String pkg;         // its Android package, or null
        boolean installed;  // the app is on the TV and not in hide_services (set when the lists are read)
        String price;       // "" (TMDB doesn't say)

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("n", name).put("k", kind).put("a", appKey).put("p", pkg == null ? "" : pkg).put("c", price);
        }

        static Provider fromJson(JSONObject o) {
            Provider p = new Provider();
            p.name = o.optString("n");
            p.kind = o.optString("k", STREAM);
            p.appKey = o.optString("a");
            p.pkg = o.optString("p").isEmpty() ? null : o.optString("p");
            p.price = o.optString("c");
            return p;
        }
    }

    int tmdbId;
    String imdbId = "";             // "tt1234567" or ""
    String kind = "movie";          // "movie" or "tv"
    String title = "";
    int year;                       // 0 = unknown
    List<String> genres = new ArrayList<>();   // at most 2
    int runtime;                    // minutes (movies), 0 = unknown
    int seasons;                    // shows, 0 = unknown
    double rating;                  // 0 = none; 0..10
    boolean imdbRating;             // rating is IMDb's (else TMDB's vote average)
    String overview = "";
    String backdropPath = "", posterPath = "";   // TMDB paths ("/abc.jpg") or ""
    String trailerYouTubeId = "";   // "" until Recs.details has looked (then possibly still "")
    List<String> cast = new ArrayList<>();      // up to 3, only after Recs.details
    List<Provider> providers = new ArrayList<>();   // streaming first, then rent, then buy; installed first in each
    boolean stremio;                // Stremio is a way to watch it (and Stremio is installed)
    boolean detailed;               // genres, runtime, providers and the like are filled
    boolean full;                   // trailer and cast have been looked up

    boolean isMovie() { return "movie".equals(kind); }

    /** "movie:603" / "tv:1396": movies and shows are numbered separately on TMDB. */
    String key() { return kind + ":" + tmdbId; }

    /** Stremio's name for the kind: "movie" or "series". */
    String stremioKind() { return isMovie() ? "movie" : "series"; }

    String backdropUrl(int widthPx) { return backdropPath.isEmpty() ? "" : Tmdb.image(backdropPath, widthPx); }

    String posterUrl(int widthPx) { return posterPath.isEmpty() ? "" : Tmdb.poster(posterPath, widthPx); }

    /** Streaming providers whose app is on the TV (the "Watch" buttons), first the best. */
    List<Provider> installedStreams() {
        List<Provider> out = new ArrayList<>();
        for (Provider p : providers) if (p.installed && Provider.STREAM.equals(p.kind)) out.add(p);
        return out;
    }

    /** It can be played now: a streaming app on the TV has it, or Stremio does. */
    boolean playable() {
        return stremio || !installedStreams().isEmpty();
    }

    /** "2026 · Adventure, Biography · 2h 6m" (shows: "2026 · Drama · 3 seasons"); parts left out when unknown. */
    String metaLine() {
        List<String> parts = new ArrayList<>();
        if (year > 0) parts.add(String.valueOf(year));
        if (!genres.isEmpty()) parts.add(String.join(", ", genres));
        if (isMovie() && runtime > 0) parts.add(runtime >= 60 ? (runtime / 60) + "h " + (runtime % 60) + "m" : runtime + "m");
        if (!isMovie() && seasons > 0) parts.add(seasons + (seasons == 1 ? " season" : " seasons"));
        return String.join(" · ", parts);
    }

    /** "7.7" or "" when there's no rating. */
    String ratingText() {
        return rating > 0 ? String.format(Locale.US, "%.1f", rating) : "";
    }

    /** "year · IMDb 7.7" (the card's second line): "2026 · IMDb 7.7", "2026 · TMDB 7.7" or just the year. */
    String cardLine() {
        String r = ratingText();
        String y = year > 0 ? String.valueOf(year) : "";
        if (r.isEmpty()) return y;
        String tag = (imdbRating ? "IMDb " : "TMDB ") + r;
        return y.isEmpty() ? tag : y + " · " + tag;
    }

    Title copy() {
        Title t = new Title();
        t.tmdbId = tmdbId;
        t.imdbId = imdbId;
        t.kind = kind;
        t.title = title;
        t.year = year;
        t.genres = new ArrayList<>(genres);
        t.runtime = runtime;
        t.seasons = seasons;
        t.rating = rating;
        t.imdbRating = imdbRating;
        t.overview = overview;
        t.backdropPath = backdropPath;
        t.posterPath = posterPath;
        t.trailerYouTubeId = trailerYouTubeId;
        t.cast = new ArrayList<>(cast);
        t.providers = new ArrayList<>();
        for (Provider p : providers) {
            Provider q = new Provider();
            q.name = p.name;
            q.kind = p.kind;
            q.appKey = p.appKey;
            q.pkg = p.pkg;
            q.installed = p.installed;
            q.price = p.price;
            t.providers.add(q);
        }
        t.stremio = stremio;
        t.detailed = detailed;
        t.full = full;
        return t;
    }

    JSONObject toJson() throws JSONException {
        JSONArray g = new JSONArray(), c = new JSONArray(), p = new JSONArray();
        for (String s : genres) g.put(s);
        for (String s : cast) c.put(s);
        for (Provider pr : providers) p.put(pr.toJson());
        return new JSONObject().put("id", tmdbId).put("imdb", imdbId).put("kind", kind).put("title", title).put("year", year)
                .put("genres", g).put("runtime", runtime).put("seasons", seasons).put("rating", rating).put("imdbRating", imdbRating)
                .put("overview", overview).put("backdrop", backdropPath).put("poster", posterPath).put("trailer", trailerYouTubeId)
                .put("cast", c).put("providers", p).put("stremio", stremio).put("detailed", detailed).put("full", full);
    }

    static Title fromJson(JSONObject o) {
        Title t = new Title();
        t.tmdbId = o.optInt("id");
        t.imdbId = o.optString("imdb");
        t.kind = o.optString("kind", "movie");
        t.title = o.optString("title");
        t.year = o.optInt("year");
        JSONArray g = o.optJSONArray("genres"), c = o.optJSONArray("cast"), p = o.optJSONArray("providers");
        for (int i = 0; g != null && i < g.length(); i++) t.genres.add(g.optString(i));
        for (int i = 0; c != null && i < c.length(); i++) t.cast.add(c.optString(i));
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject po = p.optJSONObject(i);
            if (po != null) t.providers.add(Provider.fromJson(po));
        }
        t.runtime = o.optInt("runtime");
        t.seasons = o.optInt("seasons");
        t.rating = o.optDouble("rating", 0);
        t.imdbRating = o.optBoolean("imdbRating");
        t.overview = o.optString("overview");
        t.backdropPath = o.optString("backdrop");
        t.posterPath = o.optString("poster");
        t.trailerYouTubeId = o.optString("trailer");
        t.stremio = o.optBoolean("stremio");
        t.detailed = o.optBoolean("detailed");
        t.full = o.optBoolean("full");
        return t;
    }

    static List<Title> readList(JSONArray a) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return Collections.unmodifiableList(out);
    }
}
