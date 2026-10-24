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
 * screen (or the browser). Safe to call from the main thread; a Watchmode lookup (watchmode_key) happens in the
 * background first and the result is cached.
 */
final class Launch {
    private Launch() {}

    interface Done { void onDone(boolean opened); }

    /**
     * "Watch": opens `t` in provider `p` (null = the first installed streaming app, else Stremio). Records it in History.
     * The title's own page opens when we know its address (Watchmode link, Stremio); else the app's home.
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
                boolean ok = viewOrLaunch(app, link, appPkg, options);
                if (ok) History.record(t, appKey);
                if (done != null) done.onDone(ok);
            });
        });
    }

    /** Opens the title's page in Stremio (stremio:///detail/...), else Stremio itself. Needs the IMDb id. */
    static boolean openStremio(Context c, Title t, Bundle options) {
        String link = t.imdbId.isEmpty() ? "" : ContinueItem.stremioLink(t.stremioKind(), t.imdbId, "");
        return viewOrLaunch(c, link, Services.STREMIO_PKG, options);
    }

    /** Resumes a Continue card: Stremio's deep link, or the title in the app it was opened in (the app's home if unknown). */
    static boolean open(Context c, ContinueItem item, Bundle options) {
        if (item.pkg.isEmpty()) return false;
        return viewOrLaunch(c.getApplicationContext(), item.deepLink, item.pkg, options);
    }

    /** The trailer: the YouTube TV app on the video, else YouTube's search for "<title> <year> trailer". */
    static boolean trailer(Context c, Title t, Bundle options) {
        Context app = c.getApplicationContext();
        String id = t.trailerYouTubeId;
        if (!id.isEmpty()) {
            if (view(app, "vnd.youtube:" + id, Services.YOUTUBE_PKG, options)) return true;
            if (view(app, "https://www.youtube.com/watch?v=" + id, Services.YOUTUBE_PKG, options)) return true;
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

    /** `link` in `pkg` if there is one and the app takes it, else the app's launch screen. */
    private static boolean viewOrLaunch(Context c, String link, String pkg, Bundle options) {
        if (link != null && !link.isEmpty() && view(c, link, pkg, options)) return true;
        return launchApp(c, pkg, options);
    }

    private static boolean view(Context c, String uri, String pkg, Bundle options) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
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
            AppLog.w("couldn't open " + (i.getPackage() != null ? i.getPackage() : i.getAction()) + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
