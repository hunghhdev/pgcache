package io.github.hunghhdev.pgcache.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hunghhdev.pgcache.core.PgCacheException;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Spring Cache contract (S12): {@code get(key, type)} must throw
 * {@link IllegalStateException} when a cached value exists but does not match
 * the requested type — silently returning null hides data-model bugs. Other
 * backend failures keep degrading to a miss.
 */
@ExtendWith(MockitoExtension.class)
class PgCacheTypeMismatchTest {

    @Mock
    private PgCacheStore cacheStore;

    /** A real Jackson type-mismatch failure, produced the way the store produces it. */
    private static JsonProcessingException typeMismatch() {
        try {
            new ObjectMapper().readValue("\"definitely not a number\"", Integer.class);
            throw new AssertionError("parse should have failed");
        } catch (JsonProcessingException e) {
            return e;
        }
    }

    @Test
    void typeMismatchThrowsIllegalStateExceptionPerSpringContract() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), false, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:k", Integer.class, true))
                .thenThrow(new PgCacheException("Failed to deserialize cached value", typeMismatch()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.get("k", Integer.class));
        assertTrue(thrown.getMessage().contains("test:k") || thrown.getMessage().contains("k"),
                "message should identify the key");
    }

    @Test
    void nonMismatchBackendFailureStillDegradesToNull() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), false, TTLPolicy.ABSOLUTE);

        when(cacheStore.get("test:k", Integer.class, true))
                .thenThrow(new PgCacheException("db down", new java.sql.SQLException("boom")));

        assertNull(cache.get("k", Integer.class), "infrastructure failures degrade reads to a miss");
    }
}
