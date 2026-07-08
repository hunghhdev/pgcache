package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Spring-contract sync loader — {@code @Cacheable(sync=true)} — must route
 * through the store's single-flight getOrCompute so concurrent misses across
 * threads AND JVMs load exactly once (S5).
 */
@ExtendWith(MockitoExtension.class)
class PgCacheSyncLoaderTest {

    @Mock
    private PgCacheStore cacheStore;

    @SuppressWarnings("unchecked")
    private static Supplier<Object> supplierArg(org.mockito.invocation.InvocationOnMock inv) {
        return (Supplier<Object>) inv.getArgument(4);
    }

    @Test
    void missRoutesThroughSingleFlightGetOrCompute() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), false, TTLPolicy.SLIDING);

        when(cacheStore.get("test:k", Object.class, true)).thenReturn(Optional.empty());
        when(cacheStore.getOrCompute(eq("test:k"), eq(Object.class), eq(Duration.ofMinutes(5)),
                eq(TTLPolicy.SLIDING), any()))
                .thenAnswer(inv -> supplierArg(inv).get());

        String value = cache.get("k", () -> "loaded");

        assertEquals("loaded", value);
        verify(cacheStore).getOrCompute(eq("test:k"), eq(Object.class), eq(Duration.ofMinutes(5)),
                eq(TTLPolicy.SLIDING), any());
    }

    @Test
    void nullDefaultTtlRoutesPermanentGetOrCompute() {
        PgCache cache = new PgCache("test", cacheStore, null, false, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:k", Object.class, true)).thenReturn(Optional.empty());
        when(cacheStore.getOrCompute(eq("test:k"), eq(Object.class), eq((Duration) null),
                eq(TTLPolicy.ABSOLUTE), any()))
                .thenAnswer(inv -> supplierArg(inv).get());

        assertEquals("loaded", cache.get("k", () -> "loaded"));
    }

    @Test
    void loaderCheckedExceptionSurfacesAsValueRetrievalException() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), false, TTLPolicy.ABSOLUTE);
        Exception cause = new Exception("loader failed");

        when(cacheStore.get("test:k", Object.class, true)).thenReturn(Optional.empty());
        when(cacheStore.getOrCompute(eq("test:k"), eq(Object.class), any(), eq(TTLPolicy.ABSOLUTE), any()))
                .thenAnswer(inv -> supplierArg(inv).get());

        Cache.ValueRetrievalException thrown = assertThrows(Cache.ValueRetrievalException.class,
                () -> cache.get("k", () -> {
                    throw cause;
                }));
        assertSame(cause, thrown.getCause(), "original loader failure must be the cause");
    }

    @Test
    void cacheHitShortCircuitsWithoutGetOrCompute() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), false, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:k", Object.class, true)).thenReturn(Optional.of("cached"));

        assertEquals("cached", cache.get("k", () -> "fresh"));
        verify(cacheStore, org.mockito.Mockito.never()).getOrCompute(any(), any(), any(), any(), any());
    }
}
