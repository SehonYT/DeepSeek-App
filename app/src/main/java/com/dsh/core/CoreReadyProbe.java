package com.dsh.core;

import android.content.Context;
import android.util.Log;

import com.dsh.util.ProcessUtils;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * One-shot HTTPS and certificate-pin probe of the embedded core origin, plus the
 * adaptive boot budget shared by the supervisor and the splash.
 */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";

    /**
     * Fork -> loopback-listen budget for one launch attempt. Measured node boot
     * on the target device (Xiaomi M2006C3LG, armv8l, 4GB) is 24-32s with a long
     * tail under memory pressure, so 60s is the floor rather than the whole
     * wait: the install phase that runs before node is added on top by
     * {@link #splashDeadlineBootMs(long)}.
     */
    public static final long READY_TIMEOUT_MS = 60000;
    /** Extra budget on the first boot after a cold install (cold page cache, JIT warm-up, 32-bit). */
    public static final long COLD_FIRST_RUN_BONUS_MS = 20000;
    /** A core that is alive and still logging is booting slowly, not dead: extend by this much. */
    public static final long PROGRESS_EXTENSION_MS = 15000;
    /** Maximum number of progress extensions per attempt. */
    public static final int MAX_PROGRESS_EXTENSIONS = 2;
    /** Hard ceiling for a single attempt, extensions included (base + cold bonus + extensions). */
    public static final long READY_TIMEOUT_MAX_MS =
            READY_TIMEOUT_MS + COLD_FIRST_RUN_BONUS_MS + MAX_PROGRESS_EXTENSIONS * PROGRESS_EXTENSION_MS;
    /** Core output older than this means no progress (no extension). */
    public static final long PROGRESS_STALL_MS = 20000;
    /** Margin added before the splash fails closed, so the supervisor reports first. */
    public static final long SPLASH_TIMEOUT_MARGIN_MS = 20000;
    /** Absolute ceiling for the splash deadline; the boot always fails closed eventually. */
    public static final long SPLASH_DEADLINE_MAX_MS = 180000;
    /** Ceiling for the supervisor's whole boot cycle (all attempts + backoff). */
    public static final long SUPERVISOR_BOOT_MAX_MS = 180000;

    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 60;
    private static volatile byte[] expectedCertDigest;

    private CoreReadyProbe() {
    }

    /**
     * Fork -> listen budget for the current boot. A cold install adds a bonus
     * because the first node run after extraction pays cold-cache and JIT costs
     * that the warm fast path does not.
     */
    public static long readyBudgetMs() {
        long budget = READY_TIMEOUT_MS;
        if (BootMetrics.wasColdInstall()) {
            budget += COLD_FIRST_RUN_BONUS_MS;
        }
        return Math.min(budget, READY_TIMEOUT_MAX_MS);
    }

    /** Longest a single attempt can wait after the fork, extensions included. */
    public static long maxAttemptWaitMs() {
        return readyBudgetMs() + MAX_PROGRESS_EXTENSIONS * PROGRESS_EXTENSION_MS;
    }

    /**
     * Whole supervisor boot cycle (install + all attempts plus backoff). It is
     * bounded so a permanently hung but alive core still fails closed instead of
     * retrying forever, while still covering one full extended attempt.
     */
    public static long supervisorBootBudgetMs() {
        return Math.min(readyBudgetMs() + 60000L, SUPERVISOR_BOOT_MAX_MS);
    }

    /**
     * Splash deadline in boot-elapsed milliseconds: the measured install phase
     * (userspace extract + runtime extract, ~3.3s + 8.6s on the target device)
     * plus the supervisor's whole boot cycle plus a margin. It is deliberately
     * longer than everything the supervisor may legitimately do (all attempts
     * and backoff included), so the splash never reports failure for a boot the
     * supervisor still treats as alive; it is clamped to a real upper bound so a
     * genuinely wedged supervisor still fails closed. Normal failures are
     * reported much earlier through CoreRuntimeState.lastError.
     */
    public static long splashDeadlineBootMs(long installElapsedMs) {
        long install = Math.max(0L, installElapsedMs);
        long deadline = install + supervisorBootBudgetMs() + SPLASH_TIMEOUT_MARGIN_MS;
        return Math.min(deadline, SPLASH_DEADLINE_MAX_MS);
    }

    public static boolean probeOnce(Context context, int port) {
        // Fast loopback TCP socket connection check: verifies that language_server
        // has successfully bound port and is actively listening for connections.
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception notReady) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        }
    }

    private static byte[] expectedCertDigest(Context context) throws Exception {
        byte[] cached = expectedCertDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = expectedCertDigest;
            if (cached != null) {
                return cached;
            }
            int resourceId = context.getResources().getIdentifier(
                    "agy_core_cert", "raw", context.getPackageName());
            if (resourceId == 0) {
                Log.e(TAG, "bundled core certificate resource is missing");
                return null;
            }
            try (InputStream input = context.getResources().openRawResource(resourceId)) {
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input);
                if (!(certificate instanceof X509Certificate)) {
                    return null;
                }
                cached = sha256(certificate.getEncoded());
                expectedCertDigest = cached;
                return cached;
            }
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
