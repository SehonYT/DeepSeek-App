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

        // Exchange the launch token for the core's session cookie natively, install
        // it in the platform jar, and only then load. Relying on the WebView to keep
        // a Set-Cookie riding a 303 redirect is not reliable across WebView builds:
        // when that cookie is dropped the page still renders, but every /api call and
        // the WebSocket come back 401, which the UI shows as a permanent
        // "Reconnecting" sidebar. The tokenized URL is loaded regardless of the
        // outcome, so a failed priming degrades to the previous behaviour.
        final WebView target = webView;
        final String url = appUrl;
        SessionCookiePrimer.prime(context, appUrl, cookieInstalled -> {
            Log.i(TAG, "loading UI (sessionCookiePrimed=" + cookieInstalled + ")");
            // Mint the version-2 operator credential only after the document load has
            // performed its own index exchange: the two credentials share one cookie
            // name, so minting first would be overwritten by the document's own
            // Set-Cookie. The event stream and the /api/remote.mux upgrade need the
            // version-2 peer; without it the client's connection generation never
            // becomes ready and the sidebar stays on "Reconnecting" even though the
            // page itself renders fine. onPageFinished is the only point that is
            // guaranteed to be after that exchange, so the mint is queued there.
            // Mint twice on purpose. The first mint runs from onPageFinished, i.e. after
            // the document's own Set-Cookie has been committed. A second, delayed mint
            // covers the two ways that can still be too early: a WebView that reports
            // the intermediate redirect rather than the final document, and page
            // scripts that open the Gateway socket before the jar write settles. The
            // client reconnects with exponential backoff, so an early socket attempt
            // simply retries; by then the operator credential is present.
            Runnable mint = () -> SessionCookiePrimer.mintApiCredentialAfterLoad(context, url);
            AgyWebViewClient.runAfterNextCoreLoad(mint);
            target.postDelayed(() -> {
                SessionCookiePrimer.mintApiCredentialAfterLoad(context, url);
            }, 2500L);
            try {
                target.loadUrl(url);
            } catch (Throwable t) {
                Log.w(TAG, "loadUrl failed: " + t.getMessage());
                // The queued mint must not fire against some later, unrelated page.
                AgyWebViewClient.runAfterNextCoreLoad(null);
            }
        });
        return true;
    }
}
