package com.gsim.map.http;

import com.gsim.map.config.MapConfig;
import com.gsim.map.service.MapService;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gsimap HTTP server — serves the map API and static web editor.
 */
public class GsimapHttpServer {

    private static final Logger log = LoggerFactory.getLogger(GsimapHttpServer.class);
    private final int port;
    private final MapService mapService;
    private final MapConfig mapConfig;
    private HttpServer server;

    /**
     * Creates an HTTP server that serves the map API and static web editor.
     *
     * @param port      listening port (bound to 127.0.0.1)
     * @param mapService shared map service instance
     */
    public GsimapHttpServer(int port, MapService mapService) {
        this(port, mapService, MapConfig.defaults());
    }

    /**
     * Creates an HTTP server that serves the map API and static web editor.
     *
     * @param port      listening port (bound to 127.0.0.1)
     * @param mapService shared map service instance
     * @param mapConfig configurable map limits (default radius, contour cache, etc.)
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public GsimapHttpServer(int port, MapService mapService, MapConfig mapConfig) {
        this.port = port;
        this.mapService = mapService;
        this.mapConfig = mapConfig;
    }

    /**
     * Starts the HTTP server on the configured port and registers API and
     * static-file handlers.
     *
     * @throws IOException if the underlying server socket cannot be created
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/map", new MapWebUIHandler(mapService, mapConfig));
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("HTTP server started at http://127.0.0.1:{}", port);
    }

    /**
     * Stops the HTTP server gracefully, waiting up to 1 second for
     * in-flight requests to complete.
     */
    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("HTTP server stopped");
        }
    }
}
