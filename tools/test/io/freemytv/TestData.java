package io.freemytv;

import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/** Plain-JVM checks of the pure parts of the home data layer. Run: tools/test_data.sh */
public class TestData {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        fuzzy();
        stremio();
        sun();
        stores();
        System.out.println(failures == 0 ? "All good." : failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void stores() {
        String[][] want = {{"2", "com.apple.atve.sony.appletv"}, {"10", "com.amazon.amazonvideo.livingroom"}, {"192", Services.YOUTUBE_PKG}};
        for (String[] w : want) {
            boolean found = false;
            for (String[] s : Services.STORES) found |= s[0].equals(w[0]) && s[2].equals(w[1]);
            check(found, "store " + w[0] + " maps to " + w[1]);
        }
    }

    static void fuzzy() {
        check(Fuzzy.norm("Amélie  Poulain!") .equals("amelie poulain"), "norm strips accents, case and punctuation");
        check(Fuzzy.score("spid", "Spider-Man: Brand New Day") > 0, "word prefix matches");
        check(Fuzzy.score("man", "Spider-Man") > 0, "word match inside a hyphenated title");
        check(Fuzzy.score("ider", "Spider-Man") > 0, "substring (3+ letters) matches");
        check(Fuzzy.score("netflx", "Netflix") > 0, "one typo (6 letters) tolerated");
        check(Fuzzy.score("spidr", "Spider-Man") > 0, "one typo (5 letters) tolerated");
        check(Fuzzy.score("abc", "Abd") == 0, "no typo tolerance under 4 letters");
        check(Fuzzy.score("xyzq", "Spider-Man") == 0, "no match = 0");
        check(Fuzzy.score("panchayat", "Panchayat") > Fuzzy.score("panch", "Panchayat Returns Again") - 1, "full title scores well");
        check(Fuzzy.score("youtube", "YouTube") > Fuzzy.score("tube", "YouTube"), "prefix beats substring");
        check(Fuzzy.score("spider man", "Spider-Man") > 0, "two words match across a hyphen");
        check(Fuzzy.score("spider zzzz", "Spider-Man") == 0, "every word must match");
        check(Fuzzy.score("amelie", "Amélie") > 0, "accent-insensitive");
        check(Fuzzy.oneEdit("abcd", "abxd") && Fuzzy.oneEdit("abcd", "abd") && Fuzzy.oneEdit("abd", "abcd") && !Fuzzy.oneEdit("abcd", "axyd"), "oneEdit");
    }

    static final String LIBRARY = "{\"result\":["
            // series in progress: S2 E5, 23 minutes left of 50
            + "{\"_id\":\"tt0944947\",\"name\":\"Game of Thrones\",\"type\":\"series\",\"poster\":\"https://img/got.jpg\",\"removed\":false,"
            + "\"_mtime\":\"2026-10-23T19:04:11.123Z\",\"state\":{\"timeOffset\":1620000,\"duration\":3000000,\"video_id\":\"tt0944947:2:5\",\"season\":2,\"episode\":5}},"
            // movie in progress, newer, 2 h 10 min left
            + "{\"_id\":\"tt1375666\",\"name\":\"Inception\",\"type\":\"movie\",\"poster\":\"\",\"removed\":false,"
            + "\"_mtime\":\"2026-10-24T08:00:00.000Z\",\"state\":{\"timeOffset\":600000,\"duration\":8280000,\"video_id\":\"tt1375666\"}},"
            // removed: skipped
            + "{\"_id\":\"tt0000001\",\"name\":\"Removed\",\"type\":\"movie\",\"removed\":true,\"_mtime\":\"2026-10-24T09:00:00.000Z\",\"state\":{\"timeOffset\":5,\"duration\":10}},"
            // not started: skipped
            + "{\"_id\":\"tt0000002\",\"name\":\"Unstarted\",\"type\":\"movie\",\"removed\":false,\"_mtime\":\"2026-10-24T09:00:00.000Z\",\"state\":{\"timeOffset\":0,\"duration\":10}},"
            // finished (98%): skipped
            + "{\"_id\":\"tt0000003\",\"name\":\"Finished\",\"type\":\"movie\",\"removed\":false,\"_mtime\":\"2026-10-24T09:00:00.000Z\",\"state\":{\"timeOffset\":98,\"duration\":100}},"
            // series with the episode only in video_id
            + "{\"_id\":\"tt0903747\",\"name\":\"Breaking Bad\",\"type\":\"series\",\"removed\":false,\"_mtime\":\"2026-10-22T10:00:00.000Z\","
            + "\"state\":{\"timeOffset\":60000,\"duration\":2700000,\"video_id\":\"tt0903747:1:3\"}},"
            // not a movie or series (a channel): skipped
            + "{\"_id\":\"yt_id:abc\",\"name\":\"Channel\",\"type\":\"channel\",\"removed\":false,\"_mtime\":\"2026-10-24T09:00:00.000Z\",\"state\":{\"timeOffset\":5,\"duration\":10}}"
            + "]}";

    static void stremio() throws Exception {
        long now = Stremio.time("2026-10-24T12:00:00.000Z", 0);
        List<ContinueItem> items = Stremio.parse(new JSONObject(LIBRARY), now);
        check(items.size() == 3, "3 items in progress (got " + items.size() + ")");
        check(items.get(0).title.equals("Inception") && items.get(1).title.equals("Game of Thrones") && items.get(2).title.equals("Breaking Bad"),
                "newest first");
        ContinueItem got = items.get(1);
        check(got.subtitle.equals("S2 E5 · 23 min left"), "series subtitle: " + got.subtitle);
        check(Math.abs(got.progress - 0.54f) < 0.001f, "progress " + got.progress);
        check(got.deepLink.equals("stremio:///detail/series/tt0944947/tt0944947:2:5"), "series deep link: " + got.deepLink);
        check(got.kind.equals("tv") && got.source.equals("stremio") && got.imdbId.equals("tt0944947"), "kind, source, imdb");
        check(got.backdropUrl(780).equals("https://images.metahub.space/background/medium/tt0944947/img"), "metahub backdrop");
        check(got.posterUrl(300).equals("https://img/got.jpg"), "poster from the library item");
        ContinueItem inc = items.get(0);
        check(inc.subtitle.equals("2 h 8 min left"), "movie subtitle: " + inc.subtitle);
        check(inc.deepLink.equals("stremio:///detail/movie/tt1375666/tt1375666"), "movie deep link: " + inc.deepLink);
        check(items.get(2).subtitle.equals("S1 E3 · 44 min left"), "season and episode from video_id: " + items.get(2).subtitle);
        check(Stremio.time("2026-10-24T12:00:00.000Z", 0) - Stremio.time("2026-10-24T11:00:00.000Z", 0) == 3600_000L, "ISO time parse");
        String req = Stremio.request("SECRET");
        check(req.contains("\"authKey\":\"SECRET\"") && req.contains("\"collection\":\"libraryItem\"") && req.contains("\"all\":true"), "request body: " + req.replace("SECRET", "***"));
        check(ContinueItem.openedAgo(0, 30_000).equals("Opened just now") && ContinueItem.openedAgo(0, 3 * 3600_000L).equals("Opened 3 h ago")
                && ContinueItem.openedAgo(0, 30 * 3600_000L).equals("Opened yesterday"), "opened-ago text");
    }

    static void sun() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        long noon = java.time.ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        long[] t = Sun.times(40.7128, -74.0060, noon);
        java.time.ZonedDateTime rise = java.time.Instant.ofEpochMilli(t[0]).atZone(java.time.ZoneId.of("America/New_York"));
        java.time.ZonedDateTime set = java.time.Instant.ofEpochMilli(t[1]).atZone(java.time.ZoneId.of("America/New_York"));
        check(Math.abs(rise.getHour() * 60 + rise.getMinute() - (5 * 60 + 25)) <= 4 && rise.getDayOfMonth() == 21, "NYC sunrise on June 21 ~05:25 (" + rise.toLocalTime() + ")");
        check(Math.abs(set.getHour() * 60 + set.getMinute() - (20 * 60 + 31)) <= 4 && set.getDayOfMonth() == 21, "NYC sunset on June 21 ~20:31 (" + set.toLocalTime() + ")");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        noon = java.time.ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
        t = Sun.times(28.61, 77.21, noon);
        java.time.ZonedDateTime r2 = java.time.Instant.ofEpochMilli(t[0]).atZone(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.ZonedDateTime s2 = java.time.Instant.ofEpochMilli(t[1]).atZone(java.time.ZoneId.of("Asia/Kolkata"));
        check(r2.getHour() == 6 && r2.getMinute() >= 20 && r2.getMinute() <= 35 && r2.getDayOfMonth() == 24, "Delhi sunrise Oct 24 ~06:26 (" + r2.toLocalTime() + ")");
        check(s2.getHour() == 17 && s2.getMinute() >= 35 && s2.getMinute() <= 50 && s2.getDayOfMonth() == 24, "Delhi sunset Oct 24 ~17:41 (" + s2.toLocalTime() + ")");
        long[] polar = Sun.times(78.2, 15.6, java.time.ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli());
        check(polar[0] == 0 && polar[1] == 0, "polar night: no sunrise or sunset (0, 0)");
    }
}
