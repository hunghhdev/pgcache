package io.github.hunghhdev.pgcache.core;

import java.time.Duration;
import java.time.Instant;

final class TtlHelper {

    private TtlHelper() {
    }

    static boolean isExpired(Instant updatedAt, Instant lastAccessed, Integer ttlSeconds, TTLPolicy policy) {
        if (ttlSeconds == null) {
            return false;
        }
        Instant referenceTime = (policy == TTLPolicy.SLIDING) ? lastAccessed : updatedAt;
        return Instant.now().isAfter(referenceTime.plusSeconds(ttlSeconds));
    }

    static boolean isExpired(Instant updatedAt, int ttlSeconds) {
        return Instant.now().isAfter(updatedAt.plusSeconds(ttlSeconds));
    }

    static Duration calculateRemainingTtl(Instant updatedAt, Instant lastAccessed, int ttlSeconds, TTLPolicy policy) {
        Instant expirationTime = (policy == TTLPolicy.SLIDING)
            ? lastAccessed.plusSeconds(ttlSeconds)
            : updatedAt.plusSeconds(ttlSeconds);
        Duration remaining = Duration.between(Instant.now(), expirationTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
