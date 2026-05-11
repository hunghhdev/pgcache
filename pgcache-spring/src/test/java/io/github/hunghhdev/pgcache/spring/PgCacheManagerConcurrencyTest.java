package io.github.hunghhdev.pgcache.spring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")
class PgCacheManagerConcurrencyTest {

    private static PostgreSQLContainer<?> postgres;
    private static HikariDataSource dataSource;

    @BeforeAll
    static void startDb() {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine");
        postgres.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(cfg);
    }

    @AfterAll
    static void stopDb() {
        if (dataSource != null) dataSource.close();
        if (postgres != null) postgres.stop();
    }

    @Test
    void concurrentSetCacheConfigurationAndGetCacheDoesNotCloseLiveStore() throws Exception {
        PgCacheManager.PgCacheConfiguration defaultCfg = PgCacheManager.PgCacheConfiguration.builder()
            .defaultTtl(Duration.ofHours(1))
            .tableName("concurrency_test")
            .build();

        PgCacheManager mgr = new PgCacheManager(dataSource, defaultCfg);
        mgr.setCacheConfiguration("users", defaultCfg);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int round = 0; round < 50; round++) {
            CountDownLatch start = new CountDownLatch(1);

            Runnable reconfigure = () -> {
                try { start.await(); mgr.setCacheConfiguration("users", defaultCfg); }
                catch (Throwable t) { failure.compareAndSet(null, t); }
            };

            Runnable use = () -> {
                try {
                    start.await();
                    Cache cache = mgr.getCache("users");
                    cache.put("k", "v");
                    Cache.ValueWrapper w = cache.get("k");
                    if (w == null || !"v".equals(w.get())) {
                        failure.compareAndSet(null, new AssertionError("get/put failed under concurrent reconfigure"));
                    }
                } catch (Throwable t) { failure.compareAndSet(null, t); }
            };

            pool.submit(reconfigure);
            pool.submit(use);
            start.countDown();
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(failure.get(), () -> "Concurrent test failed: " + failure.get());

        mgr.destroy();
    }

    @Test
    void removeCacheReturnsTrueWhenCacheExisted() {
        PgCacheManager.PgCacheConfiguration cfg = PgCacheManager.PgCacheConfiguration.builder()
            .defaultTtl(Duration.ofHours(1))
            .tableName("concurrency_test_2")
            .build();

        PgCacheManager mgr = new PgCacheManager(dataSource, cfg);
        mgr.getCache("test");

        assertTrue(mgr.removeCache("test"), "removeCache must return true when cache existed");
        assertFalse(mgr.removeCache("test"), "second removeCache must return false (already gone)");
        assertFalse(mgr.removeCache("nonexistent"), "removeCache for unknown name must return false");

        mgr.destroy();
    }
}
