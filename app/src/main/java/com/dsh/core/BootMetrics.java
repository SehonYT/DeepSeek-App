package com.dsh.core;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Wall-clock startup telemetry across Application/Activity, Java installers,
 * Node process boot, and WebView first paint.
 */
public final class BootMetrics {

    private static final String TAG = "BootMetrics";

    private static volatile long sStartMs = SystemClock.elapsedRealtime();
    private static volatile long sServiceStartMs;
    private static volatile long sEnvCheckMs;
    private static volatile long sCoreCheckMs;
    private static volatile long sForkMs;
    private static volatile long sWebviewStartMs;
    private static volatile long sWebviewInitMs;
    private static volatile long sReadyMs;
    private static volatile long sUiPaintMs;
    private static volatile boolean sColdInstall;
    private static volatile boolean sEmitted;

    private static volatile long sNodeLine1Ms = -1;
    private static volatile long sNodeModuleLoadMs = -1;
    private static volatile long sNodeAppInitMs = -1;
    private static volatile long sNodeHttpListenMs = -1;

    private BootMetrics() {
    }

    public static synchronized void resetSession() {
        sStartMs = SystemClock.elapsedRealtime();
        sServiceStartMs = 0;
        sEnvCheckMs = 0;
        sCoreCheckMs = 0;
        sForkMs = 0;
        sWebviewStartMs = 0;
        sWebviewInitMs = 0;
        sReadyMs = 0;
        sUiPaintMs = 0;
        sColdInstall = false;
        sEmitted = false;
        sNodeLine1Ms = -1;
        sNodeModuleLoadMs = -1;
        sNodeAppInitMs = -1;
        sNodeHttpListenMs = -1;
    }

    public static void ensureStart() {
        if (sStartMs <= 0) {
            sStartMs = SystemClock.elapsedRealtime();
        }
    }

    public static void markServiceStart() {
        ensureStart();
        sServiceStartMs = SystemClock.elapsedRealtime();
    }

    public static void setEnvCheckMs(long ms) {
        sEnvCheckMs = ms;
    }

    public static void setCoreCheckMs(long ms) {
        sCoreCheckMs = ms;
    }

    public static void setColdInstall(boolean cold) {
        sColdInstall = cold;
    }

    public static void markFork() {
        sForkMs = SystemClock.elapsedRealtime();
    }

    public static void markWebviewInitStart() {
        ensureStart();
        sWebviewStartMs = SystemClock.elapsedRealtime();
    }

    public static void markWebviewInitDone() {
        if (sWebviewStartMs > 0) {
            sWebviewInitMs = SystemClock.elapsedRealtime() - sWebviewStartMs;
        }
    }

    public static void markCoreReady() {
        sReadyMs = SystemClock.elapsedRealtime();
    }

    public static void markUiPaint() {
        if (sUiPaintMs > 0) {
            return;
        }
        sUiPaintMs = SystemClock.elapsedRealtime();
        emitSummary();
    }

    public static void recordNodeStats(String line) {
        if (line == null) {
            return;
        }
        int idx = line.indexOf("[dsh_boot]");
        if (idx < 0) {
            return;
        }
        int brace = line.indexOf('{', idx);
        if (brace < 0) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(line.substring(brace));
            sNodeLine1Ms = obj.optLong("fork_to_line1_ms", -1);
            sNodeModuleLoadMs = obj.optLong("module_load_ms", -1);
            sNodeAppInitMs = obj.optLong("app_init_ms", -1);
            sNodeHttpListenMs = obj.optLong("http_listen_ms", -1);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void emitSummary() {
        if (sEmitted) {
            return;
        }
        sEmitted = true;
        long base = sStartMs > 0 ? sStartMs : SystemClock.elapsedRealtime();
        long readyDelta = sReadyMs > 0 ? (sReadyMs - base) : -1;
        long totalDelta = sUiPaintMs > 0 ? (sUiPaintMs - base) : readyDelta;
        long javaPrepMs = (sForkMs > 0 && sServiceStartMs > 0) ? (sForkMs - sServiceStartMs) : -1;
        long forkToReadyMs = (sReadyMs > 0 && sForkMs > 0) ? (sReadyMs - sForkMs) : -1;

        Log.i(TAG, "[boot_metrics] total_ms=" + totalDelta
                + " ready_ms=" + readyDelta
                + " cold=" + sColdInstall
                + " java_prep_ms=" + javaPrepMs
                + " env_check_ms=" + sEnvCheckMs
                + " core_check_ms=" + sCoreCheckMs
                + " webview_init_ms=" + sWebviewInitMs
                + " fork_to_ready_ms=" + forkToReadyMs
                + " node_line1_ms=" + sNodeLine1Ms
                + " node_module_ms=" + sNodeModuleLoadMs
                + " node_app_init_ms=" + sNodeAppInitMs
                + " node_http_listen_ms=" + sNodeHttpListenMs);
    }
}
