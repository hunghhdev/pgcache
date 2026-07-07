package io.github.hunghhdev.pgcache.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sliding-TTL read must refresh {@code last_accessed} atomically within the
 * same statement/connection as the read itself. Using a second pooled
 * connection for the refresh deadlocks (and silently skips the refresh) when
 * the pool is exhausted — e.g. a single-connection pool.
 */
@Testcontainers
class PgCacheStoreSlidingGetSingleConnectionTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void slidingGetShouldRefreshTtlUsingItsOwnConnectionOnly() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1500);

        try (HikariDataSource dataSource = new HikariDataSource(config);
             PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).build()) {

            store.put("sliding-key", "sliding-value", Duration.ofSeconds(10), TTLPolicy.SLIDING);
            Thread.sleep(2000);

            // Read with TTL refresh while the pool has exactly one connection —
            // the connection the read itself is holding
            Optional<String> value = store.get("sliding-key", String.class, true);
            assertTrue(value.isPresent());
            assertEquals("sliding-value", value.get());

            Optional<Duration> remaining = store.getRemainingTTL("sliding-key");
            assertTrue(remaining.isPresent());
            assertTrue(remaining.get().getSeconds() >= 9,
                    "sliding read must refresh last_accessed even on a single-connection pool, " +
                    "remaining was " + remaining.get());
        }
    }
}
