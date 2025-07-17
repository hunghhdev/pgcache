package io.github.hunghhdev.pgcache.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Main client for interacting with the cache using a PgCacheStore backend.
 */
public interface PgCacheClient {
    <T> Optional<T> get(String key, Class<T> clazz);
    
    /**
     * Gets a value from the cache with option to refresh TTL for sliding TTL entries.
     * @param key the cache key
     * @param clazz the class type of the cached value
     * @param refreshTTL whether to refresh the TTL on access (only applies to SLIDING TTL policy)
     * @return optional containing the cached value if found
     * @since 1.1.0
     */
    <T> Optional<T> get(String key, Class<T> clazz, boolean refreshTTL);
    
    /**
     * Puts a value in the cache with specified TTL.
     * @param key the cache key
     * @param value the value to cache
     * @param ttl time to live duration
     */
    <T> void put(String key, T value, Duration ttl);
    
    /**
     * Puts a value in the cache with specified TTL and TTL policy.
     * @param key the cache key
     * @param value the value to cache
     * @param ttl time to live duration
     * @param policy the TTL policy (ABSOLUTE or SLIDING)
     * @since 1.1.0
     */
    <T> void put(String key, T value, Duration ttl, TTLPolicy policy);
    
    /**
     * Puts a value in the cache without TTL (permanent until manually evicted).
     * @param key the cache key
     * @param value the value to cache
     */
    <T> void put(String key, T value);
    
    /**
     * Gets the remaining TTL for a cache entry.
     * @param key the cache key
     * @return optional containing the remaining TTL, empty if key doesn't exist or has no TTL
     * @since 1.1.0
     */
    Optional<Duration> getRemainingTTL(String key);
    
    /**
     * Gets the TTL policy for a cache entry.
     * @param key the cache key
     * @return optional containing the TTL policy, empty if key doesn't exist
     * @since 1.1.0
     */
    Optional<TTLPolicy> getTTLPolicy(String key);
    
    /**
     * Refreshes the TTL for an existing cache entry.
     * @param key the cache key
     * @param newTtl the new TTL duration
     * @return true if the TTL was refreshed successfully, false if key doesn't exist
     * @since 1.1.0
     */
    boolean refreshTTL(String key, Duration newTtl);
    
    void evict(String key);
    void clear();
    int size();
}
