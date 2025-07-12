package io.github.hunghhdev.pgcache.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Auto-configuration for PgCache Spring integration.
 * Automatically configures PgCacheManager when DataSource is available.
 */
@Configuration
@ConditionalOnClass({DataSource.class, CacheManager.class})
@ConditionalOnProperty(prefix = "pgcache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PgCacheProperties.class)
public class PgCacheAutoConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCacheAutoConfiguration.class);
    
    /**
     * Create PgCacheManager bean if no other CacheManager is defined.
     */
    @Bean
    @ConditionalOnMissingBean(name = "pgCacheManager")
    public PgCacheManager pgCacheManager(DataSource dataSource, PgCacheProperties properties) {
        logger.info("Auto-configuring PgCacheManager with default TTL: {}, table: {}, background cleanup: {}", 
                   properties.getDefaultTtl(), properties.getTableName(), properties.getBackgroundCleanup().isEnabled());
        
        PgCacheManager cacheManager = new PgCacheManager(dataSource, properties.toDefaultConfiguration());
        
        // Configure individual caches
        properties.getCaches().forEach((cacheName, cacheConfig) -> {
            logger.info("Configuring cache '{}' with TTL: {}, table: {}", 
                       cacheName, cacheConfig.getTtl(), cacheConfig.getTableName());
            cacheManager.setCacheConfiguration(cacheName, cacheConfig.toConfiguration(properties));
        });
        
        return cacheManager;
    }
    
    /**
     * Create primary CacheManager bean if no other CacheManager exists.
     */
    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager cacheManager(PgCacheManager pgCacheManager) {
        logger.info("Setting PgCacheManager as primary CacheManager");
        return pgCacheManager;
    }
}
