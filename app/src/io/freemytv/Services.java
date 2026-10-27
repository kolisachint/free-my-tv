package io.freemytv;

import android.content.Context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The streaming apps we know: TMDB's provider names → TV app (key, package), installed or not, and hide_services. */
final class Services {
    private Services() {}

    static final String STREMIO = "stremio", STREMIO_PKG = "com.stremio.one";
    static final String YOUTUBE_PKG = "com.google.android.youtube.tv";

    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    static final String[][] TABLE = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };

    /**
     * TMDB's rent and buy stores: provider id → the app that sells the title (key, package). Only for the Rent and Buy
     * lines of the details sheet; never a "Watch on" chip (those read streaming providers only).
     */
    static final String[][] STORES = {
        {"2", "appletv", "com.apple.atve.androidtv.appletv"},
        {"2", "appletv", "com.apple.atve.sony.appletv"},
        {"2", "appletv", "com.apple.atve.amazon.appletv"},
        {"10", "prime", "com.amazon.amazonvideo.livingroom"},   // Amazon Video
        {"10", "prime", "com.amazon.firebat"},
        {"10", "prime", "com.amazon.avod"},
        {"192", "youtube", YOUTUBE_PKG},                        // YouTube (rent or buy)
    };

    static final Map<String, String> NAME = new HashMap<>();
    static {
        NAME.put("netflix", "Netflix");
        NAME.put("prime", "Prime Video");
        NAME.put("disney", "Disney+");
        NAME.put("hulu", "Hulu");
        NAME.put("paramount", "Paramount+");
        NAME.put("peacock", "Peacock");
        NAME.put("globoplay", "Globoplay");
        NAME.put("max", "Max");
        NAME.put("appletv", "Apple TV");
        NAME.put("hotstar", "JioHotstar");
        NAME.put("okko", "Okko");
        NAME.put("crunchyroll", "Crunchyroll");
        NAME.put("oneplay", "Oneplay");
        NAME.put(STREMIO, "Stremio");
        NAME.put("youtube", "YouTube");
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** Installed answers, filled once per refresh and shared by every title (the stamp scan below refreshes them). */
    private static final Map<String, Boolean> INSTALLED = new HashMap<>();

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    static boolean installed(Context c, String pkg) {
        synchronized (INSTALLED) {
            Boolean b = INSTALLED.get(pkg);
            if (b == null) {
                b = check(c, pkg);
                INSTALLED.put(pkg, b);
            }
            return b;
        }
    }

    /** One PackageManager hit, hiding nothing: the HIDDEN filter above applies. */
    private static boolean check(Context c, String pkg) {
        if (!HIDDEN.isEmpty()) {
            for (String[] s : TABLE) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
            for (String[] s : STORES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
            if (pkg.equals(STREMIO_PKG) && HIDDEN.contains(STREMIO)) return false;
        }
        return HomeApps.launchIntent(c.getPackageManager(), pkg) != null;
    }

    static boolean stremioInstalled(Context c) {
        return installed(c, STREMIO_PKG);
    }

    /** The TABLE row for a TMDB provider name: the installed one first, when a service has more than one package. */
    static String[] match(Context c, String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : TABLE) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(c, s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** The STORES row for a TMDB provider id: the one whose app is on the TV, else the first. Null for other ids. */
    static String[] store(Context c, int providerId) {
        String id = String.valueOf(providerId);
        String[] first = null;
        for (String[] s : STORES) {
            if (!s[0].equals(id)) continue;
            if (installed(c, s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** The row for a provider line saved by name only: a streaming service, or a store ("Apple TV Store" 2, "Amazon Video" 10, "YouTube" 192). */
    static String[] byName(Context c, String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        int id = n.equals("apple tv store") ? 2 : n.equals("amazon video") ? 10 : n.equals("youtube") ? 192 : 0;
        if (id != 0) return store(c, id);
        return null;
    }

    /** The package of an installed app for the service key (any of its packages: Sony's Apple TV, JioHotstar...), else null. */
    static String installedPkg(Context c, String appKey) {
        if (appKey == null || appKey.isEmpty()) return null;
        if (appKey.equals(STREMIO)) return stremioInstalled(c) ? STREMIO_PKG : null;
        for (String[][] table : new String[][][] {TABLE, STORES}) {
            for (String[] s : table) if (s[1].equals(appKey) && installed(c, s[2])) return s[2];
        }
        return null;
    }

    /** Key of the service for a package ("com.netflix.ninja" → "netflix"), or null. */
    static String keyOf(String pkg) {
        if (STREMIO_PKG.equals(pkg)) return STREMIO;
        for (String[] s : TABLE) if (s[2].equals(pkg)) return s[1];
        return null;
    }

    /** A stable string for "which known apps are installed and not hidden" (rows refetch when it changes). */
    static long installedStamp(Context c) {
        // Fresh PackageManager answers (never the snapshot above): this scan also refreshes it for the titles.
        long stamp = 7;
        for (String[] s : TABLE) stamp = stamp * 31L + (remember(c, s[2]) ? s[2].hashCode() : 0);
        for (String[] s : STORES) stamp = stamp * 31L + (remember(c, s[2]) ? s[2].hashCode() : 0);
        return stamp * 31L + (remember(c, STREMIO_PKG) ? 1 : 0);
    }

    /** Fresh answer, remembered for the titles coming next. */
    private static boolean remember(Context c, String pkg) {
        boolean b = check(c, pkg);
        synchronized (INSTALLED) {
            INSTALLED.put(pkg, b);
        }
        return b;
    }
}
