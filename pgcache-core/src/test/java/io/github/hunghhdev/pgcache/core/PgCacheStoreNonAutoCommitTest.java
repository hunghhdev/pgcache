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
 * Pools configured with {@code auto-commit=false} (common with
 * {@code hibernate.connection.provider_disables_autocommit=true} setups) hand
 * out connections inside an open transaction; the pool rolls back on return.
 * The store must not silently lose writes on such pools.
 */
@Testcontainers
class PgCacheStoreNonAutoCommitTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void writesMustSurviveNonAutoCommitPools() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setAutoCommit(false);

        try (HikariDataSource dataSource = new HikariDataSource(config);
             PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).build()) {

            store.put("k", "v", Duration.ofMinutes(5));
            assertEquals(Optional.of("v"), store.get("k", String.class),
                    "put must be durable even when the pool hands out auto-commit=false connections");

            store.evict("k");
            assertFalse(store.containsKey("k"),
                    "evict must be durable even when the pool hands out auto-commit=false connections");
        }
    }
}
