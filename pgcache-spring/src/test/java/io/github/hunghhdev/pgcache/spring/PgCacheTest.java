package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgCacheTest {

    @Mock
    private PgCacheStore cacheStore;

    @Test
    void getWithNullType_doesNotThrowAndReturnsNull() {
        PgCache cache = new PgCache("test", cacheStore, Duration.ofMinutes(5), true, TTLPolicy.ABSOLUTE);

        when(cacheStore.get(eq("test:key1"), isNull(), eq(true)))
                .thenThrow(new IllegalArgumentException("type cannot be null"));

        assertDoesNotThrow(() -> assertNull(cache.get("key1", (Class<Object>) null)));
    }
}
