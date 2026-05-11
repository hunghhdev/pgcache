package io.github.hunghhdev.pgcache.spring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for LIKE-wildcard over-eviction bug. Cache names containing {@code _}
 * would over-match other cache names because {@code _} is a SQL LIKE single-char wildcard.
 */
@SuppressWarnings("resource")
class PgCacheLikeEscapeTest {

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
    void clearOnUnderscoredCacheNameDoesNotEvictAdjacent() {
        PgCacheStore store = PgCacheStore.builder()
            .dataSource(dataSource)
            .tableName("test_like_escape")
            .allowNullValues(true)
            .build();

        try {
            PgCache userCache = new PgCache("user_data", store, Duration.ofHours(1), true, TTLPolicy.ABSOLUTE);
            PgCache userXCache = new PgCache("userXdata", store, Duration.ofHours(1), true, TTLPolicy.ABSOLUTE);

            userCache.put("k", "userValue");
            userXCache.put("k", "userXValue");

            assertNotNull(userCache.get("k"), "pre-condition: user_data cache populated");
            assertNotNull(userXCache.get("k"), "pre-condition: userXdata cache populated");

            userCache.clear();

            assertNull(userCache.get("k"), "user_data cache should be cleared");
            assertNotNull(userXCache.get("k"), "userXdata cache must NOT be evicted (would be over-eviction via LIKE wildcard)");
        } finally {
            store.close();
        }
    }
}
