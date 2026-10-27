package io.freemytv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import java.net.URLEncoder;

/**
 * Opens titles, trailers and settings. Every call tries the deep link first and falls back to the app's own launch
 * screen (or the browser). Safe to call from the main thread; the title's address (JustWatch, then Watchmode if
 * keyed) is looked up in the background first and cached.
 */
final class Launch {
    private Launch() {}

    interface Done { void onDone(boolean opened); }

    /**
     * "Watch": opens `t` in provider `p` (null = the first installed streaming app, else Stremio). Records it in History.
     * The title's own page opens when we know its address (JustWatch, Watchmode, Stremio); else the app's home.
     * `options` (ActivityOptions.toBundle(), may be null) and `done` (main thread, may be null).
     */
    static void watch(Context c, Title t, Title.Provider p, Bundle options, Done done) {
        Context app = c.getApplicationContext();
        String key, pkg;
        if (p != null) {
            key = p.appKey;
            pkg = p.pkg;
        } else if (!t.installedStreams().isEmpty()) {
            key = t.installedStreams().get(0).appKey;
            pkg = t.installedStreams().get(0).pkg;
        } else {
            key = Services.STREMIO;
            pkg = Services.STREMIO_PKG;
        }
        final String appKey = key == null ? "" : key, appPkg = pkg;
        if (appPkg == null || appPkg.isEmpty()) {
            if (done != null) done.onDone(false);
            return;
        }
        if (appKey.equals(Services.STREMIO)) {
            boolean ok = openStremio(app, t, options);
            if (ok) History.record(t, appKey);
            if (done != null) done.onDone(ok);
            return;
        }
        Work.io(() -> {
            String link = Recs.get(app).watchLink(t, appKey, appPkg);   // cached; the first time, Watchmode (if configured)
            Work.MAIN.post(() -> {
                boolean ok = viewOrLaunch(app, link, appPkg, t.title, options);
                if (ok) History.record(t, appKey, link);
                if (done != null) done.onDone(ok);
            });
        });
    }

    /** Opens the title's page in Stremio (stremio:///detail/...), else Stremio itself. Needs the IMDb id. */
    static boolean openStremio(Context c, Title t, Bundle options) {
        String link = t.imdbId.isEmpty() ? "" : ContinueItem.stremioLink(t.stremioKind(), t.imdbId, "");
        return viewOrLaunch(c, link, Services.STREMIO_PKG, t.title, options);
    }

    /** Resumes a Continue card: Stremio's deep link, or the title in the app it was opened in (the app's home if unknown). */
    static boolean open(Context c, ContinueItem item, Bundle options) {
        if (item.pkg.isEmpty()) return false;
        Context app = c.getApplicationContext();
        if (!item.deepLink.isEmpty() || item.tmdbId == 0 || ContinueItem.SOURCE_STREMIO.equals(item.source)) {
            return viewOrLaunch(app, item.deepLink, item.pkg, item.title, options);
        }
        // Made before its page was kept (or the link wasn't known then): look it up now, then open it.
        Title t = new Title();
        t.tmdbId = item.tmdbId;
        t.kind = item.kind;
        t.title = item.title;
        Work.io(() -> {
            String link = Recs.get(app).watchLink(t, item.source, item.pkg);   // cached; Watchmode only if needed
            Work.MAIN.post(() -> {
                if (link != null) History.setLink(t.tmdbId, t.kind, link);
                viewOrLaunch(app, link, item.pkg, item.title, options);
            });
        });
        return true;
    }

    /** The trailer: the YouTube TV app on the video, else YouTube's search for "<title> <year> trailer". */
    static boolean trailer(Context c, Title t, Bundle options) {
        Context app = c.getApplicationContext();
        String id = t.trailerYouTubeId;
        if (!id.isEmpty()) {
            // https first: YouTube for TV takes vnd.youtube: but opens its home with it (Sony, Android 12).
            if (view(app, "https://www.youtube.com/watch?v=" + id, Services.YOUTUBE_PKG, options)) return true;
            if (view(app, "vnd.youtube:" + id, Services.YOUTUBE_PKG, options)) return true;
            return view(app, "https://www.youtube.com/watch?v=" + id, null, options);
        }
        String q;
        try {
            q = URLEncoder.encode((t.title + " " + (t.year > 0 ? t.year + " " : "") + "trailer").trim(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
        String url = "https://www.youtube.com/results?search_query=" + q;
        return view(app, url, Services.YOUTUBE_PKG, options) || view(app, url, null, options);
    }

    /** The TV's Wi-Fi settings (Settings.ACTION_WIFI_SETTINGS), else the Settings app. */
    static boolean wifiSettings(Context c, Bundle options) {
        Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(c, i, options)) return true;
        return start(c, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
    }

    /** Opens an installed app by package (its TV launch screen). */
    static boolean app(Context c, String pkg, Bundle options) {
        return launchApp(c.getApplicationContext(), pkg, options);
    }

    // MARK: - Plumbing

    /** `link` in `pkg` if there is one and the app takes it, else the app's own launch screen. */
    private static boolean viewOrLaunch(Context c, String link, String pkg, Bundle options) {
        return viewOrLaunch(c, link, pkg, "", options);
    }

    /** The app's own search page for the title, where one is known to open (verified on device). Voice search uses it too. */
    static String appSearchUrl(String pkg, String title) {
        String path;
        if (Services.YOUTUBE_PKG.equals(pkg)) {
            path = "https://www.youtube.com/results?search_query=";
        } else if ("in.startv.hotstar".equals(pkg)) {
            path = "https://www.hotstar.com/in/search?q=";   // JioHotstar: opens, but without results
        } else {
            return null;
        }
        try {
            return path + java.net.URLEncoder.encode(title, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * The title's page when its address is known (JustWatch, Watchmode, Stremio), else the app's own search where one
     * works (YouTube), else the app's home. Never leaves the button dead: the home opens whenever the app is there.
     */
    private static boolean viewOrLaunch(Context c, String link, String pkg, String title, Bundle options) {
        if (link != null && !link.isEmpty()) {
            if (openTitle(c, link, pkg, options)) {
                AppLog.w("opened " + pkg + " on the title page");
                return true;
            }
            AppLog.w("title page refused by " + pkg + ", falling back");
        }
        // Only YouTube's search shows results; JioHotstar's search page opens empty, so there the app's home is better.
        String url = title != null && !title.isEmpty() && Services.YOUTUBE_PKG.equals(pkg) ? appSearchUrl(pkg, title) : null;
        if (url != null && view(c, url, pkg, options)) return true;
        if (url != null && searchInApp(c, pkg, title, options)) return true;
        boolean ok = launchApp(c, pkg, options);
        AppLog.w((ok ? "opened " : "couldn't open ") + pkg + " home");
        return ok;
    }

    private static final String NETFLIX_PKG = "com.netflix.ninja";

    /** The title's address in the app, built the way that app takes it. */
    private static boolean openTitle(Context c, String link, String pkg, Bundle options) {
        if (pkg != null && pkg.startsWith("com.netflix.")) return openNetflix(c, link, pkg, options);
        return view(c, link, pkg, options);
    }

    /**
     * Netflix on TV: its MainActivity takes https://www.netflix.com/title/<id> (and /watch/<id>) with the extra
     * source=30 that JustWatch sends; the nflx:// scheme is declared without BROWSABLE, so view() can't reach it.
     */
    private static boolean openNetflix(Context c, String link, String pkg, Bundle options) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("netflix\\.com/(?:[a-z-]+/)?(?:title|watch)/(\\d+)").matcher(link);
        if (!m.find()) return view(c, link, pkg, options);
        String id = m.group(1);
        String[] tries = {"https://www.netflix.com/title/" + id, "nflx://www.netflix.com/title/" + id, "https://www.netflix.com/watch/" + id};
        for (String url : tries) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg == null ? NETFLIX_PKG : pkg)
                    .putExtra("source", "30").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (url.startsWith("https:")) i.addCategory(Intent.CATEGORY_BROWSABLE);
            if (start(c, i, options)) return true;
        }
        return false;
    }

    private static boolean view(Context c, String uri, String pkg, Bundle options) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        if (start(c, i, options)) return true;
        if (uri.startsWith("http://") || uri.startsWith("https://")) return false;
        // A custom scheme (stremio://, nflx://…) may be declared without BROWSABLE: try again without it.
        Intent plain = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) plain.setPackage(pkg);
        return start(c, plain, options);
    }

    /** The app's own search for the title (Netflix, Prime Video, YouTube…): false when it takes no search. */
    private static boolean searchInApp(Context c, String pkg, String title, Bundle options) {
        Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(c, i, options);
    }

    private static boolean launchApp(Context c, String pkg, Bundle options) {
        Intent i = HomeApps.launchIntent(c.getPackageManager(), pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(c, i, options);
    }

    private static boolean start(Context c, Intent i, Bundle options) {
        try {
            c.startActivity(i, options);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            // The package when scoped, else what was asked: tells a refused deep link from a refused search.
            String what = i.getData() != null ? String.valueOf(i.getData()) : String.valueOf(i.getAction());
            AppLog.w("couldn't open " + (i.getPackage() != null ? i.getPackage() + " " : "") + what
                    + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
