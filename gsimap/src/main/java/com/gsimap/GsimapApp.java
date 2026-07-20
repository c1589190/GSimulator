package com.gsimap;

import com.gsimap.config.GsimapConfig;
import com.gsimap.http.GsimapHttpServer;
import com.gsimap.service.MapService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gsimap entry point — hex map editor HTTP server.
 *
 * <p>This is a standalone HTTP-only launcher for the map Web UI.
 * MCP functionality is handled by the GSimulator core application
 * ({@code GSimulatorApplication --mcp}), which exposes all tools
 * (including map tools registered via {@link com.gsimap.tool.GsimapToolRegistrar})
 * through a unified MCP protocol layer.
 *
 * <p>Usage:
 * <pre>
 *   java -jar gsimap.jar                      → HTTP (default)
 *   -Dgsimap.worldsDir=/path/to/worlds        → custom worlds dir
 *   -Dgsimap.port=8711                        → custom HTTP port
 * </pre>
 */
public final class GsimapApp {

    private static final Logger log = LoggerFactory.getLogger(GsimapApp.class);

    /** Private constructor to prevent instantiation of utility class. */
    private GsimapApp() {}

    /**
     * Application entry point. Initialises MapService and starts the
     * HTTP server for the map Web UI.
     *
     * @param args command-line arguments
     * @throws Exception if server initialisation fails critically
     */
    @SuppressFBWarnings("THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION")
    public static void main(String[] args) throws Exception {
        GsimapConfig config = parseArgs(args);
        MapService mapService = new MapService(config.worldsDir());

        log.info("Gsimap v0.1.0 starting...");
        log.info("  worlds dir: {}", config.worldsDir());
        log.info("  HTTP port: {}", config.httpPort());

        // Start HTTP server
        GsimapHttpServer httpServer = new GsimapHttpServer(config.httpPort(), mapService);
        httpServer.start();
        log.info("Gsimap map server started on http://127.0.0.1:{}", config.httpPort());

        final GsimapHttpServer hs = httpServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            hs.stop();
        }));

        log.info("Gsimap ready. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    @SuppressFBWarnings("DM_EXIT")
    private static GsimapConfig parseArgs(String[] args) {
        for (String arg : args) {
            switch (arg) {
                case "--help", "-h" -> {
                    System.out.println(
                            """
                        Gsimap — Hex map editor for GSim

                        Usage: java -jar gsimap.jar [options]

                        System properties:
                          -Dgsimap.worldsDir=<path>   GSim worlds directory (default: ./worlds)
                          -Dgsimap.port=<port>        HTTP port (default: 8711)
                        """);
                    System.exit(0);
                }
                default -> {
                    /* no action */
                }
            }
        }

        return GsimapConfig.load();
    }
}
