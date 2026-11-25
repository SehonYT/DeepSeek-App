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
    public static volatile int httpsPort = RuntimeConfig.DEFAULT_HTTPS_PORT;
    public static volatile String csrfToken = null;

    /** Fail-closed startup error, surfaced to the UI instead of a hung splash. */
    public static volatile String lastError = null;
    /** True while an unexpected exit is being auto-restarted (transient, not fatal). */
    public static volatile boolean restarting = false;
    /** Bumped on every successful ready; the UI reloads the app when it changes. */
    public static volatile int readyGeneration = 0;
}
