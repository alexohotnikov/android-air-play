package dev.okhotnikov.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.security.SecureRandom;

/** The receiver's settings and identity. */
final class Prefs {

    private static final String FILE = "airplay";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The name the TV shows in its own settings and to Chromecast senders, as it came from the factory. */
    static String factoryName(Context c) {
        String n = Settings.Global.getString(c.getContentResolver(), "device_name");
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n.trim();
    }

    /** The name in Screen Mirroring: the owner's, or the factory name. */
    static String name(Context c) {
        String n = prefs(c).getString("name", "");
        return n.trim().isEmpty() ? factoryName(c) : n.trim();
    }

    /** Empty goes back to the factory name. */
    static void setName(Context c, String name) {
        String n = name == null ? "" : name.trim();
        prefs(c).edit().putString("name", n.equals(factoryName(c)) ? "" : n).apply();
    }

    /** On: the receiver runs from boot and after the app closes. Off: only while the app is open. */
    static boolean background(Context c) {
        return prefs(c).getBoolean("background", true);
    }

    static void setBackground(Context c, boolean on) {
        prefs(c).edit().putBoolean("background", on).apply();
    }

    /** The mDNS host label: letters, digits and hyphens only. */
    static String hostName(String name) {
        String h = name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (h.isEmpty()) h = "AirPlay-TV";
        return h.length() > 40 ? h.substring(0, 40) : h;
    }

    /** A random, locally administered unicast MAC kept for good: iPhones remember the receiver by it. */
    static byte[] hwAddr(Context c) {
        SharedPreferences p = prefs(c);
        String saved = p.getString("hw", null);
        byte[] hw = new byte[6];
        if (saved != null && saved.length() == 12) {
            for (int i = 0; i < 6; i++) hw[i] = (byte) Integer.parseInt(saved.substring(i * 2, i * 2 + 2), 16);
            return hw;
        }
        new SecureRandom().nextBytes(hw);
        hw[0] = (byte) ((hw[0] & 0xFE) | 0x02);
        StringBuilder sb = new StringBuilder();
        for (byte b : hw) sb.append(String.format("%02x", b));
        p.edit().putString("hw", sb.toString()).apply();
        return hw;
    }
}
