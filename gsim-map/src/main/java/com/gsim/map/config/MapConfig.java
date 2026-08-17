package com.gsim.map.config;

/**
 * Configurable limits for the gsim-map module.
 *
 * <p>Replaces the previously hardcoded constants across gsim-map services
 * (TerrainCanvas, MapService, ContourQueryEngine, LassoProcessor,
 * CompressionService, MapResolver). Defaults are identical to the legacy
 * constants — behavior is unchanged unless explicitly overridden.
 *
 * <p>Built by gsim-app (Main) from the ConfigLoader main chain keys
 * ({@code map.radius.default}, {@code map.cache.max_entries},
 * {@code map.contour.cache.max}, {@code map.lasso.max_radius},
 * {@code map.lasso.max_fill}, {@code map.compression.min_region_size},
 * {@code map.resolver.max_chain_depth}). gsim-map itself has no dependency on
 * gsim-app or AppConfig.
 *
 * @param defaultMapRadius default hex map radius (was TerrainCanvas.DEFAULT_MAP_RADIUS)
 * @param cacheMaxEntries MapService MapData LRU cache size (was MapService.MAX_CACHE_SIZE)
 * @param contourCacheMax ContourQueryEngine LRU cache size (was ContourQueryEngine.MAX_CACHE)
 * @param lassoMaxRadius lasso flood-fill coordinate bound (was LassoProcessor.MAX_RADIUS)
 * @param lassoMaxFill lasso flood-fill hex cap (was LassoProcessor.MAX_FILL)
 * @param minRegionSize compression minimum region size (was CompressionService.DEFAULT_MIN_REGION_SIZE)
 * @param maxChainDepth MapResolver parent-chain walk limit (was MapResolver.MAX_CHAIN_DEPTH)
 */
public record MapConfig(
        int defaultMapRadius,
        int cacheMaxEntries,
        int contourCacheMax,
        int lassoMaxRadius,
        int lassoMaxFill,
        int minRegionSize,
        int maxChainDepth) {

    /** Defaults, identical to the legacy hardcoded constants. */
    public static final MapConfig DEFAULT = new MapConfig(80, 32, 5000, 200, 30000, 100, 200);

    /** Create a config with the default limits. */
    public MapConfig() {
        this(80, 32, 5000, 200, 30000, 100, 200);
    }

    /** Static factory equivalent to {@link #DEFAULT}. */
    public static MapConfig defaults() {
        return DEFAULT;
    }
}
