package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.NullValueMarker;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.cache.Cache;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Quarkus Cache implementation backed by PgCacheStore.
 * Provides Quarkus Cache abstraction support with TTL capabilities.
 *
 * @since 1.5.0
 */
public class PgQuarkusCache implements Cache {

    private static final Logger logger = LoggerFactory.getLogger(PgQuarkusCache.class);

    private final String name;
    private final PgCacheStore cacheStore;
    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final TTLPolicy ttlPolicy;

    public PgQuarkusCache(String name, PgCacheStore cacheStore, Duration defaultTtl,
                          boolean allowNullValues, TTLPolicy ttlPolicy) {
        this.name = name;
        this.cacheStore = cacheStore;
        this.defaultTtl = defaultTtl;
        this.allowNullValues = allowNullValues;
        this.ttlPolicy = ttlPolicy != null ? ttlPolicy : TTLPolicy.ABSOLUTE;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getDefaultKey() {
        return "default";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Uni<V> get(K key, Function<K, V> valueLoader) {
        String keyStr = toKeyString(key);

        // Try to get from cache asynchronously (Lazy creation)
        return Uni.createFrom().completionStage(() -> cacheStore.getAsync(keyStr, Object.class))
            .flatMap(optionalValue -> {
                if (optionalValue.isPresent()) {
                    Object value = optionalValue.get();
                    if (value instanceof NullValueMarker) {
                        return Uni.createFrom().nullItem();
                    }
                    return Uni.createFrom().item((V) value);
                }

                // Cache miss - load value
                // Run valueLoader on worker thread to avoid blocking the IO thread
                return Uni.createFrom().item(() -> valueLoader.apply(key))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                    .call(value -> {
                        if (value != null || allowNullValues) {
                            return Uni.createFrom().completionStage(() -> 
                                cacheStore.putAsync(keyStr, value, defaultTtl, ttlPolicy)
                            );
                        }
                        return Uni.createFrom().voidItem();
                    });
            });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Uni<V> getAsync(K key, Function<K, Uni<V>> valueLoader) {
        String keyStr = toKeyString(key);

        return Uni.createFrom().completionStage(() -> cacheStore.getAsync(keyStr, Object.class))
            .flatMap(optionalValue -> {
                if (optionalValue.isPresent()) {
                    Object value = optionalValue.get();
                    if (value instanceof NullValueMarker) {
                        return Uni.createFrom().nullItem();
                    }
                    return Uni.createFrom().item((V) value);
                }

                // Cache miss - load value asynchronously
                return valueLoader.apply(key)
                    .call(value -> {
                        if (value != null || allowNullValues) {
                            return Uni.createFrom().completionStage(() -> 
                                cacheStore.putAsync(keyStr, value, defaultTtl, ttlPolicy)
                            );
                        }
                        return Uni.createFrom().voidItem();
                    });
            });
    }

    @Override
    public Uni<Void> invalidate(Object key) {
        if (key == null) {
            return Uni.createFrom().voidItem();
        }
        String keyStr = toKeyString(key);
        return Uni.createFrom().completionStage(() -> cacheStore.evictAsync(keyStr))
                .invoke(() -> logger.debug("Invalidated key '{}' from cache '{}'", key, name));
    }

    @Override
    public Uni<Void> invalidateAll() {
        // Clear is still sync in core, so run on worker pool
        return Uni.createFrom().item(() -> {
            cacheStore.clear();
            logger.debug("Invalidated all entries from cache '{}'", name);
            return (Void) null;
        })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> invalidateIf(Predicate<Object> predicate) {
        // Complex invalidation, run on worker pool
        return Uni.createFrom().item(() -> {
            String pattern = name + ":%";
            cacheStore.evictByPattern(pattern);
            logger.debug("Invalidated entries by predicate from cache '{}'", name);
            return (Void) null;
        })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Cache> T as(Class<T> type) {
        if (type.isAssignableFrom(this.getClass())) {
            return (T) this;
        }
        throw new IllegalArgumentException("Cannot cast " + this.getClass().getName() + " to " + type.getName());
    }

    public PgCacheStore getCacheStore() {
        return cacheStore;
    }

    public long size() {
        return cacheStore.size();
    }

    public void cleanupExpired() {
        cacheStore.cleanupExpired();
    }

    private String toKeyString(Object key) {
        return name + ":" + key.toString();
    }
}
