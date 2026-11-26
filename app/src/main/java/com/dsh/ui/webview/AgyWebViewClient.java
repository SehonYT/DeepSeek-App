package com.dsh.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dsh.bridge.AndroidBridge;
import com.dsh.oauth.OAuthManager;
import com.dsh.util.SensitiveDataRedactor;
import com.dsh.util.TrustedCoreOrigin;

/** WebView client: OAuth interception, loopback TLS, and static asset serving. */
public final class AgyWebViewClient extends WebViewClient {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;
    private final LocalAssetServer assetServer;
    private final AndroidBridge bridge;
    private final WebViewHost.Listener listener;
    private volatile boolean mainFrameError = false;

    public AgyWebViewClient(Context context, OAuthManager oauth, LocalAssetServer assetServer,
                            AndroidBridge bridge, WebViewHost.Listener listener) {
        this.context = context;
        this.oauth = oauth;
        this.assetServer = assetServer;
        this.bridge = bridge;
        this.listener = listener;
    }

    @Override
    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
        if (req != null && req.isForMainFrame()) {
            mainFrameError = true;
        }
        if (req != null && req.getUrl() != null) {
            Log.w(TAG, "Resource load error: "
                    + SensitiveDataRedactor.safeUrl(req.getUrl().toString()) + " err="
                    + (err != null ? err.getDescription() : "unknown"));
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return true;
        }
        Uri uri = request.getUrl();
        Log.i(TAG, "shouldOverrideUrlLoading url="
                + SensitiveDataRedactor.safeUrl(uri.toString()));
        if (!request.isForMainFrame()) {
            // The web UI intentionally embeds HTTPS previews. Keep those working,
            // while refusing file/javascript/intent and other non-web subframes.
            String scheme = uri.getScheme();
            return !"https".equalsIgnoreCase(scheme)
                    && !"about".equalsIgnoreCase(scheme);
        }
        return handleNavigation(v, uri.toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView v, String url) {
        Log.i(TAG, "shouldOverrideUrlLoading(deprecated) url="
                + SensitiveDataRedactor.safeUrl(url));
        return handleNavigation(v, url);
    }

    private boolean handleNavigation(WebView v, String url) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    // Do not open internal backend API endpoints in external browser
                    if (url.contains("googleapis.com/oauth2/v2/userinfo")
                            || url.contains("googleapis.com/auth")) {
                        return true;
                    }
                    Log.i(TAG, "non-core navigation -> external browser: "
                            + SensitiveDataRedactor.safeUrl(url));
                    oauth.openInBrowser(context, url);
                }
                // Never fall through to render a non-core top-level document in
                // the WebView if no browser handler is installed.
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot parse uri: " + SensitiveDataRedactor.safeUrl(url), e);
            return true;
        }
        return oauth.intercept(context, url);
    }

    /**
     * Server-side (3xx) redirects do not always reach shouldOverrideUrlLoading;
     * only the exact core origin may remain in the bridge-enabled WebView.
     */
    @Override
    public void onPageStarted(WebView v, String url, Bitmap favicon) {
        mainFrameError = false;
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                Log.i(TAG, "onPageStarted intercepted non-core url: "
                        + SensitiveDataRedactor.safeUrl(url));
                v.stopLoading();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (!url.contains("googleapis.com/oauth2/v2/userinfo")
                            && !url.contains("googleapis.com/auth")) {
                        oauth.openInBrowser(context, url);
                    }
                }
                return;
            }
        } catch (Exception ignored) {
            v.stopLoading();
            return;
        }
        if (oauth.intercept(context, url)) {
            Log.i(TAG, "onPageStarted stopped auth url="
                    + SensitiveDataRedactor.safeUrl(url));
            v.stopLoading();
        }
    }

    @Override
    public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
        String rawUrl = (error != null) ? error.getUrl() : null;
        if (rawUrl != null) {
            try {
                Uri uri = Uri.parse(rawUrl);
                if (TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                    Log.i(TAG, "accepting local loopback TLS cert for " + SensitiveDataRedactor.safeUrl(rawUrl));
                    handler.proceed();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "rejecting TLS error in WebView: "
                + SensitiveDataRedactor.safeUrl(rawUrl != null ? rawUrl : ""));
        handler.cancel();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        return assetServer.intercept(context, request.getUrl(), request.getMethod(),
                bridge.getEnginePort());
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
        if (url == null) {
            return null;
        }
        try {
            return assetServer.intercept(context, Uri.parse(url), "GET", bridge.getEnginePort());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onPageFinished(WebView v, String url) {
        if (mainFrameError) {
            Log.w(TAG, "skipping onPageFinished for failed main frame: "
                    + SensitiveDataRedactor.safeUrl(url));
            return;
        }
        if (v != null && url != null) {
            try {
                Uri uri = Uri.parse(url);
                if (TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                    injectTrustedHelpers(v);
                }
            } catch (Exception ignored) {
            }
        }
        if (listener != null) {
            listener.onPageFinished(url);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        boolean didCrash = detail != null && detail.didCrash();
        Log.e(TAG, "WebView renderer process gone (didCrash=" + didCrash + ")");
        if (listener != null) {
            listener.onRenderProcessGone(didCrash);
        }
        return true;
    }

    private void injectTrustedHelpers(WebView v) {
        String tokenLiteral = org.json.JSONObject.quote(bridge.getBlobBridgeToken());
        String js = "(function(){"
                + "if(window.__DSH_ANDROID_HELPERS__)return;"
                + "window.__DSH_ANDROID_HELPERS__=true;"
                + "window.__AG_NATIVE_BRIDGE_TOKEN__=" + tokenLiteral + ";"
                + "window.AndroidBridge=window.Android;"
                + "function b64(s){var bin=atob(s),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}"
                + "function findTarget(){var el=document.activeElement;if(el&&(el.isContentEditable||el.tagName==='TEXTAREA'||el.tagName==='INPUT'))return el;return document.querySelector('[contenteditable=\"true\"],textarea')||document.body;}"
                + "function findInput(){var l=document.querySelectorAll('input[type=\"file\"]');for(var i=l.length-1;i>=0;i--){var a=(l[i].getAttribute('accept')||'').toLowerCase();if(!a||a.indexOf('image')>=0||a.indexOf('*/*')>=0)return l[i];}return l.length?l[l.length-1]:null;}"
                + "window.__agPasteImage=function(base64,mime,name){"
                + "if(!base64)return false;try{var dt=new DataTransfer();dt.items.add(new File([b64(base64)],name||'pasted_image.png',{type:mime||'image/png',lastModified:Date.now()}));"
                + "var t=findTarget();if(t&&t.focus)try{t.focus();}catch(e){}"
                + "var pe=typeof ClipboardEvent==='function'?new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt}):new Event('paste',{bubbles:true,cancelable:true});"
                + "pe.__agSyntheticPaste=true;try{if(!pe.clipboardData||!pe.clipboardData.files.length)Object.defineProperty(pe,'clipboardData',{configurable:true,get:function(){return dt;}});}catch(e){}"
                + "if(!t.dispatchEvent(pe)||pe.defaultPrevented)return true;"
                + "var de=typeof DragEvent==='function'?new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:dt}):new Event('drop',{bubbles:true,cancelable:true});"
                + "try{if(!de.dataTransfer||!de.dataTransfer.files.length)Object.defineProperty(de,'dataTransfer',{configurable:true,get:function(){return dt;}});}catch(e){}"
                + "if(!t.dispatchEvent(de)||de.defaultPrevented)return true;"
                + "var fi=findInput();if(fi){fi.files=dt.files;fi.dispatchEvent(new Event('change',{bubbles:true}));return true;}"
                + "}catch(e){}return false;};"
                + "document.addEventListener('paste',function(e){"
                + "if(!e||e.__agSyntheticPaste)return;var cd=e.clipboardData;"
                + "if(cd){if(cd.files&&cd.files.length>0)return;var txt='';try{txt=cd.getData('text/plain')||'';}catch(ig){}if(txt.length>0)return;}"
                + "var tok=window.__AG_NATIVE_BRIDGE_TOKEN__,b=window.Android;if(!tok||!b)return;"
                + "if(typeof b.hasClipboardImage==='function'&&typeof b.requestClipboardImagePaste==='function'){"
                + "if(!b.hasClipboardImage(String(tok)))return;e.preventDefault();if(e.stopImmediatePropagation)e.stopImmediatePropagation();b.requestClipboardImagePaste(String(tok));}"
                + "},true);"
                + "if(!window.__DSH_CONN_WATCHDOG__&&typeof console!=='undefined'&&typeof console.warn==='function'){"
                + "window.__DSH_CONN_WATCHDOG__=true;"
                + "var origWarn=console.warn.bind(console);var timeouts=0;"
                + "console.warn=function(){"
                + "try{var msg=arguments.length?String(arguments[0]):'';"
                + "if(msg.indexOf('[connection]')>=0){"
                + "if(msg.indexOf('not ready within')>=0||msg.indexOf('connection lost, retry #')>=0){"
                + "timeouts++;"
                + "if(timeouts>=2&&(typeof navigator==='undefined'||navigator.onLine!==false)){"
                + "timeouts=0;var tok=window.__AG_NATIVE_BRIDGE_TOKEN__,b=window.Android;"
                + "if(tok&&b&&typeof b.reportConnectionStall==='function'){b.reportConnectionStall(String(tok));}"
                + "}"
                + "}else if(msg.indexOf('established')>=0||msg.indexOf('ready')>=0&&msg.indexOf('not ready')<0){timeouts=0;}"
                + "}}catch(ignored){}"
                + "return origWarn.apply(console,arguments);};}"
                + "})();";
        v.evaluateJavascript(js, null);
    }
}
