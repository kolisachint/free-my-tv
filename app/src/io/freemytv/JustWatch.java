package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a title lives inside each streaming app, from JustWatch's public GraphQL (no key). One request answers for
 * every app: our app key ("netflix", "prime", "appletv", "hotstar", "youtube") → the address that app opens on the
 * title's own page. Blocking: call it from Work.io only. Recs caches the answers.
 */
final class JustWatch {
    private JustWatch() {}

    private static final String ENDPOINT = "https://apis.justwatch.com/graphql";
    private static final String QUERY = "query S($q:String!,$c:Country!,$l:Language!){popularTitles(country:$c,first:10,"
            + "filter:{searchQuery:$q}){edges{node{objectType content(country:$c,language:$l){title originalReleaseYear "
            + "externalIds{tmdbId}} offers(country:$c,platform:WEB){monetizationType standardWebURL "
            + "package{clearName technicalName}}}}}}";
    /** Best first: the plan you pay for beats ads, which beats renting and buying. */
    private static final String[] MONETIZATION = {"FLATRATE", "FREE", "ADS", "RENT", "BUY"};

    /** JustWatch's package → our app key, or null for apps we don't open. */
    static String appKey(String technicalName) {
        switch (technicalName) {
            case "netflix": return "netflix";
            case "amazonprimevideo":
            case "amazonprimevideowithads":
            case "amazon":                      // Amazon Video (rent, buy) is Prime Video's app
            case "amazonappletvplus": return "prime";   // Apple TV sold as a channel inside Prime Video
            case "appletvplus":
            case "itunes": return "appletv";
            case "jiohotstar":
            case "hotstar": return "hotstar";
            case "youtube": return "youtube";
            default: return null;
        }
    }

    /**
     * The title's addresses by app key (empty when JustWatch doesn't know it). Throws on network or server errors,
     * which are not an answer: the caller must not cache them.
     */
    static Map<String, String> lookup(Title t, String country, String language) throws IOException {
        String lang = language == null || language.isEmpty() ? "en" : language.replaceAll("-.*", "").toLowerCase(Locale.ROOT);
        JSONObject answer = post(t.title, country, lang);
        if (answer.has("errors") && !lang.equals("en")) answer = post(t.title, country, "en");   // a language JustWatch lacks
        Map<String, String> out = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        JSONObject data = answer.optJSONObject("data");
        JSONObject popular = data == null ? null : data.optJSONObject("popularTitles");
        JSONArray edges = popular == null ? null : popular.optJSONArray("edges");
        if (edges == null) {
            if (answer.has("errors")) throw new IOException("JustWatch refused the query");
            return out;
        }
        String wantType = t.isMovie() ? "MOVIE" : "SHOW";
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i) == null ? null : edges.optJSONObject(i).optJSONObject("node");
            if (node == null || !wantType.equals(node.optString("objectType"))) continue;
            JSONObject content = node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids == null || !String.valueOf(t.tmdbId).equals(ids.optString("tmdbId"))) continue;
            JSONArray offers = node.optJSONArray("offers");
            for (int j = 0; offers != null && j < offers.length(); j++) {
                JSONObject o = offers.optJSONObject(j);
                JSONObject pack = o == null ? null : o.optJSONObject("package");
                if (pack == null) continue;
                String technical = pack.optString("technicalName");
                String key = appKey(technical);
                String url = o.optString("standardWebURL");
                if (key == null || !url.startsWith("https://")) continue;
                url = tidy(key, url, content.optString("title"));
                if (url == null) continue;
                int r = monetization(o.optString("monetizationType")) + (technical.equals("amazonappletvplus") ? 10 : 0);
                Integer have = rank.get(key);
                if (have == null || r < have) {
                    rank.put(key, r);
                    out.put(key, url);
                }
            }
            break;
        }
        return out;
    }

    private static int monetization(String type) {
        for (int i = 0; i < MONETIZATION.length; i++) if (MONETIZATION[i].equals(type)) return i;
        return MONETIZATION.length;
    }

    /**
     * What the app opens: JustWatch's addresses carry tracking, episodes and long slugs, and the apps want the title.
     * Apple TV: the show (an episode link has `showId`) or the movie, without the tracking query. JioHotstar:
     * `hotstar.com/in/<contentId>` (the form verified on a TV). Null when the address is not usable.
     */
    static String tidy(String key, String url, String title) {
        try {
            if (key.equals("appletv")) {
                java.net.URI u = new java.net.URI(url);
                String[] path = u.getPath().split("/");     // "", "in", "episode", "slug", "umc.cmc.…"
                if (path.length < 5) return url;
                String base = "https://tv.apple.com/" + path[1] + "/";
                if (path[2].equals("episode")) {
                    String show = param(u.getRawQuery(), "showId");
                    return show.isEmpty() ? null : base + "show/" + slug(title) + "/" + show;
                }
                return base + path[2] + "/" + path[3] + "/" + path[4];
            }
            if (key.equals("hotstar")) {
                String[] path = new java.net.URI(url).getPath().split("/");
                String last = path.length > 0 ? path[path.length - 1] : "";
                return last.matches("\\d+") ? "https://www.hotstar.com/in/" + last : url;
            }
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return url;
        }
        return url;
    }

    private static String param(String query, String name) {
        if (query == null) return "";
        for (String kv : query.split("&")) if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        return "";
    }

    private static String slug(String title) {
        String s = title == null ? "" : java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "").toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "show" : s;
    }

    private static JSONObject post(String title, String country, String language) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setConnectTimeout(4000);   // Watch waits on this: short, then the app's home
        c.setReadTimeout(4000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try {
            JSONObject body = new JSONObject().put("query", QUERY).put("variables",
                    new JSONObject().put("q", title).put("c", country).put("l", language));
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while (in != null && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            // GraphQL answers a refused query (an unknown language, say) with HTTP 200 or 400 and an "errors" list.
            if (code != 200 && code != 400) throw new IOException("JustWatch returned HTTP " + code);
            return new JSONObject(buf.toString("UTF-8"));
        } catch (org.json.JSONException e) {
            throw new IOException("JustWatch: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }
}
