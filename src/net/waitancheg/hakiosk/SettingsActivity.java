package net.waitancheg.hakiosk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Hidden settings screen (opened from MainActivity after the PIN check). */
public class SettingsActivity extends Activity implements SensorEventListener {
    static final String EXTRA_RELOAD = "reload";
    static final String EXTRA_SLIDESHOW = "slideshow";
    static final String EXTRA_RESET_LOGIN = "reset_login";

    private Prefs prefs;
    private EditText url, token, idle, slide, folder, pin;
    private LinearLayout box;
    private CheckBox nightOn;
    private EditText nightLux;
    private TextView liveLux;
    private SensorManager sm;
    private Sensor lightSensor;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // the system screen timeout (60 s) must not switch the panel off while settings are open
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);
        sv.addView(box);

        url = field("Адрес Home Assistant", prefs.url(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        token = field("Долгосрочный токен HA (необязательно, только если без него фото не грузятся)", prefs.token(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        idle = field("Заставка через, минут (0 — выключить)", String.valueOf(prefs.idleMinutes()),
                InputType.TYPE_CLASS_NUMBER);
        slide = field("Смена фото, секунд", String.valueOf(prefs.slideSeconds()),
                InputType.TYPE_CLASS_NUMBER);
        folder = field("Папка с фото в «Локальных медиа» HA (/media/…)", prefs.folder(),
                InputType.TYPE_CLASS_TEXT);
        pin = field("PIN для входа в настройки", prefs.pin(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        buildNight();

        button("Сохранить и вернуться", v -> {
            if (save()) finishWith(EXTRA_RELOAD, false);
        });
        button("Перезагрузить страницу", v -> {
            if (save()) finishWith(EXTRA_RELOAD, true);
        });
        button("Показать заставку сейчас", v -> {
            if (save()) finishWith(EXTRA_SLIDESHOW, true);
        });
        button("Настройки Wi-Fi", v -> launch(Settings.ACTION_WIFI_SETTINGS));
        button("Настройки Android", v -> launch(Settings.ACTION_SETTINGS));
        button("Выйти в систему (выбрать другой главный экран)", v -> {
            if (!launch(Settings.ACTION_HOME_SETTINGS)) launch(Settings.ACTION_SETTINGS);
        });
        button("Сбросить вход в HA (выйти из аккаунта)", v -> finishWith(EXTRA_RESET_LOGIN, true));
        button("Отмена", v -> finish());

        TextView info = new TextView(this);
        info.setText("\nФото для заставки: положите файлы JPG/PNG в папку /media/"
                + prefs.folder() + " на сервере Home Assistant (раздел «Медиа → Локальные медиа»)."
                + " Фото берутся через вход в HA, выполненный на этой панели; токен не нужен.\n\n"
                + "HA Panel 1.5");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(info);

        setContentView(sv);
    }

    private boolean save() {
        String u = url.getText().toString().trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            toast("Адрес должен начинаться с http:// или https://");
            return false;
        }
        String pn = pin.getText().toString().trim();
        if (pn.length() < 4) {
            toast("PIN — минимум 4 цифры");
            return false;
        }
        int im = parse(idle, Prefs.DEFAULT_IDLE_MIN);
        int ss = Math.max(3, parse(slide, Prefs.DEFAULT_SLIDE_SEC));
        String f = folder.getText().toString().trim();
        float nl;
        try {
            nl = Float.parseFloat(nightLux.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            toast("Порог темноты — число, например 3");
            return false;
        }
        if (nl < 0f || nl > 1000f) {
            toast("Порог темноты — от 0 до 1000 лк");
            return false;
        }
        prefs.save(u, token.getText().toString().trim(), pn, f, Math.max(0, im), ss);
        prefs.saveNight(nightOn.isChecked(), nl);
        return true;
    }

    // ------------------------------------------------------------ night mode

    private void buildNight() {
        TextView h = new TextView(this);
        h.setText("Ночной режим");
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        h.setPadding(0, dp(18), 0, 0);
        box.addView(h);

        nightOn = new CheckBox(this);
        nightOn.setText("Чёрный экран, когда в комнате темно (датчик освещённости). Касание показывает панель на минуту.");
        nightOn.setChecked(prefs.nightOn());
        box.addView(nightOn);

        nightLux = field("Темно, если освещённость ниже, лк (светло снова — выше max(порог+7, порог×3))",
                trim(prefs.nightDarkLux()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        liveLux = new TextView(this);
        liveLux.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        liveLux.setPadding(0, dp(4), 0, 0);
        box.addView(liveLux);


        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        liveLux.setText(lightSensor == null ? "Датчика освещённости нет" : "Датчик: ждём показаний…");
    }

    private static String trim(float f) {
        return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lightSensor != null) sm.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        if (lightSensor != null) sm.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        liveLux.setText("Датчик сейчас: " + String.format(java.util.Locale.US, "%.1f", e.values[0]) + " лк");
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    private void finishWith(String key, boolean value) {
        Intent r = new Intent();
        r.putExtra(key, value);
        setResult(RESULT_OK, r);
        finish();
    }

    private boolean launch(String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private static int parse(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private EditText field(String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        l.setPadding(0, dp(10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        box.addView(b, lp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
