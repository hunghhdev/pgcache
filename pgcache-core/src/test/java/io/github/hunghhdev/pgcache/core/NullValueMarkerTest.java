package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NullValueMarkerTest {

    @Test
    void markerValueConstant() {
        assertEquals("NULL_MARKER", NullValueMarker.MARKER_VALUE);
    }

    @Test
    void instanceMarkerSerializesToConstant() {
        assertEquals(NullValueMarker.MARKER_VALUE, NullValueMarker.getInstance().getMarker());
    }

    @Test
    void isMarkerForInstance() {
        assertTrue(NullValueMarker.isMarker(NullValueMarker.getInstance()));
    }

    @Test
    void isMarkerForEquivalentMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("marker", "NULL_MARKER");
        assertTrue(NullValueMarker.isMarker(map));
    }

    @Test
    void isNotMarkerForNull() {
        assertFalse(NullValueMarker.isMarker(null));
    }

    @Test
    void isNotMarkerForRegularMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "alice");
        assertFalse(NullValueMarker.isMarker(map));
    }

    @Test
    void isNotMarkerForMapWithExtraKeys() {
        Map<String, Object> map = new HashMap<>();
        map.put("marker", "NULL_MARKER");
        map.put("extra", 1);
        assertFalse(NullValueMarker.isMarker(map));
    }

    @Test
    void isNotMarkerForMapWithDifferentValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("marker", "OTHER");
        assertFalse(NullValueMarker.isMarker(map));
    }

    @Test
    void isNotMarkerForString() {
        assertFalse(NullValueMarker.isMarker("NULL_MARKER"));
    }
}
