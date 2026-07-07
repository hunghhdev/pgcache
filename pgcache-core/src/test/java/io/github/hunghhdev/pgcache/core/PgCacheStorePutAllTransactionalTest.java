package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * putAll must be atomic like Redis MSET: a failure mid-batch must leave
 * no partial prefix of entries written.
 */
@Testcontainers
class PgCacheStorePutAllTransactionalTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private PgCacheStore store;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        store = PgCacheStore.builder().dataSource(dataSource).build();
        store.clear();
    }

    @Test
    void ttlVariantMustNotLeavePartialWritesOnFailure() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("batch-ok-1", "v1");
        // PostgreSQL TEXT rejects NUL bytes - this entry fails at the database
        entries.put("bad" + (char) 0 + "key", "v2");
        entries.put("batch-ok-3", "v3");

        assertThrows(PgCacheException.class, () -> store.putAll(entries, Duration.ofMinutes(5)));

        assertFalse(store.containsKey("batch-ok-1"), "no partial prefix may remain after a failed batch");
        assertFalse(store.containsKey("batch-ok-3"));
    }

    @Test
    void permanentVariantMustNotLeavePartialWritesOnFailure() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("perm-ok-1", "v1");
        entries.put("bad" + (char) 0 + "key", "v2");

        assertThrows(PgCacheException.class, () -> store.putAll(entries));

        assertFalse(store.containsKey("perm-ok-1"), "no partial prefix may remain after a failed batch");
    }

    @Test
    void successfulBatchCommitsAllEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ok-1", "v1");
        entries.put("ok-2", "v2");

        store.putAll(entries, Duration.ofMinutes(5));

        assertTrue(store.containsKey("ok-1"));
        assertTrue(store.containsKey("ok-2"));
    }
}
