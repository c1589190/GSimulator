package com.gsim.agentsmanager.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** CoreConfig 主链视图（from 工厂）默认值 / 覆盖 / 回退行为测试。 */
class CoreConfigTest {

    private static final Map<String, String> DEFAULTS = Map.of(CoreConfig.QUERY_STAGING_THRESHOLD, "3000");

    @Test
    void fromWithDefaultsOnlyReturnsDefaultThresholds() {
        CoreConfig config = CoreConfig.from(Map.of(), DEFAULTS);
        assertEquals(3000, config.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, -1));
    }

    @Test
    void mergedValuesOverrideDefaults() {
        CoreConfig config = CoreConfig.from(Map.of(CoreConfig.QUERY_STAGING_THRESHOLD, "100"), DEFAULTS);
        assertEquals(100, config.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, -1));
    }

    @Test
    void missingKeyFallsBackToCallerDefault() {
        CoreConfig config = CoreConfig.from(Map.of(), Map.of());
        assertEquals(3000, config.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, 3000));
    }

    @Test
    void invalidValueFallsBackToDefaults() {
        CoreConfig config = CoreConfig.from(Map.of(CoreConfig.QUERY_STAGING_THRESHOLD, "abc"), DEFAULTS);
        assertEquals(3000, config.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, -1));
    }

    @Test
    void getReturnsNullForUnknownKey() {
        CoreConfig config = CoreConfig.from(Map.of(), DEFAULTS);
        assertNull(config.get("no.such.key"));
    }

    @Test
    void loadShimReturnsEmptyViewForTestCompat() {
        CoreConfig config = CoreConfig.load();
        assertNull(config.get(CoreConfig.QUERY_STAGING_THRESHOLD));
        assertEquals(3000, config.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, 3000));
    }
}
