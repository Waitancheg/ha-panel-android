package net.waitancheg.hakiosk;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Night mode: when the room is dark (ambient light sensor) the panel goes black — window
 * brightness 0 plus a full-screen black overlay; the screen itself stays on (FLAG_KEEP_SCREEN_ON),
 * so a tap still works. A tap only removes the overlay (it never reaches the dashboard) and the
 * panel stays visible TEMP_WAKE_MS after the last touch; light returning restores it at once.
 */
final class NightMode implements SensorEventListener {
    interface Host {
        void setNightOverlay(boolean show);
    }

    static final String TAG = "HAPanelNight";
    static final long DARK_MS = 30000;      // lux below the dark threshold this long -> dark
    static final long LIGHT_MS = 3000;      // lux above the light threshold this long -> light
    static final long TEMP_WAKE_MS = 60000; // after a tap in the dark: visible this long after the last touch

    private final Activity act;
    private final Host host;
    private final Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor light;
    private boolean started, dark, overlayShown, swallowing;
    private boolean pendingDark, pendingLight;
    private float lux = -1f;
    private Float sim;          // test override (adb: am start ... --es night_sim <lux|off>)
    private float minMin = Float.MAX_VALUE, maxMin = -1f, lastLogged = -100f;
    private int events;

    private final Runnable goDark = () -> { pendingDark = false; enterDark(); };
    private final Runnable goLight = () -> { pendingLight = false; exitDark(); };
    private final Runnable overlayBack = () -> {
        if (dark && !overlayShown) {
            Log.i(TAG, "temporary wake over, black again");
            showOverlay(true);
        }
    };
    private final Runnable minuteLog = new Runnable() {
        @Override
        public void run() {
            writeMinute();
            ui.postDelayed(this, 60000);
        }
    };

    NightMode(Activity act, Host host, Prefs prefs) {
        this.act = act;
        this.host = host;
        this.prefs = prefs;
        sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    static float lightThreshold(float darkLux) {
        return Math.max(darkLux + 7f, darkLux * 3f);
    }

    /** True while the room is dark (black panel or a temporary tap wake): no slideshow then. */
    boolean isDark() {
        return dark;
    }

    /** Call from onCreate and after the settings change. */
    void start() {
        if (!prefs.nightOn() || light == null) {
            if (light == null) Log.w(TAG, "no light sensor");
            stop();
            return;
        }
        if (started) {
            reevaluate();
            return;
        }
        started = true;
        sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        ui.postDelayed(minuteLog, 60000);
        Log.i(TAG, "night mode on: dark<" + prefs.nightDarkLux() + " light>" + lightThreshold(prefs.nightDarkLux())
                + " sensor=" + light.getName());
    }

    /** Call from onDestroy, or when night mode is switched off. */
    void stop() {
        cancelDark();
        cancelLight();
        ui.removeCallbacks(minuteLog);
        if (dark) exitDark();
        if (!started) return;
        started = false;
        sm.unregisterListener(this);
        Log.i(TAG, "night mode off");
    }

    /** Test hook: pretend the sensor reads v (null = use the real sensor). */
    void simulate(Float v) {
        sim = v;
        Log.i(TAG, "simulate " + (v == null ? "off" : v + " lx"));
        reevaluate();
    }

    /**
     * Called first for every touch. While the panel is black, the whole gesture that starts on the
     * overlay is swallowed (only wakes the panel). Returns true to swallow.
     */
    boolean onTouch(int action) {
        if (!dark) {
            swallowing = false;
            return false;
        }
        ui.removeCallbacks(overlayBack);
        ui.postDelayed(overlayBack, TEMP_WAKE_MS);
        if (overlayShown && action == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "tap: temporary wake for " + TEMP_WAKE_MS / 1000 + " s");
            line("TAP-WAKE");
            showOverlay(false);
            swallowing = true;
            return true;
        }
        if (swallowing) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
            return true;
        }
        return overlayShown;
    }

    // ------------------------------------------------------------- sensor

    @Override
    public void onSensorChanged(SensorEvent e) {
        float v = e.values[0];
        lux = v;
        events++;
        if (v < minMin) minMin = v;
        if (v > maxMin) maxMin = v;
        if (Math.abs(v - lastLogged) > Math.max(1f, lastLogged * 0.25f)) {
            lastLogged = v;
            Log.i(TAG, "lux " + v + " dark=" + dark);
        }
        if (sim == null) evaluate(v);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    private void reevaluate() {
        if (!started) return;
        float v = sim != null ? sim : lux;
        if (v >= 0) evaluate(v);
    }

    private void evaluate(float v) {
        float darkTh = prefs.nightDarkLux();
        float lightTh = lightThreshold(darkTh);
        if (!dark) {
            cancelLight();
            if (v < darkTh) {
                if (!pendingDark) {
                    Log.i(TAG, "below " + darkTh + " lx (" + v + "), dark in " + DARK_MS / 1000 + " s");
                    pendingDark = true;
                    ui.postDelayed(goDark, DARK_MS);
                }
            } else if (pendingDark) {
                Log.i(TAG, "light again (" + v + " lx), dark cancelled");
                cancelDark();
            }
        } else {
            cancelDark();
            if (v > lightTh) {
                if (!pendingLight) {
                    Log.i(TAG, "above " + lightTh + " lx (" + v + "), light in " + LIGHT_MS / 1000 + " s");
                    pendingLight = true;
                    ui.postDelayed(goLight, LIGHT_MS);
                }
            } else if (pendingLight) {
                cancelLight();
            }
        }
    }

    private void cancelDark() {
        pendingDark = false;
        ui.removeCallbacks(goDark);
    }

    private void cancelLight() {
        pendingLight = false;
        ui.removeCallbacks(goLight);
    }

    // ------------------------------------------------------------- states

    private void enterDark() {
        if (dark) return;
        dark = true;
        Log.i(TAG, "DARK (lux=" + lux + ", sim=" + sim + ")");
        line("DARK");
        showOverlay(true);
    }

    private void exitDark() {
        if (!dark) return;
        dark = false;
        swallowing = false;
        Log.i(TAG, "LIGHT (lux=" + lux + ", sim=" + sim + ")");
        line("LIGHT");
        ui.removeCallbacks(overlayBack);
        showOverlay(false);
    }

    private void showOverlay(boolean show) {
        overlayShown = show;
        WindowManager.LayoutParams lp = act.getWindow().getAttributes();
        lp.screenBrightness = show ? 0.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        act.getWindow().setAttributes(lp);
        act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        host.setNightOverlay(show);
    }

    // -------------------------------------------------------------- lux log

    private void writeMinute() {
        String state = dark ? (overlayShown ? "dark" : "dark-tapwake") : "light";
        if (maxMin < 0) {
            line("lux - (no events) last=" + lux + " " + state);
        } else {
            line("lux min=" + minMin + " max=" + maxMin + " last=" + lux + " n=" + events + " " + state);
        }
        minMin = Float.MAX_VALUE;
        maxMin = -1f;
        events = 0;
    }

    /** One line to logcat and to /sdcard/Android/data/<pkg>/files/night_lux.log (readable over adb). */
    private void line(String s) {
        String t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String l = t + " " + s + (sim != null ? " sim=" + sim : "");
        Log.i(TAG, l);
        try {
            File dir = act.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "night_lux.log");
            if (f.length() > 512 * 1024) {
                File old = new File(dir, "night_lux.old.log");
                if (old.exists() && !old.delete()) Log.w(TAG, "cannot delete old log");
                if (!f.renameTo(old)) Log.w(TAG, "cannot rotate log");
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(l + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "log write failed: " + ex);
        }
    }
}
