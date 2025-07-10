package dev.hunghh.pgcache.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PgCache configuration properties.
 */
@SpringBootTest(classes = PgCachePropertiesTest.TestConfig.class)
@TestPropertySource(properties = {
    "pgcache.enabled=true",
    "pgcache.default-ttl=PT2H",
    "pgcache.allow-null-values=false",
    "pgcache.table-name=custom_cache",
    "pgcache.background-cleanup.enabled=true",
    "pgcache.background-cleanup.interval=PT15M",
    "pgcache.caches.cache1.ttl=PT1H",
    "pgcache.caches.cache1.allow-null-values=true",
    "pgcache.caches.cache1.table-name=cache1_table",
    "pgcache.caches.cache2.ttl=PT30M"
})
class PgCachePropertiesTest {
    
    @Test
    void testPropertiesBinding() {
        PgCacheProperties properties = new PgCacheProperties();
        
        // Test default values
        assertTrue(properties.isEnabled());
        assertEquals(Duration.ofHours(1), properties.getDefaultTtl());
        assertTrue(properties.isAllowNullValues());
        assertEquals("pg_cache", properties.getTableName());
        assertFalse(properties.getBackgroundCleanup().isEnabled());
        assertEquals(Duration.ofMinutes(30), properties.getBackgroundCleanup().getInterval());
    }
    
    @Test
    void testCacheConfigMerging() {
        PgCacheProperties properties = new PgCacheProperties();
        properties.setDefaultTtl(Duration.ofHours(2));
        properties.setAllowNullValues(false);
        properties.setTableName("global_cache");
        properties.getBackgroundCleanup().setEnabled(true);
        properties.getBackgroundCleanup().setInterval(Duration.ofMinutes(15));
        
        // Test cache config with all overrides
        PgCacheProperties.CacheConfig cache1Config = new PgCacheProperties.CacheConfig();
        cache1Config.setTtl(Duration.ofHours(1));
        cache1Config.setAllowNullValues(true);
        cache1Config.setTableName("cache1_table");
        
        PgCacheManager.PgCacheConfiguration config1 = cache1Config.toConfiguration(properties);
        assertEquals(Duration.ofHours(1), config1.getDefaultTtl());
        assertTrue(config1.isAllowNullValues());
        assertEquals("cache1_table", config1.getTableName());
        assertTrue(config1.isBackgroundCleanupEnabled());
        assertEquals(Duration.ofMinutes(15), config1.getBackgroundCleanupInterval());
        
        // Test cache config with partial overrides
        PgCacheProperties.CacheConfig cache2Config = new PgCacheProperties.CacheConfig();
        cache2Config.setTtl(Duration.ofMinutes(30));
        // Other properties should use global defaults
        
        PgCacheManager.PgCacheConfiguration config2 = cache2Config.toConfiguration(properties);
        assertEquals(Duration.ofMinutes(30), config2.getDefaultTtl());
        assertFalse(config2.isAllowNullValues()); // Global default
        assertEquals("global_cache", config2.getTableName()); // Global default
        assertTrue(config2.isBackgroundCleanupEnabled()); // Global default
        assertEquals(Duration.ofMinutes(15), config2.getBackgroundCleanupInterval()); // Global default
    }
    
    @Test
    void testDefaultConfiguration() {
        PgCacheProperties properties = new PgCacheProperties();
        properties.setDefaultTtl(Duration.ofHours(3));
        properties.setAllowNullValues(true);
        properties.setTableName("test_cache");
        properties.getBackgroundCleanup().setEnabled(false);
        properties.getBackgroundCleanup().setInterval(Duration.ofMinutes(45));
        
        PgCacheManager.PgCacheConfiguration config = properties.toDefaultConfiguration();
        assertEquals(Duration.ofHours(3), config.getDefaultTtl());
        assertTrue(config.isAllowNullValues());
        assertEquals("test_cache", config.getTableName());
        assertFalse(config.isBackgroundCleanupEnabled());
        assertEquals(Duration.ofMinutes(45), config.getBackgroundCleanupInterval());
    }
    
    @Test
    void testBackgroundCleanupConfiguration() {
        PgCacheProperties.BackgroundCleanup cleanup = new PgCacheProperties.BackgroundCleanup();
        
        // Test defaults
        assertFalse(cleanup.isEnabled());
        assertEquals(Duration.ofMinutes(30), cleanup.getInterval());
        
        // Test setters
        cleanup.setEnabled(true);
        cleanup.setInterval(Duration.ofMinutes(10));
        
        assertTrue(cleanup.isEnabled());
        assertEquals(Duration.ofMinutes(10), cleanup.getInterval());
    }
    
    @Test
    void testNullTtlHandling() {
        PgCacheProperties properties = new PgCacheProperties();
        properties.setDefaultTtl(null); // Permanent entries
        
        PgCacheManager.PgCacheConfiguration config = properties.toDefaultConfiguration();
        assertNull(config.getDefaultTtl());
        
        // Test cache config with null TTL
        PgCacheProperties.CacheConfig cacheConfig = new PgCacheProperties.CacheConfig();
        cacheConfig.setTtl(null);
        
        PgCacheManager.PgCacheConfiguration cacheSpecificConfig = cacheConfig.toConfiguration(properties);
        assertNull(cacheSpecificConfig.getDefaultTtl());
    }
    
    @EnableConfigurationProperties(PgCacheProperties.class)
    static class TestConfig {
        // Empty configuration class for testing
    }
}
