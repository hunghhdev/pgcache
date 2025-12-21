package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgCacheHealthIndicatorTest {

    @Mock
    private PgCacheManager cacheManager;

    @Mock
    private PgCache cache;

    private PgCacheHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new PgCacheHealthIndicator(cacheManager);
    }

    @Test
    void health_returnsUp_whenCacheManagerIsHealthy() {
        when(cacheManager.getCacheCount()).thenReturn(1);
        when(cacheManager.getCacheNames()).thenReturn(Collections.singletonList("testCache"));
        when(cacheManager.getCache("testCache")).thenReturn(cache);
        when(cache.size()).thenReturn(10L);
        when(cache.getStatistics()).thenReturn(new CacheStatistics(100, 20, 50, 5));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(1, health.getDetails().get("cache.count"));
        assertEquals(10L, health.getDetails().get("cache.testCache.size"));
        assertEquals(10L, health.getDetails().get("cache.total.size"));
        assertEquals(100L, health.getDetails().get("stats.hits"));
        assertEquals(20L, health.getDetails().get("stats.misses"));
        assertEquals(50L, health.getDetails().get("stats.puts"));
        assertEquals(5L, health.getDetails().get("stats.evictions"));
    }

    @Test
    void health_returnsUp_withMultipleCaches() {
        Collection<String> cacheNames = Arrays.asList("cache1", "cache2");
        when(cacheManager.getCacheCount()).thenReturn(2);
        when(cacheManager.getCacheNames()).thenReturn(cacheNames);
        when(cacheManager.getCache("cache1")).thenReturn(cache);
        when(cacheManager.getCache("cache2")).thenReturn(cache);
        when(cache.size()).thenReturn(5L);
        when(cache.getStatistics()).thenReturn(new CacheStatistics(50, 10, 30, 2));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(2, health.getDetails().get("cache.count"));
        assertEquals(10L, health.getDetails().get("cache.total.size"));
    }

    @Test
    void health_returnsUp_withNoCaches() {
        when(cacheManager.getCacheCount()).thenReturn(0);
        when(cacheManager.getCacheNames()).thenReturn(Collections.emptyList());

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, health.getDetails().get("cache.count"));
        assertEquals(0L, health.getDetails().get("cache.total.size"));
    }

    @Test
    void health_returnsDown_whenExceptionOccurs() {
        // First call throws, second call (in catch block) returns value
        when(cacheManager.getCacheCount())
                .thenThrow(new RuntimeException("Database connection failed"))
                .thenReturn(0);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Database connection failed", health.getDetails().get("error"));
    }

    @Test
    void health_handlesIndividualCacheError() {
        when(cacheManager.getCacheCount()).thenReturn(2);
        when(cacheManager.getCacheNames()).thenReturn(Arrays.asList("goodCache", "badCache"));
        when(cacheManager.getCache("goodCache")).thenReturn(cache);
        when(cacheManager.getCache("badCache")).thenThrow(new RuntimeException("Cache error"));
        when(cache.size()).thenReturn(10L);
        when(cache.getStatistics()).thenReturn(new CacheStatistics(10, 5, 8, 1));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(10L, health.getDetails().get("cache.goodCache.size"));
        assertEquals("Cache error", health.getDetails().get("cache.badCache.error"));
    }

    @Test
    void health_calculatesHitRateCorrectly() {
        when(cacheManager.getCacheCount()).thenReturn(1);
        when(cacheManager.getCacheNames()).thenReturn(Collections.singletonList("testCache"));
        when(cacheManager.getCache("testCache")).thenReturn(cache);
        when(cache.size()).thenReturn(10L);
        // 80 hits, 20 misses = 80% hit rate
        when(cache.getStatistics()).thenReturn(new CacheStatistics(80, 20, 50, 5));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("80.00%", health.getDetails().get("stats.hitRate"));
    }
}
