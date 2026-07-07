package io.github.hunghhdev.pgcache.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules periodic background cleanup of expired cache entries.
 * Lifecycle managed via {@link #start()} and {@link #shutdown()}.
 *
 * @since 1.7.0
 */
final class BackgroundCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundCleanupScheduler.class);

    static final long DEFAULT_INTERVAL_MINUTES = 5;
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private final Duration interval;
    private final Runnable cleanupTask;

    private volatile ScheduledExecutorService executor;
    private volatile Thread shutdownHook;

    BackgroundCleanupScheduler(Duration interval, Runnable cleanupTask) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Cleanup interval must be positive, got: " + interval);
        }
        this.interval = interval;
        this.cleanupTask = cleanupTask;
    }

    void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pgcache-cleanup");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                cleanupTask.run();
            } catch (Exception e) {
                logger.warn("Background cleanup failed", e);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        logger.info("Started background cleanup with interval: {}", interval);
    }

    void shutdown() {
        deregisterShutdownHook();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            logger.info("Background cleanup shutdown completed");
        }
    }

    void registerShutdownHook() {
        Thread hook = new Thread(() -> {
            logger.info("Shutting down PgCache cleanup executor via shutdown hook...");
            shutdownHook = null; // the JVM is already shutting down; do not try to remove the hook
            shutdown();
        }, "pgcache-shutdown-hook");
        shutdownHook = hook;
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * Removes the shutdown hook registered by {@link #registerShutdownHook()}.
     * Without this, every closed store leaks its hook (and pins the store and
     * its DataSource) for the lifetime of the JVM — painful on redeploys.
     */
    private void deregisterShutdownHook() {
        Thread hook = shutdownHook;
        if (hook != null) {
            shutdownHook = null;
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // JVM shutdown in progress — nothing to clean up
            }
        }
    }
}
