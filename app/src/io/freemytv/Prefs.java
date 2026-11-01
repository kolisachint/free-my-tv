package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK, KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION,
                    KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * Reads the file again if another process changed it. On Android 11 and 12 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        String before = language();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before)) Ui.applyLanguage();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Home PIN (whole-home lock so kids can't watch without it)

    /** The home PIN: "" = off, otherwise 4 digits. */
    static String pin() { return sp().getString("pin", ""); }
    static boolean hasPin() { return !pin().isEmpty(); }
    static void setPin(String pin) {
        put("pin", pin == null ? "" : pin);
        // An open session stays open: the parent changing the PIN in the settings already entered it.
        // The key service (:keys) reads the lock from remote-keys.txt, not SharedPreferences.
        if (!writePinLocked()) AppLog.w("PIN: could not tell the key service");
        if (hasPin()) HomeMode.ensureRemoteService(app, 0);   // the lock needs it (it blocks keys and apps)
    }

    // Wrong PINs in a row (this process: the home screen and its settings). From the 5th, a pause before the next
    // try: 30 s, doubling up to 5 min. Never a lockout.
    private static int pinFails;
    private static long pinWaitUntil;

    /** Milliseconds before the next PIN try is taken (0 = now). */
    static long pinWait() {
        return Math.max(0, pinWaitUntil - SystemClock.elapsedRealtime());
    }

    /** Checks a PIN entry and counts the wrong ones. False while a pause runs, whatever was entered. */
    static boolean checkPin(String entered) {
        if (pinWait() > 0) return false;
        if (hasPin() && pin().equals(entered)) {
            pinFails = 0;
            return true;
        }
        if (++pinFails >= 5) {
            pinWaitUntil = SystemClock.elapsedRealtime() + Math.min(300_000L, 30_000L << Math.min(pinFails - 5, 4));
        }
        return false;
    }

    // The unlocked session: from the right PIN (home or settings) until the lock screen shows again. The key service
    // reads it from remote-keys.txt ("session=<boot>:<elapsedRealtime>", so a clock change or a reboot can't move it)
    // and also ends it on screen off and on its own restart.
    private static volatile long sessionOwn;
    private static int bootCount = Integer.MIN_VALUE;

    /** This boot's number (Settings.Global.BOOT_COUNT), or -1. */
    static int bootCount() {
        if (bootCount == Integer.MIN_VALUE) {
            bootCount = android.provider.Settings.Global.getInt(app.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
        }
        return bootCount;
    }

    /** The right PIN was entered: the remote's buttons and other apps work until the lock shows again. */
    static void startSession() {
        sessionOwn = SystemClock.elapsedRealtime();
        writePinLocked();
    }

    /** The lock screen is up again. */
    static void endSession() {
        // Also a session left in the file by a home process the system killed meanwhile (this one never started it).
        synchronized (keyApps) {
            keyApp(KEYS[0]);
            if (sessionOwn == 0 && keysSession == 0) return;
        }
        sessionOwn = 0;
        writePinLocked();
    }

    /** In this process (:home): whether the PIN was entered since the lock screen last showed. */
    static boolean sessionOwnOpen() { return sessionOwn != 0; }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Photos

    /** Your photos (Photos, sent from a computer) on the screensaver and behind the home screen, once imported. */
    static boolean photos() { return sp().getBoolean("photos", true); }
    static void setPhotos(boolean on) { put("photos", on); }

    // MARK: - Recent searches (the home's search screen): the last 5, newest first. No `version` bump: nothing redraws for it.

    static List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        if (app == null) return out;
        for (String q : sp().getString("recentSearches", "").split("\n")) if (!q.isEmpty()) out.add(q);
        return out;
    }

    static void addRecentSearch(String q) {
        q = q == null ? "" : q.replaceAll("\\s+", " ").trim();
        if (q.length() < 2 || app == null) return;
        List<String> list = recentSearches();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).equalsIgnoreCase(q)) list.remove(i);
        list.add(0, q);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, list.size()); i++) sb.append(i == 0 ? "" : "\n").append(list.get(i));
        sp().edit().putString("recentSearches", sb.toString()).apply();
    }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static boolean keysPin;   // the home PIN lock is on (remote-keys.txt: pin=1)
    private static long keysStamp = Long.MIN_VALUE, keysReadAt;
    private static long keysSession;   // when the right PIN was last entered (remote-keys.txt: session=), 0 = locked
    private static int keysSessionBoot = Integer.MIN_VALUE;   // the boot it was entered in

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            // Also read again after a second: two writes within the file system's time step can keep the same stamp
            // (pin=1 and pin=0 have the same length).
            long now = SystemClock.elapsedRealtime();
            if (stamp != keysStamp || now - keysReadAt > 1000) {
                keysStamp = stamp;
                keysReadAt = now;
                java.util.Arrays.fill(keyApps, null);
                keysPin = false;
                keysSession = 0;
                keysSessionBoot = Integer.MIN_VALUE;
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.startsWith("pin=")) keysPin = line.equals("pin=1");
                            else if (line.startsWith("session=")) {
                                String[] s = line.substring(8).split(":");
                                keysSessionBoot = s.length == 2 ? (int) parseLong(s[0]) : Integer.MIN_VALUE;
                                keysSession = s.length == 2 ? parseLong(s[1]) : 0;
                            }
                            else for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** Whether a home PIN is set (for the key service, which can't use SharedPreferences). */
    static boolean pinLocked() {
        synchronized (keyApps) {
            keyApp(KEYS[0]);   // re-reads remote-keys.txt when it changed (synchronized is reentrant)
            return keysPin;
        }
    }

    /** For the key service: whether the right PIN was entered after since (elapsedRealtime, this boot), lock not back. */
    static boolean sessionOpen(long since) {
        synchronized (keyApps) {
            keyApp(KEYS[0]);
            return keysSession != 0 && keysSessionBoot == bootCount() && keysSession > since
                    && keysSession <= SystemClock.elapsedRealtime();
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        synchronized (keyApps) {
            StringBuilder sb = new StringBuilder();
            for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
            appendPin(sb);
            if (writeKeys(sb.toString())) version++;
        }
    }

    /** Rewrites remote-keys.txt with the current PIN flag (the key service reads it, not SharedPreferences). */
    private static boolean writePinLocked() {
        synchronized (keyApps) {   // the UI thread and Recovery's binder thread both write (one temp file per process)
            StringBuilder sb = new StringBuilder();
            for (int key : KEYS) sb.append(key).append('=').append(keyApp(key)).append('\n');
            appendPin(sb);
            return writeKeys(sb.toString());
        }
    }

    /** The PIN flag and session lines for remote-keys.txt. */
    private static void appendPin(StringBuilder sb) {
        sb.append("pin=").append(hasPin() ? '1' : '0').append('\n');
        long s = sessionOwn;
        sb.append("session=").append(s == 0 ? "0" : bootCount() + ":" + s).append('\n');
    }
}
