package com.dsh.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.dsh.runtime.RuntimeManager;
import com.dsh.shell.ShizukuBridge;
import com.dsh.theme.ThemeManager;
import com.dsh.ui.MainActivity;

import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private final Context appContext;
    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
    private volatile WeakReference<MainActivity> activityRef;
    private volatile long lastStallReportMs = 0L;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, ThemeManager theme) {
        this.appContext = context.getApplicationContext();
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(this.appContext, runtime, engine, shizuku, themeBridge);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady(String token) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiReady();
        }
    }

    @JavascriptInterface
    public void openDirectoryPicker() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.launchDirectoryPicker();
                }
            });
        }
    }

    @JavascriptInterface
    public void onUiError(String token, final String errorMessage) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiError(errorMessage);
        }
    }

    @JavascriptInterface
    public void reportConnectionStall(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStallReportMs < 25_000L) {
            return;
        }
        lastStallReportMs = now;
        com.dsh.core.CoreServiceLauncher.restart(appContext, "Connection handshake timed out; restarting core...");
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        ui.setDesktopMode(enabled);
    }

    // Native-only helpers used while preparing the trusted app document.
    public boolean isEngineReady() {
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    public String getConfig() {
        return config.get();
    }

    /** Native-only token inserted into the trusted top-level document. */
    public String getBlobBridgeToken() {
        return blobBridgeToken;
    }

    /** Saves a downloaded blob only for scripts holding the top-level document token. */
    @JavascriptInterface
    public void saveBlob(String token, String base64Data, String filename, String mimeType) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            com.dsh.ui.download.FileDownloader.handleBlobData(
                    act, base64Data, filename, mimeType);
        }
    }

    /** Checks clipboard MIME metadata without reading clip contents on non-image clips. */
    @JavascriptInterface
    public boolean hasClipboardImage(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        return com.dsh.ui.webview.ClipboardImageReader.hasClipboardImage(appContext);
    }

    /** Reads clipboard image off the UI thread and dispatches it into the WebView composer. */
    @JavascriptInterface
    public void requestClipboardImagePaste(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return;
        }
        final android.webkit.WebView wv = act.getWebView();
        if (wv == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                com.dsh.ui.webview.ClipboardImageReader.ImageData img =
                        com.dsh.ui.webview.ClipboardImageReader.readFromClipboard(appContext);
                if (img != null && !img.base64.isEmpty()) {
                    com.dsh.ui.webview.AgyWebView.dispatchImageToWebView(wv, img);
                }
            }
        }, "DshClipboardImagePaste");
        t.setDaemon(true);
        t.start();
    }

    /** Synchronous clipboard image fallback returning JSON. */
    @JavascriptInterface
    public String readClipboardImage(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return "";
        }
        com.dsh.ui.webview.ClipboardImageReader.ImageData img =
                com.dsh.ui.webview.ClipboardImageReader.readFromClipboard(appContext);
        return img != null ? img.toJson().toString() : "";
    }

    /** Posts a clickable Android notification when an agent event completes while backgrounded. */
    @JavascriptInterface
    public void postAgentNotification(String token, String tag, String title, String message) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        com.dsh.gateway.handlers.NotificationHandler.postAgentAlert(appContext, tag, title, message);
    }

    private boolean matchesNativeBridgeToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] expected = blobBridgeToken.getBytes(Charset.forName("UTF-8"));
            byte[] actual = candidate.getBytes(Charset.forName("UTF-8"));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createBlobBridgeToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] encoded = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            encoded[i * 2] = hex[value >>> 4];
            encoded[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }
}
