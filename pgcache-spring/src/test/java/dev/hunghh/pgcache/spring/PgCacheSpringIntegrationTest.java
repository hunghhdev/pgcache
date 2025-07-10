package dev.hunghh.pgcache.spring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PgCache Spring integration.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {PgCacheSpringIntegrationTest.TestConfig.class})
@Testcontainers
class PgCacheSpringIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");
    
    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private TestCacheService testService;
    
    @BeforeEach
    void setUp() {
        // Clear all caches before each test
        ((PgCacheManager) cacheManager).clearAll();
    }
    
    @AfterEach
    void tearDown() {
        // Clean up after each test
        ((PgCacheManager) cacheManager).clearAll();
    }
    
    @Test
    void testCacheManagerCreation() {
        assertNotNull(cacheManager);
        assertTrue(cacheManager instanceof PgCacheManager);
    }
    
    @Test
    void testCacheCreation() {
        // Test dynamic cache creation
        Cache cache = cacheManager.getCache("test-cache");
        assertNotNull(cache);
        assertTrue(cache instanceof PgCache);
        assertEquals("test-cache", cache.getName());
        
        // Test cache reuse
        Cache cache2 = cacheManager.getCache("test-cache");
        assertSame(cache, cache2);
    }
    
    @Test
    void testBasicCacheOperations() {
        Cache cache = cacheManager.getCache("test-cache");
        
        // Test put and get
        cache.put("key1", "value1");
        Cache.ValueWrapper wrapper = cache.get("key1");
        assertNotNull(wrapper);
        assertEquals("value1", wrapper.get());
        
        // Test get with type
        String value = cache.get("key1", String.class);
        assertEquals("value1", value);
        
        // Test evict
        cache.evict("key1");
        assertNull(cache.get("key1"));
        
        // Test clear
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.clear();
        assertNull(cache.get("key2"));
        assertNull(cache.get("key3"));
    }
    
    @Test
    void testPutIfAbsent() {
        Cache cache = cacheManager.getCache("test-cache");
        
        // Test putIfAbsent with no existing value
        Cache.ValueWrapper result = cache.putIfAbsent("key1", "value1");
        assertNull(result);
        assertEquals("value1", cache.get("key1", String.class));
        
        // Test putIfAbsent with existing value
        result = cache.putIfAbsent("key1", "value2");
        assertNotNull(result);
        assertEquals("value1", result.get());
        assertEquals("value1", cache.get("key1", String.class));
    }
    
    @Test
    void testEvictIfPresent() {
        Cache cache = cacheManager.getCache("test-cache");
        
        // Test evictIfPresent with no existing value
        boolean result = cache.evictIfPresent("key1");
        assertFalse(result);
        
        // Test evictIfPresent with existing value
        cache.put("key1", "value1");
        result = cache.evictIfPresent("key1");
        assertTrue(result);
        assertNull(cache.get("key1"));
    }
    
    @Test
    void testCacheableAnnotation() {
        // Test @Cacheable annotation
        testService.resetCounter();
        
        // First call should execute method
        String result1 = testService.getCachedValue("test");
        assertEquals("cached-test", result1);
        assertEquals(1, testService.getCallCount());
        
        // Second call should return cached value
        String result2 = testService.getCachedValue("test");
        assertEquals("cached-test", result2);
        assertEquals(1, testService.getCallCount()); // Should not increment
        
        // Different key should execute method again
        String result3 = testService.getCachedValue("test2");
        assertEquals("cached-test2", result3);
        assertEquals(2, testService.getCallCount());
    }
    
    @Test
    void testCacheSize() {
        PgCache cache = (PgCache) cacheManager.getCache("test-cache");
        
        // Initial size should be 0
        assertEquals(0, cache.size());
        
        // Add some entries
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        assertEquals(2, cache.size());
        
        // Remove one entry
        cache.evict("key1");
        assertEquals(1, cache.size());
        
        // Clear all
        cache.clear();
        assertEquals(0, cache.size());
    }
    
    @Test
    void testCacheManagerOperations() {
        PgCacheManager pgCacheManager = (PgCacheManager) cacheManager;
        
        // Get initial cache count (may not be empty due to previous tests)
        int initialCacheCount = pgCacheManager.getCacheCount();
        
        // Create some caches
        cacheManager.getCache("cache1");
        cacheManager.getCache("cache2");
        
        assertEquals(initialCacheCount + 2, pgCacheManager.getCacheCount());
        assertTrue(pgCacheManager.getCacheNames().contains("cache1"));
        assertTrue(pgCacheManager.getCacheNames().contains("cache2"));
        
        // Test remove cache
        boolean removed = pgCacheManager.removeCache("cache1");
        assertTrue(removed);
        assertEquals(initialCacheCount + 1, pgCacheManager.getCacheCount());
        assertFalse(pgCacheManager.getCacheNames().contains("cache1"));
        
        // Test remove non-existent cache
        removed = pgCacheManager.removeCache("non-existent");
        assertFalse(removed);
    }
    
    @Test
    void testGetWithValueLoader() {
        Cache cache = cacheManager.getCache("test-cache");
        AtomicInteger counter = new AtomicInteger(0);
        
        // First call should load value
        String result1 = cache.get("key1", () -> {
            counter.incrementAndGet();
            return "loaded-value";
        });
        assertEquals("loaded-value", result1);
        assertEquals(1, counter.get());
        
        // Second call should return cached value
        String result2 = cache.get("key1", () -> {
            counter.incrementAndGet();
            return "loaded-value-2";
        });
        assertEquals("loaded-value", result2);
        assertEquals(1, counter.get()); // Should not increment
    }
    
    @Configuration
    @EnableCaching
    static class TestConfig {
        
        @Bean
        public DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(10);
            return new HikariDataSource(config);
        }
        
        @Bean
        public PgCacheManager cacheManager(DataSource dataSource) {
            PgCacheManager.PgCacheConfiguration config = 
                PgCacheManager.PgCacheConfiguration.builder()
                    .defaultTtl(Duration.ofMinutes(5))
                    .allowNullValues(true)
                    .tableName("test_cache")
                    .build();
            
            return new PgCacheManager(dataSource, config);
        }
        
        @Bean
        public TestCacheService testCacheService() {
            return new TestCacheService();
        }
    }
    
    @Service
    static class TestCacheService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        
        @Cacheable("test-cache")
        public String getCachedValue(String key) {
            callCount.incrementAndGet();
            return "cached-" + key;
        }
        
        public int getCallCount() {
            return callCount.get();
        }
        
        public void resetCounter() {
            callCount.set(0);
        }
    }
}
