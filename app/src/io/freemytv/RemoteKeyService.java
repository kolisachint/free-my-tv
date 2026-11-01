package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 *
 * With a home PIN it also keeps the lock: until the PIN is entered, keys that leave the PIN screen stop here, and any
 * other app that comes up anyway (the Assistant's mic, the dashboard, a TV menu) is sent back to the home screen, which
 * shows the PIN screen. Only while our home screen is the TV's home: otherwise it does nothing (never a loop).
 */
public class RemoteKeyService extends AccessibilityService {
    /** Windows that may show while locked: the system's own, the Assistant's overlay (not the apps it opens). */
    private static final String[] ALLOWED = {
            "android", "com.android.systemui", "com.google.android.katniss", "com.google.android.googlequicksearchbox",
    };
    /** Allowed only for an app's permission question (the same app also holds the "Default home app" screen). */
    private static final String[] PERMISSIONS = {
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long since;          // elapsedRealtime: a PIN entered before this doesn't count (service start, screen off)
    private long lastKick;
    private String lastPkg;      // the app whose window came up last, if not allowed while locked
    private String burstPkg;     // loop breaker: the app home keeps landing on
    private long burstAt, pausedUntil;
    private int burst;
    private boolean home;        // our home screen is the TV's home (checked every 30 s)
    private long homeAt = -1_000_000L;   // long ago (not MIN_VALUE: now - MIN_VALUE overflows)
    private String keyboard, dream, services;
    private long settingsAt = -1_000_000L;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            since = SystemClock.elapsedRealtime();   // the session ends: the next one needs the PIN again
            // Back to the home screen now, so the TV wakes on the PIN screen and not in the app left open.
            if (Prefs.pinLocked() && ownsHome()) performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };

    /** Looks again once a debounce or a pause is over (an app that came up meanwhile sends no new event). */
    private final Runnable recheck = this::enforce;

    @Override protected void onServiceConnected() {
        since = SystemClock.elapsedRealtime();
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(recheck);
        try {
            unregisterReceiver(screenOff);
        } catch (RuntimeException ignored) {
        }
        return super.onUnbind(intent);
    }

    /** A PIN is set, wasn't entered since the lock last showed, and our home screen is the one that shows it. */
    private boolean locked() {
        return Prefs.pinLocked() && !Prefs.sessionOpen(since) && ownsHome();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        try {
            boolean locked = locked();
            // An app is up while locked (one the watchdog missed): any key sends it away instead of reaching it.
            if (locked && lastPkg != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) enforce();
                if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                        && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE) return true;
            }
            return RemoteKeys.onKey(this, event, locked);
        } catch (RuntimeException e) {
            AppLog.w("remote key: " + e);
            return false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
            String pkg = event.getPackageName().toString();
            CharSequence cls = event.getClassName();
            if (pkg.equals(getPackageName())) {
                lastPkg = null;
                return;
            }
            if (allowed(pkg, cls == null ? "" : cls.toString())) return;   // an overlay: the app under it was seen already
            lastPkg = pkg;
            if (Prefs.pinLocked()) enforce();   // one file check per window
        } catch (RuntimeException e) {
            AppLog.w("lock: " + e);
        }
    }

    /** While locked, sends the app that came up last (lastPkg) away: home, which shows the PIN screen. */
    private void enforce() {
        String pkg = lastPkg;
        if (pkg == null || !locked()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastKick < 1_500) {
            reschedule(lastKick + 1_500 - now);
            return;
        }
        // Loop breaker, per app: if home keeps landing on the same one (home lost to it), stop for a minute.
        if (!pkg.equals(burstPkg) || now - burstAt > 10_000) {
            burstPkg = pkg;
            burstAt = now;
            burst = 0;
        } else if (now < pausedUntil) {
            reschedule(pausedUntil - now);
            return;
        }
        if (++burst > 4) {
            pausedUntil = now + 60_000;
            AppLog.w("lock: " + pkg + " keeps coming back, pausing a minute");
            reschedule(60_000);
            return;
        }
        lastKick = now;
        AppLog.w("lock: " + pkg + " -> home");
        performGlobalAction(GLOBAL_ACTION_HOME);
        // Once more in 2 s in case home didn't take (our home sends no new window event when it was under the app).
        if (burst == 1) reschedule(2_000);
        else lastPkg = null;
    }

    private void reschedule(long delay) {
        handler.removeCallbacks(recheck);
        handler.postDelayed(recheck, delay + 50);
    }

    private boolean allowed(String pkg, String cls) {
        for (String p : ALLOWED) if (p.equals(pkg)) return true;
        for (String p : PERMISSIONS) if (p.equals(pkg)) return cls.endsWith("GrantPermissionsActivity");
        long now = SystemClock.elapsedRealtime();
        if (now - settingsAt > 60_000) {
            settingsAt = now;
            keyboard = packageOf(Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
            dream = Settings.Secure.getString(getContentResolver(), "screensaver_components");
            services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        }
        if (pkg.equals(keyboard)) return true;
        // The screensaver set in the TV's settings, and accessibility services like TalkBack ("pkg/class", listed).
        return listed(dream, pkg, ',') || listed(services, pkg, ':');
    }

    private static boolean listed(String list, String pkg, char sep) {
        return list != null && (list.startsWith(pkg + "/") || list.contains(sep + pkg + "/"));
    }

    private static String packageOf(String component) {
        ComponentName name = component == null ? null : ComponentName.unflattenFromString(component);
        return name == null ? null : name.getPackageName();
    }

    /** Whether Home opens our home screen (so sending the TV home shows the PIN screen, and never loops). */
    private boolean ownsHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - homeAt > 30_000) {
            homeAt = now;
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            home = r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        }
        return home;
    }

    @Override public void onInterrupt() {}
}
