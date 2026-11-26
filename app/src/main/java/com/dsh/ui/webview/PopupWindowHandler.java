package com.dsh.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Message;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dsh.oauth.OAuthManager;
import com.dsh.util.SensitiveDataRedactor;

/**
 * Handles {@code window.open}: the popup is a fresh WebView whose navigations
 * are intercepted for OAuth (Google blocks WebView login).
 */
public final class PopupWindowHandler {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;

    public PopupWindowHandler(Context context, OAuthManager oauth) {
        this.context = context;
        this.oauth = oauth;
    }

    public boolean handle(WebView parent, boolean isDialog, boolean isUserGesture,
                          Message resultMsg) {
        Log.i(TAG, "onCreateWindow isDialog=" + isDialog + " isUserGesture=" + isUserGesture);
        final WebView popup = new WebView(context);
        popup.getSettings().setJavaScriptEnabled(true);
        final boolean[] destroyed = new boolean[1];
        popup.setWebViewClient(new WebViewClient() {
            /**
             * Opens the popup URL in the system browser and disposes the temporary
             * popup exactly once. The previous code destroyed the WebView from inside
             * its own callback without detaching it from its parent or clearing these
             * listeners, so further callbacks (a redirect chain fires several) ran
             * against a destroyed WebView, which throws on the UI thread.
             */
            private boolean openAndDispose(String url) {
                if (url == null || url.isEmpty() || url.equals("about:blank")) {
                    return false;
                }
                if (destroyed[0]) {
                    return true;
                }
                destroyed[0] = true;
                oauth.openInBrowser(context, url);
                try {
                    popup.stopLoading();
                    popup.setWebViewClient(null);
                    android.view.ViewParent parent = popup.getParent();
                    if (parent instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) parent).removeView(popup);
                    }
                    popup.destroy();
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                Log.i(TAG, "popup shouldOverrideUrlLoading url="
                        + SensitiveDataRedactor.safeUrl(url));
                return openAndDispose(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Log.i(TAG, "popup shouldOverrideUrlLoading(deprecated) url="
                        + SensitiveDataRedactor.safeUrl(url));
                return openAndDispose(url);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (openAndDispose(url)) {
                    Log.i(TAG, "popup onPageStarted opened in browser url="
                            + SensitiveDataRedactor.safeUrl(url));
                }
            }
        });
        if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
            resultMsg.sendToTarget();
        }
        return true;
    }
}
