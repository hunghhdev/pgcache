package io.github.hunghhdev.pgcache.spring;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Annotation to enable PgCache in Spring applications.
 * This annotation enables Spring's caching support and imports PgCache configuration.
 * 
 * Usage:
 * <pre>
 * {@code
 * @SpringBootApplication
 * @EnablePgCache
 * public class Application {
 *     public static void main(String[] args) {
 *         SpringApplication.run(Application.class, args);
 *     }
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnableCaching
@Import(PgCacheConfiguration.class)
public @interface EnablePgCache {
}
