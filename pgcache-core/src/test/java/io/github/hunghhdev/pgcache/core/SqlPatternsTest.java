package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlPatternsTest {

    @Test
    void escapesPlainStringUnchanged() {
        assertEquals("foo", SqlPatterns.escapeLikePattern("foo"));
    }

    @Test
    void escapesUnderscore() {
        assertEquals("user\\_data", SqlPatterns.escapeLikePattern("user_data"));
    }

    @Test
    void escapesPercent() {
        assertEquals("100\\%off", SqlPatterns.escapeLikePattern("100%off"));
    }

    @Test
    void escapesBackslash() {
        assertEquals("foo\\\\bar", SqlPatterns.escapeLikePattern("foo\\bar"));
    }

    @Test
    void escapesAllMetaChars() {
        assertEquals("a\\\\b\\_c\\%d", SqlPatterns.escapeLikePattern("a\\b_c%d"));
    }

    @Test
    void emptyStringReturnsEmpty() {
        assertEquals("", SqlPatterns.escapeLikePattern(""));
    }

    @Test
    void nullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> SqlPatterns.escapeLikePattern(null));
    }
}
