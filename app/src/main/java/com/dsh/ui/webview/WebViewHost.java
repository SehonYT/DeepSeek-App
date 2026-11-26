package com.dsh.ui.webview;

import android.content.Context;
import android.webkit.WebView;

import com.dsh.bridge.AndroidBridge;
import com.dsh.oauth.OAuthManager;
import com.dsh.theme.ThemeManager;
import com.dsh.util.CoreUrl;

/**
 * Builds and configures the single WebView that hosts *our* patched UI
 * (assets/web/index.html). The HTML is injected inline but based at the core's
 * https origin. The native object is exposed as `Android`.
 */
public final class WebViewHost {

    public static final String DESKTOP_UA = WebSettingsFactory.DESKTOP_UA;

    public interface Listener {
        void onPageFinished(String url);

        /**
         * The WebView renderer died. The host must discard the dead view and
         * recreate the surface; continuing to use it leaves an unresponsive UI.
         */
        void onRendererGone();
    }

    private final Context context;
    private final ThemeManager theme;
    private final OAuthManager oauth;
    private final AndroidBridge bridge;
    private final WebView webView;
    private Listener listener;

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge, AgyWebChromeClient.FileChooserCallback fileChooserCallback) {
        this.context = context;
        this.theme = theme;
        this.oauth = oauth;
        this.bridge = bridge;
        this.webView = WebViewFactory.create(context, bridge, theme);
        this.webView.setWebChromeClient(new AgyWebChromeClient(
                new PopupWindowHandler(context, oauth), fileChooserCallback));
        this.webView.setWebViewClient(new AgyWebViewClient(
                context, oauth, new LocalAssetServer(), bridge, new Listener() {
            @Override
            public void onPageFinished(String url) {
                if (WebViewHost.this.listener != null) {
                    WebViewHost.this.listener.onPageFinished(url);
                }
            }

            @Override
            public void onRendererGone() {
                if (WebViewHost.this.listener != null) {
                    WebViewHost.this.listener.onRendererGone();
                }
            }
        }));
        this.webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                com.dsh.ui.MainActivity activity = com.dsh.ui.MainActivity.activeActivity;
                // activeActivity is a static that outlives this WebView: after the
                // renderer died and the host was replaced, the old view (kept alive by
                // its own client/listener chains) could still deliver a download to
                // whatever activity is current. Only a live, non-destroyed view may
                // hand its own URL to the downloader.
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                com.dsh.ui.download.FileDownloader.handleDownload(
                        activity, webView, url,
                        userAgent, contentDisposition, mimetype, contentLength);
            }
        });
    }

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge) {
        this(context, theme, oauth, bridge, null);
    }

    public WebView getWebView() {
        return webView;
    }

    /** Releases WebView renderer caches; safe to call repeatedly. */
    public void freeMemory() {
        WebViewLifecycle.freeMemory(webView);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static String startUrl(int port) {
        return CoreUrl.startUrl(port);
    }

    public static String startUrl(int port, String csrfToken) {
        return CoreUrl.startUrl(port, csrfToken);
    }

    public boolean isDesktopMode() {
        return DesktopModePrefs.isDesktopMode(context);
    }

    public void setDesktopMode(boolean enabled) {
        DesktopModePrefs.setDesktopMode(context, enabled);
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebSettingsFactory.applyDesktopMode(webView, enabled);
                    webView.reload();
                } catch (Throwable t) {
                    android.util.Log.w("WebViewHost", "desktop mode reload failed: " + t.getMessage());
                }
            }
        });
    }

    /** Loads our asset UI with the app config injected ahead of every app script. */
    /** @return true when the authenticated URL was handed to the WebView. */
    public boolean loadApp() {
        return AppLoader.load(context, webView, theme, bridge);
    }
}
