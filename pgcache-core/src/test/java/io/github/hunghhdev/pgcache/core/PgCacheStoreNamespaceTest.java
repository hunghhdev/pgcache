package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Namespaced stores share one table but must behave as fully isolated caches:
 * scoped reads/writes/clear/size/keys, caller-visible keys never expose the
 * internal prefix.
 */
@Testcontainers
class PgCacheStoreNamespaceTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
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

    private PgCacheStore store(String table, String namespace) {
        return PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName(table)
                .namespace(namespace)
                .build();
    }

    @Test
    void namespacesOnTheSameTableAreIsolated() {
        try (PgCacheStore a = store("ns_iso", "tenant_a");
             PgCacheStore b = store("ns_iso", "tenant_b")) {

            a.put("k", "value-a", Duration.ofMinutes(5));
            b.put("k", "value-b", Duration.ofMinutes(5));

            assertEquals(Optional.of("value-a"), a.get("k", String.class));
            assertEquals(Optional.of("value-b"), b.get("k", String.class));

            a.evict("k");
            assertFalse(a.containsKey("k"));
            assertTrue(b.containsKey("k"), "evicting in one namespace must not touch the other");
        }
    }

    @Test
    void clearOnlyClearsOwnNamespace() {
        try (PgCacheStore a = store("ns_clear", "tenant_a");
             PgCacheStore b = store("ns_clear", "tenant_b")) {

            a.put("k1", "v", Duration.ofMinutes(5));
            a.put("k2", "v", Duration.ofMinutes(5));
            b.put("k1", "v", Duration.ofMinutes(5));

            a.clear();

            assertEquals(0, a.size());
            assertEquals(1, b.size(), "clear() must be scoped to the namespace");
            assertTrue(b.containsKey("k1"));
        }
    }

    @Test
    void sizeAndPatternSizeAreScoped() {
        try (PgCacheStore a = store("ns_size", "tenant_a");
             PgCacheStore b = store("ns_size", "tenant_b")) {

            a.put("user:1", "v", Duration.ofMinutes(5));
            a.put("user:2", "v", Duration.ofMinutes(5));
            b.put("user:1", "v", Duration.ofMinutes(5));

            assertEquals(2, a.size());
            assertEquals(1, b.size());
            assertEquals(2, a.size("user:%"));
            assertEquals(1, b.size("user:%"));
        }
    }

    @Test
    void keysComeBackWithoutThePrefix() {
        try (PgCacheStore a = store("ns_keys", "tenant_a");
             PgCacheStore b = store("ns_keys", "tenant_b")) {

            a.put("user:1", "v", Duration.ofMinutes(5));
            a.put("user:2", "v", Duration.ofMinutes(5));
            b.put("user:9", "v", Duration.ofMinutes(5));

            assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")), new HashSet<>(a.getKeys("user:%")));
            assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")), new HashSet<>(a.getAllKeys()));

            Set<String> scanned = new HashSet<>();
            a.scanKeys("user:%", 1).forEach(scanned::add);
            assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")), scanned);
        }
    }

    @Test
    void batchOperationsAreScopedAndStripPrefixes() {
        try (PgCacheStore a = store("ns_batch", "tenant_a");
             PgCacheStore b = store("ns_batch", "tenant_b")) {

            Map<String, String> entries = new HashMap<>();
            entries.put("k1", "v1");
            entries.put("k2", "v2");
            a.putAll(entries, Duration.ofMinutes(5));

            Map<String, String> fetched = a.getAll(Arrays.asList("k1", "k2"), String.class);
            assertEquals(entries, fetched, "getAll must key results by the caller's keys");

            assertTrue(b.getAll(Arrays.asList("k1", "k2"), String.class).isEmpty());

            assertEquals(2, a.evictAll(Arrays.asList("k1", "k2", "missing")));
        }
    }

    @Test
    void evictByPatternIsScoped() {
        try (PgCacheStore a = store("ns_pattern", "tenant_a");
             PgCacheStore b = store("ns_pattern", "tenant_b")) {

            a.put("user:1", "v", Duration.ofMinutes(5));
            b.put("user:1", "v", Duration.ofMinutes(5));

            assertEquals(1, a.evictByPattern("user:%"));
            assertTrue(b.containsKey("user:1"));
        }
    }

    @Test
    void atomicOpsAndGetOrComputeRespectNamespace() {
        try (PgCacheStore a = store("ns_atomic", "tenant_a");
             PgCacheStore b = store("ns_atomic", "tenant_b")) {

            assertEquals(1, a.increment("counter", 1));
            assertEquals(5, b.increment("counter", 5));
            assertEquals(2, a.increment("counter", 1));

            assertEquals("from-a", a.getOrCompute("cfg", String.class, null, () -> "from-a"));
            assertEquals("from-b", b.getOrCompute("cfg", String.class, null, () -> "from-b"));
        }
    }

    @Test
    void eventsCarryCallerVisibleKeys() {
        List<String> putKeys = new ArrayList<>();
        List<String> evictKeys = new ArrayList<>();
        CacheEventListener listener = new CacheEventListener() {
            @Override
            public void onPut(String key, Object value) {
                putKeys.add(key);
            }

            @Override
            public void onEvict(String key) {
                evictKeys.add(key);
            }
        };

        try (PgCacheStore a = PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName("ns_events")
                .namespace("tenant_a")
                .addEventListener(listener)
                .build()) {

            a.put("k", "v", Duration.ofMinutes(5));
            a.evict("k");

            assertEquals(Arrays.asList("k"), putKeys, "listeners must see the caller's key, not the prefixed one");
            assertEquals(Arrays.asList("k"), evictKeys);
        }
    }

    @Test
    void namespaceWithLikeMetaCharactersDoesNotLeakAcross() {
        try (PgCacheStore underscore = store("ns_meta", "ten_nt");
             PgCacheStore letter = store("ns_meta", "tenant")) {

            underscore.put("k", "underscore", Duration.ofMinutes(5));
            letter.put("k", "letter", Duration.ofMinutes(5));

            underscore.clear();

            assertFalse(underscore.containsKey("k"));
            assertTrue(letter.containsKey("k"),
                    "the _ in a namespace must be escaped in scoped LIKE queries");
        }
    }

    @Test
    void namespaceValidation() {
        PgCacheStore.Builder builder = PgCacheStore.builder().dataSource(dataSource);

        assertThrows(IllegalArgumentException.class, () -> builder.namespace(null));
        assertThrows(IllegalArgumentException.class, () -> builder.namespace(""));
        assertThrows(IllegalArgumentException.class, () -> builder.namespace("  "));
        assertThrows(IllegalArgumentException.class, () -> builder.namespace("bad:ns"),
                "the key-separator ':' inside a namespace would make prefixes ambiguous");
    }
}
