package io.github.hunghhdev.pgcache.core;

/**
 * Custom exception for pgcache errors.
 */
public class PgCacheException extends RuntimeException {
    public PgCacheException(String message) {
        super(message);
    }

    public PgCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}

