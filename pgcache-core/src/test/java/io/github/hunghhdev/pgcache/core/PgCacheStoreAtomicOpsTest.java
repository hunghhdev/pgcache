package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis-parity atomic operations: every operation must be a single SQL
 * statement (no read-modify-write races) with Redis-compatible semantics.
 */
@Testcontainers
class PgCacheStoreAtomicOpsTest {

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

    // ==================== increment / decrement ====================

    @Test
    void incrementCreatesMissingKeyWithDeltaAndTtl() {
        try (PgCacheStore store = store("ao_incr_create")) {
            assertEquals(5, store.increment("counter", 5, Duration.ofMinutes(5)));

            assertEquals(Optional.of(5L), store.get("counter", Long.class));
            assertTrue(store.getTtlInfo("counter").isExpiring(), "created counter must carry the requested TTL");
        }
    }

    @Test
    void incrementWithoutTtlCreatesPermanentCounter() {
        try (PgCacheStore store = store("ao_incr_perm")) {
            assertEquals(1, store.increment("counter", 1));
            assertTrue(store.getTtlInfo("counter").isPermanent());
        }
    }

    @Test
    void incrementAddsToLiveCounterAndKeepsItsTtl() {
        try (PgCacheStore store = store("ao_incr_add")) {
            store.increment("counter", 5, Duration.ofMinutes(10));

            long result = store.increment("counter", 3, Duration.ofSeconds(5));

            assertEquals(8, result);
            TtlInfo ttl = store.getTtlInfo("counter");
            assertTrue(ttl.isExpiring());
            assertTrue(ttl.getRemaining().get().getSeconds() > 300,
                    "increment on a live counter must keep its TTL (Redis INCR), was " + ttl.getRemaining().get());
        }
    }

    @Test
    void incrementOnExpiredCounterStartsFreshFromDelta() throws InterruptedException {
        try (PgCacheStore store = store("ao_incr_expired")) {
            store.put("counter", 100L, Duration.ofSeconds(1));
            Thread.sleep(1200);

            assertEquals(7, store.increment("counter", 7, Duration.ofMinutes(5)),
                    "an expired counter is absent — increment must restart from the delta");
        }
    }

    @Test
    void incrementOnNonNumericValueThrows() {
        try (PgCacheStore store = store("ao_incr_nan")) {
            store.put("k", "not a number");

            assertThrows(PgCacheException.class, () -> store.increment("k", 1));
        }
    }

    @Test
    void decrementSubtracts() {
        try (PgCacheStore store = store("ao_decr")) {
            store.increment("counter", 10);
            assertEquals(7, store.decrement("counter", 3));
            assertEquals(-3, store.decrement("missing", 3), "decrement on a missing key starts from zero");
        }
    }

    @Test
    void concurrentIncrementsLoseNoUpdates() throws Exception {
        int threads = 8;
        int perThread = 25;
        try (PgCacheStore store = store("ao_incr_race")) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = IntStream.range(0, threads)
                        .mapToObj(i -> pool.submit(() -> {
                            for (int j = 0; j < perThread; j++) {
                                store.increment("hot", 1);
                            }
                            return null;
                        }))
                        .collect(Collectors.toList());
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(Optional.of((long) threads * perThread), store.get("hot", Long.class));
        }
    }

    // ==================== getAndDelete ====================

    @Test
    void getAndDeleteReturnsValueAndRemovesEntry() {
        try (PgCacheStore store = store("ao_gad")) {
            store.put("k", "v", Duration.ofMinutes(5));

            assertEquals(Optional.of("v"), store.getAndDelete("k", String.class));
            assertFalse(store.containsKey("k"));
            assertEquals(Optional.empty(), store.getAndDelete("k", String.class));
        }
    }

    @Test
    void getAndDeleteOnExpiredEntryReturnsEmpty() throws InterruptedException {
        try (PgCacheStore store = store("ao_gad_expired")) {
            store.put("k", "v", Duration.ofSeconds(1));
            Thread.sleep(1200);

            assertEquals(Optional.empty(), store.getAndDelete("k", String.class));
        }
    }

    // ==================== getAndPut ====================

    @Test
    void getAndPutReturnsOldValueAndStoresNew() {
        try (PgCacheStore store = store("ao_gap")) {
            store.put("k", "old", Duration.ofMinutes(5));

            Optional<Object> previous = store.getAndPut("k", "new", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);

            assertEquals(Optional.of("old"), previous);
            assertEquals(Optional.of("new"), store.get("k", String.class));
        }
    }

    @Test
    void getAndPutOnMissingKeyReturnsEmptyAndStores() {
        try (PgCacheStore store = store("ao_gap_miss")) {
            Optional<Object> previous = store.getAndPut("k", "v", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);

            assertEquals(Optional.empty(), previous);
            assertEquals(Optional.of("v"), store.get("k", String.class));
        }
    }

    @Test
    void getAndPutOnExpiredEntryReturnsEmpty() throws InterruptedException {
        try (PgCacheStore store = store("ao_gap_expired")) {
            store.put("k", "stale", Duration.ofSeconds(1));
            Thread.sleep(1200);

            Optional<Object> previous = store.getAndPut("k", "fresh", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);

            assertEquals(Optional.empty(), previous, "an expired value must not resurface through getAndPut");
            assertEquals(Optional.of("fresh"), store.get("k", String.class));
        }
    }

    @Test
    void getAndPutPermanentVariantStoresWithoutTtl() {
        try (PgCacheStore store = store("ao_gap_perm")) {
            store.getAndPut("k", "v");
            assertTrue(store.getTtlInfo("k").isPermanent());
        }
    }

    @Test
    void getAndPutSurfacesCachedNullAsMarker() {
        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource).tableName("ao_gap_null").allowNullValues(true).build()) {
            store.put("k", null, Duration.ofMinutes(5));

            Optional<Object> previous = store.getAndPut("k", "v", Duration.ofMinutes(5), TTLPolicy.ABSOLUTE);

            assertTrue(previous.isPresent());
            assertTrue(previous.get() instanceof NullValueMarker,
                    "cached null must surface as NullValueMarker like get()/putIfAbsent");
        }
    }

    // ==================== persist / expireAt ====================

    @Test
    void persistRemovesTtl() {
        try (PgCacheStore store = store("ao_persist")) {
            store.put("k", "v", Duration.ofSeconds(30));

            assertTrue(store.persist("k"));
            assertTrue(store.getTtlInfo("k").isPermanent());
        }
    }

    @Test
    void persistReturnsFalseForPermanentOrMissingKey() {
        try (PgCacheStore store = store("ao_persist_noop")) {
            store.put("k", "v");

            assertFalse(store.persist("k"), "Redis PERSIST returns 0 when the key has no TTL");
            assertFalse(store.persist("missing"));
        }
    }

    @Test
    void expireAtFutureDeadlineSetsAbsoluteTtl() {
        try (PgCacheStore store = store("ao_expireat")) {
            store.put("k", "v");

            assertTrue(store.expireAt("k", Instant.now().plusSeconds(60)));

            TtlInfo ttl = store.getTtlInfo("k");
            assertTrue(ttl.isExpiring());
            long remaining = ttl.getRemaining().get().getSeconds();
            assertTrue(remaining > 50 && remaining <= 61, "remaining should be ~60s, was " + remaining);
        }
    }

    @Test
    void expireAtPastDeadlineDeletesKey() {
        try (PgCacheStore store = store("ao_expireat_past")) {
            store.put("k", "v");

            assertTrue(store.expireAt("k", Instant.now().minusSeconds(5)),
                    "Redis EXPIREAT with a past timestamp deletes the key and reports success");
            assertFalse(store.containsKey("k"));
        }
    }

    @Test
    void expireAtMissingKeyReturnsFalse() {
        try (PgCacheStore store = store("ao_expireat_miss")) {
            assertFalse(store.expireAt("missing", Instant.now().plusSeconds(60)));
        }
    }

    // ==================== getTtlInfo ====================

    @Test
    void getTtlInfoDistinguishesAllThreeStates() throws InterruptedException {
        try (PgCacheStore store = store("ao_ttlinfo")) {
            assertEquals(TtlInfo.missing(), store.getTtlInfo("absent"));

            store.put("perm", "v");
            assertEquals(TtlInfo.permanent(), store.getTtlInfo("perm"));

            store.put("temp", "v", Duration.ofMinutes(5));
            TtlInfo expiring = store.getTtlInfo("temp");
            assertTrue(expiring.isExpiring());
            long secs = expiring.getRemaining().get().getSeconds();
            assertTrue(secs > 290 && secs <= 300, "remaining should be ~300s, was " + secs);

            store.put("gone", "v", Duration.ofSeconds(1));
            Thread.sleep(1200);
            assertEquals(TtlInfo.missing(), store.getTtlInfo("gone"),
                    "a logically expired entry must report MISSING even before cleanup");
        }
    }
}
