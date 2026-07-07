package io.github.hunghhdev.pgcache.spring;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.cache.Cache;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sub-minute {@code pgcache.background-cleanup.interval} (e.g. PT30S) must
 * not crash store creation. Previously the interval was converted with
 * {@code Duration.toMinutes()}, truncating 30s to 0 and blowing up the
 * cleanup scheduler at startup.
 */
@Testcontainers
class PgCacheManagerCleanupIntervalTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void subMinuteCleanupIntervalMustNotFailCacheCreation() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        PgCacheManager.PgCacheConfiguration config = PgCacheManager.PgCacheConfiguration.builder()
                .backgroundCleanupEnabled(true)
                .backgroundCleanupInterval(Duration.ofSeconds(30))
                .build();

        PgCacheManager manager = new PgCacheManager(dataSource, config);
        try {
            Cache cache = manager.getCache("sub-minute-cleanup");
            assertNotNull(cache);

            cache.put("k", "v");
            assertEquals("v", cache.get("k", String.class));
        } finally {
            manager.destroy();
        }
    }
}
