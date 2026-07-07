package io.github.hunghhdev.pgcache.spring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.cache.Cache;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Micrometer meters are tagged per cache, so the numbers they report must be
 * per-cache as well. Reporting shared store-wide counters under per-cache tags
 * multiplies every dashboard sum by the number of caches.
 */
@Testcontainers
class PgCacheMetricsPerCacheTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private static PgCacheManager newManager() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new PgCacheManager(dataSource,
                PgCacheManager.PgCacheConfiguration.builder().build());
    }

    @Test
    void metersMustReportPerCacheCounts() {
        PgCacheManager manager = newManager();
        try {
            PgCache cacheA = (PgCache) manager.getCache("metrics-a");
            PgCache cacheB = (PgCache) manager.getCache("metrics-b");

            // traffic on cache A only: one put, one hit, one miss
            cacheA.put("k", "v");
            assertNotNull(cacheA.get("k"));
            assertNull(cacheA.get("absent"));

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new PgCacheMetrics(cacheA).bindTo(registry);
            new PgCacheMetrics(cacheB).bindTo(registry);

            assertEquals(1.0, registry.get("pgcache.gets").tag("cache", "metrics-a").tag("result", "hit")
                    .functionCounter().count(), 0.001);
            assertEquals(1.0, registry.get("pgcache.gets").tag("cache", "metrics-a").tag("result", "miss")
                    .functionCounter().count(), 0.001);
            assertEquals(1.0, registry.get("pgcache.puts").tag("cache", "metrics-a")
                    .functionCounter().count(), 0.001);

            assertEquals(0.0, registry.get("pgcache.gets").tag("cache", "metrics-b").tag("result", "hit")
                    .functionCounter().count(), 0.001,
                    "cache B had no traffic — its meters must be zero, not store-wide totals");
            assertEquals(0.0, registry.get("pgcache.puts").tag("cache", "metrics-b")
                    .functionCounter().count(), 0.001);
        } finally {
            manager.destroy();
        }
    }

    @Test
    void rebindingIsIdempotent() {
        PgCacheManager manager = newManager();
        try {
            PgCache cache = (PgCache) manager.getCache("metrics-rebind");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            new PgCacheMetrics(cache).bindTo(registry);
            new PgCacheMetrics(cache).bindTo(registry);

            assertEquals(1, registry.find("pgcache.size").tag("cache", "metrics-rebind").meters().size(),
                    "binding twice must not duplicate meters");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void meterSurvivesCacheRecreation() {
        PgCacheManager manager = newManager();
        try {
            PgCache original = (PgCache) manager.getCache("metrics-recreate");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new PgCacheMetrics(original).bindTo(registry);

            // Recreating the cache (e.g. via setCacheConfiguration) produces a new
            // PgCache; rebinding must swap the meter to the live instance
            manager.setCacheConfiguration("metrics-recreate",
                    PgCacheManager.PgCacheConfiguration.builder().build());
            PgCache recreated = (PgCache) manager.getCache("metrics-recreate");
            assertNotSame(original, recreated);

            new PgCacheMetrics(recreated).bindTo(registry);
            recreated.put("k", "v");

            double size = registry.get("pgcache.size").tag("cache", "metrics-recreate").gauge().value();
            assertEquals(1.0, size, 0.001, "gauge must read from the recreated cache instance");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void perCacheStatisticsAreExposedOnPgCache() {
        PgCacheManager manager = newManager();
        try {
            PgCache cache = (PgCache) manager.getCache("stats-local");
            cache.put("k", "v");
            assertNotNull(cache.get("k"));
            assertNull(cache.get("nope"));
            cache.evict("k");

            io.github.hunghhdev.pgcache.core.CacheStatistics stats = cache.getCacheStatistics();
            assertEquals(1, stats.getHitCount());
            assertEquals(1, stats.getMissCount());
            assertEquals(1, stats.getPutCount());
            assertEquals(1, stats.getEvictionCount());
        } finally {
            manager.destroy();
        }
    }
}
