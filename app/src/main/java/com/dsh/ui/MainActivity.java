package com.dsh.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import com.dsh.bridge.AndroidBridge;
import com.dsh.core.BootMetrics;
import com.dsh.core.CoreReadyProbe;
import com.dsh.core.CoreRuntimeState;
import com.dsh.core.CoreServiceLauncher;
import com.dsh.oauth.OAuthManager;
import com.dsh.oauth.OAuthTokenStore;
import com.dsh.runtime.RuntimeManager;
import com.dsh.shell.ShizukuBridge;
import com.dsh.theme.ThemeManager;
import com.dsh.ui.lifecycle.AppLifecycle;
import com.dsh.ui.lifecycle.BackNavigator;
import com.dsh.ui.lifecycle.ThemeRefresher;
import com.dsh.ui.splash.SplashController;
import com.dsh.ui.webview.AgyWebChromeClient;
import com.dsh.ui.webview.DesktopModePrefs;
import com.dsh.ui.webview.WebViewHost;
import com.dsh.util.SensitiveDataRedactor;
import com.dsh.util.ViewParams;

/**
 * Activity host: builds the WebView, starts CoreServerService, shows the
 * three-dot splash until the core UI has rendered, and wires theming/OAuth.
 * All logic lives in the helper classes; this only wires them and polls.
 */
public class MainActivity extends Activity
        implements WebViewHost.Listener, AgyWebChromeClient.FileChooserCallback {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_FILE_CHOOSER = 1002;
    private static final int REQUEST_MEDIA_PERMISSIONS = 1003;
    /**
     * Fallback splash budget when no boot is in progress in this process (the
     * activity was recreated with a service-owned core already running). This is
     * the adaptive budget, not a fixed 60s: install phase + readiness budget +
     * margin, hard-capped in {@link CoreReadyProbe}.
     */
    private static final long SPLASH_TIMEOUT_MS = CoreReadyProbe.splashDeadlineBootMs(0);
    private static final long POLL_STARTUP_INTERVAL_MS = 50;
    private static final long POLL_READY_INTERVAL_MS = 1000;
    /**
     * First-paint watchdog, armed when the app URL is handed to the WebView:
     * the UI is revealed even if the page's ready signal never arrives. It is a
     * backstop only; normally startup_ready.js -> onUiReady() or onPageFinished
     * dismisses the splash much sooner.
     */
    private static final long SPLASH_WATCHDOG_MS = 15000;
    /** Short fallback after onPageFinished, letting the page's own ready signal win. */
    private static final long PAINT_FALLBACK_MS = 900;
    /** How often the staged splash status line is refreshed from the boot phase. */
    private static final long STATUS_REFRESH_MS = 250;

    public static volatile MainActivity activeActivity;

    private ValueCallback<Uri[]> filePathCallback;
    private android.webkit.PermissionRequest pendingPermissionRequest;

    private ThemeManager themeManager;
    private OAuthManager oauthManager;
    private RuntimeManager runtimeManager;
    private ShizukuBridge shizukuBridge;
    private AndroidBridge androidBridge;
    private WebViewHost webViewHost;
    private SplashController splash;
    private FrameLayout rootLayout;

    private boolean appLoaded = false;
    private boolean activityResumed = false;
    private int loadedGeneration = -1;
    /** Boot-elapsed time at which the app URL was handed to the WebView (0 = not yet). */
    private long appLoadedAtUptime = 0L;
    private String lastStatusKey = null;
    private boolean splashDismissed = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable splashWatchdog = new Runnable() {
        @Override
        public void run() {
            if (splashDismissed) {
                return;
            }
            if (!splash.isFatalShown()) {
                Log.w(TAG, "watchdog: auto-dismissing splash; page ready signal never arrived");
                dismissSplash();
            }
        }
    };

    /**
     * Drives the staged splash text from the real boot phase, so the user sees
     * "Preparing environment..." -> "Extracting runtime..." -> "Starting
     * engine..." instead of a static three-dot screen for up to a minute (the
     * three dots stay on screen; the text is added below them). It only runs
     * while the splash is up, so it costs nothing once the UI is visible.
     */
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed) {
                return;
            }
            updateSplashStatus();
            if (!splashDismissed) {
                handler.postDelayed(this, STATUS_REFRESH_MS);
            }
        }
    };

    /**
     * Runs for the whole activity life: it drives the splash, loads the app on
     * ready, and keeps watching for a post-ready core death.
     */
    private final Runnable pollServer = new Runnable() {
        @Override
        public void run() {
            watchCore();
            if (activityResumed) {
                handler.postDelayed(this, appLoaded
                        ? POLL_READY_INTERVAL_MS : POLL_STARTUP_INTERVAL_MS);
            }
        }
    };

    private void watchCore() {
        String error = CoreRuntimeState.lastError;
        if (error != null) {
            if (CoreRuntimeState.restarting) {
                splash.showTransient(error);
            } else if (!appLoaded || !splashDismissed) {
                splash.showFatalError(error);
            } else {
                // The UI is already on screen; a later core failure must not
                // cover the app again, it is surfaced inside the WebView.
                Log.w(TAG, "post-paint core error: "
                        + SensitiveDataRedactor.redactLine(error));
            }
            return;
        }
        if (CoreRuntimeState.serverUrl != null && webViewHost != null) {
            int generation = CoreRuntimeState.readyGeneration;
            if (!appLoaded || (loadedGeneration > 0 && generation != loadedGeneration)) {
                // Only treat the UI as loaded once the loader actually accepted the
                // URL. loadApp() refuses an un-authenticated URL (no "?token="), and
                // setting appLoaded first would have hidden the splash and left a
                // blank screen with no way back.
                if (!webViewHost.loadApp()) {
                    return;
                }
                appLoaded = true;
                loadedGeneration = generation;
                splash.clearFatal();
                if (generation > 1 || splash.mode() != SplashController.MODE_LOADING) {
                    // Post-restart reload (or recovering from a transient error
                    // overlay): show the loading splash again, with staged text,
                    // so the reload window is explained.
                    splashDismissed = false;
                    lastStatusKey = null;
                    splash.showLoading();
                    splash.showStatus("Reloading interface...");
                }
                appLoadedAtUptime = SystemClock.elapsedRealtime();
                BootMetrics.setPhase(BootMetrics.PHASE_LOADING_UI);
                // Load immediately on serverUrl: the WebView starts fetching the
                // instant the port is listening, in parallel with the splash fade.
                webViewHost.loadApp();
                handler.removeCallbacks(splashWatchdog);
                handler.postDelayed(splashWatchdog, SPLASH_WATCHDOG_MS);
                handler.removeCallbacks(statusTicker);
                if (!splashDismissed) {
                    handler.post(statusTicker);
                }
            }
            return;
        }
        if (!appLoaded && CoreRuntimeState.serverUrl == null && !splash.isFatalShown()
                && bootElapsedMs() > splashDeadlineBudgetMs()) {
            // Route through showFatalError() so the reason is also written to the
            // Termux-readable probe file; the splash alone is not diagnosable.
            showFatalError("core did not start within "
                    + (splashDeadlineBudgetMs() / 1000) + "s");
        }
    }

    /** True boot elapsed time (survives an activity recreation mid-boot). */
    private static long bootElapsedMs() {
        return Math.max(0L, BootMetrics.bootElapsedMs());
    }

    /**
     * Adaptive splash budget in boot-elapsed milliseconds: the measured install
     * phase (userspace + runtime extract, ~12s cold on the target device) plus
     * the core's readiness budget plus a margin, hard-capped by
     * {@link CoreReadyProbe}. Never shorter than the supervisor's own wait, so
     * the splash cannot fail before the supervisor reports a real error.
     */
    private static long splashDeadlineBudgetMs() {
        long install = BootMetrics.cachedInstallElapsedMs();
        long budget = CoreReadyProbe.splashDeadlineBootMs(install);
        return budget > 0 ? budget : SPLASH_TIMEOUT_MS;
    }

    /** Publishes staged progress text for the splash from the current boot phase. */
    private void updateSplashStatus() {
        if (splash == null || splashDismissed || splash.isErrorMode() || splash.isFatalShown()) {
            return;
        }
        int phase = BootMetrics.phase();
        long installMs = CoreRuntimeState.installElapsedMs > 0
                ? CoreRuntimeState.installElapsedMs
                : BootMetrics.cachedInstallElapsedMs();
        // Elapsed seconds of the current phase. Time-to-listen alone is 24-32s
        // on the target device, so a static "Starting engine..." would look
        // frozen for most of the boot; ticking proves the app is still working.
        long phaseSeconds = Math.max(0L, BootMetrics.phaseElapsedMs()) / 1000L;
        String elapsed = phaseSeconds >= 3 ? " (" + phaseSeconds + "s)" : "";
        // First run (and every run after an app update) must unpack the userspace
        // and the runtime before node can start, which is the slow case. Say so up
        // front instead of showing an unexplained multi-second spinner; later boots
        // take the warm path and skip the install entirely.
        boolean firstRun = BootMetrics.wasColdInstall();
        String text;
        switch (phase) {
            case BootMetrics.PHASE_CORE_STARTING:
                text = firstRun
                        ? "First launch: preparing the environment, this can take a while..." + elapsed
                        : "Starting engine..." + elapsed;
                break;
            case BootMetrics.PHASE_LOADING_UI:
                text = "Loading interface..." + elapsed;
                break;
            case BootMetrics.PHASE_RESTARTING:
                long backoff = BootMetrics.restartBackoffMs();
                text = backoff > 0
                        ? "Restarting engine in " + Math.max(1, backoff / 1000) + "s..."
                        : "Restarting engine...";
                break;
            case BootMetrics.PHASE_STARTING:
            default:
                // The install phase has no sub-phase markers of its own, so the
                // measured total is the best progress signal available.
                text = installMs > 0
                        ? "Extracting runtime..." + elapsed + " (took " + (installMs / 1000) + "s)"
                        : "Preparing environment..." + elapsed;
                break;
        }
        String key = phase + "|" + text;
        if (key.equals(lastStatusKey)) {
            return;
        }
        lastStatusKey = key;
        splash.showStatus(text);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A live boot keeps its original clock: if the activity is destroyed and
        // recreated while the core is still starting, the splash must keep
        // measuring from the real boot start instead of restarting the deadline.
        // A session with no live boot behind it (service stopped, app idle for
        // long, then relaunched into this process) is stale and MUST be reset,
        // otherwise the new boot would be judged against an hours-old clock and
        // fail closed immediately with "core did not start".
        if (CoreRuntimeState.serverUrl == null) {
            boolean liveBoot = BootMetrics.activitySessionActive()
                    && BootMetrics.progressElapsedMs() < CoreReadyProbe.supervisorBootBudgetMs();
            if (!liveBoot) {
                BootMetrics.resetSession();
            }
        }
        activeActivity = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        com.dsh.ui.download.FileDownloader.pruneStalePendingBlobsAsync(this);

        themeManager = new ThemeManager(this);
        themeManager.applyToActivity(this);

        // Start CoreServerService BEFORE WebViewHost init so node fork runs in parallel with Chromium startup.
        startCoreService();

        oauthManager = new OAuthManager();
        runtimeManager = new RuntimeManager(this);
        shizukuBridge = new ShizukuBridge(this);
        androidBridge = new AndroidBridge(this, runtimeManager, shizukuBridge, themeManager);
        androidBridge.setActivity(this);
        BootMetrics.markWebviewInitStart();
        webViewHost = new WebViewHost(this, themeManager, oauthManager, androidBridge, this);
        BootMetrics.markWebviewInitDone();
        webViewHost.setListener(this);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(themeManager.getBackgroundColor());
        setContentView(rootLayout);

        rootLayout.addView(webViewHost.getWebView(), ViewParams.full());

        splash = new SplashController(this, themeManager, rootLayout);
        splash.setRetryListener(this::retryCore);
        rootLayout.addView(splash.view(), ViewParams.full());
        splash.loadInitial();

        splash.installExitAnimation(this);

        CoreRuntimeState.readyBudgetMs = CoreReadyProbe.readyBudgetMs();
        Log.i(TAG, "adaptive boot budget: ready=" + CoreRuntimeState.readyBudgetMs
                + "ms install=" + BootMetrics.cachedInstallElapsedMs()
                + "ms splash=" + splashDeadlineBudgetMs() + "ms cold="
                + BootMetrics.wasColdInstall());
        requestAppPermissions();
    }

    private void startCoreService() {
        try {
            CoreServiceLauncher.start(this);
        } catch (Exception e) {
            showFatalError("cannot start core service: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Keep the splash visible and replace it with a readable failure message. */
    public void showFatalError(String message) {
        splash.showFatalError(message);
        // Persist the failure where a desktop shell can read it, without the user
        // having to find the export button on a screen that may be unreadable.
        try {
            com.dsh.ui.webview.NetProbe.record(this, "fatal", false, -1, -1L, message);
        } catch (Throwable ignored) {
        }
    }

    /** Retry re-runs install + start: stop the service, then start it again. */
    private void retryCore() {
        splash.clearFatal();
        appLoaded = false;
        loadedGeneration = -1;
        splashDismissed = false;
        lastStatusKey = null;
        appLoadedAtUptime = 0L;
        // A retry after an explicit failure is a fresh attempt: give it the same
        // adaptive budget instead of charging it against the failed boot.
        BootMetrics.resetSession();
        CoreRuntimeState.installElapsedMs = 0L;
        CoreRuntimeState.readyBudgetMs = CoreReadyProbe.readyBudgetMs();
        splash.showLoading();
        splash.showStatus("Preparing environment...");
        CoreServiceLauncher.stop(this);
        com.dsh.install.BootStateStamp.invalidate(this);
        try {
            java.io.File marker = new java.io.File(runtimeManager.getRuntimeDir(), com.dsh.runtime.RuntimePaths.RUNTIME_MARKER);
            if (marker.exists()) {
                marker.delete();
            }
        } catch (Exception ignored) {
        }
        handler.postDelayed(this::startCoreService, 400);
    }

    /**
     * Page load finished, but the Web UI has NOT necessarily painted yet (the
     * app bundle is large), so the splash is NOT dismissed here. Dismissal is
     * driven by web/patches/startup_ready.js -> AndroidBridge.onUiReady().
     */
    @Override
    public void onPageFinished(String url) {
        Log.i(TAG, "onPageFinished " + SensitiveDataRedactor.safeUrl(url));
        if (url == null || (!url.contains("127.0.0.1") && !url.contains("localhost"))) {
            return;
        }
        if (splashDismissed) {
            return;
        }
        // The page finished loading; give the UI's own ready signal
        // (startup_ready.js -> onUiReady) a short window to win, then reveal the
        // WebView anyway so a missed signal can never leave the splash up.
        WebView wv = getWebView();
        if (wv != null) {
            wv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dismissSplash();
                }
            }, PAINT_FALLBACK_MS);
        } else {
            dismissSplash();
        }
    }

    public void loadDeepSeekUrl(final String url) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (webViewHost != null && webViewHost.getWebView() != null) {
                    appLoaded = true;
                    loadedGeneration = CoreRuntimeState.readyGeneration;
                    splash.clearFatal();
                    webViewHost.getWebView().loadUrl(url);
                }
            }
        });
    }

    /** Idempotent; called when WebView finishes loading the core UI or on watchdog timeout. */
    public void dismissSplash() {
        handler.removeCallbacks(splashWatchdog);
        if (splashDismissed) {
            return;
        }
        splashDismissed = true;
        long paintDelayMs = appLoadedAtUptime > 0
                ? SystemClock.elapsedRealtime() - appLoadedAtUptime : -1L;
        Log.i(TAG, "dismissSplash: fading out splashView (boot elapsed "
                + bootElapsedMs() + "ms, ui_load_to_paint=" + paintDelayMs + "ms)");
        splash.dismiss();
        BootMetrics.markUiPaint();
        // Record what the backend actually answered. A UI that renders but cannot
        // authenticate looks identical to a dead core from inside the WebView; the
        // probe writes the truth to a Termux-readable file for the next report.
        com.dsh.ui.webview.BackendHealthCheck.probeAfterUiLoaded(this);
    }

    public WebView getWebView() {
        return webViewHost != null ? webViewHost.getWebView() : null;
    }

    /** Notifies the WebView when connectivity transitions from offline back to online. */
    public void notifyNetworkOnline() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = getWebView();
                if (wv == null) {
                    return;
                }
                try {
                    wv.evaluateJavascript(
                            "(function(){try{"
                                    + "window.dispatchEvent(new Event('online'));"
                                    + "window.dispatchEvent(new CustomEvent('agy:network-online'));"
                                    + "window.dispatchEvent(new CustomEvent('dsh:network-online'));"
                                    + "}catch(e){}})();",
                            null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public boolean isDesktopMode() {
        return webViewHost != null ? webViewHost.isDesktopMode() : DesktopModePrefs.isDesktopMode(this);
    }

    public void setDesktopMode(boolean enabled) {
        if (webViewHost != null) {
            webViewHost.setDesktopMode(enabled);
        } else {
            DesktopModePrefs.setDesktopMode(this, enabled);
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        if (activeActivity == this) {
            activeActivity = null;
        }
        handler.removeCallbacks(splashWatchdog);
        handler.removeCallbacks(statusTicker);
        if (splash != null) {
            splash.destroy();
        }
        com.dsh.ui.download.FileDownloader.clearPendingTask();
        AppLifecycle.destroy(handler);
        if (androidBridge != null) {
            androidBridge.setActivity(null);
        }
        if (rootLayout != null) {
            rootLayout.removeAllViews();
        }
        if (webViewHost != null) {
            WebView wv = webViewHost.getWebView();
            if (wv != null) {
                try {
                    wv.stopLoading();
                    wv.setWebChromeClient(null);
                    wv.setWebViewClient(null);
                    wv.setDownloadListener(null);
                    wv.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "error destroying webView", e);
                }
            }
        }
        if (pendingPermissionRequest != null) {
            try {
                pendingPermissionRequest.deny();
            } catch (Throwable ignored) {}
            pendingPermissionRequest = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppLifecycle.trimMemory(level, handler, webViewHost);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshTheme();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        handler.removeCallbacks(pollServer);
        handler.removeCallbacks(statusTicker);
        if (webViewHost != null) {
            AppLifecycle.pause(webViewHost.getWebView());
        }
        // Persist the core's session cookie. Without this the cookie lives only in
        // memory, so a cold restart of the process repeats the whole token handshake
        // (and any window where the cookie is missing shows "Reconnecting").
        try {
            android.webkit.CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        activeActivity = this;
        if (webViewHost != null) {
            AppLifecycle.resume(webViewHost.getWebView());
        }
        refreshTheme();
        handler.removeCallbacks(pollServer);
        handler.post(pollServer);
        handler.removeCallbacks(statusTicker);
        if (!splashDismissed && splash != null) {
            // The activity is visible now, so the splash is on screen: arms the
            // staged status line and starts refreshing it from the boot phase.
            splash.startLoadingClock();
            handler.post(statusTicker);
        }
    }

    private void refreshTheme() {
        ThemeRefresher.refresh(this, themeManager, rootLayout, splash, webViewHost);
        if (webViewHost != null && webViewHost.getWebView() != null) {
            String resolved = themeManager != null ? themeManager.getTheme() : "light";
            webViewHost.getWebView().evaluateJavascript(
                    "window.__AG_RESOLVED_THEME__='" + resolved + "'; window.__agOnSystemThemeChanged && window.__agOnSystemThemeChanged();", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (!BackNavigator.handle(webViewHost != null ? webViewHost.getWebView() : null)) {
            super.onBackPressed();
        }
    }

    private void requestAppPermissions() {
        requestStoragePermissions();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.RECORD_AUDIO);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQUEST_MEDIA_PERMISSIONS);
            }
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    android.content.SharedPreferences prefs = getSharedPreferences("dsh_prefs", MODE_PRIVATE);
                    if (prefs.getBoolean("storage_prompted", false)) {
                        return;
                    }
                    prefs.edit().putBoolean("storage_prompted", true).apply();
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE);
            }
        }
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                      WebChromeClient.FileChooserParams fileChooserParams) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        this.filePathCallback = callback;

        Intent chooserIntent = null;

        // 1. Try standard intent built by Chromium WebChromeClient
        if (fileChooserParams != null) {
            try {
                Intent defaultIntent = fileChooserParams.createIntent();
                if (defaultIntent != null) {
                    chooserIntent = Intent.createChooser(defaultIntent, "Select Media / Files");
                }
            } catch (Throwable t) {
                Log.w(TAG, "fileChooserParams.createIntent failed: " + t.getMessage());
            }
        }

        // 2. Fallback to universal ACTION_GET_CONTENT chooser (supports Gallery, Photos, Files)
        if (chooserIntent == null) {
            Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
            contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
            contentIntent.setType("*/*");
            if (fileChooserParams != null) {
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                if (acceptTypes != null && acceptTypes.length > 0) {
                    List<String> validMimes = new ArrayList<>();
                    for (String type : acceptTypes) {
                        if (type != null && type.contains("/")) {
                            validMimes.add(type.trim());
                        }
                    }
                    if (!validMimes.isEmpty()) {
                        contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, validMimes.toArray(new String[0]));
                    }
                }
                if (fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }
            chooserIntent = Intent.createChooser(contentIntent, "Select Media / Files");
        }

        try {
            startActivityForResult(chooserIntent, REQUEST_FILE_CHOOSER);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Chooser failed, fallback to plain GET_CONTENT: " + e.getMessage());
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                if (fileChooserParams != null
                        && fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(fallback, REQUEST_FILE_CHOOSER);
                return true;
            } catch (Throwable ex) {
                Log.e(TAG, "Cannot launch file chooser: " + ex.getMessage());
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                return false;
            }
        }
    }

    public static final int REQUEST_DIR_CHOOSER = 2002;

    public void launchDirectoryPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_DIR_CHOOSER);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot launch directory picker: " + t.getMessage());
            if (webViewHost != null && webViewHost.getWebView() != null) {
                webViewHost.getWebView().evaluateJavascript("window.__onDirectoryPicked && window.__onDirectoryPicked(null);", null);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DIR_CHOOSER) {
            String realPath = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    grantUriPermission(getPackageName(), treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                try {
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                realPath = com.dsh.util.DocumentsContractHelper.resolveTreeUriToPath(this, treeUri);
            }
            if (webViewHost != null && webViewHost.getWebView() != null) {
                final String path = realPath;
                webViewHost.getWebView().post(new Runnable() {
                    @Override
                    public void run() {
                        String jsArg = path != null ? org.json.JSONObject.quote(path) : "null";
                        webViewHost.getWebView().evaluateJavascript(
                                "window.__onDirectoryPicked && window.__onDirectoryPicked(" + jsArg + ");", null);
                    }
                });
            }
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                try {
                    results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                } catch (Throwable t) {
                    Log.w(TAG, "parseResult error: " + t.getMessage());
                }
                if (results == null && resultCode == RESULT_OK && data != null) {
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getDataString() != null) {
                        results = new Uri[]{Uri.parse(data.getDataString())};
                    }
                }
                if (results != null) {
                    for (Uri uri : results) {
                        if (uri != null) {
                            try {
                                grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Throwable ignored) {}
                            try {
                                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    public void requestMediaPermissions(android.webkit.PermissionRequest request, List<String> permissions) {
        if (this.pendingPermissionRequest != null && this.pendingPermissionRequest != request) {
            try {
                this.pendingPermissionRequest.deny();
            } catch (Throwable ignored) {}
        }
        this.pendingPermissionRequest = request;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permissions != null && !permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_MEDIA_PERMISSIONS);
        } else {
            if (request != null) {
                try {
                    request.grant(request.getResources());
                } catch (Throwable t) {
                    Log.w(TAG, "Failed granting media permissions: " + t.getMessage());
                }
            }
            this.pendingPermissionRequest = null;
        }
    }

    public void onMediaPermissionCanceled(android.webkit.PermissionRequest request) {
        if (this.pendingPermissionRequest != null && this.pendingPermissionRequest == request) {
            this.pendingPermissionRequest = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSIONS) {
            if (pendingPermissionRequest != null) {
                boolean allGranted = true;
                if (grantResults != null && grantResults.length > 0) {
                    for (int res : grantResults) {
                        if (res != PackageManager.PERMISSION_GRANTED) {
                            allGranted = false;
                            break;
                        }
                    }
                } else {
                    allGranted = false;
                }
                if (allGranted) {
                    try {
                        pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed granting media permissions: " + t.getMessage());
                    }
                } else {
                    try {
                        pendingPermissionRequest.deny();
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed denying media permissions: " + t.getMessage());
                    }
                }
                pendingPermissionRequest = null;
            }
            return;
        }
        if (requestCode == com.dsh.ui.download.FileDownloader.REQUEST_STORAGE_DOWNLOAD) {
            WebView wv = (webViewHost != null) ? webViewHost.getWebView() : null;
            com.dsh.ui.download.FileDownloader.onPermissionResult(this, wv, requestCode, grantResults);
        }
    }
}
