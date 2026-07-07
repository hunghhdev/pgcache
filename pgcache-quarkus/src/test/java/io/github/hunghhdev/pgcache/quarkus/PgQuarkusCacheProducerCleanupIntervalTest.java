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
 * A sub-minute {@code pgcache.background-cleanup.interval} (e.g. PT30S) must
 * not crash the CDI producer at startup. Previously the interval was
 * converted with {@code Duration.toMinutes()}, truncating 30s to 0 and
 * blowing up the cleanup scheduler.
 */
@Testcontainers
class PgQuarkusCacheProducerCleanupIntervalTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void subMinuteCleanupIntervalMustNotFailStoreCreation() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        PgQuarkusCacheProducer producer = new PgQuarkusCacheProducer();
        producer.dataSource = dataSource;
        producer.config = new SubMinuteCleanupConfig();

        PgCacheStore store = producer.pgCacheStore();
        try {
            store.put("k", "v", Duration.ofMinutes(5));
            assertEquals(Optional.of("v"), store.get("k", String.class));
        } finally {
            store.close();
        }
    }

    /** Background cleanup enabled with a 30-second interval. */
    private static class SubMinuteCleanupConfig implements PgQuarkusCacheConfig {
        @Override
        public Optional<Duration> defaultTtl() {
            return Optional.empty();
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
        public Optional<String> tableName() {
            return Optional.empty();
        }

        @Override
        public boolean autoCreateTable() {
            return true;
        }

        @Override
        public BackgroundCleanupConfig backgroundCleanup() {
            return new BackgroundCleanupConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Duration interval() {
                    return Duration.ofSeconds(30);
                }
            };
        }

        @Override
        public Map<String, CacheInstanceConfig> caches() {
            return Collections.emptyMap();
        }
    }
}
