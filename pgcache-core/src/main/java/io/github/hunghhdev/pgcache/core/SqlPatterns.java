package io.github.hunghhdev.pgcache.core;

import java.util.Objects;

/**
 * Utilities for working with SQL LIKE patterns.
 *
 * @since 1.7.0
 */
public final class SqlPatterns {

    private SqlPatterns() {}

    /**
     * Escapes the SQL LIKE meta-characters ({@code \}, {@code _}, {@code %}) in the input so the
     * result matches the input literally when used with {@code LIKE ? ESCAPE '\\'}.
     *
     * <p>Order matters: backslash MUST be escaped first so we don't double-escape the backslashes
     * we add for {@code _} and {@code %}.</p>
     *
     * @param input the literal string to escape (must not be null)
     * @return the escaped pattern fragment, safe to concatenate with wildcards
     */
    public static String escapeLikePattern(String input) {
        Objects.requireNonNull(input, "input");
        return input
            .replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("%", "\\%");
    }
}
