package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * The parent's way back from a computer when the home PIN is forgotten, without wiping the settings:
 * `adb shell content call --uri content://io.freemytv.recovery --method remove_pin` (then press Home on the TV).
 * Only adb's shell (or root) may call it; apps on the TV can't. Runs in :home, the process that owns the settings.
 */
public class Recovery extends ContentProvider {
    private static final int SHELL_UID = 2000;   // Process.SHELL_UID

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid() % 100_000;   // UserHandle.getAppId: the same for every TV profile
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb");
        Bundle out = new Bundle();
        if ("remove_pin".equals(method)) {
            Prefs.setPin("");
            AppLog.w("home PIN removed from adb");
            out.putString("result", "PIN removed. Press Home on the TV.");
        } else if ("status".equals(method)) {
            out.putString("result", Prefs.hasPin() ? "PIN on" : "PIN off");
        } else {
            out.putString("result", "methods: remove_pin, status");
        }
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
