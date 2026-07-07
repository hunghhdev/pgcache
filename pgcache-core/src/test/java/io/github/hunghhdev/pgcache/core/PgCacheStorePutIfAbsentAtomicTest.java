package io.github.hunghhdev.pgcache.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * putIfAbsent must be a single atomic statement on a single connection:
 * no second pooled connection for the existing-value lookup, consistent
 * event firing, and no statistics pollution.
 */
@Testcontainers
class PgCacheStorePutIfAbsentAtomicTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    @Test
    void existingValueLookupMustNotUseASecondConnection() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1500);

        try (HikariDataSource pool = new HikariDataSource(config);
             PgCacheStore store = PgCacheStore.builder().dataSource(pool).build()) {

            store.put("pia-key", "existing", Duration.ofMinutes(5));

            long start = System.nanoTime();
            Optional<Object> result = store.putIfAbsent("pia-key", "candidate", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(result.isPresent(), "existing live value must be returned");
            assertEquals("existing", result.get());
            assertTrue(elapsedMs < 1000,
                    "putIfAbsent must not block on a second connection from an exhausted pool (took " + elapsedMs + "ms)");
        }
    }

    @Test
    void existingValueMustNotPolluteHitMissStatistics() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("pia_stats").build()) {
            store.put("stats-key", "v", Duration.ofMinutes(5));
            store.resetStatistics();

            Optional<Object> existing = store.putIfAbsent("stats-key", "other", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);
            assertTrue(existing.isPresent());

            CacheStatistics stats = store.getStatistics();
            assertEquals(0, stats.getHitCount(), "putIfAbsent must not count a hit");
            assertEquals(0, stats.getMissCount(), "putIfAbsent must not count a miss");
        }
    }

    @Test
    void permanentVariantMustFireOnPutLikeTtlVariant() {
        List<String> putEvents = new ArrayList<>();
        CacheEventListener listener = new CacheEventListener() {
            @Override
            public void onPut(String key, Object value) {
                putEvents.add(key);
            }
        };

        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName("pia_events")
                .addEventListener(listener)
                .build()) {

            Optional<Object> inserted = store.putIfAbsent("event-key", "v");
            assertFalse(inserted.isPresent(), "insert must report empty (value was absent)");
            assertTrue(putEvents.contains("event-key"),
                    "permanent putIfAbsent insert must fire onPut like the TTL variant does");
        }
    }
}
