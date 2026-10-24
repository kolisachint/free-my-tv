package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Network status for the home's Wi-Fi button: connected, the network's name, and signal level 0..4. The name needs
 * ACCESS_FINE_LOCATION on Android 10+ (a launcher can't ask for it on a TV, but `adb shell pm grant io.freemytv
 * android.permission.ACCESS_FINE_LOCATION` gives it); without it the label is "Wi-Fi" (or "Ethernet").
 */
final class Wifi {
    static final class State {
        boolean connected;      // the TV has a network with internet access
        boolean wifi;           // that network is Wi-Fi (else Ethernet or other)
        String label = "No internet";   // network name, "Wi-Fi", "Ethernet" or "No internet"
        int level;              // Wi-Fi signal 0..4 (0 when not Wi-Fi or no signal)

        @Override public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            State s = (State) o;
            return connected == s.connected && wifi == s.wifi && level == s.level && label.equals(s.label);
        }

        @Override public int hashCode() { return label.hashCode() * 31 + level; }
    }

    interface Listener { void onWifi(State state); }

    private final Context context;
    private final ConnectivityManager cm;
    private final WifiManager wm;
    private Listener listener;
    private State last = new State();
    private ConnectivityManager.NetworkCallback callback;

    Wifi(Context c) {
        context = c.getApplicationContext();
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    /** The status now (a few system calls; fine on the main thread). */
    State state() {
        State s = new State();
        try {
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return s;
            s.connected = true;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                s.wifi = true;
                s.label = "Wi-Fi";
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>") && !ssid.equals("0x")) {
                        s.label = ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
                    }
                    s.level = Math.max(0, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5)));
                }
            } else {
                s.label = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Network";
            }
        } catch (RuntimeException e) {
            AppLog.w("wifi: " + e.getClass().getSimpleName());   // SecurityException on some firmware: show "No internet" rather than crash
        }
        return s;
    }

    /** Starts listening: `l.onWifi` (main thread) is called now and whenever the status changes. Call stop() in onPause. */
    void start(Listener l) {
        stop();
        listener = l;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { changed(); }
            @Override public void onLost(Network n) { changed(); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { changed(); }
        };
        try {
            cm.registerDefaultNetworkCallback(callback, Work.MAIN);
        } catch (RuntimeException e) {
            AppLog.w("wifi: can't listen: " + e.getClass().getSimpleName());
            callback = null;
        }
        last = state();
        l.onWifi(last);
    }

    void stop() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
            callback = null;
        }
        listener = null;
    }

    private void changed() {
        Listener l = listener;
        if (l == null) return;
        State s = state();
        if (s.equals(last)) return;
        last = s;
        l.onWifi(s);
    }
}
