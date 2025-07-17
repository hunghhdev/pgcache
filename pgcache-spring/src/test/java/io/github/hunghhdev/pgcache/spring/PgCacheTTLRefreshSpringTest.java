package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TTL refresh functionality in Spring PgCache integration.
 */
@SpringBootTest
@Testcontainers
class PgCacheTTLRefreshSpringTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "fsync=off");

    @Autowired
    private PgCacheManager cacheManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void testRefreshTTLWithSpringIntegration() throws InterruptedException {
        PgCache cache = (PgCache) cacheManager.getCache("ttl-refresh-test");
        assertNotNull(cache);

        String key = "spring-refresh-key";
        String value = "spring-refresh-value";
        Duration initialTtl = Duration.ofSeconds(5);
        Duration newTtl = Duration.ofSeconds(15);

        // Put value with initial TTL
        cache.put(key, value, initialTtl);

        // Wait 2 seconds
        Thread.sleep(2000);

        // Check initial remaining TTL
        Optional<Duration> ttlBefore = cache.getRemainingTTL(key);
        assertTrue(ttlBefore.isPresent());
        assertTrue(ttlBefore.get().getSeconds() <= 3);
        assertTrue(ttlBefore.get().getSeconds() >= 2);

        // Refresh TTL
        boolean refreshed = cache.refreshTTL(key, newTtl);
        assertTrue(refreshed);

        // Check remaining TTL after refresh
        Optional<Duration> ttlAfter = cache.getRemainingTTL(key);
        assertTrue(ttlAfter.isPresent());
        assertTrue(ttlAfter.get().getSeconds() >= 14);
        assertTrue(ttlAfter.get().getSeconds() <= 15);

        // Value should still be retrievable
        String retrieved = cache.get(key, String.class);
        assertNotNull(retrieved);
        assertEquals(value, retrieved);
    }

    @Test
    void testRefreshTTLWithSlidingPolicy() throws InterruptedException {
        PgCache cache = (PgCache) cacheManager.getCache("ttl-refresh-test");
        assertNotNull(cache);

        String key = "sliding-refresh-key";
        String value = "sliding-refresh-value";
        Duration initialTtl = Duration.ofSeconds(6);
        Duration newTtl = Duration.ofSeconds(20);

        // Put value with sliding TTL
        cache.put(key, value, initialTtl, TTLPolicy.SLIDING);

        // Wait 2 seconds
        Thread.sleep(2000);

        // Access to trigger sliding refresh
        cache.get(key, String.class);

        // Check TTL (should be refreshed by sliding access)
        Optional<Duration> ttlAfterAccess = cache.getRemainingTTL(key);
        assertTrue(ttlAfterAccess.isPresent());
        assertTrue(ttlAfterAccess.get().getSeconds() >= 5);

        // Manually refresh TTL to new value
        boolean refreshed = cache.refreshTTL(key, newTtl);
        assertTrue(refreshed);

        // Check remaining TTL after manual refresh
        Optional<Duration> ttlAfterRefresh = cache.getRemainingTTL(key);
        assertTrue(ttlAfterRefresh.isPresent());
        assertTrue(ttlAfterRefresh.get().getSeconds() >= 19);
        assertTrue(ttlAfterRefresh.get().getSeconds() <= 20);

        // Value should still be retrievable
        String retrieved = cache.get(key, String.class);
        assertNotNull(retrieved);
        assertEquals(value, retrieved);
    }

    @Test
    void testRefreshTTLNonExistentKey() {
        PgCache cache = (PgCache) cacheManager.getCache("ttl-refresh-test");
        assertNotNull(cache);

        String nonExistentKey = "non-existent-key";
        Duration newTtl = Duration.ofSeconds(10);

        // Try to refresh TTL for non-existent key
        boolean refreshed = cache.refreshTTL(nonExistentKey, newTtl);
        assertFalse(refreshed);
    }

    @Test
    void testRefreshTTLWithNullKey() {
        PgCache cache = (PgCache) cacheManager.getCache("ttl-refresh-test");
        assertNotNull(cache);

        Duration newTtl = Duration.ofSeconds(10);

        // Try to refresh TTL with null key
        boolean refreshed = cache.refreshTTL(null, newTtl);
        assertFalse(refreshed);
    }

    @Test
    void testRefreshTTLFromPermanentEntry() {
        // Create cache manager without default TTL for this test
        PgCacheManager.PgCacheConfiguration config = PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(null)  // No default TTL
                .allowNullValues(true)
                .backgroundCleanupEnabled(false)
                .build();
        PgCacheManager noCacheManager = new PgCacheManager(dataSource, config);
        
        PgCache cache = (PgCache) noCacheManager.getCache("permanent-entry-test");
        assertNotNull(cache);

        String key = "permanent-to-ttl-key";
        String value = "permanent-to-ttl-value";

        // Put value without TTL (permanent) - use basic put method with null default TTL
        cache.put(key, value);

        // Check that no TTL is set
        Optional<Duration> ttlBefore = cache.getRemainingTTL(key);
        assertFalse(ttlBefore.isPresent());

        // Add TTL to permanent entry
        Duration newTtl = Duration.ofSeconds(30);
        boolean refreshed = cache.refreshTTL(key, newTtl);
        assertTrue(refreshed);

        // Check that TTL is now set
        Optional<Duration> ttlAfter = cache.getRemainingTTL(key);
        assertTrue(ttlAfter.isPresent());
        assertTrue(ttlAfter.get().getSeconds() >= 29);
        assertTrue(ttlAfter.get().getSeconds() <= 30);

        // Value should still be retrievable
        String retrieved = cache.get(key, String.class);
        assertNotNull(retrieved);
        assertEquals(value, retrieved);
    }

    @Test
    void testRefreshTTLMultipleEntriesDifferentPolicies() throws InterruptedException {
        PgCache cache = (PgCache) cacheManager.getCache("ttl-refresh-test");
        assertNotNull(cache);

        String absoluteKey = "absolute-multi-key";
        String slidingKey = "sliding-multi-key";
        String value = "multi-test-value";
        Duration initialTtl = Duration.ofSeconds(8);
        Duration newTtl = Duration.ofSeconds(25);

        // Put values with different TTL policies
        cache.put(absoluteKey, value, initialTtl, TTLPolicy.ABSOLUTE);
        cache.put(slidingKey, value, initialTtl, TTLPolicy.SLIDING);

        // Wait 3 seconds
        Thread.sleep(3000);

        // Refresh both entries
        boolean refreshedAbsolute = cache.refreshTTL(absoluteKey, newTtl);
        boolean refreshedSliding = cache.refreshTTL(slidingKey, newTtl);

        assertTrue(refreshedAbsolute);
        assertTrue(refreshedSliding);

        // Check both entries have new TTL
        Optional<Duration> absoluteTtl = cache.getRemainingTTL(absoluteKey);
        Optional<Duration> slidingTtl = cache.getRemainingTTL(slidingKey);

        assertTrue(absoluteTtl.isPresent());
        assertTrue(slidingTtl.isPresent());

        assertTrue(absoluteTtl.get().getSeconds() >= 24);
        assertTrue(absoluteTtl.get().getSeconds() <= 25);
        assertTrue(slidingTtl.get().getSeconds() >= 24);
        assertTrue(slidingTtl.get().getSeconds() <= 25);

        // Both values should be retrievable
        assertEquals(value, cache.get(absoluteKey, String.class));
        assertEquals(value, cache.get(slidingKey, String.class));
    }

    @Configuration
    static class TestConfig {
        
        @Bean
        public DataSource dataSource() {
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            return new com.zaxxer.hikari.HikariDataSource(config);
        }
        
        @Bean
        public PgCacheManager pgCacheManager(DataSource dataSource) {
            PgCacheManager.PgCacheConfiguration config = PgCacheManager.PgCacheConfiguration.builder()
                    .defaultTtl(Duration.ofMinutes(5))
                    .allowNullValues(true)
                    .backgroundCleanupEnabled(false)
                    .build();
            return new PgCacheManager(dataSource, config);
        }
    }
}
