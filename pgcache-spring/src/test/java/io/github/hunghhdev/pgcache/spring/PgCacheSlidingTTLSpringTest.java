package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring integration tests for sliding TTL functionality
 * 
 * @author Hung Hoang
 * @since 1.1.0
 */
@Testcontainers
class PgCacheSlidingTTLSpringTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private PgCache cache;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        
        dataSource = new HikariDataSource(config);
        
        PgCacheManager.PgCacheConfiguration cacheConfig = 
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofMinutes(5))
                .allowNullValues(true)
                .tableName("test_cache")
                .backgroundCleanupEnabled(false)
                .ttlPolicy(TTLPolicy.SLIDING)
                .build();
                
        cache = new PgCache("test-cache", 
                           new io.github.hunghhdev.pgcache.core.PgCacheStore(dataSource),
                           cacheConfig.getDefaultTtl(),
                           cacheConfig.isAllowNullValues(),
                           cacheConfig.getTtlPolicy());
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.clear();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void testSlidingTTLWithSpringIntegration() throws InterruptedException {
        // Put with sliding TTL policy from configuration
        cache.put("session123", "sessionData", Duration.ofSeconds(2));
        
        // Verify TTL policy is SLIDING
        Optional<TTLPolicy> policy = cache.getTTLPolicy("session123");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
        
        // Wait 1 second, then access (should refresh TTL)
        Thread.sleep(1000);
        String result = cache.get("session123", String.class);
        assertEquals("sessionData", result);
        
        // Wait another 1.5 seconds (total 2.5s) - should still be valid due to sliding
        Thread.sleep(1500);
        result = cache.get("session123", String.class);
        assertEquals("sessionData", result);
    }

    @Test
    void testExplicitTTLPolicyOverride() {
        // Put with explicit ABSOLUTE policy (overrides configuration)
        cache.put("absoluteKey", "absoluteValue", Duration.ofSeconds(2), TTLPolicy.ABSOLUTE);
        
        // Verify TTL policy is ABSOLUTE
        Optional<TTLPolicy> policy = cache.getTTLPolicy("absoluteKey");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.ABSOLUTE, policy.get());
    }

    @Test
    void testRemainingTTLWithSpringIntegration() throws InterruptedException {
        cache.put("ttlTest", "value", Duration.ofSeconds(10));
        
        Optional<Duration> remainingTTL = cache.getRemainingTTL("ttlTest");
        assertTrue(remainingTTL.isPresent());
        assertTrue(remainingTTL.get().getSeconds() <= 10);
        assertTrue(remainingTTL.get().getSeconds() > 8);
    }

    @Test
    void testRefreshTTLWithSpringIntegration() throws InterruptedException {
        cache.put("refreshTest", "value", Duration.ofSeconds(2));
        
        // Wait 1 second, then access with refresh (should reset TTL)
        Thread.sleep(1000);
        String result = cache.get("refreshTest", String.class, true);
        assertEquals("value", result);
        
        // Wait another 1.5 seconds (total 2.5s) - should still be valid due to refresh
        Thread.sleep(1500);
        result = cache.get("refreshTest", String.class, false);
        assertEquals("value", result);
    }

    @Test
    void testBackwardCompatibilityWithSpringIntegration() {
        // Test that existing Spring cache methods still work
        cache.put("backwardTest", "value");
        
        String result = cache.get("backwardTest", String.class);
        assertEquals("value", result);
        
        // Should use default TTL policy from configuration (SLIDING)
        Optional<TTLPolicy> policy = cache.getTTLPolicy("backwardTest");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
    }

    @Test
    void testSpringCacheAbstractionWithSlidingTTL() {
        // Test Spring Cache abstraction methods with sliding TTL
        cache.put("springTest", "springValue");
        
        // Test ValueWrapper get method
        org.springframework.cache.Cache.ValueWrapper wrapper = cache.get("springTest");
        assertNotNull(wrapper);
        assertEquals("springValue", wrapper.get());
        
        // Test putIfAbsent
        org.springframework.cache.Cache.ValueWrapper existing = cache.putIfAbsent("springTest", "newValue");
        assertNotNull(existing);
        assertEquals("springValue", existing.get());
        
        // Value should still be the original
        String result = cache.get("springTest", String.class);
        assertEquals("springValue", result);
    }

    @Test
    void testDefaultTTLPolicyConfiguration() {
        // Test that cache uses the configured default TTL policy
        assertEquals(TTLPolicy.SLIDING, cache.getDefaultTTLPolicy());
        
        // Put without explicit TTL policy should use default
        cache.put("defaultTest", "defaultValue");
        
        Optional<TTLPolicy> policy = cache.getTTLPolicy("defaultTest");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
    }

    @Test
    void testSlidingTTLWithCallableLoader() throws InterruptedException {
        // Test cache loader with sliding TTL
        String result = cache.get("loaderTest", () -> "loadedValue");
        assertEquals("loadedValue", result);
        
        // Verify it's stored with sliding TTL
        Optional<TTLPolicy> policy = cache.getTTLPolicy("loaderTest");
        assertTrue(policy.isPresent());
        assertEquals(TTLPolicy.SLIDING, policy.get());
        
        // Accessing again should refresh TTL and return cached value
        String cachedResult = cache.get("loaderTest", () -> "shouldNotLoad");
        assertEquals("loadedValue", cachedResult);
    }
}
