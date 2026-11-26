package com.dsh.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.dsh.runtime.RuntimeManager;
import com.dsh.util.ErrorText;
import com.dsh.util.ProcessUtils;
import com.dsh.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Writer;

/**
 * Supervises the core for its whole life. One pass = install, launch, wait for
 * ready, then block until exit. Intentional stops end the loop; an unexpected
 * exit is auto-restarted with exponential backoff up to MAX_RESTARTS, the
 * budget refilling after SUSTAINED_READY_MS of uptime.
 *
 * <p>Readiness is progress-aware: a core that is still alive and still
 * emitting output past the base timeout is booting slowly (measured node
 * time-to-listen is 24-32s on the target device, with a long memory-pressure
 * tail), so its deadline is extended while it keeps making progress. The whole
 * cycle is still bounded by {@link CoreReadyProbe#supervisorBootBudgetMs()}, so
 * a genuinely hung core fails closed instead of retrying forever.</p>
 */
public final class CoreProcessSupervisor {

    private static final String TAG = "CoreProcessSupervisor";

    /** Log once when the port is open but the tokenized entry URL has not been announced. */
    private static final long TOKEN_URL_WAIT_NOTE_MS = 3000L;

    private final Context appContext;
    private final RuntimeManager runtime;
    private final OpenUrlWatcher openUrlWatcher;
    private final com.dsh.log.LogRingBuffer logBuffer;
    private final Runnable readyListener;
    private final Runnable terminalListener;

    private volatile Process process;
    private volatile boolean stopping = false;
    private volatile boolean intentionalRestart = false;
    /** Last time the core process produced a stdout/stderr line (0 = none yet). */
    private volatile long lastCoreOutputAtMs = 0L;
    /** Readiness budget actually used by the last attempt (reported on timeout). */
    private volatile long lastReadyBudgetMs = CoreReadyProbe.READY_TIMEOUT_MS;
    private Thread worker;
    private final Object readyLock = new Object();

    private void notifyReadyLock() {
        synchronized (readyLock) {
            readyLock.notifyAll();
        }
    }

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher,
                                 com.dsh.log.LogRingBuffer logBuffer,
                                 Runnable terminalListener) {
        this(context, runtime, openUrlWatcher, logBuffer, null, terminalListener);
    }

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher,
                                 com.dsh.log.LogRingBuffer logBuffer,
                                 Runnable readyListener,
                                 Runnable terminalListener) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.openUrlWatcher = openUrlWatcher;
        this.logBuffer = logBuffer;
        this.readyListener = readyListener;
        this.terminalListener = terminalListener;
    }

    public void start() {
        stopping = false;
        worker = new Thread(this::runCore, "CoreServerThread");
        worker.start();
    }

    public void stop() {
        stopping = true;
        if (worker != null) {
            worker.interrupt();
        }
        // stop() is called from Service.onDestroy(), i.e. on the MAIN thread.
        // destroy() also runs killOrphanCoreProcesses(), which walks every
        // /proc/<pid> entry, reads each cmdline and then sleeps 200ms when it
        // killed something -- that froze the looper (and the UI) during service
        // teardown. Kill only this child here, off the main thread; the /proc
        // sweep still runs on the supervisor/failure paths where it belongs,
        // and CoreLauncher.launch() sweeps again before each fork anyway.
        final Process proc = process;
        Thread killer = new Thread(() -> destroyProcessOnly(proc), "CoreStopThread");
        killer.setDaemon(true);
        killer.start();
    }

    public void restartCore(String reason) {
        if (stopping) {
            return;
        }
        Log.w(TAG, "Restarting core intentionally: " + reason);
        intentionalRestart = true;
        CoreRuntimeState.restarting = true;
        CoreRuntimeState.lastError = reason != null ? reason : "Restarting core...";
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.detectedWebUrl = null;
        final Process proc = process;
        Thread restarter = new Thread(() -> destroy(proc), "CoreRestartThread");
        restarter.setDaemon(true);
        restarter.start();
        notifyReadyLock();
    }

    private void runCore() {
        try {
            runCoreLoop();
        } finally {
            if (!stopping && terminalListener != null) {
                try {
                    terminalListener.run();
                } catch (Exception e) {
                    Log.w(TAG, "terminal callback failed", e);
                }
            }
        }
    }

    private void runCoreLoop() {
        int attempt = 0;
        long cycleStart = System.currentTimeMillis();
        while (!stopping) {
            Writer log = CoreLogWriter.open(appContext.getFilesDir());
            Writer errLog = CoreLogWriter.open(appContext.getFilesDir(), "core_stderr.log", CoreLogWriter.STDERR_MAX_BYTES);
            try {
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.detectedWebUrl = null;
                CoreRuntimeState.httpBoundSeen = false;
                openUrlWatcher.setReadyNotifier(this::notifyReadyLock);
                CoreLogWriter.writeLine(log, "\n=== DeepSeek Core Launch (attempt " + (attempt + 1) + ") ===");
                // Install phase (cold boot) happens inside launch(); cache its
                // length so the splash deadline can be extended by the same
                // amount the boot actually spent before node was forked.
                lastCoreOutputAtMs = 0L;
                Process proc = CoreLauncher.launch(runtime, appContext.getFilesDir());
                process = proc;
                // launch() does the pre-fork install phase (userspace + runtime
                // extract) on a cold boot; publish its measured length so the
                // splash deadline is extended by exactly what the boot spent
                // before node was forked.
                BootMetrics.markInstallPhaseDone();
                CoreRuntimeState.installElapsedMs = BootMetrics.installElapsedMs();
                BootMetrics.markCoreAttemptStart();

                final Writer streamWriter = log;
                final Writer errWriter = errLog;
                Thread streamer = new Thread(() -> streamOutput(proc, streamWriter), "CoreStreamThread");
                Thread errStreamer = new Thread(() -> streamStderr(proc, errWriter), "CoreStderrThread");
                streamer.setDaemon(true);
                errStreamer.setDaemon(true);
                streamer.start();
                errStreamer.start();

                if (!waitForReady(proc, log)) {
                    if (stopping) {
                        break;
                    }
                    if (intentionalRestart) {
                        intentionalRestart = false;
                        attempt = 0;
                        CoreRuntimeState.serverUrl = null;
                        CoreRuntimeState.detectedWebUrl = null;
                        CoreLogWriter.closeQuietly(log);
                        log = null;
                        continue;
                    }
                    try {
                        streamer.join(1000);
                        errStreamer.join(500);
                    } catch (InterruptedException ignored) {
                    }
                    CoreLogWriter.closeQuietly(errLog);
                    errLog = null;
                    int code = ProcessUtils.exitCode(proc);
                    long bootCycleMs = System.currentTimeMillis() - cycleStart;
                    boolean cycleExhausted = bootCycleMs >= CoreReadyProbe.supervisorBootBudgetMs();
                    if (CoreRuntimeState.lastError == null) {
                        CoreRuntimeState.lastError = "core did not become ready within "
                                + lastReadyBudgetMs + "ms"
                                + (code != -1 ? " (exit code " + code + describeExitCode(code, appContext.getFilesDir()) + ")" : "")
                                + (cycleExhausted ? " [boot budget exhausted after " + bootCycleMs + "ms]" : "");
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    }
                    destroy(proc);
                    attempt++;
                    String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                    if (cycleExhausted) {
                        CoreRuntimeState.restarting = false;
                        CoreRuntimeState.lastError = "core did not become ready within the boot budget of "
                                + (CoreReadyProbe.supervisorBootBudgetMs() / 1000) + "s"
                                + (code != -1 ? " (last exit code " + code + describeExitCode(code, appContext.getFilesDir()) + ")" : "")
                                + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                        com.dsh.log.CrashRecorder.recordCrash(appContext, code, bootCycleMs, CoreRuntimeState.lastError, logBuffer);
                        break;
                    }
                    if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                        CoreRuntimeState.restarting = false;
                        CoreRuntimeState.lastError = "core failed to start after " + CoreRestartPolicy.MAX_RESTARTS
                                + " attempts (last exit code " + code + describeExitCode(code, appContext.getFilesDir()) + ")"
                                + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                        com.dsh.log.CrashRecorder.recordCrash(appContext, code, 0, CoreRuntimeState.lastError, logBuffer);
                        break;
                    }
                    long remaining = CoreReadyProbe.supervisorBootBudgetMs()
                            - (System.currentTimeMillis() - cycleStart);
                    long delay = CoreRestartPolicy.delayForWithin(attempt, remaining);
                    if (delay < 0) {
                        CoreRuntimeState.restarting = false;
                        CoreRuntimeState.lastError = "core startup failed (code " + code
                                + describeExitCode(code, appContext.getFilesDir())
                                + "); no boot budget left to retry"
                                + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                        com.dsh.log.CrashRecorder.recordCrash(appContext, code, bootCycleMs, CoreRuntimeState.lastError, logBuffer);
                        break;
                    }
                    CoreRuntimeState.restarting = true;
                    BootMetrics.markRestarting(delay);
                    CoreRuntimeState.lastError = "core startup failed (code " + code + describeExitCode(code, appContext.getFilesDir())
                            + "); retrying in " + (delay / 1000) + "s (attempt "
                            + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    Log.w(TAG, CoreRuntimeState.lastError);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.dsh.log.CrashRecorder.recordCrash(appContext, code, 0, CoreRuntimeState.lastError, logBuffer);
                    CoreLogWriter.closeQuietly(log);
                    log = null;
                    sleepInterruptible(delay);
                    continue;
                }

                // The boot cycle succeeded: a later crash/restart is a fresh cycle
                // with its own budget, so it is never starved by how long the
                // first (slow) boot took.
                cycleStart = System.currentTimeMillis();
                // Trigger background env sync and daemon startup now that core is serving UI.
                runtime.installEnvInBackground();
                if (readyListener != null) {
                    try {
                        readyListener.run();
                    } catch (Throwable t) {
                        Log.w(TAG, "readyListener failed", t);
                    }
                }

                long readyAt = System.currentTimeMillis();
                try {
                    proc.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (stopping) {
                    break;
                }
                if (intentionalRestart) {
                    intentionalRestart = false;
                    attempt = 0;
                    CoreRuntimeState.serverUrl = null;
                    CoreRuntimeState.detectedWebUrl = null;
                    CoreLogWriter.closeQuietly(log);
                    log = null;
                    continue;
                }
                try {
                    streamer.join(1000);
                    errStreamer.join(500);
                } catch (InterruptedException ignored) {
                }
                CoreLogWriter.closeQuietly(errLog);
                errLog = null;

                int code = ProcessUtils.exitCode(proc);
                if (System.currentTimeMillis() - readyAt >= CoreRestartPolicy.SUSTAINED_READY_MS) {
                    attempt = 0;
                }
                cycleStart = System.currentTimeMillis();
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.detectedWebUrl = null;
                attempt++;
                String exitDesc = describeExitCode(code, appContext.getFilesDir());
                String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "core crashed " + CoreRestartPolicy.MAX_RESTARTS
                            + " times; giving up (last exit code " + code + exitDesc + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.dsh.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                    break;
                }
                long remaining = CoreReadyProbe.supervisorBootBudgetMs()
                        - (System.currentTimeMillis() - cycleStart);
                long delay = CoreRestartPolicy.delayForWithin(attempt, remaining);
                if (delay < 0) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "core crashed " + attempt + " times and the boot budget is "
                            + "exhausted; giving up (last exit code " + code + exitDesc + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.dsh.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                    break;
                }
                CoreRuntimeState.restarting = true;
                BootMetrics.markRestarting(delay);
                CoreRuntimeState.lastError = "core exited unexpectedly (code " + code + exitDesc
                        + "); restarting in " + (delay / 1000) + "s (attempt "
                        + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")"
                        + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                Log.w(TAG, CoreRuntimeState.lastError);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                com.dsh.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                CoreLogWriter.closeQuietly(log);
                log = null;
                sleepInterruptible(delay);
            } catch (Exception e) {
                if (stopping) {
                    break;
                }
                if (intentionalRestart) {
                    intentionalRestart = false;
                    attempt = 0;
                    CoreRuntimeState.serverUrl = null;
                    CoreRuntimeState.detectedWebUrl = null;
                    CoreLogWriter.closeQuietly(log);
                    log = null;
                    continue;
                }
                Log.e(TAG, "core start failed", e);
                CoreRuntimeState.lastError = ErrorText.describe(e);
                CoreLogWriter.writeLine(log, "core start failed: " + CoreRuntimeState.lastError);
                // install/launch failure: fail-closed, user retries from the splash.
                break;
            } finally {
                CoreLogWriter.closeQuietly(errLog);
                CoreLogWriter.closeQuietly(log);
            }
        }
    }

    /**
     * Waits for the core's loopback port.
     *
     * <p>A live process that is still writing to its log/streams is making
     * progress, so the deadline is extended (up to
     * {@link CoreReadyProbe#MAX_PROGRESS_EXTENSIONS} times) instead of failing a
     * slow boot. A silent or dead core is never extended, so genuine failures
     * still fail closed with the original timeout message.</p>
     */
    private boolean waitForReady(Process proc, Writer log) {
        long start = System.currentTimeMillis();
        long tcpOpenAt = -1L;
        // One-time (per wait) note that the socket is open but the authenticated
        // URL has not been announced yet; see the token URL branch below.
        boolean tokenWaitNoted = false;
        int port = CoreRuntimeState.httpsPort;
        long budget = lastReadyBudgetMs = CoreReadyProbe.readyBudgetMs();
        long hardDeadline = start + CoreReadyProbe.READY_TIMEOUT_MAX_MS;
        int extensions = 0;
        long nextExtensionAt = start + budget;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= nextExtensionAt) {
                if (extensions < CoreReadyProbe.MAX_PROGRESS_EXTENSIONS
                        && isMakingProgress(now)
                        && now + CoreReadyProbe.PROGRESS_EXTENSION_MS <= hardDeadline) {
                    extensions++;
                    budget += CoreReadyProbe.PROGRESS_EXTENSION_MS;
                    nextExtensionAt = start + budget;
                    String note = "core still booting after " + (now - start) + "ms with recent output; "
                            + "extending readiness budget to " + budget + "ms (extension "
                            + extensions + "/" + CoreReadyProbe.MAX_PROGRESS_EXTENSIONS + ")";
                    Log.i(TAG, note);
                    CoreLogWriter.writeLine(log, note);
                } else {
                    break;
                }
            }
            if (stopping) {
                return false;
            }
            if (!ProcessUtils.isAlive(proc)) {
                int code = ProcessUtils.exitCode(proc);
                ProcessUtils.sleep(100);
                String lastLogs = getLastLinesFromLog(appContext.getFilesDir(), 10);
                CoreRuntimeState.lastError = "core process exited before it became ready (exit code " + code + ")"
                        + (lastLogs.isEmpty() ? "" : "\n--- core.log tail ---\n" + lastLogs);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                return false;
            }
            String detected = CoreRuntimeState.detectedWebUrl;
            if (tcpOpenAt < 0 && (CoreRuntimeState.httpBoundSeen || detected != null || CoreReadyProbe.probeOnce(appContext, port))) {
                tcpOpenAt = System.currentTimeMillis();
                detected = CoreRuntimeState.detectedWebUrl;
            }
            if (tcpOpenAt >= 0 && detected != null && !detected.isEmpty()) {
                CoreRuntimeState.serverUrl = detected;
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.readyGeneration++;
                lastReadyBudgetMs = budget;
                BootMetrics.markCoreReady();
                String readyStatus = "core ready on 127.0.0.1:" + port + " (tokenized URL)";
                Log.i(TAG, readyStatus);
                CoreLogWriter.writeLine(log, readyStatus);
                return true;
            }
            // NEVER publish an un-tokenized URL. The core's token authentication
            // mints its session cookie only for a GET /?token=<launch token>
            // request and answers every other index request with 401, so a bare
            // http://127.0.0.1:<port>/ would load a document that can never
            // authenticate: the UI renders, the WebSocket to /api/remote.mux is
            // refused, and the sidebar sits on "Reconnecting" forever.
            //
            // The token line ("dsh web: http://.../?token=...") is printed only
            // after the loader has settled every plugin, which is seconds after
            // the socket starts listening. So the port being open is NOT
            // readiness; wait for the tokenized URL itself. The readiness budget
            // above still bounds the wait and fails closed with a real error.
            if (tcpOpenAt >= 0 && !tokenWaitNoted
                    && System.currentTimeMillis() - tcpOpenAt >= TOKEN_URL_WAIT_NOTE_MS) {
                tokenWaitNoted = true;
                String note = "port " + port + " is listening; waiting for the authenticated "
                        + "URL from the core (plugins still loading)";
                Log.i(TAG, note);
                CoreLogWriter.writeLine(log, note);
            }
            synchronized (readyLock) {
                if (!stopping && CoreRuntimeState.detectedWebUrl == null && ProcessUtils.isAlive(proc)) {
                    try {
                        readyLock.wait(tcpOpenAt >= 0 ? 50L : 80L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        long waitedMs = System.currentTimeMillis() - start;
        // Distinguish the two failure shapes: the port never opened (core did not
        // come up) versus the port opened but the authenticated entry URL was never
        // announced (a plugin failed to load, so announceReady never ran). The
        // second is a real error the user must see; guessing a bare URL there is
        // what produced an authenticated-looking but dead UI.
        String timeoutNote = (tcpOpenAt >= 0
                ? "core listened on 127.0.0.1:" + port
                        + " but never announced the authenticated entry URL"
                        + " (a startup plugin failed to load; check the log above)"
                : "core did not start")
                + " after " + waitedMs + "ms (base=" + CoreReadyProbe.readyBudgetMs()
                + "ms, extensions=" + extensions
                + ", recentOutput=" + isMakingProgress(System.currentTimeMillis()) + ")";
        Log.w(TAG, timeoutNote);
        CoreLogWriter.writeLine(log, timeoutNote);
        return false;
    }

    /**
     * True when the core is still alive and produced output recently, or when
     * the boot phase was published recently (e.g. the install phase is still
     * running on a cold start). This is what separates "slow" from "dead".
     */
    private boolean isMakingProgress(long now) {
        Process proc = process;
        if (proc != null && !ProcessUtils.isAlive(proc)) {
            return false;
        }
        long lastOutput = lastCoreOutputAtMs;
        if (lastOutput > 0 && now - lastOutput <= CoreReadyProbe.PROGRESS_STALL_MS) {
            return true;
        }
        // The install phase (cold boot) produces no stdout of its own, but it
        // publishes phases; a phase change is progress too.
        if (lastOutput <= 0 && BootMetrics.progressElapsedMs() <= CoreReadyProbe.PROGRESS_STALL_MS) {
            return true;
        }
        // A core that is alive but quiet can still be doing page-cache work, so
        // give it one extension while the boot phase is still "starting core".
        return BootMetrics.phase() == BootMetrics.PHASE_CORE_STARTING
                && BootMetrics.progressElapsedMs() <= CoreReadyProbe.PROGRESS_STALL_MS * 2;
    }

    private void streamOutput(Process proc, Writer log) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastCoreOutputAtMs = System.currentTimeMillis();
                BootMetrics.markProgress();
                String safeLine = SensitiveDataRedactor.redactLine(line);
                Log.d(TAG, safeLine);
                if (logBuffer != null) {
                    logBuffer.appendLine(safeLine);
                }
                // safeLine is already redacted; re-redacting it was two more
                // regex sweeps per line on the boot critical path.
                CoreLogWriter.writeLinePreRedacted(log, safeLine);
                openUrlWatcher.handleStreamLine(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "log stream ended", e);
        } finally {
            notifyReadyLock();
        }
    }

    private void streamStderr(Process proc, Writer errLog) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastCoreOutputAtMs = System.currentTimeMillis();
                BootMetrics.markProgress();
                String safeLine = SensitiveDataRedactor.redactLine(line);
                Log.w(TAG, "[stderr] " + safeLine);
                if (logBuffer != null) {
                    logBuffer.append("core", "WARN", "[stderr] " + safeLine);
                }
                CoreLogWriter.writeLinePreRedacted(errLog, safeLine);
            }
        } catch (Exception e) {
            Log.d(TAG, "stderr stream ended", e);
        }
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopping) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            ProcessUtils.sleep(Math.min(left, 250));
        }
    }

    private static void destroy(Process proc) {
        destroyProcessOnly(proc);
        CoreLauncher.killOrphanCoreProcesses();
    }

    /** Kills one child without the /proc-wide orphan sweep (safe on any thread). */
    private static void destroyProcessOnly(Process proc) {
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            try {
                proc.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    private static String getLastLinesFromLog(java.io.File filesDir, int maxLines) {
        java.io.File logFile = new java.io.File(filesDir, "logs/core.log");
        if (!logFile.isFile()) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(logFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(SensitiveDataRedactor.redactLine(l));
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        return TextUtils.join("\n", lines);
    }

    private static String getSigillLogTail(java.io.File filesDir) {
        java.io.File sigillFile = new java.io.File(filesDir, "logs/sigill.log");
        if (!sigillFile.isFile() || sigillFile.length() == 0) {
            sigillFile = new java.io.File(filesDir, "sigill.log");
        }
        if (!sigillFile.isFile() || sigillFile.length() == 0) {
            return "";
        }
        try {
            return com.dsh.util.IoUtil.readAll(new java.io.FileInputStream(sigillFile), "UTF-8").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getStderrLogTail(java.io.File filesDir, int maxLines) {
        java.io.File errFile = new java.io.File(filesDir, "logs/core_stderr.log");
        if (!errFile.isFile() || errFile.length() == 0) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(errFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(SensitiveDataRedactor.redactLine(l));
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        return TextUtils.join("\n", lines);
    }

    private static String describeExitCode(int code, java.io.File filesDir) {
        String base;
        switch (code) {
            case 2:
                base = " [fatal error / runtime.throw]";
                break;
            case 137:
                base = " [SIGKILL / system Out-Of-Memory LMK]";
                break;
            case 139:
                base = " [SIGSEGV / segmentation fault]";
                break;
            case 134:
                base = " [SIGABRT / abort]";
                break;
            case 132:
                base = " [SIGILL / illegal instruction]";
                break;
            case 143:
                base = " [SIGTERM / terminated]";
                break;
            default:
                base = "";
                break;
        }
        if (code != 0) {
            String sigill = getSigillLogTail(filesDir);
            if (!sigill.isEmpty()) {
                base += " - " + sigill;
            }
            String stderrTail = getStderrLogTail(filesDir, 50);
            if (!stderrTail.isEmpty()) {
                base += " | stderr: " + stderrTail;
            }
        }
        return base;
    }
}
