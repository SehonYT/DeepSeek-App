package com.dsh.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dsh.oauth.OAuthManager;
import com.dsh.ui.MainActivity;
import com.dsh.util.SensitiveDataRedactor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dispatches external browser URLs and OAuth flows detected from the core's
 * stdout stream or explicit ACTION_OPEN_URL broadcasts.
 */
public final class OpenUrlWatcher {

    private static final String TAG = "OpenUrlWatcher";
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w\\-\\.\\~:\\?\\#\\[\\]\\@\\!\\$\\&\\'\\(\\)\\*\\+,;=%/]+");

    private final Context appContext;
    private final OAuthManager oauth = new OAuthManager();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Runnable readyNotifier;

    private volatile String lastDispatchedUrl = "";
    private volatile long lastDispatchedTime = 0;

    public void setReadyNotifier(Runnable notifier) {
        this.readyNotifier = notifier;
    }

    private void notifyReadySignal() {
        Runnable r = this.readyNotifier;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private final BroadcastReceiver openUrlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String url = intent.getStringExtra("url");
            if (url != null && !url.trim().isEmpty()) {
                Log.i(TAG, "broadcast ACTION_OPEN_URL received: "
                        + SensitiveDataRedactor.safeUrl(url));
                dispatchIfValid(url);
            }
        }
    };

    public OpenUrlWatcher(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void start() {
        try {
            IntentFilter filter = new IntentFilter("com.dsh.ACTION_OPEN_URL");
            // The /system/bin/am shim is launched by the app-owned core process.
            // Keep delivery inside this signed app UID; external apps cannot spoof it.
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(openUrlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(openUrlReceiver, filter);
            }
            Log.i(TAG, "registered protected ACTION_OPEN_URL receiver");
        } catch (Throwable t) {
            Log.w(TAG, "failed to register ACTION_OPEN_URL receiver: " + t.getMessage());
        }
    }

    public void stop() {
        try {
            appContext.unregisterReceiver(openUrlReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** Detects stdout OPEN_URL prefixes or auth URLs; called by the stream reader. */
    public void handleStreamLine(String line) {
        if (line == null) {
            return;
        }
        if (line.contains("[dsh_boot]")) {
            BootMetrics.recordNodeStats(line);
            return;
        }
        int port = CoreRuntimeState.httpsPort;
        String expectedPrefix = "http://127.0.0.1:" + port;
        if (line.startsWith("DSH_HTTP_BOUND ")) {
            CoreRuntimeState.httpBoundSeen = true;
            notifyReadySignal();
            return;
        }
        if (line.startsWith("DSH_WEB_READY ") || line.contains("dsh web:") || line.contains(expectedPrefix)) {
            Matcher m = URL_PATTERN.matcher(line);
            if (m.find()) {
                String candidate = m.group();
                if (candidate.startsWith(expectedPrefix)) {
                    Log.i(TAG, "detected DeepSeek Web URL: " + SensitiveDataRedactor.safeUrl(candidate));
                    CoreRuntimeState.detectedWebUrl = candidate;
                    notifyReadySignal();
                    String current = CoreRuntimeState.serverUrl;
                    if (current != null && !candidate.equals(current)) {
                        CoreRuntimeState.serverUrl = candidate;
                        CoreRuntimeState.readyGeneration++;
                        if (MainActivity.activeActivity != null) {
                            MainActivity.activeActivity.loadDeepSeekUrl(candidate);
                        }
                    }
                    return;
                }
            }
        }
        for (String prefix : new String[]{"DSH_OPEN_URL=", "DEEPSEEK_OPEN_URL=", "AGY_OPEN_URL=", "ANTIGRAVITY_OPEN_URL:", "ANTIGRAVITY_OPEN_URL="}) {
            int at = line.indexOf(prefix);
            if (at >= 0) {
                String url = line.substring(at + prefix.length()).trim();
                dispatchIfValid(url);
                return;
            }
        }
        // Fallback: detect OAuth or login URLs emitted by language_server / openBrowser
        if (line.contains("accounts.google.com")
                || (line.contains("/auth/callback") && line.contains("http"))) {
            Matcher m = URL_PATTERN.matcher(line);
            while (m.find()) {
                String candidate = m.group();
                if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                    // Ignore internal backend API endpoints and userinfo requests
                    if (candidate.contains("googleapis.com/oauth2/v2/userinfo")
                            || candidate.contains("googleapis.com/auth")
                            || candidate.contains("daily-cloudcode-pa.googleapis.com")) {
                        continue;
                    }
                    if (candidate.contains("accounts.google.com")
                            || candidate.contains("/auth/callback")
                            || candidate.contains("antigravity.google/auth")) {
                        Log.i(TAG, "stream regex detected auth URL: "
                                + SensitiveDataRedactor.safeUrl(candidate));
                        dispatchIfValid(candidate);
                    }
                }
            }
        }
    }

    public void dispatchIfValid(String url) {
        if (url == null) return;
        url = url.trim();
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) return;

        // Never dispatch internal Google API endpoints to the user's browser
        if (url.contains("googleapis.com/oauth2/v2/userinfo")
                || url.contains("googleapis.com/auth")
                || url.contains("daily-cloudcode-pa.googleapis.com")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (url.equals(lastDispatchedUrl) && (now - lastDispatchedTime) < 1500) {
            return;
        }
        lastDispatchedUrl = url;
        lastDispatchedTime = now;
        Log.i(TAG, "dispatching external URL: " + SensitiveDataRedactor.safeUrl(url));
        openUrl(url);
    }

    private void openUrl(final String url) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Context target = (MainActivity.activeActivity != null)
                        ? MainActivity.activeActivity
                        : appContext;
                boolean opened = oauth.openInBrowser(target, url);
                if (!opened) {
                    Log.w(TAG, "no browser accepted the url: "
                            + SensitiveDataRedactor.safeUrl(url));
                }
            }
        });
    }
}
