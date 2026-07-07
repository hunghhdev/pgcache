package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.cache.Cache;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgCacheTest {

    @Mock
    private PgCacheStore cacheStore;

    @Test
    void putWithTtl_rethrowsBackendFailure() {
        // Write strategy (since 1.7.0): write methods rethrow PgCacheException —
        // the TTL overload must behave like the Spring-contract put()
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        org.mockito.Mockito.doThrow(new io.github.hunghhdev.pgcache.core.PgCacheException("store down"))
                .when(cacheStore).put(eq("test:key1"), eq("v"), eq(Duration.ofSeconds(10)), eq(TTLPolicy.SLIDING));

        assertThrows(io.github.hunghhdev.pgcache.core.PgCacheException.class,
                () -> cache.put("key1", "v", Duration.ofSeconds(10), TTLPolicy.SLIDING));
    }

    @Test
    void getWithNullType_doesNotThrowAndReturnsNull() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        when(cacheStore.get(eq("test:key1"), isNull(), eq(true)))
                .thenThrow(new IllegalArgumentException("type cannot be null"));

        assertDoesNotThrow(() -> assertNull(cache.get("key1", (Class<Object>) null)));
    }

    @Test
    void getValueWrapper_swallowsBackendFailure() {
        // Read strategy (since 1.7.0): swallow + log warn + return null
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:key1", Object.class, true))
                .thenThrow(new RuntimeException("database down"));

        assertDoesNotThrow(() -> assertNull(cache.get("key1")));
    }

    @Test
    void getTyped_swallowsBackendFailure() {
        // Read strategy (since 1.7.0): swallow + log warn + return null
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:key1", String.class, true))
                .thenThrow(new RuntimeException("database down"));

        assertDoesNotThrow(() -> assertNull(cache.get("key1", String.class)));
    }

    @Test
    void put_throwsPgCacheException_onBackendFailure() {
        // Write strategy (since 1.7.0): rethrow as PgCacheException
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        org.mockito.Mockito.doThrow(new RuntimeException("database down"))
                .when(cacheStore).put(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(Duration.class),
                        org.mockito.ArgumentMatchers.any(TTLPolicy.class));

        io.github.hunghhdev.pgcache.core.PgCacheException ex = assertThrows(
                io.github.hunghhdev.pgcache.core.PgCacheException.class,
                () -> cache.put("key1", "value1"));
        assertEquals("database down", ex.getCause().getMessage());
    }
}
