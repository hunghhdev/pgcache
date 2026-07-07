package io.github.hunghhdev.pgcache.spring;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conditions of {@link PgCacheAutoConfiguration}:
 * it must only activate when a DataSource bean exists and no other
 * CacheManager has been defined by the user.
 */
@Testcontainers
class PgCacheAutoConfigurationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PgCacheAutoConfiguration.class));

    private static DataSource pgDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    @Test
    void backsOffWhenNoDataSourceBeanIsPresent() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure(),
                    "auto-config must back off, not fail, when no DataSource bean exists");
            assertFalse(context.containsBean("pgCacheManager"));
        });
    }

    @Test
    void backsOffWhenUserDefinedCacheManagerExists() {
        contextRunner
                .withBean(DataSource.class, PgCacheAutoConfigurationTest::pgDataSource)
                .withBean("userCacheManager", CacheManager.class, ConcurrentMapCacheManager::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("pgCacheManager"),
                            "auto-config must back off when the user already defined a CacheManager");
                    assertEquals(1, context.getBeanNamesForType(CacheManager.class).length,
                            "there must be exactly one CacheManager bean");
                });
    }

    @Test
    void createsPgCacheManagerWhenDataSourcePresentAndNoOtherCacheManager() {
        contextRunner
                .withBean(DataSource.class, PgCacheAutoConfigurationTest::pgDataSource)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pgCacheManager"));
                    assertNotNull(context.getBean(PgCacheManager.class));
                });
    }

    @Test
    void registersHealthIndicatorWhenActuatorOnClasspath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PgCacheAutoConfiguration.class, PgCacheHealthAutoConfiguration.class))
                .withBean(DataSource.class, PgCacheAutoConfigurationTest::pgDataSource)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pgCacheHealthIndicator"),
                            "health indicator must be auto-registered when actuator is on the classpath");
                });
    }

    @Test
    void healthIndicatorBacksOffWhenPgCacheManagerAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PgCacheAutoConfiguration.class, PgCacheHealthAutoConfiguration.class))
                .withBean(DataSource.class, PgCacheAutoConfigurationTest::pgDataSource)
                .withPropertyValues("pgcache.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("pgCacheHealthIndicator"));
                });
    }

    @Test
    void backsOffWhenDisabledViaProperty() {
        contextRunner
                .withBean(DataSource.class, PgCacheAutoConfigurationTest::pgDataSource)
                .withPropertyValues("pgcache.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("pgCacheManager"));
                });
    }
}
