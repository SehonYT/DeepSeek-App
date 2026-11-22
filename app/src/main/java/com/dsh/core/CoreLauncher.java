package com.dsh.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dsh.install.EnvInstaller;
import com.dsh.runtime.RuntimeManager;
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
                    String cmdline = new String(buf, 0, n);
                    if (cmdline.contains("dsh") || (cmdline.contains("node") && cmdline.contains("cli"))) {
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

        // 2. Ensure Termux bootstrap is extracted first so node & bash are available
        if (!EnvInstaller.isInstalled(context)) {
            Log.i(TAG, "First boot: installing Termux bootstrap environment...");
            EnvInstaller.install(context);
        }

        // 3. Extract DeepSeek runtime bundle
        runtime.installCore();

        // 4. Background env sync & toolchain updates if any
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                runtime.installEnvInBackground();
            }
        }, 3500);

        // 5. Launch DeepSeek process
        ProcessBuilder pb = new ProcessBuilder(runtime.coreCommand());
        pb.directory(filesDir);
        File logsDir = new File(filesDir, "logs");
        logsDir.mkdirs();
        File stderrLog = new File(logsDir, "core_stderr.log");
        if (stderrLog.exists()) {
            stderrLog.delete();
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            pb.redirectError(ProcessBuilder.Redirect.to(stderrLog));
        } else {
            pb.redirectErrorStream(true);
        }
        runtime.applyEnvironment(pb);
        Log.i(TAG, "Starting DeepSeek process: " + Arrays.toString(runtime.coreCommand()));
        return pb.start();
    }
}
