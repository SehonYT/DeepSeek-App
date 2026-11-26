package com.dsh.ui.webview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.dsh.core.CoreRuntimeState;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes the loopback core from the app process at the same points the WebView
 * uses it, and records the outcome for offline analysis.
 *
 * <p>This answers the question the UI alone cannot: does the HTTP API answer at
 * all, and with what status? A refused /api request, a wrong token, or a missing
 * cookie all end up as the sidebar's "Reconnecting", and they are indistinguishable
 * from the UI side.</p>
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

    /** Runs one probe shortly after the UI is shown, once per process boot. */
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

    /** Probes the root and the /api prefix and records both outcomes. */
    public static void probeOnce(Context context, String stage) {
        probeUrl(context, stage + ":root", CoreRuntimeState.serverUrl);
        int port = CoreRuntimeState.httpsPort;
        probeUrl(context, stage + ":api", "http://127.0.0.1:" + port + "/api/health");
    }

    private static void probeUrl(Context context, String stage, String url) {
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
            int code = conn.getResponseCode();
            long rtt = SystemClock.elapsedRealtime() - started;
            String detail = "";
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) {
                        detail = new String(buf, 0, n, "UTF-8");
                    }
                }
            } catch (Throwable ignored) {
            }
            NetProbe.record(context, stage, code > 0 && code < 500, code, rtt,
                    "location=" + conn.getHeaderField("location") + " " + detail);
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
