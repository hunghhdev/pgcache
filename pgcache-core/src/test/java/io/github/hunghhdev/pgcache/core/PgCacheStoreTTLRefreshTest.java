package io.github.hunghhdev.pgcache.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TTL refresh functionality in PgCacheStore.
 */
@Testcontainers
class PgCacheStoreTTLRefreshTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "fsync=off");
    
    private PgCacheStore cacheStore;
    
    @BeforeEach
    void setUp() {
        DataSource dataSource = createDataSource();
        cacheStore = new PgCacheStore(dataSource);
    }
    
    private DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        return new HikariDataSource(config);
    }
    
    @Test
    void testRefreshTTLWithAbsoluteTTL() throws InterruptedException {
        String key = "test-key";
        String value = "test-value";
        Duration originalTtl = Duration.ofSeconds(5);
        Duration newTtl = Duration.ofSeconds(10);
        
        // Put value with absolute TTL
        cacheStore.put(key, value, originalTtl, TTLPolicy.ABSOLUTE);
        
        // Wait 2 seconds
        Thread.sleep(2000);
        
        // Check remaining TTL (should be around 3 seconds)
        Optional<Duration> remainingBefore = cacheStore.getRemainingTTL(key);
        assertTrue(remainingBefore.isPresent());
        assertTrue(remainingBefore.get().getSeconds() <= 3);
        assertTrue(remainingBefore.get().getSeconds() >= 2);
        
        // Refresh TTL to 10 seconds
        boolean refreshed = cacheStore.refreshTTL(key, newTtl);
        assertTrue(refreshed);
        
        // Check remaining TTL (should be around 10 seconds now)
        Optional<Duration> remainingAfter = cacheStore.getRemainingTTL(key);
        assertTrue(remainingAfter.isPresent());
        assertTrue(remainingAfter.get().getSeconds() >= 9);
        assertTrue(remainingAfter.get().getSeconds() <= 10);
        
        // Value should still be retrievable
        Optional<String> retrieved = cacheStore.get(key, String.class);
        assertTrue(retrieved.isPresent());
        assertEquals(value, retrieved.get());
    }
    
    @Test
    void testRefreshTTLWithSlidingTTL() throws InterruptedException {
        String key = "sliding-key";
        String value = "sliding-value";
        Duration originalTtl = Duration.ofSeconds(5);
        Duration newTtl = Duration.ofSeconds(15);
        
        // Put value with sliding TTL
        cacheStore.put(key, value, originalTtl, TTLPolicy.SLIDING);
        
        // Wait 2 seconds
        Thread.sleep(2000);
        
        // Access to refresh sliding TTL
        cacheStore.get(key, String.class, true);
        
        // Check remaining TTL (should be around 5 seconds due to sliding refresh)
        Optional<Duration> remainingBefore = cacheStore.getRemainingTTL(key);
        assertTrue(remainingBefore.isPresent());
        assertTrue(remainingBefore.get().getSeconds() >= 4);
        assertTrue(remainingBefore.get().getSeconds() <= 5);
        
        // Manually refresh TTL to 15 seconds
        boolean refreshed = cacheStore.refreshTTL(key, newTtl);
        assertTrue(refreshed);
        
        // Check remaining TTL (should be around 15 seconds now)
        Optional<Duration> remainingAfter = cacheStore.getRemainingTTL(key);
        assertTrue(remainingAfter.isPresent());
        assertTrue(remainingAfter.get().getSeconds() >= 14);
        assertTrue(remainingAfter.get().getSeconds() <= 15);
        
        // Value should still be retrievable
        Optional<String> retrieved = cacheStore.get(key, String.class);
        assertTrue(retrieved.isPresent());
        assertEquals(value, retrieved.get());
    }
    
    @Test
    void testRefreshTTLNonExistentKey() {
        String nonExistentKey = "non-existent";
        Duration newTtl = Duration.ofSeconds(10);
        
        // Try to refresh TTL for non-existent key
        boolean refreshed = cacheStore.refreshTTL(nonExistentKey, newTtl);
        assertFalse(refreshed);
    }
    
    @Test
    void testRefreshTTLWithNullKey() {
        Duration newTtl = Duration.ofSeconds(10);
        
        // Try to refresh TTL with null key
        assertThrows(PgCacheException.class, () -> cacheStore.refreshTTL(null, newTtl));
    }
    
    @Test
    void testRefreshTTLWithEmptyKey() {
        Duration newTtl = Duration.ofSeconds(10);
        
        // Try to refresh TTL with empty key
        assertThrows(PgCacheException.class, () -> cacheStore.refreshTTL("", newTtl));
    }
    
    @Test
    void testRefreshTTLWithNullDuration() {
        String key = "test-key";
        String value = "test-value";
        
        // Put value first
        cacheStore.put(key, value, Duration.ofSeconds(10));
        
        // Try to refresh TTL with null duration
        assertThrows(PgCacheException.class, () -> cacheStore.refreshTTL(key, null));
    }
    
    @Test
    void testRefreshTTLWithNegativeDuration() {
        String key = "test-key";
        String value = "test-value";
        
        // Put value first
        cacheStore.put(key, value, Duration.ofSeconds(10));
        
        // Try to refresh TTL with negative duration
        assertThrows(PgCacheException.class, () -> cacheStore.refreshTTL(key, Duration.ofSeconds(-5)));
    }
    
    @Test
    void testRefreshTTLForPermanentEntry() {
        String key = "permanent-key";
        String value = "permanent-value";
        
        // Put value without TTL (permanent)
        cacheStore.put(key, value);
        
        // Check that TTL is not set
        Optional<Duration> ttlBefore = cacheStore.getRemainingTTL(key);
        assertFalse(ttlBefore.isPresent());
        
        // Refresh TTL (this should set TTL on permanent entry)
        Duration newTtl = Duration.ofSeconds(30);
        boolean refreshed = cacheStore.refreshTTL(key, newTtl);
        assertTrue(refreshed);
        
        // Check that TTL is now set
        Optional<Duration> ttlAfter = cacheStore.getRemainingTTL(key);
        assertTrue(ttlAfter.isPresent());
        assertTrue(ttlAfter.get().getSeconds() >= 29);
        assertTrue(ttlAfter.get().getSeconds() <= 30);
        
        // Value should still be retrievable
        Optional<String> retrieved = cacheStore.get(key, String.class);
        assertTrue(retrieved.isPresent());
        assertEquals(value, retrieved.get());
    }
    
    @Test
    void testRefreshTTLMultipleTimes() throws InterruptedException {
        String key = "multi-refresh-key";
        String value = "multi-refresh-value";
        
        // Put value with initial TTL
        cacheStore.put(key, value, Duration.ofSeconds(5));
        
        // First refresh
        boolean refresh1 = cacheStore.refreshTTL(key, Duration.ofSeconds(10));
        assertTrue(refresh1);
        
        Thread.sleep(1000);
        
        // Second refresh
        boolean refresh2 = cacheStore.refreshTTL(key, Duration.ofSeconds(20));
        assertTrue(refresh2);
        
        // Check final TTL
        Optional<Duration> finalTtl = cacheStore.getRemainingTTL(key);
        assertTrue(finalTtl.isPresent());
        assertTrue(finalTtl.get().getSeconds() >= 19);
        assertTrue(finalTtl.get().getSeconds() <= 20);
        
        // Value should still be retrievable
        Optional<String> retrieved = cacheStore.get(key, String.class);
        assertTrue(retrieved.isPresent());
        assertEquals(value, retrieved.get());
    }
}
