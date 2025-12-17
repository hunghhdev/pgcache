package io.github.hunghhdev.pgcache.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Tests for null value support using NullValueMarker pattern.
 */
@Testcontainers
public class PgCacheStoreNullValueTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static DataSource dataSource;
    private PgCacheStore allowNullsStore;
    private PgCacheStore disallowNullsStore;

    @BeforeAll
    static void setupDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void tearDownDataSource() {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    @BeforeEach
    void setup() {
        // Create store with null values allowed
        allowNullsStore = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .allowNullValues(true)
                .build();

        // Create store with null values disallowed (default)
        disallowNullsStore = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .allowNullValues(false)
                .build();

        // Clear the cache for each test
        allowNullsStore.clear();
        disallowNullsStore.clear();
    }

    @Test
    void testNullValueWithAllowNullsEnabled() {
        // Store null value
        allowNullsStore.put("null-key", null);

        // Retrieve null value - should return NullValueMarker (not empty Optional)
        Optional<Object> result = allowNullsStore.get("null-key", Object.class);

        assertTrue(result.isPresent(), "Null value should be present in cache");
        assertTrue(result.get() instanceof NullValueMarker, "Should return NullValueMarker for cached null");
    }

    @Test
    void testNullValueWithAllowNullsDisabled() {
        // Attempt to store null value should throw exception
        assertThrows(PgCacheException.class, () -> {
            disallowNullsStore.put("null-key", null);
        }, "Should throw exception when storing null with allowNullValues=false");
    }

    @Test
    void testNullValueWithTTL() {
        // Store null value with TTL
        allowNullsStore.put("null-key-ttl", null, Duration.ofSeconds(10), TTLPolicy.ABSOLUTE);

        // Retrieve immediately - should return NullValueMarker
        Optional<Object> result = allowNullsStore.get("null-key-ttl", Object.class);
        assertTrue(result.isPresent(), "Null value with TTL should be stored");
        assertTrue(result.get() instanceof NullValueMarker, "Should return NullValueMarker");
    }

    @Test
    void testNullValueNotConfusedWithMissingKey() {
        // Store null value
        allowNullsStore.put("null-key", null);

        // Check that null value exists vs non-existent key
        Optional<Object> nullValue = allowNullsStore.get("null-key", Object.class);
        Optional<Object> missingKey = allowNullsStore.get("nonexistent-key", Object.class);

        // Null value should return NullValueMarker, missing key should return empty
        assertTrue(nullValue.isPresent(), "Cached null should be present");
        assertTrue(nullValue.get() instanceof NullValueMarker, "Cached null should return NullValueMarker");
        assertFalse(missingKey.isPresent(), "Missing key should return empty Optional");

        // Size should be 1 (only the null entry exists)
        assertEquals(1, allowNullsStore.size(), "Cache should contain the null entry");
    }

    @Test
    void testEvictNullValue() {
        // Store null value
        allowNullsStore.put("null-key", null);

        // Verify it's stored
        assertEquals(1, allowNullsStore.size());

        // Evict it
        allowNullsStore.evict("null-key");

        // Verify it's removed
        assertEquals(0, allowNullsStore.size());
    }

    @Test
    void testNormalValueAfterNull() {
        // Store null value
        allowNullsStore.put("key", null);
        Optional<Object> nullResult = allowNullsStore.get("key", Object.class);
        assertTrue(nullResult.isPresent());
        assertTrue(nullResult.get() instanceof NullValueMarker);

        // Overwrite with normal value
        allowNullsStore.put("key", "actual-value");
        Optional<String> actualResult = allowNullsStore.get("key", String.class);
        assertTrue(actualResult.isPresent());
        assertEquals("actual-value", actualResult.get());
    }

    @Test
    void testNullAfterNormalValue() {
        // Store normal value
        allowNullsStore.put("key", "actual-value");
        Optional<String> actualResult = allowNullsStore.get("key", String.class);
        assertTrue(actualResult.isPresent());
        assertEquals("actual-value", actualResult.get());

        // Overwrite with null
        allowNullsStore.put("key", null);
        Optional<Object> nullResult = allowNullsStore.get("key", Object.class);
        assertTrue(nullResult.isPresent());
        assertTrue(nullResult.get() instanceof NullValueMarker);
    }

    @Test
    void testMultipleNullValuesWithDifferentTypes() {
        // Store null for String type
        allowNullsStore.put("null-string", null);

        // Store null for Integer type (note: retrieving with different type will still work)
        allowNullsStore.put("null-int", null);

        Optional<Object> stringResult = allowNullsStore.get("null-string", Object.class);
        Optional<Object> intResult = allowNullsStore.get("null-int", Object.class);

        assertTrue(stringResult.isPresent());
        assertTrue(stringResult.get() instanceof NullValueMarker);
        assertTrue(intResult.isPresent());
        assertTrue(intResult.get() instanceof NullValueMarker);
        assertEquals(2, allowNullsStore.size());
    }

    @Test
    void testNullValueSlidingTTL() {
        // Store null with sliding TTL
        allowNullsStore.put("null-sliding", null, Duration.ofSeconds(10), TTLPolicy.SLIDING);

        // Access multiple times to refresh TTL
        for (int i = 0; i < 3; i++) {
            Optional<Object> result = allowNullsStore.get("null-sliding", Object.class, true);
            assertTrue(result.isPresent(), "Null value with sliding TTL should be accessible");
            assertTrue(result.get() instanceof NullValueMarker, "Should return NullValueMarker");
        }

        // Verify TTL policy
        Optional<TTLPolicy> policy = allowNullsStore.getTTLPolicy("null-sliding");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
    }
}
