package com.dsh.ui.webview;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import com.dsh.bridge.AndroidBridge;
import com.dsh.core.CoreRuntimeState;
import com.dsh.theme.ThemeManager;

/**
 * Loads the DeepSeek Web UI into the WebView.
 */
public final class AppLoader {

    private static final String TAG = "AppLoader";

    private AppLoader() {
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        if (webView == null) {
            return;
        }
        if (theme != null) {
            theme.applyToWebView(webView);
        }
        String appUrl = CoreRuntimeState.serverUrl;
        int port = bridge != null ? bridge.getEnginePort() : CoreRuntimeState.httpsPort;
        if (appUrl == null || appUrl.trim().isEmpty()) {
            appUrl = "http://127.0.0.1:" + port + "/";
        }
        Log.i(TAG, "Loading DeepSeek application URL: " + appUrl);
        webView.loadUrl(appUrl);
    }
}
