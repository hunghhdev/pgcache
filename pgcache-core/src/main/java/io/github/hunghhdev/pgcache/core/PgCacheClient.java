package io.github.hunghhdev.pgcache.core;

import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

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

    /**
     * Counts non-expired keys matching the given SQL LIKE pattern.
     *
     * <p>Default implementation materializes all matching keys via {@link #getKeys(String)},
     * then returns the size — correct but O(N) memory. Implementations should override with
     * an efficient {@code COUNT} query.</p>
     *
     * <p>Unlike {@link #size()}, this method does not cache results.</p>
     *
     * @param pattern SQL LIKE pattern (use {@code %} for any chars)
     * @return number of non-expired matching keys
     * @since 1.7.0
     */
    default int size(String pattern) {
        return getKeys(pattern).size();
    }

    /**
     * Checks if the cache contains a non-expired entry for the given key.
     * This is more efficient than get() as it doesn't deserialize the value.
     * 
     * @param key the cache key to check
     * @return true if the key exists and is not expired, false otherwise
     * @since 1.6.0
     */
    boolean containsKey(String key);

    // ==================== Generic reads (v1.9.0) ====================

    /**
     * Gets a value deserialized through a Jackson {@link TypeReference}, so
     * parameterized types come back properly typed — e.g.
     * {@code get("users", new TypeReference<List<User>>() {})} returns a
     * {@code List<User>}, not a {@code List<LinkedHashMap>}.
     *
     * @since 1.9.0
     */
    <T> Optional<T> get(String key, TypeReference<T> typeRef);

    /**
     * Variant of {@link #get(String, TypeReference)} with control over TTL refresh
     * for SLIDING entries.
     *
     * @since 1.9.0
     */
    <T> Optional<T> get(String key, TypeReference<T> typeRef, boolean refreshTTL);

    /**
     * Batch variant of {@link #get(String, TypeReference)}. Like
     * {@link #getAll(Collection, Class)}, this does not refresh sliding TTLs.
     *
     * @since 1.9.0
     */
    <T> Map<String, T> getAll(Collection<String> keys, TypeReference<T> typeRef);

    // ==================== Key iteration (v1.9.0) ====================

    /**
     * Lazily iterates non-expired keys matching the SQL LIKE pattern using
     * keyset pagination — constant memory regardless of match count, unlike
     * {@link #getKeys(String)} which materializes everything.
     *
     * <p>Each batch runs as its own query, so the iteration is weakly
     * consistent: keys written or expired mid-scan may or may not appear.
     * Iterators are not thread-safe; obtain one per thread.</p>
     *
     * @param pattern SQL LIKE pattern (use {@code %} for any chars)
     * @param batchSize keys fetched per round-trip, must be positive
     * @since 1.9.0
     */
    Iterable<String> scanKeys(String pattern, int batchSize);

    // ==================== Read-through with single-flight (v1.9.0) ====================

    /**
     * Returns the cached value for the key, or computes, stores and returns it.
     *
     * <p>On a miss the computation is <em>single-flight</em>: a PostgreSQL
     * transaction-scoped advisory lock on the key guarantees that only one
     * caller — across threads <em>and</em> JVMs sharing the database — runs the
     * loader, while concurrent callers block and then read the freshly stored
     * value (cache stampede protection).</p>
     *
     * <p>The loader runs while holding a pooled connection and the advisory
     * lock; keep it reasonably fast. Loader exceptions propagate unwrapped and
     * nothing is cached. If the database is unreachable, the loader is invoked
     * directly and its result returned uncached (reads fail open).</p>
     *
     * <p>A {@code null} loader result is cached only when the store allows null
     * values; otherwise it is returned uncached.</p>
     *
     * @param key the cache key
     * @param clazz the class of the cached value
     * @param ttl time to live for a computed value, or {@code null} for a permanent entry
     * @param loader invoked on a miss to compute the value
     * @return the cached or computed value; may be {@code null}
     * @since 1.9.0
     */
    <T> T getOrCompute(String key, Class<T> clazz, Duration ttl, Supplier<T> loader);

    /**
     * Variant of {@link #getOrCompute(String, Class, Duration, Supplier)} with an
     * explicit TTL policy for the computed value.
     *
     * @since 1.9.0
     */
    <T> T getOrCompute(String key, Class<T> clazz, Duration ttl, TTLPolicy policy, Supplier<T> loader);

    // ==================== Atomic Operations (v1.9.0) ====================

    /**
     * Atomically adds {@code delta} to the numeric value at {@code key} (Redis INCRBY).
     *
     * <p>A missing or expired key is created with the value {@code delta} and the
     * given TTL. A live key is incremented in place and <em>keeps its own TTL</em>
     * (Redis INCR semantics) — the {@code ttl} argument only applies on creation.</p>
     *
     * @param key the cache key
     * @param delta the amount to add (may be negative)
     * @param ttl TTL applied when the counter is created, or {@code null} for permanent
     * @return the counter value after the increment
     * @throws PgCacheException if the existing value is not an integer
     * @since 1.9.0
     */
    long increment(String key, long delta, Duration ttl);

    /**
     * Atomically adds {@code delta} to the numeric value at {@code key};
     * a missing key is created as a permanent entry.
     *
     * @since 1.9.0
     */
    long increment(String key, long delta);

    /**
     * Atomically subtracts {@code delta} from the numeric value at {@code key} (Redis DECRBY).
     *
     * @since 1.9.0
     */
    default long decrement(String key, long delta, Duration ttl) {
        return increment(key, -delta, ttl);
    }

    /**
     * Atomically subtracts {@code delta} from the numeric value at {@code key};
     * a missing key is created as a permanent entry.
     *
     * @since 1.9.0
     */
    default long decrement(String key, long delta) {
        return increment(key, -delta);
    }

    /**
     * Atomically reads and removes the value at {@code key} (Redis GETDEL).
     *
     * @return the removed value, or empty if the key was absent or expired
     * @since 1.9.0
     */
    <T> Optional<T> getAndDelete(String key, Class<T> clazz);

    /**
     * Atomically stores the new value and returns the previous live value (Redis GETSET).
     *
     * @return the value the write replaced, or empty if the key was absent or expired
     * @since 1.9.0
     */
    <T> Optional<Object> getAndPut(String key, T value, Duration ttl, TTLPolicy policy);

    /**
     * Atomically stores a permanent value and returns the previous live value.
     *
     * @since 1.9.0
     */
    <T> Optional<Object> getAndPut(String key, T value);

    /**
     * Removes the TTL from a live entry, making it permanent (Redis PERSIST).
     *
     * @return true if a TTL was removed; false if the key is absent, expired or already permanent
     * @since 1.9.0
     */
    boolean persist(String key);

    /**
     * Sets an absolute expiration deadline on a live entry (Redis EXPIREAT).
     * A deadline in the past deletes the entry. The remaining TTL is computed
     * against the database clock.
     *
     * @return true if the deadline was applied (or the entry deleted); false if the key is absent or expired
     * @since 1.9.0
     */
    boolean expireAt(String key, Instant deadline);

    /**
     * TTL introspection distinguishing missing, permanent and expiring entries —
     * unlike {@link #getRemainingTTL(String)}, which reports empty for both a
     * missing key and a permanent one.
     *
     * @return never null; state MISSING, PERMANENT, or EXPIRING with the remaining duration
     * @since 1.9.0
     */
    TtlInfo getTtlInfo(String key);

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

    /**
     * Gets all non-expired cache keys matching the given SQL LIKE pattern.
     * Warning: Can be expensive on large datasets.
     * @since 1.6.0
     */
    Collection<String> getKeys(String pattern);

    /**
     * Gets all non-expired cache keys.
     * Warning: Can be expensive on large datasets.
     * @since 1.6.0
     */
    Collection<String> getAllKeys();

    // ==================== Async Operations (v1.6.0) ====================

    /**
     * Asynchronously gets a value from the cache.
     * @since 1.6.0
     */
    <T> CompletableFuture<Optional<T>> getAsync(String key, Class<T> clazz);

    /**
     * Asynchronously puts a value in the cache with TTL.
     * @since 1.6.0
     */
    <T> CompletableFuture<Void> putAsync(String key, T value, Duration ttl);

    /**
     * Asynchronously puts a value in the cache with TTL and policy.
     * @since 1.6.0
     */
    <T> CompletableFuture<Void> putAsync(String key, T value, Duration ttl, TTLPolicy policy);

    /**
     * Asynchronously evicts a key from the cache.
     * @since 1.6.0
     */
    CompletableFuture<Void> evictAsync(String key);
}
