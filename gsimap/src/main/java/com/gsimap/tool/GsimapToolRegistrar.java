package com.gsimap.tool;

import com.gsim.tool.ToolRegistry;
import com.gsimap.service.MapService;

/**
 * Registers all gsimap AgentTools into a ToolRegistry.
 *
 * <p>Call {@link #registerAll(ToolRegistry, MapService)} during application
 * startup to make all 19 gsimap map tools available to the agent system.
 */
public final class GsimapToolRegistrar {

    private GsimapToolRegistrar() {}

    /**
     * Register all 20 gsimap tools into the given registry.
     *
     * @param registry   the tool registry to register into
     * @param mapService the shared MapService instance
     */
    public static void registerAll(ToolRegistry registry, MapService mapService) {
        // ── Query tools (8) ─────────────────────────────────
        registry.register(new GsimapGetHexTool(mapService));
        registry.register(new GsimapGetProvinceTool(mapService));
        registry.register(new GsimapGetNeighborsTool(mapService));
        registry.register(new GsimapQueryRadiusTool(mapService));
        registry.register(new GsimapGetCitiesTool(mapService));
        registry.register(new GsimapFindRiverPathTool(mapService));
        registry.register(new GsimapListRegionsTool(mapService));
        registry.register(new GsimapGetDistanceTool(mapService));

        // ── Address resolution (1) ──────────────────────────
        registry.register(new GsimapQueryByAddressTool(mapService));

        // ── Diff tools (2) ──────────────────────────────────
        registry.register(new GsimapGetDiffTool(mapService));
        registry.register(new GsimapGetHistoryTool(mapService));

        // ── Region CRUD tools (6) ───────────────────────────
        registry.register(new GsimapUpdateRegionTool(mapService));
        registry.register(new GsimapAddHexToRegionTool(mapService));
        registry.register(new GsimapRemoveHexFromRegionTool(mapService));
        registry.register(new GsimapCreateRegionTool(mapService));
        registry.register(new GsimapDeleteRegionTool(mapService));
        registry.register(new GsimapRenameRegionTool(mapService));

        // ── Init tools (3) ──────────────────────────────────
        registry.register(new GsimapGenerateTool(mapService));
        registry.register(new GsimapInitNationTool(mapService));
        registry.register(new GsimapUpdateTerrainTypeTool(mapService));
    }
}
