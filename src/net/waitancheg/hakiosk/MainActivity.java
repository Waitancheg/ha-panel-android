package net.waitancheg.hakiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * HOME launcher: fullscreen WebView with Home Assistant, idle photo slideshow
 * from HA Media Source (or a clock), hidden PIN-protected settings.
 * Deprecated APIs are used on purpose: the target device runs Android 5.0 (API 21).
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements NightMode.Host {
    private static final String TAG = "HAPanel";
    private static final int REQ_SETTINGS = 1;
    private static final long RETRY_MS = 15000;
    private static final long FADE_MS = 1200;
    private static final long LIST_RETRY_MS = 60000;
    private static final long JS_TIMEOUT_MS = 20000;
    private static final Locale RU = new Locale("ru", "RU");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler bg;
    private HandlerThread dlThread;
    private Handler dl;
    private final Set<String> prefetching = new HashSet<>();
    private Prefs prefs;
    private NightMode night;
    private View nightOverlay;

    private FrameLayout root;
    private WebView web;
    private TextView errorView;

    // Pull-to-refresh (custom: no support / AndroidX library)
    private static final int PTR_TRIGGER_DP = 120;
    private static final int PTR_TOP_BAND_DP = 96;
    private static final int PTR_LOCK_DP = 12;
    private static final long PTR_TIMEOUT_MS = 15000;
    private FrameLayout ptrBox;
    private boolean pageAtTop = true;
    private boolean ptrTracking;
    private boolean ptrLocked;
    private boolean ptrRefreshing;
    private boolean ptrSentCancel;
    private float ptrStartX, ptrStartY, ptrPull;
    private final Runnable ptrTimeout = this::endPtrRefresh;

    // Slideshow
    private FrameLayout slideRoot;
    private final ImageView[] img = new ImageView[2];
    private final Bitmap[] bmp = new Bitmap[2];
    private int front = 0;
    private LinearLayout clockBox;
    private TextView clockTime, clockDate, smallClock;
    private boolean slideshowOn;
    private int session;
    private List<String> photos;
    private int photoIdx, failures;
    private boolean swallowTouch;
    private boolean pendingSlideshow;
    private boolean resumed;
    private final Random random = new Random();

    // JS bridge to the HA frontend already logged in inside the WebView
    private interface JsCallback {
        void ok(String json);
        void fail(String error);
    }
    private final SparseArray<JsCallback> jsPending = new SparseArray<>();
    private int jsSeq;
    private boolean useJs; // current slideshow source: WebView session (true) or token WebSocket

    // Worker-thread-only state
    private HaMedia media;
    private String mediaUrl, mediaToken;

    // Page state
    private boolean pageError;
    private String failedUrl;
    private String loadedStartUrl;
    private boolean wasOffline;

    // Hidden settings gesture
    private long cornerFirstTap;
    private int cornerTaps;
    private AlertDialog pinDialog;

    private final Runnable idleRunnable = () -> {
        if (night != null && night.isDark()) {
            Log.i(TAG, "idle: dark room (night mode), slideshow skipped");
            return;
        }
        if (!resumed || slideshowOn) {
            Log.i(TAG, "idle: skipped (resumed=" + resumed + ", slideshowOn=" + slideshowOn + ")");
            return;
        }
        Log.i(TAG, "idle " + idleMs() / 1000 + " s without touches -> slideshow");
        startSlideshow();
    };
    private int idleTestSec; // test hook only (adb ... --ei idle_test_sec N, 0 = off); not saved
    private final Runnable retryRunnable = this::retryLoad;
    private final Runnable longPressRunnable = this::askPin;
    private final Runnable nextSlideRunnable = () -> requestNext(session);
    private final Runnable clockTick = this::tickClock;
    private final Runnable listRetryRunnable = () -> {
        if (slideshowOn && photos == null) loadPhotoList(session);
    };

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onConnectivityChanged();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        workerThread = new HandlerThread("slides");
        workerThread.start();
        bg = new Handler(workerThread.getLooper());
        dlThread = new HandlerThread("slide-dl");
        dlThread.start();
        dl = new Handler(dlThread.getLooper());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        buildWebView();
        buildPtrOverlay();
        buildErrorView();
        buildSlideshow();
        nightOverlay = new View(this);
        nightOverlay.setBackgroundColor(Color.BLACK);
        nightOverlay.setVisibility(View.GONE);
        root.addView(nightOverlay, match());
        setContentView(root);

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(v -> {
            if ((v & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) ui.postDelayed(this::hideSystemUi, 2000);
        });

        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        loadStartUrl();
        night = new NightMode(this, this, prefs);
        night.start();
        handleTestIntent(getIntent());
    }

    @Override
    public void setNightOverlay(boolean show) {
        nightOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            stopSlideshow();
            ui.removeCallbacks(idleRunnable);
        } else {
            resetIdle();
        }
    }

    /** Test hook for night mode: adb shell am start -n net.waitancheg.hakiosk/.MainActivity --es night_sim 0|off */
    private void handleTestIntent(Intent i) {
        if (i != null && i.hasExtra("idle_test_sec")) {
            idleTestSec = Math.max(0, i.getIntExtra("idle_test_sec", 0));
            Log.i(TAG, "idle test override: " + (idleTestSec > 0 ? idleTestSec + " s" : "off, "
                    + prefs.idleMinutes() + " min from settings"));
            resetIdle();
        }
        String v = i == null ? null : i.getStringExtra("night_sim");
        if (v == null) return;
        try {
            night.simulate("off".equals(v) ? null : Float.valueOf(v));
        } catch (NumberFormatException ignored) { }
    }

    // ---------------------------------------------------------------- WebView

    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect over USB/ADB only

        web.addJavascriptInterface(new JsBridge(), "HAPanelBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // everything stays inside the panel
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageError = false;
                Log.i(TAG, "page started " + url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TAG, "load error " + errorCode + " " + description + " " + failingUrl);
                pageError = true;
                failedUrl = failingUrl;
                showError("Нет связи, повтор…\n" + (description == null ? "" : description));
                scheduleRetry();
                if (ptrRefreshing) endPtrRefresh();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageError) {
                    hideError();
                    ui.removeCallbacks(retryRunnable);
                }
                CookieManager.getInstance().flush();
                injectPtrHook();
                ui.postDelayed(() -> injectPtrHook(), 600);
                if (ptrRefreshing) endPtrRefresh();
            }
        });
        root.addView(web, match());
    }

    private void buildErrorView() {
        errorView = new TextView(this);
        errorView.setBackgroundColor(0xE0000000);
        errorView.setTextColor(0xFFDDDDDD);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorView.setVisibility(View.GONE);
        root.addView(errorView, match());
    }

    private void loadStartUrl() {
        loadedStartUrl = prefs.url();
        failedUrl = null;
        web.loadUrl(loadedStartUrl);
    }

    private void retryLoad() {
        if (!isOnline()) {
            showError("Нет сети, жду подключения…");
            scheduleRetry();
            return;
        }
        String u = failedUrl != null && failedUrl.startsWith("http") ? failedUrl : prefs.url();
        web.loadUrl(u);
    }

    private void scheduleRetry() {
        ui.removeCallbacks(retryRunnable);
        ui.postDelayed(retryRunnable, RETRY_MS);
    }

    private void showError(String text) {
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void onConnectivityChanged() {
        if (!isOnline()) {
            wasOffline = true;
            showError("Нет связи, повтор…");
            scheduleRetry();
        } else if (wasOffline || pageError) {
            wasOffline = false;
            ui.removeCallbacks(retryRunnable);
            ui.postDelayed(retryRunnable, 3000); // give DHCP/DNS a moment
        }
    }

    // -------------------------------------------------------- System UI / input

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (night != null && night.onTouch(action)) return true;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) resetIdle();
        handleCorner(ev);
        if (slideshowOn) {
            if (action == MotionEvent.ACTION_DOWN) {
                stopSlideshow();
                swallowTouch = true;
            }
            return true;
        }
        if (swallowTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowTouch = false;
            return true;
        }
        if (handlePtr(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        resetIdle();
        if (slideshowOn && event.getAction() == KeyEvent.ACTION_DOWN) {
            stopSlideshow();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Hidden gesture: hold top-left corner 3 s, or tap it 5 times within 3 s. */
    private void handleCorner(MotionEvent ev) {
        int corner = dp(80);
        boolean inCorner = ev.getX() < corner && ev.getY() < corner;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inCorner) {
                    cornerTaps = 0;
                    return;
                }
                ui.removeCallbacks(longPressRunnable);
                ui.postDelayed(longPressRunnable, 3000);
                long now = SystemClock.uptimeMillis();
                if (now - cornerFirstTap > 3000) {
                    cornerFirstTap = now;
                    cornerTaps = 0;
                }
                if (++cornerTaps >= 5) {
                    cornerTaps = 0;
                    ui.removeCallbacks(longPressRunnable);
                    askPin();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!inCorner) ui.removeCallbacks(longPressRunnable);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressRunnable);
                break;
            default:
                break;
        }
    }

    private void askPin() {
        if (pinDialog != null && pinDialog.isShowing()) return;
        stopSlideshow();
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        pinDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Настройки панели")
                .setMessage("Введите PIN")
                .setView(pin)
                .setPositiveButton("OK", (d, w) -> {
                    if (pin.getText().toString().trim().equals(prefs.pin())) {
                        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
                    } else {
                        Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        pinDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        pin.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        pin.setOnEditorActionListener((v, actionId, ev) -> {
            pinDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        pinDialog.show();
        pin.requestFocus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SETTINGS) return;
        if (data != null && data.getBooleanExtra(SettingsActivity.EXTRA_RESET_LOGIN, false)) {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            web.clearHistory();
            loadStartUrl();
        } else if (data != null && data.getBooleanExtra(SettingsActivity.EXTRA_RELOAD, false)) {
            web.reload();
        } else if (!prefs.url().equals(loadedStartUrl)) {
            loadStartUrl();
        }
        night.start(); // re-reads the night mode settings
        if (data != null && data.getBooleanExtra(SettingsActivity.EXTRA_SLIDESHOW, false)) {
            pendingSlideshow = true;
        }
    }

    @Override
    public void onBackPressed() {
        if (slideshowOn) {
            stopSlideshow();
        } else if (web.canGoBack()) {
            web.goBack();
        }
        // never leave the panel
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        stopSlideshow(); // HOME pressed while we are the launcher
        handleTestIntent(intent);
        resetIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        hideSystemUi();
        resetIdle();
        Log.i(TAG, "resumed; slideshow after " + idleMs() / 1000 + " s idle, folder '" + prefs.folder() + "'");
        if (pendingSlideshow) {
            pendingSlideshow = false;
            ui.postDelayed(this::startSlideshow, 300);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopSlideshow();
        ui.removeCallbacks(idleRunnable);
        ui.removeCallbacks(longPressRunnable);
        CookieManager.getInstance().flush();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(netReceiver); } catch (IllegalArgumentException ignored) { }
        night.stop();
        ui.removeCallbacksAndMessages(null);
        bg.post(() -> {
            if (media != null) media.close();
            media = null;
        });
        workerThread.quitSafely();
        dlThread.quitSafely();
        releaseSlot(0);
        releaseSlot(1);
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }

    private long idleMs() {
        return idleTestSec > 0 ? idleTestSec * 1000L : prefs.idleMinutes() * 60000L;
    }

    private void resetIdle() {
        ui.removeCallbacks(idleRunnable);
        long ms = idleMs();
        if (ms > 0) ui.postDelayed(idleRunnable, ms);
    }

    // -------------------------------------------------------------- Slideshow

    private void buildSlideshow() {
        slideRoot = new FrameLayout(this);
        slideRoot.setBackgroundColor(Color.BLACK);
        slideRoot.setVisibility(View.GONE);
        for (int i = 0; i < 2; i++) {
            img[i] = new ImageView(this);
            img[i].setScaleType(ImageView.ScaleType.MATRIX);
            img[i].setAlpha(0f);
            slideRoot.addView(img[i], match());
        }

        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        clockTime = new TextView(this);
        clockTime.setTextColor(0xFF8A8A8A);
        clockTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 110);
        clockTime.setGravity(Gravity.CENTER);
        clockDate = new TextView(this);
        clockDate.setTextColor(0xFF6A6A6A);
        clockDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        clockDate.setGravity(Gravity.CENTER);
        clockBox.addView(clockTime);
        clockBox.addView(clockDate);
        slideRoot.addView(clockBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        smallClock = new TextView(this);
        smallClock.setTextColor(Color.WHITE);
        smallClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        smallClock.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        smallClock.setPadding(dp(16), dp(16), dp(20), dp(16));
        smallClock.setVisibility(View.GONE);
        slideRoot.addView(smallClock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));

        root.addView(slideRoot, match());
    }

    private void startSlideshow() {
        if (slideshowOn || !resumed) return;
        if (pinDialog != null && pinDialog.isShowing()) return;
        if (night != null && night.isDark()) return; // darkness: night mode has priority
        Log.i(TAG, "slideshow start");
        slideshowOn = true;
        final int s = ++session;
        photos = null;
        failures = 0;
        slideRoot.setAlpha(0f);
        slideRoot.setVisibility(View.VISIBLE);
        slideRoot.animate().alpha(1f).setDuration(600).start();
        showClockMode(true);
        tickClock();
        loadPhotoList(s);
    }

    private void stopSlideshow() {
        if (!slideshowOn) return;
        Log.i(TAG, "slideshow stop");
        slideshowOn = false;
        session++;
        ui.removeCallbacks(nextSlideRunnable);
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(listRetryRunnable);
        slideRoot.animate().cancel();
        slideRoot.setVisibility(View.GONE);
        for (int i = 0; i < 2; i++) {
            img[i].animate().cancel();
            img[i].setAlpha(0f);
            releaseSlot(i);
        }
        photos = null;
        resetIdle();
    }

    private void releaseSlot(int i) {
        img[i].setImageDrawable(null);
        if (bmp[i] != null) {
            bmp[i].recycle();
            bmp[i] = null;
        }
    }

    private void showClockMode(boolean clock) {
        clockBox.setVisibility(clock ? View.VISIBLE : View.GONE);
        smallClock.setVisibility(clock ? View.GONE : View.VISIBLE);
    }

    private void tickClock() {
        if (!slideshowOn) return;
        Date now = new Date();
        String t = new SimpleDateFormat("HH:mm", RU).format(now);
        String d = new SimpleDateFormat("EEEE, d MMMM", RU).format(now);
        if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        if (!t.contentEquals(clockTime.getText())) {
            // move the clock a little every minute to avoid burn-in
            int r = dp(70);
            clockBox.setTranslationX(random.nextInt(2 * r + 1) - r);
            clockBox.setTranslationY(random.nextInt(4 * r + 1) - 2 * r);
        }
        clockTime.setText(t);
        clockDate.setText(d);
        smallClock.setText(t);
        ui.removeCallbacks(clockTick);
        ui.postDelayed(clockTick, 1000 - (System.currentTimeMillis() % 1000) + 20);
    }

    /**
     * Lists photos. Primary: the HA frontend session in the WebView (hass.callWS).
     * Fallback: our own WebSocket client with a long-lived token, if one is set.
     */
    private void loadPhotoList(final int s) {
        final String folder = prefs.folder();
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "media_source/browse_media");
            cmd.put("media_content_id", HaMedia.folderId(folder));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        jsCallWS(cmd, new JsCallback() {
            @Override
            public void ok(String json) {
                List<String> ids = null;
                try {
                    ids = HaMedia.imagesFromBrowse(new JSONObject(json));
                    Collections.shuffle(ids);
                    Log.i(TAG, "slideshow (session): " + ids.size() + " images in " + folder);
                } catch (JSONException e) {
                    Log.w(TAG, "bad browse json: " + e);
                }
                useJs = true;
                onPhotoList(s, ids);
            }

            @Override
            public void fail(String error) {
                Log.i(TAG, "slideshow via WebView session unavailable: " + error);
                if (s != session || !slideshowOn) return;
                if (prefs.token().isEmpty()) onPhotoList(s, null);
                else loadPhotoListWithToken(s);
            }
        });
    }

    private void loadPhotoListWithToken(final int s) {
        final String url = prefs.url();
        final String token = prefs.token();
        final String folder = prefs.folder();
        bg.post(() -> {
            List<String> ids = null;
            try {
                HaMedia m = mediaFor(url, token);
                ids = m.listImages(folder);
                Collections.shuffle(ids);
                Log.i(TAG, "slideshow (token): " + ids.size() + " images in " + folder);
            } catch (Exception e) {
                Log.w(TAG, "slideshow list failed: " + e);
            }
            final List<String> result = ids;
            ui.post(() -> {
                useJs = false;
                onPhotoList(s, result);
            });
        });
    }

    private void onPhotoList(int s, List<String> result) {
        if (s != session || !slideshowOn) return;
        if (result == null || result.isEmpty()) {
            ui.removeCallbacks(listRetryRunnable);
            ui.postDelayed(listRetryRunnable, LIST_RETRY_MS); // clock meanwhile
            return;
        }
        photos = result;
        photoIdx = 0;
        failures = 0;
        pruneCache(result);
        requestNext(s);
        prefetchNext(s);
    }

    /** Runs hass.callWS(cmd) in the HA frontend; the result comes back via JsBridge. */
    private void jsCallWS(JSONObject cmd, JsCallback cb) {
        final int id = ++jsSeq;
        jsPending.put(id, cb);
        String js = "(function(){var ID=" + id + ",B=window.HAPanelBridge;try{"
                + "var e=document.querySelector('home-assistant'),h=e&&e.hass;"
                + "if(!h||!h.callWS||!h.connection){B.onError(ID,'no_hass');return;}"
                + "h.callWS(" + cmd.toString() + ").then(function(r){B.onResult(ID,JSON.stringify(r===undefined?null:r));},"
                + "function(x){B.onError(ID,String((x&&(x.code||x.message))||x));});"
                + "}catch(x){B.onError(ID,'js: '+x);}})();";
        web.evaluateJavascript(js, null);
        ui.postDelayed(() -> deliverJs(id, null, "timeout"), JS_TIMEOUT_MS);
    }

    private void deliverJs(int id, String json, String error) {
        JsCallback cb = jsPending.get(id);
        if (cb == null) return;
        jsPending.remove(id);
        if (error == null) cb.ok(json);
        else cb.fail(error);
    }

    /** Called by page JS on a binder thread; only answers pending requests. */
    private final class JsBridge {
        @JavascriptInterface
        public void onResult(final int id, final String json) {
            ui.post(() -> deliverJs(id, json == null ? "null" : json, null));
        }

        @JavascriptInterface
        public void onError(final int id, final String error) {
            ui.post(() -> deliverJs(id, null, error == null ? "error" : error));
        }

        @JavascriptInterface
        public void setAtTop(final boolean atTop) {
            ui.post(() -> pageAtTop = atTop);
        }
    }

    /** Worker thread only. */
    private HaMedia mediaFor(String url, String token) throws java.io.IOException {
        if (media == null || !url.equals(mediaUrl) || !token.equals(mediaToken)) {
            if (media != null) media.close();
            media = new HaMedia(url, token);
            mediaUrl = url;
            mediaToken = token;
        }
        return media;
    }

    private void requestNext(final int s) {
        if (s != session || !slideshowOn || photos == null || photos.isEmpty()) return;
        final String id = photos.get(photoIdx++ % photos.size());
        if (cacheFile(id).exists()) {
            fetchSlide(s, id, "");
            return;
        }
        if (!useJs) {
            fetchSlide(s, id, null);
            return;
        }
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "media_source/resolve_media");
            cmd.put("media_content_id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        jsCallWS(cmd, new JsCallback() {
            @Override
            public void ok(String json) {
                try {
                    fetchSlide(s, id, HaMedia.onBase(prefs.url(), new JSONObject(json).optString("url", "")));
                } catch (Exception e) {
                    Log.w(TAG, "resolve result: " + e);
                    onSlideFailed(s);
                }
            }

            @Override
            public void fail(String error) {
                Log.w(TAG, "resolve failed: " + id + " " + error);
                onSlideFailed(s);
            }
        });
    }

    /** signedUrl == null: resolve on the worker via the token WebSocket. */
    private void fetchSlide(final int s, final String id, final String signedUrl) {
        if (s != session || !slideshowOn) return;
        final int w = viewW();
        final int h = viewH();
        final String token = signedUrl == null ? prefs.token() : null;
        final File cache = cacheFile(id);
        bg.post(() -> {
            if (s != session) return;
            Bitmap b = null;
            int rot = 0;
            try {
                if (!cache.exists()) {
                    String u = signedUrl;
                    if (u == null || u.isEmpty()) {
                        HaMedia m = media;
                        if (m == null) throw new java.io.IOException("not connected");
                        u = m.resolve(id);
                    }
                    long t0 = SystemClock.elapsedRealtime();
                    downloadTo(u, cache, token);
                    Log.i(TAG, "downloaded " + cache.length() / 1024 + " KB in "
                            + (SystemClock.elapsedRealtime() - t0) + " ms");
                }
                rot = exifRotation(cache);
                b = decode(cache, w, h, rot);
            } catch (Exception e) {
                Log.w(TAG, "slide failed: " + id + " " + e);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "slide OOM: " + id);
            }
            final Bitmap fb = b;
            final int frot = rot;
            ui.post(() -> {
                if (fb == null) onSlideFailed(s);
                else showBitmap(s, fb, frot);
            });
        });
    }

    private void onSlideFailed(int s) {
        if (s != session || !slideshowOn) return;
        failures++;
        if (photos == null || failures >= Math.min(photos.size(), 5)) {
            photos = null;
            if (bmp[front] == null) showClockMode(true);
            ui.postDelayed(listRetryRunnable, LIST_RETRY_MS);
            return;
        }
        ui.removeCallbacks(nextSlideRunnable);
        ui.postDelayed(nextSlideRunnable, 2000);
    }

    private void showBitmap(int s, Bitmap b, int rot) {
        if (s != session || !slideshowOn) {
            b.recycle();
            return;
        }
        if (bmp[front] == null) Log.i(TAG, "slideshow: first photo shown " + b.getWidth() + "x" + b.getHeight());
        failures = 0;
        final int back = 1 - front;
        img[back].animate().cancel();
        releaseSlot(back); // never more than 2 bitmaps alive
        ImageView iv = img[back];
        iv.setImageMatrix(fitMatrix(b, rot, viewW(), viewH()));
        iv.setImageBitmap(b);
        bmp[back] = b;
        iv.setAlpha(0f);
        showClockMode(false);
        iv.animate().alpha(1f).setDuration(FADE_MS).start();
        final int oldIdx = front;
        img[oldIdx].animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (oldIdx != front) releaseSlot(oldIdx);
        }).start();
        front = back;
        ui.removeCallbacks(nextSlideRunnable);
        ui.postDelayed(nextSlideRunnable, prefs.slideSeconds() * 1000L);
        prefetchNext(s);
    }

    // ------------------------------------------------------------ Photo cache

    private File slidesDir() {
        File d = new File(getFilesDir(), "slides");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private File cacheFile(String id) {
        return new File(slidesDir(), sha1(id) + ".img");
    }

    private static String sha1(String v) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(v.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(v.hashCode());
        }
    }

    /** Downloads to a temp file and renames, so a half-written photo is never used. */
    private static synchronized void downloadTo(String url, File dest, String bearer) throws java.io.IOException {
        if (dest.exists()) return;
        File tmp = new File(dest.getPath() + ".part");
        tmp.delete();
        HaMedia.download(url, tmp, bearer);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new java.io.IOException("rename failed");
        }
    }

    /** Removes cached photos that are no longer in the HA folder. */
    private void pruneCache(List<String> ids) {
        final Set<String> keep = new HashSet<>();
        for (String id : ids) keep.add(sha1(id) + ".img");
        final File dir = slidesDir();
        dl.post(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!keep.contains(f.getName())) f.delete();
            }
        });
    }

    /** While a photo is on screen, downloads the next uncached photo in the background. */
    private void prefetchNext(final int s) {
        if (s != session || !slideshowOn || photos == null || !useJs) return;
        String target = null;
        for (int k = 0; k < photos.size(); k++) {
            String id = photos.get((photoIdx + k) % photos.size());
            if (!cacheFile(id).exists() && !prefetching.contains(id)) {
                target = id;
                break;
            }
        }
        if (target == null) return;
        final String id = target;
        prefetching.add(id);
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "media_source/resolve_media");
            cmd.put("media_content_id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        jsCallWS(cmd, new JsCallback() {
            @Override
            public void ok(String json) {
                final String u;
                try {
                    u = HaMedia.onBase(prefs.url(), new JSONObject(json).optString("url", ""));
                } catch (Exception e) {
                    prefetching.remove(id);
                    return;
                }
                final File f = cacheFile(id);
                dl.post(() -> {
                    boolean ok = false;
                    try {
                        long t0 = SystemClock.elapsedRealtime();
                        downloadTo(u, f, null);
                        ok = true;
                        Log.i(TAG, "prefetched " + f.length() / 1024 + " KB in "
                                + (SystemClock.elapsedRealtime() - t0) + " ms");
                    } catch (Exception e) {
                        Log.w(TAG, "prefetch failed: " + e);
                    }
                    final boolean fok = ok;
                    ui.post(() -> {
                        prefetching.remove(id);
                        if (fok) prefetchNext(s);
                    });
                });
            }

            @Override
            public void fail(String error) {
                prefetching.remove(id);
                Log.w(TAG, "prefetch resolve failed: " + error);
            }
        });
    }

    private static Matrix fitMatrix(Bitmap b, int rot, int vw, int vh) {
        float bw = b.getWidth(), bh = b.getHeight();
        float rw = (rot % 180 == 0) ? bw : bh;
        float rh = (rot % 180 == 0) ? bh : bw;
        float scale = Math.min(vw / rw, vh / rh);
        Matrix m = new Matrix();
        m.setTranslate(-bw / 2f, -bh / 2f);
        m.postRotate(rot);
        m.postScale(scale, scale);
        m.postTranslate(vw / 2f, vh / 2f);
        return m;
    }

    private static int exifRotation(File f) {
        try {
            int o = new ExifInterface(f.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Decodes with inSampleSize so the bitmap is about screen-sized (RGB_565). */
    private static Bitmap decode(File f, int vw, int vh, int rot) throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new java.io.IOException("not an image");
        int iw = (rot % 180 == 0) ? o.outWidth : o.outHeight;
        int ih = (rot % 180 == 0) ? o.outHeight : o.outWidth;
        int sample = 1;
        while (iw / (sample * 2) >= vw && ih / (sample * 2) >= vh) sample *= 2;
        while ((long) (iw / sample) * (ih / sample) > 2_500_000L) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        d.inDither = true;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        if (b == null) throw new java.io.IOException("decode failed");
        return b;
    }


    // -------------------------------------------------------- Pull-to-refresh

    private void buildPtrOverlay() {
        int size = dp(56);
        ptrBox = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC222222);
        ptrBox.setBackground(bg);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyle);
        int pad = dp(8);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        blp.setMargins(pad, pad, pad, pad);
        ptrBox.addView(bar, blp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(8);
        ptrBox.setLayoutParams(lp);
        ptrBox.setVisibility(View.INVISIBLE);
        ptrBox.setAlpha(0f);
        root.addView(ptrBox);
    }

    /**
     * Reports whether HA's real scroll container (often inside shadow DOM) is at the top.
     * WebView.getScrollY() is usually 0 on this dashboard.
     */
    private void injectPtrHook() {
        if (web == null) return;
        String js = "(function(){"
                + "function scrolled(el){try{return !!(el&&el.scrollTop>1);}catch(e){return false;}}"
                + "function atTop(){"
                + "if((window.scrollY||0)>1)return false;"
                + "var de=document.documentElement,b=document.body;"
                + "if(scrolled(de)||scrolled(b))return false;"
                + "var hit=false;"
                + "function walk(n,d){"
                + "if(!n||d>16||hit)return;"
                + "if(scrolled(n)){hit=true;return;}"
                + "if(n.shadowRoot)walk(n.shadowRoot,d+1);"
                + "var c=n.children;if(c){for(var i=0;i<c.length;i++)walk(c[i],d+1);}"
                + "}"
                + "walk(document.documentElement,0);"
                + "var ha=document.querySelector('home-assistant');if(ha)walk(ha,0);"
                + "return !hit;"
                + "}"
                + "function report(){try{if(window.HAPanelBridge&&HAPanelBridge.setAtTop)HAPanelBridge.setAtTop(atTop());}catch(e){}}"
                + "if(!window.__haPanelPtrHook){"
                + "window.__haPanelPtrHook=true;"
                + "document.addEventListener('scroll',report,true);"
                + "window.addEventListener('scroll',report,true);"
                + "setInterval(report,700);"
                + "}"
                + "report();"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    /** @return true if the event is consumed by pull-to-refresh and must not reach the WebView. */
    private boolean handlePtr(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (pinDialog != null && pinDialog.isShowing()) {
            resetPtrGesture();
            return false;
        }
        if (ptrRefreshing) {
            return false;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                ptrTracking = true;
                ptrLocked = false;
                ptrSentCancel = false;
                ptrStartX = ev.getX();
                ptrStartY = ev.getY();
                ptrPull = 0f;
                injectPtrHook();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!ptrTracking) return false;
                float dx = ev.getX() - ptrStartX;
                float dy = ev.getY() - ptrStartY;
                if (!ptrLocked) {
                    int lock = dp(PTR_LOCK_DP);
                    if (Math.abs(dx) < lock && Math.abs(dy) < lock) return false;
                    boolean fromTop = ptrStartY <= dp(PTR_TOP_BAND_DP);
                    boolean vertical = dy > lock && dy >= 2f * Math.abs(dx);
                    boolean verticalFromTop = fromTop && dy > lock && dy >= Math.abs(dx);
                    if (pageAtTop && (vertical || verticalFromTop)) {
                        ptrLocked = true;
                    } else {
                        ptrTracking = false;
                        return false;
                    }
                }
                if (!ptrSentCancel) {
                    ptrSentCancel = true;
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                ptrPull = Math.max(0f, ev.getY() - ptrStartY);
                showPtrPull(ptrPull);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!ptrLocked) ptrTracking = false;
                return ptrLocked;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!ptrTracking) return false;
                boolean locked = ptrLocked;
                float pull = ptrPull;
                resetPtrGesture();
                if (!locked) return false;
                if (action == MotionEvent.ACTION_UP && pageAtTop && pull >= dp(PTR_TRIGGER_DP)) {
                    startPtrRefresh();
                } else {
                    hidePtrIndicator();
                }
                return true;
            default:
                return ptrLocked;
        }
    }

    private void resetPtrGesture() {
        ptrTracking = false;
        ptrLocked = false;
        ptrPull = 0f;
    }

    private void showPtrPull(float pullPx) {
        float trigger = dp(PTR_TRIGGER_DP);
        float damped = pullPx * 0.5f;
        float y = Math.min(damped, trigger * 0.55f);
        ptrBox.animate().cancel();
        ptrBox.setVisibility(View.VISIBLE);
        ptrBox.setTranslationY(y);
        float p = Math.min(1f, pullPx / trigger);
        ptrBox.setAlpha(0.35f + 0.65f * p);
        float scale = 0.75f + 0.25f * p;
        ptrBox.setScaleX(scale);
        ptrBox.setScaleY(scale);
    }

    private void startPtrRefresh() {
        if (ptrRefreshing) return;
        ptrRefreshing = true;
        showPtrPull(dp(PTR_TRIGGER_DP));
        Log.i(TAG, "pull-to-refresh: reload");
        ui.removeCallbacks(ptrTimeout);
        ui.postDelayed(ptrTimeout, PTR_TIMEOUT_MS);
        web.reload();
    }

    private void endPtrRefresh() {
        ui.removeCallbacks(ptrTimeout);
        ptrRefreshing = false;
        hidePtrIndicator();
    }

    private void hidePtrIndicator() {
        if (ptrBox == null) return;
        ptrBox.animate().cancel();
        ptrBox.animate()
                .alpha(0f)
                .translationY(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    ptrBox.setVisibility(View.INVISIBLE);
                    ptrBox.setScaleX(1f);
                    ptrBox.setScaleY(1f);
                })
                .start();
    }

    // ---------------------------------------------------------------- helpers

    private int viewW() {
        return slideRoot.getWidth() > 0 ? slideRoot.getWidth() : screen().widthPixels;
    }

    private int viewH() {
        return slideRoot.getHeight() > 0 ? slideRoot.getHeight() : screen().heightPixels;
    }

    private DisplayMetrics screen() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
