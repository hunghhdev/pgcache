package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Producer wiring: custom table name, auto-create-table flag, managed executor
 * (never {@code ForkJoinPool.commonPool()}), and an injectable health check.
 */
@Testcontainers
class PgQuarkusCacheProducerConfigTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private static PGSimpleDataSource createDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    @Test
    void tableNameConfigIsHonored() {
        PgQuarkusCacheProducer producer = new PgQuarkusCacheProducer();
        producer.dataSource = createDataSource();
        producer.config = new TestConfig() {
            @Override
            public Optional<String> tableName() {
                return Optional.of("quarkus_named_cache");
            }
        };

        PgCacheStore store = producer.pgCacheStore();
        try {
            store.put("k", "v", Duration.ofMinutes(5));
            assertEquals(Optional.of("v"), store.get("k", String.class));
            assertEquals("quarkus_named_cache", store.getTableName());
        } finally {
            store.close();
        }
    }

    @Test
    void asyncOperationsMustNotRunOnCommonPool() throws Exception {
        ExecutorService customExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "pgcache-managed-exec"));

        @SuppressWarnings("unchecked")
        Instance<ExecutorService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(customExecutor);

        PgQuarkusCacheProducer producer = new PgQuarkusCacheProducer();
        producer.dataSource = createDataSource();
        producer.config = new TestConfig();
        producer.managedExecutorService = instance;

        PgCacheStore store = producer.pgCacheStore();
        try {
            AtomicReference<String> completionThread = new AtomicReference<>();
            store.putAsync("async-k", "async-v", Duration.ofMinutes(5))
                    .thenRun(() -> completionThread.set(Thread.currentThread().getName()))
                    .get();

            assertEquals("pgcache-managed-exec", completionThread.get(),
                    "async work must run on the injected managed executor, not commonPool");
        } finally {
            store.close();
            customExecutor.shutdownNow();
        }
    }

    @Test
    void healthCheckIsProducible() {
        PgQuarkusCacheProducer producer = new PgQuarkusCacheProducer();
        producer.dataSource = createDataSource();
        producer.config = new TestConfig();

        PgCacheStore store = producer.pgCacheStore();
        try {
            PgQuarkusCacheManager manager = producer.pgQuarkusCacheManager(store);
            PgQuarkusHealthCheck healthCheck = producer.pgQuarkusHealthCheck(manager);

            PgQuarkusHealthCheck.HealthResult result = healthCheck.check();
            assertTrue(result.isUp(), "health must be UP against a live database: " + result.getError());
        } finally {
            store.close();
        }
    }

    /** Defaults: global allow-null=false, cleanup off, no per-cache config. */
    private static class TestConfig implements PgQuarkusCacheConfig {
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
            return Collections.emptyMap();
        }
    }
}
