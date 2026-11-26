package com.dsh.ui.webview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exchanges the core's launch token for its session cookie and installs that cookie
 * into the platform cookie jar before the WebView loads the UI.
 *
 * <p>Why this exists: the core mints its session cookie in the {@code Set-Cookie}
 * header of a {@code 303} response to {@code GET /?token=...}, and every later API
 * call and the {@code /api} WebSocket authenticate with that cookie. Relying on the
 * WebView to persist a {@code Set-Cookie} that rides a redirect leaves the whole UI
 * dependent on redirect-cookie handling that varies by WebView build; when it is not
 * stored the page renders but every API call and the socket are refused, which the
 * user sees as a permanent "Reconnecting" sidebar.</p>
 *
 * <p>Doing the exchange natively and writing the cookie ourselves makes the flow
 * deterministic. The tokenized URL is still what the WebView loads, so a jar write
 * that silently fails still recovers: the second request carries the cookie, the core
 * answers {@code 303 -> ./} as an authenticated index request, and the UI loads.</p>
 */
public final class SessionCookiePrimer {

    private static final String TAG = "SessionCookiePrimer";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SessionCookiePrimer");
        t.setDaemon(true);
        return t;
    });

    /** Called on the main thread once priming finished (success or not). */
    public interface Callback {
        void onPrimed(boolean cookieInstalled);
    }

    private SessionCookiePrimer() {
    }

    /** True when this URL carries the core's launch token. */
    public static boolean hasLaunchToken(String url) {
        return url != null && url.indexOf("token=") >= 0;
    }

    /**
     * Performs the token exchange off the main thread, stores the resulting cookie in
     * the platform jar, then invokes {@code callback} on the main thread.
     *
     * @param context   any context; the application context is used.
     * @param tokenizedUrl the tokenized entry URL published by the core.
     * @param callback  always invoked exactly once on the main thread.
     */
    public static void prime(Context context, String tokenizedUrl, Callback callback) {
        final Context app = context == null ? null
                : (context.getApplicationContext() != null ? context.getApplicationContext() : context);
        final Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            boolean installed = false;
            try {
                installed = exchange(app, tokenizedUrl);
            } catch (Throwable t) {
                Log.w(TAG, "token exchange failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
            final boolean result = installed;
            main.post(() -> {
                try {
                    callback.onPrimed(result);
                } catch (Throwable ignored) {
                }
            });
        });
    }

    private static boolean exchange(Context context, String tokenizedUrl)
            throws java.io.IOException {
        if (tokenizedUrl == null || tokenizedUrl.isEmpty()) {
            return false;
        }
        String origin = originOf(tokenizedUrl);
        if (origin == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(tokenizedUrl).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            // The cookie is on the 303 itself; following it would land on "./" which
            // is served without repeating Set-Cookie.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            Map<String, List<String>> headers = conn.getHeaderFields();
            int stored = 0;
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String name = entry.getKey();
                    if (name == null || !"set-cookie".equalsIgnoreCase(name)) {
                        continue;
                    }
                    for (String value : entry.getValue()) {
                        stored += installCookie(origin, value);
                    }
                }
            }
            Log.i(TAG, "token exchange status=" + code + " cookiesInstalled=" + stored);
            return stored > 0;
        } finally {
            if (conn != null) {
                try {
                    InputStream stream = conn.getErrorStream();
                    if (stream != null) {
                        stream.close();
                    }
                } catch (Throwable ignored) {
                }
                conn.disconnect();
            }
        }
    }

    /**
     * Writes one Set-Cookie value for the core origin into the platform jar.
     * @return 1 when the jar accepted it, 0 otherwise.
     */
    private static int installCookie(String origin, String setCookieValue) {
        if (setCookieValue == null || setCookieValue.isEmpty()) {
            return 0;
        }
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            // The jar wants "name=value; attributes"; it accepts the header verbatim.
            manager.setCookie(origin, setCookieValue);
            manager.flush();
            String check = manager.getCookie(origin);
            boolean present = check != null && !check.isEmpty();
            Log.i(TAG, "installed session cookie; jarNowHasCookie=" + present);
            return present ? 1 : 0;
        } catch (Throwable t) {
            Log.w(TAG, "cannot install cookie: " + t.getMessage());
            return 0;
        }
    }

    /** scheme://host:port of a URL, or null when it cannot be parsed. */
    private static String originOf(String url) throws java.net.MalformedURLException {
        try {
            URL parsed = new URL(url);
            String protocol = parsed.getProtocol();
            int port = parsed.getPort();
            StringBuilder origin = new StringBuilder()
                    .append(protocol).append("://").append(parsed.getHost());
            if (port >= 0) {
                origin.append(':').append(port);
            }
            return origin.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
