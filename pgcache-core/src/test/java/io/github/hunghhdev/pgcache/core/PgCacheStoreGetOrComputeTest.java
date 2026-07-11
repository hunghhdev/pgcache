package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * getOrCompute must be a single-flight read-through: on a miss exactly one
 * caller runs the loader (guarded by a transaction-scoped advisory lock),
 * concurrent callers wait and then read the freshly stored value.
 */
@Testcontainers
class PgCacheStoreGetOrComputeTest {

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
    void missComputesStoresAndReturnsWithTtl() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_miss").build()) {
            AtomicInteger loads = new AtomicInteger();

            String value = store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return "computed";
            });

            assertEquals("computed", value);
            assertEquals(1, loads.get());
            assertEquals(Optional.of("computed"), store.get("k", String.class));
            Optional<Duration> ttl = store.getRemainingTTL("k");
            assertTrue(ttl.isPresent(), "computed value must be stored with the requested TTL");
            assertTrue(ttl.get().getSeconds() > 290, "TTL should be close to 5 minutes, was " + ttl.get());
        }
    }

    @Test
    void nullTtlStoresPermanentEntry() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_perm").build()) {
            store.getOrCompute("k", String.class, null, () -> "v");

            assertEquals(Optional.of("v"), store.get("k", String.class));
            assertFalse(store.getRemainingTTL("k").isPresent(), "null ttl must store a permanent entry");
        }
    }

    @Test
    void hitDoesNotInvokeLoader() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_hit").build()) {
            store.put("k", "cached", Duration.ofMinutes(5));
            AtomicInteger loads = new AtomicInteger();

            String value = store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return "fresh";
            });

            assertEquals("cached", value);
            assertEquals(0, loads.get());
        }
    }

    @Test
    void slidingPolicyIsStoredOnComputedValue() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_sliding").build()) {
            store.getOrCompute("k", String.class, Duration.ofMinutes(5), TTLPolicy.SLIDING, () -> "v");

            assertEquals(Optional.of(TTLPolicy.SLIDING), store.getTTLPolicy("k"));
        }
    }

    @Test
    @Timeout(60)
    void concurrentMissesInvokeLoaderExactlyOnce() throws Exception {
        int threads = 8;
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_flight").build()) {
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<String>> results = IntStream.range(0, threads)
                        .mapToObj(i -> pool.submit(() -> {
                            start.await();
                            return store.getOrCompute("hot", String.class, Duration.ofMinutes(5), () -> {
                                loads.incrementAndGet();
                                // widen the race window: everyone else must wait on the lock
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return "loaded";
                            });
                        }))
                        .collect(Collectors.toList());
                start.countDown();

                for (Future<String> f : results) {
                    assertEquals("loaded", f.get(30, TimeUnit.SECONDS));
                }
                assertEquals(1, loads.get(), "single-flight: only one thread may run the loader");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void loaderFailurePropagatesReleasesLockAndCachesNothing() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_fail").build()) {
            RuntimeException boom = new RuntimeException("loader blew up");

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                        throw boom;
                    }));
            assertSame(boom, thrown, "loader failure must propagate unwrapped");
            assertFalse(store.containsKey("k"), "failed load must cache nothing");

            // lock must have been released: the next call runs the loader again
            String recovered = store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> "second-try");
            assertEquals("second-try", recovered);
        }
    }

    @Test
    void nullFromLoaderWithAllowNullValuesIsCached() {
        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource).tableName("goc_null").allowNullValues(true).build()) {
            AtomicInteger loads = new AtomicInteger();

            assertNull(store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return null;
            }));
            assertNull(store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return "should not run";
            }));

            assertEquals(1, loads.get(), "cached null must satisfy subsequent calls without loading");
        }
    }

    @Test
    void nullFromLoaderWithoutAllowNullValuesIsNotCached() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_nonull").build()) {
            AtomicInteger loads = new AtomicInteger();

            assertNull(store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return null;
            }));
            assertNull(store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return null;
            }));

            assertEquals(2, loads.get(), "null must not be cached when allowNullValues=false");
            assertFalse(store.containsKey("k"));
        }
    }

    @Test
    void undeserializableRowIsRecomputedAndOverwritten() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_corrupt").build()) {
            // a row the requested class cannot map — e.g. written by another component
            store.put("k", Collections.singletonMap("a", 1), Duration.ofMinutes(5));
            AtomicInteger loads = new AtomicInteger();

            Integer first = store.getOrCompute("k", Integer.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return 42;
            });
            Integer second = store.getOrCompute("k", Integer.class, Duration.ofMinutes(5), () -> {
                loads.incrementAndGet();
                return 43;
            });

            assertEquals(Integer.valueOf(42), first);
            assertEquals(Integer.valueOf(42), second, "the recomputed value must be served from cache");
            assertEquals(1, loads.get(), "an undeserializable row must be overwritten, not bypassed on every call");
            assertEquals(Optional.of(42), store.get("k", Integer.class));
        }
    }

    @Test
    void failsOpenToDirectLoadWhenDatabaseIsDown() {
        DataSource broken = new PGSimpleDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("database is down", "57P01");
            }
        };

        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(broken).tableName("goc_down").autoCreateTable(false).build()) {

            String value = store.getOrCompute("k", String.class, Duration.ofMinutes(5), () -> "direct");
            assertEquals("direct", value, "read path must fail open to the loader when the DB is unreachable");
        }
    }

    @Test
    void nullLoaderIsRejected() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("goc_args").build()) {
            assertThrows(PgCacheException.class,
                    () -> store.getOrCompute("k", String.class, Duration.ofMinutes(5), null));
        }
    }
}
