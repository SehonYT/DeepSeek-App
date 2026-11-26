package com.dsh.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.dsh.runtime.RuntimeManager;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Foreground host for the patched core (language_server). Holds a wake lock,
 * streams the process log to files/logs/core.log, and publishes serverUrl once
 * ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreProcessSupervisor supervisor;
    private com.dsh.log.LogRingBuffer logBuffer;
    private com.dsh.gateway.SystemGatewayServer gatewayServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private volatile boolean daemonStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        BootMetrics.markServiceStart();
        runtime = new RuntimeManager(this);
        logBuffer = new com.dsh.log.LogRingBuffer();
        com.dsh.log.LogRingBuffer.installShared(logBuffer);
        gatewayServer = new com.dsh.gateway.SystemGatewayServer(this, logBuffer, com.dsh.BuildConfig.GATEWAY_PORT);
        gatewayServer.start();

        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " appData=" + runtime.getAppDataDir().getAbsolutePath()
                + " projects=" + runtime.getProjectsDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.detectedWebUrl = null;

        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher, logBuffer,
                this::onCoreReady,
                this::onSupervisorTerminated);
        supervisor.start();
    }

    private synchronized void onCoreReady() {
        if (destroyed || daemonStarted) {
            return;
        }
        daemonStarted = true;
        startBackgroundDaemon();
    }

    private void applyDaemonEnvironment(ProcessBuilder pb) {
        String files = getFilesDir().getAbsolutePath();
        String prefix = files + "/usr";
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", files);
        env.put("PREFIX", prefix);
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", prefix + "/lib");
        String sysPath = System.getenv("PATH");
        env.put("PATH", files + "/runtime/bin:" + prefix + "/bin:/system/bin" + (sysPath != null ? ":" + sysPath : ""));
        env.put("DSH_HOME", files + "/.dsh");
        env.put("AGY_APPLICATION_ID", getPackageName());
    }

    private File resolveDaemonBin() {
        File dshDaemon = new File(getFilesDir(), "usr/bin/dsh-daemon");
        if (dshDaemon.isFile()) {
            return dshDaemon;
        }
        File legacyDaemon = new File(getFilesDir(), "usr/bin/agy-daemon");
        return legacyDaemon.isFile() ? legacyDaemon : dshDaemon;
    }

    private File resolvePythonBin() {
        File py3 = new File(getFilesDir(), "usr/bin/python3");
        if (py3.isFile() && py3.canExecute()) {
            return py3;
        }
        File py = new File(getFilesDir(), "usr/bin/python");
        return (py.isFile() && py.canExecute()) ? py : null;
    }

    private ProcessBuilder buildDaemonCommand(File daemonBin, String action) {
        File py = resolvePythonBin();
        if (py != null) {
            return new ProcessBuilder(py.getAbsolutePath(), daemonBin.getAbsolutePath(), action);
        }
        return new ProcessBuilder(daemonBin.getAbsolutePath(), action);
    }

    private void startBackgroundDaemon() {
        new Thread(() -> {
            try {
                if (destroyed) {
                    return;
                }
                File daemonBin = resolveDaemonBin();
                File py = resolvePythonBin();
                if (!daemonBin.isFile()) {
                    Log.d(TAG, "dsh-daemon not present yet; skipping background daemon start");
                    return;
                }
                if (py == null) {
                    Log.d(TAG, "python3 not installed in usr/bin; skipping background daemon start");
                    return;
                }
                Log.i(TAG, "Starting autonomous background daemon (" + daemonBin.getName() + ")...");
                ProcessBuilder pb = buildDaemonCommand(daemonBin, "start");
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                applyDaemonEnvironment(pb);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < 1024) {
                            if (out.length() > 0) out.append(" | ");
                            out.append(line);
                        }
                    }
                } catch (Exception ignored) {
                }
                if (!p.waitFor(5000, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, daemonBin.getName() + " start timed out; destroying");
                    p.destroyForcibly();
                } else if (p.exitValue() != 0) {
                    Log.w(TAG, daemonBin.getName() + " start exited " + p.exitValue() + ": " + out);
                } else {
                    Log.i(TAG, daemonBin.getName() + " start: " + out);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to start dsh-daemon: " + e.getMessage());
            }
        }, "DshDaemonStarter").start();
    }

    private void stopBackgroundDaemon() {
        try {
            File daemonBin = resolveDaemonBin();
            if (daemonBin.isFile() && resolvePythonBin() != null) {
                Log.i(TAG, "Stopping autonomous background daemon (" + daemonBin.getName() + ")...");
                ProcessBuilder pb = buildDaemonCommand(daemonBin, "stop");
                pb.directory(getFilesDir());
                applyDaemonEnvironment(pb);
                Process p = pb.start();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, daemonBin.getName() + " stop timed out after 1500ms; destroying forcibly");
                    p.destroyForcibly();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop dsh-daemon: " + e.getMessage());
        }
    }

    private void onSupervisorTerminated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.restarting = false;
                if (CoreRuntimeState.lastError == null) {
                    CoreRuntimeState.lastError = "core supervisor stopped unexpectedly";
                }
                if (wakeLock != null) {
                    wakeLock.release();
                }
                if (runtime != null) {
                    runtime.stopNetworkMonitor();
                }
                if (openUrlWatcher != null) {
                    openUrlWatcher.stop();
                }
                ForegroundNotification.update(CoreServerService.this,
                        "Core stopped — open app to retry");
            }
        });
    }

    @Override
    public void onDestroy() {
        stopBackgroundDaemon();
        if (gatewayServer != null) {
            gatewayServer.stop();
        }
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        // Intentional stop: the watchdog must not treat the exit as a crash.
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (runtime != null) {
            runtime.stopNetworkMonitor();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.detectedWebUrl = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
