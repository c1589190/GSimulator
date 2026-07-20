package com.gsim.mcp;

import com.gsim.app.ApplicationContext;
import com.gsim.tool.ToolRegistry;
import java.nio.file.Path;

/**
 * GSimulator MCP (Model Context Protocol) JSON-RPC 2.0 server over stdio.
 *
 * <p>Extends {@link AbstractMcpServer} and uses {@link ToolRegistry} as the
 * single source of truth for all tool definitions. Tools are exposed via the
 * {@link ToolRegistryMcpAdapter} which maps {@link com.gsim.tool.AgentTool}
 * instances to MCP {@link ToolDef} entries automatically.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // From ToolRegistry directly
 *   GsimMcpServer server = new GsimMcpServer(toolRegistry);
 *   server.start();  // blocking, reads from stdin, writes to stdout
 *
 *   // From ApplicationContext
 *   GsimMcpServer server = new GsimMcpServer(applicationContext);
 *   server.start();
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
     * Creates an MCP server using the ToolRegistry from an ApplicationContext.
     *
     * @param ctx the application context
     */
    public GsimMcpServer(ApplicationContext ctx) {
        this(ctx.getToolRegistry());
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

    // ── Standalone entry point (deprecated) ───────────────────

    /**
     * @deprecated Use {@code com.gsim.Main --no-cli} instead.
     *             This standalone entry point will be removed in a future version.
     */
    @Deprecated
    public static void main(String[] args) {
        System.err.println("[MCP] WARNING: GsimMcpServer.main() is deprecated. Use 'java -jar GSimulator.jar --no-cli' instead.");
        if (args.length < 1) {
            System.err.println("Usage: gsim-mcp <worldsDir> [importDir]");
            System.exit(1);
        }
        Path worldsDir = Path.of(args[0]);
        Path importDir = args.length >= 2 ? Path.of(args[1]) : null;

        ToolRegistry toolRegistry = com.gsim.mcp.McpStandaloneToolRegistry.create(worldsDir, importDir);
        startMapHttpServer(worldsDir, importDir);

        GsimMcpServer server = new GsimMcpServer(toolRegistry);
        server.start();
    }

    /**
     * @deprecated Map HTTP server is now started by Main.java directly.
     */
    @Deprecated
    private static void startMapHttpServer(Path worldsDir, Path importDir) {
        try {
            Class<?> mapServiceClass = Class.forName("com.gsimap.service.MapService");
            Class<?> httpServerClass = Class.forName("com.gsimap.http.GsimapHttpServer");
            Object mapService = mapServiceClass.getConstructor(Path.class).newInstance(worldsDir);
            int port = Integer.parseInt(
                    System.getProperty("gsimap.port", System.getenv().getOrDefault("GSIMAP_PORT", "8711")));
            Object httpServer =
                    httpServerClass.getConstructor(int.class, mapServiceClass).newInstance(port, mapService);
            httpServerClass.getMethod("start").invoke(httpServer);
            System.err.println("[MCP] Gsimap map server started on http://127.0.0.1:" + port);
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    httpServerClass.getMethod("stop").invoke(httpServer);
                } catch (Exception ignored) {
                }
            }));
        } catch (ClassNotFoundException e) {
            // gsimap not on classpath — skip map HTTP server
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Failed to start Gsimap HTTP server: " + e.getMessage());
        }
    }

    /**
     * @deprecated Map tools are now registered by Main.java directly via GsimapToolRegistrar.
     */
    @Deprecated
    private static void tryRegisterMapTools(ToolRegistry registry, Path worldsDir) {
        try {
            Class<?> mapServiceClass = Class.forName("com.gsimap.service.MapService");
            Class<?> registrarClass = Class.forName("com.gsimap.tool.GsimapToolRegistrar");
            Object mapService = mapServiceClass.getConstructor(Path.class).newInstance(worldsDir);
            registrarClass
                    .getMethod("registerAll", ToolRegistry.class, mapServiceClass)
                    .invoke(null, registry, mapService);
            System.err.println("[MCP] Registered GSimap map tools via reflection");
        } catch (ClassNotFoundException e) {
            // gsimap not on classpath — that's fine, MCP works without map tools
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Failed to load GSimap map tools: " + e.getMessage());
        }
    }
}
