package io.github.hunghhdev.pgcache.core;

/**
 * Listener for cache events. Implementations receive callbacks when cache entries
 * are created, evicted, or cleared.
 * 
 * @since 1.6.0
 */
public interface CacheEventListener {
    
    default void onPut(String key, Object value) {}
    
    default void onEvict(String key) {}
    
    default void onClear() {}
}
