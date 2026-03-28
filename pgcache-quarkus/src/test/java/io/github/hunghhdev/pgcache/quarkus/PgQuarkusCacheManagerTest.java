package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PgQuarkusCacheManager.
 */
@ExtendWith(MockitoExtension.class)
class PgQuarkusCacheManagerTest {

    @Mock
    private PgCacheStore cacheStore;

    private PgQuarkusCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new PgQuarkusCacheManager(
            cacheStore,
            Duration.ofHours(1),
            true,
            TTLPolicy.ABSOLUTE
        );
    }

    @Test
    void getCache_createsNewCache() {
        Optional<Cache> cache = cacheManager.getCache("myCache");

        assertTrue(cache.isPresent());
        assertEquals("myCache", cache.get().getName());
    }

    @Test
    void getCache_returnsSameCacheForSameName() {
        Optional<Cache> cache1 = cacheManager.getCache("myCache");
        Optional<Cache> cache2 = cacheManager.getCache("myCache");

        assertTrue(cache1.isPresent());
        assertTrue(cache2.isPresent());
        assertSame(cache1.get(), cache2.get());
    }

    @Test
    void getCache_createsDifferentCachesForDifferentNames() {
        Optional<Cache> cache1 = cacheManager.getCache("cache1");
        Optional<Cache> cache2 = cacheManager.getCache("cache2");

        assertTrue(cache1.isPresent());
        assertTrue(cache2.isPresent());
        assertNotSame(cache1.get(), cache2.get());
    }

    @Test
    void getCacheNames_returnsAllCacheNames() {
        cacheManager.getCache("cache1");
        cacheManager.getCache("cache2");
        cacheManager.getCache("cache3");

        Collection<String> names = cacheManager.getCacheNames();

        assertEquals(3, names.size());
        assertTrue(names.contains("cache1"));
        assertTrue(names.contains("cache2"));
        assertTrue(names.contains("cache3"));
    }

    @Test
    void getCacheNames_emptyWhenNoCaches() {
        Collection<String> names = cacheManager.getCacheNames();

        assertTrue(names.isEmpty());
    }

    @Test
    void getOrCreateCache_createsCacheWithCorrectConfig() {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("myCache");

        assertNotNull(cache);
        assertEquals("myCache", cache.getName());
    }

    @Test
    void getCacheStore_returnsUnderlyingStore() {
        assertSame(cacheStore, cacheManager.getCacheStore());
    }

    @Test
    void cacheConfig_appliesPerCacheSettings() {
        Map<String, PgQuarkusCacheManager.CacheConfig> configs = new HashMap<>();
        PgQuarkusCacheManager.CacheConfig config = new PgQuarkusCacheManager.CacheConfig(
            Duration.ofMinutes(30),
            TTLPolicy.SLIDING,
            false
        );
        configs.put("customCache", config);

        PgQuarkusCacheManager customManager = new PgQuarkusCacheManager(
            cacheStore,
            Duration.ofHours(1),
            true,
            TTLPolicy.ABSOLUTE,
            configs
        );

        PgQuarkusCache cache = customManager.getOrCreateCache("customCache");
        assertNotNull(cache);
        // The cache is created with custom config - verify it's a PgQuarkusCache
        assertEquals("customCache", cache.getName());
    }

    @Test
    void cacheConfig_withoutAllowNullOverride_inheritsGlobalFalse() {
        when(cacheStore.getAsync(anyString(), any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        Map<String, PgQuarkusCacheManager.CacheConfig> configs = new HashMap<>();
        PgQuarkusCacheManager.CacheConfig config = new PgQuarkusCacheManager.CacheConfig();
        config.setTtl(Duration.ofMinutes(30));
        configs.put("customCache", config);

        PgQuarkusCacheManager customManager = new PgQuarkusCacheManager(
            cacheStore,
            Duration.ofHours(1),
            false,
            TTLPolicy.ABSOLUTE,
            configs
        );

        PgQuarkusCache cache = customManager.getOrCreateCache("customCache");
        AtomicInteger loads = new AtomicInteger(0);

        String first = cache.<String, String>get("null-key", k -> {
            loads.incrementAndGet();
            return null;
        }).await().indefinitely();

        String second = cache.<String, String>get("null-key", k -> {
            loads.incrementAndGet();
            return null;
        }).await().indefinitely();

        assertNull(first);
        assertNull(second);
        assertEquals(2, loads.get(), "Per-cache TTL-only config should inherit global allowNullValues=false");
    }
}
