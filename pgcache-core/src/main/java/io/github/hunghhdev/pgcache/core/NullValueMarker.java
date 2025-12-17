package io.github.hunghhdev.pgcache.core;

import java.io.Serializable;

/**
 * Marker class to represent null values in the cache.
 * This allows caching of null values when allowNullValues is enabled,
 * while maintaining type safety and proper serialization.
 * 
 * &lt;p&gt;This class uses the singleton pattern to ensure all null markers
 * are the same instance, even across serialization boundaries.&lt;/p&gt;
 * 
 * @since 1.3.0
 */
public final class NullValueMarker implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private static final NullValueMarker INSTANCE = new NullValueMarker();
    
    // Dummy property so Jackson can serialize/deserialize this class
    private final String marker = "NULL_MARKER";
    
    /**
     * Private constructor to prevent instantiation.
     */
    private NullValueMarker() {
    }
    
    /**
     * Gets the singleton instance of NullValueMarker.
     * 
     * @return the singleton instance
     */
    public static NullValueMarker getInstance() {
        return INSTANCE;
    }
    
    /**
     * Gets the marker identifier (for Jackson serialization).
     * 
     * @return the marker identifier
     */
    public String getMarker() {
        return marker;
    }
    
    /**
     * Ensures singleton pattern is preserved during deserialization.
     * 
     * @return the singleton instance
     */
    private Object readResolve() {
        return INSTANCE;
    }
    
    @Override
    public String toString() {
        return "NullValueMarker";
    }
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof NullValueMarker;
    }
    
    @Override
    public int hashCode() {
        return NullValueMarker.class.hashCode();
    }
}
