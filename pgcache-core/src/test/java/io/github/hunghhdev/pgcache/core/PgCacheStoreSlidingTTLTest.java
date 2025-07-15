package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;

/**
 * Integration tests for sliding TTL functionality
 * 
 * @author Hung Hoang
 * @since 1.1.0
 */
@Testcontainers
class PgCacheStoreSlidingTTLTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private PgCacheStore cacheStore;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        
        dataSource = new HikariDataSource(config);
        cacheStore = new PgCacheStore(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void testSlidingTTLBasicFunctionality() {
        // Test basic sliding TTL functionality
        Duration ttl = Duration.ofSeconds(5);
        String key = "sliding-test";
        String value = "test-value";
        
        // Put with sliding TTL
        cacheStore.put(key, value, ttl, TTLPolicy.SLIDING);
        
        // Should be able to retrieve it immediately
        Optional<String> result = cacheStore.get(key, String.class);
        assertTrue(result.isPresent());
        assertEquals(value, result.get());
        
        // Verify TTL policy is set correctly
        Optional<TTLPolicy> policy = cacheStore.getTTLPolicy(key);
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
        
        // Verify remaining TTL is approximately correct
        Optional<Duration> remaining = cacheStore.getRemainingTTL(key);
        assertTrue(remaining.isPresent());
        assertTrue(remaining.get().getSeconds() > 0);
        assertTrue(remaining.get().getSeconds() <= ttl.getSeconds());
    }

    @Test
    void testSlidingTTLExpirationReset() throws InterruptedException {
        // Test that accessing an entry resets its expiration time
        Duration ttl = Duration.ofSeconds(3);
        String key = "sliding-reset-test";
        String value = "test-value";
        
        // Put with sliding TTL
        cacheStore.put(key, value, ttl, TTLPolicy.SLIDING);
        
        // Wait for 2 seconds (less than TTL)
        Thread.sleep(2000);
        
        // Access the entry with TTL refresh
        Optional<String> result1 = cacheStore.get(key, String.class, true);
        assertTrue(result1.isPresent());
        assertEquals(value, result1.get());
        
        // Wait for another 2 seconds (would expire if TTL wasn't reset)
        Thread.sleep(2000);
        
        // Entry should still be available because TTL was reset
        Optional<String> result2 = cacheStore.get(key, String.class, true);
        assertTrue(result2.isPresent());
        assertEquals(value, result2.get());
        
        // Wait for full TTL duration
        Thread.sleep(3100);
        
        // Now it should be expired
        Optional<String> result3 = cacheStore.get(key, String.class, true);
        assertFalse(result3.isPresent());
    }

    @Test
    void testSlidingTTLInactiveEntryExpiration() throws InterruptedException {
        // Test that inactive entries expire naturally
        Duration ttl = Duration.ofSeconds(2);
        String key = "sliding-inactive-test";
        String value = "test-value";
        
        // Put with sliding TTL
        cacheStore.put(key, value, ttl, TTLPolicy.SLIDING);
        
        // Don't access the entry, let it expire naturally
        Thread.sleep(2100);
        
        // Should be expired due to inactivity
        Optional<String> result = cacheStore.get(key, String.class, true);
        assertFalse(result.isPresent());
    }

    @Test
    void testSlidingTTLPolicyBackwardCompatibility() {
        // Test that default behavior remains ABSOLUTE for backward compatibility
        Duration ttl = Duration.ofSeconds(5);
        String key = "backward-compat-test";
        String value = "test-value";
        
        // Put with default TTL (should be ABSOLUTE)
        cacheStore.put(key, value, ttl);
        
        // Verify TTL policy is ABSOLUTE by default
        Optional<TTLPolicy> policy = cacheStore.getTTLPolicy(key);
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.ABSOLUTE, policy.get());
        
        // Verify the value is retrievable
        Optional<String> result = cacheStore.get(key, String.class);
        assertTrue(result.isPresent());
        assertEquals(value, result.get());
    }

    @Test
    void testSlidingTTLWithConcurrentAccess() throws InterruptedException {
        // Test thread safety of sliding TTL
        Duration ttl = Duration.ofSeconds(5);
        String key = "concurrent-test";
        String value = "test-value";
        
        // Put with sliding TTL
        cacheStore.put(key, value, ttl, TTLPolicy.SLIDING);
        
        // Create multiple threads to access the same key concurrently
        int numThreads = 5;
        Thread[] threads = new Thread[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    try {
                        Optional<String> result = cacheStore.get(key, String.class, true);
                        assertTrue(result.isPresent());
                        assertEquals(value, result.get());
                        Thread.sleep(100); // Small delay between accesses
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify the key still exists after concurrent access
        Optional<String> finalResult = cacheStore.get(key, String.class);
        assertTrue(finalResult.isPresent());
        assertEquals(value, finalResult.get());
    }
}
