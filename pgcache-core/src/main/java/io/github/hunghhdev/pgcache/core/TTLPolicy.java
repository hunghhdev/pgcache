package io.github.hunghhdev.pgcache.core;

/**
 * Enum defining different TTL (Time To Live) policies for cache entries.
 * 
 * @author Hung Hoang
 * @since 1.1.0
 */
public enum TTLPolicy {
    
    /**
     * Absolute TTL - Entry expires after a fixed time from creation.
     * This is the default behavior in v1.0.0.
     * 
     * Example: Entry created at 10:00 with 1 hour TTL will expire at 11:00
     * regardless of access patterns.
     */
    ABSOLUTE,
    
    /**
     * Sliding TTL - Entry expiration time resets on each access.
     * Popular entries stay cached longer, inactive entries expire naturally.
     * 
     * Example: Entry created at 10:00 with 1 hour sliding TTL.
     * If accessed at 10:30, expiration moves to 11:30.
     * If accessed again at 11:00, expiration moves to 12:00.
     */
    SLIDING;
    
    /**
     * Returns the default TTL policy for backward compatibility.
     *
     * @return ABSOLUTE policy (maintains v1.0.0 behavior)
     */
    public static TTLPolicy getDefault() {
        return ABSOLUTE;
    }

    /**
     * Parses a string into a {@link TTLPolicy}. Case-insensitive.
     *
     * @param value the string to parse (may be null)
     * @return the parsed policy, or empty if value is null/empty/invalid
     * @since 1.7.0
     */
    public static java.util.Optional<TTLPolicy> parse(String value) {
        if (value == null || value.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(value.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Parses a string into a {@link TTLPolicy}, defaulting to {@link #ABSOLUTE} on null/invalid input.
     *
     * @param value the string to parse (may be null)
     * @return the parsed policy, or {@link #ABSOLUTE} as fallback
     * @since 1.7.0
     */
    public static TTLPolicy parseOrDefault(String value) {
        return parse(value).orElse(ABSOLUTE);
    }
}
