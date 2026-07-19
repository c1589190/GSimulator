package com.gsimap;

import com.gsim.app.AppConfig;
import com.gsim.app.GSimulatorApplication;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches an embedded GSimulator HTTP API in the same JVM.
 *
 * <p>This provides the HTTP endpoint that {@code gsim_*} LLM/Agent MCP tools
 * communicate with when no {@code ApplicationContext} is available.
 */
public final class GsimEmbeddedLauncher {

    private static final Logger log = LoggerFactory.getLogger(GsimEmbeddedLauncher.class);

    private GsimEmbeddedLauncher() {}

    /**
     * Start an embedded GSimulator HTTP API server on the given port.
     *
     * @param worldsDir path to the GSim worlds directory
     * @param importDir path to the import documents directory (may be null)
     * @param port      HTTP port for the API server
     * @return the running application instance, or null if startup failed
     */
    public static GSimulatorApplication launch(Path worldsDir, Path importDir, int port) {
        System.setProperty("api.port", String.valueOf(port));
        System.setProperty("api.enabled", "true");
        System.setProperty("worlds.dir", worldsDir.toAbsolutePath().toString());
        if (importDir != null) {
            System.setProperty("import.dir", importDir.toAbsolutePath().toString());
        }
        try {
            AppConfig gsimConfig = new AppConfig(
                    new com.gsim.config.ConfigLoader(new String[0]).load());
            GSimulatorApplication app = new GSimulatorApplication(gsimConfig, false, true);
            new Thread(
                            () -> {
                                try {
                                    app.start();
                                } catch (Exception e) {
                                    log.error("GSim embed failed", e);
                                }
                            },
                            "gsim-embed")
                    .start();
            Thread.sleep(2000);
            log.info("GSimulator HTTP API embedded on port {}", port);
            return app;
        } catch (Exception e) {
            log.warn("Failed to start embedded GSimulator: {}", e.getMessage());
            return null;
        }
    }
}
