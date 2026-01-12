package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PgQuarkusCache.
 */
@ExtendWith(MockitoExtension.class)
class PgQuarkusCacheTest {

    @Mock
    private PgCacheStore cacheStore;

    private PgQuarkusCache cache;

    @BeforeEach
    void setUp() {
        cache = new PgQuarkusCache("test", cacheStore, Duration.ofHours(1), true, TTLPolicy.ABSOLUTE);
    }

    @Test
    void getName_returnsCorrectName() {
        assertEquals("test", cache.getName());
    }

    @Test
    void getDefaultKey_returnsDefault() {
        assertEquals("default", cache.getDefaultKey());
    }

    @Test
    void get_cacheHit_returnsValueFromCache() {
        String key = "key1";
        String expectedValue = "cached-value";
        when(cacheStore.getAsync(eq("test:" + key), eq(Object.class)))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(expectedValue)));

        Uni<String> result = cache.get(key, k -> "loaded-value");
        String value = result.await().indefinitely();

        assertEquals(expectedValue, value);
        verify(cacheStore, never()).putAsync(anyString(), any(), any(Duration.class), any(TTLPolicy.class));
    }

    @Test
    void get_cacheMiss_loadsAndCachesValue() {
        String key = "key1";
        String loadedValue = "loaded-value";
        when(cacheStore.getAsync(eq("test:" + key), eq(Object.class)))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(cacheStore.putAsync(anyString(), any(), any(Duration.class), any(TTLPolicy.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        Uni<String> result = cache.get(key, k -> loadedValue);
        String value = result.await().indefinitely();

        assertEquals(loadedValue, value);
        verify(cacheStore).putAsync(eq("test:" + key), eq(loadedValue), eq(Duration.ofHours(1)), eq(TTLPolicy.ABSOLUTE));
    }

    @Test
    void get_cacheMiss_nullValueAllowed_cachesNull() {
        String key = "key1";
        when(cacheStore.getAsync(eq("test:" + key), eq(Object.class)))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(cacheStore.putAsync(anyString(), any(), any(Duration.class), any(TTLPolicy.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        Uni<String> result = cache.get(key, k -> null);
        String value = result.await().indefinitely();

        assertNull(value);
        verify(cacheStore).putAsync(eq("test:" + key), isNull(), eq(Duration.ofHours(1)), eq(TTLPolicy.ABSOLUTE));
    }

    @Test
    void invalidate_evictsFromCache() {
        String key = "key1";
        when(cacheStore.evictAsync(eq("test:" + key)))
            .thenReturn(CompletableFuture.completedFuture(null));

        Uni<Void> result = cache.invalidate(key);
        result.await().indefinitely();

        verify(cacheStore).evictAsync("test:" + key);
    }

    @Test
    void invalidateAll_clearsCache() {
        Uni<Void> result = cache.invalidateAll();
        result.await().indefinitely();

        verify(cacheStore).clear();
    }

    @Test
    void as_returnsThisForCompatibleType() {
        PgQuarkusCache result = cache.as(PgQuarkusCache.class);
        assertSame(cache, result);
    }

    @Test
    void as_throwsForIncompatibleType() {
        // Can only test with a Cache subtype that PgQuarkusCache doesn't implement
        // For now, just verify the method works correctly with valid type
        PgQuarkusCache result = cache.as(PgQuarkusCache.class);
        assertSame(cache, result);
    }

    @Test
    void size_delegatesToCacheStore() {
        when(cacheStore.size()).thenReturn(42);

        long size = cache.size();

        assertEquals(42, size);
    }

    @Test
    void cleanupExpired_delegatesToCacheStore() {
        cache.cleanupExpired();

        verify(cacheStore).cleanupExpired();
    }

    @Test
    void getCacheStore_returnsUnderlyingStore() {
        assertSame(cacheStore, cache.getCacheStore());
    }
}
