package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Collections;

/**
 * Micrometer metrics binder for PgCache.
 * Exposes cache statistics as Micrometer metrics.
 *
 * <p>Metrics exposed:</p>
 * <ul>
 *   <li>{@code pgcache.gets} - Counter for cache gets (tagged by result: hit/miss)</li>
 *   <li>{@code pgcache.puts} - Counter for cache puts</li>
 *   <li>{@code pgcache.evictions} - Counter for cache evictions</li>
 *   <li>{@code pgcache.size} - Gauge for current cache size</li>
 *   <li>{@code pgcache.hit.rate} - Gauge for cache hit rate (0.0 - 1.0)</li>
 * </ul>
 *
 * @since 1.4.0
 */
public class PgCacheMetrics implements MeterBinder {

    private final PgCache cache;
    private final String cacheName;
    private final Iterable<Tag> tags;

    /**
     * Create metrics for a PgCache instance.
     *
     * @param cache the cache to monitor
     */
    public PgCacheMetrics(PgCache cache) {
        this(cache, Collections.emptyList());
    }

    /**
     * Create metrics for a PgCache instance with additional tags.
     *
     * @param cache the cache to monitor
     * @param tags additional tags to apply to all metrics
     */
    public PgCacheMetrics(PgCache cache, Iterable<Tag> tags) {
        this.cache = cache;
        this.cacheName = cache.getName();
        this.tags = tags;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Cache hits counter
        FunctionCounter.builder("pgcache.gets", cache, c -> c.getStatistics().getHitCount())
                .tags(tags)
                .tag("cache", cacheName)
                .tag("result", "hit")
                .description("The number of cache hits")
                .register(registry);

        // Cache misses counter
        FunctionCounter.builder("pgcache.gets", cache, c -> c.getStatistics().getMissCount())
                .tags(tags)
                .tag("cache", cacheName)
                .tag("result", "miss")
                .description("The number of cache misses")
                .register(registry);

        // Cache puts counter
        FunctionCounter.builder("pgcache.puts", cache, c -> c.getStatistics().getPutCount())
                .tags(tags)
                .tag("cache", cacheName)
                .description("The number of cache puts")
                .register(registry);

        // Cache evictions counter
        FunctionCounter.builder("pgcache.evictions", cache, c -> c.getStatistics().getEvictionCount())
                .tags(tags)
                .tag("cache", cacheName)
                .description("The number of cache evictions")
                .register(registry);

        // Cache size gauge
        Gauge.builder("pgcache.size", cache, PgCache::size)
                .tags(tags)
                .tag("cache", cacheName)
                .description("The current number of entries in the cache")
                .register(registry);

        // Hit rate gauge
        Gauge.builder("pgcache.hit.rate", cache, c -> c.getStatistics().getHitRate())
                .tags(tags)
                .tag("cache", cacheName)
                .description("The cache hit rate (0.0 - 1.0)")
                .register(registry);
    }

    /**
     * Convenience method to monitor a cache and bind metrics to a registry.
     *
     * @param cache the cache to monitor
     * @param registry the meter registry
     * @return the cache (for chaining)
     */
    public static PgCache monitor(PgCache cache, MeterRegistry registry) {
        return monitor(cache, registry, Collections.emptyList());
    }

    /**
     * Convenience method to monitor a cache and bind metrics to a registry with tags.
     *
     * @param cache the cache to monitor
     * @param registry the meter registry
     * @param tags additional tags
     * @return the cache (for chaining)
     */
    public static PgCache monitor(PgCache cache, MeterRegistry registry, Iterable<Tag> tags) {
        new PgCacheMetrics(cache, tags).bindTo(registry);
        return cache;
    }
}
