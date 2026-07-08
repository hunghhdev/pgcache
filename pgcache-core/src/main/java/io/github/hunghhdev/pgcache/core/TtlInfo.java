package io.github.hunghhdev.pgcache.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * TTL introspection result that distinguishes the three states Redis TTL/PTTL
 * distinguish: key missing (or expired), key present without TTL, and key
 * present with a remaining time to live.
 *
 * @since 1.9.0
 */
public final class TtlInfo {

    /** The lifecycle state of a key with respect to expiration. */
    public enum State {
        /** No live entry for the key (absent or already expired). */
        MISSING,
        /** Live entry without a TTL — stays until evicted. */
        PERMANENT,
        /** Live entry with a TTL — see {@link #getRemaining()}. */
        EXPIRING
    }

    private static final TtlInfo MISSING = new TtlInfo(State.MISSING, null);
    private static final TtlInfo PERMANENT = new TtlInfo(State.PERMANENT, null);

    private final State state;
    private final Duration remaining;

    private TtlInfo(State state, Duration remaining) {
        this.state = state;
        this.remaining = remaining;
    }

    public static TtlInfo missing() {
        return MISSING;
    }

    public static TtlInfo permanent() {
        return PERMANENT;
    }

    public static TtlInfo expiring(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("remaining must be positive, got: " + remaining);
        }
        return new TtlInfo(State.EXPIRING, remaining);
    }

    public State getState() {
        return state;
    }

    /** Remaining time to live; present only when {@link #getState()} is {@link State#EXPIRING}. */
    public Optional<Duration> getRemaining() {
        return Optional.ofNullable(remaining);
    }

    public boolean isMissing() {
        return state == State.MISSING;
    }

    public boolean isPermanent() {
        return state == State.PERMANENT;
    }

    public boolean isExpiring() {
        return state == State.EXPIRING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TtlInfo)) {
            return false;
        }
        TtlInfo other = (TtlInfo) o;
        return state == other.state && Objects.equals(remaining, other.remaining);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, remaining);
    }

    @Override
    public String toString() {
        return state == State.EXPIRING ? "TtlInfo{EXPIRING, remaining=" + remaining + "}" : "TtlInfo{" + state + "}";
    }
}
