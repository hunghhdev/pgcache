package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TTLPolicy enum
 * 
 * @author Hung Hoang
 * @since 1.1.0
 */
class TTLPolicyTest {

    @Test
    void testEnumValues() {
        assertEquals(2, TTLPolicy.values().length);
        assertEquals(TTLPolicy.ABSOLUTE, TTLPolicy.valueOf("ABSOLUTE"));
        assertEquals(TTLPolicy.SLIDING, TTLPolicy.valueOf("SLIDING"));
    }

    @Test
    void testGetDefault() {
        assertEquals(TTLPolicy.ABSOLUTE, TTLPolicy.getDefault());
    }

    @Test
    void testEnumOrder() {
        TTLPolicy[] values = TTLPolicy.values();
        assertEquals(TTLPolicy.ABSOLUTE, values[0]);
        assertEquals(TTLPolicy.SLIDING, values[1]);
    }
    
    @Test
    void testEnumToString() {
        assertEquals("ABSOLUTE", TTLPolicy.ABSOLUTE.toString());
        assertEquals("SLIDING", TTLPolicy.SLIDING.toString());
    }
}
