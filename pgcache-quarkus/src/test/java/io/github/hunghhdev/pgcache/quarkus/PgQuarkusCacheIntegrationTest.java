package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.cache.Cache;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PgCache Quarkus integration with real PostgreSQL.
 */
@Testcontainers
class PgQuarkusCacheIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private PgCacheStore cacheStore;
    private PgQuarkusCacheManager cacheManager;

    @BeforeAll
    static void beforeAll() {
        postgres.start();
    }

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        cacheStore = PgCacheStore.builder()
                .dataSource(dataSource)
                .allowNullValues(true)
                .build();
        cacheManager = new PgQuarkusCacheManager(
                cacheStore,
                Duration.ofMinutes(5),
                true,
                TTLPolicy.ABSOLUTE
        );
    }

    @AfterEach
    void tearDown() {
        if (cacheStore != null) {
            cacheStore.clear();
        }
    }

    @Test
    void testCacheManagerCreation() {
        assertNotNull(cacheManager);
        assertSame(cacheStore, cacheManager.getCacheStore());
    }

    @Test
    void testCacheCreation() {
        Optional<Cache> cache = cacheManager.getCache("test-cache");

        assertTrue(cache.isPresent());
        assertTrue(cache.get() instanceof PgQuarkusCache);
        assertEquals("test-cache", cache.get().getName());

        // Test cache reuse
        Optional<Cache> cache2 = cacheManager.getCache("test-cache");
        assertSame(cache.get(), cache2.get());
    }

    @Test
    void testBasicCacheOperations() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("test-cache");

        // Test get with value loader (cache miss -> loads and stores)
        AtomicInteger loadCount = new AtomicInteger(0);
        String result = cache.<String, String>get("key1", k -> {
            loadCount.incrementAndGet();
            return "value1";
        }).await().indefinitely();

        assertEquals("value1", result);
        assertEquals(1, loadCount.get());

        // Test get again (cache hit)
        String result2 = cache.<String, String>get("key1", k -> {
            loadCount.incrementAndGet();
            return "value2";
        }).await().indefinitely();

        assertEquals("value1", result2);
        assertEquals(1, loadCount.get()); // Should not increment

        // Test invalidate
        cache.invalidate("key1").await().indefinitely();

        // After invalidate, should load again
        String result3 = cache.<String, String>get("key1", k -> {
            loadCount.incrementAndGet();
            return "value3";
        }).await().indefinitely();

        assertEquals("value3", result3);
        assertEquals(2, loadCount.get());
    }

    @Test
    void testInvalidateAll() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("test-cache");

        // Store multiple values
        cache.<String, String>get("key1", k -> "value1").await().indefinitely();
        cache.<String, String>get("key2", k -> "value2").await().indefinitely();
        cache.<String, String>get("key3", k -> "value3").await().indefinitely();

        // Invalidate all
        cache.invalidateAll().await().indefinitely();

        // All should reload
        AtomicInteger loadCount = new AtomicInteger(0);
        cache.<String, String>get("key1", k -> {
            loadCount.incrementAndGet();
            return "new-value";
        }).await().indefinitely();

        assertEquals(1, loadCount.get());
    }

    @Test
    void testCacheSize() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("size-test-cache");

        assertEquals(0, cache.size());

        cache.<String, String>get("key1", k -> "value1").await().indefinitely();
        cache.<String, String>get("key2", k -> "value2").await().indefinitely();

        assertEquals(2, cache.size());

        cache.invalidate("key1").await().indefinitely();
        assertEquals(1, cache.size());

        cache.invalidateAll().await().indefinitely();
        assertEquals(0, cache.size());
    }

    @Test
    void testMultipleCaches() {
        Optional<Cache> cache1 = cacheManager.getCache("cache1");
        Optional<Cache> cache2 = cacheManager.getCache("cache2");

        assertTrue(cache1.isPresent());
        assertTrue(cache2.isPresent());
        assertNotSame(cache1.get(), cache2.get());

        Collection<String> names = cacheManager.getCacheNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("cache1"));
        assertTrue(names.contains("cache2"));
    }

    @Test
    void testNullValueCaching() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("null-test-cache");

        AtomicInteger loadCount = new AtomicInteger(0);

        // Load null value
        String result = cache.<String, String>get("nullKey", k -> {
            loadCount.incrementAndGet();
            return null;
        }).await().indefinitely();

        assertNull(result);
        assertEquals(1, loadCount.get());

        // Should return cached null (not reload)
        String result2 = cache.<String, String>get("nullKey", k -> {
            loadCount.incrementAndGet();
            return "should-not-load";
        }).await().indefinitely();

        assertNull(result2);
        assertEquals(1, loadCount.get()); // Should not increment
    }

    @Test
    void testAsyncValueLoader() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("async-test-cache");

        AtomicInteger loadCount = new AtomicInteger(0);

        // Test async get
        String result = cache.<String, String>getAsync("asyncKey", k -> {
            loadCount.incrementAndGet();
            return Uni.createFrom().item("async-value");
        }).await().indefinitely();

        assertEquals("async-value", result);
        assertEquals(1, loadCount.get());

        // Cached - should not reload
        String result2 = cache.<String, String>getAsync("asyncKey", k -> {
            loadCount.incrementAndGet();
            return Uni.createFrom().item("new-async-value");
        }).await().indefinitely();

        assertEquals("async-value", result2);
        assertEquals(1, loadCount.get());
    }

    @Test
    void testCacheNamePrefixing() {
        PgQuarkusCache cache1 = cacheManager.getOrCreateCache("prefixTest1");
        PgQuarkusCache cache2 = cacheManager.getOrCreateCache("prefixTest2");

        // Store same key in different caches
        cache1.<String, String>get("sameKey", k -> "value-from-cache1").await().indefinitely();
        cache2.<String, String>get("sameKey", k -> "value-from-cache2").await().indefinitely();

        // Retrieve and verify isolation
        AtomicInteger loadCount = new AtomicInteger(0);
        String result1 = cache1.<String, String>get("sameKey", k -> {
            loadCount.incrementAndGet();
            return "should-not-load";
        }).await().indefinitely();

        String result2 = cache2.<String, String>get("sameKey", k -> {
            loadCount.incrementAndGet();
            return "should-not-load";
        }).await().indefinitely();

        assertEquals("value-from-cache1", result1);
        assertEquals("value-from-cache2", result2);
        assertEquals(0, loadCount.get()); // Should not reload either
    }

    @Test
    void testCleanupExpired() throws InterruptedException {
        // Create cache with short TTL
        PgCacheStore shortTtlStore = cacheStore;
        PgQuarkusCacheManager shortTtlManager = new PgQuarkusCacheManager(
                shortTtlStore,
                Duration.ofMillis(100),
                true,
                TTLPolicy.ABSOLUTE
        );

        PgQuarkusCache cache = shortTtlManager.getOrCreateCache("expiring-cache");

        // Store value
        cache.<String, String>get("expiringKey", k -> "expiring-value").await().indefinitely();

        // Wait for expiration
        Thread.sleep(200);

        // Trigger cleanup
        cache.cleanupExpired();

        // Value should be reloaded
        AtomicInteger loadCount = new AtomicInteger(0);
        String result = cache.<String, String>get("expiringKey", k -> {
            loadCount.incrementAndGet();
            return "new-value";
        }).await().indefinitely();

        assertEquals("new-value", result);
        assertEquals(1, loadCount.get());
    }
}
