package com.dsh.runtime;

import android.content.Context;
import android.util.Log;

import com.dsh.core.CoreEnvironment;
import com.dsh.core.CoreRuntimeState;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unpacks the bundled native runtime (assets/runtime) and web UI (assets/web)
 * into getFilesDir(), verifies the runtime manifest hashes (fail-closed), and
 * describes how to launch the patched core through the glibc loader.
 *
 * targetSdk 28 is required so the loader/core can be exec'd from the data dir.
 */
public final class RuntimeManager {

    private static final String TAG = "RuntimeManager";
    public static final String CORE_VERSION = RuntimeConfig.CORE_VERSION;

    private final Context appContext;
    private final String csrfToken;
    private final RuntimePaths paths;
    private final NetworkMonitorHolder network;
    /** Guards against starting the background env install more than once. */
    private final AtomicBoolean envInstallStarted = new AtomicBoolean(false);

    public RuntimeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.csrfToken = UUID.randomUUID().toString();
        this.paths = new RuntimePaths(context);
        this.network = new NetworkMonitorHolder(context);
    }

    public Context appContext() {
        return appContext;
    }

    public int getHttpsPort() {
        return RuntimeConfig.httpsPort(appContext);
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public File getRuntimeDir() {
        return paths.getRuntimeDir();
    }

    public File getWebDir() {
        return paths.getWebDir();
    }

    public File getBinDir() {
        return paths.getBinDir();
    }

    public File getLibDir() {
        return paths.getLibDir();
    }

    public File getCertFile() {
        return paths.getCertFile();
    }

    public File getGeminiDir() {
        return paths.getGeminiDir();
    }

    public File getDshHome() {
        return paths.getDshHome();
    }

    public File getAppDataDir() {
        return paths.getAppDataDir();
    }

    public File getProjectsDir() {
        return paths.getProjectsDir();
    }

    /**
     * Phase 1: extract {@code assets/runtime} (the core + glibc) and the web UI,
     * verify the runtime manifest sha256 (fail-closed), and prepare DNS/onboarding.
     * Idempotent: a runtime whose marker is present and whose manifest still
     * verifies is reused.
     */
    public synchronized void installCore() {
        CoreInstaller.installCore(this);
    }

    /**
     * Phase 2: extract the bionic toolchain and regenerate {@code runtime/bin}
     * bridges on a daemon thread, in parallel with core startup. Best-effort.
     */
    public void installEnvInBackground() {
        EnvInstallScheduler.schedule(this, envInstallStarted);
    }

    /** Command line required to boot DeepSeek Harness via Node.js in Termux. */
    public String[] coreCommand() {
        File filesDir = appContext.getFilesDir();
        File prefix = new File(filesDir, com.dsh.install.EnvPaths.PREFIX_DIR);
        File nodeBin = new File(prefix, "bin/node");
        File bashBin = new File(prefix, com.dsh.install.EnvPaths.BASH_REL);
        File dshBoot = new File(getRuntimeDir(), "dsh/apps/cli/lib/dsh_boot.mjs");
        File dshScript = dshBoot.isFile() ? dshBoot : new File(getRuntimeDir(), "dsh/apps/cli/lib/bin.js");
        if (!dshScript.isFile()) {
            File altScript1 = new File(filesDir, "runtime/apps/cli/lib/bin.js");
            File altScript2 = new File(filesDir, "dsh/apps/cli/lib/bin.js");
            if (altScript1.isFile()) {
                dshScript = altScript1;
            } else if (altScript2.isFile()) {
                dshScript = altScript2;
            }
        }
        int port = getHttpsPort();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (nodeBin.canExecute()) {
            cmd.add(nodeBin.getAbsolutePath());
        } else if (bashBin.canExecute()) {
            cmd.add(bashBin.getAbsolutePath());
            cmd.add("-c");
            cmd.add("exec node --expose-internals \"" + dshScript.getAbsolutePath() + "\" --profile web --no-open --port " + port + " --host 127.0.0.1");
            return cmd.toArray(new String[0]);
        } else {
            cmd.add("node");
        }
        cmd.add("--expose-internals");
        cmd.add(dshScript.getAbsolutePath());
        cmd.add("--profile");
        cmd.add("web");
        cmd.add("--no-open");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--host");
        cmd.add("127.0.0.1");

        return cmd.toArray(new String[0]);
    }

    public void applyEnvironment(ProcessBuilder pb) {
        CoreEnvironment.apply(this, pb);
    }

    // ---- network config ---------------------------------------------------

    /** Start watching the default network; on changes the DNS config is rewritten. */
    public void startNetworkMonitor() {
        network.start();
    }

    /** Unregisters the process network callback when the core service stops. */
    public void stopNetworkMonitor() {
        network.unregister();
    }

    /**
     * Refresh DNS config in place. Rewriting resolv.conf is enough for a running
     * core: it uses GODEBUG=netdns=go and re-reads the file, so the process is
     * not restarted.
     */
    public synchronized void refreshNetwork() {
        writeNetworkConfig();
        if (CoreRuntimeState.serverUrl != null) {
            android.util.Log.d("RuntimeManager", "network changed; rewrote DNS config (core stays up)");
        } else {
            android.util.Log.d("RuntimeManager", "network changed; DNS config refreshed");
        }
    }

    public synchronized void writeNetworkConfig() {
        NetworkConfigWriter.write(appContext);
    }
}
