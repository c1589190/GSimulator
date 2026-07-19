package com.gsimap;

import com.gsimap.config.GsimapConfig;
import com.gsimap.http.GsimapHttpServer;
import com.gsimap.mcp.GsimapMcpServer;
import com.gsimap.service.MapService;
import com.gsim.app.GSimulatorApplication;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gsimap entry point.
 *
 * <p>Usage:
 * <pre>
 *   java -jar gsimap.jar                    → HTTP + MCP (default)
 *   java -jar gsimap.jar --http-only        → HTTP only
 *   java -jar gsimap.jar --mcp-only         → MCP stdio only
 *   java -Dgsimap.worldsDir=/path/to/worlds → custom worlds dir
 *   java -Dgsimap.port=8711                 → custom HTTP port
 * </pre>
 */
public final class GsimapApp {

    private static final Logger log = LoggerFactory.getLogger(GsimapApp.class);

    /** Private constructor to prevent instantiation of utility class. */
    private GsimapApp() {}

    /**
     * Application entry point. Parses CLI arguments, initialises MapService,
     * optionally starts the embedded GSimulator API, then starts the HTTP
     * server and/or MCP server according to the resolved configuration.
     *
     * @param args command-line arguments (--http-only, --mcp-only, --help)
     * @throws Exception if server initialisation fails critically
     */
    @SuppressFBWarnings("THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION")
    public static void main(String[] args) throws Exception {
        GsimapConfig config = parseArgs(args);
        MapService mapService = new MapService(config.worldsDir());

        log.info("Gsimap v0.1.0 starting...");
        log.info("  worlds dir: {}", config.worldsDir());
        log.info("  HTTP mode: {} (port {})", config.httpMode(), config.httpPort());
        log.info("  MCP mode: {}", config.mcpMode());

        // ── Embedded GSimulator HTTP API server (for LLM/Agent MCP tools) ──
        GSimulatorApplication gsimApp = null;
        if (!Boolean.parseBoolean(System.getProperty("gsimap.noGsim", "false"))) {
            int gsimPort = config.gsimPort();
            gsimApp = GsimEmbeddedLauncher.launch(config.worldsDir(), config.importDir(), gsimPort);
        }

        // Start HTTP server
        GsimapHttpServer httpServer = null;
        if (config.httpMode()) {
            httpServer = new GsimapHttpServer(config.httpPort(), mapService);
            httpServer.start();
        }

        // Start MCP server (on main thread if MCP-only, background thread otherwise)
        GsimapMcpServer mcpServer = new GsimapMcpServer(mapService, config.importDir());
        if (config.mcpMode() && !config.httpMode()) {
            // MCP-only: run on main thread (blocking)
            mcpServer.start();
        } else if (config.mcpMode()) {
            // HTTP + MCP: MCP in background thread
            Thread mcpThread = new Thread(mcpServer, "mcp-stdio");
            mcpThread.setDaemon(true);
            mcpThread.start();
            log.info("MCP server running in background thread");
        }

        // If HTTP-only or HTTP+MCP, keep main thread alive
        if (config.httpMode()) {
            final GsimapHttpServer hs = httpServer;
            final GsimapMcpServer ms = mcpServer;
            final GSimulatorApplication gs = gsimApp;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                if (hs != null) hs.stop();
                ms.stop();
                if (gs != null) gs.stop();
            }));
            log.info("Gsimap ready. Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }

    @SuppressFBWarnings("DM_EXIT")
    private static GsimapConfig parseArgs(String[] args) {
        boolean httpOnly = false;
        boolean mcpOnly = false;

        for (String arg : args) {
            switch (arg) {
                case "--http-only" -> httpOnly = true;
                case "--mcp-only" -> mcpOnly = true;
                case "--help", "-h" -> {
                    System.out.println(
                            """
                        Gsimap — Hex map editor and MCP bridge for GSim

                        Usage: java -jar gsimap.jar [options]

                        Options:
                          --http-only     Start HTTP server only (no MCP)
                          --mcp-only      Start MCP stdio server only (no HTTP)
                          --help, -h      Show this help

                        System properties:
                          -Dgsimap.worldsDir=<path>   GSim worlds directory (default: ./worlds)
                          -Dgsimap.importDir=<path>   GSim import/docs directory (default: ./import)
                          -Dgsimap.port=<port>        HTTP port (default: 8711)
                          -Dgsimap.gsimPort=<port>    GSim embedded API port (default: 8710)
                          -Dgsimap.noGsim=true        Disable embedded GSim API
                        """);
                    System.exit(0);
                }
                default -> {
                    /* no action */
                }
            }
        }

        if (httpOnly) System.setProperty("gsimap.httpOnly", "true");
        if (mcpOnly) System.setProperty("gsimap.mcpOnly", "true");

        return GsimapConfig.load();
    }
}
