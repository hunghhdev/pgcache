package dev.hunghh.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgCacheStoreThreadSafetyTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_thread_test")
            .withUsername("test")
            .withPassword("test");

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    @Test
    void testConcurrentInitialization() throws InterruptedException {
        // Test that multiple threads can safely create PgCacheStore instances simultaneously
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<PgCacheStore>> futures = new ArrayList<>();
        
        // Submit tasks that create PgCacheStore instances concurrently
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> 
                PgCacheStore.builder()
                    .dataSource(dataSource)
                    .autoCreateTable(true)
                    .build()
            ));
        }
        
        // Wait for all tasks to complete and verify no exceptions
        List<PgCacheStore> stores = new ArrayList<>();
        for (Future<PgCacheStore> future : futures) {
            try {
                PgCacheStore store = future.get(10, TimeUnit.SECONDS);
                assertNotNull(store);
                assertTrue(store.tableExists());
                stores.add(store);
            } catch (Exception e) {
                fail("Concurrent initialization failed: " + e.getMessage());
            }
        }
        
        assertEquals(threadCount, stores.size());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void testConcurrentCacheOperations() throws InterruptedException {
        // Create a single cache instance to test concurrent operations
        PgCacheStore cache = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .build();
        
        cache.clear(); // Start with clean cache
        
        int threadCount = 20;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        AtomicInteger successfulPuts = new AtomicInteger(0);
        AtomicInteger successfulGets = new AtomicInteger(0);
        AtomicInteger successfulEvicts = new AtomicInteger(0);
        
        List<Future<Void>> futures = new ArrayList<>();
        
        // Submit concurrent read/write operations
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "thread-" + threadId + "-key-" + i;
                    TestObject value = new TestObject("value-" + threadId + "-" + i, threadId * 1000 + i);
                    
                    try {
                        // Put operation
                        cache.put(key, value, Duration.ofMinutes(5));
                        successfulPuts.incrementAndGet();
                        
                        // Get operation
                        Optional<TestObject> retrieved = cache.get(key, TestObject.class);
                        if (retrieved.isPresent()) {
                            successfulGets.incrementAndGet();
                            assertEquals(value.getName(), retrieved.get().getName());
                            assertEquals(value.getValue(), retrieved.get().getValue());
                        }
                        
                        // Evict some entries
                        if (i % 3 == 0) {
                            cache.evict(key);
                            successfulEvicts.incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        // Log but don't fail - some contention is expected
                        System.err.println("Operation failed in thread " + threadId + ": " + e.getMessage());
                    }
                }
                return null;
            }));
        }
        
        // Wait for all operations to complete
        for (Future<Void> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Concurrent operations failed: " + e.getMessage());
            }
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        
        // Verify that most operations were successful
        int totalExpectedPuts = threadCount * operationsPerThread;
        assertTrue(successfulPuts.get() > totalExpectedPuts * 0.9, 
                "Expected most puts to succeed, got " + successfulPuts.get() + "/" + totalExpectedPuts);
        
        assertTrue(successfulGets.get() > 0, "Expected some gets to succeed");
        assertTrue(successfulEvicts.get() > 0, "Expected some evicts to succeed");
        
        // Verify cache is still functional
        cache.put("final-test", new TestObject("final", 999), Duration.ofMinutes(1));
        Optional<TestObject> finalResult = cache.get("final-test", TestObject.class);
        assertTrue(finalResult.isPresent());
        assertEquals("final", finalResult.get().getName());
    }

    @Test
    void testConcurrentExpiredKeyHandling() throws InterruptedException {
        PgCacheStore cache = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .build();
        
        cache.clear();
        
        // Put entries with very short TTL
        String key = "expiring-key";
        cache.put(key, new TestObject("expiring", 123), Duration.ofMillis(100));
        
        // Wait for expiration
        Thread.sleep(200);
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Optional<TestObject>>> futures = new ArrayList<>();
        
        // Multiple threads try to get the expired key simultaneously
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> cache.get(key, TestObject.class)));
        }
        
        // All should return empty (expired)
        for (Future<Optional<TestObject>> future : futures) {
            try {
                Optional<TestObject> result = future.get(5, TimeUnit.SECONDS);
                assertFalse(result.isPresent(), "Expired key should return empty");
            } catch (Exception e) {
                fail("Concurrent expired key handling failed: " + e.getMessage());
            }
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    static class TestObject {
        private String name;
        private int value;

        public TestObject() {} // Jackson default constructor

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
