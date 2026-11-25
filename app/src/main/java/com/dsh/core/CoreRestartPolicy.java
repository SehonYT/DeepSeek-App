package com.dsh.core;

/**
 * Restart budget and exponential backoff for the core supervisor.
 *
 * <p>The budget must not be spent on backoff: a restart delay only makes sense
 * while the whole boot cycle still has room, so the supervisor passes the
 * remaining cycle budget to {@link #delayForWithin(int, long)} and gives up when
 * there is no room left. That keeps a permanently failing core fail-closed
 * while never turning a slow-but-progressing first boot into a restart loop.</p>
 */
public final class CoreRestartPolicy {

    /** Uptime that proves the core is stable and refills the restart budget. */
    public static final long SUSTAINED_READY_MS = 60000;
    public static final int MAX_RESTARTS = 3;
    public static final long RESTART_BASE_MS = 1000;
    public static final long RESTART_MAX_MS = 30000;
    /** A backoff shorter than this is useless (the core needs time to die/start). */
    public static final long MIN_USEFUL_BACKOFF_MS = 500;

    private CoreRestartPolicy() {
    }

    public static long delayFor(int attempt) {
        int shift = Math.min(attempt - 1, 5);
        return Math.min(RESTART_BASE_MS << shift, RESTART_MAX_MS);
    }

    /**
     * Backoff capped by the remaining boot-cycle budget. Returns -1 when the
     * remaining budget cannot fit even a useful delay, which means the caller
     * should stop retrying and report the failure instead of sleeping.
     */
    public static long delayForWithin(int attempt, long remainingBudgetMs) {
        long delay = delayFor(attempt);
        if (remainingBudgetMs <= MIN_USEFUL_BACKOFF_MS) {
            return -1L;
        }
        return Math.min(delay, remainingBudgetMs - MIN_USEFUL_BACKOFF_MS);
    }
}
