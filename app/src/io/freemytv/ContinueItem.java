package io.freemytv;

/** One card of the Continue row: something opened from the home (any app) or in progress in Stremio. */
final class ContinueItem {
    static final String SOURCE_STREMIO = "stremio";

    String title = "";
    String kind = "movie";          // "movie" or "tv"
    String source = "";             // SOURCE_STREMIO, or the app key it was opened in ("netflix", "prime"...)
    String sourceName = "";         // "Stremio", "Netflix": the card's badge
    String subtitle = "";           // "S2 E5 · 23 min left", "23 min left", "Opened 2 h ago"
    float progress;                 // 0..1 (0 = no bar)
    long at;                        // epoch ms of the last watch / open (the row is newest first)
    int tmdbId;                     // 0 when unknown (Stremio items)
    String imdbId = "";
    String backdropPath = "";       // TMDB path, or ""
    String posterPath = "";         // TMDB path, or ""
    String remoteBackdrop = "";     // full address (Stremio's metahub), when there's no TMDB path
    String remotePoster = "";
    String pkg = "";                // package of the app to open
    String deepLink = "";           // opens the title (Stremio: stremio:///detail/...); "" = just open the app

    /** The landscape image for a card `widthPx` wide ("" if none: draw the placeholder with the title). */
    String backdropUrl(int widthPx) {
        if (!backdropPath.isEmpty()) return Tmdb.image(backdropPath, widthPx);
        if (!remoteBackdrop.isEmpty()) return remoteBackdrop.replace("/medium/", widthPx <= 400 ? "/small/" : "/medium/");
        return "";
    }

    /** The portrait image, or "". */
    String posterUrl(int widthPx) {
        if (!posterPath.isEmpty()) return Tmdb.poster(posterPath, widthPx);
        return remotePoster;
    }

    /**
     * Stremio's address for a title: stremio:///detail/movie/tt0111161 or, to resume, stremio:///detail/series/tt0944947/tt0944947:2:5.
     * `type` is "movie" or "series"; `videoId` may be "".
     */
    static String stremioLink(String type, String id, String videoId) {
        return "stremio:///detail/" + type + "/" + id + (videoId == null || videoId.isEmpty() ? "" : "/" + videoId);
    }

    /** "Opened 2 h ago" / "Opened just now" / "Opened yesterday" / "Opened 3 days ago". */
    static String openedAgo(long at, long now) {
        long min = Math.max(0, now - at) / 60_000L;
        if (min < 2) return "Opened just now";
        if (min < 60) return "Opened " + min + " min ago";
        long h = min / 60;
        if (h < 24) return "Opened " + h + " h ago";
        long d = h / 24;
        return d == 1 ? "Opened yesterday" : "Opened " + d + " days ago";
    }
}
