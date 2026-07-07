package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Robustness fixes: shutdown-hook lifecycle, oversized batch key sets,
 * and retry behavior on non-transient connection failures.
 */
@Testcontainers
class PgCacheStoreRobustnessTest {

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

    // ---------- L2: shutdown hook must be removed on close ----------

    @SuppressWarnings("unchecked")
    private static long pgcacheShutdownHookCount() throws Exception {
        Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
        Field field = hooksClass.getDeclaredField("hooks");
        field.setAccessible(true);
        Map<Thread, Thread> hooks = (Map<Thread, Thread>) field.get(null);
        synchronized (hooksClass) {
            return hooks.keySet().stream()
                    .filter(t -> "pgcache-shutdown-hook".equals(t.getName()))
                    .count();
        }
    }

    @Test
    void closeMustDeregisterTheShutdownHook() throws Exception {
        long before = pgcacheShutdownHookCount();

        PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName("hook_leak_cache")
                .enableBackgroundCleanup(true)
                .cleanupInterval(Duration.ofMinutes(5))
                .build();
        assertEquals(before + 1, pgcacheShutdownHookCount(), "hook registered on start");

        store.close();
        assertEquals(before, pgcacheShutdownHookCount(),
                "close() must remove the shutdown hook — otherwise redeploys leak threads and pin the DataSource");
    }

    // ---------- L6: batches beyond the 32767 bind-parameter limit ----------

    @Test
    void getAllMustHandleMoreThan32kKeys() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("chunk_cache").build()) {
            store.put("chunk-hit", "v", Duration.ofMinutes(5));

            List<String> keys = IntStream.range(0, 33_000)
                    .mapToObj(i -> "chunk-miss-" + i)
                    .collect(Collectors.toCollection(ArrayList::new));
            keys.add("chunk-hit");

            Map<String, String> result = store.getAll(keys, String.class);
            assertEquals(1, result.size(), "one existing key among 33k must be found");
            assertEquals("v", result.get("chunk-hit"));
        }
    }

    @Test
    void evictAllMustHandleMoreThan32kKeys() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("chunk_cache2").build()) {
            store.put("evict-hit", "v", Duration.ofMinutes(5));

            List<String> keys = IntStream.range(0, 33_000)
                    .mapToObj(i -> "evict-miss-" + i)
                    .collect(Collectors.toCollection(ArrayList::new));
            keys.add("evict-hit");

            assertDoesNotThrow(() -> store.evictAll(keys));
            assertFalse(store.containsKey("evict-hit"));
        }
    }

    // ---------- L9: no retries for non-transient failures ----------

    @Test
    void nonTransientConnectionFailuresMustNotBeRetried() throws SQLException {
        DataSource failingDataSource = mock(DataSource.class);
        // 28P01 = invalid_password: retrying cannot help
        when(failingDataSource.getConnection())
                .thenThrow(new SQLException("password authentication failed", "28P01"));

        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(failingDataSource)
                .autoCreateTable(false)
                .build()) {
            assertThrows(PgCacheException.class, () -> store.get("k", String.class));
        }

        verify(failingDataSource, times(1)).getConnection();
    }

    @Test
    void transientConnectionFailuresAreRetried() throws SQLException {
        DataSource flakyDataSource = mock(DataSource.class);
        // 08006 = connection_failure: transient, retry is appropriate
        when(flakyDataSource.getConnection())
                .thenThrow(new SQLException("connection failure", "08006"));

        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(flakyDataSource)
                .autoCreateTable(false)
                .build()) {
            assertThrows(PgCacheException.class, () -> store.get("k", String.class));
        }

        verify(flakyDataSource, times(3)).getConnection();
    }
}
