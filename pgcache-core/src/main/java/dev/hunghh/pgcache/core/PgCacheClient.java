package dev.hunghh.pgcache.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Main client for interacting with the cache using a PgCacheStore backend.
 */
public interface PgCacheClient {
    <T> Optional<T> get(String key, Class<T> clazz);
    
    /**
     * Puts a value in the cache with specified TTL.
     * @param key the cache key
     * @param value the value to cache
     * @param ttl time to live duration
     */
    <T> void put(String key, T value, Duration ttl);
    
    /**
     * Puts a value in the cache without TTL (permanent until manually evicted).
     * @param key the cache key
     * @param value the value to cache
     */
    <T> void put(String key, T value);
    
    void evict(String key);
    void clear();
    int size();
}
