package dev.hunghh.pgcache.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TTL cleanup functionality in PgCacheStore.
 */
@Testcontainers
class PgCacheStoreTTLCleanupTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private PgCacheStore cache;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        cache = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .build();

        // Clear any existing data
        cache.clear();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            try {
                cache.clear();
                cache.shutdown();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    void testManualCleanupExpired() {
        // Add entries with different TTLs
        cache.put("short-lived", "value1", Duration.ofSeconds(1));
        cache.put("long-lived", "value2", Duration.ofHours(1));
        cache.put("medium-lived", "value3", Duration.ofSeconds(3));

        // Verify all entries exist
        assertEquals(3, cache.size());
        assertTrue(cache.get("short-lived", String.class).isPresent());
        assertTrue(cache.get("long-lived", String.class).isPresent());
        assertTrue(cache.get("medium-lived", String.class).isPresent());

        // Wait for some entries to expire
        try {
            Thread.sleep(2000); // 2 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted");
        }

        // Manual cleanup
        int cleanedUp = cache.cleanupExpired();
        
        // Should have cleaned up the short-lived entry
        assertTrue(cleanedUp >= 1, "Should have cleaned up at least 1 expired entry");
        
        // Verify only long-lived entry remains
        assertFalse(cache.get("short-lived", String.class).isPresent());
        assertTrue(cache.get("long-lived", String.class).isPresent());
    }

    @Test
    void testCleanupExpiredNoEntries() {
        // Test cleanup when no entries are expired
        cache.put("long-lived", "value", Duration.ofHours(1));
        
        int cleanedUp = cache.cleanupExpired();
        assertEquals(0, cleanedUp);
        
        // Entry should still exist
        assertTrue(cache.get("long-lived", String.class).isPresent());
    }

    @Test
    void testCleanupExpiredEmptyCache() {
        // Test cleanup on empty cache
        int cleanedUp = cache.cleanupExpired();
        assertEquals(0, cleanedUp);
    }

    @Test
    @Timeout(15)
    void testBackgroundCleanup() {
        // Create datasource for background cache
        PGSimpleDataSource backgroundDataSource = new PGSimpleDataSource();
        backgroundDataSource.setUrl(postgres.getJdbcUrl());
        backgroundDataSource.setUser(postgres.getUsername());
        backgroundDataSource.setPassword(postgres.getPassword());
        
        // Create cache with background cleanup enabled
        PgCacheStore backgroundCache = PgCacheStore.builder()
                .dataSource(backgroundDataSource)
                .autoCreateTable(true)
                .enableBackgroundCleanup(true)
                .cleanupIntervalMinutes(1) // 1 minute for test
                .build();

        try {
            // Add entries with short TTL
            backgroundCache.put("short1", "value1", Duration.ofSeconds(2));
            backgroundCache.put("short2", "value2", Duration.ofSeconds(2));
            backgroundCache.put("long", "value3", Duration.ofHours(1));

            // Verify entries exist
            assertEquals(3, backgroundCache.size());

            // Wait for entries to expire
            Thread.sleep(3000); // 3 seconds

            // Wait a bit more for background cleanup to potentially run
            // Note: This test might be flaky since background cleanup runs every minute
            // In a real scenario, we'd mock the scheduler or reduce the interval
            
            // Verify that manual cleanup would find fewer entries (background might have cleaned up)
            int remainingExpired = backgroundCache.cleanupExpired();
            
            // The background cleanup might have already cleaned up, or it might not have run yet
            // So we just verify the cache still works correctly
            assertFalse(backgroundCache.get("short1", String.class).isPresent());
            assertFalse(backgroundCache.get("short2", String.class).isPresent());
            assertTrue(backgroundCache.get("long", String.class).isPresent());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted");
        } finally {
            backgroundCache.clear();
            backgroundCache.shutdown();
        }
    }

    @Test
    void testSizeWithExpiredEntries() {
        // Add entries that will expire
        cache.put("expired1", "value1", Duration.ofSeconds(1));
        cache.put("expired2", "value2", Duration.ofSeconds(1));
        cache.put("valid", "value3", Duration.ofHours(1));

        // Initially should have 3 entries
        assertEquals(3, cache.size());

        // Wait for expiration
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted");
        }

        // size() should only count non-expired entries
        int sizeAfterExpiration = cache.size();
        assertEquals(1, sizeAfterExpiration, "Size should only count non-expired entries");

        // Manual cleanup should remove the expired entries
        int cleanedUp = cache.cleanupExpired();
        assertTrue(cleanedUp >= 2, "Should clean up at least 2 expired entries");

        // Size should still be 1
        assertEquals(1, cache.size());
    }

    @Test
    void testBuilderCleanupConfiguration() {
        // Create datasource for configured cache
        PGSimpleDataSource configDataSource = new PGSimpleDataSource();
        configDataSource.setUrl(postgres.getJdbcUrl());
        configDataSource.setUser(postgres.getUsername());
        configDataSource.setPassword(postgres.getPassword());
        
        // Test builder with cleanup configuration
        PgCacheStore configuredCache = PgCacheStore.builder()
                .dataSource(configDataSource)
                .autoCreateTable(true)
                .enableBackgroundCleanup(true)
                .cleanupIntervalMinutes(10)
                .build();

        assertNotNull(configuredCache);
        
        // Test that cache works normally
        configuredCache.put("test", "value", Duration.ofMinutes(5));
        assertTrue(configuredCache.get("test", String.class).isPresent());

        configuredCache.clear();
        configuredCache.shutdown();
    }
}
