package com.dsh.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.dsh.install.BootStateStamp;
import com.dsh.install.EnvInstaller;
import com.dsh.runtime.CoreInstaller;
import com.dsh.runtime.RuntimeManager;
import com.dsh.runtime.ShellBridgeInstaller;
import com.dsh.util.ErrorText;
import com.dsh.util.ProcessUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/** Installs Termux bootloader and DeepSeek runtime, then launches the DeepSeek server. */
public final class CoreLauncher {

    private static final String TAG = "CoreLauncher";

    private CoreLauncher() {
    }

    /** Terminate any leftover orphan processes from previous sessions to prevent EADDRINUSE on fixed ports. */
    public static void killOrphanCoreProcesses() {
        int myPid = android.os.Process.myPid();
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) {
            return;
        }
        boolean killedAny = false;
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.matches("\\d+")) {
                continue;
            }
            try {
                int pid = Integer.parseInt(name);
                if (pid == myPid) {
                    continue;
                }
                File cmdlineFile = new File(entry, "cmdline");
                if (!cmdlineFile.canRead()) {
                    continue;
                }
                byte[] buf = new byte[512];
                int n;
                try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                    n = fis.read(buf);
                }
                if (n > 0) {
                    String cmdline = new String(buf, 0, n).replace('\0', ' ');
                    boolean isDshCore = (cmdline.contains("runtime/dsh") || cmdline.contains("apps/cli/lib/bin.js"))
                            && cmdline.contains("--profile");
                    if (isDshCore) {
                        Log.w(TAG, "Terminating orphan DeepSeek process (PID " + pid + ")");
                        android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL);
                        killedAny = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (killedAny) {
            ProcessUtils.sleep(200);
        }
    }

    public static Process launch(RuntimeManager runtime, File filesDir) throws IOException {
        Context context = runtime.appContext();

        // 1. Terminate any previous orphan instances
        killOrphanCoreProcesses();

        String envBundleId = EnvInstaller.readBundledBundleId(context);
        String coreHash = CoreInstaller.getExpectedRuntimeHash(context);
        File prefix = EnvInstaller.prefix(context);

        long tCheck = SystemClock.elapsedRealtime();
        boolean warmReady = BootStateStamp.isWarmReady(
                context, prefix, runtime.getRuntimeDir(), runtime.getBinDir(),
                envBundleId, coreHash, ShellBridgeInstaller.BRIDGE_FORMAT);

        if (warmReady) {
            BootMetrics.setColdInstall(false);
            long checkMs = SystemClock.elapsedRealtime() - tCheck;
            BootMetrics.setEnvCheckMs(checkMs);
            BootMetrics.setCoreCheckMs(0);
            Log.i(TAG, "warm-boot fast-path hit (" + checkMs + "ms); launching node directly");
        } else {
            BootMetrics.setColdInstall(true);

            // 2 + 3. The userspace install (extract ~3s) and the runtime extract
            // (~9s) write to completely independent directories and neither reads
            // the other's output, yet they used to run strictly one after the
            // other on the launch thread. On a cold install that serialised ~12s
            // of I/O before node could even be forked, which on a slow device
            // pushed total boot past the readiness budget and produced
            // "core did not start". Run them concurrently and wait for both.
            //
            // The two tasks only touch:
            //   userspace -> filesDir/usr (+ .gemini state)
            //   runtime   -> filesDir/runtime (+ web assets)
            // so there is no shared-write hazard. A userspace failure stays
            // non-fatal (the core still runs from assets/runtime).
            final Context ctxForEnv = context;
            final RuntimeManager rmForCore = runtime;
            final Throwable[] envFailure = new Throwable[1];
            final boolean[] envUpdated = new boolean[1];
            // The runtime extract is REQUIRED (node cannot start without
            // dsh/apps/cli/lib/bin.js). Before, an exception inside installCore()
            // was uncaught on this helper thread: it died silently, join()
            // returned normally, and launch() went on to fork node against a
            // half-populated runtime, so the real cause only surfaced minutes
            // later as "core did not start". Capture it and fail closed here.
            final Throwable[] coreFailure = new Throwable[1];

            Thread coreThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    long t = SystemClock.elapsedRealtime();
                    try {
                        rmForCore.installCore();
                    } catch (Throwable e) {
                        coreFailure[0] = e;
                        Log.e(TAG, "runtime install failed: " + e.getMessage(), e);
                        return;
                    }
                    BootMetrics.setCoreCheckMs(SystemClock.elapsedRealtime() - t);
                }
            }, "CoreInstallThread");
            coreThread.setDaemon(true);

            long tEnv = SystemClock.elapsedRealtime();
            coreThread.start();
            String installedEnvIdBefore = EnvInstaller.readInstalledBundleId(ctxForEnv);
            boolean envNeedsUpdate = !EnvInstaller.isInstalled(ctxForEnv)
                    || (!envBundleId.isEmpty() && !envBundleId.equals(installedEnvIdBefore))
                    || !new File(prefix, "bin/rg").isFile();
            if (envNeedsUpdate) {
                Log.i(TAG, "Installing/updating Bionic userspace (in parallel with runtime extract)...");
                try {
                    envUpdated[0] = EnvInstaller.install(ctxForEnv);
                } catch (Throwable e) {
                    envFailure[0] = e;
                    Log.e(TAG, "userspace install failed; continuing with runtime only: "
                            + e.getMessage(), e);
                    CoreRuntimeState.lastError = "userspace install failed: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            BootMetrics.setEnvCheckMs(SystemClock.elapsedRealtime() - tEnv);

            // Wait for the runtime extract before forking; node needs bin.js.
            try {
                coreThread.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for runtime extract", ie);
            }
            if (envUpdated[0] || (new File(prefix, "bin/rg").isFile()
                    && !new File(runtime.getBinDir(), "rg").canExecute())) {
                ShellBridgeInstaller.install(runtime, envUpdated[0]);
            }
            if (coreFailure[0] != null) {
                // Fail closed with the real reason instead of forking node
                // against an incomplete runtime tree.
                String reason = coreFailure[0].getMessage();
                if (reason == null || reason.isEmpty()) {
                    reason = coreFailure[0].getClass().getSimpleName();
                }
                throw new IOException("runtime install failed: " + reason, coreFailure[0]);
            }

            // Persist composite stamp so subsequent launches hit the <1ms warm-boot path
            String installedEnvId = EnvInstaller.readInstalledBundleId(context);
            BootStateStamp.write(context,
                    !envBundleId.isEmpty() ? envBundleId : installedEnvId,
                    coreHash,
                    ShellBridgeInstaller.BRIDGE_FORMAT);
        }

        // 3b. Refresh resolv.conf and (re)register the default-network callback on
        // EVERY boot. Both used to happen only inside CoreInstaller.installCore(),
        // i.e. only on the cold path, so every warm boot forked node with whatever
        // DNS servers the *previous* session happened to see. After a
        // Wi-Fi <-> mobile switch between launches the core's Go resolver
        // (GODEBUG=netdns=go) read a resolv.conf full of unreachable servers and
        // every request failed until the next cold install -- the UI then shows a
        // disconnected sidebar. CoreInstaller still calls them on the cold path;
        // both are idempotent and now cheap (unchanged files are not rewritten).
        try {
            runtime.writeNetworkConfig();
            runtime.startNetworkMonitor();
        } catch (Throwable t) {
            // Network plumbing must never block the fork.
            Log.w(TAG, "cannot refresh network config before fork: " + t.getMessage());
        }

        // 4. Launch DeepSeek process immediately; CoreProcessSupervisor handles post-ready tasks.
        ProcessBuilder pb = new ProcessBuilder(runtime.coreCommand());
        pb.directory(filesDir);
        runtime.applyEnvironment(pb);
        BootMetrics.markFork();
        Log.i(TAG, "Starting DeepSeek process: " + Arrays.toString(runtime.coreCommand()));
        return pb.start();
    }
}
