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

    public CacheStatistics(long hitCount, long missCount, long putCount, long evictionCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.putCount = putCount;
        this.evictionCount = evictionCount;
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
            "CacheStatistics{hits=%d, misses=%d, hitRate=%.2f%%, puts=%d, evictions=%d}",
            hitCount, missCount, getHitRate() * 100, putCount, evictionCount
        );
    }
}
