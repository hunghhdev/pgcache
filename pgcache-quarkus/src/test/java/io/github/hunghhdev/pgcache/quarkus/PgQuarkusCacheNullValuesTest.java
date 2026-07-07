package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A cache configured with {@code pgcache.caches.<name>.allow-null-values=true}
 * must be able to cache nulls even when the global
 * {@code pgcache.allow-null-values} is {@code false}. All caches share one
 * store, so the store must accept null markers whenever any cache allows them.
 */
@Testcontainers
class PgQuarkusCacheNullValuesTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void perCacheAllowNullValuesWorksWhenGlobalIsFalse() {
        PgQuarkusCacheProducer producer = new PgQuarkusCacheProducer();
        producer.dataSource = createDataSource();
        producer.config = new TestConfig();

        PgCacheStore store = producer.pgCacheStore();
        try {
            PgQuarkusCacheManager manager = producer.pgQuarkusCacheManager(store);

            // Cache-level override: allow-null-values=true
            PgQuarkusCache nullableCache = (PgQuarkusCache) manager.getCache("nullable").get();
            String value = nullableCache.<String, String>get("k", k -> null)
                    .await().atMost(Duration.ofSeconds(10));
            assertNull(value);
            assertTrue(store.containsKey("nullable:k"),
                    "null must be cached (as marker) for a cache that allows null values");

            // No override: inherits global allow-null-values=false → null not cached
            PgQuarkusCache plainCache = (PgQuarkusCache) manager.getCache("plain").get();
            String value2 = plainCache.<String, String>get("k2", k -> null)
                    .await().atMost(Duration.ofSeconds(10));
            assertNull(value2);
            assertFalse(store.containsKey("plain:k2"),
                    "null must not be cached for a cache that inherits global=false");
        } finally {
            store.close();
        }
    }

    private static PGSimpleDataSource createDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    /** Global allow-null-values=false; cache "nullable" overrides with true. */
    private static class TestConfig implements PgQuarkusCacheConfig {
        @Override
        public Optional<Duration> defaultTtl() {
            return Optional.of(Duration.ofMinutes(5));
        }

        @Override
        public boolean allowNullValues() {
            return false;
        }

        @Override
        public String ttlPolicy() {
            return "ABSOLUTE";
        }

        @Override
        public BackgroundCleanupConfig backgroundCleanup() {
            return new BackgroundCleanupConfig() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public Duration interval() {
                    return Duration.ofMinutes(30);
                }
            };
        }

        @Override
        public Map<String, CacheInstanceConfig> caches() {
            return Collections.singletonMap("nullable", new CacheInstanceConfig() {
                @Override
                public Optional<Duration> ttl() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> ttlPolicy() {
                    return Optional.empty();
                }

                @Override
                public Optional<Boolean> allowNullValues() {
                    return Optional.of(true);
                }
            });
        }
    }
}
