package com.gsim.agent.tools.map;

import com.gsim.agent.tools.search.SearchToolContext;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.map.service.MapService;

/**
 * Registers all gsimap AgentTools into a ToolRegistry.
 *
 * <p>Call {@link #registerAll(ToolRegistry, MapService, SearchToolContext)} during application
 * startup to make all 25 gsimap map tools available to the agent system.
 */
public final class GsimapToolRegistrar {

    private GsimapToolRegistrar() {}

    /**
     * Register all 25 gsimap tools into the given registry.
     *
     * @param registry   the tool registry to register into
     * @param mapService the shared MapService instance
     * @param searchCtx  the shared search tool context（T8 接线传递；T9 renameRegion 传播
     *                   已消费——GsimapRenameRegionTool 借此改写 LinkIndex 关联；其余
     *                   24 个 gsimap 工具注册行为不变）
     */
    public static void registerAll(ToolRegistry registry, MapService mapService, SearchToolContext searchCtx) {
        // ── Query tools (9) ─────────────────────────────────
        registry.register(new GsimapGetHexTool(mapService));
        registry.register(new GsimapGetProvinceTool(mapService));
        registry.register(new GsimapGetNeighborsTool(mapService));
        registry.register(new GsimapQueryRadiusTool(mapService));
        registry.register(new GsimapRenderTextTool(mapService));
        registry.register(new GsimapGetCitiesTool(mapService));
        registry.register(new GsimapFindRiverPathTool(mapService));
        registry.register(new GsimapListRegionsTool(mapService));
        registry.register(new GsimapGetDistanceTool(mapService));

        // ── Address resolution (1) ──────────────────────────
        registry.register(new GsimapQueryByAddressTool(mapService));

        // ── Diff tools (2) ──────────────────────────────────
        registry.register(new GsimapGetDiffTool(mapService));
        registry.register(new GsimapGetHistoryTool(mapService));

        // ── Region CRUD tools (7) ───────────────────────────
        registry.register(new GsimapUpdateRegionTool(mapService));
        registry.register(new GsimapAddHexToRegionTool(mapService));
        registry.register(new GsimapRemoveHexFromRegionTool(mapService));
        registry.register(new GsimapCreateRegionTool(mapService));
        registry.register(new GsimapDeleteRegionTool(mapService));
        registry.register(new GsimapRenameRegionTool(mapService, searchCtx));
        registry.register(new GsimapMergeRegionsTool(mapService));

        // ── Edge pathway tools (4) ────────────────────────────
        registry.register(new GsimapEdgeSetTool(mapService));
        registry.register(new GsimapEdgeGetTool(mapService));
        registry.register(new GsimapEdgeRemoveTool(mapService));
        registry.register(new GsimapEdgeListTool(mapService));

        // ── Init tools (2) ──────────────────────────────────
        registry.register(new GsimapGenerateTool(mapService));
        registry.register(new GsimapUpdateTerrainTypeTool(mapService));
    }
}
