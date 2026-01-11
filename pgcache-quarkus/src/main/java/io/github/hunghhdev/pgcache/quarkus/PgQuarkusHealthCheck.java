package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check data provider for PgCache in Quarkus.
 * Users should create their own HealthCheck bean that delegates to this class.
 * 
 * Example usage:
 * <pre>
 * &#64;Liveness
 * &#64;ApplicationScoped
 * public class MyPgCacheHealthCheck implements HealthCheck {
 *     &#64;Inject PgQuarkusHealthCheck healthCheck;
 *     
 *     &#64;Override
 *     public HealthCheckResponse call() {
 *         return healthCheck.check();
 *     }
 * }
 * </pre>
 * 
 * @since 1.6.0
 */
public class PgQuarkusHealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(PgQuarkusHealthCheck.class);

    private final PgQuarkusCacheManager cacheManager;

    public PgQuarkusHealthCheck(PgQuarkusCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public HealthResult check() {
        try {
            PgCacheStore store = cacheManager.getCacheStore();
            
            boolean tableExists = store.tableExists();
            if (!tableExists) {
                return HealthResult.down("Cache table does not exist");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            int cacheCount = cacheManager.getCacheNames().size();
            data.put("cache.count", cacheCount);
            data.put("cache.names", cacheManager.getCacheNames());

            CacheStatistics stats = store.getStatistics();
            data.put("cache.size", store.size());
            data.put("stats.hits", stats.getHitCount());
            data.put("stats.misses", stats.getMissCount());
            data.put("stats.hitRate", String.format("%.2f%%", stats.getHitRate() * 100));
            data.put("stats.puts", stats.getPutCount());
            data.put("stats.evictions", stats.getEvictionCount());

            return HealthResult.up(data);

        } catch (Exception e) {
            logger.error("PgCache health check failed: {}", e.getMessage());
            return HealthResult.down(e.getMessage());
        }
    }

    public static class HealthResult {
        private final boolean up;
        private final Map<String, Object> data;
        private final String error;

        private HealthResult(boolean up, Map<String, Object> data, String error) {
            this.up = up;
            this.data = data;
            this.error = error;
        }

        public static HealthResult up(Map<String, Object> data) {
            return new HealthResult(true, data, null);
        }

        public static HealthResult down(String error) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("error", error);
            return new HealthResult(false, data, error);
        }

        public boolean isUp() {
            return up;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public String getError() {
            return error;
        }
    }
}
