package dev.hunghh.pgcache.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * Configuration class for PgCache Spring integration.
 * This class is imported by {@link EnablePgCache} annotation.
 */
@Configuration
@EnableConfigurationProperties(PgCacheProperties.class)
@Import(PgCacheAutoConfiguration.class)
public class PgCacheConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCacheConfiguration.class);
    
    public PgCacheConfiguration() {
        logger.info("PgCache Spring integration enabled");
    }
}
