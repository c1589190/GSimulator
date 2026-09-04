package com.gsim.agentsmanager.mcp;

import com.gsim.agentsmanager.mcp.AbstractMcpServer;
import com.gsim.agentsmanager.mcp.McpTransport;
import com.gsim.agentsmanager.mcp.StdioMcpTransport;
import com.gsim.agentsmanager.mcp.ToolDef;
import com.gsim.agentsmanager.mcp.ToolRegistryMcpAdapter;
import com.gsim.agentsmanager.tool.ToolRegistry;

/**
 * GSimulator MCP (Model Context Protocol) JSON-RPC 2.0 server over stdio.
 *
 * <p>Extends {@link AbstractMcpServer} and uses {@link ToolRegistry} as the
 * single source of truth for all tool definitions. Tools are exposed via the
 * {@link ToolRegistryMcpAdapter} which maps {@link com.gsim.agentsmanager.tool.AgentTool}
 * instances to MCP {@link ToolDef} entries automatically.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   GsimMcpServer server = new GsimMcpServer(toolRegistry);
 *   server.start();  // blocking, reads from stdin, writes to stdout
 * }</pre>
 */
public class GsimMcpServer extends AbstractMcpServer {

    private final ToolRegistry toolRegistry;

    /**
     * Creates an MCP server backed by the given ToolRegistry.
     * Uses default {@link StdioMcpTransport} (captures System.out at call time).
     *
     * @param toolRegistry the tool registry (must not be null)
     */
    public GsimMcpServer(ToolRegistry toolRegistry) {
        super(toolRegistry);
        this.toolRegistry = toolRegistry;
    }

    /**
     * Creates an MCP server with explicit transport.
     * Use this when System.out has been redirected and you need to pass the
     * original stdout for clean JSON-RPC output.
     *
     * @param toolRegistry the tool registry (must not be null)
     * @param transport    the transport layer (must not be null)
     */
    public GsimMcpServer(ToolRegistry toolRegistry, McpTransport transport) {
        super(toolRegistry, transport);
        this.toolRegistry = toolRegistry;
    }

    /**
     * Creates an MCP server with active world tracking.
     * Worldinfo tools will validate that caller-provided worldId matches the active world.
     *
     * @param toolRegistry   the tool registry (must not be null)
     * @param activeWorldId  supplier for the active world ID (may be null)
     */
    public GsimMcpServer(ToolRegistry toolRegistry, java.util.function.Supplier<String> activeWorldId) {
        super(toolRegistry, activeWorldId);
        this.toolRegistry = toolRegistry;
    }

    /**
     * Creates an MCP server with active world tracking and explicit transport.
     *
     * @param toolRegistry   the tool registry (must not be null)
     * @param activeWorldId  supplier for the active world ID (may be null)
     * @param transport      the transport layer (must not be null)
     */
    public GsimMcpServer(
            ToolRegistry toolRegistry, java.util.function.Supplier<String> activeWorldId, McpTransport transport) {
        super(toolRegistry, activeWorldId, transport);
        this.toolRegistry = toolRegistry;
    }

    // ── AbstractMcpServer template methods ───────────────────

    @Override
    protected String getServerName() {
        return "GSimulator-MCP";
    }

    @Override
    protected String getServerVersion() {
        return "0.1.0";
    }

    // ── Public API ──────────────────────────────────────────

    /** Returns the underlying ToolRegistry. */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
}
