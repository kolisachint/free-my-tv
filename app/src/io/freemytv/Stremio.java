package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Continue in Stremio": what is in progress in the user's Stremio library (api.strem.io datastoreGet, with the auth
 * key from config.properties; the key is never logged). Refreshed at most every 5 minutes, cached on disk.
 */
final class Stremio {
    private Stremio() {}

    private static final String API = "https://api.strem.io/api/datastoreGet";
    private static final long EVERY = 5 * 60_000L, RETRY = 2 * 60_000L;
    /** How many items are kept from the library. */
    static final int KEEP = 6;

    private static volatile List<ContinueItem> items = Collections.emptyList();
    private static volatile long at, failedAt;
    private static volatile boolean fetching, loaded;

    static boolean hasKey() {
        return !Config.STREMIO_AUTH_KEY.trim().isEmpty();
    }

    /** The saved items (read once from disk, then kept in memory). */
    static List<ContinueItem> items() {
        if (!loaded) load();
        return hasKey() ? items : Collections.<ContinueItem>emptyList();
    }

    /** Asks Stremio again if the list is older than 5 minutes (background); `done` runs on the main thread when it changed. */
    static void refreshIfStale(Runnable done) {
        if (!hasKey() || fetching) return;
        long now = System.currentTimeMillis();
        if (now >= at && now - at < EVERY) return;
        if (failedAt != 0 && now >= failedAt && now - failedAt < RETRY) return;
        fetching = true;
        Work.io(() -> {
            try {
                String body = Net.postJson(API, request(Config.STREMIO_AUTH_KEY.trim()), "Stremio", null);
                List<ContinueItem> list = parse(new JSONObject(body), System.currentTimeMillis());
                items = Collections.unmodifiableList(list);
                at = System.currentTimeMillis();
                failedAt = 0;
                save(list);
                Work.MAIN.post(done);
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                // The message may name the host, never the key (it is in the body, not in any message).
                AppLog.w("stremio: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** The request body: all library items. */
    static String request(String authKey) throws org.json.JSONException {
        return new JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("ids", new JSONArray()).put("all", true).toString();
    }

    /**
     * Library items in progress, newest first (at most KEEP): not removed, with a watch position, not finished.
     * `root` is the API's answer: {"result": [libraryItem...]}. Pure: tools/TestData.java runs it with a fixture.
     */
    static List<ContinueItem> parse(JSONObject root, long now) {
        List<ContinueItem> out = new ArrayList<>();
        JSONArray result = root.optJSONArray("result");
        for (int i = 0; result != null && i < result.length(); i++) {
            JSONObject o = result.optJSONObject(i);
            if (o == null || o.optBoolean("removed")) continue;
            String type = o.optString("type");
            if (!type.equals("movie") && !type.equals("series")) continue;
            JSONObject state = o.optJSONObject("state");
            if (state == null) continue;
            long offset = state.optLong("timeOffset"), duration = state.optLong("duration");
            if (offset <= 0) continue;
            if (duration > 0 && offset >= duration * 0.95) continue;   // finished
            String id = o.optString("_id");
            if (id.isEmpty()) continue;
            ContinueItem c = new ContinueItem();
            c.title = o.optString("name");
            if (c.title.isEmpty()) continue;
            c.kind = type.equals("movie") ? "movie" : "tv";
            c.source = ContinueItem.SOURCE_STREMIO;
            c.sourceName = "Stremio";
            c.pkg = Services.STREMIO_PKG;
            c.imdbId = id.startsWith("tt") ? id : "";
            c.at = time(o.optString("_mtime"), now);
            c.remotePoster = o.optString("poster");
            if (c.imdbId.isEmpty()) {
                // Not an IMDb title (a Kitsu anime, a channel): the poster is all we have.
                c.remoteBackdrop = "";
            } else {
                c.remoteBackdrop = "https://images.metahub.space/background/medium/" + id + "/img";
                if (c.remotePoster.isEmpty()) c.remotePoster = "https://images.metahub.space/poster/medium/" + id + "/img";
            }
            c.progress = duration > 0 ? Math.max(0f, Math.min(1f, offset / (float) duration)) : 0f;
            String video = state.optString("video_id");
            int[] se = season(state, video);
            StringBuilder sub = new StringBuilder();
            if (type.equals("series") && se[0] > 0 && se[1] > 0) sub.append('S').append(se[0]).append(" E").append(se[1]).append(" · ");
            if (duration > 0) {
                long left = Math.max(1, (duration - offset + 59_999) / 60_000);
                sub.append(left >= 120 ? (left / 60) + " h " + (left % 60) + " min left" : left + " min left");
            } else {
                sub.append("Resume");
            }
            c.subtitle = sub.toString();
            c.deepLink = ContinueItem.stremioLink(type, id, type.equals("series") && !video.isEmpty() ? video : type.equals("movie") ? id : "");
            out.add(c);
        }
        Collections.sort(out, new Comparator<ContinueItem>() {
            @Override public int compare(ContinueItem a, ContinueItem b) { return Long.compare(b.at, a.at); }
        });
        while (out.size() > KEEP) out.remove(out.size() - 1);
        return out;
    }

    /** {season, episode} from the state, or from the video id "tt0944947:2:5"; 0 when unknown. */
    static int[] season(JSONObject state, String videoId) {
        int s = state.optInt("season"), e = state.optInt("episode");
        if ((s <= 0 || e <= 0) && videoId != null) {
            String[] p = videoId.split(":");
            if (p.length >= 3) {
                try {
                    s = Integer.parseInt(p[p.length - 2]);
                    e = Integer.parseInt(p[p.length - 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[] {s, e};
    }

    /** "2026-10-23T19:04:11.123Z" → epoch ms (UTC); `fallback` if it doesn't read. */
    static long time(String iso, long fallback) {
        if (iso == null || iso.length() < 19) return fallback;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return fallback;
        }
    }

    // MARK: - Disk (the card fields; the next start shows them before the network answers)

    private static SharedPreferences sp() {
        return Prefs.context().getSharedPreferences("stremio", Context.MODE_PRIVATE);
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(sp().getString("items", "[]"));
            List<ContinueItem> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                ContinueItem c = new ContinueItem();
                c.title = o.optString("t");
                c.kind = o.optString("k");
                c.source = ContinueItem.SOURCE_STREMIO;
                c.sourceName = "Stremio";
                c.pkg = Services.STREMIO_PKG;
                c.subtitle = o.optString("s");
                c.progress = (float) o.optDouble("p", 0);
                c.at = o.optLong("at");
                c.imdbId = o.optString("i");
                c.remoteBackdrop = o.optString("b");
                c.remotePoster = o.optString("po");
                c.deepLink = o.optString("d");
                list.add(c);
            }
            items = Collections.unmodifiableList(list);
            at = sp().getLong("at", 0);
        } catch (Exception ignored) {
        }
    }

    private static void save(List<ContinueItem> list) {
        try {
            JSONArray a = new JSONArray();
            for (ContinueItem c : list) {
                a.put(new JSONObject().put("t", c.title).put("k", c.kind).put("s", c.subtitle).put("p", c.progress).put("at", c.at)
                        .put("i", c.imdbId).put("b", c.remoteBackdrop).put("po", c.remotePoster).put("d", c.deepLink));
            }
            sp().edit().putString("items", a.toString()).putLong("at", at).apply();
        } catch (Exception ignored) {
        }
    }
}
