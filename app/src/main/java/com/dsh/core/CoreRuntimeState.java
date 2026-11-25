package com.dsh.core;

import com.dsh.runtime.RuntimeConfig;

/** Cross-component state shared by the service, the bridge and the UI. */
public final class CoreRuntimeState {

    private CoreRuntimeState() {
    }

    /** Published once the core answers on its loopback port. */
    public static volatile String serverUrl = null;
    /** Exact tokenized web URL emitted on stdout by `dsh web` (e.g. http://127.0.0.1:45257/?token=...). */
    public static volatile String detectedWebUrl = null;
    public static volatile boolean httpBoundSeen = false;
    public static volatile int httpsPort = RuntimeConfig.DEFAULT_HTTPS_PORT;
    public static volatile String csrfToken = null;

    /** Fail-closed startup error, surfaced to the UI instead of a hung splash. */
    public static volatile String lastError = null;
    /** True while an unexpected exit is being auto-restarted (transient, not fatal). */
    public static volatile boolean restarting = false;
    /** Bumped on every successful ready; the UI reloads the app when it changes. */
    public static volatile int readyGeneration = 0;

    /**
     * Total readiness budget for this boot, in milliseconds, decided by
     * {@link CoreReadyProbe} (base + cold-install bonus). The splash uses the same
     * number so it can never time out before the supervisor does.
     */
    public static volatile long readyBudgetMs = CoreReadyProbe.READY_TIMEOUT_MS;
    /**
     * Install phase (userspace + runtime extract) length of this boot, in
     * milliseconds. Written by the supervisor right after the pre-fork install
     * phase, read by the UI to size the splash deadline and show progress.
     */
    public static volatile long installElapsedMs = 0L;
}
