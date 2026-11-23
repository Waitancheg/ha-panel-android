package net.waitancheg.hakiosk;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings, stored in private SharedPreferences. */
final class Prefs {
    static final String DEFAULT_URL = "http://homeassistant.example:8123";
    static final String DEFAULT_PIN = "1234";
    static final String DEFAULT_FOLDER = "slideshow";
    static final int DEFAULT_IDLE_MIN = 5;
    static final int DEFAULT_SLIDE_SEC = 15;
    static final float DEFAULT_NIGHT_DARK_LUX = 3f;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("hakiosk", Context.MODE_PRIVATE);
    }

    String url() { return sp.getString("url", DEFAULT_URL); }
    String token() { return sp.getString("token", ""); }
    String pin() { return sp.getString("pin", DEFAULT_PIN); }
    String folder() { return sp.getString("folder", DEFAULT_FOLDER); }
    int idleMinutes() { return sp.getInt("idle_min", DEFAULT_IDLE_MIN); }
    int slideSeconds() { return Math.max(3, sp.getInt("slide_sec", DEFAULT_SLIDE_SEC)); }

    boolean nightOn() { return sp.getBoolean("night_on", true); }
    float nightDarkLux() { return sp.getFloat("night_dark_lux", DEFAULT_NIGHT_DARK_LUX); }
    void saveNight(boolean on, float darkLux) {
        sp.edit()
                .putBoolean("night_on", on)
                .putFloat("night_dark_lux", darkLux)
                .remove("night_mode")
                .remove("saved_timeout")
                .apply();
    }

    void save(String url, String token, String pin, String folder, int idleMin, int slideSec) {
        sp.edit()
                .putString("url", url)
                .putString("token", token)
                .putString("pin", pin)
                .putString("folder", folder)
                .putInt("idle_min", idleMin)
                .putInt("slide_sec", slideSec)
                .apply();
    }
}
