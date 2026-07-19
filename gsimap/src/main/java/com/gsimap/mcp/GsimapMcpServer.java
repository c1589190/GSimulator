package com.gsimap.mcp;

import com.gsimap.service.MapService;
import com.gsim.mcp.AbstractMcpServer;
import com.gsim.mcp.GsimMcpToolRegistry;
import com.gsim.mcp.McpToolRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gsimap MCP (Model Context Protocol) server over stdio.
 *
 * <p>Extends {@link AbstractMcpServer} for JSON-RPC 2.0 protocol handling.
 * Merges both Gsimap map tools ({@code gsimap_*}) and GSim world/document
 * management tools ({@code gsim_*}) via the standard {@link McpToolRegistry} interface.
 *
 * <p>All Gsimap tools are prefixed "gsimap_", GSim tools prefixed "gsim_".
 */
public class GsimapMcpServer extends AbstractMcpServer {

    private static final Logger log = LoggerFactory.getLogger(GsimapMcpServer.class);

    private final McpToolRegistry gsimapRegistry;
    private final McpToolRegistry gsimRegistry;

    /**
     * Creates an MCP server with both Gsimap and GSim tool registries.
     *
     * @param mapService the shared map service instance
     * @param importDir  directory for importing GSim worlds
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // MapService is a shared service class, not a data object
    public GsimapMcpServer(MapService mapService, Path importDir) {
        super(List.of()); // registries set below after construction
        this.gsimapRegistry = new GsimapMcpToolRegistry(mapService);
        GsimMcpToolRegistry rawGsim = new GsimMcpToolRegistry(mapService.getWorldsDir(), importDir, null);
        this.gsimRegistry = rawGsim.asMcpRegistry();
    }

    // ── AbstractMcpServer template methods ──────────────────

    @Override
    protected String getServerName() {
        return "Gsimap";
    }

    @Override
    protected String getServerVersion() {
        return "0.1.0";
    }

    @Override
    protected List<com.gsim.mcp.ToolDef> getAllTools() {
        List<com.gsim.mcp.ToolDef> gsimapTools = gsimapRegistry.all();
        List<com.gsim.mcp.ToolDef> gsimTools = gsimRegistry.all();
        List<com.gsim.mcp.ToolDef> all = new java.util.ArrayList<>(gsimapTools);
        all.addAll(gsimTools);
        log.info("tools/list: {} gsimap_ + {} gsim_ = {} total", gsimapTools.size(), gsimTools.size(), all.size());
        return all;
    }

    @Override
    protected String executeTool(String name, com.fasterxml.jackson.databind.JsonNode args) throws Exception {
        // Route: gsim_* tools go to GSim registry, gsimap_* to Gsimap
        if (name.startsWith("gsim_")) {
            return gsimRegistry.execute(name, args);
        }
        return gsimapRegistry.execute(name, args);
    }

    // ── Lifecycle ───────────────────────────────────────────

    /**
     * Signals the server loop to stop and closes stdin.
     */
    @Override
    public void stop() {
        log.info("[MCP-LIFECYCLE] Gsimap MCP server stop requested");
        super.stop();
    }
}
