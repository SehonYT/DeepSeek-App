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
            // 2. Ensure Termux bootstrap is extracted first so node & bash are available
            long tEnv = SystemClock.elapsedRealtime();
            if (!EnvInstaller.isInstalled(context)) {
                Log.i(TAG, "First boot: installing Termux bootstrap environment...");
                EnvInstaller.install(context);
            }
            BootMetrics.setEnvCheckMs(SystemClock.elapsedRealtime() - tEnv);

            // 3. Extract DeepSeek runtime bundle & web UI
            long tCore = SystemClock.elapsedRealtime();
            runtime.installCore();
            BootMetrics.setCoreCheckMs(SystemClock.elapsedRealtime() - tCore);

            // Persist composite stamp so subsequent launches hit the <1ms warm-boot path
            String installedEnvId = EnvInstaller.readInstalledBundleId(context);
            BootStateStamp.write(context,
                    !envBundleId.isEmpty() ? envBundleId : installedEnvId,
                    coreHash,
                    ShellBridgeInstaller.BRIDGE_FORMAT);
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
