package io.github.hunghhdev.pgcache.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final long intervalMinutes;
    private final Runnable cleanupTask;

    private volatile ScheduledExecutorService executor;

    BackgroundCleanupScheduler(long intervalMinutes, Runnable cleanupTask) {
        this.intervalMinutes = intervalMinutes;
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
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        logger.info("Started background cleanup with interval: {} minutes", intervalMinutes);
    }

    void shutdown() {
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down PgCache cleanup executor via shutdown hook...");
            shutdown();
        }, "pgcache-shutdown-hook"));
    }
}
