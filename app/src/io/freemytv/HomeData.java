package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where you are and when the sun is up: today's sunrise and sunset worked out on the TV from the latitude and
 * longitude (settings, first from config.properties), so the light design can darken at night. No network, no key.
 * (Weather and crypto prices are gone from the home screen.) City lookup for the settings screen is here too.
 */
final class HomeData {
    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData();
        return instance;
    }

    /** Today's sunrise and sunset in epoch ms; 0 when the sun doesn't rise or set here today (polar day or night). */
    volatile long sunrise, sunset;
    private volatile int day = -1;
    private volatile String place = "";

    private HomeData() {}

    /** The location changed in the settings: work today's times out again. */
    void settingsChanged() {
        day = -1;
        refreshIfStale();
    }

    /** Works out the times once a day (and after the location changes). Cheap: call it as often as you like. */
    void refreshIfStale() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int today = cal.get(java.util.Calendar.YEAR) * 400 + cal.get(java.util.Calendar.DAY_OF_YEAR);
        String here = Prefs.latitude() + "," + Prefs.longitude();
        if (today == day && here.equals(place)) return;
        long[] t = Sun.times(Sun.parse(Prefs.latitude(), 40.7128), Sun.parse(Prefs.longitude(), -74.0060), System.currentTimeMillis());
        sunrise = t[0];
        sunset = t[1];
        day = today;
        place = here;
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Without a location that works, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        refreshIfStale();
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }
}
