package com.gsim.map.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapConfig} defaults and construction.
 */
public class MapConfigTest {

    @Test
    void defaultsMatchLegacyConstants() {
        MapConfig c = MapConfig.defaults();
        assertEquals(80, c.defaultMapRadius());
        assertEquals(32, c.cacheMaxEntries());
        assertEquals(5000, c.contourCacheMax());
        assertEquals(200, c.lassoMaxRadius());
        assertEquals(30000, c.lassoMaxFill());
        assertEquals(100, c.minRegionSize());
        assertEquals(200, c.maxChainDepth());
    }

    @Test
    void noArgConstructorEqualsDefaults() {
        assertEquals(MapConfig.defaults(), new MapConfig());
        assertEquals(MapConfig.DEFAULT, new MapConfig());
    }

    @Test
    void fullConstructorRoundTrip() {
        MapConfig c = new MapConfig(10, 4, 100, 20, 500, 50, 10);
        assertEquals(10, c.defaultMapRadius());
        assertEquals(4, c.cacheMaxEntries());
        assertEquals(100, c.contourCacheMax());
        assertEquals(20, c.lassoMaxRadius());
        assertEquals(500, c.lassoMaxFill());
        assertEquals(50, c.minRegionSize());
        assertEquals(10, c.maxChainDepth());
    }
}
