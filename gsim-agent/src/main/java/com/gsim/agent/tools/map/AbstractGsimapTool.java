package com.gsim.agent.tools.map;

import com.gsim.agentlib.mcp.GsimRequestContext;
import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.map.service.MapService;

/**
 * Abstract base class for all GSimap tools.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #mapService} — shared map data service</li>
 *   <li>{@link #requiresWorldId()} — defaults to {@code true} (all map tools need worldId)</li>
 *   <li>{@link #resolveWorldId(ToolCall)} — unified worldId resolution: context first, param fallback</li>
 * </ul>
 */
public abstract class AbstractGsimapTool implements AgentTool {

    protected final MapService mapService;

    protected AbstractGsimapTool(MapService mapService) {
        this.mapService = mapService;
    }

    /** All GSimap tools operate on world-scoped map data. */
    @Override
    public boolean requiresWorldId() {
        return true;
    }

    /**
     * Resolve worldId from shared context, falling back to call params.
     *
     * @param call the tool call
     * @return resolved worldId
     * @throws IllegalArgumentException if worldId is missing or blank
     */
    protected String resolveWorldId(ToolCall call) {
        String worldId = GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
        }
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("worldId is required for " + name());
        }
        return worldId;
    }
}
