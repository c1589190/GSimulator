package com.gsimap.mcp;

import com.gsimap.service.MapService;
import com.gsim.mcp.AbstractMcpServer;
import com.gsim.mcp.GsimMcpToolRegistry;
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
 * management tools ({@code gsim_*}). AbstractMcpServer handles routing internally.
 *
 * <p>All Gsimap tools are prefixed "gsimap_", GSim tools prefixed "gsim_".
 */
public class GsimapMcpServer extends AbstractMcpServer {

    private static final Logger log = LoggerFactory.getLogger(GsimapMcpServer.class);

    /**
     * Creates an MCP server with both Gsimap and GSim tool registries.
     *
     * @param mapService the shared map service instance
     * @param importDir  directory for importing GSim worlds
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public GsimapMcpServer(MapService mapService, Path importDir) {
        super(List.of(
                new GsimapMcpToolRegistry(mapService),
                new GsimMcpToolRegistry(mapService.getWorldsDir(), importDir, null).asMcpRegistry()));
    }

    @Override
    protected String getServerName() {
        return "Gsimap";
    }

    @Override
    protected String getServerVersion() {
        return "0.1.0";
    }

    @Override
    public void stop() {
        log.info("[MCP-LIFECYCLE] Gsimap MCP server stop requested");
        super.stop();
    }
}
