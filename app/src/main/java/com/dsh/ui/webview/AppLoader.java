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

    /** @return true when the authenticated URL was loaded. */
    public static boolean load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        if (webView == null) {
            return false;
        }
        if (theme != null) {
            theme.applyToWebView(webView);
        }
        String appUrl = CoreRuntimeState.serverUrl;
        // Defense in depth for the one fatal mistake this loader can make: handing
        // the WebView a bare loopback URL. The core's token authentication accepts
        // only GET /?token=<launch token> (it mints the session cookie there) and
        // answers any other index request with 401, so an un-tokenized load always
        // ends as a rendered-but-unauthenticated page whose WebSocket is refused --
        // the sidebar sits on "Reconnecting" and nothing works. CoreProcessSupervisor
        // no longer publishes such a URL; refuse to invent one here as well.
        if (appUrl == null || appUrl.indexOf("token=") < 0) {
            Log.w(TAG, "refusing to load the core UI without an authenticated URL"
                    + " (waiting for the tokenized entry URL)");
            return false;
        }
        Log.i(TAG, "Loading DeepSeek application URL: "
                + com.dsh.util.SensitiveDataRedactor.safeUrl(appUrl));
        webView.loadUrl(appUrl);
        return true;
    }
}
