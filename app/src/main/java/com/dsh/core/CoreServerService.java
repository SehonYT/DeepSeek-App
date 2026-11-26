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
    private final java.util.concurrent.atomic.AtomicBoolean healthCheckInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastHealthCheckMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        BootMetrics.markServiceStart();
        // Promote to foreground FIRST. startForegroundService() gives the service
        // only ~5s (10s on some ROMs) to call startForeground() before the
        // platform kills the app; everything below touches disk and binder, so
        // doing it first used to spend that budget before the notification
        // existed. The wake lock is taken here too, before the long-lived
        // threads are created.
        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

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
        // stopBackgroundDaemon() forks python3, waits up to 1.5s for it and
        // destroys it. That used to run right at the top of onDestroy(), i.e. on
        // the MAIN thread, so the looper (and any UI animation) was blocked while
        // the service was torn down. Fork it off the main thread instead, after
        // the synchronous teardown above.
        Thread stopper = new Thread(this::stopBackgroundDaemon, "DshDaemonStopper");
        stopper.setDaemon(true);
        stopper.start();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && !destroyed && supervisor != null) {
            String action = intent.getAction();
            if (CoreServiceLauncher.ACTION_RESTART_CORE.equals(action)) {
                String reason = intent.getStringExtra(CoreServiceLauncher.EXTRA_REASON);
                supervisor.restartCore(reason != null ? reason : "Restarting core...");
            } else if (CoreServiceLauncher.ACTION_HEALTH_CHECK.equals(action)) {
                runResumeHealthCheckAsync();
            }
        }
        return START_STICKY;
    }

    private void runResumeHealthCheckAsync() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheckMs < 3000L || !healthCheckInFlight.compareAndSet(false, true)) {
            return;
        }
        lastHealthCheckMs = now;
        final String url = CoreRuntimeState.serverUrl;
        if (url == null || CoreRuntimeState.restarting) {
            healthCheckInFlight.set(false);
            return;
        }
        new Thread(() -> {
            try {
                if (!probeEventsReady(url)) {
                    Log.w(TAG, "Resume $events health check failed; restarting core");
                    if (!destroyed && supervisor != null) {
                        supervisor.restartCore("Core connection unresponsive; restarting...");
                    }
                }
            } finally {
                healthCheckInFlight.set(false);
            }
        }, "CoreHealthCheck").start();
    }

    /** Verifies HTTP token auth + /api/remote.mux $events stream readiness within 3.5s. */
    private static boolean probeEventsReady(String serverUrl) {
        java.net.Socket sock = null;
        try {
            java.net.URI uri = java.net.URI.create(serverUrl);
            int port = uri.getPort();
            if (port <= 0) {
                return false;
            }
            String pathAndQuery = uri.getRawPath();
            if (pathAndQuery == null || pathAndQuery.isEmpty()) {
                pathAndQuery = "/";
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                pathAndQuery += "?" + uri.getRawQuery();
            }
            java.net.URL authUrl = new java.net.URL("http://127.0.0.1:" + port + pathAndQuery);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) authUrl.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            String cookieHeader = null;
            java.util.List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String c : cookies) {
                    if (c != null && c.startsWith("dsh-auth-")) {
                        int semi = c.indexOf(';');
                        cookieHeader = semi > 0 ? c.substring(0, semi) : c;
                        break;
                    }
                }
            }
            conn.disconnect();
            if ((code != 200 && code != 303 && code != 302) || cookieHeader == null) {
                return false;
            }

            sock = new java.net.Socket("127.0.0.1", port);
            sock.setSoTimeout(3500);
            java.io.OutputStream out = sock.getOutputStream();
            java.io.InputStream in = sock.getInputStream();
            String req = "GET /api/remote.mux HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: http://127.0.0.1:" + port + "\r\n"
                    + "Cookie: " + cookieHeader + "\r\n\r\n";
            out.write(req.getBytes("UTF-8"));
            out.flush();

            StringBuilder hdr = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                hdr.append((char) b);
                int len = hdr.length();
                if (len >= 4 && hdr.substring(len - 4).equals("\r\n\r\n")) {
                    break;
                }
                if (len > 4096) {
                    return false;
                }
            }
            if (!hdr.toString().contains(" 101 ")) {
                return false;
            }

            // Send masked WS text frame: {"type":"open","streamId":"hc","endpoint":"$events","payload":{"args":{}}}
            byte[] payload = "{\"type\":\"open\",\"streamId\":\"hc\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}".getBytes("UTF-8");
            byte[] mask = new byte[]{0x12, 0x34, 0x56, 0x78};
            java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
            frame.write(0x81);
            frame.write(0x80 | payload.length);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i & 3]);
            }
            out.write(frame.toByteArray());
            out.flush();

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + 3500L;
            StringBuilder received = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                int n = in.read(buf);
                if (n <= 0) {
                    return false;
                }
                received.append(new String(buf, 0, n, "ISO-8859-1"));
                if (received.toString().contains("\"ready\"")) {
                    // Send a clean masked WebSocket Close frame (code 1000) so the server
                    // does not retain a half-open peer or log an abrupt reset.
                    try {
                        byte[] closeFrame = new byte[]{
                                (byte) 0x88, (byte) 0x82,
                                0x12, 0x34, 0x56, 0x78,
                                (byte) (0x03 ^ 0x12), (byte) (0xE8 ^ 0x34)
                        };
                        out.write(closeFrame);
                        out.flush();
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "probeEventsReady failed: " + e.getMessage());
            return false;
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
