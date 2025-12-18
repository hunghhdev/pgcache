package io.github.hunghhdev.pgcache.core;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
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

    /**
     * Atomically puts a value in the cache only if the key is not already present.
     * This operation is atomic at the database level using PostgreSQL's ON CONFLICT.
     * @param key the cache key
     * @param value the value to cache
     * @param ttl time to live duration
     * @param policy the TTL policy (ABSOLUTE or SLIDING)
     * @return Optional containing the existing value if key was present, empty if value was inserted
     * @since 1.2.2
     */
    <T> Optional<Object> putIfAbsent(String key, T value, Duration ttl, TTLPolicy policy);

    /**
     * Atomically puts a permanent value in the cache only if the key is not already present.
     * @param key the cache key
     * @param value the value to cache
     * @return Optional containing the existing value if key was present, empty if value was inserted
     * @since 1.2.2
     */
    <T> Optional<Object> putIfAbsent(String key, T value);

    void evict(String key);
    void clear();
    int size();

    // ==================== Batch Operations (v1.3.0) ====================

    /**
     * Gets multiple values from the cache in a single operation.
     * <p>Note: This method does NOT refresh TTL for sliding TTL entries (for performance).
     * Use individual {@link #get(String, Class)} calls if TTL refresh is needed.</p>
     * @param keys the cache keys to retrieve
     * @param clazz the class type of the cached values
     * @return map of key to value for keys that exist and are not expired
     * @since 1.3.0
     */
    <T> Map<String, T> getAll(Collection<String> keys, Class<T> clazz);

    /**
     * Puts multiple values in the cache with specified TTL.
     * @param entries map of key to value
     * @param ttl time to live duration
     * @since 1.3.0
     */
    <T> void putAll(Map<String, T> entries, Duration ttl);

    /**
     * Puts multiple values in the cache with specified TTL and policy.
     * @param entries map of key to value
     * @param ttl time to live duration
     * @param policy the TTL policy (ABSOLUTE or SLIDING)
     * @since 1.3.0
     */
    <T> void putAll(Map<String, T> entries, Duration ttl, TTLPolicy policy);

    /**
     * Puts multiple permanent values in the cache (no TTL).
     * @param entries map of key to value
     * @since 1.3.0
     */
    <T> void putAll(Map<String, T> entries);

    /**
     * Evicts multiple keys from the cache.
     * @param keys the keys to evict
     * @return number of keys actually evicted
     * @since 1.3.0
     */
    int evictAll(Collection<String> keys);

    // ==================== Cache Statistics (v1.3.0) ====================

    /**
     * Returns cache statistics including hit/miss counts and rates.
     * @return current cache statistics
     * @since 1.3.0
     */
    CacheStatistics getStatistics();

    /**
     * Resets all cache statistics counters to zero.
     * @since 1.3.0
     */
    void resetStatistics();

    // ==================== Pattern Operations (v1.3.0) ====================

    /**
     * Evicts all cache entries matching the given pattern.
     * Uses SQL LIKE pattern matching (% for any characters, _ for single character).
     *
     * <p>Example patterns:</p>
     * <ul>
     *   <li>"user:%" - matches all keys starting with "user:"</li>
     *   <li>"%:session" - matches all keys ending with ":session"</li>
     *   <li>"cache:%" - matches all keys in the "cache:" namespace</li>
     * </ul>
     *
     * @param pattern SQL LIKE pattern to match keys
     * @return number of entries evicted
     * @since 1.3.0
     */
    int evictByPattern(String pattern);
}
