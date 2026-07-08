package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event and statistics consistency (L7/L8): batch and pattern operations must
 * fire listener events like their single-key counterparts, onClear fires only
 * when something was cleared, and expiry is counted separately from explicit
 * eviction (Redis expired_keys vs evicted_keys).
 */
@Testcontainers
class PgCacheStoreEventConsistencyTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private PGSimpleDataSource dataSource;
    private List<String> putEvents;
    private List<String> evictEvents;
    private AtomicInteger clearEvents;
    private CacheEventListener listener;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        putEvents = new ArrayList<>();
        evictEvents = new ArrayList<>();
        clearEvents = new AtomicInteger();
        listener = new CacheEventListener() {
            @Override
            public void onPut(String key, Object value) {
                putEvents.add(key);
            }

            @Override
            public void onEvict(String key) {
                evictEvents.add(key);
            }

            @Override
            public void onClear() {
                clearEvents.incrementAndGet();
            }
        };
    }

    private PgCacheStore store(String table) {
        return PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName(table)
                .addEventListener(listener)
                .build();
    }

    @Test
    void putAllFiresOnPutPerEntry() {
        try (PgCacheStore store = store("ev_putall")) {
            Map<String, String> entries = new HashMap<>();
            entries.put("k1", "v1");
            entries.put("k2", "v2");

            store.putAll(entries, Duration.ofMinutes(5));
            store.putAll(java.util.Collections.singletonMap("k3", "v3"));

            assertEquals(new HashSet<>(Arrays.asList("k1", "k2", "k3")), new HashSet<>(putEvents),
                    "both putAll variants must fire onPut for every entry");
        }
    }

    @Test
    void evictAllFiresOnEvictOnlyForActuallyDeletedKeys() {
        try (PgCacheStore store = store("ev_evictall")) {
            store.put("k1", "v", Duration.ofMinutes(5));
            store.put("k2", "v", Duration.ofMinutes(5));
            putEvents.clear();

            int deleted = store.evictAll(Arrays.asList("k1", "k2", "missing"));

            assertEquals(2, deleted);
            assertEquals(new HashSet<>(Arrays.asList("k1", "k2")), new HashSet<>(evictEvents),
                    "onEvict must fire per deleted key and not for missing keys");
        }
    }

    @Test
    void evictByPatternFiresOnEvictPerDeletedKey() {
        try (PgCacheStore store = store("ev_pattern")) {
            store.put("user:1", "v", Duration.ofMinutes(5));
            store.put("user:2", "v", Duration.ofMinutes(5));
            store.put("other:1", "v", Duration.ofMinutes(5));

            int deleted = store.evictByPattern("user:%");

            assertEquals(2, deleted);
            assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")), new HashSet<>(evictEvents));
        }
    }

    @Test
    void clearFiresOnClearOnlyWhenRowsWereDeleted() {
        try (PgCacheStore store = store("ev_clear")) {
            store.clear();
            assertEquals(0, clearEvents.get(), "clearing an empty cache must not fire onClear");

            store.put("k", "v", Duration.ofMinutes(5));
            store.clear();
            assertEquals(1, clearEvents.get());
        }
    }

    @Test
    void cleanupCountsExpiredSeparatelyFromEvictions() throws InterruptedException {
        try (PgCacheStore store = store("ev_expired")) {
            store.put("dead1", "v", Duration.ofSeconds(1));
            store.put("dead2", "v", Duration.ofSeconds(1));
            store.put("live", "v", Duration.ofMinutes(5));
            store.evict("live");
            store.resetStatistics();

            Thread.sleep(1200);
            int removed = store.cleanupExpired();

            assertEquals(2, removed);
            CacheStatistics stats = store.getStatistics();
            assertEquals(2, stats.getExpiredCount(), "expiry must be counted as expiredCount");
            assertEquals(0, stats.getEvictionCount(), "expiry must no longer inflate evictionCount");
        }
    }

    @Test
    void deserializationFailureCountsAsMiss() {
        try (PgCacheStore store = store("ev_deser")) {
            store.put("k", "not a number", Duration.ofMinutes(5));
            store.resetStatistics();

            assertThrows(PgCacheException.class, () -> store.get("k", Integer.class));

            CacheStatistics stats = store.getStatistics();
            assertEquals(0, stats.getHitCount());
            assertEquals(1, stats.getMissCount(),
                    "a value that cannot be deserialized is not a usable hit — count it as a miss");
        }
    }
}
