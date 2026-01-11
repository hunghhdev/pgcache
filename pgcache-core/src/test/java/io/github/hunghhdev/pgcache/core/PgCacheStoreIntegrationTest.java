package io.github.hunghhdev.pgcache.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgCacheStoreIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private PgCacheStore cacheStore;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        cacheStore = PgCacheStore.builder()
                .dataSource(dataSource)
                .objectMapper(new ObjectMapper())
                .autoCreateTable(true)
                .build();

        // Clear the cache before each test
        cacheStore.clear();
    }

    @Test
    void testPutAndGet() {
        // Arrange
        String key = "test-key-" + UUID.randomUUID();
        TestUser user = new TestUser("John Doe", 30);

        // Act & Assert - Initially the key shouldn't exist
        Optional<TestUser> beforePut = cacheStore.get(key, TestUser.class);
        assertFalse(beforePut.isPresent());

        // Put the value
        cacheStore.put(key, user, Duration.ofMinutes(5));

        // Get the value and verify
        Optional<TestUser> afterPut = cacheStore.get(key, TestUser.class);
        assertTrue(afterPut.isPresent());
        assertEquals("John Doe", afterPut.get().getName());
        assertEquals(30, afterPut.get().getAge());
    }

    @Test
    void testEvict() {
        // Arrange
        String key = "evict-key-" + UUID.randomUUID();
        TestUser user = new TestUser("Jane Doe", 25);

        // Put the value
        cacheStore.put(key, user, Duration.ofMinutes(5));

        // Verify it exists
        assertTrue(cacheStore.get(key, TestUser.class).isPresent());

        // Evict
        cacheStore.evict(key);

        // Verify it's gone
        assertFalse(cacheStore.get(key, TestUser.class).isPresent());
    }

    @Test
    void testClear() {
        // Arrange - Put several values
        for (int i = 0; i < 5; i++) {
            cacheStore.put(
                "clear-key-" + i,
                new TestUser("User " + i, 20 + i),
                Duration.ofMinutes(5)
            );
        }

        // Act
        cacheStore.clear();

        // Assert
        assertEquals(0, cacheStore.size());
        for (int i = 0; i < 5; i++) {
            assertFalse(cacheStore.get("clear-key-" + i, TestUser.class).isPresent());
        }
    }

    @Test
    void testSize() {
        // Arrange - Put several values
        for (int i = 0; i < 5; i++) {
            cacheStore.put(
                "size-key-" + i,
                new TestUser("User " + i, 20 + i),
                Duration.ofMinutes(5)
            );
        }

        // Assert
        assertEquals(5, cacheStore.size());
    }

    @Test
    void testExpiration() throws Exception {
        // Arrange
        String key = "expiring-key-" + UUID.randomUUID();
        TestUser user = new TestUser("Expiring User", 40);

        // Put with a very short TTL
        cacheStore.put(key, user, Duration.ofSeconds(1));

        // Verify it exists initially
        assertTrue(cacheStore.get(key, TestUser.class).isPresent());

        // Wait for expiration
        Thread.sleep(2000); // 2 seconds

        // Verify it's gone after expiration
        assertFalse(cacheStore.get(key, TestUser.class).isPresent());
    }

    @Test
    void testPutWithoutTTL() {
        // Arrange
        String key = "permanent-key-" + UUID.randomUUID();
        TestUser user = new TestUser("Alice", 25);

        // Act
        cacheStore.put(key, user); // No TTL - permanent entry

        // Assert
        Optional<TestUser> result = cacheStore.get(key, TestUser.class);
        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().name);
        assertEquals(25, result.get().age);
    }

    @Test
    void testPermanentEntryDoesNotExpire() throws InterruptedException {
        // Arrange
        String key = "permanent-key-" + UUID.randomUUID();
        TestUser user = new TestUser("Bob", 30);

        // Act - Put without TTL
        cacheStore.put(key, user);

        // Wait a bit to simulate time passing
        Thread.sleep(1000);

        // Assert - Entry should still be there
        Optional<TestUser> result = cacheStore.get(key, TestUser.class);
        assertTrue(result.isPresent());
        assertEquals("Bob", result.get().name);
        assertEquals(30, result.get().age);
    }

    @Test
    void testSizeCountsPermanentEntries() {
        // Arrange
        String tempKey = "temp-key-" + UUID.randomUUID();
        String permKey = "perm-key-" + UUID.randomUUID();
        TestUser user = new TestUser("Charlie", 35);

        // Act
        cacheStore.put(tempKey, user, Duration.ofSeconds(1)); // Temporary
        cacheStore.put(permKey, user); // Permanent

        // Assert
        assertEquals(2, cacheStore.size());

        // Wait for temp entry to expire
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Invalidate size cache to ensure accurate count after expiration
        cacheStore.invalidateSizeCache();

        // Only permanent entry should remain in count
        assertEquals(1, cacheStore.size());
    }

    // ==================== Batch Operations Tests (v1.3.0) ====================

    @Test
    void testGetAll() {
        // Arrange
        String key1 = "batch-get-1";
        String key2 = "batch-get-2";
        String key3 = "batch-get-3";

        cacheStore.put(key1, new TestUser("User1", 21), Duration.ofMinutes(5));
        cacheStore.put(key2, new TestUser("User2", 22), Duration.ofMinutes(5));
        // key3 not put

        // Act
        Map<String, TestUser> results = cacheStore.getAll(
            Arrays.asList(key1, key2, key3), TestUser.class);

        // Assert
        assertEquals(2, results.size());
        assertTrue(results.containsKey(key1));
        assertTrue(results.containsKey(key2));
        assertFalse(results.containsKey(key3));
        assertEquals("User1", results.get(key1).getName());
        assertEquals("User2", results.get(key2).getName());
    }

    @Test
    void testPutAll() {
        // Arrange
        Map<String, TestUser> entries = new HashMap<>();
        entries.put("batch-put-1", new TestUser("User1", 31));
        entries.put("batch-put-2", new TestUser("User2", 32));
        entries.put("batch-put-3", new TestUser("User3", 33));

        // Act
        cacheStore.putAll(entries, Duration.ofMinutes(5));

        // Assert
        assertEquals(3, cacheStore.size());
        assertTrue(cacheStore.get("batch-put-1", TestUser.class).isPresent());
        assertTrue(cacheStore.get("batch-put-2", TestUser.class).isPresent());
        assertTrue(cacheStore.get("batch-put-3", TestUser.class).isPresent());
    }

    @Test
    void testPutAllPermanent() {
        // Arrange
        Map<String, TestUser> entries = new HashMap<>();
        entries.put("perm-1", new TestUser("User1", 41));
        entries.put("perm-2", new TestUser("User2", 42));

        // Act
        cacheStore.putAll(entries);

        // Assert
        assertEquals(2, cacheStore.size());
        assertTrue(cacheStore.get("perm-1", TestUser.class).isPresent());
    }

    @Test
    void testEvictAll() {
        // Arrange
        cacheStore.put("evict-all-1", new TestUser("User1", 51), Duration.ofMinutes(5));
        cacheStore.put("evict-all-2", new TestUser("User2", 52), Duration.ofMinutes(5));
        cacheStore.put("evict-all-3", new TestUser("User3", 53), Duration.ofMinutes(5));

        // Act
        int evicted = cacheStore.evictAll(Arrays.asList("evict-all-1", "evict-all-2", "evict-all-missing"));

        // Assert
        assertEquals(2, evicted);
        assertFalse(cacheStore.get("evict-all-1", TestUser.class).isPresent());
        assertFalse(cacheStore.get("evict-all-2", TestUser.class).isPresent());
        assertTrue(cacheStore.get("evict-all-3", TestUser.class).isPresent());
    }

    // ==================== Cache Statistics Tests (v1.3.0) ====================

    @Test
    void testCacheStatistics() {
        // Arrange
        cacheStore.resetStatistics();
        String key1 = "stats-key-1";
        String key2 = "stats-key-2";

        // Act - Some puts
        cacheStore.put(key1, new TestUser("User1", 61), Duration.ofMinutes(5));
        cacheStore.put(key2, new TestUser("User2", 62), Duration.ofMinutes(5));

        // Some hits
        cacheStore.get(key1, TestUser.class);
        cacheStore.get(key2, TestUser.class);

        // Some misses
        cacheStore.get("nonexistent-1", TestUser.class);
        cacheStore.get("nonexistent-2", TestUser.class);

        // Eviction
        cacheStore.evict(key1);

        // Assert
        CacheStatistics stats = cacheStore.getStatistics();
        assertEquals(2, stats.getPutCount());
        assertEquals(2, stats.getHitCount());
        assertEquals(2, stats.getMissCount());
        assertEquals(1, stats.getEvictionCount());
        assertEquals(4, stats.getRequestCount());
        assertEquals(0.5, stats.getHitRate(), 0.001);
    }

    @Test
    void testResetStatistics() {
        // Arrange - Do some operations
        cacheStore.put("reset-key", new TestUser("User", 71), Duration.ofMinutes(5));
        cacheStore.get("reset-key", TestUser.class);

        // Verify stats are not zero
        CacheStatistics before = cacheStore.getStatistics();
        assertTrue(before.getPutCount() > 0 || before.getHitCount() > 0);

        // Act
        cacheStore.resetStatistics();

        // Assert
        CacheStatistics after = cacheStore.getStatistics();
        assertEquals(0, after.getPutCount());
        assertEquals(0, after.getHitCount());
        assertEquals(0, after.getMissCount());
        assertEquals(0, after.getEvictionCount());
    }

    // ==================== Pattern Eviction Tests (v1.3.0) ====================

    @Test
    void testEvictByPattern() {
        // Arrange
        cacheStore.put("user:1", new TestUser("User1", 81), Duration.ofMinutes(5));
        cacheStore.put("user:2", new TestUser("User2", 82), Duration.ofMinutes(5));
        cacheStore.put("user:3", new TestUser("User3", 83), Duration.ofMinutes(5));
        cacheStore.put("session:1", new TestUser("Session1", 84), Duration.ofMinutes(5));
        cacheStore.put("session:2", new TestUser("Session2", 85), Duration.ofMinutes(5));

        // Act - Evict all user: entries
        int evicted = cacheStore.evictByPattern("user:%");

        // Assert
        assertEquals(3, evicted);
        assertFalse(cacheStore.get("user:1", TestUser.class).isPresent());
        assertFalse(cacheStore.get("user:2", TestUser.class).isPresent());
        assertFalse(cacheStore.get("user:3", TestUser.class).isPresent());
        assertTrue(cacheStore.get("session:1", TestUser.class).isPresent());
        assertTrue(cacheStore.get("session:2", TestUser.class).isPresent());
    }

    @Test
    void testEvictByPatternNoMatch() {
        // Arrange
        cacheStore.put("data:1", new TestUser("Data1", 91), Duration.ofMinutes(5));

        // Act
        int evicted = cacheStore.evictByPattern("nonexistent:%");

        // Assert
        assertEquals(0, evicted);
        assertTrue(cacheStore.get("data:1", TestUser.class).isPresent());
    }

    static class TestUser {
        private String name;
        private int age;

        // Default constructor for Jackson
        public TestUser() {
        }

        public TestUser(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    @Test
    void testContainsKey_Integration() {
        // Arrange
        String key = "exists-" + UUID.randomUUID();
        cacheStore.put(key, new TestUser("Exists", 1), Duration.ofMinutes(1));

        // Act & Assert
        assertTrue(cacheStore.containsKey(key));
        assertFalse(cacheStore.containsKey("non-existent"));
    }

    @Test
    void testGetKeys_Integration() {
        // Arrange
        String prefix = "pattern-" + UUID.randomUUID();
        cacheStore.put(prefix + ":1", new TestUser("U1", 1), Duration.ofMinutes(1));
        cacheStore.put(prefix + ":2", new TestUser("U2", 2), Duration.ofMinutes(1));
        cacheStore.put("other:1", new TestUser("O1", 1), Duration.ofMinutes(1));

        // Act
        Collection<String> keys = cacheStore.getKeys(prefix + ":%");

        // Assert
        assertEquals(2, keys.size());
        assertTrue(keys.contains(prefix + ":1"));
        assertTrue(keys.contains(prefix + ":2"));
    }

    @Test
    void testAsyncOperations_Integration() throws Exception {
        // Arrange
        String key = "async-" + UUID.randomUUID();
        TestUser user = new TestUser("Async", 1);

        // Act - Put Async
        cacheStore.putAsync(key, user, Duration.ofMinutes(1)).get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Assert
        assertTrue(cacheStore.containsKey(key));

        // Act - Get Async
        Optional<TestUser> result = cacheStore.getAsync(key, TestUser.class).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        assertEquals("Async", result.get().getName());
    }
}
