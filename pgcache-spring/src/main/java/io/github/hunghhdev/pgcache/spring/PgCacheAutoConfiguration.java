package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Auto-configuration for PgCache Spring integration.
 * Automatically configures PgCacheManager when a DataSource bean is available
 * and the user has not defined a CacheManager of their own.
 */
@Configuration
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "pgcache", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(PgCacheProperties.class)
public class PgCacheAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PgCacheAutoConfiguration.class);

    /**
     * Create PgCacheManager bean if a DataSource exists and no other CacheManager is defined.
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(CacheManager.class)
    public PgCacheManager pgCacheManager(DataSource dataSource, PgCacheProperties properties,
                                         ObjectProvider<CacheEventListener> listenersProvider) {
        logger.info("Auto-configuring PgCacheManager with default TTL: {}, table: {}, background cleanup: {}", 
                   properties.getDefaultTtl(), properties.getTableName(), properties.getBackgroundCleanup().isEnabled());
        
        List<CacheEventListener> listeners = listenersProvider.stream().collect(Collectors.toList());
        if (!listeners.isEmpty()) {
            logger.info("Registering {} cache event listeners", listeners.size());
        }

        PgCacheManager cacheManager = new PgCacheManager(dataSource, properties.toDefaultConfiguration(), listeners);
        
        // Configure individual caches
        properties.getCaches().forEach((cacheName, cacheConfig) -> {
            logger.info("Configuring cache '{}' with TTL: {}, table: {}", 
                       cacheName, cacheConfig.getTtl(), cacheConfig.getTableName());
            cacheManager.setCacheConfiguration(cacheName, cacheConfig.toConfiguration(properties));
        });
        
        return cacheManager;
    }
}
