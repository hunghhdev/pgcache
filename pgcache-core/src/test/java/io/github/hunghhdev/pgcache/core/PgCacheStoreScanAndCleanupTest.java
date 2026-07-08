package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * scanKeys must stream matching keys with keyset pagination instead of
 * materializing the whole key set; batched cleanupExpired must remove all
 * expired rows across several LIMIT-ed deletes.
 */
@Testcontainers
class PgCacheStoreScanAndCleanupTest {

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

    private PgCacheStore store(String table) {
        return PgCacheStore.builder().dataSource(dataSource).tableName(table).build();
    }

    // ==================== scanKeys ====================

    @Test
    void scanKeysStreamsAllMatchesAcrossMultipleBatches() {
        try (PgCacheStore store = store("sc_scan")) {
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < 25; i++) {
                String key = String.format("scan:%03d", i);
                store.put(key, i, Duration.ofMinutes(5));
                expected.add(key);
            }
            store.put("other:1", 1, Duration.ofMinutes(5));

            Set<String> scanned = new HashSet<>();
            for (String key : store.scanKeys("scan:%", 10)) {
                assertTrue(scanned.add(key), "keys must not repeat across batches: " + key);
            }

            assertEquals(expected, scanned);
        }
    }

    @Test
    void scanKeysSkipsExpiredEntries() throws InterruptedException {
        try (PgCacheStore store = store("sc_scan_exp")) {
            store.put("scan:live", "v", Duration.ofMinutes(5));
            store.put("scan:dead", "v", Duration.ofSeconds(1));
            Thread.sleep(1200);

            List<String> scanned = new ArrayList<>();
            store.scanKeys("scan:%", 10).forEach(scanned::add);

            assertEquals(List.of("scan:live"), scanned);
        }
    }

    @Test
    void scanKeysIsLazyBetweenBatches() {
        try (PgCacheStore store = store("sc_scan_lazy")) {
            for (int i = 0; i < 6; i++) {
                store.put("scan:" + i, i, Duration.ofMinutes(5));
            }

            // consume only the first batch, then add a key beyond the current cursor:
            // keyset pagination must pick it up in the next batch
            var iterator = store.scanKeys("scan:%", 3).iterator();
            List<String> collected = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                collected.add(iterator.next());
            }
            store.put("scan:9", 9, Duration.ofMinutes(5));
            iterator.forEachRemaining(collected::add);

            assertTrue(collected.contains("scan:9"),
                    "keyset pagination reads each batch fresh — a key past the cursor must appear");
            assertEquals(7, collected.size());
        }
    }

    @Test
    void scanKeysValidatesArguments() {
        try (PgCacheStore store = store("sc_scan_args")) {
            assertThrows(PgCacheException.class, () -> store.scanKeys(null, 10));
            assertThrows(PgCacheException.class, () -> store.scanKeys("", 10));
            assertThrows(PgCacheException.class, () -> store.scanKeys("x%", 0));
            assertThrows(PgCacheException.class, () -> store.scanKeys("x%", -1));
        }
    }

    // ==================== batched cleanupExpired ====================

    @Test
    void batchedCleanupRemovesAllExpiredAcrossBatches() throws InterruptedException {
        try (PgCacheStore store = store("sc_cleanup")) {
            for (int i = 0; i < 25; i++) {
                store.put("dead:" + i, i, Duration.ofSeconds(1));
            }
            for (int i = 0; i < 5; i++) {
                store.put("live:" + i, i, Duration.ofMinutes(5));
            }
            Thread.sleep(1200);

            int removed = store.cleanupExpired(10);

            assertEquals(25, removed, "every expired row must be removed even when it takes multiple batches");
            assertEquals(5, store.size(), "live rows must survive the cleanup");
        }
    }

    @Test
    void batchedCleanupValidatesBatchSize() {
        try (PgCacheStore store = store("sc_cleanup_args")) {
            assertThrows(PgCacheException.class, () -> store.cleanupExpired(0));
            assertThrows(PgCacheException.class, () -> store.cleanupExpired(-5));
        }
    }
}
