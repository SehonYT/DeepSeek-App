package com.dsh.core;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Wall-clock startup telemetry across Application/Activity, Java installers,
 * Node process boot, and WebView first paint.
 *
 * <p>Also owns the coarse boot phase that the splash renders as status text:
 * supervisors/services write it on any thread, the UI polls it from the main
 * thread to advance the splash without the splash having to know about the
 * installers.</p>
 */
public final class BootMetrics {

    private static final String TAG = "BootMetrics";

    /** Preparing/verifying the environment and extracting the runtime (pre-fork). */
    public static final int PHASE_STARTING = 1;
    /** The core process is forked; waiting for it to listen on the loopback port. */
    public static final int PHASE_CORE_STARTING = 2;
    /** The port is listening; the WebView is loading and painting. */
    public static final int PHASE_LOADING_UI = 3;
    /** An unexpected exit is being auto-restarted. */
    public static final int PHASE_RESTARTING = 4;

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

    private static volatile int sPhase = PHASE_STARTING;
    private static volatile long sPhaseSinceMs = SystemClock.elapsedRealtime();
    /** True once a MainActivity has claimed the current boot session. */
    private static volatile boolean sActivitySessionClaimed;
    private static volatile long sCacheInstallElapsedMs;
    private static volatile long sRestartBackoffMs;
    private static volatile long sProgressMs = SystemClock.elapsedRealtime();

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
        sCacheInstallElapsedMs = 0L;
        sRestartBackoffMs = 0L;
        sActivitySessionClaimed = true;
        // Force the phase back to the start of a boot (setPhase() is monotonic and
        // would ignore STARTING after LOADING_UI, e.g. on a user Retry).
        sPhase = PHASE_STARTING;
        sPhaseSinceMs = SystemClock.elapsedRealtime();
        sProgressMs = sPhaseSinceMs;
        Log.i(TAG, "boot session reset");
    }

    /**
     * True when an activity already owns this boot session (the process kept
     * booting while the activity was destroyed and recreated). The activity then
     * must not reset the boot clock: the splash deadline is measured from the
     * real boot start, not from the newest activity's onCreate.
     */
    public static boolean activitySessionActive() {
        return sActivitySessionClaimed;
    }

    public static void ensureStart() {
        if (sStartMs <= 0) {
            sStartMs = SystemClock.elapsedRealtime();
        }
    }

    /** Milliseconds since this boot started (0 if no boot is in progress). */
    public static long bootElapsedMs() {
        long start = sStartMs;
        if (start <= 0) {
            return 0L;
        }
        return Math.max(0L, SystemClock.elapsedRealtime() - start);
    }

    /**
     * Publishes the coarse boot phase. Monotonic for forward transitions
     * (STARTING -> CORE_STARTING -> LOADING_UI) so a late service-start marker
     * cannot move the splash backwards; RESTARTING is always honoured.
     */
    public static void setPhase(int phase) {
        int previous = sPhase;
        if (phase == previous) {
            return;
        }
        if (phase < previous && phase != PHASE_RESTARTING && previous != PHASE_RESTARTING) {
            return;
        }
        sPhase = phase;
        sPhaseSinceMs = SystemClock.elapsedRealtime();
        sProgressMs = sPhaseSinceMs;
        Log.i(TAG, "boot phase=" + phase);
    }

    public static int phase() {
        return sPhase;
    }

    /** Milliseconds spent in the current phase. */
    public static long phaseElapsedMs() {
        return Math.max(0L, SystemClock.elapsedRealtime() - sPhaseSinceMs);
    }

    /**
     * Renews the liveness marker. Called on core stdout/stderr output and by
     * every phase change, so the supervisor can tell "slow but progressing"
     * from "dead or hung".
     */
    public static void markProgress() {
        sProgressMs = SystemClock.elapsedRealtime();
    }

    /** Milliseconds since the last observed progress signal. */
    public static long progressElapsedMs() {
        return Math.max(0L, SystemClock.elapsedRealtime() - sProgressMs);
    }

    public static void markServiceStart() {
        ensureStart();
        sServiceStartMs = SystemClock.elapsedRealtime();
    }

    /** Publishes the length of the pre-fork install phase (cold install only). */
    public static void markInstallPhaseDone() {
        long installMs = installElapsedMs();
        if (installMs > 0) {
            sCacheInstallElapsedMs = installMs;
        }
    }

    /** Wall-clock length of the install phase (userspace + runtime extract) of the current boot. */
    public static long installElapsedMs() {
        long fork = sForkMs;
        if (fork <= 0) {
            return 0L;
        }
        long start = sStartMs > 0 ? sStartMs : fork;
        return Math.max(0L, fork - start);
    }

    /** Install-phase length cached at fork time; survives a supervisor restart within one boot. */
    public static long cachedInstallElapsedMs() {
        long cached = sCacheInstallElapsedMs;
        return cached > 0 ? cached : installElapsedMs();
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

    /** Whether the current boot extracts the userspace/runtime (used for the budget bonus). */
    public static boolean wasColdInstall() {
        return sColdInstall;
    }

    public static void markFork() {
        sForkMs = SystemClock.elapsedRealtime();
        setPhase(PHASE_CORE_STARTING);
    }

    /** Re-announces the forking phase for every supervisor attempt (not only the first). */
    public static void markCoreAttemptStart() {
        if (sForkMs <= 0) {
            markFork();
        } else {
            setPhase(PHASE_CORE_STARTING);
        }
        // A fresh fork is a fresh progress signal, so a retry attempt gets its
        // own liveness window even if the previous attempt ended a while ago.
        markProgress();
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
        setPhase(PHASE_LOADING_UI);
    }

    /** Publishes the restart phase with the current backoff, so the splash can count down. */
    public static void markRestarting(long backoffMs) {
        sRestartBackoffMs = Math.max(0L, backoffMs);
        setPhase(PHASE_RESTARTING);
    }

    public static long restartBackoffMs() {
        return sRestartBackoffMs;
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
