package com.dsh.ui.webview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;

import com.dsh.core.CoreRuntimeState;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes the loopback core from the app process and records the outcome for offline
 * analysis.
 *
 * <p>The three probes together isolate where authentication breaks:</p>
 * <ol>
 *   <li>{@code root} - does the launcher's tokenized URL still exchange for a cookie
 *       (303 + Set-Cookie), i.e. is the token valid?</li>
 *   <li>{@code aj} - is that cookie accepted by {@code /api} (200) or rejected (401)?</li>
 *   <li>{@code api} - the same request without a cookie, as a control (expect 401).</li>
 * </ol>
 * <p>If {@code aj} succeeds and the page still reports an unauthenticated API, the
 * defect is in how the WebView stores or sends the cookie. If {@code aj} also fails,
 * the cookie itself was never minted or is bound to a different authority.</p>
 */
public final class BackendHealthCheck {

    private static final String TAG = "BackendHealth";
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BackendHealthCheck");
        t.setDaemon(true);
        return t;
    });

    private BackendHealthCheck() {
    }

    /** Runs the probe battery shortly after the UI is shown, once per process boot. */
    public static void probeAfterUiLoaded(Context context) {
        if (context == null || !sScheduled.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        new Handler(Looper.getMainLooper()).postDelayed(() -> POOL.execute(() -> {
            try {
                probeOnce(app, "t+3s");
            } catch (Throwable t) {
                Log.w(TAG, "probe failed: " + t.getMessage());
            }
        }), 3000L);
    }

    /** Probes root, cookie-authenticated API, and anonymous API. */
    public static void probeOnce(Context context, String stage) {
        int port = CoreRuntimeState.httpsPort;
        probeUrl(context, stage + ":root", CoreRuntimeState.serverUrl, null);

        String jarCookie = cookieJar(port);
        NetProbe.record(context, stage + ":cookiejar", jarCookie != null, -1, -1L,
                "origin=http://127.0.0.1:" + port + " cookieCount=" + countCookies(jarCookie)
                        + " raw=" + describeCookieNames(jarCookie));

        // Decisive: replay the WebView's own cookie to /api from native code.
        probeUrl(context, stage + ":apiWithJarCookie",
                "http://127.0.0.1:" + port + "/api", jarCookie);
        // Control: same request without the cookie (documents the expected 401).
        probeUrl(context, stage + ":apiNoCookie",
                "http://127.0.0.1:" + port + "/api", null);
    }

    private static String cookieJar(int port) {
        try {
            String cookie = CookieManager.getInstance().getCookie("http://127.0.0.1:" + port + "/");
            return cookie == null || cookie.isEmpty() ? null : cookie;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countCookies(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String part : cookie.split(";")) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String describeCookieNames(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return "none";
        }
        StringBuilder names = new StringBuilder();
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = eq >= 0 ? trimmed.substring(0, eq) : trimmed;
            if (names.length() > 0) {
                names.append(',');
            }
            // Names only: the values are session credentials.
            names.append(name).append('(').append(eq >= 0 ? trimmed.length() - eq - 1 : 0).append("B)");
        }
        return names.length() == 0 ? "none" : names.toString();
    }

    private static void probeUrl(Context context, String stage, String url, String cookie) {
        if (url == null || url.isEmpty()) {
            NetProbe.record(context, stage, false, -1, -1L, "no url");
            return;
        }
        HttpURLConnection conn = null;
        long started = SystemClock.elapsedRealtime();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            long rtt = SystemClock.elapsedRealtime() - started;
            String detail = "";
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) {
                        detail = new String(buf, 0, n, "UTF-8").replaceAll("\\s+", " ").trim();
                    }
                }
            } catch (Throwable ignored) {
            }
            NetProbe.record(context, stage, code > 0 && code < 400, code, rtt,
                    "sentCookie=" + (cookie != null)
                            + " location=" + conn.getHeaderField("location")
                            + " body=" + detail);
        } catch (Throwable t) {
            long rtt = SystemClock.elapsedRealtime() - started;
            NetProbe.record(context, stage, false, -1, rtt,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
