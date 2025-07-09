package dev.hunghh.pgcache.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
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
}
