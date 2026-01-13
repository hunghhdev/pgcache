package io.github.hunghhdev.pgcache.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

final class CacheEventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(CacheEventDispatcher.class);

    private final List<CacheEventListener> listeners;

    CacheEventDispatcher(List<CacheEventListener> listeners) {
        this.listeners = listeners != null ? new ArrayList<>(listeners) : new ArrayList<>();
    }

    void fireOnPut(String key, Object value) {
        for (CacheEventListener listener : listeners) {
            try {
                listener.onPut(key, value);
            } catch (Exception e) {
                logger.warn("CacheEventListener.onPut failed for key '{}': {}", key, e.getMessage());
            }
        }
    }

    void fireOnEvict(String key) {
        for (CacheEventListener listener : listeners) {
            try {
                listener.onEvict(key);
            } catch (Exception e) {
                logger.warn("CacheEventListener.onEvict failed for key '{}': {}", key, e.getMessage());
            }
        }
    }

    void fireOnClear() {
        for (CacheEventListener listener : listeners) {
            try {
                listener.onClear();
            } catch (Exception e) {
                logger.warn("CacheEventListener.onClear failed: {}", e.getMessage());
            }
        }
    }
}
