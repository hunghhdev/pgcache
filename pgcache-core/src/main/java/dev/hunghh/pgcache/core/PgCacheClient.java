package dev.hunghh.pgcache.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Main client for interacting with the cache using a PgCacheStore backend.
 */
public interface PgCacheClient {
    <T> Optional<T> get(String key, Class<T> clazz);
    <T> void put(String key, T value, Duration ttl);
    void evict(String key);
    void clear();
    int size();
}
