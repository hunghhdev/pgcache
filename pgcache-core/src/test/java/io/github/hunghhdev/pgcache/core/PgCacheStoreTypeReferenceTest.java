package io.github.hunghhdev.pgcache.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generic reads: get/getAll with a Jackson TypeReference must deserialize
 * parameterized types properly — no more {@code List<LinkedHashMap>} leaking
 * out of the cache.
 */
@Testcontainers
class PgCacheStoreTypeReferenceTest {

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

    public static class User {
        public String name;
        public int age;

        public User() {
        }

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof User)) {
                return false;
            }
            User u = (User) o;
            return age == u.age && Objects.equals(name, u.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }
    }

    @Test
    void getWithTypeReferenceDeserializesParameterizedList() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("tr_list").build()) {
            List<User> users = Arrays.asList(new User("alice", 30), new User("bob", 25));
            store.put("users", users, Duration.ofMinutes(5));

            Optional<List<User>> cached = store.get("users", new TypeReference<List<User>>() {});

            assertTrue(cached.isPresent());
            assertEquals(users, cached.get(), "elements must come back as User instances, not LinkedHashMap");
            assertTrue(cached.get().get(0) instanceof User);
        }
    }

    @Test
    void getWithTypeReferenceDeserializesNestedMap() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("tr_map").build()) {
            Map<String, List<Integer>> scores = new HashMap<>();
            scores.put("a", Arrays.asList(1, 2, 3));
            store.put("scores", scores, Duration.ofMinutes(5));

            Optional<Map<String, List<Integer>>> cached =
                    store.get("scores", new TypeReference<Map<String, List<Integer>>>() {});

            assertEquals(Optional.of(scores), cached);
        }
    }

    @Test
    void getWithTypeReferenceMissingKeyReturnsEmpty() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("tr_miss").build()) {
            assertEquals(Optional.empty(), store.get("absent", new TypeReference<List<User>>() {}));
        }
    }

    @Test
    void getWithTypeReferenceSurfacesCachedNullAsMarker() {
        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource).tableName("tr_null").allowNullValues(true).build()) {
            store.put("k", null, Duration.ofMinutes(5));

            Optional<Object> cached = store.get("k", new TypeReference<Object>() {});

            assertTrue(cached.isPresent());
            assertTrue(cached.get() instanceof NullValueMarker,
                    "cached null must surface exactly like the Class-based get()");
        }
    }

    @Test
    void getAllWithTypeReferenceDeserializesEveryValueTyped() {
        try (PgCacheStore store = PgCacheStore.builder().dataSource(dataSource).tableName("tr_getall").build()) {
            store.put("team:a", Arrays.asList(new User("alice", 30)), Duration.ofMinutes(5));
            store.put("team:b", Arrays.asList(new User("bob", 25)), Duration.ofMinutes(5));

            Map<String, List<User>> cached = store.getAll(
                    Arrays.asList("team:a", "team:b", "team:missing"),
                    new TypeReference<List<User>>() {});

            assertEquals(2, cached.size());
            assertEquals(new User("alice", 30), cached.get("team:a").get(0));
            assertTrue(cached.get("team:b").get(0) instanceof User);
            assertFalse(cached.containsKey("team:missing"));
        }
    }
}
