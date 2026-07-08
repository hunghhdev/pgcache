package io.github.hunghhdev.pgcache.core;

/**
 * Cache statistics for monitoring cache performance.
 *
 * @since 1.3.0
 */
public class CacheStatistics {
    private final long hitCount;
    private final long missCount;
    private final long putCount;
    private final long evictionCount;
    private final long expiredCount;

    public CacheStatistics(long hitCount, long missCount, long putCount, long evictionCount) {
        this(hitCount, missCount, putCount, evictionCount, 0);
    }

    /**
     * @param expiredCount entries removed because their TTL elapsed, counted
     *     separately from explicit evictions (Redis expired_keys vs evicted_keys)
     * @since 1.9.0
     */
    public CacheStatistics(long hitCount, long missCount, long putCount, long evictionCount, long expiredCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.putCount = putCount;
        this.evictionCount = evictionCount;
        this.expiredCount = expiredCount;
    }

    /**
     * Returns the number of cache hits.
     */
    public long getHitCount() {
        return hitCount;
    }

    /**
     * Returns the number of cache misses.
     */
    public long getMissCount() {
        return missCount;
    }

    /**
     * Returns the number of put operations.
     */
    public long getPutCount() {
        return putCount;
    }

    /**
     * Returns the number of eviction operations.
     */
    public long getEvictionCount() {
        return evictionCount;
    }

    /**
     * Returns the number of entries removed because their TTL elapsed.
     *
     * @since 1.9.0
     */
    public long getExpiredCount() {
        return expiredCount;
    }

    /**
     * Returns the total number of requests (hits + misses).
     */
    public long getRequestCount() {
        return hitCount + missCount;
    }

    /**
     * Returns the hit rate as a value between 0.0 and 1.0.
     * Returns 0.0 if there are no requests.
     */
    public double getHitRate() {
        long total = getRequestCount();
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * Returns the miss rate as a value between 0.0 and 1.0.
     * Returns 0.0 if there are no requests.
     */
    public double getMissRate() {
        long total = getRequestCount();
        return total == 0 ? 0.0 : (double) missCount / total;
    }

    @Override
    public String toString() {
        return String.format(
            "CacheStatistics{hits=%d, misses=%d, hitRate=%.2f%%, puts=%d, evictions=%d, expired=%d}",
            hitCount, missCount, getHitRate() * 100, putCount, evictionCount, expiredCount
        );
    }
}
